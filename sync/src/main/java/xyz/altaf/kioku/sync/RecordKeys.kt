package xyz.altaf.kioku.sync

import xyz.altaf.kioku.sync.proto.CategoryKey
import xyz.altaf.kioku.sync.proto.ChapterKey
import xyz.altaf.kioku.sync.proto.HistoryKey
import xyz.altaf.kioku.sync.proto.MangaKey
import xyz.altaf.kioku.sync.proto.RecordKey
import xyz.altaf.kioku.sync.proto.SourcePrefKey
import xyz.altaf.kioku.sync.proto.TrackKey

/**
 * The record types, matching the values the triggers write into the outbox
 * and the server's record classification.
 */
object RecordTypes {
    const val MANGA = 1
    const val CHAPTER = 2
    const val HISTORY = 3
    const val CATEGORY = 4
    const val TRACK = 5
    const val PREFERENCE = 6
    const val SOURCE_PREFERENCE = 7
    const val EXTENSION_STORE = 8
    const val SOURCE_NAME = 9
}

/**
 * Builds record keys for the change messages. The wire bytes of these keys
 * are the identity of a record on the server and in the local bookkeeping.
 */
object RecordKeys {
    fun manga(sourceId: Long, url: String): RecordKey = RecordKey.newBuilder()
        .setManga(mangaKey(sourceId, url))
        .build()

    fun chapter(sourceId: Long, mangaUrl: String, chapterUrl: String): RecordKey = RecordKey.newBuilder()
        .setChapter(
            ChapterKey.newBuilder()
                .setManga(mangaKey(sourceId, mangaUrl))
                .setUrl(chapterUrl),
        )
        .build()

    fun history(sourceId: Long, mangaUrl: String, chapterUrl: String): RecordKey = RecordKey.newBuilder()
        .setHistory(
            HistoryKey.newBuilder()
                .setManga(mangaKey(sourceId, mangaUrl))
                .setChapterUrl(chapterUrl),
        )
        .build()

    fun category(syncId: String): RecordKey = RecordKey.newBuilder()
        .setCategory(CategoryKey.newBuilder().setId(syncId))
        .build()

    fun track(sourceId: Long, mangaUrl: String, trackerId: Long): RecordKey = RecordKey.newBuilder()
        .setTrack(
            TrackKey.newBuilder()
                .setManga(mangaKey(sourceId, mangaUrl))
                .setTrackerId(trackerId),
        )
        .build()

    fun preference(key: String): RecordKey = RecordKey.newBuilder()
        .setPreference(key)
        .build()

    fun sourcePreference(source: String, pref: String): RecordKey = RecordKey.newBuilder()
        .setSourcePreference(
            SourcePrefKey.newBuilder()
                .setSource(source)
                .setPref(pref),
        )
        .build()

    fun extensionStore(indexUrl: String): RecordKey = RecordKey.newBuilder()
        .setExtensionStore(indexUrl)
        .build()

    fun sourceName(sourceId: Long): RecordKey = RecordKey.newBuilder()
        .setSourceName(sourceId)
        .build()

    private fun mangaKey(sourceId: Long, url: String): MangaKey = MangaKey.newBuilder()
        .setSourceId(sourceId)
        .setUrl(url)
        .build()
}
