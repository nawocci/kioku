package eu.kanade.tachiyomi.sync

import android.os.Build
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.preferenceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.domain.source.service.SourceManager
import xyz.altaf.kioku.sync.Clock
import xyz.altaf.kioku.sync.RecordTypes
import xyz.altaf.kioku.sync.compareClocks
import xyz.altaf.kioku.sync.db.SyncDatabase
import xyz.altaf.kioku.sync.proto.Ack
import xyz.altaf.kioku.sync.proto.Change
import xyz.altaf.kioku.sync.proto.Frame
import xyz.altaf.kioku.sync.proto.Pong
import xyz.altaf.kioku.sync.proto.RecordKey
import xyz.altaf.kioku.sync.proto.SnapshotResponse
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock as WallClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Drives sync sessions: connects, pairs, drains the outbox, and applies
 * incoming events. One session runs at a time per device — the mutex makes
 * the live loop and the background worker mutually exclusive, so outbox rows
 * are never drained by two sessions and the device clock never issues the
 * same value twice. The app keeps the live session alive while it is in
 * use; the worker runs short catch-up sessions otherwise.
 */
@Inject
@SingleIn(AppScope::class)
class SyncEngine(
    private val database: Database,
    private val syncDb: SyncDatabase,
    private val network: NetworkHelper,
    private val sourceManager: SourceManager,
    private val resolver: ChangeResolver,
    private val applier: ChangeApplier,
    private val prefWatcher: SyncPrefWatcher,
    @AppCoroutineScope private val scope: CoroutineScope,
) {

    private var sessionJob: Job? = null

    /** The pairing session; once live, it is also the device's live session. */
    private var pairingJob: Job? = null

    /** Wakes a live session's idle wait when the outbox gains rows. */
    private val outboxSignal = Channel<Unit>(Channel.CONFLATED)
    private var outboxWatcher: Job? = null
    /** One session at a time per device; see the class comment. */
    private val sessionMutex = Mutex()

    /** What the sync UI shows. */
    val status: kotlinx.coroutines.flow.StateFlow<SyncStatus>
        field = kotlinx.coroutines.flow.MutableStateFlow<SyncStatus>(SyncStatus.Idle)

    private var mergeConfirmation: CompletableDeferred<Boolean>? = null
    private var pairingReview: CompletableDeferred<Boolean>? = null

    // ------------------------------------------------------------- lifecycle

    /** Starts the live session loop while the app is in use. */
    fun start() {
        startOutboxWatcher()
        if (sessionJob?.isActive == true) return
        sessionJob = scope.launch { runLoop(interactive = true) }
    }

    /**
     * Watches the outbox table and signals whenever it holds rows, so a live
     * session pushes local writes as they happen instead of waiting for the
     * next inbound frame (a quiet server sends none).
     */
    private fun startOutboxWatcher() {
        if (outboxWatcher?.isActive == true) return
        outboxWatcher = scope.launch {
            database.syncQueries.getOutbox(1)
                .asFlow()
                .mapToList(Dispatchers.IO)
                .collect { rows -> if (rows.isNotEmpty()) outboxSignal.trySend(Unit) }
        }
    }

    /** Stops the foreground loop; the socket closes when the session ends. */
    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        // A pairing session is the live session too; it holds the socket and
        // the session mutex, so the next start() would block on the mutex
        // forever and the status would stay stuck at Idle.
        pairingJob?.cancel()
        pairingJob = null
        prefWatcher.stop()
        status.value = SyncStatus.Idle
    }

    /** Runs one catch-up session when no other session is active. */
    suspend fun syncOnce() {
        if (!isPaired()) return
        if (!sessionMutex.tryLock()) return // the live loop is already at it
        try {
            runSession(interactive = false)
        } finally {
            sessionMutex.unlock()
        }
    }

    private suspend fun runLoop(interactive: Boolean) {
        // Watch preferences for the whole loop, so changes between sessions
        // (reconnect pauses, error backoffs) are still captured.
        prefWatcher.start(sourcePrefKeys())
        try {
            var backoff = 5.seconds
            while (coroutineContext.isActive) {
                try {
                    sessionMutex.withLock {
                        if (!runSession(interactive)) {
                            status.value = SyncStatus.NotPaired
                            return
                        }
                    }
                    backoff = 5.seconds
                    delay(30.seconds)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "KiokuSync session failed, retrying in $backoff: ${e.message}" }
                    status.value = SyncStatus.Error(e.message ?: e.javaClass.simpleName)
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(10.minutes)
                }
            }
        } finally {
            prefWatcher.stop()
        }
    }

    // ------------------------------------------------------------- pairing

    /**
     * Pairs with a console-minted key: the server operator creates the key
     * with `kioku-sync key` and pastes it here. Runs on the app scope, so
     * leaving the settings screen does not cancel it.
     */
    fun pairWithKey(serverUrl: String, key: String, onDone: (Result<Unit>) -> Unit) {
        pairingJob?.cancel()
        pairingJob = scope.launch {
            val result = runCatching {
                val url = serverUrl.trim().trimEnd('/')
                syncDb.syncQueries.upsertServer(url, key.trim())
                status.value = SyncStatus.Connecting
                sessionMutex.withLock { runSession(interactive = true) }
                Unit
            }
            when (val e = result.exceptionOrNull()) {
                null -> {
                    onDone(result)
                    if (isPaired()) {
                        // A live pairing session only returns when its
                        // connection ended; the run loop takes over with its
                        // reconnect backoff.
                        sessionJob?.cancel()
                        sessionJob = scope.launch { runLoop(interactive = true) }
                    }
                }
                // The pairing job itself was cancelled (stop() or the user
                // closing the dialog): nothing to report, the caller already
                // reset the status.
                is CancellationException -> if (coroutineContext.isActive) {
                    // A withTimeout inside the session, not our cancellation:
                    // a real failure the UI must see.
                    status.value = SyncStatus.Error(e.message ?: e.javaClass.simpleName)
                    onDone(result)
                }
                else -> {
                    status.value = SyncStatus.Error(e.message ?: e.javaClass.simpleName)
                    onDone(result)
                }
            }
        }
    }

    /**
     * Cancels an in-flight pairing, e.g. when the user closes the pairing
     * dialog. A pairing that already completed (and became the live session)
     * is left running.
     */
    fun cancelPairing() {
        val job = pairingJob ?: return
        val busy = status.value.let {
            it is SyncStatus.Connecting || it is SyncStatus.ApplyingSnapshot ||
                it is SyncStatus.UploadingLibrary || it is SyncStatus.MergeConfirmation ||
                it is SyncStatus.AwaitingReview
        }
        if (!busy) return
        job.cancel()
        pairingJob = null
        status.value = SyncStatus.Idle
    }

    /** Answers the merge confirmation: proceed or cancel the pairing. */
    fun resolveMergeConfirmation(merge: Boolean) {
        mergeConfirmation?.complete(merge)
        mergeConfirmation = null
    }

    /** Answers the pairing review: keep the server entries or remove them everywhere. */
    fun resolvePairing(removeEverywhere: Boolean) {
        pairingReview?.complete(removeEverywhere)
        pairingReview = null
    }

    /** Forgets the server and stops syncing; local data stays untouched. */
    suspend fun unpair() {
        stop()
        // Wait briefly for an in-flight session to end, so credentials do
        // not vanish under a running session; if it does not finish in time,
        // the session's own failure handling takes care of the rest.
        withTimeoutOrNull(5.seconds) { sessionMutex.withLock {} }
        syncDb.syncQueries.clearServer()
        syncDb.syncQueries.clearRecords()
        database.syncQueries.clearOutbox()
        status.value = SyncStatus.NotPaired
    }

    /**
     * Runs a local maintenance action (a database clear) without capturing
     * its writes: the action stays local, and the next session re-pulls the
     * server state from a snapshot instead of propagating the clear.
     */
    suspend fun localMaintenance(action: suspend () -> Unit) {
        database.syncQueries.setSuppress(SUPPRESS_ON)
        try {
            action()
        } finally {
            database.syncQueries.setSuppress(SUPPRESS_OFF)
            syncDb.syncQueries.setLastSeq(0)
            syncDb.syncQueries.clearRecords()
            database.syncQueries.clearOutbox()
            // Category mappings minted before the clear can point at rows
            // that no longer exist; dropping them lets the server state
            // recreate its categories here.
            database.syncQueries.clearCategoryMap()
        }
    }

    /** Whether a server, key, and completed pairing are in place. */
    suspend fun isPaired(): Boolean =
        syncDb.syncQueries.getMeta().awaitAsOne().paired

    /** The configured server, for the settings screen. */
    suspend fun serverUrl(): String? = syncDb.syncQueries.getMeta().awaitAsOne().server_url

    /** The API key, shown only when the user asks to share it with another device. */
    suspend fun apiKey(): String? = syncDb.syncQueries.getMeta().awaitAsOne().api_key

    /** The device label other devices and the dashboard see. */
    suspend fun deviceName(): String =
        syncDb.syncQueries.getMeta().awaitAsOne().device_name ?: Build.MODEL

    suspend fun setDeviceName(name: String) {
        syncDb.syncQueries.setDeviceName(name)
    }

    /** Whether private settings ride along. */
    suspend fun syncPrivate(): Boolean = syncDb.syncQueries.getMeta().awaitAsOne().sync_private

    suspend fun setSyncPrivate(enabled: Boolean) {
        syncDb.syncQueries.setSyncPrivate(enabled)
    }

    /** Runs one catch-up session in the background of the app. */
    fun syncNow() {
        scope.launch { runCatching { syncOnce() } }
    }

    // ------------------------------------------------------------- session

    /** Returns false when there is no server to talk to. */
    private suspend fun runSession(interactive: Boolean): Boolean {
        val meta = syncDb.syncQueries.getMeta().awaitAsOne()
        val serverUrl = meta.server_url ?: return false
        val apiKey = meta.api_key ?: return false
        val deviceId = syncDb.syncQueries.getDeviceId().awaitAsOne()
        val deviceName = meta.device_name ?: Build.MODEL

        // A background session never runs before the interactive pairing
        // completed: it would apply the snapshot without the merge
        // confirmation and the review.
        if (!interactive && !meta.paired) return true

        status.value = SyncStatus.Connecting
        val connection = SyncConnection(network, serverUrl, apiKey)
        val hello = try {
            connection.open(
                SyncConnection.hello(deviceId, deviceName, BuildConfig.VERSION_NAME, meta.last_seq),
                timeout = 10.seconds,
            )
        } catch (e: Exception) {
            connection.close()
            throw e
        }

        logcat { "KiokuSync session opening to $serverUrl as $deviceId at seq ${meta.last_seq}" }
        var clock = Clock(meta.hlc_wall, meta.hlc_counter, deviceId)
        resolver.syncPrivate = meta.sync_private
        prefWatcher.start(sourcePrefKeys())

        try {
            if (hello.snapshotRequired) {
                connection.send(snapshotRequestFrame())
                val frame = awaitSnapshot(connection)
                clock = applySnapshot(frame.snapshotResponse!!, meta.paired, clock, interactive)
            }

            status.value = SyncStatus.Online(WallClock.System.now())
            logcat { "KiokuSync session live" }
            while (coroutineContext.isActive && connection.isOpen) {
                if (drainOneBatch(connection, clock)) continue
                if (!interactive) {
                    // A catch-up session has nothing else to wait for once
                    // drained; the live loop stays for pushes and events.
                    break
                }
                val frame = connection.awaitFrameOr(outboxSignal)
                if (frame != null) {
                    clock = handleEvents(frame, clock)
                }
            }
        } finally {
            connection.close()
            logcat { "KiokuSync session closed" }
        }
        return true
    }

    /**
     * Waits for the snapshot, answering pings meanwhile. The snapshot phase
     * carries no other frames.
     */
    private suspend fun awaitSnapshot(connection: SyncConnection): Frame {
        while (true) {
            val frame = connection.receive() ?: throw SyncException("connection closed")
            when (frame.bodyCase) {
                Frame.BodyCase.SNAPSHOT_RESPONSE -> return frame
                Frame.BodyCase.PING -> connection.send(
                    Frame.newBuilder().setPong(Pong.newBuilder().setWallMs(frame.ping.wallMs)).build(),
                )
                else -> throw SyncException("unexpected frame during pairing")
            }
        }
    }

    /**
     * Applies a snapshot: pairing merges backup-style, a catch-up snapshot
     * replaces per the clock. Returns the clock observed in it.
     */
    private suspend fun applySnapshot(
        snapshot: SnapshotResponse,
        paired: Boolean,
        clock: Clock,
        interactive: Boolean,
    ): Clock {
        val mode = if (paired) ChangeApplier.Mode.LIVE else ChangeApplier.Mode.PAIRING
        status.value = SyncStatus.ApplyingSnapshot(snapshot.recordsCount)

        if (!paired && interactive && snapshot.recordsCount > 0) {
            val localCount = localLibraryCount()
            if (localCount > 0 && !confirmMerge(snapshot.recordsCount, localCount)) {
                // Nothing was applied; the device keeps its data and the
                // server keeps its library, and the user can retry with a
                // fresh key or after clearing one side.
                throw SyncException("pairing declined: both this device and the server hold a library")
            }
        }

        val report = applier.applyBatch(snapshot.recordsList, mode)
        val observed = clock
        snapshot.recordsList.forEach { observed.observe(it.modified) }
        syncDb.syncQueries.setHlc(observed.wall, observed.counter)
        snapshot.recordsList.forEach { rememberRecord(it) }
        syncDb.syncQueries.setLastSeq(snapshot.latestSeq)

        if (paired) return observed

        if (report.entries.isNotEmpty() && interactive) {
            logcat { "KiokuSync pairing review for ${report.entries.size} server-only favorites" }
            val review = CompletableDeferred<Boolean>()
            pairingReview = review
            status.value = SyncStatus.AwaitingReview(report.entries)
            val removeEverywhere = review.await()
            pairingReview = null
            if (removeEverywhere) {
                unfavoriteEverywhere(report.entries)
            }
        }

        // Everything this device holds rides up as one dump, seeding an empty
        // key or converging a merged one; fresh clocks win everywhere.
        status.value = SyncStatus.UploadingLibrary
        database.syncQueries.clearOutbox()
        syncDb.syncQueries.clearRecords()
        seedDump()
        syncDb.syncQueries.markPaired()
        return observed
    }

    /**
     * Asks the user to confirm merging two non-empty libraries. True merges;
     * false cancels the pairing before anything is applied.
     */
    private suspend fun confirmMerge(serverEntries: Int, localEntries: Int): Boolean {
        val confirmation = CompletableDeferred<Boolean>()
        mergeConfirmation = confirmation
        status.value = SyncStatus.MergeConfirmation(serverEntries, localEntries)
        val merge = confirmation.await()
        mergeConfirmation = null
        return merge
    }

    /** Library entries this device would carry into a pairing dump. */
    private suspend fun localLibraryCount(): Int =
        database.syncQueries.getSyncMangaKeys().awaitAsList().size

    /**
     * Unfavorites the reviewed entries here and enqueues their records, so
     * the drain pushes explicit unfavorites to the server — including
     * entries with no other user state, which the seed dump alone would not
     * carry.
     */
    private suspend fun unfavoriteEverywhere(entries: List<ChangeApplier.ReviewEntry>) {
        database.transaction {
            database.syncQueries.setSuppress(SUPPRESS_ON)
            try {
                entries.forEach { entry ->
                    database.syncQueries.unfavoriteManga(entry.sourceId, entry.url)
                }
            } finally {
                database.syncQueries.setSuppress(SUPPRESS_OFF)
            }
        }
        entries.forEach { entry ->
            database.syncQueries.enqueueOutbox(
                RecordTypes.MANGA.toLong(),
                entry.sourceId,
                entry.url,
                null,
                null,
            )
        }
    }

    /**
     * Enqueues every local record worth syncing, so the drain pushes the
     * whole state after pairing.
     */
    private suspend fun seedDump() {
        for (key in database.syncQueries.getSyncMangaKeys().awaitAsList()) {
            database.syncQueries.enqueueOutbox(
                RecordTypes.MANGA.toLong(),
                key.source_id,
                key.remote_url,
                null,
                null,
            )
            database.syncQueries.getSyncChapterKeysForManga(key.source_id, key.remote_url).awaitAsList()
                .forEach { url ->
                    database.syncQueries.enqueueOutbox(
                        RecordTypes.CHAPTER.toLong(),
                        key.source_id,
                        key.remote_url,
                        url,
                        null,
                    )
                }
            database.syncQueries.getSyncHistoryKeysForManga(key.source_id, key.remote_url).awaitAsList()
                .forEach { url ->
                    database.syncQueries.enqueueOutbox(
                        RecordTypes.HISTORY.toLong(),
                        key.source_id,
                        key.remote_url,
                        url,
                        null,
                    )
                }
            database.syncQueries.getSyncTrackKeysForManga(key.source_id, key.remote_url).awaitAsList()
                .forEach { trackerId ->
                    database.syncQueries.enqueueOutbox(
                        RecordTypes.TRACK.toLong(),
                        key.source_id,
                        key.remote_url,
                        trackerId.toString(),
                        null,
                    )
                }
        }

        database.syncQueries.getSyncCategoryIds().awaitAsList().forEach { syncId ->
            database.syncQueries.enqueueOutbox(RecordTypes.CATEGORY.toLong(), null, null, syncId, null)
        }

        resolver.preferenceEntries().forEach { (key, _) ->
            database.syncQueries.enqueueOutbox(RecordTypes.PREFERENCE.toLong(), null, null, key, null)
        }
        sourcePrefKeys().forEach { source ->
            resolver.sourcePreferenceEntries(source).forEach { (pref, _) ->
                database.syncQueries.enqueueOutbox(
                    RecordTypes.SOURCE_PREFERENCE.toLong(),
                    null,
                    null,
                    pref,
                    source,
                )
            }
        }

        database.extension_storeQueries.getAll().awaitAsList().forEach { store ->
            database.syncQueries.enqueueOutbox(
                RecordTypes.EXTENSION_STORE.toLong(),
                null,
                null,
                store.index_url,
                null,
            )
        }
        database.sourceQueries.findAll().awaitAsList().forEach { source ->
            database.syncQueries.enqueueOutbox(
                RecordTypes.SOURCE_NAME.toLong(),
                null,
                null,
                source.id.toString(),
                null,
            )
        }
    }

    // ------------------------------------------------------------- outbound

    /**
     * Drains up to one batch of outbox rows. Returns false when the outbox
     * ran empty.
     */
    private suspend fun drainOneBatch(connection: SyncConnection, clock: Clock): Boolean {
        val rows = database.syncQueries.getOutbox(BATCH_SIZE.toLong()).awaitAsList()
        if (rows.isEmpty()) return false

        val changes = mutableListOf<Change>()
        val pushedRows = mutableListOf<Pair<Change, tachiyomi.data.GetOutbox>>()

        // Chapter, history, and track records only make sense with their
        // manga record beside them, so the parent rides in the same batch.
        val carriedManga = mutableSetOf<Pair<Long, String>>()
        suspend fun carryManga(sourceId: Long, url: String, row: tachiyomi.data.GetOutbox) {
            if (row.record_type == RecordTypes.MANGA.toLong()) return
            if (!carriedManga.add(sourceId to url)) return
            val parent = resolver.resolve(
                RecordTypes.MANGA.toLong(),
                sourceId,
                url,
                null,
                null,
                clock.tick(WallClock.System.now()),
            ) ?: return
            changes += parent
            pushedRows += parent to row
        }

        for (row in rows) {
            val change = resolver.resolve(
                row.record_type,
                row.manga_source,
                row.manga_url,
                row.extra,
                row.extra2,
                clock.tick(WallClock.System.now()),
            )
            when {
                change == null || isPreferenceEcho(change) -> {
                    // Nothing to push anymore (or an echo of a remote apply):
                    // the row retires either way.
                    database.syncQueries.deleteOutboxRow(row.rowId)
                }
                else -> {
                    val source = row.manga_source
                    val url = row.manga_url
                    if (source != null && url != null) {
                        carryManga(source, url, row)
                    }
                    changes += change
                    pushedRows += change to row
                }
            }
        }
        if (changes.isNotEmpty()) {
            // The clock persists before the batch leaves, so a crash between
            // the send and the ack re-pushes with fresh, not reused, values.
            syncDb.syncQueries.setHlc(clock.wall, clock.counter)
            val sent = connection.send(changeBatchFrame(changes))
            logcat { "KiokuSync pushing ${changes.size} changes (sent=$sent)" }
            if (!sent) throw SyncException("send failed")
            val ack = awaitAck(connection, clock)

            val applied = ack.itemsList.associateBy { item ->
                item.key.toByteArray().contentHashCode() to item.modified.toString()
            }
            for ((change, row) in pushedRows) {
                // Removal by rowid: a local write that landed mid-drain
                // re-enqueued this record as a fresh row, which survives.
                database.syncQueries.deleteOutboxRow(row.rowId)
                val itemKey = change.key.toByteArray().contentHashCode() to change.modified.toString()
                val wasApplied = applied[itemKey]?.applied ?: true
                if (wasApplied) {
                    rememberRecord(change)
                }
                // A rejected push means the server holds something newer; the
                // matching event will arrive and bring us up to date.
            }
        }
        return true
    }

    /**
     * A preference change whose value still equals the last one seen from the
     * server or pushed by us is an echo of a remote apply, not a local edit.
     */
    private suspend fun isPreferenceEcho(change: Change): Boolean {
        if (!isPreference(change) || change.tombstone) return false
        val record = syncDb.syncQueries.getRecord(change.key.toByteArray()).awaitAsOneOrNull()
            ?: return false
        return record.value_hash == valueHash(change)
    }

    // ------------------------------------------------------------- inbound

    /**
     * Waits for the ack of a pushed batch, applying any events that arrive
     * first. Returns the observed clock.
     */
    private suspend fun awaitAck(connection: SyncConnection, clock: Clock): Ack {
        var observed = clock
        while (true) {
            val frame = connection.receive() ?: throw SyncException("connection closed")
            when (frame.bodyCase) {
                Frame.BodyCase.ACK -> {
                    syncDb.syncQueries.setHlc(observed.wall, observed.counter)
                    return frame.ack
                }
                else -> observed = handleEvents(frame, observed)
            }
        }
    }

    private suspend fun handleEvents(frame: Frame, clock: Clock): Clock {
        if (frame.bodyCase != Frame.BodyCase.EVENTS) return clock
        val events = frame.events
        var maxSeq = -1L
        for (committed in events.eventsList) {
            val change = committed.change
            clock.observe(change.modified)
            if (applyIncoming(change)) {
                rememberRecord(change)
            }
            if (committed.seq > maxSeq) maxSeq = committed.seq
        }
        syncDb.syncQueries.setLastSeq(maxSeq)
        syncDb.syncQueries.setHlc(clock.wall, clock.counter)
        return clock
    }

    /**
     * Applies one event when the server's value is newer than what we last
     * knew; local pending changes are untouched and win their own pushes.
     */
    private suspend fun applyIncoming(change: Change): Boolean {
        val local = syncDb.syncQueries.getRecord(change.key.toByteArray()).awaitAsOneOrNull()
        if (local != null) {
            val localClock = Clock.hlc(local.wall, local.counter, local.device)
            if (compareClocks(change.modified, localClock) <= 0) return false
        }
        applier.applyBatch(listOf(change), ChangeApplier.Mode.LIVE)
        return true
    }

    // ------------------------------------------------------------- helpers

    private suspend fun rememberRecord(change: Change) {
        syncDb.syncQueries.upsertRecord(
            canonKey = change.key.toByteArray(),
            wall = change.modified.wallMs,
            counter = change.modified.counter.toLong(),
            device = change.modified.device,
            valueHash = valueHash(change),
        )
    }

    private fun isPreference(change: Change): Boolean =
        change.key.kindCase == RecordKey.KindCase.PREFERENCE ||
            change.key.kindCase == RecordKey.KindCase.SOURCE_PREFERENCE

    private fun valueHash(change: Change): String? {
        if (!isPreference(change) || change.tombstone) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(change.preference.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun sourcePrefKeys(): List<String> =
        sourceManager.getAll().filterIsInstance<ConfigurableSource>().map { it.preferenceKey() }

    private companion object {
        const val BATCH_SIZE = 500
        const val SUPPRESS_ON = 1L
        const val SUPPRESS_OFF = 0L
    }
}
