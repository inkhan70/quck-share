package com.quickshare.core.transfer

/** Which way the bytes are moving. */
enum class TransferDirection { SEND, RECEIVE }

/** Terminal and in-flight states of one transfer. */
enum class TransferStatus {
    IN_PROGRESS,
    SUCCESS,
    FAILED,
    CANCELLED,
    REJECTED,
    VERIFICATION_FAILED,
    INTERRUPTED,
    ;

    val isTerminal: Boolean get() = this != IN_PROGRESS
    val isSuccess: Boolean get() = this == SUCCESS
}

/** A file as offered by the sender: metadata only, never the bytes. */
data class TransferFileInfo(
    val name: String,
    val relativePath: String,
    val size: Long,
    val mime: String,
) {
    val extension: String
        get() = com.quickshare.core.util.SafePaths.extensionOf(name)
}

/** Progress snapshot for one transfer, as surfaced to the UI and the notification. */
data class TransferProgress(
    val transferId: String,
    val direction: TransferDirection,
    val fileIndex: Int,
    val fileCount: Int,
    val fileName: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val etaSeconds: Long,
    val fileBytesTransferred: Long = 0L,
    val fileBytesTotal: Long = 0L,
) {
    val percent: Int get() = com.quickshare.core.util.ByteFormat.percent(bytesTransferred, totalBytes)

    val filePercent: Int get() = com.quickshare.core.util.ByteFormat.percent(fileBytesTransferred, fileBytesTotal)

    val isIndeterminate: Boolean get() = totalBytes <= 0
}

/**
 * A persistent summary of a finished transfer. History is what lets the user answer "did that
 * file actually arrive, and did it check out?" long after the notification is gone.
 */
data class TransferRecord(
    val transferId: String,
    val direction: TransferDirection,
    val peerName: String,
    val peerAddress: String = "",
    val fileNames: List<String> = emptyList(),
    val fileCount: Int = 0,
    val totalBytes: Long = 0L,
    val startedAtMs: Long = 0L,
    val finishedAtMs: Long = 0L,
    val status: TransferStatus = TransferStatus.IN_PROGRESS,
    val verified: Boolean = false,
    val verifiedFileCount: Int = 0,
    val failedFiles: List<String> = emptyList(),
    val errorMessage: String? = null,
    val saveLocation: String? = null,
) {
    val durationMs: Long
        get() = if (finishedAtMs > startedAtMs) finishedAtMs - startedAtMs else 0L

    val averageBytesPerSecond: Long
        get() = if (durationMs > 0) (totalBytes * 1000L) / durationMs else 0L

    /** One-line summary for the history list. */
    fun summary(): String {
        val size = com.quickshare.core.util.ByteFormat.size(totalBytes)
        val count = if (fileCount == 1) "1 file" else "$fileCount files"
        return "$count \u00b7 $size"
    }
}

/**
 * Raised for any transfer failure that has a user-facing explanation. The message is written to be
 * shown verbatim in the UI, so it names the cause and the remedy rather than an exception type.
 */
class TransferException(
    message: String,
    cause: Throwable? = null,
    val code: String = "transfer_error",
) : java.io.IOException(message, cause)

/** Raised when the peer rejects an offer or a pairing request. Carries the peer's reason. */
class TransferRejectedException(message: String) : java.io.IOException(message)

/** Raised when an explicit cancel was requested, so it is never reported as a failure. */
class TransferCancelledException(message: String = "Transfer cancelled") : java.io.IOException(message)
