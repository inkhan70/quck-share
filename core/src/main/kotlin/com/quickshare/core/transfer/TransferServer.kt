package com.quickshare.core.transfer

import com.quickshare.core.protocol.ErrorMessage
import com.quickshare.core.protocol.FileDigest
import com.quickshare.core.protocol.FrameCodec
import com.quickshare.core.protocol.Hello
import com.quickshare.core.protocol.HelloAck
import com.quickshare.core.protocol.Message
import com.quickshare.core.protocol.Offer
import com.quickshare.core.protocol.OfferResponse
import com.quickshare.core.protocol.PairRequest
import com.quickshare.core.protocol.PairResponse
import com.quickshare.core.protocol.Protocol
import com.quickshare.core.protocol.ProtocolException
import com.quickshare.core.protocol.TransferAck
import com.quickshare.core.protocol.TransferComplete
import com.quickshare.core.util.Checksums
import com.quickshare.core.util.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest

/**
 * The receiving half of a transfer.
 *
 * Security model, in order of enforcement:
 * 1. **Reachability** - the app only listens while the user has receiving enabled.
 * 2. **Same-network check** - a connection from outside the local subnet is refused before any
 *    frame is parsed, so a random internet host that finds the port gets nothing.
 * 3. **Explicit pairing** - the user confirms a code shown on both screens; nothing is written to
 *    disk before that confirmation.
 * 4. **Per-transfer acceptance** - the user sees the file list and total size and accepts again.
 * 5. **Filename sanitization** - every name is reduced to a single safe segment
 *    ([com.quickshare.core.util.SafePaths]), which is what makes path traversal impossible.
 * 6. **Checksum verification** - the sender's digests are compared against digests computed from
 *    the bytes actually written.
 */
class TransferServer(
    private val deviceId: String,
    private val deviceName: String,
    private val appVersion: String = "",
    private val port: Int = com.quickshare.core.net.DeviceIdentity.controlPort(),
    private val sinkFactory: SinkFactory,
    private val spaceProvider: SpaceProvider,
    private val listener: TransferListener,
    private val maxConcurrentConnections: Int = 4,
    private val acceptTimeoutMs: Int = 15_000,
    /** When set, consulted for every handshake so a rename in Settings takes effect immediately. */
    private val deviceNameProvider: (() -> String)? = null,
) {

    companion object {
        private const val TAG = "TransferServer"
        private const val PROGRESS_INTERVAL_MS = 200L
        private const val IO_BUFFER = 64 * 1024

        /**
         * Compares the sender's digest manifest with what was computed locally.
         *
         * Exposed for testing: verification is the part of the protocol most worth exercising
         * directly, since a silent false-positive here would report corrupt files as intact.
         */
        fun verify(expected: List<FileDigest>, actual: Map<String, String>): VerificationResult {
            val failed = mutableListOf<String>()
            var verified = 0
            for (digest in expected) {
                val computed = actual[digest.name]
                if (computed != null && Checksums.equalHex(digest.sha256, computed)) {
                    verified++
                } else {
                    failed += digest.name
                }
            }
            // A file that was written but not listed in the manifest is also a failure: the two
            // sides disagree about what this transfer contained.
            val unlisted = actual.keys.filter { name -> expected.none { it.name == name } }
            return VerificationResult(
                verified = failed.isEmpty() && unlisted.isEmpty(),
                verifiedFiles = verified,
                failedFiles = failed + unlisted,
            )
        }

        /**
         * Returns a function that turns repeated file names into unique keys, in call order:
         * `a.txt`, `a.txt#2`, `a.txt#3`.
         *
         * The digest manifest is keyed by name, but a user can legitimately send two files with the same
         * name from different folders. Keyed by bare name, the second file overwrote the first one's
         * digest and a perfectly good transfer was reported as a checksum failure. Both sides walk the
         * files in manifest order, so the same occurrence index lines the two lists up.
         */
        internal fun occurrenceKeys(): (String) -> String {
            val seen = HashMap<String, Int>()
            return { name ->
                val n = (seen[name] ?: 0) + 1
                seen[name] = n
                if (n == 1) name else "$name#$n"
            }
        }

        /** Why a connection was refused, as a short machine-readable code. */
        const val CODE_NOT_ACCEPTING = "not_accepting"
        const val CODE_FOREIGN_NETWORK = "foreign_network"
        const val CODE_PROTOCOL = "protocol"
        const val CODE_NO_SPACE = "no_space"
        const val CODE_DESTINATION = "no_destination"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    private var acceptJob: kotlinx.coroutines.Job? = null

    /**
     * Connections currently being handled, from accept until close - including ones still waiting
     * for the user to answer a pairing prompt. Counting only post-offer transfers let any device on the
     * network open unlimited connections (and prompts) before sending an offer.
     */
    private val openConnections = java.util.concurrent.atomic.AtomicInteger(0)

    /** Names of transfers currently being received, so the UI can show them all. */
    private val activeTransfers = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun isRunning(): Boolean = running

    fun boundPort(): Int? = serverSocket?.localPort?.takeIf { it > 0 }

    fun activeTransferCount(): Int = activeTransfers.size

    /**
     * Starts accepting.
     *
     * @param shouldAcceptConnection consulted for every inbound connection. Returning false sends a
     *        refusal and closes. The default refuses, so a server that is started before the user
     *        opts in can never accept anything.
     */
    fun start(shouldAcceptConnection: () -> Boolean = { true }) {
        if (running) {
            Logs.w(TAG, "Server already running")
            return
        }
        running = true
        // Bind before returning. Binding only inside the accept loop meant start() could return while
        // nothing was listening yet, so a client that connected straight away got "connection
        // refused". If this bind fails the accept loop keeps retrying in the background.
        ensureServerSocket()
        acceptJob = scope.launch { acceptLoop(shouldAcceptConnection) }
        Logs.i(TAG, "Receiver started on TCP port $port")
    }

    fun stop() {
        running = false
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Logs.d(TAG, "Server socket close: ${e.message}")
        }
        serverSocket = null
        activeTransfers.clear()
        Logs.i(TAG, "Receiver stopped")
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private suspend fun acceptLoop(shouldAcceptConnection: () -> Boolean) = withContext(Dispatchers.IO) {
        var consecutiveFailures = 0
        while (running && scope.isActive) {
            try {
                val socket = ensureServerSocket()
                if (socket == null) {
                    // Could not bind (port busy, no network yet). Retry rather than leaving the loop:
                    // returning here left the receiver permanently deaf while still reporting running.
                    kotlinx.coroutines.delay(1000)
                    continue
                }

                val client = try {
                    socket.accept()
                } catch (e: SocketTimeoutException) {
                    continue // soTimeout exists so stop() is honoured promptly; just poll again.
                }
                consecutiveFailures = 0

                if (openConnections.get() >= maxConcurrentConnections) {
                    Logs.w(TAG, "Refusing connection: $maxConcurrentConnections transfers already active")
                    rejectAsync(client, "busy", "This device is already receiving the maximum number of transfers.")
                    continue
                }

                if (!shouldAcceptConnection()) {
                    Logs.i(TAG, "Refusing connection: receiving is switched off")
                    rejectAsync(client, CODE_NOT_ACCEPTING, "The other device is not accepting files right now.")
                    continue
                }

                openConnections.incrementAndGet()
                scope.launch {
                    try {
                        handleConnection(client)
                    } finally {
                        openConnections.decrementAndGet()
                    }
                }
            } catch (e: SocketException) {
                if (!running) return@withContext
                consecutiveFailures++
                Logs.w(TAG, "Accept failed (attempt $consecutiveFailures)", e)
                serverSocket = null
                kotlinx.coroutines.delay((250L * consecutiveFailures).coerceAtMost(3000L))
            } catch (e: Exception) {
                if (!running) return@withContext
                Logs.w(TAG, "Unexpected accept-loop error", e)
                kotlinx.coroutines.delay(500)
            }
        }
    }

    private fun ensureServerSocket(): ServerSocket? {
        serverSocket?.let { if (!it.isClosed) return it }
        return try {
            val created = ServerSocket().apply {
                reuseAddress = true
                soTimeout = 2000
                bind(InetSocketAddress(port))
            }
            serverSocket = created
            created
        } catch (e: Exception) {
            Logs.e(TAG, "Could not bind receiver to port $port", e)
            null
        }
    }

    /** Sends a best-effort refusal on a socket we are about to close. */
    private fun rejectAsync(socket: Socket, code: String, message: String) {
        scope.launch {
            try {
                socket.soTimeout = 2000
                val out = BufferedOutputStream(socket.getOutputStream(), IO_BUFFER)
                FrameCodec.writeMessage(out, ErrorMessage(code = code, message = message))
            } catch (e: Exception) {
                Logs.d(TAG, "Could not deliver refusal: ${e.message}")
            } finally {
                closeQuietly(socket)
            }
        }
    }

    /** What is known about a transfer so far, so a failure can be recorded with full context. */
    private class Context {
        var peerName: String = "Unknown device"
        var peerAddress: String = ""
        var offer: Offer? = null
        var startedAt: Long = System.currentTimeMillis()
    }

    private suspend fun handleConnection(socket: Socket) {
        var transferId: String? = null
        val ctx = Context()
        val openedSinks = mutableListOf<FileSink>()
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = acceptTimeoutMs

            val remoteAddress = socket.inetAddress?.hostAddress ?: "unknown"
            ctx.peerAddress = remoteAddress

            // --- Gate 1: the peer must be on this network. Checked before parsing anything.
            if (!isFromLocalNetwork(remoteAddress)) {
                Logs.w(TAG, "Refusing connection from $remoteAddress: outside the local network")
                rejectAsync(
                    socket, CODE_FOREIGN_NETWORK,
                    "Connections are only accepted from devices on the same Wi-Fi network.",
                )
                return
            }

            val rawInput = socket.getInputStream()
            val input = BufferedInputStream(rawInput, IO_BUFFER)
            val rawOutput = socket.getOutputStream()
            val output = BufferedOutputStream(rawOutput, IO_BUFFER)

            // --- Gate 2: magic prefix. Anything else is not QuickShare.
            val magic = ByteArray(Protocol.HANDSHAKE_MAGIC.length)
            if (FrameCodec.readFully(rawInput, magic) < magic.size ||
                String(magic, Charsets.US_ASCII) != Protocol.HANDSHAKE_MAGIC
            ) {
                Logs.w(TAG, "Refusing connection from $remoteAddress: bad handshake magic")
                rejectAsync(socket, CODE_PROTOCOL, "This does not look like a QuickShare connection.")
                return
            }

            // --- Hello / HelloAck
            val hello = readExpect<Hello>(input, Hello.TYPE)
            if (hello.protocolVersion != Protocol.VERSION) {
                FrameCodec.writeMessage(
                    output,
                    ErrorMessage(
                        code = "version_mismatch",
                        message = "Version mismatch: this device speaks protocol v${Protocol.VERSION}, " +
                            "the other speaks v${hello.protocolVersion}. Update both apps.",
                    ),
                )
                return
            }

            FrameCodec.writeMessage(
                output,
                HelloAck(
                    protocolVersion = Protocol.VERSION,
                    deviceName = (deviceNameProvider?.invoke() ?: deviceName).take(Protocol.MAX_DEVICE_NAME_LENGTH),
                    deviceId = deviceId,
                    controlPort = boundPort() ?: port,
                    accepted = true,
                    reason = null,
                ),
            )

            // --- Gate 3: explicit pairing confirmation.
            val pairRequest = readExpect<PairRequest>(input, PairRequest.TYPE)
            val pairingAccepted = listener.onPairingRequested(pairRequest.deviceName, pairRequest.code)
            FrameCodec.writeMessage(
                output,
                PairResponse(
                    accepted = pairingAccepted,
                    reason = if (pairingAccepted) null else "Pairing was declined on this device.",
                ),
            )
            if (!pairingAccepted) {
                Logs.i(TAG, "Pairing with ${pairRequest.deviceName} declined by the user")
                return
            }

            // --- Offer
            val offer = readExpect<Offer>(input, Offer.TYPE)
            transferId = offer.transferId
            ctx.peerName = pairRequest.deviceName
            ctx.offer = offer
            ctx.startedAt = System.currentTimeMillis()
            activeTransfers[offer.transferId] = true

            val summary = OfferSummary(
                transferId = offer.transferId,
                peerName = pairRequest.deviceName,
                files = offer.files.map { TransferFileInfo(it.name, it.relativePath, it.size, it.mime) },
                totalBytes = offer.totalBytes,
                message = offer.message,
            )

            // --- Gate 4: storage. Checked before asking the user, so we never ask for a transfer
            // that cannot physically land.
            val destination = listener.onDestinationRequired()
            if (destination.isNullOrEmpty()) {
                FrameCodec.writeMessage(
                    output,
                    OfferResponse(
                        transferId = offer.transferId,
                        accepted = false,
                        reason = "No destination folder was chosen on this device.",
                    ),
                )
                Logs.i(TAG, "Offer for ${offer.transferId} refused: no destination")
                return
            }

            val available = spaceProvider.usableBytes(destination)
            val spaceError = StorageGuard.check(offer.totalBytes, available)
            if (spaceError != null) {
                FrameCodec.writeMessage(
                    output,
                    OfferResponse(transferId = offer.transferId, accepted = false, reason = spaceError),
                )
                Logs.w(TAG, "Offer for ${offer.transferId} refused: $spaceError")
                return
            }

            // --- Gate 5: the user accepts the specific file list.
            val offerAccepted = listener.onOfferReceived(pairRequest.deviceName, summary)
            FrameCodec.writeMessage(
                output,
                OfferResponse(
                    transferId = offer.transferId,
                    accepted = offerAccepted,
                    reason = if (offerAccepted) null else "The transfer was declined on this device.",
                ),
            )
            if (!offerAccepted) {
                Logs.i(TAG, "Offer for ${offer.transferId} declined by the user")
                return
            }

            // --- Bytes
            socket.soTimeout = Protocol.SOCKET_READ_TIMEOUT_MS
            val tracker = ProgressTracker()
            val actualDigests = LinkedHashMap<String, String>()
            val actualKey = occurrenceKeys()
            var transferred = 0L
            var lastReport = 0L

            val buffer = ByteArray(IO_BUFFER)
            val startedAt = System.currentTimeMillis()

            offer.files.forEachIndexed { index, file ->
                val info = TransferFileInfo(file.name, file.relativePath, file.size, file.mime)
                val sink = try {
                    sinkFactory.create(info)
                } catch (e: TransferException) {
                    throw e
                } catch (e: Exception) {
                    throw TransferException(
                        "Could not create '${file.name}' at the destination: ${e.message}",
                        e,
                        code = CODE_DESTINATION,
                    )
                }
                openedSinks += sink

                var fileTransferred = 0L
                var digested: String? = null

                try {
                    sink.openStream().use { rawOut ->
                        val hashing = Checksums.HashingOutputStream(rawOut) { hex -> digested = hex }
                        val fileOut = BufferedOutputStream(hashing, IO_BUFFER)

                        var remaining = file.size
                        while (remaining > 0) {
                            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                            val read = input.read(buffer, 0, toRead)
                            if (read < 0) {
                                throw ProtocolException(
                                    "The sender disconnected while '${file.name}' was still " +
                                        "${remaining} bytes short.",
                                )
                            }
                            if (read == 0) continue

                            fileOut.write(buffer, 0, read)
                            remaining -= read
                            fileTransferred += read
                            transferred += read

                            val now = System.currentTimeMillis()
                            if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                                lastReport = now
                                listener.onProgress(
                                    tracker.snapshot(
                                        transferId = offer.transferId,
                                        direction = TransferDirection.RECEIVE,
                                        fileIndex = index,
                                        fileCount = offer.files.size,
                                        fileName = file.name,
                                        bytesTransferred = transferred,
                                        totalBytes = offer.totalBytes,
                                        fileBytesTransferred = fileTransferred,
                                        fileBytesTotal = file.size,
                                    ),
                                )
                            }
                        }
                        fileOut.flush()
                        fileOut.close() // closes the hashing wrapper, which publishes the digest
                    }
                } catch (e: Exception) {
                    // A partial file must never be left looking complete.
                    try {
                        sink.delete()
                    } catch (ignored: Exception) {
                        Logs.d(TAG, "Could not delete partial file ${info.name}")
                    }
                    throw e
                }

                actualDigests[actualKey(file.name)] = digested ?: ""
                listener.onFileCompleted(index, offer.files.size, file.name)
                listener.onProgress(
                    tracker.snapshot(
                        transferId = offer.transferId,
                        direction = TransferDirection.RECEIVE,
                        fileIndex = index,
                        fileCount = offer.files.size,
                        fileName = file.name,
                        bytesTransferred = transferred,
                        totalBytes = offer.totalBytes,
                        fileBytesTransferred = fileTransferred,
                        fileBytesTotal = file.size,
                    ),
                )
            }

            // --- Verification
            val complete = readExpect<TransferComplete>(input, TransferComplete.TYPE)
            val expectedKey = occurrenceKeys()
            val expected = complete.digests.map { FileDigest(expectedKey(it.name), it.sha256) }
            val verification = verify(expected, actualDigests)

            FrameCodec.writeMessage(
                output,
                TransferAck(
                    transferId = offer.transferId,
                    ok = verification.verified,
                    verifiedFiles = verification.verifiedFiles,
                    failedFiles = verification.failedFiles,
                    message = if (verification.verified) {
                        null
                    } else {
                        "Checksum mismatch on: ${verification.failedFiles.joinToString(", ")}"
                    },
                ),
            )

            openedSinks.forEach { sink ->
                try {
                    sink.onSuccess(verification.verified)
                } catch (e: Exception) {
                    Logs.w(TAG, "Post-processing failed for ${sink.name}", e)
                }
            }

            listener.onFinished(
                TransferRecord(
                    transferId = offer.transferId,
                    direction = TransferDirection.RECEIVE,
                    peerName = pairRequest.deviceName,
                    peerAddress = remoteAddress,
                    fileNames = offer.files.map { it.name },
                    fileCount = offer.files.size,
                    totalBytes = offer.totalBytes,
                    startedAtMs = startedAt,
                    finishedAtMs = System.currentTimeMillis(),
                    status = if (verification.verified) TransferStatus.SUCCESS else TransferStatus.VERIFICATION_FAILED,
                    verified = verification.verified,
                    verifiedFileCount = verification.verifiedFiles,
                    failedFiles = verification.failedFiles,
                    errorMessage = if (verification.verified) null else {
                        "Checksum mismatch on: ${verification.failedFiles.joinToString(", ")}"
                    },
                    saveLocation = destination,
                ),
            )
            Logs.i(TAG, "Receive ${offer.transferId} finished: verified=${verification.verified}")
        } catch (e: EOFException) {
            Logs.i(TAG, "Peer disconnected during transfer $transferId")
            reportFailure(transferId, openedSinks, TransferStatus.INTERRUPTED, "The other device disconnected before the transfer finished.", ctx)
        } catch (e: TransferCancelledException) {
            reportFailure(transferId, openedSinks, TransferStatus.CANCELLED, e.message, ctx)
        } catch (e: ProtocolException) {
            Logs.w(TAG, "Protocol error on ${transferId ?: "incoming connection"}", e)
            reportFailure(
                transferId, openedSinks, TransferStatus.FAILED,
                "The other device sent an unexpected response. ${e.message ?: ""}".trim(), ctx,
            )
        } catch (e: SocketTimeoutException) {
            reportFailure(
                transferId, openedSinks, TransferStatus.INTERRUPTED,
                "The transfer stalled and timed out. Move both devices closer to the router and try again.", ctx,
            )
        } catch (e: SocketException) {
            reportFailure(
                transferId, openedSinks, TransferStatus.INTERRUPTED,
                "The network connection dropped: ${e.message}", ctx,
            )
        } catch (e: TransferException) {
            reportFailure(transferId, openedSinks, TransferStatus.FAILED, e.message, ctx)
        } catch (e: Exception) {
            Logs.e(TAG, "Unexpected receiver error on $transferId", e)
            reportFailure(
                transferId, openedSinks, TransferStatus.FAILED,
                "Receive failed: ${e.message ?: e::class.java.simpleName}", ctx,
            )
        } finally {
            transferId?.let { activeTransfers.remove(it) }
            closeQuietly(socket)
        }
    }

    private fun reportFailure(
        transferId: String?,
        sinks: List<FileSink>,
        status: TransferStatus,
        error: String?,
        ctx: Context,
    ) {
        if (transferId == null) return
        // Files that were being written when the connection died are removed: a truncated file that
        // looks like a finished one is worse than no file at all.
        if (status != TransferStatus.SUCCESS) {
            sinks.forEach { sink ->
                try {
                    sink.delete()
                } catch (e: Exception) {
                    Logs.d(TAG, "Could not remove partial ${sink.name}: ${e.message}")
                }
            }
        }
        listener.onFinished(
            TransferRecord(
                transferId = transferId,
                direction = TransferDirection.RECEIVE,
                peerName = ctx.peerName,
                peerAddress = ctx.peerAddress,
                fileNames = ctx.offer?.files?.map { it.name }.orEmpty(),
                fileCount = ctx.offer?.files?.size ?: 0,
                totalBytes = ctx.offer?.totalBytes ?: 0L,
                startedAtMs = ctx.startedAt,
                finishedAtMs = System.currentTimeMillis(),
                status = status,
                errorMessage = error,
            ),
        )
    }

    private inline fun <reified T : Message> readExpect(input: InputStream, expected: String): T {
        val message = FrameCodec.readMessage(input)
        if (message is ErrorMessage) {
            throw TransferException(message.message, code = message.code)
        }
        if (message !is T) {
            throw ProtocolException("Expected a '$expected' frame but received '${message.type}'")
        }
        return message
    }

    /**
     * True when [address] is on one of this device's own subnets.
     *
     * Loopback is allowed so that two instances on the same host (the automated end-to-end test, or
     * two emulators on one machine) can talk to each other.
     */
    private fun isFromLocalNetwork(address: String): Boolean {
        if (address == "127.0.0.1" || address == "localhost") return true
        val interfaces = com.quickshare.core.net.NetworkUtils.activeIpv4Interfaces()
        if (interfaces.isEmpty()) {
            // No usable interface information: allow, because a receiver on a device with no
            // enumerable interface is almost certainly on a hotspot, and pairing still gates access.
            return true
        }
        // Use each interface's real prefix: assuming /24 refused legitimate peers on /23, /22 and /16
        // networks (common on routers, schools and offices).
        return interfaces.any {
            com.quickshare.core.net.NetworkUtils.sameSubnet(it.address, address, it.prefixLength)
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (e: Exception) {
            Logs.d(TAG, "Socket close failed: ${e.message}")
        }
    }
}
