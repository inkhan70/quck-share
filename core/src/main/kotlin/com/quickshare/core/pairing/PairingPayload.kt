package com.quickshare.core.pairing

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The payload carried by a pairing QR code.
 *
 * A QR code removes the typing step entirely: the receiver's camera reads the address, port and
 * code at once. The format is a URI rather than raw JSON so that it is self-describing - if a user
 * scans a QuickShare code with any other app, it is obvious what it is.
 *
 * ```
 * quickshare://pair?host=192.168.1.42&port=8988&code=482913&name=Pixel%207&v=1
 * ```
 *
 * Parsing is deliberately strict but never throws: a QR code is untrusted input, and a bad code
 * should show a clear message rather than crash the scanner.
 */
data class PairingPayload(
    val host: String,
    val port: Int,
    val code: String,
    val deviceName: String,
    val protocolVersion: Int,
) {

    companion object {
        const val SCHEME = "quickshare"
        const val HOST = "pair"

        private const val MAX_NAME_LENGTH = com.quickshare.core.protocol.Protocol.MAX_DEVICE_NAME_LENGTH

        /**
         * Builds the URI for [host]:[port].
         *
         * @throws IllegalArgumentException when the host is not a valid IPv4 literal, so a QR code is
         *         never generated pointing at something unreachable.
         */
        fun encode(
            host: String,
            port: Int,
            code: String,
            deviceName: String,
            protocolVersion: Int = com.quickshare.core.protocol.Protocol.VERSION,
        ): String {
            require(com.quickshare.core.net.NetworkUtils.isValidIpv4(host)) { "Not a valid IPv4 address: $host" }
            require(port in 1..65535) { "Port out of range: $port" }
            require(PairingCode.isWellFormed(code)) { "Code must be ${PairingCode.LENGTH} digits" }
            return buildString {
                append(SCHEME).append("://").append(HOST)
                append("?host=").append(encodeComponent(host))
                append("&port=").append(port)
                append("&code=").append(encodeComponent(code))
                append("&name=").append(encodeComponent(deviceName.take(MAX_NAME_LENGTH)))
                append("&v=").append(protocolVersion)
            }
        }

        /**
         * Parses a scanned QR payload.
         *
         * @return the payload, or null when the string is not a QuickShare pairing code or is
         *         missing/ill-forming its required parameters.
         */
        fun decode(raw: String?): PairingPayload? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            if (!trimmed.startsWith("$SCHEME://", ignoreCase = true)) return null

            val withoutScheme = trimmed.substringAfter("://", "")
            val authority = withoutScheme.substringBefore('?')
            if (!authority.equals(HOST, ignoreCase = true)) return null

            val query = withoutScheme.substringAfter('?', "")
            if (query.isEmpty()) return null

            val params = mutableMapOf<String, String>()
            for (pair in query.split('&')) {
                if (pair.isBlank()) continue
                val key = pair.substringBefore('=', "").lowercase()
                val value = pair.substringAfter('=', "")
                if (key.isNotEmpty()) params[key] = decodeComponent(value)
            }

            val host = params["host"]?.takeIf { com.quickshare.core.net.NetworkUtils.isValidIpv4(it) } ?: return null
            val port = params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val code = params["code"]?.takeIf { PairingCode.isWellFormed(it) } ?: return null
            val name = params["name"]
                ?.takeIf { it.isNotBlank() }
                ?.take(MAX_NAME_LENGTH)
                ?: "QuickShare device"
            val version = params["v"]?.toIntOrNull() ?: com.quickshare.core.protocol.Protocol.VERSION

            return PairingPayload(host = host, port = port, code = code, deviceName = name, protocolVersion = version)
        }

        private fun encodeComponent(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")

        private fun decodeComponent(value: String): String = try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.toString())
        } catch (e: Exception) {
            value
        }
    }

    /** A human-readable summary for the confirmation sheet. */
    fun describe(): String = "$deviceName\n$host:$port\nCode ${PairingCode.format(code)}"
}
