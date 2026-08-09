package eu.kanade.tachiyomi.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire DTOs for the mihon-sync server. Entity identity mirrors backup restore:
 * manga by (sourceId, url), chapters by (manga, chapter url), categories by name.
 */
@Serializable
data class SyncMangaDto(
    @SerialName("source_id") val sourceId: Long,
    val url: String,
    val title: String = "",
    // favorite and updateStrategy must not have defaults: kotlinx omits
    // default-valued properties and the server would read them as false / "".
    val favorite: Boolean,
    @SerialName("chapter_flags") val chapterFlags: Long = 0,
    @SerialName("viewer_flags") val viewerFlags: Long = 0,
    @SerialName("update_strategy") val updateStrategy: String,
    val notes: String = "",
    @SerialName("date_added") val dateAdded: Long = 0,
    @SerialName("client_version") val clientVersion: Long = 0,
    val deleted: Boolean = false,
)

@Serializable
data class SyncChapterDto(
    @SerialName("manga_source_id") val mangaSourceId: Long,
    @SerialName("manga_url") val mangaUrl: String,
    val url: String,
    val read: Boolean = false,
    val bookmark: Boolean = false,
    @SerialName("last_page_read") val lastPageRead: Long = 0,
    @SerialName("client_version") val clientVersion: Long = 0,
)

@Serializable
data class SyncCategoryDto(
    val name: String,
    val order: Long = 0,
    val flags: Long = 0,
    val deleted: Boolean = false,
)

@Serializable
data class SyncMangaCategoryDto(
    @SerialName("manga_source_id") val mangaSourceId: Long,
    @SerialName("manga_url") val mangaUrl: String,
    val category: String,
    val deleted: Boolean = false,
)

@Serializable
data class SyncHistoryDto(
    @SerialName("manga_source_id") val mangaSourceId: Long,
    @SerialName("manga_url") val mangaUrl: String,
    @SerialName("chapter_url") val chapterUrl: String,
    @SerialName("last_read") val lastRead: Long = 0,
    @SerialName("read_duration") val readDuration: Long = 0,
)

@Serializable
data class SyncPreferenceDto(
    val key: String,
    val type: String,
    val value: JsonElement? = null,
    val deleted: Boolean = false,
)

@Serializable
data class SyncChangeSetDto(
    val mangas: List<SyncMangaDto> = emptyList(),
    val chapters: List<SyncChapterDto> = emptyList(),
    val categories: List<SyncCategoryDto> = emptyList(),
    @SerialName("manga_categories") val mangaCategories: List<SyncMangaCategoryDto> = emptyList(),
    val history: List<SyncHistoryDto> = emptyList(),
    val preferences: List<SyncPreferenceDto> = emptyList(),
)

@Serializable
data class SyncPushRequest(
    @SerialName("device_id") val deviceId: String,
    // Caller's watermark; the response carries other devices' changes since it.
    val since: Long,
    val changes: SyncChangeSetDto,
)

@Serializable
data class SyncPushResponse(
    val rev: Long,
    val changes: SyncChangeSetDto = SyncChangeSetDto(),
)

@Serializable
data class SyncPullResponse(
    val rev: Long,
    val changes: SyncChangeSetDto,
)

@Serializable
data class SyncAuthCheckResponse(
    val ok: Boolean,
)
