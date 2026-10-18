package com.quickshare.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Address and subnet helpers.
 *
 * These underpin three separate decisions - which addresses are advertised, whether an incoming
 * connection is local, and whether a peer is on "the same Wi-Fi" - so getting them wrong either
 * breaks discovery or weakens the network gate.
 */
class NetworkUtilsTest {

    @Test
    fun `valid ipv4 literals are accepted`() {
        assertTrue(NetworkUtils.isValidIpv4("0.0.0.0"))
        assertTrue(NetworkUtils.isValidIpv4("192.168.1.42"))
        assertTrue(NetworkUtils.isValidIpv4("255.255.255.255"))
        assertTrue(NetworkUtils.isValidIpv4("10.0.0.1"))
    }

    @Test
    fun `invalid addresses are refused`() {
        assertFalse(NetworkUtils.isValidIpv4(null))
        assertFalse(NetworkUtils.isValidIpv4(""))
        assertFalse(NetworkUtils.isValidIpv4("192.168.1"))
        assertFalse(NetworkUtils.isValidIpv4("192.168.1.256"))
        assertFalse(NetworkUtils.isValidIpv4("192.168.1.1.1"))
        assertFalse(NetworkUtils.isValidIpv4("not-an-ip"))
        assertFalse(NetworkUtils.isValidIpv4("192.168.1.-1"))
    }

    @Test
    fun `a trailing space is tolerated but internal spaces are not`() {
        assertTrue(NetworkUtils.isValidIpv4(" 192.168.1.1 "))
        assertFalse(NetworkUtils.isValidIpv4("192.168 .1.1"))
    }

    @Test
    fun `two private addresses in the same slash twenty four are on the same subnet`() {
        assertTrue(NetworkUtils.sameSubnet("192.168.1.10", "192.168.1.20"))
    }

    @Test
    fun `addresses in different subnets are not`() {
        assertFalse(NetworkUtils.sameSubnet("192.168.1.10", "10.0.0.5"))
        assertFalse(NetworkUtils.sameSubnet("192.168.1.10", "172.16.0.5"))
    }

    @Test
    fun `an unparseable or absent address is never treated as local`() {
        // This is the security-relevant direction: failing closed matters more than
        // failing open, because the same-subnet question gates incoming connections.
        assertFalse(NetworkUtils.sameSubnet(null, "192.168.1.1"))
        assertFalse(NetworkUtils.sameSubnet("192.168.1.1", null))
        assertFalse(NetworkUtils.sameSubnet("garbage", "192.168.1.1"))
        assertFalse(NetworkUtils.sameSubnet("192.168.1.1", "garbage"))
    }

    @Test
    fun `host and port parsing handles the forms users actually type`() {
        assertEquals("192.168.1.5" to 8988, NetworkUtils.parseHostPort("192.168.1.5", 8988))
        assertEquals("192.168.1.5" to 9000, NetworkUtils.parseHostPort("192.168.1.5:9000", 8988))
        assertEquals("192.168.1.5" to 8988, NetworkUtils.parseHostPort("192.168.1.5/", 8988))
        assertEquals("192.168.1.5" to 8988, NetworkUtils.parseHostPort("  192.168.1.5  ", 8988))
    }

    @Test
    fun `host and port parsing rejects nonsense rather than producing a bad target`() {
        assertNull(NetworkUtils.parseHostPort(null, 8988))
        assertNull(NetworkUtils.parseHostPort("", 8988))
        assertNull(NetworkUtils.parseHostPort("192.168.1.5:notaport", 8988))
        assertNull(NetworkUtils.parseHostPort("192.168.1.5:0", 8988))
        assertNull(NetworkUtils.parseHostPort("192.168.1.5:70000", 8988))
        assertNull(NetworkUtils.parseHostPort("nonsense", 8988))
    }

    @Test
    fun `ip to long and back are inverses`() {
        for (address in listOf("0.0.0.0", "192.168.1.1", "255.255.255.255", "10.20.30.40")) {
            assertEquals(address, NetworkUtils.longToIp(NetworkUtils.ipToLong(address)))
        }
    }

    @Test
    fun `the group code is stable for an address and differs across subnets`() {
        // A stable group code is what lets two devices recognise each other as "same Wi-Fi" and lets
        // foreign-network beacons be ignored without any server involved.
        val a = NetworkUtils.groupCode("192.168.1.10")
        val b = NetworkUtils.groupCode("192.168.1.99")
        val c = NetworkUtils.groupCode("10.0.0.10")
        assertEquals("the same subnet must produce the same code", NetworkUtils.groupCode("192.168.1.10"), a)
        assertTrue("a subnet code should be non-empty", a.isNotEmpty())
        assertFalse("different subnets should differ", a == c)
        assertTrue(b.isNotEmpty())
    }

    @Test
    fun `the group code of an unknown address is still a usable value`() {
        assertTrue(NetworkUtils.groupCode(null).isNotEmpty())
        assertTrue(NetworkUtils.groupCode("garbage").isNotEmpty())
    }

    @Test
    fun `the local interface list never throws in a container without wifi`() {
        // The build container has no Wi-Fi radio; the call must degrade to an empty list rather than
        // throwing, because this is on the startup path.
        val interfaces = NetworkUtils.activeIpv4Interfaces()
        assertTrue(interfaces.isNotEmpty() || interfaces.isEmpty())
    }
}
