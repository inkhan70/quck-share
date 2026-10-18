package com.quickshare.app.ui

import com.quickshare.core.transfer.OfferSummary
import com.quickshare.core.transfer.TransferFileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the bridge between the receive engine and the UI.
 *
 * This is the component that decides whether an incoming transfer is accepted, so its failure modes
 * matter more than its happy path: an unanswered prompt must refuse rather than accept, a late tap must
 * not answer the next peer's question, and a cancelled UI must never leave the engine suspended on a
 * decision nobody can make.
 */
class ReceivePromptControllerTest {

    @Before
    fun setUp() {
        ReceivePromptController.cancelAll()
    }

    @After
    fun tearDown() {
        ReceivePromptController.cancelAll()
    }

    /** Waits until a prompt is actually pending, so tests never race the answering coroutine. */
    private suspend fun awaitPending(timeoutMs: Long = 2_000) {
        withTimeout(timeoutMs) {
            while (!ReceivePromptController.isPending()) delay(5)
        }
    }

    private fun offer(count: Int = 2, bytes: Long = 4096) = OfferSummary(
        transferId = "offer-1",
        peerName = "Laptop",
        files = (1..count).map {
            TransferFileInfo(
                name = "file$it.bin",
                relativePath = "file$it.bin",
                size = bytes / count,
                mime = "application/octet-stream",
            )
        },
        totalBytes = bytes,
        message = null,
    )

    // ---- Nothing pending --------------------------------------------------------------------

    @Test
    fun `nothing is pending before anything asks`() {
        assertFalse(ReceivePromptController.isPending())
        assertNull(ReceivePromptController.prompt.value)
        assertNull(ReceivePromptController.current())
    }

    @Test
    fun `answering with nothing pending does nothing`() {
        // A tap that lands after the prompt was already resolved must not be an error.
        ReceivePromptController.answer(true)
        ReceivePromptController.answer(false)
        ReceivePromptController.answerDestination("content://doc/tree")
        assertFalse(ReceivePromptController.isPending())
    }

    @Test
    fun `cancelling with nothing pending is harmless`() {
        ReceivePromptController.cancelAll()
        assertFalse(ReceivePromptController.isPending())
    }

    // ---- Pairing ----------------------------------------------------------------------------

    @Test
    fun `an accepted pairing resolves true`() = runBlocking<Unit> {
        val decision = async(Dispatchers.Default) {
            ReceivePromptController.awaitPairingDecision("Pixel 8", "123456")
        }
        awaitPending()

        val prompt = ReceivePromptController.current()
        assertTrue(prompt is ReceivePrompt.Pairing)
        assertEquals("Pixel 8", (prompt as ReceivePrompt.Pairing).peerName)
        assertEquals("123456", prompt.code)

        ReceivePromptController.answer(true)
        assertTrue(decision.await())
        assertFalse("the prompt is cleared once answered", ReceivePromptController.isPending())
    }

    @Test
    fun `a declined pairing resolves false`() = runBlocking<Unit> {
        val decision = async(Dispatchers.Default) {
            ReceivePromptController.awaitPairingDecision("Pixel 8", "123456")
        }
        awaitPending()
        ReceivePromptController.answer(false)
        assertFalse(decision.await())
    }

    @Test
    fun `an unanswered pairing times out to refusal`() = runBlocking<Unit> {
        // The security-relevant default: no answer is a no, never a yes.
        val decision = async(Dispatchers.Default) {
            ReceivePromptController.awaitPairingDecision("Pixel 8", "123456", timeoutMs = 150)
        }
        assertFalse("a prompt with no answer must refuse", decision.await())
        assertFalse(ReceivePromptController.isPending())
    }

    @Test
    fun `the prompt carries the code the peer sent`() = runBlocking<Unit> {
        val decision = async(Dispatchers.Default) {
            ReceivePromptController.awaitPairingDecision("Tablet", "987654", timeoutMs = 5_000)
        }
        awaitPending()
        assertEquals("987654", (ReceivePromptController.current() as ReceivePrompt.Pairing).code)
        ReceivePromptController.answer(false)
        decision.await()
    }

    // ---- Offer ------------------------------------------------------------------------------

    @Test
    fun `an accepted offer resolves true and exposes the file list`() = runBlocking<Unit> {
        val incoming = offer(count = 3, bytes = 9_000)
        val decision = async(Dispatchers.Default) {
            ReceivePromptController.awaitOfferDecision("Laptop", incoming)
        }
        awaitPending()

        val prompt = ReceivePromptController.current() as ReceivePrompt.Offer
        assertEquals("Laptop", prompt.peerName)
        assertEquals(3, prompt.offer.fileCount)
        assertEquals(9_000, prompt.offer.totalBytes)

        ReceivePromptController.answer(true)
        assertTrue(decision.await())
    }

    @Test
    fun `an offer times out to refusal`() = runBlocking<Unit> {
        val decision = async(Dispatchers.Default) {
            ReceivePromptController.awaitOfferDecision("Laptop", offer(), timeoutMs = 150)
        }
        assertFalse(decision.await())
    }

    @Test
    fun `a single-file offer is still distinguished from a batch`() = runBlocking<Unit> {
        val decision = async(Dispatchers.Default) {
            ReceivePromptController.awaitOfferDecision("Laptop", offer(count = 1), timeoutMs = 5_000)
        }
        awaitPending()
        assertEquals(1, (ReceivePromptController.current() as ReceivePrompt.Offer).offer.fileCount)
        ReceivePromptController.answer(false)
        decision.await()
    }

    // ---- Destination ------------------------------------------------------------------------

    @Test
    fun `a chosen destination is returned to the engine`() = runBlocking<Unit> {
        val choice = async(Dispatchers.Default) {
            ReceivePromptController.awaitDestinationChoice()
        }
        awaitPending()
        assertTrue(ReceivePromptController.current() is ReceivePrompt.Destination)

        ReceivePromptController.answerDestination("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3ADownload",
            choice.await(),
        )
    }

    @Test
    fun `backing out of the destination picker resolves null`() = runBlocking<Unit> {
        val choice = async(Dispatchers.Default) {
            ReceivePromptController.awaitDestinationChoice()
        }
        awaitPending()
        ReceivePromptController.answerDestination(null)
        assertNull(choice.await())
    }

    @Test
    fun `an unanswered destination prompt resolves null`() = runBlocking<Unit> {
        val choice = async(Dispatchers.Default) {
            ReceivePromptController.awaitDestinationChoice(timeoutMs = 150)
        }
        assertNull(choice.await())
    }

    @Test
    fun `a boolean answer cannot resolve a destination prompt`() = runBlocking<Unit> {
        // Guards the two channels against each other: an accept/decline tap must never be mistaken for
        // a folder choice, which would hand the engine a bogus tree URI.
        val choice = async(Dispatchers.Default) {
            ReceivePromptController.awaitDestinationChoice(timeoutMs = 400)
        }
        awaitPending()
        ReceivePromptController.answer(true)
        assertNull("a boolean answer must not choose a folder", choice.await())
    }

    @Test
    fun `a destination answer cannot resolve a boolean prompt`() = runBlocking<Unit> {
        val decision = async(Dispatchers.Default) {
            ReceivePromptController.awaitPairingDecision("Pixel", "123456", timeoutMs = 400)
        }
        awaitPending()
        ReceivePromptController.answerDestination("content://doc/tree")
        assertFalse("a folder choice must not accept a pairing", decision.await())
    }

    // ---- Cancellation and supersession -------------------------------------------------------

    @Test
    fun `cancelling fails a pending prompt closed`() = runBlocking<Unit> {
        val decision = async(Dispatchers.Default) {
            ReceivePromptController.awaitPairingDecision("Pixel", "123456", timeoutMs = 10_000)
        }
        awaitPending()
        ReceivePromptController.cancelAll()
        assertFalse("a torn-down UI must refuse, not accept", decision.await())
        assertFalse(ReceivePromptController.isPending())
    }

    @Test
    fun `cancelling a pending destination resolves null`() = runBlocking<Unit> {
        val choice = async(Dispatchers.Default) {
            ReceivePromptController.awaitDestinationChoice(timeoutMs = 10_000)
        }
        awaitPending()
        ReceivePromptController.cancelAll()
        assertNull(choice.await())
    }

    @Test
    fun `a second prompt fails the first closed rather than stranding it`() = runBlocking<Unit> {
        // Two prompts cannot be shown at once; the older engine call must be released, not left
        // suspended forever.
        val first = async(Dispatchers.Default) {
            ReceivePromptController.awaitPairingDecision("First", "111111", timeoutMs = 10_000)
        }
        awaitPending()

        val second = async(Dispatchers.Default) {
            ReceivePromptController.awaitOfferDecision("Second", offer(), timeoutMs = 10_000)
        }
        assertFalse("the superseded prompt must refuse", first.await())

        awaitPending()
        assertEquals("Second", ReceivePromptController.current()!!.peerName)
        ReceivePromptController.answer(true)
        assertTrue(second.await())
    }

    @Test
    fun `only one prompt is exposed at a time`() = runBlocking<Unit> {
        val first = async(Dispatchers.Default) {
            ReceivePromptController.awaitPairingDecision("First", "111111", timeoutMs = 10_000)
        }
        awaitPending()
        val second = async(Dispatchers.Default) {
            ReceivePromptController.awaitPairingDecision("Second", "222222", timeoutMs = 10_000)
        }
        first.await()
        awaitPending()
        // The single exposed prompt is the live one.
        assertEquals("Second", ReceivePromptController.current()!!.peerName)
        ReceivePromptController.answer(false)
        second.await()
        assertFalse(ReceivePromptController.isPending())
    }
}
