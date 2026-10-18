package com.quickshare.core.net

import com.quickshare.core.util.Logs

/**
 * Thread-safe, deduplicating store of discovered peers.
 *
 * Peers are keyed by device id so repeated beacons refresh one entry rather than piling up. The
 * store also records the local interface signature at the time of the last sweep, so the caller can
 * drop every entry when the device changes Wi-Fi networks.
 */
class PeerRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = com.quickshare.core.protocol.Protocol.PEER_TTL_MS,
) {

    companion object {
        private const val TAG = "PeerRegistry"

        /**
         * Sentinel "do not apply the TTL" timestamp.
         *
         * A negative value can never be a real `System.currentTimeMillis()`, so it is unambiguous
         * as a flag while keeping the read methods on one signature.
         */
        const val NO_EXPIRY_CHECK: Long = -1L
    }

    private val lock = Any()
    private val peers = LinkedHashMap<String, Peer>()

    /**
     * Keys of peers added by hand (manual IP or QR). They never send a discovery beacon, so the TTL
     * would evict them about twelve seconds after the user added them - long before they could be
     * tapped. They stay until removed or the registry is cleared.
     */
    private val pinned = HashSet<String>()

    /** Adds or refreshes [peer]. Returns true when this was a previously unseen device. */
    fun add(peer: Peer): Boolean {
        val refreshed = peer.seenAt(clock())
        return synchronized(lock) {
            val isNew = !peers.containsKey(refreshed.key)
            peers[refreshed.key] = refreshed
            isNew
        }
    }

    /**
     * Adds or refreshes [peer] using an explicit timestamp.
     *
     * The timestamp is a parameter (rather than always reading the clock) so the TTL behaviour is
     * directly testable without sleeping, and so a caller that has already read the clock does not
     * read it a second time and get a different answer.
     *
     * @return true when this was a previously unseen device.
     */
    fun upsert(peer: Peer, nowMs: Long = clock()): Boolean {
        val refreshed = peer.seenAt(nowMs)
        return synchronized(lock) {
            val isNew = !peers.containsKey(refreshed.key)
            peers[refreshed.key] = refreshed
            isNew
        }
    }

    fun remove(deviceId: String) {
        synchronized(lock) {
            peers.remove(deviceId)
            pinned.remove(deviceId)
        }
    }

    /**
     * Returns [deviceId], or null when it is absent **or already past its TTL**.
     *
     * Expiry is applied on read for the TTL-aware callers: handing a dead address to the UI is what
     * produces a "could not connect" the user cannot explain, so this fails closed. Passing
     * [NO_EXPIRY_CHECK] skips the check, which is what the plain `get(id)` form does - useful for
     * inspecting the registry itself rather than deciding whether a peer is reachable.
     */
    fun get(deviceId: String, nowMs: Long = NO_EXPIRY_CHECK): Peer? = synchronized(lock) {
        val peer = peers[deviceId]
        when {
            peer == null -> null
            isStale(peer, nowMs) -> {
                peers.remove(deviceId)
                null
            }
            else -> peer
        }
    }

    /**
     * All known peers, in insertion order.
     *
     * With [NO_EXPIRY_CHECK] (the default) entries are listed regardless of age, because the
     * discovery sweeper is the component responsible for eviction and it runs on its own cadence.
     * Passing a timestamp prunes first, which is what a caller that needs "who is reachable right
     * now" should do.
     */
    fun all(nowMs: Long = NO_EXPIRY_CHECK): List<Peer> {
        if (nowMs != NO_EXPIRY_CHECK) sweep(nowMs)
        return synchronized(lock) { peers.values.toList() }
    }

    /** Number of known peers; see [all] for the [NO_EXPIRY_CHECK] semantics. */
    fun size(nowMs: Long = NO_EXPIRY_CHECK): Int {
        if (nowMs != NO_EXPIRY_CHECK) sweep(nowMs)
        return synchronized(lock) { peers.size }
    }

    fun clear() {
        synchronized(lock) {
            peers.clear()
            pinned.clear()
        }
    }

    /**
     * Drops entries that have not been seen within the TTL.
     *
     * @return the peers that were removed, so the UI can be told exactly what disappeared.
     */
    fun sweep(nowMs: Long = clock()): List<Peer> {
        val removed = mutableListOf<Peer>()
        synchronized(lock) {
            val iterator = peers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in pinned && entry.value.isExpired(nowMs, ttlMs)) {
                    removed += entry.value
                    iterator.remove()
                }
            }
        }
        if (removed.isNotEmpty()) {
            Logs.d(TAG, "Swept ${removed.size} stale peer(s)")
        }
        return removed
    }

    /** True when the TTL applies to this read and [peer] has outlived it. */
    private fun isStale(peer: Peer, nowMs: Long): Boolean =
        nowMs != NO_EXPIRY_CHECK && peer.key !in pinned && peer.isExpired(nowMs, ttlMs)

    /**
     * Replaces the identity of a peer that was reached through the manual-IP fallback, where
     * discovery never ran and no beacon supplied a real device id.
     */
    fun addManual(
        address: String,
        port: Int,
        deviceName: String,
        deviceId: String,
        protocolVersion: Int,
        appVersion: String = "",
        groupCode: String = "",
    ): Peer {
        val peer = Peer(
            deviceId = deviceId,
            deviceName = deviceName,
            address = address,
            controlPort = port,
            protocolVersion = protocolVersion,
            appVersion = appVersion,
            groupCode = groupCode,
            lastSeenMs = clock(),
        )
        add(peer)
        synchronized(lock) { pinned += peer.key }
        return peer
    }
}
