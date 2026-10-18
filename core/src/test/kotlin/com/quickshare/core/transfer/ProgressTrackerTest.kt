package com.quickshare.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progress tracking: speed smoothing, ETA and the indeterminate case.
 *
 * These are the numbers the user actually reads while waiting, so the degenerate cases (no samples
 * yet, a stalled socket, a file that turns out to be empty) are asserted rather than assumed.
 *
 * The tracker takes an injectable clock, so every timing assertion here is deterministic - a test
 * that sleeps to observe a speed would be flaky on CI.
 */
class ProgressTrackerTest {

    /** A clock the test advances by hand. */
    private class FakeClock(var now: Long = 1_000_000L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    private fun tracker(clock: FakeClock, windowMs: Long = 3_000L) =
        ProgressTracker(windowMs = windowMs, clock = { clock.now })

    private fun snapshotOf(
        tracker: ProgressTracker,
        transferred: Long,
        total: Long = 1_000_000L,
        fileIndex: Int = 1,
        fileCount: Int = 1,
        fileName: String = "clip.mp4",
    ) = tracker.snapshot(
        transferId = "t-1",
        direction = TransferDirection.SEND,
        fileIndex = fileIndex,
        fileCount = fileCount,
        fileName = fileName,
        bytesTransferred = transferred,
        totalBytes = total,
    )

    // ---------------------------------------------------------------------------------------
    // Percentage and completion
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a fresh snapshot reports zero percent`() {
        val p = snapshotOf(tracker(FakeClock()), transferred = 0)
        assertEquals(0, p.percent)
        assertEquals(0L, p.bytesTransferred)
    }

    @Test
    fun `progress reaches and holds one hundred percent`() {
        assertEquals(40, snapshotOf(tracker(FakeClock()), 400L, total = 1000L).percent)
        assertEquals(100, snapshotOf(tracker(FakeClock()), 1000L, total = 1000L).percent)
    }

    @Test
    fun `percent never exceeds one hundred when a peer over sends`() {
        assertEquals(100, snapshotOf(tracker(FakeClock()), 5_000L, total = 1000L).percent)
    }

    @Test
    fun `an unknown total is reported as indeterminate rather than zero progress`() {
        val p = snapshotOf(tracker(FakeClock()), transferred = 500L, total = 0L)
        assertTrue("a zero/unknown total must be indeterminate", p.isIndeterminate)
    }

    @Test
    fun `a known total is not indeterminate`() {
        assertFalse(snapshotOf(tracker(FakeClock()), 500L, total = 1000L).isIndeterminate)
    }

    // ---------------------------------------------------------------------------------------
    // Speed
    // ---------------------------------------------------------------------------------------

    @Test
    fun `speed is zero before any time has passed`() {
        // A single sample with no elapsed time cannot yield a rate.
        assertEquals(0L, snapshotOf(tracker(FakeClock()), transferred = 500L).bytesPerSecond)
    }

    @Test
    fun `speed is computed from elapsed time and byte delta`() {
        val clock = FakeClock()
        val t = tracker(clock)
        snapshotOf(t, transferred = 0L)
        clock.advance(1_000L)
        // 100 KB in one second.
        val p = snapshotOf(t, transferred = 100_000L)
        assertEquals(100_000L, p.bytesPerSecond)
    }

    @Test
    fun `eta shrinks as the transfer advances`() {
        val clock = FakeClock()
        val t = tracker(clock)
        snapshotOf(t, transferred = 0L, total = 1_000_000L)
        clock.advance(1_000L)
        val first = snapshotOf(t, transferred = 100_000L, total = 1_000_000L)

        clock.advance(1_000L)
        val second = snapshotOf(t, transferred = 200_000L, total = 1_000_000L)

        assertTrue("first eta was ${first.etaSeconds}", first.etaSeconds > 0)
        assertTrue(
            "eta should shrink: ${first.etaSeconds} -> ${second.etaSeconds}",
            second.etaSeconds < first.etaSeconds,
        )
    }

    @Test
    fun `a stalled transfer reports no speed and no eta`() {
        val clock = FakeClock()
        val t = tracker(clock)
        snapshotOf(t, transferred = 500L)
        clock.advance(5_000L)
        // No new bytes in five seconds.
        val p = snapshotOf(t, transferred = 500L)
        assertEquals(0L, p.bytesPerSecond)
        assertEquals(-1L, p.etaSeconds)
    }

    @Test
    fun `eta is zero once everything has arrived`() {
        val clock = FakeClock()
        val t = tracker(clock)
        snapshotOf(t, transferred = 0L, total = 1000L)
        clock.advance(1_000L)
        assertEquals(0L, snapshotOf(t, transferred = 1000L, total = 1000L).etaSeconds)
    }

    @Test
    fun `speed recovers after a stall instead of staying wrong`() {
        // This is the whole point of the sliding window: an average-since-start figure would still
        // be reporting the stall long after throughput came back.
        val clock = FakeClock()
        val t = tracker(clock, windowMs = 2_000L)
        snapshotOf(t, transferred = 0L)
        clock.advance(5_000L)
        snapshotOf(t, transferred = 0L) // stalled
        clock.advance(1_000L)
        val recovered = snapshotOf(t, transferred = 500_000L)
        assertTrue("speed should have recovered, was ${recovered.bytesPerSecond}", recovered.bytesPerSecond > 0)
    }

    @Test
    fun `a backwards byte count never yields a negative speed`() {
        val clock = FakeClock()
        val t = tracker(clock)
        snapshotOf(t, transferred = 10_000L)
        clock.advance(1_000L)
        // A reset (e.g. a retry) must not produce a negative rate that would render as "-3 MB/s".
        val p = snapshotOf(t, transferred = 1_000L)
        assertTrue("speed was ${p.bytesPerSecond}", p.bytesPerSecond >= 0)
    }

    @Test
    fun `reset clears the measured speed`() {
        val clock = FakeClock()
        val t = tracker(clock)
        snapshotOf(t, transferred = 0L)
        clock.advance(1_000L)
        snapshotOf(t, transferred = 100_000L)
        assertTrue(t.speed() > 0)
        t.reset()
        assertEquals(0L, t.speed())
    }

    // ---------------------------------------------------------------------------------------
    // Multi-file bookkeeping
    // ---------------------------------------------------------------------------------------

    @Test
    fun `file indices are carried through for a multi file transfer`() {
        val p = snapshotOf(
            tracker(FakeClock()),
            transferred = 100L,
            fileIndex = 2,
            fileCount = 3,
            fileName = "b.txt",
        )
        assertEquals(2, p.fileIndex)
        assertEquals(3, p.fileCount)
        assertEquals("b.txt", p.fileName)
    }

    @Test
    fun `the per file percentage is independent of the overall one`() {
        val p = tracker(FakeClock()).snapshot(
            transferId = "t",
            direction = TransferDirection.RECEIVE,
            fileIndex = 2,
            fileCount = 4,
            fileName = "b.bin",
            bytesTransferred = 250L,
            totalBytes = 1_000L,
            fileBytesTransferred = 50L,
            fileBytesTotal = 100L,
        )
        assertEquals(25, p.percent)
        assertEquals(50, p.filePercent)
    }
}

/**
 * Storage pre-flight tests.
 *
 * A wrong answer here is expensive in both directions: too strict blocks a transfer that would have
 * worked, too lax lets a 4 GB send fail at 95%.
 */
class StorageGuardTest {

    @Test
    fun `a transfer that fits is allowed`() {
        assertTrue(StorageGuard.hasRoom(100L * 1024 * 1024, 10L * 1024 * 1024 * 1024))
        assertEquals(null, StorageGuard.check(100L * 1024 * 1024, 10L * 1024 * 1024 * 1024))
    }

    @Test
    fun `a transfer that exactly fills the disk is refused`() {
        // Without the safety margin this would be accepted and then fail mid-write.
        assertFalse(StorageGuard.hasRoom(1000L * 1024 * 1024, 1000L * 1024 * 1024))
    }

    @Test
    fun `nothing to transfer is always allowed`() {
        assertTrue(StorageGuard.hasRoom(0, 0))
        assertTrue(StorageGuard.hasRoom(-5, 0))
    }

    @Test
    fun `unknown free space does not block the user`() {
        // -1 means "the provider would not tell us"; refusing every transfer in that case would
        // break the app on such devices for no benefit.
        assertTrue(StorageGuard.hasRoom(5L * 1024 * 1024 * 1024, -1L))
    }

    @Test
    fun `the safety margin has a floor for small transfers`() {
        assertEquals(StorageGuard.SAFETY_MARGIN_FLOOR_BYTES, StorageGuard.safetyMargin(10L * 1024 * 1024))
    }

    @Test
    fun `the safety margin scales for large transfers`() {
        // 5% of 10 GB is well above the 32 MB floor, so the percentage must win.
        val margin = StorageGuard.safetyMargin(10L * 1024 * 1024 * 1024)
        assertTrue("margin was $margin", margin > StorageGuard.SAFETY_MARGIN_FLOOR_BYTES)
        assertEquals((10L * 1024 * 1024 * 1024) * 5L / 100L, margin)
    }

    @Test
    fun `the refusal message names the shortfall`() {
        val message = StorageGuard.check(1_000L * 1024 * 1024, 100L * 1024 * 1024)
        assertTrue("message was $message", message != null)
        assertTrue(message!!.contains("Not enough storage"))
        assertTrue(message.contains("free up space", ignoreCase = true))
    }
}
