package com.quickshare.app.ui

import com.quickshare.core.transfer.OfferSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A question the receive engine is blocked on, waiting for the user.
 *
 * Modelled as a sealed interface rather than three separate channels so the UI has exactly one thing
 * to render and cannot accidentally show two prompts at once - which would let a pairing dialog be
 * answered by a tap meant for an offer.
 */
sealed interface ReceivePrompt {

    /** Who is asking. Empty for a destination request, which has no peer attached. */
    val peerName: String

    /** The peer wants to connect; the user confirms the code shown on both screens. */
    data class Pairing(
        override val peerName: String,
        val code: String,
    ) : ReceivePrompt

    /** The peer wants to send files; the user reviews the list and the total size. */
    data class Offer(
        override val peerName: String,
        val offer: OfferSummary,
    ) : ReceivePrompt

    /** No save folder is configured yet, so the user must choose one or decline. */
    data object Destination : ReceivePrompt {
        override val peerName: String = ""
    }
}

/**
 * Bridges the receive engine's suspending callbacks to the UI.
 *
 * The engine runs on its own threads (and, while a transfer is in the background, in a service) and
 * asks the user a question by suspending. This object parks that question in a [StateFlow] the
 * Compose tree observes, and unblocks the engine when the user answers.
 *
 * Two properties matter and are both asserted by tests:
 *  * a prompt that is never answered still resolves - after [DEFAULT_TIMEOUT_MS] - and resolves to
 *    **refusal**, so an unanswered transfer can never be accepted by default, and
 *  * answering a prompt that is no longer pending is a no-op, so a late tap cannot answer the
 *    *next* peer's question.
 */
object ReceivePromptController {

    /**
     * How long a prompt waits for the user before it is treated as declined.
     *
     * Deliberately generous - a person has to read a file list and decide - but finite, because the
     * sender is sitting on an open socket and would otherwise wait forever.
     */
    const val DEFAULT_TIMEOUT_MS: Long = 90_000L

    private val _prompt = MutableStateFlow<ReceivePrompt?>(null)

    /** The prompt the UI should currently render, or null when nothing is being asked. */
    val prompt: StateFlow<ReceivePrompt?> = _prompt.asStateFlow()

    private val lock = Any()
    private var decision: CompletableDeferred<Boolean>? = null
    private var destinationDecision: CompletableDeferred<String?>? = null

    /** True when the engine is currently parked on a question. */
    fun isPending(): Boolean = _prompt.value != null

    /** The prompt being awaited, for assertion in tests and for notification text. */
    fun current(): ReceivePrompt? = _prompt.value

    /**
     * Asks the user to confirm a pairing code.
     *
     * @return true when the user accepted; false when they declined or the prompt timed out.
     */
    suspend fun awaitPairingDecision(
        peerName: String,
        code: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Boolean = awaitBooleanPrompt(ReceivePrompt.Pairing(peerName, code), timeoutMs)

    /**
     * Asks the user to accept a specific offer.
     *
     * @return true when the user accepted; false when they declined or the prompt timed out.
     */
    suspend fun awaitOfferDecision(
        peerName: String,
        offer: OfferSummary,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Boolean = awaitBooleanPrompt(ReceivePrompt.Offer(peerName, offer), timeoutMs)

    /**
     * Asks the user to pick a save folder.
     *
     * @return the chosen tree URI, or null when the user backed out (which the engine turns into a
     *         clean refusal with a visible reason).
     */
    suspend fun awaitDestinationChoice(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): String? = promptForDestination(timeoutMs)

    private suspend fun awaitBooleanPrompt(prompt: ReceivePrompt, timeoutMs: Long): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        // A second concurrent prompt would strand the first engine call, so the older one is failed
        // closed first. In practice the engine serialises its prompts, but the queue must not be able
        // to grow silently if that ever changes.
        synchronized(lock) {
            decision?.complete(false)
            decision = deferred
        }
        _prompt.value = prompt
        val answered = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            // Cancellation or an interrupted wait: refuse, never accept.
            null
        }
        synchronized(lock) {
            if (decision === deferred) decision = null
        }
        // Only the prompt that is still current is cleared, so a stale prompt cannot blank a newer one.
        if (_prompt.value === prompt) _prompt.value = null
        return answered == true
    }

    private suspend fun promptForDestination(timeoutMs: Long): String? {
        val deferred = CompletableDeferred<String?>()
        synchronized(lock) {
            destinationDecision?.complete(null)
            destinationDecision = deferred
        }
        _prompt.value = ReceivePrompt.Destination
        val chosen = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            null
        }
        synchronized(lock) {
            if (destinationDecision === deferred) destinationDecision = null
        }
        if (_prompt.value === ReceivePrompt.Destination) _prompt.value = null
        return chosen
    }

    /**
     * Answers the pending prompt.
     *
     * A no-op when nothing is pending, which is what makes a tap that lands after the prompt was
     * already resolved harmless.
     */
    fun answer(accepted: Boolean) {
        val target = synchronized(lock) {
            when (_prompt.value) {
                is ReceivePrompt.Destination -> null
                null -> null
                else -> decision
            }
        }
        target?.complete(accepted)
    }

    /** Answers a destination prompt with the tree URI the user picked, or null to decline. */
    fun answerDestination(treeUri: String?) {
        val target = synchronized(lock) {
            if (_prompt.value is ReceivePrompt.Destination) destinationDecision else null
        }
        target?.complete(treeUri)
    }

    /**
     * Fails every pending prompt closed.
     *
     * Called when the UI goes away (activity destroyed, service stopping) so the engine is never left
     * parked on a question nobody can answer.
     */
    fun cancelAll() {
        synchronized(lock) {
            decision?.complete(false)
            destinationDecision?.complete(null)
            decision = null
            destinationDecision = null
        }
        _prompt.value = null
    }
}
