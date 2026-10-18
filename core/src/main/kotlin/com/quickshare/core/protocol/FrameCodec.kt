package com.quickshare.core.protocol

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed framing for the control channel.
 *
 * Layout: a 4-byte big-endian unsigned length, then that many bytes of UTF-8 JSON. The reader
 * refuses any length above [Protocol.MAX_FRAME_BYTES] before allocating, so a hostile
 * `0x7FFFFFFF` prefix cannot make the app reserve 2 GB.
 *
 * The same helper is used for in-memory encode/decode so the framing itself is unit-testable
 * without sockets.
 */
object FrameCodec {

    /** Frame bytes for [message]: 4-byte length prefix followed by the UTF-8 JSON body. */
    fun encode(message: Message): ByteArray {
        val payload = MessageCodec.encode(message).toByteArray(Charsets.UTF_8)
        require(payload.size <= Protocol.MAX_FRAME_BYTES) {
            "Control message too large: ${payload.size} bytes"
        }
        val out = ByteArray(4 + payload.size)
        writeLength(out, payload.size)
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }

    /** Decodes a frame produced by [encode]. */
    fun decode(frame: ByteArray): Message {
        if (frame.size < 4) throw ProtocolException("Truncated control frame: only ${frame.size} bytes")
        val length = readLength(frame, 0)
        if (length > Protocol.MAX_FRAME_BYTES) {
            throw ProtocolException("Control frame too large: $length bytes")
        }
        if (frame.size - 4 != length) {
            throw ProtocolException("Control frame length mismatch: header says $length, got ${frame.size - 4}")
        }
        val json = String(frame, 4, length, Charsets.UTF_8)
        return MessageCodec.decode(json)
    }

    /** Writes one framed [message] to [out]. */
    fun writeMessage(out: OutputStream, message: Message) {
        out.write(encode(message))
        out.flush()
    }

    /**
     * Reads one framed message from [input].
     *
     * @return the decoded message.
     * @throws EOFException when the stream ended cleanly before a frame started, which the caller
     *         treats as "the peer hung up".
     * @throws ProtocolException when the prefix is out of range or the body is truncated because
     *         the connection died mid-frame - a genuinely different, and worth distinguishing,
     *         failure from a clean hang-up.
     */
    fun readMessage(input: InputStream): Message {
        val header = ByteArray(4)
        val headerRead = readFully(input, header)
        if (headerRead == 0) throw EOFException("Peer closed the connection")
        if (headerRead < 4) throw ProtocolException("Truncated control frame header")

        val length = readLength(header, 0)
        if (length > Protocol.MAX_FRAME_BYTES) {
            throw ProtocolException("Control frame too large: $length bytes")
        }
        if (length == 0) throw ProtocolException("Zero-length control frame")

        val body = ByteArray(length)
        val bodyRead = readFully(input, body)
        if (bodyRead < length) {
            throw ProtocolException("Connection lost mid-frame: expected $length bytes, got $bodyRead")
        }
        return MessageCodec.decode(String(body, Charsets.UTF_8))
    }

    /**
     * Reads until [buffer] is full or the stream ends.
     *
     * @return the number of bytes actually read; a short count means end-of-stream.
     */
    fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            if (read == 0) continue
            total += read
        }
        return total
    }

    private fun writeLength(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun writeLength(target: ByteArray, value: Int) = writeLength(target, 0, value)

    private fun readLength(source: ByteArray, offset: Int): Int {
        val b0 = source[offset].toInt() and 0xFF
        val b1 = source[offset + 1].toInt() and 0xFF
        val b2 = source[offset + 2].toInt() and 0xFF
        val b3 = source[offset + 3].toInt() and 0xFF
        val value = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        // Guard against a negative int produced by a prefix with the high bit set: such a frame is
        // always invalid, and letting it through would make ByteArray() throw NegativeArraySize.
        if (value < 0) throw ProtocolException("Control frame has a negative length prefix")
        return value
    }
}
