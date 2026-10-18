package com.quickshare.core.transfer

import com.quickshare.core.protocol.ErrorMessage
import com.quickshare.core.protocol.FrameCodec
import com.quickshare.core.protocol.Hello
import com.quickshare.core.protocol.HelloAck
import com.quickshare.core.protocol.Message
import com.quickshare.core.protocol.Offer
import com.quickshare.core.protocol.OfferResponse
import com.quickshare.core.protocol.OfferedFile
import com.quickshare.core.protocol.PairRequest
import com.quickshare.core.protocol.PairResponse
import com.quickshare.core.protocol.Protocol
import com.quickshare.core.protocol.ProtocolException
import com.quickshare.core.protocol.TransferComplete
import com.quickshare.core.protocol.TransferAck
import com.quickshare.core.protocol.FileDigest
import com.quickshare.core.util.Checksums
import com.quickshare.core.util.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * The sending half of a transfer.
 *
 * Runs on a single TCP connection: handshake, pairing, offer, raw file bytes, then a digest
 * manifest. Files are streamed in fixed-size chunks, so peak memory is one buffer regardless of
 * file size.
 */
class TransferClient(
    private val deviceId: String,
    private val deviceName: String,
    private val localControlPort: Int,
    private val appVersion: String = "",
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = Protocol.SOCKET_READ_TIMEOUT_MS,
    /**
     * How long to wait for the person on the other device to answer the pairing and offer prompts.
     * The receiving app waits up to 90 s for them; a 30 s socket timeout here made the sender give up
     * ("stopped responding") while the other user was still reading the dialog.
     */
    private val userDecisionTimeoutMs: Int = 100_000,
    /** When set, consulted at send time so a rename in Settings takes effect immediately. */
    private val deviceNameProvider: (() -> String)? = null,
) {

    companion object {
        private const val TAG = "TransferClient"
        private const val PROGRESS_INTERVAL_MS = 200L
    }

    /**
     * Sends [files] to [peerAddress]:[peerPort].
     *
     * Suspends until the transfer reaches a terminal state. Cancellation is cooperative via
     * [isCancelled]; the returned [TransferRecord] then carries
     * [TransferStatus.CANCELLED] rather than an exception.
     *
     * @param pairingCode shown on both devices; the receiver must confirm it.
     */
    suspend fun send(
        peerAddress: String,
        peerPort: Int,
        peerName: String,
        files: List<FileSource>,
        transferId: String,
        pairingCode: String,
        listener: TransferListener = object : TransferListener {},
        isCancelled: () -> Boolean = { false },
    ): TransferRecord = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val deviceName = deviceNameProvider?.invoke() ?: this@TransferClient.deviceName
        val totalBytes = files.sumOf { it.size }
        val record = TransferRecord(
            transferId = transferId,
            direction = TransferDirection.SEND,
            peerName = peerName,
            peerAddress = peerAddress,
            fileNames = files.map { it.name },
            fileCount = files.size,
            totalBytes = totalBytes,
            startedAtMs = startedAt,
        )

        var socket: Socket? = null
        try {
            require(files.isNotEmpty()) { "Nothing to send" }
            socket = Socket().apply {
                tcpNoDelay = true
                soTimeout = readTimeoutMs
                // A generous send buffer keeps throughput up on high-latency Wi-Fi without
                // affecting the app's own memory: this is kernel memory, not heap.
                sendBufferSize = 256 * 1024
                connect(InetSocketAddress(peerAddress, peerPort), connectTimeoutMs)
            }
            Logs.i(TAG, "Connected to $peerName at $peerAddress:$peerPort for $transferId")

            val input = BufferedInputStream(socket.getInputStream(), Protocol.IO_BUFFER_SIZE)
            val output = BufferedOutputStream(socket.getOutputStream(), Protocol.IO_BUFFER_SIZE)

            // --- 1. Handshake -------------------------------------------------------------
            socket.getOutputStream().write(Protocol.HANDSHAKE_MAGIC.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()

            FrameCodec.writeMessage(
                output,
                Hello(
                    protocolVersion = Protocol.VERSION,
                    deviceName = deviceName.take(Protocol.MAX_DEVICE_NAME_LENGTH),
                    deviceId = deviceId,
                    controlPort = localControlPort,
                    groupCode = com.quickshare.core.net.NetworkUtils.groupCode(peerAddress),
                    appVersion = appVersion,
                ),
            )

            val ack = readExpect<HelloAck>(input, HelloAck.TYPE)
            if (!ack.accepted) {
                throw TransferRejectedException(ack.reason ?: "The other device declined the connection")
            }
            if (ack.protocolVersion != Protocol.VERSION) {
                throw TransferException(
                    "Version mismatch: the other device speaks protocol v${ack.protocolVersion}, " +
                        "this device speaks v${Protocol.VERSION}. Update both apps to the same version.",
                    code = "version_mismatch",
                )
            }

            // --- 2. Pairing ---------------------------------------------------------------
            FrameCodec.writeMessage(
                output,
                PairRequest(code = pairingCode, deviceName = deviceName, deviceId = deviceId),
            )
            // A person is deciding on the other device, so allow far longer than a network stall.
            socket.soTimeout = maxOf(readTimeoutMs, userDecisionTimeoutMs)
            val pairResponse = readExpect<PairResponse>(input, PairResponse.TYPE)
            if (!pairResponse.accepted) {
                return@withContext finish(
                    record, TransferStatus.REJECTED, listener,
                    error = pairResponse.reason ?: "Pairing was declined on the other device",
                )
            }

            // --- 3. Offer -----------------------------------------------------------------
            val offered = files.map { it.toInfo() }
            FrameCodec.writeMessage(
                output,
                Offer(
                    transferId = transferId,
                    files = offered.map {
                        OfferedFile(
                            name = it.name,
                            relativePath = it.relativePath,
                            size = it.size,
                            mime = it.mime,
                        )
                    },
                    totalBytes = totalBytes,
                    message = null,
                ),
            )
            val offerResponse = readExpect<OfferResponse>(input, OfferResponse.TYPE)
            if (!offerResponse.accepted) {
                return@withContext finish(
                    record, TransferStatus.REJECTED, listener,
                    error = offerResponse.reason ?: "The transfer was declined on the other device",
                )
            }

            // Decision made: back to the stall timeout for the byte stream.
            socket.soTimeout = readTimeoutMs

            if (isCancelled()) throw TransferCancelledException()

            // --- 4. Bytes -----------------------------------------------------------------
            val tracker = ProgressTracker()
            val digests = mutableListOf<FileDigest>()
            var transferred = 0L
            var lastReport = 0L

            val buffer = ByteArray(Protocol.IO_BUFFER_SIZE)

            files.forEachIndexed { index, source ->
                if (isCancelled()) throw TransferCancelledException()

                val digest = MessageDigest.getInstance("SHA-256")
                var fileTransferred = 0L

                source.openStream().use { raw ->
                    val fileInput = if (raw is BufferedInputStream) raw else BufferedInputStream(raw, Protocol.IO_BUFFER_SIZE)
                    while (true) {
                        if (isCancelled()) throw TransferCancelledException()
                        // Never send more than the manifest promised. If a file grows while it is
                        // being sent, writing the extra bytes would desynchronise the stream and the
                        // receiver would misread them as the next frame.
                        val wanted = minOf(buffer.size.toLong(), source.size - fileTransferred).toInt()
                        if (wanted <= 0) {
                            if (fileInput.read() != -1) {
                                throw TransferException(
                                    "'${source.name}' changed while it was being sent: it is longer than " +
                                        "the ${source.size} bytes that were offered.",
                                    code = "source_changed",
                                )
                            }
                            break
                        }
                        val read = fileInput.read(buffer, 0, wanted)
                        if (read <= 0) break

                        // The digest covers the exact bytes put on the wire, so it is computed
                        // here rather than by re-reading the file afterwards.
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        fileTransferred += read
                        transferred += read

                        val now = System.currentTimeMillis()
                        if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                            lastReport = now
                            listener.onProgress(
                                tracker.snapshot(
                                    transferId = transferId,
                                    direction = TransferDirection.SEND,
                                    fileIndex = index,
                                    fileCount = files.size,
                                    fileName = source.name,
                                    bytesTransferred = transferred,
                                    totalBytes = totalBytes,
                                    fileBytesTransferred = fileTransferred,
                                    fileBytesTotal = source.size,
                                ),
                            )
                        }
                    }
                }

                if (fileTransferred != source.size) {
                    throw TransferException(
                        "'${source.name}' changed while it was being sent: the manifest said " +
                            "${source.size} bytes but ${fileTransferred} were readable.",
                        code = "source_changed",
                    )
                }

                digests += FileDigest(source.name, Checksums.toHex(digest.digest()))
                listener.onFileCompleted(index, files.size, source.name)
                listener.onProgress(
                    tracker.snapshot(
                        transferId = transferId,
                        direction = TransferDirection.SEND,
                        fileIndex = index,
                        fileCount = files.size,
                        fileName = source.name,
                        bytesTransferred = transferred,
                        totalBytes = totalBytes,
                        fileBytesTransferred = fileTransferred,
                        fileBytesTotal = source.size,
                    ),
                )
            }

            output.flush()

            // --- 5. Verification ----------------------------------------------------------
            FrameCodec.writeMessage(output, TransferComplete(transferId = transferId, digests = digests))

            val verification = readExpect<TransferAck>(input, TransferAck.TYPE)
            return@withContext finish(
                record = record.copy(
                    verified = verification.ok,
                    verifiedFileCount = verification.verifiedFiles,
                    failedFiles = verification.failedFiles,
                ),
                status = if (verification.ok) TransferStatus.SUCCESS else TransferStatus.VERIFICATION_FAILED,
                listener = listener,
                error = verification.message,
            )
        } catch (e: TransferCancelledException) {
            Logs.i(TAG, "Send $transferId cancelled")
            finish(record, TransferStatus.CANCELLED, listener, error = e.message)
        } catch (e: TransferRejectedException) {
            Logs.i(TAG, "Send $transferId rejected: ${e.message}")
            finish(record, TransferStatus.REJECTED, listener, error = e.message)
        } catch (e: ProtocolException) {
            Logs.w(TAG, "Send $transferId protocol error", e)
            finish(record, TransferStatus.FAILED, listener, error = protocolMessage(e))
        } catch (e: EOFException) {
            Logs.w(TAG, "Send $transferId: peer disconnected", e)
            finish(
                record, TransferStatus.INTERRUPTED, listener,
                error = "The connection to $peerName was lost. Stay on the same Wi-Fi and try again.",
            )
        } catch (e: java.net.SocketTimeoutException) {
            Logs.w(TAG, "Send $transferId timed out", e)
            finish(
                record, TransferStatus.INTERRUPTED, listener,
                error = "$peerName stopped responding. Check that both devices are still on the same Wi-Fi.",
            )
        } catch (e: java.net.UnknownHostException) {
            finish(record, TransferStatus.FAILED, listener, error = "Could not find $peerAddress on this network.")
        } catch (e: java.net.ConnectException) {
            // A refused connection means nothing is listening on that port. That is a setup problem
            // rather than a dropped transfer, so it gets its own message and a FAILED status.
            // ConnectException extends SocketException, so this catch must come first.
            Logs.w(TAG, "Send $transferId could not connect", e)
            finish(
                record, TransferStatus.FAILED, listener,
                error = "Could not connect to $peerName at $peerAddress:$peerPort. Check that " +
                    "QuickShare is open on the other device and that both are on the same Wi-Fi network.",
            )
        } catch (e: java.net.NoRouteToHostException) {
            Logs.w(TAG, "Send $transferId has no route", e)
            finish(record, TransferStatus.FAILED, listener, error = "$peerAddress is not reachable on this network.")
        } catch (e: java.net.SocketException) {
            Logs.w(TAG, "Send $transferId socket error", e)
            finish(record, TransferStatus.INTERRUPTED, listener, error = "The network connection dropped: ${e.message}")
        } catch (e: TransferException) {
            finish(record, TransferStatus.FAILED, listener, error = e.message)
        } catch (e: Exception) {
            Logs.e(TAG, "Send $transferId failed unexpectedly", e)
            finish(record, TransferStatus.FAILED, listener, error = "Transfer failed: ${e.message ?: e::class.java.simpleName}")
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                Logs.d(TAG, "Socket close failed: ${e.message}")
            }
        }
    }

    private fun protocolMessage(e: ProtocolException): String =
        "The other device sent an unexpected response. ${e.message ?: ""}".trim()

    private fun finish(
        record: TransferRecord,
        status: TransferStatus,
        listener: TransferListener,
        error: String? = null,
    ): TransferRecord {
        val finished = record.copy(
            status = status,
            finishedAtMs = System.currentTimeMillis(),
            errorMessage = error,
        )
        listener.onFinished(finished)
        return finished
    }

    /**
     * Reads the next frame and fails with a clear message when the peer sent something else.
     *
     * A peer that answers an [Hello] with an [ErrorMessage] is reported with that message rather
     * than a generic "unexpected frame" error, which is what makes protocol errors diagnosable.
     */
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
}
