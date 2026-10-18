package com.quickshare.core

import com.quickshare.core.data.InMemoryHistoryStore
import com.quickshare.core.data.TransferHistoryRepository
import com.quickshare.core.net.NetworkUtils
import com.quickshare.core.net.PeerRegistry
import com.quickshare.core.protocol.MessageCodec
import com.quickshare.core.protocol.ProtocolException
import com.quickshare.core.transfer.StorageGuard
import com.quickshare.core.transfer.TransferDirection
import com.quickshare.core.transfer.TransferRecord
import com.quickshare.core.transfer.TransferStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for defects found in the source review. Each one failed before its fix. */
class ReviewFixesTest {

    private fun offerJson(sizeA: Long, sizeB: Long) =
        """{"type":"offer","data":{"transferId":"t1","totalBytes":0,"files":[
            {"name":"a.bin","relativePath":"a.bin","size":$sizeA,"mime":"application/octet-stream"},
            {"name":"b.bin","relativePath":"b.bin","size":$sizeB,"mime":"application/octet-stream"}]}}"""

    @Test
    fun `an offer whose sizes overflow the total is rejected`() {
        // 2^62 + 2^62 wraps to Long.MIN_VALUE, which used to slip past the free-space check.
        val half = 1L shl 62
        try {
            MessageCodec.decode(offerJson(half, half))
            throw AssertionError("expected a ProtocolException")
        } catch (e: ProtocolException) {
            assertTrue(e.message!!.contains("out of range"))
        }
    }

    @Test
    fun `deeply nested json is a protocol error not an uncaught exception`() {
        val nested = "[".repeat(200_000)
        try {
            MessageCodec.decode(nested)
            throw AssertionError("expected a ProtocolException")
        } catch (e: ProtocolException) {
            // expected
        }
    }

    @Test
    fun `storage guard cannot be fooled by huge requirements`() {
        assertFalse(StorageGuard.hasRoom(Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(StorageGuard.hasRoom(Long.MAX_VALUE - 10, Long.MAX_VALUE - 5))
        assertNotNull(StorageGuard.check(Long.MAX_VALUE, 1L shl 40))
        assertTrue(StorageGuard.safetyMargin(Long.MAX_VALUE) > 0)
    }

    @Test
    fun `subnet check honours the real prefix length`() {
        // Same /23 network, different /24s: the old /24 shortcut refused this peer.
        assertTrue(NetworkUtils.sameSubnet("192.168.4.10", "192.168.5.20", 23))
        assertFalse(NetworkUtils.sameSubnet("192.168.4.10", "192.168.5.20", 24))
        assertTrue(NetworkUtils.sameSubnet("10.1.2.3", "10.1.200.9", 16))
        assertFalse(NetworkUtils.sameSubnet("10.1.2.3", "10.2.2.3", 16))
        // Nonsense prefixes fall back to /24.
        assertTrue(NetworkUtils.sameSubnet("192.168.1.1", "192.168.1.9", 0))
        assertFalse(NetworkUtils.sameSubnet("192.168.1.1", "192.168.2.9", 99))
    }

    @Test
    fun `cellular and vpn interfaces are not local networks`() {
        listOf("rmnet_data0", "ccmni1", "tun0", "ppp0", "v4-rmnet_data2").forEach {
            assertTrue(it, NetworkUtils.isWanInterface(it))
        }
        listOf("wlan0", "eth0", "ap0", "swlan0").forEach { assertFalse(it, NetworkUtils.isWanInterface(it)) }
    }

    @Test
    fun `manually added peers survive the discovery sweep`() {
        var now = 1_000L
        val registry = PeerRegistry(ttlMs = 12_000L, clock = { now })
        registry.addManual("192.168.1.50", 8988, "Typed in", "manual-1", 1, "", "")
        now += 60_000L
        registry.sweep(now)
        assertEquals(1, registry.size())
        registry.remove("manual-1")
        assertEquals(0, registry.size())
    }

    @Test
    fun `stale in-progress history rows become interrupted`() {
        val repo = TransferHistoryRepository(InMemoryHistoryStore())
        repo.upsert(TransferRecord("running", TransferDirection.SEND, "Phone", status = TransferStatus.IN_PROGRESS))
        repo.upsert(TransferRecord("done", TransferDirection.SEND, "Phone", status = TransferStatus.SUCCESS))
        assertEquals(1, repo.markStaleInterrupted())
        assertEquals(0, repo.markStaleInterrupted())
        val byId = repo.all().associateBy { it.transferId }
        assertEquals(TransferStatus.INTERRUPTED, byId.getValue("running").status)
        assertTrue(byId.getValue("running").finishedAtMs > 0)
        assertEquals(TransferStatus.SUCCESS, byId.getValue("done").status)
    }

    @Test
    fun `record summary separates count and size`() {
        val record = TransferRecord(
            "t", TransferDirection.SEND, "Phone", fileCount = 3, totalBytes = 2048L,
            status = TransferStatus.SUCCESS,
        )
        assertTrue(record.summary(), record.summary().contains(" \u00b7 "))
    }
}
