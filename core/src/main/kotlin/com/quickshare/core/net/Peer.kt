package com.quickshare.core.net

/**
 * A device seen on the local network.
 *
 * [lastSeenMs] is updated by every discovery beacon; the peer list prunes anything older than
 * [com.quickshare.core.protocol.Protocol.PEER_TTL_MS], which is how a device that goes to sleep
 * (rather than announcing a goodbye) disappears from the UI.
 */
data class Peer(
    val deviceId: String,
    val deviceName: String,
    val address: String,
    val controlPort: Int,
    val protocolVersion: Int,
    val appVersion: String = "",
    val groupCode: String = "",
    val lastSeenMs: Long = 0L,
) {
    /** Stable key for de-duplication across beacons. */
    val key: String get() = deviceId

    val isCompatible: Boolean
        get() = protocolVersion == com.quickshare.core.protocol.Protocol.VERSION

    fun seenAt(now: Long): Peer = copy(lastSeenMs = now)

    fun isExpired(now: Long, ttlMs: Long = com.quickshare.core.protocol.Protocol.PEER_TTL_MS): Boolean =
        lastSeenMs > 0 && now - lastSeenMs > ttlMs

    override fun toString(): String = "$deviceName ($address:$controlPort)"
}
