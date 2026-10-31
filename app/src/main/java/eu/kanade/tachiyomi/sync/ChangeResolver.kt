package eu.kanade.tachiyomi.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.google.protobuf.ByteString
import dev.zacsweers.metro.Inject
import mihon.core.common.extensions.toByteArray
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.Database
import xyz.altaf.kioku.sync.RecordKeys
import xyz.altaf.kioku.sync.RecordTypes
import xyz.altaf.kioku.sync.db.SyncDatabase
import xyz.altaf.kioku.sync.proto.CategoryState
import xyz.altaf.kioku.sync.proto.Change
import xyz.altaf.kioku.sync.proto.ChapterState
import xyz.altaf.kioku.sync.proto.ExtensionStoreState
import xyz.altaf.kioku.sync.proto.HistoryState
import xyz.altaf.kioku.sync.proto.Hlc
import xyz.altaf.kioku.sync.proto.MangaState
import xyz.altaf.kioku.sync.proto.PrefValue
import xyz.altaf.kioku.sync.proto.SourceNameState
import xyz.altaf.kioku.sync.proto.TrackState

/**
 * Builds protocol changes from pending outbox rows, reading the current state
 * of each record. A manga row that is gone becomes a tombstone, propagating
 * a genuine deletion; a chapter, history, or track row that is gone becomes
 * a tombstone of its own. Records with nothing worth syncing (browsing
 * leftovers without user state) are dropped so they never reach the server.
 */
@Inject
class ChangeResolver(
    private val appContext: Context,
    private val database: Database,
    private val syncDb: SyncDatabase,
) {

    private val appPreferences: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(appContext)

    /** Whether private preferences ride along; from the sync settings. */
    var syncPrivate: Boolean = false

    /**
     * Resolves one outbox row against the current database state, carrying
     * [clock] as the change time.
     */
    suspend fun resolve(
        type: Long,
        mangaSource: Long?,
        mangaUrl: String?,
        extra: String?,
        extra2: String?,
        clock: Hlc,
    ): Change? = when (type.toInt()) {
        RecordTypes.MANGA -> resolveManga(mangaSource!!, mangaUrl!!, clock)
        RecordTypes.CHAPTER -> resolveChapter(mangaSource!!, mangaUrl!!, extra!!, clock)
        RecordTypes.HISTORY -> resolveHistory(mangaSource!!, mangaUrl!!, extra!!, clock)
        RecordTypes.TRACK -> resolveTrack(mangaSource!!, mangaUrl!!, extra!!, clock)
        RecordTypes.CATEGORY -> resolveCategory(extra!!, clock)
        RecordTypes.PREFERENCE -> resolvePreference(extra!!, null, clock)
        RecordTypes.SOURCE_PREFERENCE -> resolvePreference(extra!!, extra2!!, clock)
        RecordTypes.EXTENSION_STORE -> resolveExtensionStore(extra!!, clock)
        RecordTypes.SOURCE_NAME -> resolveSourceName(extra!!.toLong(), clock)
        else -> null
    }

    private suspend fun resolveManga(sourceId: Long, url: String, clock: Hlc): Change? {
        val row = database.syncQueries.getSyncManga(sourceId, url).awaitAsOneOrNull()
            // The row is gone: a deletion outside suppressed maintenance.
            // Its chapters and history cascaded away with it, so this one
            // tombstone carries the whole deletion to the other devices.
            ?: return tombstone(RecordKeys.manga(sourceId, url), clock)
        if (!mangaSyncable(sourceId, url)) return null
        return build(RecordKeys.manga(sourceId, url), clock) {
            setManga(
                MangaState.newBuilder().apply {
                    row.user_favorite_at?.let { favoriteAt = it.toEpochMilliseconds() }
                    notes = row.user_notes
                    readerFlags = row.user_reader_flags
                    chapterFlags = row.user_chapter_flags
                    updateStrategy = row.remote_update_strategy.ordinal
                    row.excludedScanlators?.takeIf {
                        it.isNotEmpty()
                    }?.let { text -> addAllExcludedScanlators(text.split('\n')) }
                    row.categories?.takeIf { it.isNotEmpty() }?.let { text -> addAllCategories(text.split('\n')) }
                    title = row.remote_title
                    author = row.remote_author.orEmpty()
                    artist = row.remote_artist.orEmpty()
                    description = row.remote_description.orEmpty()
                    addAllGenre(row.remote_genre.orEmpty())
                    status = row.remote_status.toInt()
                    cover = row.remote_cover.orEmpty()
                    memo = ByteString.copyFrom(row.remote_memo.toByteArray())
                    initialized = row.state_initialized
                },
            )
        }
    }

    private suspend fun resolveChapter(sourceId: Long, mangaUrl: String, url: String, clock: Hlc): Change? {
        if (!mangaSyncable(sourceId, mangaUrl)) return null
        val row = database.syncQueries.getSyncChapter(sourceId, mangaUrl, url).awaitAsOneOrNull()
            ?: return tombstone(RecordKeys.chapter(sourceId, mangaUrl, url), clock)
        // A chapter's metadata belongs to the source and comes back from a
        // refresh, so only user state is worth a record. A chapter nobody
        // touched has nothing to say.
        if (!row.user_read && !row.user_bookmark && row.user_last_page_read == 0) return null
        return build(RecordKeys.chapter(sourceId, mangaUrl, url), clock) {
            setChapter(
                ChapterState.newBuilder().apply {
                    read = row.user_read
                    bookmark = row.user_bookmark
                    lastPageRead = row.user_last_page_read
                },
            )
        }
    }

    private suspend fun resolveHistory(sourceId: Long, mangaUrl: String, chapterUrl: String, clock: Hlc): Change? {
        if (!mangaSyncable(sourceId, mangaUrl)) return null
        val row = database.syncQueries.getSyncHistory(sourceId, mangaUrl, chapterUrl).awaitAsOneOrNull()
            ?: return tombstone(RecordKeys.history(sourceId, mangaUrl, chapterUrl), clock)
        return build(RecordKeys.history(sourceId, mangaUrl, chapterUrl), clock) {
            setHistory(
                HistoryState.newBuilder().apply {
                    row.read_at?.let { readAt = it.toEpochMilliseconds() }
                    readDuration = row.read_duration
                },
            )
        }
    }

    private suspend fun resolveTrack(sourceId: Long, mangaUrl: String, trackerIdText: String, clock: Hlc): Change? {
        if (!mangaSyncable(sourceId, mangaUrl)) return null
        val trackerId = trackerIdText.toLongOrNull() ?: return null
        val row = database.syncQueries.getSyncTrack(sourceId, mangaUrl, trackerId).awaitAsOneOrNull()
            ?: return tombstone(RecordKeys.track(sourceId, mangaUrl, trackerId), clock)
        return build(RecordKeys.track(sourceId, mangaUrl, trackerId), clock) {
            setTrack(
                TrackState.newBuilder().apply {
                    remoteId = row.remote_id
                    remoteUrl = row.remote_url
                    row.library_id?.let { libraryId = it }
                    title = row.title
                    lastChapterRead = row.last_chapter_read
                    totalChapters = row.total_chapters
                    status = row.status.toInt()
                    score = row.score
                    row.start_date?.let { startDate = it.toEpochMilliseconds() }
                    row.finish_date?.let { finishDate = it.toEpochMilliseconds() }
                    setPrivate(row.private_)
                },
            )
        }
    }

    private suspend fun resolveCategory(syncId: String, clock: Hlc): Change? {
        val row = database.syncQueries.getSyncCategory(syncId).awaitAsOneOrNull()
            ?: return tombstone(RecordKeys.category(syncId), clock)
        return build(RecordKeys.category(syncId), clock) {
            setCategory(
                CategoryState.newBuilder().apply {
                    name = row.name
                    setOrder(row.order)
                    flags = row.flags
                },
            )
        }
    }

    private suspend fun resolveExtensionStore(indexUrl: String, clock: Hlc): Change? {
        val row = database.syncQueries.getSyncExtensionStore(indexUrl).awaitAsOneOrNull()
            ?: return tombstone(RecordKeys.extensionStore(indexUrl), clock)
        return build(RecordKeys.extensionStore(indexUrl), clock) {
            setExtensionStore(
                ExtensionStoreState.newBuilder().apply {
                    name = row.name
                    badgeLabel = row.badge_label
                    signingKey = row.signing_key
                    contactWebsite = row.contact_website
                    contactDiscord = row.contact_discord.orEmpty()
                    isLegacy = row.is_legacy
                    extensionListUrl = row.extension_list_url.orEmpty()
                },
            )
        }
    }

    private suspend fun resolveSourceName(sourceId: Long, clock: Hlc): Change? {
        val row = database.syncQueries.getSyncSourceName(sourceId).awaitAsOneOrNull()
            ?: return tombstone(RecordKeys.sourceName(sourceId), clock)
        return build(RecordKeys.sourceName(sourceId), clock) {
            setSourceName(
                SourceNameState.newBuilder()
                    .setName(row.name)
                    .setLanguage(row.language),
            )
        }
    }

    private fun resolvePreference(key: String, source: String?, clock: Hlc): Change? {
        if (!preferenceWorthSyncing(key)) return null
        val recordKey = if (source == null) {
            RecordKeys.preference(key)
        } else {
            RecordKeys.sourcePreference(source, key)
        }
        val value = if (source == null) {
            appPreferences.all[key]
        } else {
            sourcePreferences(source).all[key]
        }
        val prefValue = value?.toPrefValue() ?: return tombstone(recordKey, clock)
        return build(recordKey, clock) { setPreference(prefValue) }
    }

    /**
     * The current syncable app preference values, for the pairing dump.
     */
    fun preferenceEntries(): Map<String, Any?> =
        appPreferences.all.filterKeys { preferenceWorthSyncing(it) }

    /**
     * The current syncable preference values of one source, for the pairing
     * dump. The source file is opened read-only; uninstalled sources simply
     * have no entries.
     */
    fun sourcePreferenceEntries(source: String): Map<String, Any?> =
        sourcePreferences(source).all.filterKeys { preferenceWorthSyncing(it) }

    private fun preferenceWorthSyncing(key: String): Boolean =
        !Preference.isAppState(key) && (syncPrivate || !Preference.isPrivate(key))

    private fun sourcePreferences(source: String): SharedPreferences =
        appContext.getSharedPreferences(source, Context.MODE_PRIVATE)

    private fun Any?.toPrefValue(): PrefValue? = when (this) {
        is Int -> PrefValue.newBuilder().setIntValue(this).build()
        is Long -> PrefValue.newBuilder().setLongValue(this).build()
        is Float -> PrefValue.newBuilder().setFloatValue(this).build()
        is String -> PrefValue.newBuilder().setStringValue(this).build()
        is Boolean -> PrefValue.newBuilder().setBoolValue(this).build()
        is Set<*> -> (this as? Set<String>)?.let { values ->
            PrefValue.newBuilder()
                .setStringSetValue(
                    PrefValue.StringSet.newBuilder().addAllValues(values),
                )
                .build()
        }
        else -> null
    }

    /**
     * A record is worth pushing when it carries user state, or when this
     * device has synced it before: an unfavorited entry the server still
     * holds must be pushed so the unfavorite propagates, even with nothing
     * else left on it.
     */
    private suspend fun mangaSyncable(sourceId: Long, mangaUrl: String): Boolean =
        database.syncQueries.getSyncMangaExists(sourceId, mangaUrl).awaitAsOne() ||
            syncDb.syncQueries.getRecord(RecordKeys.manga(sourceId, mangaUrl).toByteArray())
                .awaitAsOneOrNull() != null
}

private fun build(
    key: xyz.altaf.kioku.sync.proto.RecordKey,
    clock: Hlc,
    setter: Change.Builder.() -> Unit,
): Change = Change.newBuilder().setKey(key).setModified(clock).apply(setter).build()

private fun tombstone(key: xyz.altaf.kioku.sync.proto.RecordKey, clock: Hlc): Change =
    Change.newBuilder().setKey(key).setModified(clock).setTombstone(true).build()
