package eu.kanade.tachiyomi.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Instant

class SyncMergeTest {

    private fun at(ms: Long) = Instant.fromEpochMilliseconds(ms)

    @Test
    fun `live takes the server favorite, including its absence`() {
        assertEquals(at(2_000), SyncMerge.favoriteAt(SyncMode.LIVE, existing = at(1_000), incoming = at(2_000)))
        assertNull(SyncMerge.favoriteAt(SyncMode.LIVE, existing = at(1_000), incoming = null))
    }

    @Test
    fun `pairing keeps a favorite from either side`() {
        assertEquals(at(1_000), SyncMerge.favoriteAt(SyncMode.PAIRING, existing = at(1_000), incoming = null))
        assertEquals(at(2_000), SyncMerge.favoriteAt(SyncMode.PAIRING, existing = null, incoming = at(2_000)))
    }

    @Test
    fun `pairing keeps the earliest known favorite time`() {
        assertEquals(at(1_000), SyncMerge.favoriteAt(SyncMode.PAIRING, existing = at(3_000), incoming = at(1_000)))
        assertEquals(at(1_000), SyncMerge.favoriteAt(SyncMode.PAIRING, existing = at(1_000), incoming = at(3_000)))
    }

    @Test
    fun `an unknown favorite time stays unknown unless a real one arrives`() {
        // The placeholder both sides use for "favorited at an unknown time"
        // must not win over a real timestamp.
        assertEquals(
            Manga.UNKNOWN_FAVORITE_AT,
            SyncMerge.favoriteAt(SyncMode.PAIRING, existing = Manga.UNKNOWN_FAVORITE_AT, incoming = null),
        )
        assertEquals(
            at(5_000),
            SyncMerge.favoriteAt(SyncMode.PAIRING, existing = Manga.UNKNOWN_FAVORITE_AT, incoming = at(5_000)),
        )
    }

    @Test
    fun `live replaces the chapter state so an unread chapter stays unread`() {
        val merged = SyncMerge.chapterUserState(
            mode = SyncMode.LIVE,
            existing = ChapterUserState(read = true, bookmark = true, lastPageRead = 30),
            incoming = ChapterUserState(read = false, bookmark = false, lastPageRead = 0),
        )

        assertEquals(ChapterUserState(read = false, bookmark = false, lastPageRead = 0), merged)
    }

    @Test
    fun `pairing unions the chapter state and keeps the furthest page`() {
        val merged = SyncMerge.chapterUserState(
            mode = SyncMode.PAIRING,
            existing = ChapterUserState(read = true, bookmark = false, lastPageRead = 30),
            incoming = ChapterUserState(read = false, bookmark = true, lastPageRead = 12),
        )

        assertEquals(ChapterUserState(read = true, bookmark = true, lastPageRead = 30), merged)
    }

    @Test
    fun `history unions in both modes`() {
        val (liveReadAt, liveDuration) = SyncMerge.history(
            existingReadAt = at(3_000),
            existingDuration = 900,
            incomingReadAt = at(1_000),
            incomingDuration = 300,
        )
        assertEquals(at(3_000), liveReadAt)
        assertEquals(900, liveDuration)

        val (pairingReadAt, pairingDuration) = SyncMerge.history(
            existingReadAt = at(3_000),
            existingDuration = 900,
            incomingReadAt = at(5_000),
            incomingDuration = 1_500,
        )
        assertEquals(at(5_000), pairingReadAt)
        assertEquals(1_500, pairingDuration)
    }

    @Test
    fun `a cleared history does not erase a read elsewhere`() {
        val (readAt, duration) = SyncMerge.history(
            existingReadAt = at(3_000),
            existingDuration = 900,
            incomingReadAt = null,
            incomingDuration = 0,
        )

        assertEquals(at(3_000), readAt)
        assertEquals(900, duration)
    }

    @Test
    fun `tracking only moves forward on a restore`() {
        assertTrue(SyncMerge.trackAdvances(SyncMode.PAIRING, existing = 10.0, incoming = 12.0))
        assertFalse(SyncMerge.trackAdvances(SyncMode.PAIRING, existing = 10.0, incoming = 10.0))
        assertFalse(SyncMerge.trackAdvances(SyncMode.PAIRING, existing = 12.0, incoming = 10.0))
    }

    @Test
    fun `live tracking follows the server even backwards`() {
        assertTrue(SyncMerge.trackAdvances(SyncMode.LIVE, existing = 12.0, incoming = 10.0))
    }
}
