package xyz.altaf.kioku.sync

import xyz.altaf.kioku.sync.proto.Hlc
import kotlin.time.Instant

/**
 * The hybrid logical clock of this device. Wall time orders events across
 * devices; the counter breaks ties between events in the same millisecond;
 * the device id breaks remaining ties deterministically.
 */
data class Clock(
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
            wall = nowMs
            counter = 0
        } else {
            counter++
        }
        return current()
    }

    /**
     * Absorbs a remote value, keeping the clock ahead of everything observed.
     */
    fun observe(remote: Hlc) {
        if (remote.wallMs > wall) {
            wall = remote.wallMs
            counter = remote.counter.toLong()
        } else if (remote.wallMs == wall.toLong() && remote.counter.toLong() > counter) {
            counter = remote.counter.toLong()
        }
    }

    fun current(): Hlc = hlc(wall, counter, device)

    companion object {
        fun hlc(wall: Long, counter: Long, device: String): Hlc = Hlc.newBuilder()
            .setWallMs(wall)
            .setCounter(counter.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
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
