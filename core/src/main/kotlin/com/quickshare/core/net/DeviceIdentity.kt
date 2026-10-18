package com.quickshare.core.net

import com.quickshare.core.util.Logs
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Derives a stable, private device identifier and a user-facing default device name.
 *
 * The identifier is intentionally *not* the Android ID or any hardware serial: it is a random value
 * generated once and persisted by the caller. That keeps the device unlinkable across reinstalls
 * and avoids needing any restricted hardware identifier API.
 */
object DeviceIdentity {

    private const val TAG = "DeviceIdentity"

    /**
     * A private multicast group derived from the process, so two QuickShare instances on the same
     * host (e.g. two emulators) do not fight over the same socket. Set
     * `-Dquickshare.net.offset=N` to force a distinct offset.
     */
    val netOffset: Int =
        Integer.getInteger("quickshare.net.offset", 0)

    /** Control port for this instance: default unless an offset is requested. */
    fun controlPort(): Int = com.quickshare.core.protocol.Protocol.DEFAULT_CONTROL_PORT + netOffset

    /** Discovery port for this instance. */
    fun discoveryPort(): Int = com.quickshare.core.protocol.Protocol.DISCOVERY_PORT + netOffset

    /**
     * Best-effort default display name.
     *
     * On Android the caller passes in `Build.MODEL`; here we fall back to the host name so the
     * core module stays platform-independent and testable.
     */
    fun defaultDeviceName(modelHint: String? = null): String {
        val hint = modelHint?.trim()
        if (!hint.isNullOrEmpty()) return hint.take(com.quickshare.core.protocol.Protocol.MAX_DEVICE_NAME_LENGTH)
        return try {
            InetAddress.getLocalHost().hostName
                ?.takeIf { it.isNotBlank() }
                ?.take(com.quickshare.core.protocol.Protocol.MAX_DEVICE_NAME_LENGTH)
                ?: "QuickShare device"
        } catch (e: Exception) {
            Logs.w(TAG, "Could not resolve a host name for the default device name", e)
            "QuickShare device"
        }
    }

    /**
     * A short, stable hash of an interface set, used to detect "the network changed" without
     * needing a connectivity callback. A change means every cached peer is stale.
     */
    fun networkSignature(interfaces: List<InterfaceAddressInfo>): String =
        interfaces.map { it.address }.sorted().joinToString("|").ifEmpty { "offline" }
}
