package com.quickshare.app.ui

import com.quickshare.core.data.Settings
import com.quickshare.core.net.Peer
import com.quickshare.core.pairing.PairingCode
import com.quickshare.core.transfer.TransferDirection
import com.quickshare.core.transfer.TransferRecord
import com.quickshare.core.transfer.TransferStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the UI layer's decision logic.
 *
 * These are the rules that are easy to get subtly wrong and impossible to eyeball on a device - does a
 * cancelled transfer offer a Retry, may an empty selection be sent, is `0.0.0.0` a device address - so
 * they are pinned here rather than left to be discovered by a user.
 *
 * They run in the app module (not core) because that is where the logic lives, and they need no
 * Android runtime: everything under test is Android-free by construction.
 */
class UiLogicTest {

    // ---- Transfer status mapping ------------------------------------------------------------

    @Test
    fun `every transfer status maps to a distinct ui state`() {
        val mapped = TransferStatus.entries.map { uiStateOf(it) }
        assertEquals("each status needs its own presentation", TransferStatus.entries.size, mapped.toSet().size)
    }

    @Test
    fun `a successful transfer is the only happy state`() {
        assertTrue(uiStateOf(TransferStatus.SUCCESS).isOk)
        TransferStatus.entries.filter { it != TransferStatus.SUCCESS }.forEach {
            assertFalse("$it must not read as success", uiStateOf(it).isOk)
        }
    }

    @Test
    fun `retry is offered for recoverable failures but not for a user cancel`() {
        assertTrue(uiStateOf(TransferStatus.FAILED).canRetry)
        assertTrue(uiStateOf(TransferStatus.INTERRUPTED).canRetry)
        assertTrue(uiStateOf(TransferStatus.VERIFICATION_FAILED).canRetry)
        // The user chose to stop; offering Retry would second-guess them.
        assertFalse(uiStateOf(TransferStatus.CANCELLED).canRetry)
        // A peer saying no is not something a retry fixes.
        assertFalse(uiStateOf(TransferStatus.REJECTED).canRetry)
        assertFalse(uiStateOf(TransferStatus.SUCCESS).canRetry)
        assertFalse(uiStateOf(TransferStatus.IN_PROGRESS).canRetry)
    }

    @Test
    fun `a problem state is exactly the set with a reason to show`() {
        assertTrue(uiStateOf(TransferStatus.FAILED).isProblem)
        assertTrue(uiStateOf(TransferStatus.VERIFICATION_FAILED).isProblem)
        assertTrue(uiStateOf(TransferStatus.INTERRUPTED).isProblem)
        assertTrue(uiStateOf(TransferStatus.REJECTED).isProblem)
        assertFalse(uiStateOf(TransferStatus.SUCCESS).isProblem)
        assertFalse(uiStateOf(TransferStatus.IN_PROGRESS).isProblem)
        // A user cancel is not a problem to report back.
        assertFalse(uiStateOf(TransferStatus.CANCELLED).isProblem)
    }

    // ---- Send selection ---------------------------------------------------------------------

    private fun file(uri: String, bytes: Long = 100) =
        StagedFile(uri = uri, name = uri.substringAfterLast('/'), size = bytes, mime = "application/octet-stream")

    @Test
    fun `an empty selection cannot be sent`() {
        assertFalse(SendSelection().canSend)
        assertTrue(SendSelection().isEmpty)
        assertEquals(0, SendSelection().totalBytes)
    }

    @Test
    fun `adding a file makes a selection sendable`() {
        val selection = SendSelection().add(file("content://doc/a.jpg", 2048))
        assertTrue(selection.canSend)
        assertEquals(1, selection.fileCount)
        assertEquals(2048, selection.totalBytes)
    }

    @Test
    fun `re-adding the same uri does not duplicate it`() {
        val one = file("content://doc/a.jpg")
        val selection = SendSelection().add(one).add(one).add(one)
        assertEquals("a mis-tap must not send the same file three times", 1, selection.fileCount)
    }

    @Test
    fun `files with the same name but different uris are both kept`() {
        // Two folders can each hold a photo.jpg; they are different files and both must be sent.
        val a = StagedFile("content://doc/1/photo.jpg", "photo.jpg", 10, "image/jpeg")
        val b = StagedFile("content://doc/2/photo.jpg", "photo.jpg", 20, "image/jpeg")
        val selection = SendSelection().addAll(listOf(a, b))
        assertEquals(2, selection.fileCount)
        assertEquals(30, selection.totalBytes)
    }

    @Test
    fun `removing a file updates the total`() {
        val a = file("content://doc/a.jpg", 100)
        val b = file("content://doc/b.zip", 400)
        val selection = SendSelection().addAll(listOf(a, b))
        assertEquals(500, selection.totalBytes)
        val after = selection.remove(b.uri)
        assertEquals(1, after.fileCount)
        assertEquals(100, after.totalBytes)
    }

    @Test
    fun `removing a uri that is not staged is a no-op`() {
        val selection = SendSelection().add(file("content://doc/a.jpg"))
        assertEquals(1, selection.remove("content://doc/nope").fileCount)
    }

    @Test
    fun `clearing empties the selection`() {
        val selection = SendSelection().addAll(listOf(file("content://doc/a"), file("content://doc/b"))).clear()
        assertTrue(selection.isEmpty)
        assertFalse(selection.canSend)
    }

    @Test
    fun `the total is the sum across files, not the size of one`() {
        val big = SendSelection().addAll(
            listOf(file("content://doc/1", 1_500_000_000), file("content://doc/2", 2_500_000_000)),
        )
        assertEquals(4_000_000_000L, big.totalBytes)
    }

    // ---- Manual connection ------------------------------------------------------------------

    @Test
    fun `a plain address uses the default port`() {
        val result = ManualConnect.parse("192.168.1.42", 8988)
        assertTrue(result is ManualConnect.Result.Ok)
        assertEquals("192.168.1.42", (result as ManualConnect.Result.Ok).host)
        assertEquals(8988, result.port)
    }

    @Test
    fun `an explicit port overrides the default`() {
        val result = ManualConnect.parse("192.168.1.42:9000", 8988)
        assertEquals(9000, (result as ManualConnect.Result.Ok).port)
    }

    @Test
    fun `surrounding whitespace and a scheme are tolerated`() {
        assertTrue(ManualConnect.parse("  10.0.0.5  ", 8988) is ManualConnect.Result.Ok)
        assertTrue(ManualConnect.parse("http://10.0.0.5/", 8988) is ManualConnect.Result.Ok)
    }

    @Test
    fun `blank input is reported as blank, not as an error`() {
        assertTrue(ManualConnect.parse("", 8988) is ManualConnect.Result.Blank)
        assertTrue(ManualConnect.parse("   ", 8988) is ManualConnect.Result.Blank)
        assertTrue(ManualConnect.parse(null, 8988) is ManualConnect.Result.Blank)
    }

    @Test
    fun `malformed input is rejected as not an address`() {
        listOf(
            "not-an-ip",
            "192.168.1",
            "192.168.1.256",
            "192.168.1.1.1",
            "192.168.1.",
            "999.999.999.999",
            "192.168.1.1:notaport",
            "192.168.1.1:0",
            "192.168.1.1:70000",
            "010.1.1.1",
        ).forEach { input ->
            assertTrue("$input should not parse", ManualConnect.parse(input, 8988) !is ManualConnect.Result.Ok)
        }
    }

    @Test
    fun `the unroutable wildcard and broadcast are refused as targets`() {
        // Both are valid literals, which is why they get their own outcome: dialling either hangs
        // and then times out, so the user is told immediately instead.
        assertTrue(ManualConnect.parse("0.0.0.0", 8988) is ManualConnect.Result.NotDialable)
        assertTrue(ManualConnect.parse("255.255.255.255", 8988) is ManualConnect.Result.NotDialable)
        assertTrue(ManualConnect.parse("0.1.2.3", 8988) is ManualConnect.Result.NotDialable)
    }

    // ---- Peer ordering ----------------------------------------------------------------------

    private fun peer(name: String, id: String) = Peer(
        deviceId = id,
        deviceName = name,
        address = "192.168.1.10",
        controlPort = 8988,
        protocolVersion = 1,
    )

    @Test
    fun `peers are ordered case-insensitively by name`() {
        val sorted = PeerOrdering.sorted(
            listOf(peer("zoe", "1"), peer("Alice", "2"), peer("bob", "3")),
        )
        assertEquals(listOf("Alice", "bob", "zoe"), sorted.map { it.deviceName })
    }

    @Test
    fun `the order is stable when two devices share a name`() {
        val a = peer("Pixel", "aaa")
        val b = peer("Pixel", "bbb")
        assertEquals(
            listOf("aaa", "bbb"),
            PeerOrdering.sorted(listOf(b, a)).map { it.deviceId },
        )
    }

    @Test
    fun `ordering does not reshuffle on a refresh`() {
        // Same set in a different arrival order must produce the same list, or the UI reorders
        // under the user's finger every time a beacon arrives.
        val one = listOf(peer("A", "1"), peer("B", "2"), peer("C", "3"))
        assertEquals(PeerOrdering.sorted(one), PeerOrdering.sorted(one.reversed()))
    }

    // ---- History summary --------------------------------------------------------------------

    private fun record(
        id: String,
        direction: TransferDirection,
        status: TransferStatus,
        bytes: Long = 1000,
    ) = TransferRecord(
        transferId = id,
        direction = direction,
        peerName = "Pixel",
        totalBytes = bytes,
        status = status,
        fileCount = 1,
        fileNames = listOf("a.bin"),
    )

    @Test
    fun `an empty history reports nothing`() {
        val summary = summarize(emptyList())
        assertFalse(summary.hasAny)
        assertEquals(0, summary.total)
    }

    @Test
    fun `the summary counts directions and only successful bytes`() {
        val summary = summarize(
            listOf(
                record("1", TransferDirection.SEND, TransferStatus.SUCCESS, 1_000),
                record("2", TransferDirection.RECEIVE, TransferStatus.SUCCESS, 2_000),
                record("3", TransferDirection.SEND, TransferStatus.FAILED, 500_000),
                record("4", TransferDirection.RECEIVE, TransferStatus.CANCELLED, 99_000),
            ),
        )
        assertEquals(4, summary.total)
        assertEquals(2, summary.sent)
        assertEquals(2, summary.received)
        assertEquals(2, summary.failed)
        // Only the two successful records count; a failed transfer moved no usable data.
        assertEquals(3_000, summary.successfulBytes)
    }

    @Test
    fun `an in-flight record is not counted as a failure`() {
        val summary = summarize(
            listOf(
                record("1", TransferDirection.SEND, TransferStatus.IN_PROGRESS),
                record("2", TransferDirection.SEND, TransferStatus.INTERRUPTED),
            ),
        )
        assertEquals(1, summary.failed)
    }

    // ---- Theme ------------------------------------------------------------------------------

    @Test
    fun `stored theme values round-trip`() {
        ThemeOption.entries.forEach { option ->
            assertEquals(option, ThemeOption.from(option.storageValue))
        }
    }

    @Test
    fun `an unknown or absent stored theme falls back to system`() {
        assertEquals(ThemeOption.SYSTEM, ThemeOption.from(null))
        assertEquals(ThemeOption.SYSTEM, ThemeOption.from(""))
        assertEquals(ThemeOption.SYSTEM, ThemeOption.from("sepia"))
    }

    @Test
    fun `no theme option shares a stored value with another`() {
        val values = ThemeOption.entries.map { it.storageValue }
        assertEquals(values.size, values.toSet().size)
        // These are the constants the core's Settings normaliser understands.
        assertTrue(values.containsAll(listOf(Settings.DARK_MODE_SYSTEM, Settings.DARK_MODE_LIGHT, Settings.DARK_MODE_DARK)))
    }

    // ---- Pairing code formatting ------------------------------------------------------------

    @Test
    fun `a pairing code is displayed grouped for reading aloud`() {
        val code = PairingCode.generate()
        val formatted = PairingCode.format(code)
        assertTrue("a 6-digit code should be shown in two groups", formatted.contains(" "))
        // Whitespace is presentation only; the underlying code is unchanged.
        assertEquals(code, formatted.replace(" ", ""))
    }
}
