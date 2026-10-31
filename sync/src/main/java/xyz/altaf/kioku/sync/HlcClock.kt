package xyz.altaf.kioku.sync

import xyz.altaf.kioku.sync.proto.Hlc
import kotlin.time.Instant

/**
 * The hybrid logical clock of this device. Wall time orders events across
 * devices; the counter breaks ties between events in the same millisecond;
 * the device id breaks remaining ties deterministically.
 *
 * Both [tick] and [observe] leave the clock strictly ahead of every value it
 * has seen, so a value issued after observing a remote change always sorts
 * after it — including when the remote value carried the same wall time and
 * counter with a larger device id.
 */
class HlcClock(
    var wall: Long,
    var counter: Long,
    val device: String,
) {
    /**
     * Advances the clock for a local event and returns the new value.
     */
    fun tick(now: Instant): Hlc {
        val nowMs = now.toEpochMilliseconds()
        if (nowMs > wall) {
            advance(nowMs, 0)
        } else {
            advance(wall, counter + 1)
        }
        return current()
    }

    /**
     * Absorbs a remote value, keeping the clock ahead of everything observed.
     * A remote value below the local one teaches nothing: the next tick is
     * already past it.
     */
    fun observe(remote: Hlc) {
        val remoteWall = remote.wallMs
        val remoteCounter = remote.counter.toLong()
        when {
            remoteWall > wall -> advance(remoteWall, remoteCounter + 1)
            remoteWall == wall && remoteCounter >= counter -> advance(wall, remoteCounter + 1)
        }
    }

    /**
     * Reseats a clock that runs far ahead of the server, which would otherwise
     * win every last-write-wins comparison forever. Returns true when the
     * clock was moved, so the caller can persist it.
     */
    fun clampTo(serverTimeMs: Long, toleranceMs: Long = DEFAULT_TOLERANCE_MS): Boolean {
        if (wall <= serverTimeMs + toleranceMs) return false
        advance(serverTimeMs, 0)
        return true
    }

    fun current(): Hlc = hlc(wall, counter, device)

    private fun advance(newWall: Long, newCounter: Long) {
        // A counter that would overflow the wire field moves the clock to the
        // next millisecond instead, so the ordering never regresses.
        if (newCounter > MAX_COUNTER) {
            wall = newWall + 1
            counter = 0
        } else {
            wall = newWall
            counter = newCounter
        }
    }

    companion object {
        private const val MAX_COUNTER = Int.MAX_VALUE.toLong()

        /** Tolerated clock skew against the server before a session reseats. */
        const val DEFAULT_TOLERANCE_MS = 10 * 60 * 1000L

        fun hlc(wall: Long, counter: Long, device: String): Hlc = Hlc.newBuilder()
            .setWallMs(wall)
            .setCounter(counter.coerceIn(0, MAX_COUNTER).toInt())
            .setDevice(device)
            .build()
    }
}

/**
 * Orders two clock values: wall time, then counter, then device id.
 * Returns a negative number when [a] happened first.
 */
fun compareClocks(a: Hlc, b: Hlc): Int {
    var c = a.wallMs.compareTo(b.wallMs)
    if (c != 0) return c
    c = a.counter.compareTo(b.counter)
    if (c != 0) return c
    return a.device.compareTo(b.device)
}
