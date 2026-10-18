package com.quickshare.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Framing tests, focused on the length prefix.
 *
 * The prefix is the one place where a peer controls an allocation size, so every bound is asserted
 * explicitly: an unchecked prefix is a remote memory-exhaustion bug.
 */
class FrameCodecTest {

    @Test
    fun `a frame round trips through bytes`() {
        val message = Ping(nonce = 12345L)
        val frame = FrameCodec.encode(message)
        val decoded = FrameCodec.decode(frame)
        assertTrue(decoded is Ping)
        assertEquals(12345L, (decoded as Ping).nonce)
    }

    @Test
    fun `the length prefix is big endian and excludes itself`() {
        val frame = FrameCodec.encode(Ping(1L))
        val declared = ((frame[0].toInt() and 0xFF) shl 24) or
            ((frame[1].toInt() and 0xFF) shl 16) or
            ((frame[2].toInt() and 0xFF) shl 8) or
            (frame[3].toInt() and 0xFF)
        assertEquals(frame.size - 4, declared)
        assertTrue(declared > 0)
    }

    @Test
    fun `a frame round trips through a stream`() {
        val out = java.io.ByteArrayOutputStream()
        FrameCodec.writeMessage(out, HelloAck(1, "A", "id", 8988, true, null))
        FrameCodec.writeMessage(out, Ping(7L))

        val input = java.io.ByteArrayInputStream(out.toByteArray())
        val first = FrameCodec.readMessage(input)
        val second = FrameCodec.readMessage(input)

        assertTrue(first is HelloAck)
        assertEquals("A", (first as HelloAck).deviceName)
        assertTrue(second is Ping)
        assertEquals(7L, (second as Ping).nonce)
    }

    @Test
    fun `the stream reader reports a clean hang up as eof`() {
        val input = java.io.ByteArrayInputStream(ByteArray(0))
        var eof = false
        try {
            FrameCodec.readMessage(input)
        } catch (e: java.io.EOFException) {
            eof = true
        }
        assertTrue("an empty stream must surface as EOF, not a protocol error", eof)
    }

    @Test
    fun `a truncated header is a protocol error, not eof`() {
        val input = java.io.ByteArrayInputStream(byteArrayOf(0, 0))
        var protocolError = false
        try {
            FrameCodec.readMessage(input)
        } catch (e: ProtocolException) {
            protocolError = true
        }
        assertTrue(protocolError)
    }

    @Test
    fun `a truncated body is detected`() {
        val full = FrameCodec.encode(Ping(99L))
        val truncated = full.copyOf(full.size - 3)
        val input = java.io.ByteArrayInputStream(truncated)
        var protocolError = false
        try {
            FrameCodec.readMessage(input)
        } catch (e: ProtocolException) {
            protocolError = true
        }
        assertTrue("a body cut short mid-frame must be detected", protocolError)
    }

    @Test
    fun `an oversized length prefix is refused before allocating`() {
        // 0x7FFFFFFF would be a 2 GB allocation if the guard were missing.
        val hostile = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val input = java.io.ByteArrayInputStream(hostile)
        var message = ""
        try {
            FrameCodec.readMessage(input)
        } catch (e: ProtocolException) {
            message = e.message ?: ""
        }
        assertTrue("the oversized prefix must be refused", message.contains("too large"))
    }

    @Test
    fun `a negative length prefix is refused`() {
        // High bit set: as a signed int this is negative and must never reach ByteArray().
        val hostile = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x01)
        var thrown = false
        try {
            FrameCodec.readMessage(java.io.ByteArrayInputStream(hostile))
        } catch (e: ProtocolException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `a zero length frame is refused`() {
        val hostile = byteArrayOf(0, 0, 0, 0)
        var thrown = false
        try {
            FrameCodec.readMessage(java.io.ByteArrayInputStream(hostile))
        } catch (e: ProtocolException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `decode rejects a frame whose declared length disagrees with its size`() {
        val frame = FrameCodec.encode(Ping(1L))
        val padded = frame.copyOf(frame.size + 5)
        var thrown = false
        try {
            FrameCodec.decode(padded)
        } catch (e: ProtocolException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `decode rejects a frame shorter than its own header`() {
        var thrown = false
        try {
            FrameCodec.decode(byteArrayOf(1, 2))
        } catch (e: ProtocolException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `readFully fills the buffer exactly`() {
        val data = ByteArray(4096) { (it % 251).toByte() }
        val buffer = ByteArray(4096)
        val read = FrameCodec.readFully(java.io.ByteArrayInputStream(data), buffer)
        assertEquals(4096, read)
        assertTrue(data.contentEquals(buffer))
    }

    @Test
    fun `readFully reports a short read instead of pretending success`() {
        val buffer = ByteArray(100)
        val read = FrameCodec.readFully(java.io.ByteArrayInputStream(ByteArray(30)), buffer)
        assertEquals(30, read)
        assertFalse(read == buffer.size)
    }
}
