package com.quickshare.core.pairing

import java.security.SecureRandom

/**
 * Short numeric pairing codes.
 *
 * A six-digit code is the usability sweet spot: short enough to read aloud across a room, long
 * enough that blind guessing against a live socket is impractical given that the receiver also
 * enforces a limited number of attempts within a short window.
 *
 * [SecureRandom] is used rather than [kotlin.random.Random] because a guessable code would defeat
 * the pairing step entirely.
 */
object PairingCode {

    const val LENGTH = com.quickshare.core.protocol.Protocol.PAIRING_CODE_LENGTH

    private val random = SecureRandom()

    /** A zero-padded code of exactly [LENGTH] digits. */
    fun generate(length: Int = LENGTH): String {
        require(length in 4..10) { "Pairing codes must be 4-10 digits" }
        val builder = StringBuilder(length)
        repeat(length) { builder.append(random.nextInt(10)) }
        return builder.toString()
    }

    /**
     * Compares two codes without an early exit.
     *
     * Both codes are the same short length, so the practical risk is low, but comparing in constant
     * time costs nothing here and removes a timing side channel from the one place a user-supplied
     * secret is compared.
     */
    fun matches(expected: String?, provided: String?): Boolean {
        if (expected.isNullOrBlank() || provided.isNullOrBlank()) return false
        val a = expected.trim()
        val b = provided.trim()
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /** True when [value] is exactly [LENGTH] digits - the shape check the UI applies. */
    fun isWellFormed(value: String?, length: Int = LENGTH): Boolean {
        if (value == null) return false
        val trimmed = value.trim()
        return trimmed.length == length && trimmed.all { it.isDigit() }
    }

    /** Formats a code for display: `482913` -> `482 913`, which is easier to compare visually. */
    fun format(code: String): String {
        if (code.length <= 3) return code
        val half = code.length / 2
        return code.substring(0, half) + " " + code.substring(half)
    }

    /**
     * Masks a code for logs, keeping only the last digit. Codes must never appear in full in any
     * log that might be shared in a bug report.
     */
    fun mask(code: String?): String {
        if (code.isNullOrEmpty()) return "(none)"
        return "*".repeat((code.length - 1).coerceAtLeast(0)) + code.last()
    }
}
