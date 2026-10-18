package com.quickshare.core.data

import com.google.gson.annotations.SerializedName

/**
 * All user preferences.
 *
 * Every field is nullable with a sane default so that a settings file written by an older or newer
 * version still loads: a missing key becomes null and is then replaced by the default, rather than
 * failing the whole load and losing every other setting.
 */
data class Settings(
    @SerializedName("deviceName") val deviceName: String? = null,
    @SerializedName("deviceId") val deviceId: String? = null,
    @SerializedName("receiveEnabled") val receiveEnabled: Boolean? = null,
    @SerializedName("autoAcceptFromKnownDevices") val autoAcceptFromKnownDevices: Boolean? = null,
    @SerializedName("requirePairingCode") val requirePairingCode: Boolean? = null,
    @SerializedName("saveLocation") val saveLocation: String? = null,
    @SerializedName("saveLocationLabel") val saveLocationLabel: String? = null,
    @SerializedName("darkMode") val darkMode: String? = null,
    @SerializedName("saveImagesToGallery") val saveImagesToGallery: Boolean? = null,
    @SerializedName("keepScreenOnDuringTransfer") val keepScreenOnDuringTransfer: Boolean? = null,
    @SerializedName("notifyOnTransfer") val notifyOnTransfer: Boolean? = null,
    @SerializedName("knownDeviceIds") val knownDeviceIds: List<String>? = null,
) {

    companion object {
        const val DARK_MODE_SYSTEM = "system"
        const val DARK_MODE_LIGHT = "light"
        const val DARK_MODE_DARK = "dark"

        /** The values `darkMode` is allowed to take; anything else falls back to `system`. */
        val VALID_DARK_MODES = setOf(DARK_MODE_SYSTEM, DARK_MODE_LIGHT, DARK_MODE_DARK)

        /** The defaults a first-run install starts from. */
        fun defaults(deviceId: String, deviceName: String) = Settings(
            deviceName = deviceName,
            deviceId = deviceId,
            receiveEnabled = true,
            autoAcceptFromKnownDevices = false,
            requirePairingCode = true,
            saveLocation = null,
            saveLocationLabel = null,
            darkMode = DARK_MODE_SYSTEM,
            saveImagesToGallery = true,
            keepScreenOnDuringTransfer = true,
            notifyOnTransfer = true,
            knownDeviceIds = emptyList(),
        )
    }

    /** Fills in every null with its default, so callers never deal with nullable preferences. */
    fun withDefaults(deviceId: String, deviceName: String): Settings {
        val base = defaults(deviceId, deviceName)
        return Settings(
            // `this.` is required on both: without it the name resolves to the *parameter* (the
            // caller's fallback) instead of the stored preference, so a saved device name was
            // silently discarded on every load and replaced by the default.
            deviceName = safeDeviceName(this.deviceName.orIfBlank(base.deviceName)),
            deviceId = this.deviceId.orIfBlank(base.deviceId),
            receiveEnabled = receiveEnabled ?: base.receiveEnabled,
            autoAcceptFromKnownDevices = autoAcceptFromKnownDevices ?: base.autoAcceptFromKnownDevices,
            requirePairingCode = requirePairingCode ?: base.requirePairingCode,
            saveLocation = saveLocation,
            saveLocationLabel = saveLocationLabel,
            darkMode = darkMode?.takeIf { it in VALID_DARK_MODES } ?: base.darkMode,
            saveImagesToGallery = saveImagesToGallery ?: base.saveImagesToGallery,
            keepScreenOnDuringTransfer = keepScreenOnDuringTransfer ?: base.keepScreenOnDuringTransfer,
            notifyOnTransfer = notifyOnTransfer ?: base.notifyOnTransfer,
            knownDeviceIds = (knownDeviceIds ?: emptyList()).distinct().take(500),
        )
    }

    fun withDarkMode(mode: String): Settings =
        copy(darkMode = if (mode in VALID_DARK_MODES) mode else DARK_MODE_SYSTEM)

    fun rememberDevice(deviceId: String): Settings {
        val current = knownDeviceIds ?: emptyList()
        return if (deviceId in current) this else copy(knownDeviceIds = (current + deviceId).distinct().take(500))
    }

    fun forgetDevice(deviceId: String): Settings =
        copy(knownDeviceIds = (knownDeviceIds ?: emptyList()).filterNot { it == deviceId })

    private fun safeDeviceName(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return "QuickShare device"
        return trimmed.take(com.quickshare.core.protocol.Protocol.MAX_DEVICE_NAME_LENGTH)
    }

    private fun String?.orIfBlank(fallback: String?): String? =
        if (this.isNullOrBlank()) fallback else this

    val isDarkModeFixed: Boolean get() = darkMode == DARK_MODE_DARK || darkMode == DARK_MODE_LIGHT
}
