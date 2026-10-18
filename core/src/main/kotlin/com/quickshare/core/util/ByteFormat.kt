package com.quickshare.core.util

import java.util.Locale
import kotlin.math.abs

/** Human-readable formatting for byte counts, speeds and durations, shared by UI and logs. */
object ByteFormat {

    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    fun size(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < UNITS.size - 1) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, UNITS[unit])
    }

    fun speed(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0) "-- /s" else size(bytesPerSecond) + "/s"

    /** Formats a duration in seconds as `m:ss` or `h:mm:ss`. */
    fun duration(seconds: Long): String {
        if (seconds < 0) return "--"
        if (seconds == 0L) return "0:00"
        val s = seconds % 60
        val m = (seconds / 60) % 60
        val h = seconds / 3600
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    /**
     * ETA in seconds from the remaining byte count and the current throughput.
     *
     * @return a positive number of seconds, or -1 when it cannot be estimated yet (no speed
     *         sample, nothing left to send, or a nonsensical value). Callers render -1 as "--".
     */
    fun etaSeconds(remainingBytes: Long, bytesPerSecond: Long): Long {
        if (remainingBytes <= 0) return 0
        if (bytesPerSecond <= 0) return -1
        val eta = remainingBytes / bytesPerSecond
        return if (eta < 0 || eta > 86_400L * 7) -1 else eta
    }

    /** Rounds a fraction to a stable integer percentage, clamped to 0..100. */
    fun percent(done: Long, total: Long): Int {
        if (total <= 0) return 0
        if (done <= 0) return 0
        if (done >= total) return 100
        val pct = (done.toDouble() / total.toDouble() * 100.0).toInt()
        return pct.coerceIn(0, 100)
    }

    /** True when [a] and [b] are within [tolerance] of each other; used for speed smoothing. */
    fun approximatelyEqual(a: Long, b: Long, tolerance: Long = 1024): Boolean =
        abs(a - b) <= tolerance
}
