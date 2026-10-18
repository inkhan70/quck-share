package com.quickshare.core.net

import com.quickshare.core.util.Logs
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

/**
 * Local-network address helpers.
 *
 * Discovery needs a broadcast address per interface; the sender needs a routable local address to
 * advertise. Both are derived here so the logic is testable and there is exactly one place that
 * knows how to walk [NetworkInterface].
 */
object NetworkUtils {

    private const val TAG = "NetworkUtils"

    /**
     * Every IPv4 interface that is up, is not loopback, and has an address. Ordering is stable so
     * a device with both Wi-Fi and a hotspot reports consistently.
     */
    fun activeIpv4Interfaces(): List<InterfaceAddressInfo> {
        val result = mutableListOf<InterfaceAddressInfo>()
        val interfaces = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        } catch (e: Exception) {
            Logs.w(TAG, "Could not enumerate network interfaces", e)
            return result
        }

        for (nif in interfaces) {
            try {
                if (!nif.isUp || nif.isLoopback) continue
                // Mobile-data and VPN tunnels carry an IPv4 address too, but they are not a local
                // network: counting them made the app report "on Wi-Fi" (and advertise an unreachable
                // address) on a phone that only had mobile data.
                if (isWanInterface(nif.name)) continue
                val addresses = Collections.list(nif.inetAddresses)
                for (address in addresses) {
                    if (address !is Inet4Address) continue
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    result += InterfaceAddressInfo(
                        interfaceName = nif.name,
                        address = address.hostAddress ?: continue,
                        broadcastAddress = broadcastAddressFor(address, nif),
                        prefixLength = prefixLengthFor(nif, address),
                    )
                }
            } catch (e: Exception) {
                // A single misbehaving interface must never take down discovery entirely.
                Logs.w(TAG, "Skipping interface ${nif.name}", e)
            }
        }
        return result
    }

    private val WAN_INTERFACE_PREFIXES = listOf("rmnet", "ccmni", "pdp", "ppp", "tun", "clat", "v4-rmnet", "ipsec")

    /** True for cellular and VPN interfaces, which are never part of the local Wi-Fi network. */
    fun isWanInterface(name: String?): Boolean {
        val lower = name?.lowercase(Locale.ROOT) ?: return false
        return WAN_INTERFACE_PREFIXES.any { lower.startsWith(it) }
    }

    /** The first non-loopback IPv4 address, or null when the device is offline. */
    fun primaryLocalAddress(): String? = activeIpv4Interfaces().firstOrNull()?.address

    /**
     * The subnet broadcast address for [address] on [nif] (e.g. `192.168.1.255`).
     *
     * Falls back to the conventional class-C broadcast when the interface does not expose its
     * prefixes, which is a graceful degradation: a wrong broadcast only costs extra packets, never
     * correctness, because real peers are also reachable by their announced address.
     */
    fun broadcastAddressFor(address: Inet4Address, nif: NetworkInterface): String {
        val prefix = prefixLengthFor(nif, address)
        val ip = ipToLong(address.hostAddress ?: "0.0.0.0")
        val mask = if (prefix in 1..32) maskFromPrefix(prefix) else maskFromPrefix(24)
        val broadcast = (ip and mask) or (mask.inv() and 0xFFFFFFFFL)
        return longToIp(broadcast)
    }

    fun prefixLengthFor(nif: NetworkInterface, address: InetAddress): Int {
        return try {
            nif.interfaceAddresses
                .firstOrNull { it.address == address }
                ?.networkPrefixLength
                ?.toInt()
                ?: 24
        } catch (e: Exception) {
            24
        }
    }

    /**
     * A short code derived from the local /24, used to tell "same Wi-Fi" from "different network"
     * when a device is reachable through a bridged or routed setup.
     */
    fun groupCode(localAddress: String?): String {
        if (localAddress.isNullOrBlank()) return "----"
        val parts = localAddress.split('.')
        if (parts.size != 4) return "----"
        val third = parts[2].toIntOrNull() ?: return "----"
        return String.format(Locale.US, "%02x%02x", parts[1].toIntOrNull() ?: 0, third)
    }

    /** True when both addresses sit in the same /24, i.e. almost certainly the same Wi-Fi. */
    fun sameSubnet(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val pa = a.split('.')
        val pb = b.split('.')
        if (pa.size != 4 || pb.size != 4) return false
        return pa[0] == pb[0] && pa[1] == pb[1] && pa[2] == pb[2]
    }

    /**
     * True when [a] and [b] are in the same subnet of the given [prefixLength].
     *
     * The /24 shortcut in the two-argument form is wrong on larger networks (/23, /22, /16 are common
     * on routers, schools and offices): two devices on the same Wi-Fi would be refused as "outside the
     * local network". An out-of-range prefix falls back to /24.
     */
    fun sameSubnet(a: String?, b: String?, prefixLength: Int): Boolean {
        if (!isValidIpv4(a) || !isValidIpv4(b)) return false
        val prefix = if (prefixLength in 8..32) prefixLength else 24
        val mask = maskFromPrefix(prefix)
        return (ipToLong(a!!.trim()) and mask) == (ipToLong(b!!.trim()) and mask)
    }

    /**
     * True when [value] is a syntactically valid IPv4 literal.
     *
     * This is a *shape* check, not a reachability check: `0.0.0.0` and `255.255.255.255` are valid
     * literals, and the QR payload parser relies on that (a payload carrying either must be
     * recognised as well-formed so it can be rejected for a specific reason rather than looking like
     * a malformed code). Callers that need "can I actually connect to this" use
     * [isUsableHostAddress].
     *
     * Multi-digit parts with a leading zero are refused (`010.1.1.1`), because the value is
     * ambiguous between decimal and octal across stacks.
     */
    fun isValidIpv4(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val trimmed = value.trim()
        val parts = trimmed.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            if (part.isEmpty() || part.length > 3) return@all false
            if (part.any { !it.isDigit() }) return@all false
            if (part.length > 1 && part[0] == '0') return@all false
            val v = part.toIntOrNull() ?: return@all false
            v in 0..255
        }
    }

    /**
     * True when [value] is an IPv4 literal that can sensibly be dialled.
     *
     * Stricter than [isValidIpv4] by exactly the cases that make a connection attempt meaningless:
     * `0.0.0.0` (unroutable "any" address) and the limited broadcast `255.255.255.255`. Used by the
     * manual-IP fallback so the user gets an immediate "that is not a device address" instead of a
     * transfer that hangs and then times out.
     */
    fun isUsableHostAddress(value: String?): Boolean {
        if (!isValidIpv4(value)) return false
        val trimmed = value!!.trim()
        return trimmed != "0.0.0.0" && trimmed != "255.255.255.255" && !trimmed.startsWith("0.")
    }

    /** Accepts `host` or `host:port`; returns the pair, or null when malformed or unroutable. */
    fun parseHostPort(input: String?, defaultPort: Int): Pair<String, Int>? {
        if (input.isNullOrBlank()) return null
        val trimmed = input.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (trimmed.isEmpty()) return null

        val parts = trimmed.split(':')
        return when (parts.size) {
            1 -> if (isUsableHostAddress(parts[0])) parts[0] to defaultPort else null
            2 -> {
                if (!isUsableHostAddress(parts[0])) return null
                val port = parts[1].toIntOrNull() ?: return null
                if (port !in 1..65535) null else parts[0] to port
            }
            else -> null
        }
    }

    private fun maskFromPrefix(prefix: Int): Long {
        if (prefix <= 0) return 0L
        if (prefix >= 32) return 0xFFFFFFFFL
        return (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
    }

    fun ipToLong(ip: String): Long {
        val parts = ip.split('.')
        if (parts.size != 4) return 0L
        var result = 0L
        for (part in parts) {
            val v = part.toIntOrNull() ?: return 0L
            result = (result shl 8) or (v.toLong() and 0xFF)
        }
        return result and 0xFFFFFFFFL
    }

    fun longToIp(value: Long): String =
        "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"
}

/**
 * One IPv4 interface with everything discovery and advertising need.
 *
 * [broadcastAddress] is derived at construction time by [NetworkUtils.activeIpv4Interfaces], so it
 * is left blank-safe: a caller that builds the record by hand (a test, or a future platform
 * adapter) still gets a usable value rather than a silent empty string, and [DiscoveryService]
 * additionally falls back to limited broadcast when it is empty.
 */
data class InterfaceAddressInfo(
    val interfaceName: String,
    val address: String,
    val prefixLength: Int = 24,
    val isUp: Boolean = true,
    val broadcastAddress: String = "",
)
