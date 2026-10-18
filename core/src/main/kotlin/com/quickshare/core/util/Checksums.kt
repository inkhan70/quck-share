package com.quickshare.core.util

import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Streaming SHA-256 helpers.
 *
 * Both sides hash while they move bytes, so verifying a 4 GB file costs no extra pass and no
 * extra memory beyond the 8 KB digest buffer.
 */
object Checksums {

    private const val BUFFER_SIZE = 8 * 1024

    /** Hex-encoded SHA-256 of the whole stream. The caller still owns (and should close) [input]. */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return toHex(digest.digest())
    }

    fun sha256Hex(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = digits[v ushr 4]
            out[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(out)
    }

    /** Constant-time-ish comparison for digests, so verification does not leak a prefix match. */
    fun equalHex(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val x = a.trim().lowercase()
        val y = b.trim().lowercase()
        if (x.length != y.length || x.isEmpty()) return false
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].code xor y[i].code)
        return diff == 0
    }

    /**
     * Wraps an [OutputStream] so the digest of everything written through it is available once
     * the stream is closed. Used on the receive side, where the hash must be computed from the
     * exact bytes that landed on disk.
     */
    class HashingOutputStream(
        out: OutputStream,
        private val onDigest: (String) -> Unit,
    ) : FilterOutputStream(out) {

        private val digest = MessageDigest.getInstance("SHA-256")
        private var closed = false

        override fun write(b: Int) {
            digest.update(b.toByte())
            out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            digest.update(b, off, len)
            out.write(b, off, len)
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                super.close()
            } finally {
                onDigest(toHex(digest.digest()))
            }
        }
    }
}
