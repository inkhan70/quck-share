package com.quickshare.app.ui

import com.quickshare.core.data.Settings
import com.quickshare.core.net.Peer
import com.quickshare.core.net.NetworkUtils
import com.quickshare.core.transfer.TransferStatus

/**
 * Pure presentation logic for the UI layer.
 *
 * Everything here is deliberately free of Android and Compose types, which is what lets the app
 * module carry real JVM unit tests: the parts of the UI that make decisions (is this transfer a
 * failure, may this selection be sent, which address did the user mean) are testable without an
 * emulator, while the parts that merely draw stay in the composables.
 */

/** Which screen the bottom navigation is showing. */
enum class AppTab { NEARBY, HISTORY, SETTINGS }

/** Full-screen information pages that sit outside the tab bar. */
enum class InfoPage { HOW_TO_USE, ABOUT }

/**
 * How a transfer should be presented.
 *
 * Derived from [TransferStatus] rather than using it directly so the UI has one explicit mapping to
 * reason about, and so "needs a retry button" is a property of the view state rather than a
 * condition repeated at each call site.
 */
enum class TransferUiState {
    IN_PROGRESS,
    SUCCESS,
    VERIFICATION_FAILED,
    FAILED,
    CANCELLED,
    REJECTED,
    INTERRUPTED,
    ;

    val isRunning: Boolean get() = this == IN_PROGRESS
    val isOk: Boolean get() = this == SUCCESS

    /** True when the user could reasonably try again. A user-initiated cancel is not offered. */
    val canRetry: Boolean
        get() = this == FAILED || this == INTERRUPTED || this == VERIFICATION_FAILED

    /** True when the transfer left data in a bad state and should be shown in the warning tone. */
    val isProblem: Boolean
        get() = this == FAILED || this == INTERRUPTED || this == VERIFICATION_FAILED || this == REJECTED
}

fun uiStateOf(status: TransferStatus): TransferUiState = when (status) {
    TransferStatus.IN_PROGRESS -> TransferUiState.IN_PROGRESS
    TransferStatus.SUCCESS -> TransferUiState.SUCCESS
    TransferStatus.VERIFICATION_FAILED -> TransferUiState.VERIFICATION_FAILED
    TransferStatus.FAILED -> TransferUiState.FAILED
    TransferStatus.CANCELLED -> TransferUiState.CANCELLED
    TransferStatus.REJECTED -> TransferUiState.REJECTED
    TransferStatus.INTERRUPTED -> TransferUiState.INTERRUPTED
}

/**
 * The files the user has staged for sending.
 *
 * A small value type rather than a bare list so the "may I send this" rules live in one place: they
 * are easy to get subtly wrong (an empty selection, a duplicate pick) and are asserted by tests.
 */
data class SendSelection(val items: List<StagedFile> = emptyList()) {

    /** The form the UI loads first. Kept so a caller can write `SendSelection.empty`. */
    companion object {
        val empty: SendSelection = SendSelection()
    }

    val fileCount: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()
    val totalBytes: Long get() = items.sumOf { it.size }

    /** Sending requires something to send. */
    val canSend: Boolean get() = items.isNotEmpty()

    /**
     * Adds [file], ignoring one that is already staged under the same URI.
     *
     * Re-picking a file the user already added is a realistic mis-tap, and honouring it would send
     * the same content twice under a numbered name.
     */
    fun add(file: StagedFile): SendSelection =
        if (items.any { it.uri == file.uri }) this else copy(items = items + file)

    fun addAll(files: List<StagedFile>): SendSelection = files.fold(this) { acc, f -> acc.add(f) }

    fun remove(uri: String): SendSelection = copy(items = items.filterNot { it.uri == uri })

    fun clear(): SendSelection = SendSelection()
}

/** One picked file, described without ever opening it. */
data class StagedFile(
    val uri: String,
    val name: String,
    val size: Long,
    val mime: String,
)

/**
 * Turns what the user typed into a connect target.
 *
 * Thin wrapper over the core parser so the UI and the tests share one definition of "usable", and so
 * the error the user sees can distinguish "not an address" from "not a device address" (a broadcast
 * or the unroutable `0.0.0.0`, both of which are valid literals but meaningless to dial).
 */
object ManualConnect {

    sealed interface Result {
        data class Ok(val host: String, val port: Int) : Result
        data object Blank : Result
        data object NotAnAddress : Result
        data object NotDialable : Result
    }

    fun parse(input: String?, defaultPort: Int): Result {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isEmpty()) return Result.Blank
        val withoutScheme = trimmed.removePrefix("http://").removePrefix("https://").trimEnd('/')
        val host = withoutScheme.substringBefore(':')
        if (!NetworkUtils.isValidIpv4(host)) return Result.NotAnAddress
        if (!NetworkUtils.isUsableHostAddress(host)) return Result.NotDialable
        val parsed = NetworkUtils.parseHostPort(trimmed, defaultPort) ?: return Result.NotAnAddress
        return Result.Ok(parsed.first, parsed.second)
    }
}

/** Theme choices as the Settings screen presents them. */
enum class ThemeOption(val storageValue: String) {
    SYSTEM(Settings.DARK_MODE_SYSTEM),
    LIGHT(Settings.DARK_MODE_LIGHT),
    DARK(Settings.DARK_MODE_DARK),
    ;

    companion object {
        /** Maps a stored value to an option, falling back to [SYSTEM] for anything unrecognised. */
        fun from(stored: String?): ThemeOption =
            entries.firstOrNull { it.storageValue == stored } ?: SYSTEM
    }
}

/**
 * Orders peers for display.
 *
 * Sorted by name then id so the list does not reshuffle every time a beacon refreshes an entry -
 * insertion order changes as peers come and go, which makes a list that reorders under the user's
 * finger. The id is the tiebreaker so two devices with the same name still have a stable order.
 */
object PeerOrdering {
    fun sorted(peers: List<Peer>): List<Peer> =
        peers.sortedWith(compareBy({ it.deviceName.lowercase() }, { it.deviceId }))
}

/**
 * A quick summary of the history list, used by the empty/header state.
 */
data class HistorySummary(
    val total: Int,
    val sent: Int,
    val received: Int,
    val failed: Int,
    val successfulBytes: Long,
) {
    val hasAny: Boolean get() = total > 0
}

fun summarize(records: List<com.quickshare.core.transfer.TransferRecord>): HistorySummary =
    HistorySummary(
        total = records.size,
        sent = records.count { it.direction == com.quickshare.core.transfer.TransferDirection.SEND },
        received = records.count { it.direction == com.quickshare.core.transfer.TransferDirection.RECEIVE },
        failed = records.count { it.status.isTerminal && !it.status.isSuccess },
        successfulBytes = records.filter { it.status.isSuccess }.sumOf { it.totalBytes },
    )
