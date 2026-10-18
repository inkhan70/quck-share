package com.quickshare.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Peer registry behaviour: expiry, deduplication and ordering.
 *
 * A stale peer entry is worse than a missing one - the user taps it and the send fails - so the TTL
 * behaviour is asserted directly, with an injected clock instead of a sleep.
 */
class PeerRegistryTest {

    private fun peer(id: String, name: String = "Phone", address: String = "192.168.1.5", port: Int = 8988) =
        Peer(
            deviceId = id,
            deviceName = name,
            address = address,
            controlPort = port,
            protocolVersion = 1,
            appVersion = "1.0.0",
            lastSeenMs = 0L,
        )

    @Test
    fun `a registered peer is returned`() {
        val registry = PeerRegistry()
        registry.upsert(peer("a", name = "Pixel"), nowMs = 1_000L)
        assertEquals(1, registry.size())
        assertEquals("Pixel", registry.get("a")?.deviceName)
    }

    @Test
    fun `an unknown peer is null`() {
        assertNull(PeerRegistry().get("missing"))
    }

    @Test
    fun `re-registering the same device updates rather than duplicates`() {
        val registry = PeerRegistry()
        registry.upsert(peer("a", name = "Old"), nowMs = 1_000L)
        registry.upsert(peer("a", name = "New"), nowMs = 2_000L)
        assertEquals("a device must not appear twice", 1, registry.size())
        assertEquals("New", registry.get("a")?.deviceName)
    }

    @Test
    fun `a peer whose address changed is updated in place`() {
        // A phone moving between networks is one device, not two.
        val registry = PeerRegistry()
        registry.upsert(peer("a", address = "192.168.1.5"), nowMs = 1_000L)
        registry.upsert(peer("a", address = "10.0.0.7"), nowMs = 2_000L)
        assertEquals(1, registry.size())
        assertEquals("10.0.0.7", registry.get("a")?.address)
    }

    @Test
    fun `a peer expires once its ttl has passed`() {
        val registry = PeerRegistry(ttlMs = 5_000L)
        registry.upsert(peer("a"), nowMs = 1_000L)
        assertEquals(1, registry.size(nowMs = 3_000L))
        assertEquals(0, registry.size(nowMs = 7_000L))
        assertNull("an expired peer must not be handed to the UI", registry.get("a", nowMs = 7_000L))
    }

    @Test
    fun `a peer that keeps announcing does not expire`() {
        val registry = PeerRegistry(ttlMs = 5_000L)
        registry.upsert(peer("a"), nowMs = 1_000L)
        registry.upsert(peer("a"), nowMs = 4_000L) // announced again
        assertNotNull("a live peer must stay discoverable", registry.get("a", nowMs = 7_000L))
    }

    @Test
    fun `expired peers are pruned from the list`() {
        val registry = PeerRegistry(ttlMs = 5_000L)
        registry.upsert(peer("a"), nowMs = 1_000L)
        registry.upsert(peer("b"), nowMs = 6_000L)
        val live = registry.all(nowMs = 7_000L)
        assertEquals(1, live.size)
        assertEquals("b", live.first().deviceId)
    }

    @Test
    fun `the peer's own copy records when it was last seen`() {
        val updated = peer("a").seenAt(12_345L)
        assertEquals(12_345L, updated.lastSeenMs)
    }

    @Test
    fun `expiry is based on the ttl and the last sighting`() {
        val p = peer("a").seenAt(10_000L)
        assertFalse(p.isExpired(now = 12_000L, ttlMs = 5_000L))
        assertTrue(p.isExpired(now = 16_000L, ttlMs = 5_000L))
    }

    @Test
    fun `clearing removes every peer`() {
        val registry = PeerRegistry()
        registry.upsert(peer("a"), nowMs = 1_000L)
        registry.upsert(peer("b"), nowMs = 1_000L)
        registry.clear()
        assertEquals(0, registry.size())
    }

    @Test
    fun `concurrent registration does not corrupt the registry`() {
        // Discovery beacons arrive from several devices at once, on more than one thread.
        val registry = PeerRegistry()
        val threads = (1..8).map { t ->
            Thread {
                repeat(100) { i -> registry.upsert(peer("dev-$t-$i"), nowMs = 1_000L) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(20_000) }
        assertEquals("every peer must be recorded exactly once", 800, registry.size(nowMs = 1_000L))
    }
}

/** Device identity: the default name and the derived ports. */
class DeviceIdentityTest {

    @Test
    fun `a friendly name is derived from the model hint`() {
        val name = DeviceIdentity.defaultDeviceName("Pixel 7")
        assertTrue("name was '$name'", name.isNotEmpty())
        assertTrue(name.length <= com.quickshare.core.protocol.Protocol.MAX_DEVICE_NAME_LENGTH)
    }

    @Test
    fun `a missing or blank model still yields a usable name`() {
        assertTrue(DeviceIdentity.defaultDeviceName(null).isNotEmpty())
        assertTrue(DeviceIdentity.defaultDeviceName("").isNotEmpty())
        assertTrue(DeviceIdentity.defaultDeviceName("   ").isNotEmpty())
    }

    @Test
    fun `an absurdly long model name is clamped`() {
        val name = DeviceIdentity.defaultDeviceName("X".repeat(500))
        assertTrue(name.length <= com.quickshare.core.protocol.Protocol.MAX_DEVICE_NAME_LENGTH)
    }

    @Test
    fun `the control port is inside the usable range`() {
        val port = DeviceIdentity.controlPort()
        assertTrue("port was $port", port in 1024..65535)
    }

    @Test
    fun `the discovery port is inside the usable range`() {
        val port = DeviceIdentity.discoveryPort()
        assertTrue("port was $port", port in 1024..65535)
    }

    @Test
    fun `the network signature changes when the interface set changes`() {
        val a = DeviceIdentity.networkSignature(
            listOf(InterfaceAddressInfo("wlan0", "192.168.1.10", 24, false)),
        )
        val b = DeviceIdentity.networkSignature(
            listOf(InterfaceAddressInfo("wlan0", "10.0.0.10", 24, false)),
        )
        assertTrue(a.isNotEmpty())
        assertFalse("a different network must produce a different signature", a == b)
    }
}
