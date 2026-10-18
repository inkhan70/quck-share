package com.quickshare.core.transfer

/**
 * Callbacks emitted by an in-flight transfer.
 *
 * All of them are optional and default to no-ops, so a caller only implements what it needs. They
 * are invoked from a background thread; the Android layer is responsible for hopping to the main
 * thread before touching UI.
 */
interface TransferListener {

    /** Throttled progress update (roughly 5 per second, plus one at each milestone). */
    fun onProgress(progress: TransferProgress) {}

    /** A single file finished and passed its checksum. */
    fun onFileCompleted(index: Int, total: Int, name: String) {}

    /** The transfer finished, successfully or not. Always called exactly once. */
    fun onFinished(record: TransferRecord) {}

    /**
     * Receive side only: the peer wants to pair and the user must confirm.
     *
     * Suspends until the user decides. Returning false sends a rejection to the peer.
     */
    suspend fun onPairingRequested(peerName: String, code: String): Boolean = true

    /**
     * Receive side only: an offer arrived and the user must accept.
     *
     * Suspends until the user decides. The default refuses, so a caller that forgets to implement
     * this can never silently accept an unsolicited file drop.
     */
    suspend fun onOfferReceived(peerName: String, offer: OfferSummary): Boolean = false

    /** Receive side only: the user is asked to choose a destination folder. */
    suspend fun onDestinationRequired(): String? = null
}

/**
 * A sanitized, UI-ready view of an incoming offer.
 *
 * Deliberately a copy rather than the protocol message: the UI layer must not be able to mutate the
 * wire object, and it should not have to know about `com.quickshare.core.protocol`.
 */
data class OfferSummary(
    val transferId: String,
    val peerName: String,
    val files: List<TransferFileInfo>,
    val totalBytes: Long,
    val message: String?,
) {
    val fileCount: Int get() = files.size
    val totalSizeText: String get() = com.quickshare.core.util.ByteFormat.size(totalBytes)

    /** A few names plus a "+N more" tail, which is what fits on a dialog. */
    fun previewNames(limit: Int = 3): String {
        if (files.isEmpty()) return "No files"
        val head = files.take(limit).joinToString(", ") { it.name }
        return if (files.size > limit) "$head and ${files.size - limit} more" else head
    }
}

/** Outcome of one file's checksum comparison. */
data class VerificationResult(
    val verified: Boolean,
    val verifiedFiles: Int,
    val failedFiles: List<String>,
)
