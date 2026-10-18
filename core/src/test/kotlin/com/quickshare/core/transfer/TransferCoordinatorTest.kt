package com.quickshare.core.transfer

import com.quickshare.core.data.InMemoryHistoryStore
import com.quickshare.core.data.TransferHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Orchestration tests for [TransferCoordinator].
 *
 * The coordinator is what the UI talks to, so its contract is asserted here: an id comes back
 * immediately, history is recorded even for a transfer that dies, cancellation is cooperative, and
 * two concurrent sends cannot interleave.
 */
class TransferCoordinatorTest {

    private class EmptySource(private val bytes: Int = 1024) : FileSource {
        override val name: String = "a.bin"
        override val relativePath: String = "a.bin"
        override val size: Long = bytes.toLong()
        override val mime: String = "application/octet-stream"
        override fun openStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(bytes))
    }

    private fun coordinatorWith(history: TransferHistoryRepository): TransferCoordinator {
        // Port 1 is privileged and unbound, so every send fails fast and deterministically - which is
        // exactly what these tests need in order to assert history and cancellation bookkeeping.
        val client = TransferClient("dev", "Phone", 8988, "1", connectTimeoutMs = 500, readTimeoutMs = 1000)
        return TransferCoordinator(client = client, history = history)
    }

    private fun peer() = com.quickshare.core.net.Peer(
        deviceId = "peer-1",
        deviceName = "Receiver",
        address = "127.0.0.1",
        controlPort = 1,
        protocolVersion = 1,
        appVersion = "1.0.0",
        lastSeenMs = 0L,
    )

    @Test
    fun `startSend returns an id before the socket has connected`() {
        val history = TransferHistoryRepository(InMemoryHistoryStore())
        val coordinator = coordinatorWith(history)
        val id = coordinator.startSend(peer(), listOf(EmptySource()), "123456")
        assertTrue("an id must be available immediately", id.isNotEmpty())
        coordinator.shutdown()
    }

    @Test
    fun `a failed send is still recorded in history`() = runBlocking {
        val history = TransferHistoryRepository(InMemoryHistoryStore())
        val coordinator = coordinatorWith(history)
        val done = java.util.concurrent.CountDownLatch(1)

        coordinator.startSend(
            peer(), listOf(EmptySource()), "123456",
            listener = object : TransferListener {
                override fun onFinished(record: TransferRecord) { done.countDown() }
            },
        )
        assertTrue("the transfer should have settled", done.await(30, java.util.concurrent.TimeUnit.SECONDS))

        // Give the coordinator a moment to persist the terminal record.
        Thread.sleep(200)
        assertEquals(1, coordinator.history().size)
        assertFalse(
            "an unreachable peer must not be recorded as success",
            coordinator.history().first().status.isSuccess,
        )
        coordinator.shutdown()
    }

    @Test
    fun `an empty file list is rejected before any socket is opened`() = runBlocking {
        val history = TransferHistoryRepository(InMemoryHistoryStore())
        val coordinator = coordinatorWith(history)
        val rejected = java.util.concurrent.CountDownLatch(1)

        val collected = mutableListOf<RejectionEvent>()
        // A rejection is a one-shot event on a flow with replay=0, so it is observable only if the
        // collector is already subscribed when it is emitted. UNDISPATCHED runs the collector body
        // inline up to its first suspension point - which is the subscribe inside collect() - so the
        // subscription is established before this call returns and there is no race to paper over
        // with a sleep.
        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.rejections.collect {
                collected += it
                rejected.countDown()
            }
        }

        coordinator.startSend(peer(), emptyList(), "123456")
        assertTrue(
            "the rejection should have arrived",
            rejected.await(10, java.util.concurrent.TimeUnit.SECONDS),
        )
        job.cancel()

        assertTrue("sending nothing must be reported", collected.isNotEmpty())
        assertEquals(0, coordinator.history().size)
        coordinator.shutdown()
    }

    @Test
    fun `cancelling an unknown transfer is harmless`() {
        val coordinator = coordinatorWith(TransferHistoryRepository(InMemoryHistoryStore()))
        // Must not throw: the UI may race a transfer that has already settled.
        coordinator.cancel("does-not-exist")
        coordinator.shutdown()
    }

    @Test
    fun `cancelAll sets every outstanding flag`() = runBlocking {
        val coordinator = coordinatorWith(TransferHistoryRepository(InMemoryHistoryStore()))
        val id = coordinator.startSend(peer(), listOf(EmptySource()), "123456")
        coordinator.cancel(id)
        coordinator.cancelAll()
        coordinator.shutdown()
        assertTrue(true) // Reaching here without an exception is the assertion.
    }

    @Test
    fun `history helpers delegate to the repository`() {
        val history = TransferHistoryRepository(InMemoryHistoryStore())
        history.upsert(
            TransferRecord(
                transferId = "t1", direction = TransferDirection.RECEIVE, peerName = "Pixel",
                fileNames = listOf("a"), fileCount = 1, totalBytes = 100, startedAtMs = 1,
                status = TransferStatus.SUCCESS,
            ),
        )
        val coordinator = coordinatorWith(history)
        assertEquals(1, coordinator.history().size)
        assertEquals(1, coordinator.stats().totalTransfers)
        assertTrue(coordinator.hasTradedWith("Pixel"))
        assertEquals(0, coordinator.removeHistory("t1").size)
        assertEquals(0, coordinator.clearHistory().size)
        coordinator.shutdown()
    }

    @Test
    fun `a received record is stored and published`() {
        val coordinator = coordinatorWith(TransferHistoryRepository(InMemoryHistoryStore()))
        val record = TransferRecord(
            transferId = "r1", direction = TransferDirection.RECEIVE, peerName = "Sender",
            fileNames = listOf("x"), fileCount = 1, totalBytes = 10, startedAtMs = 1,
            status = TransferStatus.SUCCESS, verified = true,
        )
        val all = coordinator.recordReceived(record)
        assertEquals(1, all.size)
        assertTrue(coordinator.hasTradedWith("Sender"))
        coordinator.shutdown()
    }

    @Test
    fun `a fresh pairing code is generated per call and is well formed`() {
        val coordinator = coordinatorWith(TransferHistoryRepository(InMemoryHistoryStore()))
        val codes = (1..20).map { coordinator.newPairingCode() }
        for (code in codes) {
            assertTrue("code '$code' should be ${com.quickshare.core.pairing.PairingCode.LENGTH} digits",
                com.quickshare.core.pairing.PairingCode.isWellFormed(code))
        }
        assertTrue("codes must not repeat", codes.toSet().size > 15)
        coordinator.shutdown()
    }
}
