package com.quickshare.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.quickshare.core.pairing.PairingPayload
import com.quickshare.core.util.Logs

/**
 * Renders a pairing QR code.
 *
 * Generated on device from the same [PairingPayload] URI the scanner parses, so the two halves cannot
 * drift: if the encoder and decoder disagreed, scanning our own code would fail, which is exactly the
 * kind of bug a QR feature is prone to.
 *
 * Error correction is set to M (~15% recoverable) because the code is read off a bright phone screen
 * at close range; the higher levels buy nothing here and make the modules denser.
 */
object QrCode {

    private const val TAG = "QrCode"

    /**
     * @param content the payload to encode (a `quickshare://pair?...` URI).
     * @param sizePx edge length of the produced bitmap.
     * @return the bitmap, or null when encoding failed (an over-long payload, or no encoder): the
     *         caller shows a fallback rather than crashing, and the manual-IP path is always
     *         available, so a missing QR is never a dead end.
     */
    fun generate(content: String, sizePx: Int): Bitmap? {
        if (content.isBlank() || sizePx <= 0) return null
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix = MultiFormatWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                hints,
            )
            val pixels = IntArray(matrix.width * matrix.height)
            for (y in 0 until matrix.height) {
                val rowOffset = y * matrix.width
                for (x in 0 until matrix.width) {
                    pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
            }
        } catch (e: Exception) {
            // A QR code is a convenience; its failure must never block the transfer path.
            Logs.w(TAG, "Could not generate a QR code", e)
            null
        }
    }

    /**
     * Builds the payload for this device, or null when there is no usable local address (no Wi-Fi).
     */
    fun payloadFor(
        host: String?,
        port: Int,
        code: String,
        deviceName: String,
    ): String? {
        if (host.isNullOrBlank()) return null
        return try {
            PairingPayload.encode(host, port, code, deviceName)
        } catch (e: IllegalArgumentException) {
            Logs.w(TAG, "Could not build a pairing payload for $host", e)
            null
        }
    }
}
