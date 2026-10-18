package com.quickshare.core.protocol

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.quickshare.core.util.SafePaths

/**
 * The control-channel message set.
 *
 * Messages are modelled as a small sealed hierarchy for use inside the app, and serialised through
 * a two-layer envelope (`{type, data}`) rather than Gson polymorphic adapters. The envelope keeps
 * the decoder explicit: an unknown or hostile `type` is rejected with a clear
 * [ProtocolException] instead of a reflective class load.
 */
sealed interface Message {
    val type: String
}

// ---------------------------------------------------------------------------------------------
// Domain messages
// ---------------------------------------------------------------------------------------------

/**
 * First message on a control connection. Announces who is calling and which protocol they speak.
 *
 * @param groupCode short human-readable code derived from the group's network, used to suppress
 *        cross-network noise and shown in the UI as "same Wi-Fi" confirmation.
 */
data class Hello(
    val protocolVersion: Int,
    val deviceName: String,
    val deviceId: String,
    val controlPort: Int,
    val groupCode: String,
    val appVersion: String,
) : Message {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "hello"
    }
}

/** Reply to [Hello]; carries the responder's own listening port. */
data class HelloAck(
    val protocolVersion: Int,
    val deviceName: String,
    val deviceId: String,
    val controlPort: Int,
    val accepted: Boolean,
    val reason: String?,
) : Message {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "hello_ack"
    }
}

/**
 * Sent by the sender so the receiver can confirm the pairing code out of band (visually).
 * The receiver only proceeds when the user accepts.
 */
data class PairRequest(
    val code: String,
    val deviceName: String,
    val deviceId: String,
) : Message {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "pair_request"
    }
}

/** The receiver's verdict on a [PairRequest]. */
data class PairResponse(
    val accepted: Boolean,
    val reason: String? = null,
) : Message {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "pair_response"
    }
}

/** One file inside an [Offer]. Sizes are always the true byte length, never an estimate. */
data class OfferedFile(
    val name: String,
    val relativePath: String,
    val size: Long,
    val mime: String,
)

/** The manifest of a transfer, sent before any bytes move. */
data class Offer(
    val transferId: String,
    val files: List<OfferedFile>,
    val totalBytes: Long,
    val message: String?,
) : Message {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "offer"
    }
}

/** The receiver's verdict on an [Offer]. `accepted == false` carries a reason for the UI. */
data class OfferResponse(
    val transferId: String,
    val accepted: Boolean,
    val reason: String? = null,
) : Message {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "offer_response"
    }
}

/** Per-file digest reported by the sender once every byte has been written to the socket. */
data class FileDigest(
    val name: String,
    val sha256: String,
)

/**
 * Sent by the sender after the data channel is fully written. The receiver compares these
 * digests against the ones it computed while writing to disk: that comparison is what turns
 * "the bytes arrived" into "the bytes are correct".
 */
data class TransferComplete(
    val transferId: String,
    val digests: List<FileDigest>,
) : Message {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "transfer_complete"
    }
}

/** The receiver's final verdict, including any files whose checksum did not match. */
data class TransferAck(
    val transferId: String,
    val ok: Boolean,
    val verifiedFiles: Int,
    val failedFiles: List<String> = emptyList(),
    val message: String? = null,
) : Message {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "transfer_ack"
    }
}

/** Either side may cancel at any point. The peer drops the partial files. */
data class Cancel(
    val transferId: String,
    val reason: String?,
) : Message {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "cancel"
    }
}

/** Protocol-level error, always accompanied by a human-readable [message]. */
data class ErrorMessage(
    val code: String,
    val message: String,
) : Message {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "error"
    }
}

/** Liveness probe. Also used by the manual-IP fallback to confirm a port is really QuickShare. */
data class Ping(val nonce: Long) : Message {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "ping"
    }
}

data class Pong(val nonce: Long) : Message {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "pong"
    }
}

// ---------------------------------------------------------------------------------------------
// Wire DTOs
// Every field is nullable so that a malformed or truncated payload arrives as nulls and is then
// rejected by validate(), instead of throwing from inside Gson's reflection.
// ---------------------------------------------------------------------------------------------

private data class Envelope(
    @SerializedName("type") val type: String? = null,
    @SerializedName("data") val data: JsonObject? = null,
)

private data class HelloDto(
    @SerializedName("protocolVersion") val protocolVersion: Int? = null,
    @SerializedName("deviceName") val deviceName: String? = null,
    @SerializedName("deviceId") val deviceId: String? = null,
    @SerializedName("controlPort") val controlPort: Int? = null,
    @SerializedName("groupCode") val groupCode: String? = null,
    @SerializedName("appVersion") val appVersion: String? = null,
)

private data class HelloAckDto(
    @SerializedName("protocolVersion") val protocolVersion: Int? = null,
    @SerializedName("deviceName") val deviceName: String? = null,
    @SerializedName("deviceId") val deviceId: String? = null,
    @SerializedName("controlPort") val controlPort: Int? = null,
    @SerializedName("accepted") val accepted: Boolean? = null,
    @SerializedName("reason") val reason: String? = null,
)

private data class PairRequestDto(
    @SerializedName("code") val code: String? = null,
    @SerializedName("deviceName") val deviceName: String? = null,
    @SerializedName("deviceId") val deviceId: String? = null,
)

private data class PairResponseDto(
    @SerializedName("accepted") val accepted: Boolean? = null,
    @SerializedName("reason") val reason: String? = null,
)

private data class OfferedFileDto(
    @SerializedName("name") val name: String? = null,
    @SerializedName("relativePath") val relativePath: String? = null,
    @SerializedName("size") val size: Long? = null,
    @SerializedName("mime") val mime: String? = null,
)

private data class OfferDto(
    @SerializedName("transferId") val transferId: String? = null,
    @SerializedName("files") val files: List<OfferedFileDto>? = null,
    @SerializedName("totalBytes") val totalBytes: Long? = null,
    @SerializedName("message") val message: String? = null,
)

private data class OfferResponseDto(
    @SerializedName("transferId") val transferId: String? = null,
    @SerializedName("accepted") val accepted: Boolean? = null,
    @SerializedName("reason") val reason: String? = null,
)

private data class FileDigestDto(
    @SerializedName("name") val name: String? = null,
    @SerializedName("sha256") val sha256: String? = null,
)

private data class TransferCompleteDto(
    @SerializedName("transferId") val transferId: String? = null,
    @SerializedName("digests") val digests: List<FileDigestDto>? = null,
)

private data class TransferAckDto(
    @SerializedName("transferId") val transferId: String? = null,
    @SerializedName("ok") val ok: Boolean? = null,
    @SerializedName("verifiedFiles") val verifiedFiles: Int? = null,
    @SerializedName("failedFiles") val failedFiles: List<String>? = null,
    @SerializedName("message") val message: String? = null,
)

private data class CancelDto(
    @SerializedName("transferId") val transferId: String? = null,
    @SerializedName("reason") val reason: String? = null,
)

private data class ErrorDto(
    @SerializedName("code") val code: String? = null,
    @SerializedName("message") val message: String? = null,
)

private data class PingDto(
    @SerializedName("nonce") val nonce: Long? = null,
)

/**
 * Encodes and decodes control messages.
 *
 * Decoding is deliberately strict: unknown types, missing required fields, out-of-range values and
 * over-long strings all raise [ProtocolException] before anything touches the filesystem or a
 * socket. Truncation of string fields is applied where the value is cosmetic (device names,
 * messages); rejection is used where the value is structural (file names, sizes, ports).
 */
object MessageCodec {

    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .serializeNulls()
        .create()

    /** Serialises [message] into a single JSON string, ready to be framed. */
    fun encode(message: Message): String {
        val envelope = Envelope(type = message.type, data = gson.toJsonTree(message).asJsonObject)
        return gson.toJson(envelope)
    }

    /**
     * Parses a single JSON string into a [Message].
     *
     * @throws ProtocolException when the payload is not a JSON object, has no `type`, has an
     *         unknown `type`, or fails per-message validation.
     */
    fun decode(json: String): Message {
        val root: JsonElement = try {
            JsonParser.parseString(json)
        } catch (e: JsonParseException) {
            // JsonParseException is the common parent of JsonSyntaxException and JsonIOException, and
            // is also what Gson throws for pathologically nested input (StackOverflowError).
            throw ProtocolException("Malformed control payload: not valid JSON", e)
        }
        if (!root.isJsonObject) throw ProtocolException("Malformed control payload: expected a JSON object")

        val envelope = try {
            gson.fromJson(root, Envelope::class.java)
        } catch (e: JsonParseException) {
            throw ProtocolException("Malformed control envelope", e)
        } ?: throw ProtocolException("Empty control envelope")

        val type = envelope.type?.takeIf { it.isNotBlank() }
            ?: throw ProtocolException("Control envelope is missing its 'type' field")
        val data = envelope.data
            ?: throw ProtocolException("Control envelope '$type' carries no data")

        return when (type) {
            Hello.TYPE -> decodeHello(data)
            HelloAck.TYPE -> decodeHelloAck(data)
            PairRequest.TYPE -> decodePairRequest(data)
            PairResponse.TYPE -> decodePairResponse(data)
            Offer.TYPE -> decodeOffer(data)
            OfferResponse.TYPE -> decodeOfferResponse(data)
            TransferComplete.TYPE -> decodeTransferComplete(data)
            TransferAck.TYPE -> decodeTransferAck(data)
            Cancel.TYPE -> decodeCancel(data)
            ErrorMessage.TYPE -> decodeError(data)
            Ping.TYPE -> Ping(requireLong(data, "nonce", Ping.TYPE))
            Pong.TYPE -> Pong(requireLong(data, "nonce", Pong.TYPE))
            else -> throw ProtocolException("Unknown control message type: '$type'")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Per-message decoders
    // ---------------------------------------------------------------------------------------

    private fun decodeHello(data: JsonObject): Hello {
        val dto = adapt<HelloDto>(data, Hello.TYPE)
        return Hello(
            protocolVersion = requireInt(dto.protocolVersion, "protocolVersion", Hello.TYPE),
            deviceName = requireName(dto.deviceName, "deviceName", Hello.TYPE, Protocol.MAX_DEVICE_NAME_LENGTH),
            deviceId = requireName(dto.deviceId, "deviceId", Hello.TYPE, 128),
            controlPort = requirePort(dto.controlPort, "controlPort", Hello.TYPE),
            groupCode = dto.groupCode?.trim()?.take(64) ?: "",
            appVersion = dto.appVersion?.trim()?.take(32) ?: "",
        )
    }

    private fun decodeHelloAck(data: JsonObject): HelloAck {
        val dto = adapt<HelloAckDto>(data, HelloAck.TYPE)
        return HelloAck(
            protocolVersion = requireInt(dto.protocolVersion, "protocolVersion", HelloAck.TYPE),
            deviceName = requireName(dto.deviceName, "deviceName", HelloAck.TYPE, Protocol.MAX_DEVICE_NAME_LENGTH),
            deviceId = requireName(dto.deviceId, "deviceId", HelloAck.TYPE, 128),
            controlPort = requirePort(dto.controlPort, "controlPort", HelloAck.TYPE),
            accepted = dto.accepted ?: throw ProtocolException("${HelloAck.TYPE}: missing 'accepted'"),
            reason = dto.reason?.take(Protocol.MAX_MESSAGE_LENGTH),
        )
    }

    private fun decodePairRequest(data: JsonObject): PairRequest {
        val dto = adapt<PairRequestDto>(data, PairRequest.TYPE)
        val code = requireName(dto.code, "code", PairRequest.TYPE, 12)
        if (code.any { !it.isDigit() }) {
            throw ProtocolException("${PairRequest.TYPE}: 'code' must contain digits only")
        }
        return PairRequest(
            code = code,
            deviceName = requireName(dto.deviceName, "deviceName", PairRequest.TYPE, Protocol.MAX_DEVICE_NAME_LENGTH),
            deviceId = requireName(dto.deviceId, "deviceId", PairRequest.TYPE, 128),
        )
    }

    private fun decodePairResponse(data: JsonObject): PairResponse {
        val dto = adapt<PairResponseDto>(data, PairResponse.TYPE)
        return PairResponse(
            accepted = dto.accepted ?: throw ProtocolException("${PairResponse.TYPE}: missing 'accepted'"),
            reason = dto.reason?.take(Protocol.MAX_MESSAGE_LENGTH),
        )
    }

    private fun decodeOffer(data: JsonObject): Offer {
        val dto = adapt<OfferDto>(data, Offer.TYPE)
        val transferId = requireName(dto.transferId, "transferId", Offer.TYPE, 128)
        val rawFiles = dto.files
            ?: throw ProtocolException("${Offer.TYPE}: missing 'files'")
        if (rawFiles.isEmpty()) throw ProtocolException("${Offer.TYPE}: 'files' must not be empty")
        if (rawFiles.size > Protocol.MAX_FILES_PER_TRANSFER) {
            throw ProtocolException(
                "${Offer.TYPE}: too many files (${rawFiles.size} > ${Protocol.MAX_FILES_PER_TRANSFER})"
            )
        }

        val files = rawFiles.map { f ->
            val rawName = f.name ?: throw ProtocolException("${Offer.TYPE}: a file entry has no 'name'")
            // Sanitize rather than reject: the peer's name is untrusted, and the sanitizer is what
            // guarantees no path separator survives into the destination.
            val safeName = SafePaths.sanitizeFileName(rawName).take(Protocol.MAX_FILE_NAME_LENGTH)
            val size = f.size ?: throw ProtocolException("${Offer.TYPE}: file '$rawName' has no 'size'")
            if (size < 0) throw ProtocolException("${Offer.TYPE}: file '$rawName' has a negative size")
            OfferedFile(
                name = safeName,
                relativePath = SafePaths.sanitizeRelativePath(f.relativePath ?: safeName),
                size = size,
                mime = f.mime?.trim()?.take(128) ?: "application/octet-stream",
            )
        }

        val declaredTotal = dto.totalBytes
        if (declaredTotal != null && declaredTotal < 0) {
            throw ProtocolException("${Offer.TYPE}: negative 'totalBytes'")
        }
        // Trust the sum over the declared total: the manifest is the authoritative metadata. The sum
        // is overflow-checked: a hostile peer could otherwise offer two huge files whose sizes wrap to
        // a negative total and slip past the free-space check.
        var total = 0L
        for (file in files) {
            total = try {
                Math.addExact(total, file.size)
            } catch (e: ArithmeticException) {
                throw ProtocolException("${Offer.TYPE}: the total size of the files is out of range", e)
            }
        }

        return Offer(
            transferId = transferId,
            files = files,
            totalBytes = total,
            message = dto.message?.take(Protocol.MAX_MESSAGE_LENGTH),
        )
    }

    private fun decodeOfferResponse(data: JsonObject): OfferResponse {
        val dto = adapt<OfferResponseDto>(data, OfferResponse.TYPE)
        return OfferResponse(
            transferId = requireName(dto.transferId, "transferId", OfferResponse.TYPE, 128),
            accepted = dto.accepted ?: throw ProtocolException("${OfferResponse.TYPE}: missing 'accepted'"),
            reason = dto.reason?.take(Protocol.MAX_MESSAGE_LENGTH),
        )
    }

    private fun decodeTransferComplete(data: JsonObject): TransferComplete {
        val dto = adapt<TransferCompleteDto>(data, TransferComplete.TYPE)
        val raw = dto.digests ?: throw ProtocolException("${TransferComplete.TYPE}: missing 'digests'")
        if (raw.size > Protocol.MAX_FILES_PER_TRANSFER) {
            throw ProtocolException("${TransferComplete.TYPE}: too many digests")
        }
        val digests = raw.map { d ->
            val name = d.name ?: throw ProtocolException("${TransferComplete.TYPE}: a digest has no 'name'")
            val hash = d.sha256 ?: throw ProtocolException("${TransferComplete.TYPE}: digest '$name' has no 'sha256'")
            FileDigest(
                name = SafePaths.sanitizeFileName(name).take(Protocol.MAX_FILE_NAME_LENGTH),
                sha256 = hash.trim().lowercase(),
            )
        }
        return TransferComplete(
            transferId = requireName(dto.transferId, "transferId", TransferComplete.TYPE, 128),
            digests = digests,
        )
    }

    private fun decodeTransferAck(data: JsonObject): TransferAck {
        val dto = adapt<TransferAckDto>(data, TransferAck.TYPE)
        return TransferAck(
            transferId = requireName(dto.transferId, "transferId", TransferAck.TYPE, 128),
            ok = dto.ok ?: throw ProtocolException("${TransferAck.TYPE}: missing 'ok'"),
            verifiedFiles = dto.verifiedFiles ?: 0,
            failedFiles = (dto.failedFiles ?: emptyList())
                .take(Protocol.MAX_FILES_PER_TRANSFER)
                .map { SafePaths.sanitizeFileName(it) },
            message = dto.message?.take(Protocol.MAX_MESSAGE_LENGTH),
        )
    }

    private fun decodeCancel(data: JsonObject): Cancel {
        val dto = adapt<CancelDto>(data, Cancel.TYPE)
        return Cancel(
            transferId = dto.transferId?.trim()?.take(128) ?: "",
            reason = dto.reason?.take(Protocol.MAX_MESSAGE_LENGTH),
        )
    }

    private fun decodeError(data: JsonObject): ErrorMessage {
        val dto = adapt<ErrorDto>(data, ErrorMessage.TYPE)
        return ErrorMessage(
            code = dto.code?.trim()?.take(64) ?: "error",
            message = dto.message?.trim()?.take(Protocol.MAX_MESSAGE_LENGTH) ?: "The peer reported an error",
        )
    }

    // ---------------------------------------------------------------------------------------
    // Field helpers
    // ---------------------------------------------------------------------------------------

    private inline fun <reified T> adapt(data: JsonObject, type: String): T = try {
        gson.fromJson(data, T::class.java)
    } catch (e: JsonParseException) {
        throw ProtocolException("Malformed '$type' message", e)
    } ?: throw ProtocolException("Malformed '$type' message")

    private fun requireInt(value: Int?, field: String, type: String): Int =
        value ?: throw ProtocolException("$type: missing '$field'")

    private fun requireLong(data: JsonObject, field: String, type: String): Long {
        val element = data.get(field) ?: throw ProtocolException("$type: missing '$field'")
        return try {
            element.asLong
        } catch (e: NumberFormatException) {
            throw ProtocolException("$type: '$field' is not a number", e)
        } catch (e: UnsupportedOperationException) {
            throw ProtocolException("$type: '$field' is not a number", e)
        } catch (e: IllegalStateException) {
            // Gson throws this for a multi-element array where a number was expected.
            throw ProtocolException("$type: '$field' is not a number", e)
        }
    }

    /** Required non-blank, length-clamped string. */
    private fun requireName(value: String?, field: String, type: String, maxLength: Int): String {
        val trimmed = value?.trim()
        if (trimmed.isNullOrEmpty()) throw ProtocolException("$type: missing or blank '$field'")
        return trimmed.take(maxLength)
    }

    /** Ports must be in the usable range; port 0 is never a valid announced endpoint. */
    private fun requirePort(value: Int?, field: String, type: String): Int {
        val port = value ?: throw ProtocolException("$type: missing '$field'")
        if (port !in 1..65535) throw ProtocolException("$type: '$field' out of range: $port")
        return port
    }
}
