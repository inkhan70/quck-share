package com.quickshare.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Formatting and progress-arithmetic tests, including the degenerate cases the UI must survive. */
class ByteFormatTest {

    @Test
    fun `sizes scale through the units`() {
        assertEquals("0 B", ByteFormat.size(0))
        assertEquals("512 B", ByteFormat.size(512))
        assertEquals("1.0 KB", ByteFormat.size(1024))
        assertEquals("1.0 MB", ByteFormat.size(1024L * 1024))
        assertEquals("1.5 GB", ByteFormat.size((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `negative sizes are reported as unknown rather than crashing`() {
        assertEquals("?", ByteFormat.size(-1))
    }

    @Test
    fun `speed rendering handles the unknown case`() {
        assertEquals("-- /s", ByteFormat.speed(0))
        assertEquals("-- /s", ByteFormat.speed(-5))
        assertTrue(ByteFormat.speed(2048).endsWith("/s"))
    }

    @Test
    fun `durations use minutes until an hour is reached`() {
        assertEquals("0:00", ByteFormat.duration(0))
        assertEquals("0:09", ByteFormat.duration(9))
        assertEquals("1:05", ByteFormat.duration(65))
        assertEquals("1:00:00", ByteFormat.duration(3600))
        assertEquals("2:03:04", ByteFormat.duration(7384))
    }

    @Test
    fun `unknown durations render as a dash pair`() {
        assertEquals("--", ByteFormat.duration(-1))
    }

    @Test
    fun `eta is unknown without a speed sample`() {
        assertEquals(-1L, ByteFormat.etaSeconds(1000, 0))
        assertEquals(-1L, ByteFormat.etaSeconds(1000, -1))
    }

    @Test
    fun `eta is zero when nothing remains`() {
        assertEquals(0L, ByteFormat.etaSeconds(0, 1000))
        assertEquals(0L, ByteFormat.etaSeconds(-10, 1000))
    }

    @Test
    fun `eta divides remaining bytes by throughput`() {
        assertEquals(10L, ByteFormat.etaSeconds(10_000, 1_000))
    }

    @Test
    fun `an absurd eta is reported as unknown instead of a huge number`() {
        // 1 byte per second over 7 days exceeds the guard.
        assertEquals(-1L, ByteFormat.etaSeconds(1_000_000_000L, 1))
    }

    @Test
    fun `percent clamps at both ends`() {
        assertEquals(0, ByteFormat.percent(0, 100))
        assertEquals(50, ByteFormat.percent(50, 100))
        assertEquals(100, ByteFormat.percent(100, 100))
        assertEquals(100, ByteFormat.percent(150, 100))
        assertEquals(0, ByteFormat.percent(-5, 100))
    }

    @Test
    fun `percent of an unknown total is zero rather than a division by zero`() {
        assertEquals(0, ByteFormat.percent(50, 0))
        assertEquals(0, ByteFormat.percent(50, -1))
    }
}
