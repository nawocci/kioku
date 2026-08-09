package eu.kanade.tachiyomi.data.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import logcat.LogPriority
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

/**
 * Applies a remote [SyncChangeSetDto] to the local database, mirroring
 * [eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer] merge
 * semantics. Manga/chapter writes are done with is_syncing = 1 so the outbox
 * triggers don't re-enqueue them; any remaining echoes (categories, history,
 * manga-category links) are idempotent no-ops on the server.
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

    suspend fun apply(changes: SyncChangeSetDto): ApplyResult {
        var pendingRetry = false
        var newMangaAdded = false

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

        return ApplyResult(pendingRetry, newMangaAdded)
    }

    // region Categories

    private suspend fun applyCategories(dtos: List<SyncCategoryDto>) {
        if (dtos.isEmpty()) return
        val dbCategories = database.categoriesQueries.getCategories().awaitAsList()
        val byName = dbCategories.associateBy { it.name }

        for (dto in dtos) {
            val existing = byName[dto.name]
            if (dto.deleted) {
                if (existing != null && existing.id > 0) {
                    database.categoriesQueries.delete(existing.id)
                }
                continue
            }
            if (existing == null) {
                val nextOrder = (dbCategories.maxOfOrNull { it.order } ?: -1) + 1
                database.categoriesQueries.insert(dto.name, nextOrder, dto.flags)
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
                dateAdded = dto.dateAdded.takeIf { it > 0 } ?: System.currentTimeMillis(),
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
        if (read == dbChapter.read && bookmark == dbChapter.bookmark && page == dbChapter.last_page_read) {
            return true
        }

        database.chaptersQueries.update(
            mangaId = null, url = null, name = null, scanlator = null,
            read = read, bookmark = bookmark, lastPageRead = page,
            chapterNumber = null, sourceOrder = null, dateFetch = null, dateUpload = null,
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

            // Ensure referenced categories exist.
            val names = entry.value.map { it.category }
            val existing = database.categoriesQueries.getCategories().awaitAsList()
            val existingNames = existing.map { it.name }.toSet()
            var nextOrder = (existing.maxOfOrNull { it.order } ?: -1) + 1
            names.forEach { name ->
                if (name !in existingNames) {
                    database.categoriesQueries.insert(name, nextOrder++, 0)
                }
            }
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
                database.mangas_categoriesQueries.deleteMangaCategoryByMangaId(dbManga._id)
                ids.forEach { database.mangas_categoriesQueries.insert(dbManga._id, it) }
            }
        }
    }

    // endregion

    // region Preferences

    private fun applyPreferences(dtos: List<SyncPreferenceDto>) {
        if (dtos.isEmpty()) return
        val all = preferenceStore.getAll()

        for (dto in dtos) {
            try {
                if (dto.key in PREFERENCE_DENYLIST ||
                    Preference.isPrivate(dto.key) ||
                    Preference.isAppState(dto.key)
                ) {
                    continue
                }
                val current = all[dto.key]
                if (current == null) continue // set-if-exists, like PreferenceRestorer

                if (dto.deleted) {
                    deletePreference(dto.key, current)
                    continue
                }
                val value = dto.value ?: continue
                when (current) {
                    is Int -> value.jsonPrimitive.longOrNull?.let {
                        preferenceStore.getInt(dto.key).set(it.toInt())
                    }
                    is Long -> value.jsonPrimitive.longOrNull?.let {
                        preferenceStore.getLong(dto.key).set(it)
                    }
                    is Float -> value.jsonPrimitive.doubleOrNull?.let {
                        preferenceStore.getFloat(dto.key).set(it.toFloat())
                    }
                    is Boolean -> value.jsonPrimitive.booleanOrNull?.let {
                        preferenceStore.getBoolean(dto.key).set(it)
                    }
                    is String -> if (value is JsonPrimitive && value.isString) {
                        preferenceStore.getString(dto.key).set(value.content)
                    }
                    is Set<*> -> if (value is JsonArray) {
                        preferenceStore.getStringSet(dto.key)
                            .set(value.mapNotNull { (it as? JsonPrimitive)?.content }.toSet())
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Sync: failed to apply preference <${dto.key}>" }
            }
        }
    }

    private fun deletePreference(key: String, current: Any) {
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

        /** Preferences referencing local category ids can't sync (ids differ per device). */
        val PREFERENCE_DENYLIST: Set<String> =
            LibraryPreferences.categoryPreferenceKeys + DownloadPreferences.categoryPreferenceKeys

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
