package xyz.altaf.kioku.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class ClockTest {

    private fun clock() = Clock(0, 0, "d1")

    @Test
    fun `tick uses wall time when it has advanced`() {
        val h = clock().tick(Instant.fromEpochMilliseconds(1_000))
        assertEquals(1_000, h.wallMs)
        assertEquals(0, h.counter)
    }

    @Test
    fun `tick within the same millisecond advances the counter`() {
        val c = clock()
        c.tick(Instant.fromEpochMilliseconds(1_000))
        val h = c.tick(Instant.fromEpochMilliseconds(1_000))
        assertEquals(1_000, h.wallMs)
        assertEquals(1, h.counter)
    }

    @Test
    fun `tick after observing a future value keeps the counter monotonic`() {
        val c = clock()
        // Another device is 10 minutes ahead.
        c.observe(Clock.hlc(700_000, 5, "d2"))
        val h = c.tick(Instant.fromEpochMilliseconds(1_000))
        assertEquals(700_000, h.wallMs)
        assertEquals(6, h.counter, "the counter must not restart below the observed one")
    }

    @Test
    fun `observe never moves the clock backwards`() {
        val c = clock()
        c.observe(Clock.hlc(500, 3, "d2"))
        c.observe(Clock.hlc(100, 9, "d3"))
        assertEquals(500, c.wall)
        assertEquals(3, c.counter)
    }

    @Test
    fun `compare orders by wall then counter then device`() {
        val a = Clock.hlc(1, 1, "a")
        assertEquals(0, compareClocks(a, Clock.hlc(1, 1, "a")))
        assertTrue(compareClocks(Clock.hlc(2, 0, "z"), Clock.hlc(1, 9, "a")) > 0)
        assertTrue(compareClocks(Clock.hlc(1, 2, "z"), Clock.hlc(1, 1, "a")) > 0)
        assertTrue(compareClocks(Clock.hlc(1, 1, "b"), Clock.hlc(1, 1, "a")) > 0)
        assertTrue(compareClocks(Clock.hlc(1, 1, "a"), Clock.hlc(1, 1, "b")) < 0)
    }

    @Test
    fun `keys encode canonically`() {
        val k1 = RecordKeys.manga(2469, "https://example.com/manga")
        val k2 = RecordKeys.manga(2469, "https://example.com/manga")
        assertTrue(k1.toByteArray().contentEquals(k2.toByteArray()))

        val chapterA = RecordKeys.chapter(2469, "m", "c1")
        val chapterB = RecordKeys.chapter(2469, "m", "c2")
        assertFalse(chapterA.toByteArray().contentEquals(chapterB.toByteArray()))
    }
}
