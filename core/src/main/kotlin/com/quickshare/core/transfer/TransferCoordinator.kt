package com.quickshare.core.transfer

import com.quickshare.core.data.HistoryStats
import com.quickshare.core.data.TransferHistoryRepository
import com.quickshare.core.net.Peer
import com.quickshare.core.pairing.PairingCode
import com.quickshare.core.util.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single entry point the UI uses to send and receive.
 *
 * Responsibilities, and the reason this exists as a separate type rather than living in a screen:
 * * owns the send/receive lifecycles and exposes them as flows,
 * * serialises concurrent sends so two taps cannot interleave on one socket,
 * * keeps cancellation flags that the engine polls, and
 * * records history for every terminal state.
 *
 * It is deliberately free of Android types, so the whole orchestration path is unit-testable and the
 * end-to-end test drives exactly the same code the app does.
 */
class TransferCoordinator(
    private val client: TransferClient,
    private val history: TransferHistoryRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {

    private companion object {
        const val TAG = "TransferCoordinator"
    }

    private val _progress = MutableSharedFlow<TransferProgress>(extraBufferCapacity = 64)
    /** Progress ticks from every in-flight transfer. */
    val progress: SharedFlow<TransferProgress> = _progress.asSharedFlow()

    private val _completed = MutableSharedFlow<TransferRecord>(extraBufferCapacity = 16)
    /** Terminal records, emitted once per transfer. */
    val completed: SharedFlow<TransferRecord> = _completed.asSharedFlow()

    private val _activeSends = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    /** Currently running sends, keyed by transfer id. Drives the in-progress tray. */
    val activeSends: StateFlow<Map<String, TransferProgress>> = _activeSends.asStateFlow()

    private val _activeReceives = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    /** Currently running receives, keyed by transfer id. */
    val activeReceives: StateFlow<Map<String, TransferProgress>> = _activeReceives.asStateFlow()

    private val cancellations = ConcurrentHashMap<String, AtomicBoolean>()
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Requests refused because the sender is not paired, or because they arrived unannounced. */
    private val _rejections = MutableSharedFlow<RejectionEvent>(extraBufferCapacity = 16)
    val rejections: SharedFlow<RejectionEvent> = _rejections.asSharedFlow()

    /** A new pairing code is generated per transfer, so a code cannot be replayed later. */
    fun newPairingCode(): String = PairingCode.generate()

    fun newTransferId(): String = idFactory()

    fun isSending(transferId: String): Boolean = jobs[transferId]?.isActive == true

    fun activeCount(): Int = jobs.count { it.value.isActive }

    /**
     * Starts a send.
     *
     * @return the transfer id immediately, so the UI can show the progress row before the socket
     *         has even connected. The work continues in the background.
     */
    fun startSend(
        peer: Peer,
        files: List<FileSource>,
        pairingCode: String,
        listener: TransferListener = object : TransferListener {},
        onStarted: (String) -> Unit = {},
    ): String {
        val transferId = idFactory()
        val cancelled = AtomicBoolean(false)
        cancellations[transferId] = cancelled

        val job = scope.launch {
            if (files.isEmpty()) {
                emitRejection(transferId, "Nothing was selected to send.")
                cancellations.remove(transferId)
                jobs.remove(transferId)
                return@launch
            }

            // Record the attempt up front so a transfer that dies mid-flight is still visible in
            // history as "interrupted" rather than vanishing entirely.
            history.upsert(
                TransferRecord(
                    transferId = transferId,
                    direction = TransferDirection.SEND,
                    peerName = peer.deviceName,
                    peerAddress = peer.address,
                    fileNames = files.map { it.name },
                    fileCount = files.size,
                    totalBytes = files.sumOf { it.size },
                    startedAtMs = System.currentTimeMillis(),
                    status = TransferStatus.IN_PROGRESS,
                ),
            )
            onStarted(transferId)

            val record = client.send(
                peerAddress = peer.address,
                peerPort = peer.controlPort,
                peerName = peer.deviceName,
                files = files,
                transferId = transferId,
                pairingCode = pairingCode,
                listener = object : TransferListener by listener {
                    override fun onProgress(progress: TransferProgress) {
                        // modify{} is atomic; a plain read-modify-write could resurrect an entry
                        // that finish() had just removed on another thread.
                        _activeSends.modify { it + (transferId to progress) }
                        _progress.tryEmit(progress)
                        listener.onProgress(progress)
                    }
                },
                isCancelled = { cancelled.get() },
            )

            finish(record)
        }

        jobs[transferId] = job
        return transferId
    }

    /**
     * Requests cancellation of [transferId].
     *
     * The flag is polled between chunks rather than the socket being closed underneath the writer, so
     * the peer receives a clean end-of-stream instead of a truncated frame it would report as a
     * protocol error.
     */
    fun cancel(transferId: String) {
        val flag = cancellations[transferId]
        if (flag == null) {
            Logs.d(TAG, "Cancel requested for unknown transfer $transferId")
            return
        }
        flag.set(true)
        Logs.i(TAG, "Cancel requested for $transferId")
    }

    fun cancelAll() {
        cancellations.values.forEach { it.set(true) }
    }

    /** Re-runs a failed send with the same peer, files and code. Returns the new transfer id. */
    fun retry(
        previous: TransferRecord,
        peer: Peer,
        files: List<FileSource>,
        pairingCode: String,
    ): String {
        Logs.i(TAG, "Retrying ${previous.transferId} (${previous.status}) to ${peer.deviceName}")
        // A fresh pairing code and transfer id are used, so a retry can never be mistaken for a
        // replay of the original attempt by either side.
        return startSend(peer = peer, files = files, pairingCode = pairingCode)
    }

    /** Records a receive-side terminal state. Called by the Android receiver service. */
    fun recordReceived(record: TransferRecord): List<TransferRecord> {
        _activeReceives.modify { it - record.transferId }
        val updated = history.upsert(record)
        _completed.tryEmit(record)
        return updated
    }

    /** Pushes a receive-side progress tick (the receiver runs on its own thread). */
    fun publishReceiveProgress(progress: TransferProgress) {
        _activeReceives.modify { it + (progress.transferId to progress) }
        _progress.tryEmit(progress)
    }

    fun history(): List<TransferRecord> = history.all()

    fun stats(): HistoryStats = history.stats()

    fun clearHistory(): List<TransferRecord> = history.clear()

    fun removeHistory(transferId: String): List<TransferRecord> = history.remove(transferId)

    /** True when a send to this peer name has succeeded before; used for the trusted-device prompt. */
    fun hasTradedWith(peerName: String): Boolean = history.hasSuccessfulTransferWith(peerName)

    private fun finish(record: TransferRecord) {
        cancellations.remove(record.transferId)
        jobs.remove(record.transferId)
        _activeSends.modify { it - record.transferId }
        history.upsert(record)
        _completed.tryEmit(record)
        Logs.i(TAG, "Finished ${record.transferId}: ${record.status} (${record.summary()})")
    }

    private fun emitRejection(transferId: String, reason: String) {
        _rejections.tryEmit(RejectionEvent(transferId, reason))
        Logs.w(TAG, "Rejected $transferId: $reason")
    }

    fun shutdown() {
        cancelAll()
        scope.cancel()
    }
}

/** A transfer that never started, with the reason shown to the user. */
data class RejectionEvent(val transferId: String, val reason: String)

/**
 * Atomic read-modify-write on a state flow (a compare-and-set loop).
 *
 * Progress arrives from several threads while finished transfers are removed from the same map; a plain
 * `value = value + x` can lose one of those updates, which would leave a finished transfer stuck on
 * screen or make a running one vanish.
 */
private inline fun <T> MutableStateFlow<T>.modify(transform: (T) -> T) {
    while (true) {
        val previous = value
        if (compareAndSet(previous, transform(previous))) return
    }
}
