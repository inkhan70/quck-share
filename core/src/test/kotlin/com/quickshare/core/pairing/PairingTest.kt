package com.quickshare.core.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pairing code and QR payload tests. */
class PairingTest {

    @Test
    fun `a generated code is exactly six digits`() {
        repeat(200) {
            val code = PairingCode.generate()
            assertEquals(PairingCode.LENGTH, code.length)
            assertTrue("code was '$code'", code.all { it.isDigit() })
        }
    }

    @Test
    fun `generated codes vary`() {
        val codes = (1..50).map { PairingCode.generate() }.toSet()
        assertTrue("codes must not be predictable/repeating", codes.size > 40)
    }

    @Test
    fun `matching is exact and rejects blanks`() {
        assertTrue(PairingCode.matches("123456", "123456"))
        assertFalse(PairingCode.matches("123456", "123457"))
        assertFalse(PairingCode.matches("123456", "12345"))
        assertFalse(PairingCode.matches("", ""))
        assertFalse(PairingCode.matches(null, "123456"))
        assertFalse(PairingCode.matches("123456", null))
    }

    @Test
    fun `matching ignores surrounding whitespace`() {
        assertTrue(PairingCode.matches("123456", "  123456  "))
    }

    @Test
    fun `well formed checks the shape the UI uses`() {
        assertTrue(PairingCode.isWellFormed("123456"))
        assertFalse(PairingCode.isWellFormed("12345"))
        assertFalse(PairingCode.isWellFormed("12345a"))
        assertFalse(PairingCode.isWellFormed(null))
    }

    @Test
    fun `masking never reveals more than the last digit`() {
        assertEquals("*****6", PairingCode.mask("123456"))
        assertEquals("(none)", PairingCode.mask(null))
        assertEquals("(none)", PairingCode.mask(""))
    }

    @Test
    fun `formatting groups the digits for readability`() {
        assertEquals("123 456", PairingCode.format("123456"))
    }

    // ---------------------------------------------------------------------------------------
    // QR payload
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a payload round trips`() {
        val uri = PairingPayload.encode("192.168.1.42", 8988, "482913", "Pixel 7")
        val decoded = PairingPayload.decode(uri)
        assertNotNull(decoded)
        assertEquals("192.168.1.42", decoded!!.host)
        assertEquals(8988, decoded.port)
        assertEquals("482913", decoded.code)
        assertEquals("Pixel 7", decoded.deviceName)
    }

    @Test
    fun `a device name with spaces and symbols survives encoding`() {
        val uri = PairingPayload.encode("10.0.0.2", 9000, "111111", "Ana's Pixel 8 (work)")
        assertEquals("Ana's Pixel 8 (work)", PairingPayload.decode(uri)!!.deviceName)
    }

    @Test
    fun `encoding refuses a host that is not an ipv4 literal`() {
        var thrown = false
        try {
            PairingPayload.encode("not-an-ip", 8988, "123456", "x")
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `encoding refuses a malformed code and an out of range port`() {
        assertEquals(false, runCatching { PairingPayload.encode("1.2.3.4", 8988, "12ab", "x") }.isSuccess)
        assertEquals(false, runCatching { PairingPayload.encode("1.2.3.4", 0, "123456", "x") }.isSuccess)
    }

    @Test
    fun `decoding rejects a foreign scheme`() {
        assertNull(PairingPayload.decode("https://example.com/pair?host=1.2.3.4&port=1&code=123456"))
        assertNull(PairingPayload.decode("random text"))
        assertNull(PairingPayload.decode(null))
        assertNull(PairingPayload.decode(""))
    }

    @Test
    fun `decoding rejects a payload missing any required field`() {
        assertNull(PairingPayload.decode("quickshare://pair?port=8988&code=123456")) // no host
        assertNull(PairingPayload.decode("quickshare://pair?host=1.2.3.4&code=123456")) // no port
        assertNull(PairingPayload.decode("quickshare://pair?host=1.2.3.4&port=8988")) // no code
    }

    @Test
    fun `decoding rejects nonsense values in each field`() {
        assertNull(PairingPayload.decode("quickshare://pair?host=500.1.1.1&port=8988&code=123456"))
        assertNull(PairingPayload.decode("quickshare://pair?host=1.2.3.4&port=99999&code=123456"))
        assertNull(PairingPayload.decode("quickshare://pair?host=1.2.3.4&port=8988&code=abcdef"))
    }

    @Test
    fun `decoding tolerates a missing name and a missing version`() {
        val decoded = PairingPayload.decode("quickshare://pair?host=1.2.3.4&port=8988&code=123456")
        assertNotNull(decoded)
        assertTrue(decoded!!.deviceName.isNotEmpty())
        assertEquals(com.quickshare.core.protocol.Protocol.VERSION, decoded.protocolVersion)
    }

    @Test
    fun `decode is case insensitive on the scheme`() {
        assertNotNull(PairingPayload.decode("QuickShare://PAIR?host=1.2.3.4&port=8988&code=123456"))
    }

    @Test
    fun `describe produces a human readable summary`() {
        val summary = PairingPayload("1.2.3.4", 8988, "123456", "Pixel", 1).describe()
        assertTrue(summary.contains("Pixel"))
        assertTrue(summary.contains("1.2.3.4:8988"))
        assertTrue(summary.contains("123 456"))
    }
}
