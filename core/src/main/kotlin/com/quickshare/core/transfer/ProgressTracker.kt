package com.quickshare.core.transfer

import com.quickshare.core.util.ByteFormat

/**
 * Computes throughput and ETA from progress samples.
 *
 * Speed is measured over a sliding window rather than since the start of the transfer: an
 * average-since-start number stays wrong for a long time after a hiccup, whereas a window recovers.
 * The computation is kept free of Android types so it is directly unit-testable.
 */
class ProgressTracker(
    private val windowMs: Long = 3_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private data class Sample(val timestampMs: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()
    private var lastSpeed: Long = 0L

    /** Discards all history; call when a transfer (re)starts. */
    fun reset() {
        samples.clear()
        lastSpeed = 0L
    }

    /**
     * Records the cumulative byte count and returns the smoothed throughput in bytes/second.
     *
     * @return 0 until there are at least two samples, or when the elapsed time is too small to
     *         divide by.
     */
    fun record(totalBytesTransferred: Long): Long {
        val now = clock()
        samples.addLast(Sample(now, totalBytesTransferred))

        // Drop samples older than the window, but keep at least two so a slow transfer with long
        // gaps between updates still produces an estimate.
        while (samples.size > 2 && now - samples.first().timestampMs > windowMs) {
            samples.removeFirst()
        }

        val first = samples.first()
        val elapsed = now - first.timestampMs
        if (elapsed >= 250L) {
            val delta = totalBytesTransferred - first.bytes
            if (delta >= 0) {
                lastSpeed = (delta * 1000L) / elapsed
            }
        }
        return lastSpeed
    }

    fun speed(): Long = lastSpeed

    /** Seconds remaining for the whole transfer at the current speed; -1 when unknowable. */
    fun etaSeconds(remainingBytes: Long): Long = ByteFormat.etaSeconds(remainingBytes, lastSpeed)

    /** Builds a snapshot for the UI. */
    fun snapshot(
        transferId: String,
        direction: TransferDirection,
        fileIndex: Int,
        fileCount: Int,
        fileName: String,
        bytesTransferred: Long,
        totalBytes: Long,
        fileBytesTransferred: Long = 0L,
        fileBytesTotal: Long = 0L,
    ): TransferProgress {
        val speed = record(bytesTransferred)
        return TransferProgress(
            transferId = transferId,
            direction = direction,
            fileIndex = fileIndex,
            fileCount = fileCount,
            fileName = fileName,
            bytesTransferred = bytesTransferred,
            totalBytes = totalBytes,
            bytesPerSecond = speed,
            etaSeconds = ByteFormat.etaSeconds(totalBytes - bytesTransferred, speed),
            fileBytesTransferred = fileBytesTransferred,
            fileBytesTotal = fileBytesTotal,
        )
    }
}

/**
 * Refuses a transfer when the destination cannot hold it.
 *
 * The safety margin matters: a filesystem that reports exactly `totalBytes` free will still fail
 * partway through, because the filesystem's own metadata needs room. The margin is a percentage
 * with a floor, so it scales for large transfers without being absurd for tiny ones.
 */
object StorageGuard {

    const val SAFETY_MARGIN_PERCENT = 5L
    const val SAFETY_MARGIN_FLOOR_BYTES = 32L * 1024 * 1024

    /** Free bytes that must remain after the transfer completes. */
    fun safetyMargin(requiredBytes: Long): Long {
        // Multiply first for exact rounding, but fall back to divide-first for multi-exabyte figures
        // where the multiplication itself would overflow and go negative.
        val percent = if (requiredBytes <= Long.MAX_VALUE / SAFETY_MARGIN_PERCENT) {
            (requiredBytes * SAFETY_MARGIN_PERCENT) / 100L
        } else {
            (requiredBytes / 100L) * SAFETY_MARGIN_PERCENT
        }
        return maxOf(percent, SAFETY_MARGIN_FLOOR_BYTES)
    }

    /** True when [availableBytes] can hold [requiredBytes] plus the safety margin. */
    fun hasRoom(requiredBytes: Long, availableBytes: Long): Boolean {
        if (requiredBytes <= 0) return true
        if (availableBytes < 0) return true // Unknown free space: do not block the user on a guess.
        // Subtract rather than add, so a huge requirement cannot overflow into a "fits" answer.
        return availableBytes - requiredBytes >= safetyMargin(requiredBytes)
    }

    /**
     * @return null when there is room, otherwise a message naming the shortfall.
     */
    fun check(requiredBytes: Long, availableBytes: Long): String? {
        if (hasRoom(requiredBytes, availableBytes)) return null
        val margin = safetyMargin(requiredBytes)
        val needed = if (requiredBytes > Long.MAX_VALUE - margin) Long.MAX_VALUE else requiredBytes + margin
        val shortfall = needed - availableBytes
        return "Not enough storage: this transfer needs ${ByteFormat.size(needed)} " +
            "but only ${ByteFormat.size(availableBytes)} is free " +
            "(short by ${ByteFormat.size(shortfall)}). Free up space and try again."
    }
}
