package eu.kanade.tachiyomi.data.sync

import android.content.Context
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.library.MetadataUpdateJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.sync.service.SyncPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

/**
 * Orchestrates sync with the server.
 *
 * First sync (watermark 0) pushes a full snapshot with `since = 0`; the response
 * carries everything the server holds that this device didn't just push, so the
 * initial merge is one round trip. Steady state drains the `sync_changes` outbox
 * (fed by DB triggers) and applies the foreign changes piggybacked on the push
 * response. [pullNow] is the pull-only variant used on app start.
 *
 * The watermark only advances once every fetched change could be applied; entries
 * that can't be applied yet (e.g. chapter state for a manga whose chapters haven't
 * been fetched) hold it back and trigger a library update + bounded re-pull.
 */
class SyncManager(
    private val context: Context,
    private val database: Database = Injekt.get(),
    private val syncApi: SyncApi = Injekt.get(),
    private val preferences: SyncPreferences = Injekt.get(),
    private val preferenceStore: PreferenceStore = Injekt.get(),
) {

    private val merger = SyncMerger(database, preferenceStore)
    private val mutex = Mutex()

    /** Re-pull attempts allowed for one watermark before force-advancing. */
    private var watermarkRetries = 0

    suspend fun syncNow(): Boolean {
        if (!mutex.tryLock()) return false
        try {
            if (!syncApi.isConfigured) return false
            ensureDeviceId()

            var watermark = preferences.lastSyncRevision.get()
            if (watermark == 0L) {
                val changes = buildSnapshot()
                val response = syncApi.push(since = watermark, changes = changes)
                logcat(LogPriority.DEBUG) {
                    "Sync PUSH snapshot sent=${changes.summary()} resp.rev=${response.rev} " +
                        "recv=${response.changes.summary()}"
                }
                val applied = merger.apply(response.changes)
                if (applied.newMangaAdded) {
                    MetadataUpdateJob.startNow(context)
                }
                if (applied.pendingRetry && watermarkRetries < MAX_WATERMARK_RETRIES) {
                    watermarkRetries++
                    logcat(LogPriority.WARN) { "Sync: holding watermark for retry ($watermarkRetries)" }
                } else {
                    watermarkRetries = 0
                    preferences.lastSyncRevision.set(response.rev)
                }
            } else {
                var iterations = 0
                while (iterations < MAX_DRAIN_ITERATIONS) {
                    iterations++
                    val (changes, maxQueueId) = drainQueue()
                    val response = syncApi.push(since = watermark, changes = changes)
                    logcat(LogPriority.DEBUG) {
                        "Sync PUSH watermark=$watermark sent=${changes.summary()} resp.rev=${response.rev} " +
                            "recv=${response.changes.summary()}"
                    }

                    if (maxQueueId != null) {
                        database.sync_changesQueries.deleteUpTo(maxQueueId)
                    }

                    val applied = merger.apply(response.changes)
                    if (applied.newMangaAdded) {
                        MetadataUpdateJob.startNow(context)
                    }

                    if (applied.pendingRetry && watermarkRetries < MAX_WATERMARK_RETRIES) {
                        watermarkRetries++
                        logcat(LogPriority.WARN) { "Sync: holding watermark for retry ($watermarkRetries)" }
                    } else {
                        watermarkRetries = 0
                        preferences.lastSyncRevision.set(response.rev)
                        watermark = response.rev
                    }

                    // If queue was empty or partial batch, no more pending items.
                    if (maxQueueId == null) {
                        break
                    }
                }
            }

            preferences.lastSyncTimestamp.set(System.currentTimeMillis())
            preferences.lastSyncError.set("")
            return true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Sync failed" }
            preferences.lastSyncError.set(e.message ?: e.javaClass.simpleName)
            return false
        } finally {
            mutex.unlock()
        }
    }

    /** Resets sync watermark and clears the outbox, allowing a fresh snapshot push. */
    suspend fun resetSync() {
        preferences.lastSyncRevision.set(0L)
        preferences.lastSyncTimestamp.set(0L)
        preferences.lastSyncError.set("")
        database.sync_changesQueries.clear()
    }

    /** Pull-only sync (app start): fetch and apply foreign changes. */
    suspend fun pullNow(): Boolean {
        if (!mutex.tryLock()) return false
        try {
            if (!syncApi.isConfigured) return false
            ensureDeviceId()

            val watermark = preferences.lastSyncRevision.get()
            val response = syncApi.pull(watermark)
            logcat(LogPriority.DEBUG) {
                "Sync PULL watermark=$watermark resp.rev=${response.rev} recv=${response.changes.summary()}"
            }

            val applied = merger.apply(response.changes)
            logcat(LogPriority.DEBUG) {
                "Sync PULL applied pending=${applied.pendingRetry} newManga=${applied.newMangaAdded}"
            }
            if (applied.newMangaAdded) {
                MetadataUpdateJob.startNow(context)
            }

            if (applied.pendingRetry && watermarkRetries < MAX_WATERMARK_RETRIES) {
                watermarkRetries++
            } else {
                watermarkRetries = 0
                preferences.lastSyncRevision.set(response.rev)
            }

            preferences.lastSyncTimestamp.set(System.currentTimeMillis())
            preferences.lastSyncError.set("")
            return true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Sync pull failed" }
            preferences.lastSyncError.set(e.message ?: e.javaClass.simpleName)
            return false
        } finally {
            mutex.unlock()
        }
    }

    private fun ensureDeviceId() {
        if (preferences.syncDeviceId.get().isBlank()) {
            preferences.syncDeviceId.set(UUID.randomUUID().toString())
        }
    }

    // region Outbox drain

    private suspend fun drainQueue(): Pair<SyncChangeSetDto, Long?> {
        val batch = database.sync_changesQueries.getBatch(QUEUE_BATCH_SIZE).awaitAsList()
        if (batch.isEmpty()) return SyncChangeSetDto() to null

        val mangas = LinkedHashMap<Pair<Long, String>, SyncMangaDto>()
        val chapters = LinkedHashMap<Triple<Long, String, String>, SyncChapterDto>()
        val categories = LinkedHashMap<String, SyncCategoryDto>()
        val mangaCategories = LinkedHashMap<String, SyncMangaCategoryDto>()
        val history = LinkedHashMap<Triple<Long, String, String>, SyncHistoryDto>()
        val prefs = LinkedHashMap<String, SyncPreferenceDto>()
        val extensionStores = LinkedHashMap<String, SyncExtensionStoreDto>()

        for (row in batch) {
            when (row.entity_type) {
                ENTITY_MANGA -> resolveManga(row.manga_source, row.manga_url, row.change_type)
                    ?.let { mangas[it.sourceId to it.url] = it }
                ENTITY_CHAPTER -> resolveChapter(row.manga_source, row.manga_url, row.chapter_url)
                    ?.let { chapters[Triple(it.mangaSourceId, it.mangaUrl, it.url)] = it }
                ENTITY_CATEGORY -> resolveCategory(row.category_name, row.change_type)
                    ?.let { categories[it.name] = it }
                ENTITY_MANGA_CATEGORY -> resolveMangaCategory(
                    row.manga_source,
                    row.manga_url,
                    row.category_name,
                    row.change_type,
                )?.let { mangaCategories["${it.mangaSourceId}|${it.mangaUrl}|${it.category}"] = it }
                ENTITY_HISTORY -> resolveHistory(row.manga_source, row.manga_url, row.chapter_url)
                    ?.let { history[Triple(it.mangaSourceId, it.mangaUrl, it.chapterUrl)] = it }
                ENTITY_PREFERENCE -> resolvePreference(row.pref_key, row.change_type)
                    ?.let { prefs[it.key] = it }
                ENTITY_EXTENSION_STORE -> resolveExtensionStore(row.extension_store_url, row.change_type)
                    ?.let { extensionStores[it.indexUrl] = it }
            }
        }

        return SyncChangeSetDto(
            mangas = mangas.values.toList(),
            chapters = chapters.values.toList(),
            categories = categories.values.toList(),
            mangaCategories = mangaCategories.values.toList(),
            history = history.values.toList(),
            preferences = prefs.values.toList(),
            extensionStores = extensionStores.values.toList(),
        ) to batch.last()._id
    }

    private suspend fun resolveManga(source: Long?, url: String?, changeType: String): SyncMangaDto? {
        if (url == null || source == null) return null
        val dbManga = database.mangasQueries.getMangaByUrlAndSource(url, source)
            .awaitAsOneOrNull()
        // Tombstone when the row is gone or no longer in the library.
        if (changeType == "delete" || dbManga == null || !dbManga.favorite) {
            return SyncMangaDto(sourceId = source, url = url, favorite = false, updateStrategy = "", deleted = true)
        }
        return dbManga.toDto()
    }

    private fun tachiyomi.data.Mangas.toDto(): SyncMangaDto {
        return SyncMangaDto(
            sourceId = source,
            url = url,
            title = title,
            favorite = favorite,
            chapterFlags = chapter_flags,
            viewerFlags = viewer,
            updateStrategy = update_strategy.name,
            notes = notes,
            dateAdded = date_added,
            clientVersion = version,
            deleted = false,
        )
    }

    private suspend fun resolveChapter(source: Long?, mangaUrl: String?, chapterUrl: String?): SyncChapterDto? {
        if (source == null || mangaUrl == null || chapterUrl == null) return null
        val dbManga = database.mangasQueries.getMangaByUrlAndSource(mangaUrl, source)
            .awaitAsOneOrNull() ?: return null
        val dbChapter = database.chaptersQueries.getChapterByUrlAndMangaId(chapterUrl, dbManga._id)
            .awaitAsOneOrNull() ?: return null
        return SyncChapterDto(
            mangaSourceId = source,
            mangaUrl = mangaUrl,
            url = chapterUrl,
            read = dbChapter.read,
            bookmark = dbChapter.bookmark,
            lastPageRead = dbChapter.last_page_read,
            clientVersion = dbChapter.version,
        )
    }

    private suspend fun resolveCategory(name: String?, changeType: String): SyncCategoryDto? {
        if (name == null) return null
        if (changeType == "delete") return SyncCategoryDto(name = name, deleted = true)
        val dbCategory = database.categoriesQueries.getCategories().awaitAsList()
            .firstOrNull { it.name == name }
            ?: return SyncCategoryDto(name = name, deleted = true)
        return SyncCategoryDto(name = dbCategory.name, order = dbCategory.order, flags = dbCategory.flags)
    }

    private fun resolveMangaCategory(
        source: Long?,
        mangaUrl: String?,
        category: String?,
        changeType: String,
    ): SyncMangaCategoryDto? {
        if (source == null || mangaUrl == null || category == null) return null
        return SyncMangaCategoryDto(
            mangaSourceId = source,
            mangaUrl = mangaUrl,
            category = category,
            deleted = changeType == "delete",
        )
    }

    private suspend fun resolveHistory(source: Long?, mangaUrl: String?, chapterUrl: String?): SyncHistoryDto? {
        if (source == null || mangaUrl == null || chapterUrl == null) return null
        val dbManga = database.mangasQueries.getMangaByUrlAndSource(mangaUrl, source)
            .awaitAsOneOrNull() ?: return null
        val dbHistory = database.historyQueries.getHistoryByChapterUrlAndMangaId(chapterUrl, dbManga._id)
            .awaitAsOneOrNull() ?: return null
        val lastRead = dbHistory.last_read?.time ?: 0L
        if (lastRead <= 0L) return null
        return SyncHistoryDto(
            mangaSourceId = source,
            mangaUrl = mangaUrl,
            chapterUrl = chapterUrl,
            lastRead = lastRead,
            readDuration = dbHistory.time_read,
        )
    }

    private fun resolvePreference(key: String?, changeType: String): SyncPreferenceDto? {
        if (key == null || !isSyncablePreference(key)) return null
        if (changeType == "delete") {
            return SyncPreferenceDto(key = key, type = "string", deleted = true)
        }
        val value = preferenceStore.getAll()[key] ?: return null
        val (type, json) = SyncMerger.preferenceToJson(value) ?: return null
        return SyncPreferenceDto(key = key, type = type, value = json)
    }

    private suspend fun resolveExtensionStore(indexUrl: String?, changeType: String): SyncExtensionStoreDto? {
        if (indexUrl == null) return null
        if (changeType == "delete") return SyncExtensionStoreDto(indexUrl = indexUrl, deleted = true)
        val dbStore = database.extension_storeQueries.get(indexUrl).awaitAsOneOrNull()
            ?: return SyncExtensionStoreDto(indexUrl = indexUrl, deleted = true)
        return SyncExtensionStoreDto(
            indexUrl = dbStore.index_url,
            name = dbStore.name,
            badgeLabel = dbStore.badge_label,
            signingKey = dbStore.signing_key,
            deleted = false,
        )
    }

    // endregion

    // region Snapshot (first sync)

    private suspend fun buildSnapshot(): SyncChangeSetDto {
        val dbMangas = database.mangasQueries.getFavorites().awaitAsList() +
            database.mangasQueries.getReadMangaNotInLibrary().awaitAsList()

        val mangas = dbMangas.map { it.toDto() }

        val chapters = mutableListOf<SyncChapterDto>()
        val history = mutableListOf<SyncHistoryDto>()
        val mangaCategories = mutableListOf<SyncMangaCategoryDto>()

        val dbCategories = database.categoriesQueries.getCategories().awaitAsList()
            .filter { it.id > 0 }

        for (manga in dbMangas) {
            database.chaptersQueries.getChaptersByMangaId(manga._id, 0L).awaitAsList()
                // Only user state matters; default-state chapters carry no information.
                .filter { it.read || it.bookmark || it.last_page_read > 0L }
                .forEach { chapter ->
                    chapters.add(
                        SyncChapterDto(
                            mangaSourceId = manga.source,
                            mangaUrl = manga.url,
                            url = chapter.url,
                            read = chapter.read,
                            bookmark = chapter.bookmark,
                            lastPageRead = chapter.last_page_read,
                            clientVersion = chapter.version,
                        ),
                    )
                }

            database.historyQueries.getHistoryByMangaId(manga._id).awaitAsList()
                .filter { (it.last_read?.time ?: 0L) > 0L }
                .forEach { row ->
                    val chapter = database.chaptersQueries.getChapterById(row.chapter_id)
                        .awaitAsOneOrNull() ?: return@forEach
                    history.add(
                        SyncHistoryDto(
                            mangaSourceId = manga.source,
                            mangaUrl = manga.url,
                            chapterUrl = chapter.url,
                            lastRead = row.last_read!!.time,
                            readDuration = row.time_read,
                        ),
                    )
                }

            database.categoriesQueries.getCategoriesByMangaId(manga._id).awaitAsList()
                .forEach { category ->
                    mangaCategories.add(
                        SyncMangaCategoryDto(
                            mangaSourceId = manga.source,
                            mangaUrl = manga.url,
                            category = category.name,
                        ),
                    )
                }
        }

        val categories = dbCategories.map { SyncCategoryDto(name = it.name, order = it.order, flags = it.flags) }

        val preferences = preferenceStore.getAll()
            .filterKeys { isSyncablePreference(it) }
            .mapNotNull { (key, value) ->
                value ?: return@mapNotNull null
                SyncMerger.preferenceToJson(value)?.let { (type, json) ->
                    SyncPreferenceDto(key = key, type = type, value = json)
                }
            }

        val extensionStores = database.extension_storeQueries.getAll().awaitAsList()
            .map {
                SyncExtensionStoreDto(
                    indexUrl = it.index_url,
                    name = it.name,
                    badgeLabel = it.badge_label,
                    signingKey = it.signing_key,
                    deleted = false,
                )
            }

        return SyncChangeSetDto(
            mangas = mangas,
            chapters = chapters,
            categories = categories,
            mangaCategories = mangaCategories,
            history = history,
            preferences = preferences,
            extensionStores = extensionStores,
        )
    }

    // endregion

    companion object {
        private const val QUEUE_BATCH_SIZE = 500L
        private const val MAX_DRAIN_ITERATIONS = 20
        private const val MAX_WATERMARK_RETRIES = 3

        const val ENTITY_MANGA = "manga"
        const val ENTITY_CHAPTER = "chapter"
        const val ENTITY_CATEGORY = "category"
        const val ENTITY_MANGA_CATEGORY = "manga_category"
        const val ENTITY_HISTORY = "history"
        const val ENTITY_PREFERENCE = "preference"
        const val ENTITY_EXTENSION_STORE = "extension_store"

        /** Private (secrets) and app-state keys never leave the device. */
        fun isSyncablePreference(key: String): Boolean {
            return !Preference.isPrivate(key) &&
                !Preference.isAppState(key) &&
                key !in SyncMerger.PREFERENCE_DENYLIST &&
                !key.startsWith(SYNC_OWN_PREFIX)
        }

        const val SYNC_OWN_PREFIX = "sync_"
    }
}

private fun SyncChangeSetDto.summary(): String {
    return "{mangas=${mangas.size},chapters=${chapters.size},categories=${categories.size}," +
        "links=${mangaCategories.size},history=${history.size},prefs=${preferences.size},stores=${extensionStores.size}}"
}
