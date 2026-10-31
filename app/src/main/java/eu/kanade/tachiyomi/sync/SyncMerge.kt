package eu.kanade.tachiyomi.sync

import tachiyomi.domain.manga.model.Manga
import kotlin.time.Instant

/** Which side wins when a record already exists on both. */
enum class SyncMode {
    /** The server resolved the winner; its value replaces the local one. */
    LIVE,

    /** First pairing onto a device with its own data: backup-restore union. */
    PAIRING,
}

/** The user state of one chapter, as either side of a merge knows it. */
internal data class ChapterUserState(
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Int,
)

/**
 * The merge rules of the sync protocol, kept pure so they can be exercised
 * without a database. Everything here is a decision about which of two known
 * values survives; the code that reads and writes rows only feeds them.
 */
internal object SyncMerge {

    /**
     * When the entry was favorited, or null when it is not in the library.
     *
     * LIVE takes the server's value, including its absence: a record that was
     * unfavorited elsewhere must unfavorite here. PAIRING keeps either side's
     * favorite and, when both know a time, the earliest one — a restore unions
     * the history rather than picking a favorite twice.
     */
    fun favoriteAt(mode: SyncMode, existing: Instant?, incoming: Instant?): Instant? = when {
        mode == SyncMode.LIVE -> incoming
        incoming == null -> existing
        else -> listOfNotNull(existing, incoming)
            .filter { it > Manga.UNKNOWN_FAVORITE_AT }
            .minOrNull()
    }

    /**
     * The reading state of a chapter: LIVE replaces it (an unread chapter has
     * to stay unread), PAIRING unions it so a read anywhere is a read, and the
     * furthest page reached survives.
     */
    fun chapterUserState(
        mode: SyncMode,
        existing: ChapterUserState,
        incoming: ChapterUserState,
    ): ChapterUserState = when (mode) {
        SyncMode.LIVE -> incoming
        SyncMode.PAIRING -> ChapterUserState(
            read = existing.read || incoming.read,
            bookmark = existing.bookmark || incoming.bookmark,
            lastPageRead = maxOf(existing.lastPageRead, incoming.lastPageRead),
        )
    }

    /**
     * History unions in both modes: the latest read time and the longest
     * duration of either side are kept, so reads on concurrent devices are all
     * preserved. A cleared read is not a deletion — only a tombstone is.
     */
    fun history(
        existingReadAt: Instant?,
        existingDuration: Long,
        incomingReadAt: Instant?,
        incomingDuration: Long,
    ): Pair<Instant?, Long> =
        listOfNotNull(incomingReadAt, existingReadAt).maxOrNull() to maxOf(existingDuration, incomingDuration)

    /**
     * Whether tracking progress is worth writing: LIVE takes the server's
     * value, PAIRING only moves a tracker forward.
     */
    fun trackAdvances(mode: SyncMode, existing: Double, incoming: Double): Boolean =
        mode == SyncMode.LIVE || incoming > existing
}
