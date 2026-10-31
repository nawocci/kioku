package xyz.altaf.kioku.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class HlcClockTest {

    private fun clock() = HlcClock(0, 0, "d1")

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
        c.observe(HlcClock.hlc(700_000, 5, "d2"))
        val h = c.tick(Instant.fromEpochMilliseconds(1_000))
        assertEquals(700_000, h.wallMs)
        // 6 from observe (strictly past the observed value) and 7 from the tick.
        assertEquals(7, h.counter, "the counter must stay ahead of the observed one")
    }

    @Test
    fun `observe never moves the clock backwards`() {
        val c = clock()
        c.observe(HlcClock.hlc(500, 3, "d2"))
        c.observe(HlcClock.hlc(100, 9, "d3"))
        assertEquals(500, c.wall)
        assertEquals(4, c.counter)
    }

    @Test
    fun `a local event after observing always sorts after the observed one`() {
        val c = clock()
        // Equal wall and counter with the larger device id: the case a plain
        // max would keep, letting the next local value sort before it.
        val remote = HlcClock.hlc(1_000, 4, "zz")
        c.observe(remote)
        assertTrue(compareClocks(c.current(), remote) > 0)

        val local = c.tick(Instant.fromEpochMilliseconds(1_000))
        assertTrue(compareClocks(local, remote) > 0)
    }

    @Test
    fun `a clock far ahead of the server is reseated`() {
        val c = HlcClock(wall = 10_000_000, counter = 7, device = "d1")
        // Two seconds of skew is tolerated.
        assertFalse(c.clampTo(serverTimeMs = 10_000_000 - 2_000))
        assertEquals(10_000_000, c.wall)

        // A day ahead is not.
        val aDayBehind = 10_000_000L - 86_400_000L
        assertTrue(c.clampTo(serverTimeMs = aDayBehind))
        assertEquals(aDayBehind, c.wall)
        assertEquals(0, c.counter)
    }

    @Test
    fun `compare orders by wall then counter then device`() {
        val a = HlcClock.hlc(1, 1, "a")
        assertEquals(0, compareClocks(a, HlcClock.hlc(1, 1, "a")))
        assertTrue(compareClocks(HlcClock.hlc(2, 0, "z"), HlcClock.hlc(1, 9, "a")) > 0)
        assertTrue(compareClocks(HlcClock.hlc(1, 2, "z"), HlcClock.hlc(1, 1, "a")) > 0)
        assertTrue(compareClocks(HlcClock.hlc(1, 1, "b"), HlcClock.hlc(1, 1, "a")) > 0)
        assertTrue(compareClocks(HlcClock.hlc(1, 1, "a"), HlcClock.hlc(1, 1, "b")) < 0)
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
