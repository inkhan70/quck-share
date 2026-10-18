package com.quickshare.core.net

import com.quickshare.core.protocol.Protocol
import com.quickshare.core.util.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * Local-network peer discovery over UDP broadcast.
 *
 * Every device both announces itself and listens for announcements, so no device has to be
 * "the server". Broadcast is used rather than multicast because broadcast survives the cheap
 * access points and phone hotspots that frequently drop multicast.
 *
 * Beacon payload (UTF-8, single line, fields separated by `|`):
 * ```
 * QSH-DISCOVER/1|<deviceId>|<deviceName>|<controlPort>|<protocolVersion>|<appVersion>|<groupCode>
 * ```
 * A plain-text beacon keeps discovery dependency-free and trivially debuggable; anything that does
 * not start with the magic string is ignored, so unrelated UDP traffic on the same port is
 * harmless.
 */
class DiscoveryService(
    private val deviceId: String,
    private val deviceName: () -> String,
    private val controlPort: () -> Int,
    private val appVersion: String = "",
    private val port: Int = DeviceIdentity.discoveryPort(),
    private val announceIntervalMs: Long = Protocol.DISCOVERY_INTERVAL_MS,
    private val registry: PeerRegistry = PeerRegistry(),
    private val selfAddresses: () -> List<String> = { NetworkUtils.activeIpv4Interfaces().map { it.address } },
) {

    companion object {
        private const val TAG = "Discovery"
        private const val FIELD_SEPARATOR = "|"
        private const val MAX_BEACON_BYTES = 1024

        /** Encodes a beacon. Exposed so the format is unit-testable without sockets. */
        fun encodeBeacon(
            deviceId: String,
            deviceName: String,
            controlPort: Int,
            protocolVersion: Int = Protocol.VERSION,
            appVersion: String = "",
            groupCode: String = "",
        ): String = listOf(
            Protocol.DISCOVERY_MAGIC,
            sanitizeField(deviceId),
            sanitizeField(deviceName),
            controlPort.toString(),
            protocolVersion.toString(),
            sanitizeField(appVersion),
            sanitizeField(groupCode),
        ).joinToString(FIELD_SEPARATOR)

        /**
         * Parses a beacon.
         *
         * @return the [Peer], or null when the datagram is not ours, is truncated, or carries an
         *         unusable port. Returning null (rather than throwing) is deliberate: discovery runs
         *         on a socket that any host on the network can send to.
         */
        fun parseBeacon(raw: String, senderAddress: String): Peer? {
            if (raw.isBlank()) return null
            val fields = raw.trim().split(FIELD_SEPARATOR)
            if (fields.size < 5) return null
            if (fields[0] != Protocol.DISCOVERY_MAGIC) return null

            val deviceId = fields[1].takeIf { it.isNotBlank() } ?: return null
            val deviceName = fields[2].take(Protocol.MAX_DEVICE_NAME_LENGTH).ifBlank { "QuickShare device" }
            val controlPort = fields[3].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val protocolVersion = fields[4].toIntOrNull() ?: return null
            val appVersion = fields.getOrNull(5)?.take(32) ?: ""
            val groupCode = fields.getOrNull(6)?.take(64) ?: ""

            return Peer(
                deviceId = deviceId,
                deviceName = deviceName,
                address = senderAddress,
                controlPort = controlPort,
                protocolVersion = protocolVersion,
                appVersion = appVersion,
                groupCode = groupCode,
            )
        }

        private fun sanitizeField(value: String): String =
            value.replace(FIELD_SEPARATOR, "/").replace("\n", " ").replace("\r", " ").take(64)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: DatagramSocket? = null
    private var listenJob: Job? = null
    private var announceJob: Job? = null
    private var sweepJob: Job? = null

    /** Snapshot of currently visible peers. */
    fun peers(): List<Peer> = registry.all()

    fun peerCount(): Int = registry.size()

    fun findPeer(deviceId: String): Peer? = registry.get(deviceId)

    /**
     * Starts listening and announcing.
     *
     * @param onPeerDiscovered invoked for every beacon that is new or refreshed, so the UI can
     *        update without polling.
     * @param onPeerLost invoked for every peer whose TTL expired.
     */
    fun start(
        onPeerDiscovered: (Peer) -> Unit = {},
        onPeerLost: (List<Peer>) -> Unit = {},
    ) {
        if (listenJob?.isActive == true) {
            Logs.w(TAG, "Discovery already running")
            return
        }
        listenJob = scope.launch { listenLoop(onPeerDiscovered) }
        announceJob = scope.launch { announceLoop() }
        sweepJob = scope.launch { sweepLoop(onPeerLost) }
        Logs.i(TAG, "Discovery started on UDP port $port as '$deviceId'")
    }

    fun stop() {
        listenJob?.cancel()
        announceJob?.cancel()
        sweepJob?.cancel()
        listenJob = null
        announceJob = null
        sweepJob = null
        try {
            socket?.close()
        } catch (e: Exception) {
            Logs.d(TAG, "Socket close during stop: ${e.message}")
        }
        socket = null
        registry.clear()
        Logs.i(TAG, "Discovery stopped")
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private suspend fun listenLoop(onPeerDiscovered: (Peer) -> Unit) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(MAX_BEACON_BYTES)
        var failures = 0
        while (scope.isActive) {
            try {
                val activeSocket = ensureSocket()
                if (activeSocket == null) {
                    // The bind can fail transiently (port still held after a Wi-Fi change, no network
                    // yet at launch). Retry instead of leaving the listen loop - returning here used
                    // to end discovery for good, even though the comment above promises recovery.
                    delay(1000)
                    continue
                }
                val packet = DatagramPacket(buffer, buffer.size)
                activeSocket.receive(packet)
                failures = 0

                val raw = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                val sender = packet.address?.hostAddress ?: continue
                val peer = parseBeacon(raw, sender) ?: continue

                // Ignore our own beacon: a device can hear itself on some drivers.
                if (peer.deviceId == deviceId) continue
                if (sender in selfAddresses()) continue

                val isNew = registry.add(peer)
                onPeerDiscovered(registry.get(peer.deviceId) ?: peer)
                if (isNew) Logs.i(TAG, "Discovered $peer")
            } catch (e: SocketTimeoutException) {
                // Expected: the socket timeout exists so stop() is honoured promptly.
            } catch (e: SocketException) {
                if (!scope.isActive) return@withContext
                failures++
                Logs.w(TAG, "Discovery socket error (attempt $failures)", e)
                socket = null
                if (failures > 10) {
                    Logs.e(TAG, "Discovery giving up after repeated socket failures")
                    delay(5000)
                    failures = 0
                } else {
                    delay(500L * failures)
                }
            } catch (e: Exception) {
                if (!scope.isActive) return@withContext
                Logs.w(TAG, "Unexpected discovery error", e)
                delay(500)
            }
        }
    }

    /**
     * Creates or re-creates the listening socket.
     *
     * `reuseAddress` matters here: after a Wi-Fi change the old socket may linger in the kernel, and
     * without it the rebind fails and discovery never recovers.
     */
    private fun ensureSocket(): DatagramSocket? {
        socket?.let { if (!it.isClosed) return it }
        return try {
            val created = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 2000
                bind(InetSocketAddress(port))
            }
            socket = created
            created
        } catch (e: Exception) {
            Logs.e(TAG, "Could not bind discovery socket on port $port", e)
            null
        }
    }

    private suspend fun announceLoop() {
        while (scope.isActive) {
            try {
                announceOnce()
            } catch (e: Exception) {
                Logs.w(TAG, "Announce failed", e)
            }
            delay(announceIntervalMs)
        }
    }

    /** Sends one broadcast beacon on every active interface. */
    fun announceOnce() {
        val activeSocket = socket ?: ensureSocket() ?: return
        val beacon = encodeBeacon(
            deviceId = deviceId,
            deviceName = deviceName(),
            controlPort = controlPort(),
            appVersion = appVersion,
            groupCode = NetworkUtils.groupCode(NetworkUtils.primaryLocalAddress()),
        )
        val bytes = beacon.toByteArray(StandardCharsets.UTF_8)

        // A record built without a derived broadcast (e.g. a hand-constructed one) contributes
        // nothing here rather than an empty target string, and the limited-broadcast fallback below
        // still covers that case.
        val targets = NetworkUtils.activeIpv4Interfaces()
            .map { it.broadcastAddress }
            .filter { it.isNotBlank() }
            .distinct()
        // 255.255.255.255 is the last-resort target: some drivers only deliver limited broadcast.
        for (target in targets + "255.255.255.255") {
            try {
                val address = InetAddress.getByName(target)
                activeSocket.send(DatagramPacket(bytes, bytes.size, address, port))
            } catch (e: Exception) {
                Logs.d(TAG, "Beacon to $target failed: ${e.message}")
            }
        }
    }

    private suspend fun sweepLoop(onPeerLost: (List<Peer>) -> Unit) {
        while (scope.isActive) {
            delay(2000)
            val removed = registry.sweep()
            if (removed.isNotEmpty()) {
                Logs.i(TAG, "Lost ${removed.size} peer(s)")
                onPeerLost(removed)
            }
        }
    }
}
