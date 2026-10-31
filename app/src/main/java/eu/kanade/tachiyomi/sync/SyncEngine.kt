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
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.domain.source.service.SourceManager
import xyz.altaf.kioku.sync.HlcClock
import xyz.altaf.kioku.sync.RecordKeys
import xyz.altaf.kioku.sync.RecordTypes
import xyz.altaf.kioku.sync.compareClocks
import xyz.altaf.kioku.sync.db.SyncDatabase
import xyz.altaf.kioku.sync.proto.Ack
import xyz.altaf.kioku.sync.proto.Change
import xyz.altaf.kioku.sync.proto.Frame
import xyz.altaf.kioku.sync.proto.HelloResponse
import xyz.altaf.kioku.sync.proto.Pong
import xyz.altaf.kioku.sync.proto.RecordKey
import xyz.altaf.kioku.sync.proto.SnapshotResponse
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.Clock as WallClock

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
     * with `kioku-sync key` and pastes it here, or the pairing QR carries it.
     *
     * Runs on the app scope, so leaving the settings screen does not cancel it.
     * [onDone] reports the pairing itself — snapshot applied, dump queued,
     * device marked paired — not the lifetime of the live session it opens
     * afterwards, so the form stops waiting as soon as the pairing landed.
     */
    fun pairWithKey(serverUrl: String, key: String, onDone: (Result<Unit>) -> Unit) {
        pairingJob?.cancel()
        // A live session holds the session mutex for as long as its socket
        // stays open, and pairing needs that mutex to swap libraries. Stopping
        // the loop here also means the pairing socket becomes the live one.
        sessionJob?.cancel()
        sessionJob = null
        pairingJob = scope.launch {
            val opened = try {
                val url = serverUrl.trim().trimEnd('/')
                val secret = key.trim()
                status.value = SyncStatus.Connecting
                sessionMutex.withLock {
                    val identity = identity()
                    val sameLibrary = identity.paired &&
                        identity.serverUrl == url &&
                        identity.apiKey == secret
                    if (!sameLibrary) {
                        if (identity.paired) {
                            // A different library: what this device knew about
                            // the previous one means nothing there.
                            logcat { "KiokuSync pairing with a different library" }
                            forgetLibrary()
                        }
                        syncDb.syncQueries.upsertServer(url, secret)
                    }
                    openSession(interactive = true)
                }
            } catch (e: CancellationException) {
                // This job was cancelled (stop(), or the user closing the
                // dialog) or a timeout inside the session fired. Only the
                // latter is a failure the UI must see.
                if (coroutineContext.isActive) {
                    val message = e.message ?: "pairing timed out"
                    status.value = SyncStatus.Error(message)
                    onDone(Result.failure(SyncException(message)))
                }
                return@launch
            } catch (e: Exception) {
                status.value = SyncStatus.Error(e.message ?: e.javaClass.simpleName)
                onDone(Result.failure(e))
                return@launch
            }

            if (opened == null) {
                status.value = SyncStatus.Idle
                onDone(Result.failure(SyncException("no sync server is configured")))
                return@launch
            }

            // Pairing landed: the snapshot is applied, the dump is queued, and
            // the device is marked paired. Report now, rather than when the
            // live session below eventually ends.
            onDone(Result.success(Unit))
            logcat { "KiokuSync paired; the session is live" }

            try {
                // Keep the socket the pairing opened: it is already caught up
                // and holds the session mutex, so no second session can start.
                sessionMutex.withLock { liveLoop(opened, interactive = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.value = SyncStatus.Error(e.message ?: e.javaClass.simpleName)
            } finally {
                opened.connection.close()
            }

            if (isPaired()) {
                // The live socket ended; the run loop takes over with its
                // reconnect backoff.
                sessionJob?.cancel()
                sessionJob = scope.launch { runLoop(interactive = true) }
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
        if (!status.value.pairingBusy) return
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
        forgetLibrary()
        status.value = SyncStatus.NotPaired
    }

    /**
     * Drops everything this device knew about the library it was paired with:
     * the position in its event log, the per-record clocks, the pending
     * changes, and the category ids minted against it. The local library stays
     * untouched — the next session pulls the server state again.
     */
    private suspend fun forgetLibrary() {
        syncDb.syncQueries.clearRecords()
        database.syncQueries.clearOutbox()
        // Category mappings point at local ids that a fresh snapshot re-creates.
        database.syncQueries.clearCategoryMap()
        syncDb.syncQueries.setLastSeq(0)
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
            forgetLibrary()
        }
    }

    /** What the sync settings screen shows about the stored pairing. */
    data class Identity(
        val paired: Boolean,
        val serverUrl: String?,
        val apiKey: String?,
        val deviceName: String,
        val syncPrivate: Boolean,
    )

    /** The stored pairing, read in one query. */
    suspend fun identity(): Identity {
        val meta = syncDb.syncQueries.getMeta().awaitAsOne()
        return Identity(
            paired = meta.paired,
            serverUrl = meta.server_url,
            apiKey = meta.api_key,
            deviceName = meta.device_name ?: Build.MODEL,
            syncPrivate = meta.sync_private,
        )
    }

    /** Whether a server, key, and completed pairing are in place. */
    suspend fun isPaired(): Boolean = syncDb.syncQueries.getMeta().awaitAsOne().paired

    suspend fun setDeviceName(name: String) {
        syncDb.syncQueries.setDeviceName(name)
    }

    suspend fun setSyncPrivate(enabled: Boolean) {
        syncDb.syncQueries.setSyncPrivate(enabled)
        // Nothing to retract before the first pairing: the server has not seen
        // any of these values.
        if (enabled || !isPaired()) return

        // Values that already reached the server are removed everywhere rather
        // than left behind. The record positions name them, which also covers
        // sources that are no longer installed — their preference files are
        // gone, but their values on the server are not.
        syncDb.syncQueries.getRecordKeys().awaitAsList().forEach { canonKey ->
            val key = runCatching { RecordKey.parseFrom(canonKey) }.getOrNull() ?: return@forEach
            val pref = when (key.kindCase) {
                RecordKey.KindCase.PREFERENCE -> key.preference
                RecordKey.KindCase.SOURCE_PREFERENCE -> key.sourcePreference.pref
                else -> return@forEach
            }
            if (!Preference.isPrivate(pref)) return@forEach

            // The resolver decides whether anything is left to retract: a
            // record already stored as a tombstone is skipped there.
            if (key.kindCase == RecordKey.KindCase.PREFERENCE) {
                database.syncQueries.enqueueOutbox(
                    RecordTypes.PREFERENCE.toLong(),
                    null,
                    null,
                    key.preference,
                    null,
                )
            } else {
                database.syncQueries.enqueueOutbox(
                    RecordTypes.SOURCE_PREFERENCE.toLong(),
                    null,
                    null,
                    key.sourcePreference.pref,
                    key.sourcePreference.source,
                )
            }
        }
    }

    /** Runs one catch-up session in the background of the app. */
    fun syncNow() {
        scope.launch { runCatching { syncOnce() } }
    }

    // ------------------------------------------------------------- session

    /** A session that finished hello and catch-up; its socket is live. */
    private class LiveSession(val connection: SyncConnection, var clock: HlcClock)

    /**
     * Opens a session and brings it up to date: hello, the snapshot when the
     * server asks for one, then the catch-up drain. Returns null when this
     * device has no server configured (or a background session runs before
     * pairing completed). The socket is live on return.
     */
    private suspend fun openSession(interactive: Boolean): LiveSession? {
        val meta = syncDb.syncQueries.getMeta().awaitAsOne()
        val serverUrl = meta.server_url ?: return null
        val apiKey = meta.api_key ?: return null
        val deviceId = syncDb.syncQueries.getDeviceId().awaitAsOne()
        val deviceName = meta.device_name ?: Build.MODEL

        // A background session never runs before the interactive pairing
        // completed: it would apply the snapshot without the merge
        // confirmation and the review.
        if (!interactive && !meta.paired) return null

        status.value = SyncStatus.Connecting
        val connection = SyncConnection(network, serverUrl, apiKey)
        try {
            val hello = connection.open(
                SyncConnection.hello(deviceId, deviceName, BuildConfig.VERSION_NAME, meta.last_seq),
                timeout = 10.seconds,
            )

            logcat { "KiokuSync session opening to $serverUrl as $deviceId at seq ${meta.last_seq}" }
            var clock = HlcClock(meta.hlc_wall, meta.hlc_counter, deviceId)
            // A device whose clock runs far ahead of the server would win every
            // last-write-wins comparison forever. The session reseats it
            // against the server's own time before issuing any new value.
            if (clock.clampTo(hello.serverTimeMs)) {
                logcat { "KiokuSync reseated a clock that was ahead of the server" }
                syncDb.syncQueries.setHlc(clock.wall, clock.counter)
            }
            resolver.syncPrivate = meta.sync_private
            prefWatcher.start(sourcePrefKeys())

            if (hello.snapshotRequired) {
                clock = applySnapshot(connection, hello, meta.paired, clock, interactive)
            }

            status.value = SyncStatus.Online(WallClock.System.now())
            logcat { "KiokuSync session live" }
            return LiveSession(connection, clock)
        } catch (e: Exception) {
            connection.close()
            throw e
        }
    }

    /**
     * Pushes local writes and applies events until the socket closes. A
     * catch-up session returns as soon as the outbox ran empty; the live one
     * stays for pushes and events.
     */
    private suspend fun liveLoop(session: LiveSession, interactive: Boolean) {
        while (coroutineContext.isActive && session.connection.isOpen) {
            if (drainOneBatch(session.connection, session.clock)) {
                status.value = SyncStatus.Online(WallClock.System.now())
                continue
            }
            if (!interactive) return
            val frame = session.connection.awaitFrameOr(outboxSignal)
            if (frame != null) {
                session.clock = handleEvents(frame, session.clock)
                status.value = SyncStatus.Online(WallClock.System.now())
            }
        }
    }

    /** One session: open, catch up, hang up. */
    private suspend fun runSession(interactive: Boolean): Boolean {
        val session = openSession(interactive) ?: return false
        try {
            liveLoop(session, interactive)
        } finally {
            session.connection.close()
            logcat { "KiokuSync session closed" }
        }
        return true
    }

    /**
     * Waits for one snapshot page, answering pings meanwhile. The snapshot
     * phase carries no other frames.
     */
    private suspend fun awaitSnapshotPage(connection: SyncConnection): SnapshotResponse {
        while (true) {
            val frame = connection.receive() ?: throw SyncException("connection closed")
            when (frame.bodyCase) {
                Frame.BodyCase.SNAPSHOT_RESPONSE ->
                    return frame.snapshotResponse ?: throw SyncException("empty snapshot page")
                Frame.BodyCase.PING -> connection.send(
                    Frame.newBuilder().setPong(Pong.newBuilder().setWallMs(frame.ping.wallMs)).build(),
                )
                else -> throw SyncException("unexpected frame during pairing")
            }
        }
    }

    /**
     * Applies the snapshot page by page: pairing merges backup-style, a
     * catch-up snapshot replaces per the clock. Returns the clock observed in
     * it.
     */
    private suspend fun applySnapshot(
        connection: SyncConnection,
        hello: HelloResponse,
        paired: Boolean,
        clock: HlcClock,
        interactive: Boolean,
    ): HlcClock {
        val mode = if (paired) ChangeApplier.Mode.LIVE else ChangeApplier.Mode.PAIRING
        val serverRecords = hello.snapshotRecords.toInt()
        status.value = SyncStatus.ApplyingSnapshot(serverRecords)

        // The size rides with the hello, so a device pairing into a non-empty
        // library asks about the merge before the first page is requested.
        if (!paired && interactive && serverRecords > 0) {
            val localCount = localLibraryCount()
            if (localCount > 0 && !confirmMerge(serverRecords, localCount)) {
                // Nothing was applied; the device keeps its data and the
                // server keeps its library, and the user can retry with a
                // fresh key or after clearing one side.
                throw SyncException("pairing declined: both this device and the server hold a library")
            }
        }

        connection.send(snapshotRequestFrame())
        val entries = mutableListOf<ChangeApplier.ReviewEntry>()
        var latestSeq = 0L
        while (true) {
            val page = awaitSnapshotPage(connection)
            // Applied as it lands: the database is never held for a whole
            // snapshot, and a large library is never resident at once.
            entries += applier.applyBatch(page.recordsList, mode).entries
            page.recordsList.forEach {
                clock.observe(it.modified)
                rememberRecord(it)
            }
            latestSeq = page.latestSeq
            if (page.last) break
        }
        syncDb.syncQueries.setHlc(clock.wall, clock.counter)
        syncDb.syncQueries.setLastSeq(latestSeq)

        if (paired) return clock

        val removeEverywhere = if (entries.isNotEmpty() && interactive) {
            logcat { "KiokuSync pairing review for ${entries.size} server-only favorites" }
            val review = CompletableDeferred<Boolean>()
            pairingReview = review
            status.value = SyncStatus.AwaitingReview(entries)
            val answer = review.await()
            pairingReview = null
            answer
        } else {
            false
        }

        // Everything this device holds rides up as one dump, seeding an empty
        // key or converging a merged one; fresh clocks win everywhere.
        status.value = SyncStatus.UploadingLibrary
        database.syncQueries.clearOutbox()
        syncDb.syncQueries.clearRecords()
        seedDump()

        // Applied after the dump: the reviewed entries are no longer favorites,
        // so the dump does not carry them, and their unfavorites must be queued
        // explicitly. Anything queued before the dump would be dropped with it.
        if (removeEverywhere) {
            unfavoriteEverywhere(entries)
        }

        syncDb.syncQueries.markPaired()
        return clock
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
     * Unfavorites the reviewed entries here. The capture trigger queues each
     * record as an ordinary local change, and the record position the server
     * reported is kept so the resolver does not mistake the entry for a
     * browsing leftover with nothing left to sync.
     */
    private suspend fun unfavoriteEverywhere(entries: List<ChangeApplier.ReviewEntry>) {
        database.transaction {
            entries.forEach { entry ->
                database.syncQueries.unfavoriteManga(entry.sourceId, entry.url)
            }
        }
        entries.forEach { entry ->
            syncDb.syncQueries.upsertRecord(
                canonKey = RecordKeys.manga(entry.sourceId, entry.url).toByteArray(),
                wall = entry.modified.wallMs,
                counter = entry.modified.counter.toLong(),
                device = entry.modified.device,
                valueHash = null,
            )
        }
    }

    /**
     * Enqueues every local record worth syncing, so the drain pushes the whole
     * state after pairing. The database part is a handful of statements; only
     * preferences, which live outside it, are walked entry by entry.
     */
    private suspend fun seedDump() {
        database.transaction {
            for (key in database.syncQueries.getSyncMangaKeys().awaitAsList()) {
                database.syncQueries.enqueueOutbox(
                    RecordTypes.MANGA.toLong(),
                    key.source_id,
                    key.remote_url,
                    null,
                    null,
                )
            }
            database.syncQueries.seedDumpChapters()
            database.syncQueries.seedDumpHistory()
            database.syncQueries.seedDumpTracks()
            database.syncQueries.seedDumpCategories()
            database.syncQueries.seedDumpExtensionStores()
            database.syncQueries.seedDumpSourceNames()
        }

        resolver.preferenceEntries().keys.forEach { key ->
            database.syncQueries.enqueueOutbox(RecordTypes.PREFERENCE.toLong(), null, null, key, null)
        }
        sourcePrefKeys().forEach { source ->
            resolver.sourcePreferenceEntries(source).keys.forEach { pref ->
                database.syncQueries.enqueueOutbox(
                    RecordTypes.SOURCE_PREFERENCE.toLong(),
                    null,
                    null,
                    pref,
                    source,
                )
            }
        }
    }

    // ------------------------------------------------------------- outbound

    /**
     * Drains up to one batch of outbox rows. Returns false when the outbox
     * ran empty.
     */
    private suspend fun drainOneBatch(connection: SyncConnection, clock: HlcClock): Boolean {
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
            val change: Change? = try {
                resolver.resolve(
                    row.record_type,
                    row.manga_source,
                    row.manga_url,
                    row.extra,
                    row.extra2,
                    clock.tick(WallClock.System.now()),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One row this build cannot resolve must not take the whole
                // queue down with it: it is counted and skipped, and the rows
                // behind it still sync.
                logcat(LogPriority.ERROR, e) { "KiokuSync could not resolve an outbox row" }
                database.syncQueries.markOutboxAttempt(row.rowId)
                continue
            }
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

            // The server answers one item per change, in the order it received
            // them, so the ack lines up with what was pushed by index.
            val applied = ack.itemsList
            if (applied.size != pushedRows.size) {
                logcat { "KiokuSync ack carries ${applied.size} items for ${pushedRows.size} changes" }
            }
            for ((index, pushed) in pushedRows.withIndex()) {
                val (change, row) = pushed
                // Removal by rowid: a local write that landed mid-drain
                // re-enqueued this record as a fresh row, which survives.
                database.syncQueries.deleteOutboxRow(row.rowId)
                val wasApplied = applied.getOrNull(index)?.applied ?: true
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
    private suspend fun awaitAck(connection: SyncConnection, clock: HlcClock): Ack {
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

    private suspend fun handleEvents(frame: Frame, clock: HlcClock): HlcClock {
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
        // An empty batch must not rewind the position; only a real event
        // advances it.
        if (maxSeq >= 0) syncDb.syncQueries.setLastSeq(maxSeq)
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
            val localClock = HlcClock.hlc(local.wall, local.counter, local.device)
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
