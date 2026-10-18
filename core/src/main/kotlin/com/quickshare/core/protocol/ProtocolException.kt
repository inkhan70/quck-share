package com.quickshare.core.protocol

/**
 * Signalled whenever a peer sends something that violates the protocol: a truncated frame, an
 * oversized length prefix, a missing required field, or an unknown message type.
 *
 * It is a checked-style failure rather than a crash: the transfer engine turns it into a clear
 * user-facing error and closes the connection.
 */
class ProtocolException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)
