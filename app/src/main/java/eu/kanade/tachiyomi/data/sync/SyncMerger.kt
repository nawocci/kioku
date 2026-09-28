package eu.kanade.tachiyomi.data.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import logcat.LogPriority
import mihon.domain.extension.interactor.UpdateExtensionStores
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date
import kotlin.math.max
import kotlin.time.Clock

/**
 * Applies a remote [SyncChangeSetDto] to the local database, mirroring
 * [eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer] merge semantics.
 *
 * Echo suppression happens on two levels while applying:
 * - SQL triggers are muted by the single-row `sync_state.applying` flag, so no
 *   entity type (manga, chapter, category, manga-category, history, extension
 *   store) re-enters the outbox;
 * - manga/chapter rows are written with `is_syncing = 1`, which additionally
 *   suppresses the local `version` bumps that the sync protocol uses as
 *   `client_version`;
 * - preference writes are guarded by the in-memory [SyncApplyState.applying] flag,
 *   observed by [SyncObserver], since preferences never touch SQLite.
 */
class SyncMerger(
    private val database: Database = Injekt.get(),
    private val preferenceStore: PreferenceStore = Injekt.get(),
) {

    class ApplyResult(
        /** Chapter/history entries skipped because their manga/chapter rows don't exist locally yet. */
        val pendingRetry: Boolean,
        /** New library manga inserted, which need a chapter fetch from their source. */
        val newMangaAdded: Boolean,
    )

    suspend fun apply(changes: SyncChangeSetDto): ApplyResult = SyncApplyState.applyMutex.withLock {
        var pendingRetry = false
        var newMangaAdded = false

        SyncApplyState.applying.set(true)
        setDbApplying(true)
        try {
            applyExtensionStores(changes.extensionStores)
            applyCategories(changes.categories)

            for (dto in changes.mangas) {
                if (applyManga(dto)) newMangaAdded = true
            }
            for (dto in changes.chapters) {
                if (!applyChapter(dto)) pendingRetry = true
            }
            for (dto in changes.history) {
                if (!applyHistory(dto)) pendingRetry = true
            }
            applyMangaCategories(changes.mangaCategories)
            applyPreferences(changes.preferences)
        } finally {
            // Clear is_syncing and the applying flag together so triggers resume.
            database.transaction {
                database.mangasQueries.resetIsSyncing()
                database.chaptersQueries.resetIsSyncing()
            }
            setDbApplying(false)
            SyncApplyState.applying.set(false)
        }

        ApplyResult(pendingRetry, newMangaAdded)
    }

    private suspend fun setDbApplying(applying: Boolean) {
        database.sync_changesQueries.setApplying(if (applying) 1L else 0L)
    }

    // region Extension Stores

    private suspend fun applyExtensionStores(dtos: List<SyncExtensionStoreDto>) {
        if (dtos.isEmpty()) return
        var storesChanged = false
        for (dto in dtos) {
            if (dto.deleted) {
                database.extension_storeQueries.delete(dto.indexUrl)
                storesChanged = true
            } else {
                database.extension_storeQueries.upsert(
                    indexUrl = dto.indexUrl,
                    name = dto.name,
                    badgeLabel = dto.badgeLabel,
                    signingKey = dto.signingKey,
                    contactWebsite = "",
                    contactDiscord = null,
                    isLegacy = false,
                    extensionListUrl = null,
                )
                storesChanged = true
            }
        }
        if (storesChanged) {
            // Refresh the available-extension catalog so the new store's entries show up.
            runCatching {
                Injekt.get<UpdateExtensionStores>()()
            }
        }
    }

    // endregion

    // region Categories

    private suspend fun applyCategories(dtos: List<SyncCategoryDto>) {
        if (dtos.isEmpty()) return
        val dbCategories = database.categoriesQueries.getCategories().awaitAsList()
        val byName = dbCategories.associateBy { it.name }
        var nextOrder = (dbCategories.maxOfOrNull { it.order } ?: -1L) + 1

        for (dto in dtos) {
            val existing = byName[dto.name]
            if (dto.deleted) {
                if (existing != null && existing.id > 0) {
                    database.categoriesQueries.delete(existing.id)
                }
                continue
            }
            if (existing == null) {
                database.categoriesQueries.insert(dto.name, nextOrder++, dto.flags)
            }
        }
    }

    // endregion

    // region Manga

    /** @return true if a new library manga was inserted. */
    private suspend fun applyManga(dto: SyncMangaDto): Boolean {
        val dbManga = database.mangasQueries
            .getMangaByUrlAndSource(dto.url, dto.sourceId)
            .awaitAsOneOrNull()

        if (dto.deleted) {
            if (dbManga != null && dbManga.favorite) {
                database.mangasQueries.update(
                    source = null, url = null, artist = null, author = null, description = null,
                    genre = null, title = null, status = null, thumbnailUrl = null,
                    favorite = false, lastUpdate = null, nextUpdate = null, calculateInterval = null,
                    initialized = null, viewer = null, chapterFlags = null, coverLastModified = null,
                    dateAdded = null, mangaId = dbManga._id, updateStrategy = null, version = null,
                    isSyncing = 1, notes = null, memo = null,
                )
            }
            return false
        }

        if (dbManga == null) {
            database.mangasQueries.insertReturningId(
                source = dto.sourceId,
                url = dto.url,
                artist = null,
                author = null,
                description = null,
                genre = null,
                title = dto.title,
                status = 0,
                thumbnailUrl = null,
                favorite = dto.favorite,
                lastUpdate = 0,
                nextUpdate = 0,
                calculateInterval = 0,
                initialized = false,
                viewerFlags = dto.viewerFlags,
                chapterFlags = dto.chapterFlags,
                coverLastModified = 0,
                dateAdded = dto.dateAdded.takeIf { it > 0 } ?: Clock.System.now().toEpochMilliseconds(),
                updateStrategy = decodeUpdateStrategy(dto.updateStrategy),
                version = dto.clientVersion,
                notes = dto.notes,
                memo = EMPTY_MEMO,
            )
                .awaitAsOne()
            return true
        }

        val remoteWins = dto.clientVersion > dbManga.version
        val favorite = dbManga.favorite || dto.favorite
        if (!remoteWins && favorite == dbManga.favorite) return false

        database.mangasQueries.update(
            source = null,
            url = null,
            artist = null,
            author = null,
            description = null,
            genre = null,
            title = if (remoteWins) dto.title else null,
            status = null,
            thumbnailUrl = null,
            favorite = favorite,
            lastUpdate = null,
            nextUpdate = null,
            calculateInterval = null,
            initialized = null,
            viewer = if (remoteWins) dto.viewerFlags else null,
            chapterFlags = if (remoteWins) dto.chapterFlags else null,
            coverLastModified = null,
            dateAdded = null,
            mangaId = dbManga._id,
            updateStrategy = if (remoteWins) {
                UpdateStrategyColumnAdapter.encode(decodeUpdateStrategy(dto.updateStrategy))
            } else {
                null
            },
            version = if (remoteWins) dto.clientVersion else null,
            isSyncing = 1,
            notes = if (remoteWins) dto.notes else null,
            memo = null,
        )
        return false
    }

    // endregion

    // region Chapters

    /** @return false if the chapter couldn't be applied (manga/chapter missing locally). */
    private suspend fun applyChapter(dto: SyncChapterDto): Boolean {
        val dbManga = database.mangasQueries
            .getMangaByUrlAndSource(dto.mangaUrl, dto.mangaSourceId)
            .awaitAsOneOrNull()
            ?: return false
        val dbChapter = database.chaptersQueries
            .getChapterByUrlAndMangaId(dto.url, dbManga._id)
            .awaitAsOneOrNull()
            ?: return false

        val read = dbChapter.read || dto.read
        val bookmark = dbChapter.bookmark || dto.bookmark
        val page = when {
            dbChapter.read && !dto.read -> dbChapter.last_page_read
            dto.read && !dbChapter.read -> dto.lastPageRead
            else -> max(dbChapter.last_page_read, dto.lastPageRead)
        }
        // Updates-page dates: earliest wins. 0 means unknown (old client) and never
        // overwrites a known date. This lets a fresh device that bulk-fetched every
        // chapter with dateFetch=now converge back to the original dates and thus
        // the exact same Updates list.
        val dateFetch = minNonZero(dbChapter.date_fetch, dto.dateFetch)
        val dateUpload = minNonZero(dbChapter.date_upload, dto.dateUpload)
        if (read == dbChapter.read && bookmark == dbChapter.bookmark && page == dbChapter.last_page_read &&
            dateFetch == dbChapter.date_fetch && dateUpload == dbChapter.date_upload
        ) {
            return true
        }

        database.chaptersQueries.update(
            mangaId = null, url = null, name = null, scanlator = null,
            read = read, bookmark = bookmark, lastPageRead = page,
            chapterNumber = null, sourceOrder = null, dateFetch = dateFetch, dateUpload = dateUpload,
            chapterId = dbChapter._id,
            version = max(dbChapter.version, dto.clientVersion),
            isSyncing = 1,
            memo = null,
        )
        return true
    }

    // endregion

    // region History

    /** @return false if the entry couldn't be applied (manga/chapter missing locally). */
    private suspend fun applyHistory(dto: SyncHistoryDto): Boolean {
        val dbManga = database.mangasQueries
            .getMangaByUrlAndSource(dto.mangaUrl, dto.mangaSourceId)
            .awaitAsOneOrNull()
            ?: return false
        val dbChapter = database.chaptersQueries
            .getChapterByUrlAndMangaId(dto.chapterUrl, dbManga._id)
            .awaitAsOneOrNull()
            ?: return false

        val dbHistory = database.historyQueries
            .getHistoryByChapterUrlAndMangaId(dto.chapterUrl, dbManga._id)
            .awaitAsOneOrNull()

        val readAt = max(dto.lastRead, dbHistory?.last_read?.time ?: 0L)
        if (readAt <= 0L) return true
        // history.upsert accumulates time_read; pass the delta to arrive at max().
        val durationDelta = max(dto.readDuration, dbHistory?.time_read ?: 0L) - (dbHistory?.time_read ?: 0L)

        database.historyQueries.upsert(dbChapter._id, Date(readAt), durationDelta)
        return true
    }

    // endregion

    // region Manga categories

    private suspend fun applyMangaCategories(dtos: List<SyncMangaCategoryDto>) {
        if (dtos.isEmpty()) return

        dtos.groupBy { it.mangaSourceId to it.mangaUrl }.forEach { entry ->
            val (sourceId, mangaUrl) = entry.key
            val dbManga = database.mangasQueries
                .getMangaByUrlAndSource(mangaUrl, sourceId)
                .awaitAsOneOrNull()
                ?: return@forEach

            val categoriesByName = database.categoriesQueries.getCategories().awaitAsList()
                .associateBy { it.name }

            val current = database.categoriesQueries.getCategoriesByMangaId(dbManga._id)
                .awaitAsList()
                .map { it.name }
                .toMutableSet()
            entry.value.forEach { dto ->
                if (dto.deleted) current.remove(dto.category) else current.add(dto.category)
            }
            // Default category (id 0) is represented by an empty set.
            val ids = current.mapNotNull { categoriesByName[it]?.id }

            database.transaction {
                // Mark the manga as syncing so the manga-category version trigger
                // doesn't bump the manga's client_version on an applied change.
                database.mangasQueries.update(
                    source = null, url = null, artist = null, author = null, description = null,
                    genre = null, title = null, status = null, thumbnailUrl = null,
                    favorite = null, lastUpdate = null, nextUpdate = null, calculateInterval = null,
                    initialized = null, viewer = null, chapterFlags = null, coverLastModified = null,
                    dateAdded = null, mangaId = dbManga._id, updateStrategy = null, version = null,
                    isSyncing = 1, notes = null, memo = null,
                )
                database.mangas_categoriesQueries.deleteMangaCategoryByMangaId(dbManga._id)
                ids.forEach { database.mangas_categoriesQueries.insert(dbManga._id, it) }
            }
        }
    }

    // endregion

    // region Preferences

    private suspend fun applyPreferences(dtos: List<SyncPreferenceDto>) {
        if (dtos.isEmpty()) return

        for (dto in dtos) {
            try {
                if (!isSyncablePreference(dto.key)) {
                    continue
                }

                if (dto.deleted) {
                    deletePreference(dto.key)
                    continue
                }
                val value = dto.value ?: continue
                when (dto.type) {
                    "int" -> value.jsonPrimitive.longOrNull?.let {
                        preferenceStore.getInt(dto.key).set(it.toInt())
                    }
                    "long" -> value.jsonPrimitive.longOrNull?.let {
                        preferenceStore.getLong(dto.key).set(it)
                    }
                    "float" -> value.jsonPrimitive.doubleOrNull?.let {
                        preferenceStore.getFloat(dto.key).set(it.toFloat())
                    }
                    "boolean" -> value.jsonPrimitive.booleanOrNull?.let {
                        preferenceStore.getBoolean(dto.key).set(it)
                    }
                    "string" -> if (value is JsonPrimitive && value.isString) {
                        preferenceStore.getString(dto.key).set(value.content)
                    }
                    "stringset" -> if (value is JsonArray) {
                        preferenceStore.getStringSet(dto.key)
                            .set(value.mapNotNull { (it as? JsonPrimitive)?.content }.toSet())
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Sync: failed to apply preference <${dto.key}>" }
            }
        }
    }

    private fun deletePreference(key: String) {
        val current = preferenceStore.getAll()[key] ?: return
        when (current) {
            is Int -> preferenceStore.getInt(key).delete()
            is Long -> preferenceStore.getLong(key).delete()
            is Float -> preferenceStore.getFloat(key).delete()
            is Boolean -> preferenceStore.getBoolean(key).delete()
            is String -> preferenceStore.getString(key).delete()
            is Set<*> -> preferenceStore.getStringSet(key).delete()
        }
    }

    // endregion

    companion object {
        private val EMPTY_MEMO = JsonObject(emptyMap())

        /**
         * Preferences that must never sync because they are device-specific or
         * security-sensitive, so pushing them to another device is wrong or harmful.
         */
        val PREFERENCE_DENYLIST: Set<String> = buildSet {
            // Reference local category ids, which differ per device.
            addAll(LibraryPreferences.categoryPreferenceKeys)
            addAll(DownloadPreferences.categoryPreferenceKeys)

            // Security: a device with no biometrics/enrollment would either be locked
            // out of its own library or silently disable the lock. Keep it per-device.
            add("use_biometric_lock")
            add("lock_app_after")
            add("secure_screen_v2")
            add("hide_notification_content")

            // Device form factor: a tablet's grid/layout choice is meaningless on a phone.
            add("tablet_ui_mode")
            add("pref_library_columns_portrait_key")
            add("pref_library_columns_landscape_key")

            // Local network configuration; a per-device DNS choice must not leak across devices.
            add("doh_provider")
            add("default_user_agent")

            // Superseded by the synced extension_store table; keep it from resurrecting.
            add("extension_repos")
        }

        /** Private (secrets) and app-state keys never leave the device. */
        const val SYNC_OWN_PREFIX = "sync_"

        /**
         * Updates-page state that is technically app-state but must sync so a fresh
         * device shows the same Updates badge/list: last library-update timestamp
         * and unseen-updates count. Latest writer wins.
         */
        val SYNCABLE_APP_STATE_KEYS: Set<String> = setOf(
            Preference.appStateKey("library_update_last_timestamp"),
            Preference.appStateKey("library_unseen_updates_count"),
        )

        fun isSyncablePreference(key: String): Boolean {
            if (key in SYNCABLE_APP_STATE_KEYS) return key !in PREFERENCE_DENYLIST
            return !Preference.isPrivate(key) &&
                !Preference.isAppState(key) &&
                key !in PREFERENCE_DENYLIST &&
                !key.startsWith(SYNC_OWN_PREFIX)
        }

        /** Earliest-wins merge for Updates dates; 0 means unknown and never wins. */
        fun minNonZero(local: Long, remote: Long): Long = when {
            local <= 0L -> remote
            remote <= 0L -> local
            else -> minOf(local, remote)
        }

        fun decodeUpdateStrategy(name: String): UpdateStrategy = try {
            UpdateStrategy.valueOf(name)
        } catch (_: IllegalArgumentException) {
            UpdateStrategy.ALWAYS_UPDATE
        }

        fun preferenceToJson(value: Any): Pair<String, JsonElement>? = when (value) {
            is Int -> "int" to JsonPrimitive(value)
            is Long -> "long" to JsonPrimitive(value)
            is Float -> "float" to JsonPrimitive(value)
            is Boolean -> "boolean" to JsonPrimitive(value)
            is String -> "string" to JsonPrimitive(value)
            is Set<*> -> {
                val strings = value.filterIsInstance<String>()
                "stringset" to JsonArray(strings.map { JsonPrimitive(it) })
            }
            else -> null
        }
    }
}
