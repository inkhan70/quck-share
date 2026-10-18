package com.quickshare.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checksum tests: the verification step is only trustworthy if the digest itself is. */
class ChecksumsTest {

    @Test
    fun `sha256 of the empty string is the known constant`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Checksums.sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun `sha256 of abc matches the published vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Checksums.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun `streaming and in memory digests agree`() {
        val data = ByteArray(300_000) { (it % 256).toByte() }
        assertEquals(
            Checksums.sha256Hex(data),
            Checksums.sha256Hex(java.io.ByteArrayInputStream(data)),
        )
    }

    @Test
    fun `a single flipped byte changes the digest`() {
        val a = ByteArray(1024) { 1 }
        val b = a.copyOf().also { it[512] = 2 }
        assertFalse(Checksums.equalHex(Checksums.sha256Hex(a), Checksums.sha256Hex(b)))
    }

    @Test
    fun `equalHex is case and whitespace insensitive`() {
        assertTrue(Checksums.equalHex("ABCDEF", "abcdef"))
        assertTrue(Checksums.equalHex(" abc ", "abc"))
    }

    @Test
    fun `equalHex rejects nulls, empties and length mismatches`() {
        assertFalse(Checksums.equalHex(null, "abc"))
        assertFalse(Checksums.equalHex("abc", null))
        assertFalse(Checksums.equalHex("", ""))
        assertFalse(Checksums.equalHex("abc", "abcd"))
    }

    @Test
    fun `toHex renders lowercase zero padded pairs`() {
        assertEquals("000fff", Checksums.toHex(byteArrayOf(0x00, 0x0F, 0xFF.toByte())))
    }

    @Test
    fun `the hashing output stream publishes the digest of what was written`() {
        var published: String? = null
        val sink = java.io.ByteArrayOutputStream()
        val hashing = Checksums.HashingOutputStream(sink) { published = it }
        hashing.write("hello world".toByteArray())
        hashing.close()

        assertEquals(Checksums.sha256Hex("hello world".toByteArray()), published)
        assertEquals("hello world", sink.toString("UTF-8"))
    }

    @Test
    fun `the hashing output stream digests byte-at-a-time and bulk writes identically`() {
        var a: String? = null
        var b: String? = null
        Checksums.HashingOutputStream(java.io.ByteArrayOutputStream()) { a = it }.apply {
            "ab".toByteArray().forEach { byte -> write(byte.toInt()) }
            close()
        }
        Checksums.HashingOutputStream(java.io.ByteArrayOutputStream()) { b = it }.apply {
            write("ab".toByteArray())
            close()
        }
        assertEquals(a, b)
    }

    @Test
    fun `closing the hashing stream twice does not double publish`() {
        var count = 0
        val hashing = Checksums.HashingOutputStream(java.io.ByteArrayOutputStream()) { count++ }
        hashing.write(1)
        hashing.close()
        hashing.close()
        assertEquals(1, count)
    }
}
