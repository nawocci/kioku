package eu.kanade.tachiyomi.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonObject
import logcat.LogPriority
import mihon.core.common.extensions.toJsonObject
import tachiyomi.data.Database
import tachiyomi.domain.manga.model.Manga
import xyz.altaf.kioku.sync.proto.CategoryState
import xyz.altaf.kioku.sync.proto.Change
import xyz.altaf.kioku.sync.proto.ChapterKey
import xyz.altaf.kioku.sync.proto.ChapterState
import xyz.altaf.kioku.sync.proto.ExtensionStoreState
import xyz.altaf.kioku.sync.proto.HistoryState
import xyz.altaf.kioku.sync.proto.Hlc
import xyz.altaf.kioku.sync.proto.MangaKey
import xyz.altaf.kioku.sync.proto.MangaState
import xyz.altaf.kioku.sync.proto.PrefValue
import xyz.altaf.kioku.sync.proto.RecordKey
import xyz.altaf.kioku.sync.proto.SourceNameState
import xyz.altaf.kioku.sync.proto.SourcePrefKey
import xyz.altaf.kioku.sync.proto.TrackKey
import xyz.altaf.kioku.sync.proto.TrackState
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import tachiyomi.core.common.util.system.logcat as logcatFn

/**
 * Applies incoming changes to the local database. Database writes run in one
 * transaction that suppresses the capture triggers, so remote state never
 * echoes back. Records are applied in the order they arrive and the outcome
 * must not depend on that order, so a snapshot converges no matter how the
 * server sorted it:
 *
 *  - category memberships are collected while applying and written after the
 *    whole batch, so a membership never lands before its category record;
 *  - preference writes (SharedPreferences, outside the transaction) are
 *    collected and applied after the transaction commits; a crash in between
 *    replays the batch, whose clock-guarded re-apply converges.
 *
 * Two modes:
 *  - [Mode.LIVE] for steady-state events: the server has already resolved
 *    the winner, so values replace local ones. History still unions, since a
 *    read anywhere is a read.
 *  - [Mode.PAIRING] for the first snapshot onto a device that has its own
 *    data: backup-restore semantics — favorites and reads union, tracking
 *    takes the furthest progress, details only replace what was never
 *    initialized, preferences follow the server.
 */
@Inject
class ChangeApplier(
    private val appContext: Context,
    private val database: Database,
) {

    enum class Mode { LIVE, PAIRING }

    /**
     * Manga the snapshot holds favorited that this device does not, reported
     * during pairing so the user can decide between keeping them (the
     * default) and removing them from the library everywhere.
     */
    data class PairingReport(val entries: List<ReviewEntry>)

    /** A library entry the server holds favorited that this device does not. */
    data class ReviewEntry(
        val title: String,
        val sourceId: Long,
        val url: String,
        /** The server's clock for the record, so a later answer can address it. */
        val modified: Hlc,
    )

    /** A category-membership write deferred until the batch has landed. */
    private data class DeferredMembership(val mangaKey: MangaKey, val syncIds: List<String>)

    /** A preference write deferred until after the transaction commits. */
    private sealed interface DeferredPref {
        data class App(val key: String, val value: PrefValue) : DeferredPref
        data class Source(val key: SourcePrefKey, val value: PrefValue) : DeferredPref
        data class RemoveApp(val key: String) : DeferredPref
        data class RemoveSource(val key: SourcePrefKey) : DeferredPref
    }

    /**
     * Applies [changes] in order. All database writes land or none of them
     * does; preference writes follow after a successful commit.
     */
    suspend fun applyBatch(changes: List<Change>, mode: Mode): PairingReport {
        val entries = mutableListOf<ReviewEntry>()
        val memberships = mutableListOf<DeferredMembership>()
        val prefs = mutableListOf<DeferredPref>()
        database.transaction {
            // Suppression is scoped to this transaction: a crash rolls the
            // writes and the flag back together.
            database.syncQueries.setSuppress(SUPPRESS_ON)
            try {
                for (change in changes) {
                    apply(change, mode, memberships, prefs)?.let { entries += it }
                }
            } finally {
                database.syncQueries.setSuppress(SUPPRESS_OFF)
            }
            memberships.forEach { applyMembership(it) }
        }
        applyPrefs(prefs)
        return PairingReport(entries)
    }

    private suspend fun apply(
        change: Change,
        mode: Mode,
        memberships: MutableList<DeferredMembership>,
        prefs: MutableList<DeferredPref>,
    ): ReviewEntry? {
        if (change.tombstone) {
            applyTombstone(change, prefs)
            return null
        }
        return when (change.stateCase) {
            Change.StateCase.MANGA ->
                applyManga(change.key.manga, change.manga, change.modified, mode, memberships)
            Change.StateCase.CHAPTER -> {
                applyChapter(change.key.chapter.manga, change.key.chapter, change.chapter, mode)
                null
            }
            Change.StateCase.HISTORY -> {
                applyHistory(change.key.history.manga, change.key.history.chapterUrl, change.history)
                null
            }
            Change.StateCase.TRACK -> {
                applyTrack(change.key.track.manga, change.key.track, change.track, mode)
                null
            }
            Change.StateCase.CATEGORY -> {
                applyCategory(change.key.category.id, change.category)
                null
            }
            Change.StateCase.PREFERENCE -> {
                if (change.key.kindCase == RecordKey.KindCase.SOURCE_PREFERENCE) {
                    prefs += DeferredPref.Source(change.key.sourcePreference, change.preference)
                } else {
                    prefs += DeferredPref.App(change.key.preference, change.preference)
                }
                null
            }
            Change.StateCase.EXTENSION_STORE -> {
                applyExtensionStore(change.key.extensionStore, change.extensionStore)
                null
            }
            Change.StateCase.SOURCE_NAME -> {
                applySourceName(change.key.sourceName, change.sourceName)
                null
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------ manga

    private suspend fun applyManga(
        mangaKey: MangaKey,
        state: MangaState,
        modified: Hlc,
        mode: Mode,
        memberships: MutableList<DeferredMembership>,
    ): ReviewEntry? {
        val existing = database.syncQueries.getMangaRow(mangaKey.sourceId, mangaKey.url)
            .awaitAsOneOrNull()

        if (existing == null) {
            // A manga this device has never seen is new data arriving, not an
            // unfavorite to confirm; only a local unfavorite of a known entry
            // is ambiguous (its "when" was never recorded).
            insertManga(mangaKey, state)
            memberships += DeferredMembership(mangaKey, state.categoriesList)
            return null
        }

        val favoriteAt = if (mode == Mode.PAIRING && !state.hasFavoriteAt()) {
            // Either side favoriting keeps the entry; a restore unions them
            // with the earliest known time.
            existing.user_favorite_at
        } else if (mode == Mode.PAIRING) {
            listOfNotNull(existing.user_favorite_at, Instant.fromEpochMilliseconds(state.favoriteAt))
                .filter { it > Manga.UNKNOWN_FAVORITE_AT }
                .minOrNull()
        } else {
            if (state.hasFavoriteAt()) Instant.fromEpochMilliseconds(state.favoriteAt) else null
        }

        val detailsWin = mode == Mode.LIVE || existing.state_initialized || !state.initialized
        database.mangaQueries.updateFromBackup(
            userFavoriteAt = favoriteAt,
            remoteArtist = if (detailsWin) state.artist else null,
            remoteAuthor = if (detailsWin) state.author else null,
            remoteDescription = if (detailsWin) state.description else null,
            remoteGenre = if (detailsWin) state.genreList else null,
            remoteTitle = if (detailsWin) state.title else null,
            remoteStatus = if (detailsWin) state.status.toLong() else null,
            remoteCover = if (detailsWin) state.cover else null,
            stateChapterLastUpdate = null,
            stateInitialized = state.initialized || existing.state_initialized,
            userReaderFlags = state.readerFlags,
            userChapterFlags = state.chapterFlags,
            stateCoverLastModified = null,
            id = existing.id,
            remoteUpdateStrategy = updateStrategy(state.updateStrategy),
            userNotes = state.notes,
            remoteMemo = if (mode == Mode.PAIRING) {
                JsonObject(state.memo.toByteArray().toJsonObject() + existing.remote_memo)
            } else {
                state.memo.toByteArray().toJsonObject()
            },
        )
        memberships += DeferredMembership(mangaKey, state.categoriesList)
        return if (mode == Mode.PAIRING && state.hasFavoriteAt() && existing.user_favorite_at == null) {
            ReviewEntry(state.title, mangaKey.sourceId, mangaKey.url, modified)
        } else {
            null
        }
    }

    private suspend fun insertManga(mangaKey: MangaKey, state: MangaState) {
        database.mangaQueries.insertReturningId(
            userFavoriteAt = if (state.hasFavoriteAt()) Instant.fromEpochMilliseconds(state.favoriteAt) else null,
            sourceId = mangaKey.sourceId,
            remoteUrl = mangaKey.url,
            remoteArtist = state.artist.ifEmpty { null },
            remoteAuthor = state.author.ifEmpty { null },
            remoteDescription = state.description.ifEmpty { null },
            remoteGenre = state.genreList,
            remoteTitle = state.title,
            remoteStatus = state.status.toLong(),
            remoteCover = state.cover.ifEmpty { null },
            stateChapterLastUpdate = null,
            stateChapterNextUpdate = null,
            stateChapterFetchInterval = 0L,
            stateInitialized = state.initialized,
            userReaderFlags = state.readerFlags,
            userChapterFlags = state.chapterFlags,
            stateCoverLastModified = null,
            remoteUpdateStrategy = updateStrategy(state.updateStrategy),
            userNotes = state.notes,
            remoteMemo = state.memo.toByteArray().toJsonObject(),
        ).awaitAsOne()
    }

    /**
     * Replaces the categories of one manga. Runs after the whole batch
     * landed, so every category the memberships name has its local row
     * already; a sync id that still resolves to nothing names a category
     * the server no longer holds, and skipping it matches the server state.
     */
    private suspend fun applyMembership(membership: DeferredMembership) {
        val mangaId = database.syncQueries.getMangaIdByKey(
            membership.mangaKey.sourceId,
            membership.mangaKey.url,
        ).awaitAsOneOrNull() ?: return
        val categoryIds = membership.syncIds.mapNotNull { syncId ->
            database.syncQueries.getSyncCategoryId(syncId).awaitAsOneOrNull()
        }
        database.manga_categoryQueries.deleteMangaCategoryByMangaId(mangaId)
        categoryIds.forEach { categoryId ->
            database.manga_categoryQueries.insert(mangaId, categoryId)
        }
    }

    // ------------------------------------------------------------------ chapter

    private suspend fun applyChapter(
        mangaKey: MangaKey,
        chapterKey: ChapterKey,
        state: ChapterState,
        mode: Mode,
    ) {
        val chapterId = database.syncQueries.getChapterIdByKey(
            mangaKey.sourceId,
            mangaKey.url,
            chapterKey.url,
        ).awaitAsOneOrNull()

        if (chapterId == null) {
            val mangaId = database.syncQueries.getMangaIdByKey(mangaKey.sourceId, mangaKey.url).awaitAsOneOrNull()
                // Batches and snapshots carry the manga record before its
                // chapters; one that still has no manga is dropped here and
                // arrives again with the manga on the next sync.
                ?: return
            database.chapterQueries.insertReturningId(
                mangaId = mangaId,
                remoteUrl = chapterKey.url,
                remoteName = state.name,
                remoteScanlator = state.scanlator.ifEmpty { null },
                userRead = state.read,
                userBookmark = state.bookmark,
                userLastPageRead = state.lastPageRead,
                remoteChapterNumber = state.number,
                remoteOrder = state.order,
                stateDateFetch = Instant.DISTANT_PAST,
                remoteDateUpload = if (state.hasDateUpload()) Instant.fromEpochMilliseconds(state.dateUpload) else null,
                remoteMemo = state.memo.toByteArray().toJsonObject(),
            ).awaitAsOneOrNull()
            return
        }

        val existing = database.chapterQueries.getChapterById(chapterId).awaitAsOne()
        val (read, bookmark, lastPageRead) = if (mode == Mode.PAIRING) {
            Triple(
                state.read || existing.user_read,
                state.bookmark || existing.user_bookmark,
                maxOf(state.lastPageRead, existing.user_last_page_read),
            )
        } else {
            Triple(state.read, state.bookmark, state.lastPageRead)
        }

        database.syncQueries.updateSyncChapter(
            read = read,
            bookmark = bookmark,
            lastPageRead = lastPageRead,
            name = state.name,
            scanlator = state.scanlator.ifEmpty { null },
            number = state.number,
            dateUpload = if (state.hasDateUpload()) Instant.fromEpochMilliseconds(state.dateUpload) else null,
            order = state.order,
            memo = state.memo.toByteArray().toJsonObject(),
            id = chapterId,
        )
    }

    // ------------------------------------------------------------------ history

    private suspend fun applyHistory(
        mangaKey: MangaKey,
        chapterUrl: String,
        state: HistoryState,
    ) {
        val chapterId = database.syncQueries.getChapterIdByKey(
            mangaKey.sourceId,
            mangaKey.url,
            chapterUrl,
        ).awaitAsOneOrNull() ?: return

        val existing = database.syncQueries.getSyncHistory(
            mangaKey.sourceId,
            mangaKey.url,
            chapterUrl,
        ).awaitAsOneOrNull()

        if (existing == null) {
            database.historyQueries.upsert(
                chapterId = chapterId,
                readAt = if (state.hasReadAt()) Instant.fromEpochMilliseconds(state.readAt) else null,
                readDuration = state.readDuration,
            )
            return
        }

        // The upsert adds durations, so the union is fed as the difference
        // from the stored value, exactly as a restore does.
        val readAt = listOfNotNull(
            if (state.hasReadAt()) Instant.fromEpochMilliseconds(state.readAt) else null,
            existing.read_at,
        ).maxOrNull()
        val duration = maxOf(state.readDuration, existing.read_duration) - existing.read_duration
        database.historyQueries.upsert(
            chapterId = chapterId,
            readAt = readAt,
            readDuration = duration,
        )
    }

    // ------------------------------------------------------------------ track

    private suspend fun applyTrack(
        mangaKey: MangaKey,
        trackKey: TrackKey,
        state: TrackState,
        mode: Mode,
    ) {
        val mangaId = database.syncQueries.getMangaIdByKey(mangaKey.sourceId, mangaKey.url).awaitAsOneOrNull()
            ?: return
        val existing = database.syncQueries.getSyncTrack(
            mangaKey.sourceId,
            mangaKey.url,
            trackKey.trackerId,
        ).awaitAsOneOrNull()

        if (existing == null || mode == Mode.LIVE) {
            database.manga_trackQueries.upsert(
                mangaId = mangaId,
                trackerId = trackKey.trackerId,
                remoteId = state.remoteId,
                remoteUrl = state.remoteUrl,
                libraryId = if (state.hasLibraryId()) state.libraryId else null,
                title = state.title,
                lastChapterRead = state.lastChapterRead,
                totalChapters = state.totalChapters,
                status = state.status.toLong(),
                score = state.score,
                startDate = if (state.hasStartDate()) Instant.fromEpochMilliseconds(state.startDate) else null,
                finishDate = if (state.hasFinishDate()) Instant.fromEpochMilliseconds(state.finishDate) else null,
                private = state.private,
            )
            return
        }

        // A restore only moves tracking forward.
        if (state.lastChapterRead > existing.last_chapter_read) {
            database.manga_trackQueries.update(
                mangaId = mangaId,
                trackerId = null,
                remoteId = null,
                remoteUrl = null,
                libraryId = null,
                title = null,
                lastChapterRead = state.lastChapterRead,
                totalChapters = null,
                status = null,
                score = null,
                startDate = null,
                finishDate = null,
                private = null,
                id = existing.id,
            )
        }
    }

    // ------------------------------------------------------------------ category

    private suspend fun applyCategory(syncId: String, state: CategoryState) {
        val localId = database.syncQueries.getSyncCategoryId(syncId).awaitAsOneOrNull()
            ?: createCategory(syncId, state)
            ?: return
        database.syncQueries.updateSyncCategory(
            name = state.name,
            order = state.order,
            flags = state.flags,
            id = localId,
        )
    }

    private suspend fun createCategory(syncId: String, state: CategoryState): Long? {
        database.syncQueries.insertSyncCategory(state.name, state.order, state.flags)
        val id = database.syncQueries.lastInsertRowId().awaitAsOne()
        database.syncQueries.upsertSyncCategoryMap(id, syncId)
        return id
    }

    // ------------------------------------------------------------------ preferences

    private fun applyPrefs(prefs: List<DeferredPref>) {
        for (pref in prefs) {
            try {
                when (pref) {
                    is DeferredPref.App -> applyPreference(
                        PreferenceManager.getDefaultSharedPreferences(appContext),
                        pref.key,
                        pref.value,
                    )
                    is DeferredPref.Source -> applyPreference(
                        appContext.getSharedPreferences(pref.key.source, Context.MODE_PRIVATE),
                        pref.key.pref,
                        pref.value,
                    )
                    is DeferredPref.RemoveApp ->
                        PreferenceManager.getDefaultSharedPreferences(appContext).edit().remove(pref.key).apply()
                    is DeferredPref.RemoveSource ->
                        appContext.getSharedPreferences(pref.key.source, Context.MODE_PRIVATE).edit()
                            .remove(pref.key.pref).apply()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed preference write must not lose the database
                // writes it followed; the batch replays on the next sync
                // and re-lands it.
                logcat(e)
            }
        }
    }

    private fun applyPreference(prefs: SharedPreferences, key: String, value: PrefValue) {
        val editor = prefs.edit()
        when (value.valueCase) {
            PrefValue.ValueCase.INT_VALUE -> editor.putInt(key, value.intValue)
            PrefValue.ValueCase.LONG_VALUE -> editor.putLong(key, value.longValue)
            PrefValue.ValueCase.FLOAT_VALUE -> editor.putFloat(key, value.floatValue)
            PrefValue.ValueCase.STRING_VALUE -> editor.putString(key, value.stringValue)
            PrefValue.ValueCase.BOOL_VALUE -> editor.putBoolean(key, value.boolValue)
            PrefValue.ValueCase.STRING_SET_VALUE -> editor.putStringSet(key, value.stringSetValue.valuesList.toSet())
            else -> editor.remove(key)
        }
        editor.apply()
    }

    // ------------------------------------------------------------------ stores & sources

    private suspend fun applyExtensionStore(indexUrl: String, state: ExtensionStoreState) {
        database.extension_storeQueries.upsert(
            indexUrl = indexUrl,
            name = state.name,
            badgeLabel = state.badgeLabel,
            signingKey = state.signingKey,
            contactWebsite = state.contactWebsite,
            contactDiscord = state.contactDiscord.ifEmpty { null },
            isLegacy = state.isLegacy,
            extensionListUrl = state.extensionListUrl.ifEmpty { null },
        )
    }

    private suspend fun applySourceName(sourceId: Long, state: SourceNameState) {
        database.sourceQueries.upsert(sourceId, state.name, state.language)
    }

    // ------------------------------------------------------------------ tombstones

    private suspend fun applyTombstone(change: Change, prefs: MutableList<DeferredPref>) {
        when (change.key.kindCase) {
            RecordKey.KindCase.MANGA -> {
                // Deleting the manga cascades its chapters, history, and
                // tracks here, the same cascade the sending device had.
                val manga = change.key.manga
                database.syncQueries.deleteSyncManga(manga.sourceId, manga.url)
            }
            RecordKey.KindCase.CHAPTER -> {
                val key = change.key.chapter
                database.syncQueries.getChapterIdByKey(
                    key.manga.sourceId,
                    key.manga.url,
                    key.url,
                ).awaitAsOneOrNull()?.let { chapterId ->
                    database.syncQueries.deleteSyncHistory(chapterId)
                    database.syncQueries.deleteSyncChapter(chapterId)
                }
            }
            RecordKey.KindCase.CATEGORY -> {
                val syncId = change.key.category.id
                database.syncQueries.deleteSyncCategory(syncId)
                database.syncQueries.deleteSyncCategoryMap(syncId)
            }
            RecordKey.KindCase.TRACK -> {
                val key = change.key.track
                database.syncQueries.getMangaIdByKey(key.manga.sourceId, key.manga.url)
                    .awaitAsOneOrNull()
                    ?.let { mangaId -> database.syncQueries.deleteSyncTrack(mangaId, key.trackerId) }
            }
            RecordKey.KindCase.PREFERENCE ->
                prefs += DeferredPref.RemoveApp(change.key.preference)
            RecordKey.KindCase.SOURCE_PREFERENCE ->
                prefs += DeferredPref.RemoveSource(change.key.sourcePreference)
            RecordKey.KindCase.EXTENSION_STORE ->
                database.syncQueries.deleteSyncExtensionStore(change.key.extensionStore)
            RecordKey.KindCase.SOURCE_NAME ->
                database.syncQueries.deleteSyncSource(change.key.sourceName)
            else -> {}
        }
    }

    private fun updateStrategy(ordinal: Int): UpdateStrategy =
        UpdateStrategy.entries.getOrElse(ordinal) { UpdateStrategy.ALWAYS_UPDATE }

    private fun logcat(e: Exception) {
        logcatFn(LogPriority.WARN, throwable = e) { "KiokuSync preference apply failed" }
    }

    private companion object {
        const val SUPPRESS_ON = 1L
        const val SUPPRESS_OFF = 0L
    }
}
