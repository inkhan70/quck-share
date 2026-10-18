package com.quickshare.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip and rejection tests for the control-message codec.
 *
 * The codec is the app's only parser for hostile input, so the "reject" cases matter more than the
 * happy path: each one is a specific way a malformed or malicious peer could otherwise reach the
 * filesystem.
 */
class MessageCodecTest {

    @Test
    fun `hello survives a round trip`() {
        val original = Hello(
            protocolVersion = 1,
            deviceName = "Pixel 7",
            deviceId = "qs-abc123",
            controlPort = 8988,
            groupCode = "0a01",
            appVersion = "1.0.0",
        )
        val decoded = MessageCodec.decode(MessageCodec.encode(original))
        assertTrue(decoded is Hello)
        assertEquals(original, decoded as Hello)
    }

    @Test
    fun `offer round trip preserves every file and recomputes the total`() {
        val original = Offer(
            transferId = "t-1",
            files = listOf(
                OfferedFile("photo.jpg", "photo.jpg", 1024L, "image/jpeg"),
                OfferedFile("clip.mp4", "clip.mp4", 2048L, "video/mp4"),
            ),
            totalBytes = 999_999L, // deliberately wrong
            message = "holiday pics",
        )
        val decoded = MessageCodec.decode(MessageCodec.encode(original)) as Offer
        assertEquals(2, decoded.files.size)
        assertEquals("photo.jpg", decoded.files[0].name)
        // The manifest is authoritative, so the bogus declared total is replaced by the real sum.
        assertEquals(3072L, decoded.totalBytes)
        assertEquals("holiday pics", decoded.message)
    }

    @Test
    fun `transfer ack round trip keeps the failed file list`() {
        val original = TransferAck(
            transferId = "t-2",
            ok = false,
            verifiedFiles = 1,
            failedFiles = listOf("broken.zip"),
            message = "Checksum mismatch",
        )
        val decoded = MessageCodec.decode(MessageCodec.encode(original)) as TransferAck
        assertFalse(decoded.ok)
        assertEquals(1, decoded.verifiedFiles)
        assertEquals(listOf("broken.zip"), decoded.failedFiles)
    }

    @Test
    fun `ping and pong round trip their nonce`() {
        val decoded = MessageCodec.decode(MessageCodec.encode(Ping(42L))) as Ping
        assertEquals(42L, decoded.nonce)
        val pong = MessageCodec.decode(MessageCodec.encode(Pong(-7L))) as Pong
        assertEquals(-7L, pong.nonce)
    }

    @Test
    fun `every message type has a distinct wire tag`() {
        val tags = listOf(
            Hello.TYPE, HelloAck.TYPE, PairRequest.TYPE, PairResponse.TYPE, Offer.TYPE,
            OfferResponse.TYPE, TransferComplete.TYPE, TransferAck.TYPE, Cancel.TYPE,
            ErrorMessage.TYPE, Ping.TYPE, Pong.TYPE,
        )
        assertEquals(tags.size, tags.toSet().size)
    }

    // ---------------------------------------------------------------------------------------
    // Rejection cases
    // ---------------------------------------------------------------------------------------

    @Test(expected = ProtocolException::class)
    fun `non json payload is rejected`() {
        MessageCodec.decode("this is not json at all")
    }

    @Test(expected = ProtocolException::class)
    fun `a json array instead of an object is rejected`() {
        MessageCodec.decode("[1,2,3]")
    }

    @Test(expected = ProtocolException::class)
    fun `an envelope without a type is rejected`() {
        MessageCodec.decode("{\"data\":{}}")
    }

    @Test(expected = ProtocolException::class)
    fun `an envelope without data is rejected`() {
        MessageCodec.decode("{\"type\":\"hello\"}")
    }

    @Test(expected = ProtocolException::class)
    fun `an unknown message type is rejected rather than ignored`() {
        MessageCodec.decode("{\"type\":\"rm -rf\",\"data\":{}}")
    }

    @Test(expected = ProtocolException::class)
    fun `a hello with a missing field is rejected`() {
        MessageCodec.decode(
            "{\"type\":\"hello\",\"data\":{\"protocolVersion\":1,\"deviceName\":\"x\",\"deviceId\":\"y\"}}",
        )
    }

    @Test(expected = ProtocolException::class)
    fun `a hello with port zero is rejected`() {
        MessageCodec.decode(
            "{\"type\":\"hello\",\"data\":{\"protocolVersion\":1,\"deviceName\":\"x\"," +
                "\"deviceId\":\"y\",\"controlPort\":0,\"groupCode\":\"\",\"appVersion\":\"\"}}",
        )
    }

    @Test(expected = ProtocolException::class)
    fun `a hello with an out of range port is rejected`() {
        MessageCodec.decode(
            "{\"type\":\"hello\",\"data\":{\"protocolVersion\":1,\"deviceName\":\"x\"," +
                "\"deviceId\":\"y\",\"controlPort\":99999,\"groupCode\":\"\",\"appVersion\":\"\"}}",
        )
    }

    @Test(expected = ProtocolException::class)
    fun `an offer with no files is rejected`() {
        MessageCodec.decode(
            "{\"type\":\"offer\",\"data\":{\"transferId\":\"t\",\"files\":[],\"totalBytes\":0}}",
        )
    }

    @Test(expected = ProtocolException::class)
    fun `an offer with a negative file size is rejected`() {
        MessageCodec.decode(
            "{\"type\":\"offer\",\"data\":{\"transferId\":\"t\",\"files\":[{\"name\":\"a\"," +
                "\"relativePath\":\"a\",\"size\":-5,\"mime\":\"text/plain\"}],\"totalBytes\":-5}}",
        )
    }

    @Test(expected = ProtocolException::class)
    fun `a file entry without a name is rejected`() {
        MessageCodec.decode(
            "{\"type\":\"offer\",\"data\":{\"transferId\":\"t\",\"files\":[{\"relativePath\":\"a\"," +
                "\"size\":1,\"mime\":\"text/plain\"}],\"totalBytes\":1}}",
        )
    }

    @Test(expected = ProtocolException::class)
    fun `a transfer ack without an ok flag is rejected`() {
        MessageCodec.decode("{\"type\":\"transfer_ack\",\"data\":{\"transferId\":\"t\"}}")
    }

    @Test
    fun `a hostile file name is sanitized at parse time, not later`() {
        val decoded = MessageCodec.decode(
            "{\"type\":\"offer\",\"data\":{\"transferId\":\"t\",\"files\":[{\"name\":" +
                "\"../../../etc/passwd\",\"relativePath\":\"../../x\",\"size\":1,\"mime\":\"text/plain\"}]," +
                "\"totalBytes\":1}}",
        ) as Offer
        assertEquals("passwd", decoded.files[0].name)
        assertFalse(decoded.files[0].name.contains('/'))
        assertFalse(decoded.files[0].relativePath.contains(".."))
    }

    @Test
    fun `an absurdly long device name is clamped rather than rejected`() {
        val longName = "A".repeat(5000)
        val decoded = MessageCodec.decode(
            "{\"type\":\"hello\",\"data\":{\"protocolVersion\":1,\"deviceName\":\"$longName\"," +
                "\"deviceId\":\"y\",\"controlPort\":8988,\"groupCode\":\"\",\"appVersion\":\"\"}}",
        ) as Hello
        assertEquals(Protocol.MAX_DEVICE_NAME_LENGTH, decoded.deviceName.length)
    }

    @Test
    fun `a pair request with non-digit characters is rejected`() {
        var threw = false
        try {
            MessageCodec.decode(
                "{\"type\":\"pair_request\",\"data\":{\"code\":\"12a45b\",\"deviceName\":\"x\",\"deviceId\":\"y\"}}",
            )
        } catch (e: ProtocolException) {
            threw = true
        }
        assertTrue("a non-numeric pairing code must be rejected", threw)
    }

    @Test
    fun `an error message falls back to a usable default when fields are absent`() {
        val decoded = MessageCodec.decode("{\"type\":\"error\",\"data\":{}}") as ErrorMessage
        assertNotNull(decoded.code)
        assertTrue(decoded.message.isNotEmpty())
    }

    @Test
    fun `a cancel without a transfer id is tolerated`() {
        val decoded = MessageCodec.decode("{\"type\":\"cancel\",\"data\":{}}") as Cancel
        assertEquals("", decoded.transferId)
        assertNull(decoded.reason)
    }
}
