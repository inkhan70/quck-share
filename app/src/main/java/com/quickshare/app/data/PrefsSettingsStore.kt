package com.quickshare.app.data

import android.content.Context
import com.quickshare.core.data.Settings
import com.quickshare.core.data.SettingsStore
import com.quickshare.core.util.Logs

/**
 * [SettingsStore] backed by [android.content.SharedPreferences].
 *
 * Settings are stored as individual keys rather than one JSON blob. That choice is what makes a
 * forward-compatible upgrade safe: a key written by a newer build is simply ignored by an older one,
 * instead of making the whole blob unparseable.
 */
class PrefsSettingsStore(context: Context, name: String) : SettingsStore {

    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun load(): Settings? {
        if (prefs.all.isEmpty()) return null
        return Settings(
            deviceName = prefs.getString(KEY_DEVICE_NAME, null),
            deviceId = prefs.getString(KEY_DEVICE_ID, null),
            receiveEnabled = prefs.getBooleanOrNull(KEY_RECEIVE_ENABLED),
            autoAcceptFromKnownDevices = prefs.getBooleanOrNull(KEY_AUTO_ACCEPT),
            requirePairingCode = prefs.getBooleanOrNull(KEY_REQUIRE_PAIRING),
            saveLocation = prefs.getString(KEY_SAVE_LOCATION, null),
            saveLocationLabel = prefs.getString(KEY_SAVE_LOCATION_LABEL, null),
            darkMode = prefs.getString(KEY_DARK_MODE, null),
            saveImagesToGallery = prefs.getBooleanOrNull(KEY_SAVE_GALLERY),
            keepScreenOnDuringTransfer = prefs.getBooleanOrNull(KEY_KEEP_AWAKE),
            notifyOnTransfer = prefs.getBooleanOrNull(KEY_NOTIFY),
            knownDeviceIds = prefs.getStringSet(KEY_KNOWN_DEVICES, null)?.toList(),
        )
    }

    override fun save(settings: Settings) {
        try {
            prefs.edit().apply {
                settings.deviceName?.let { putString(KEY_DEVICE_NAME, it) }
                settings.deviceId?.let { putString(KEY_DEVICE_ID, it) }
                settings.receiveEnabled?.let { putBoolean(KEY_RECEIVE_ENABLED, it) }
                settings.autoAcceptFromKnownDevices?.let { putBoolean(KEY_AUTO_ACCEPT, it) }
                settings.requirePairingCode?.let { putBoolean(KEY_REQUIRE_PAIRING, it) }
                // A null save location is meaningful ("not chosen yet"), so it is stored as removal.
                if (settings.saveLocation == null) remove(KEY_SAVE_LOCATION) else putString(KEY_SAVE_LOCATION, settings.saveLocation)
                if (settings.saveLocationLabel == null) remove(KEY_SAVE_LOCATION_LABEL) else putString(KEY_SAVE_LOCATION_LABEL, settings.saveLocationLabel)
                settings.darkMode?.let { putString(KEY_DARK_MODE, it) }
                settings.saveImagesToGallery?.let { putBoolean(KEY_SAVE_GALLERY, it) }
                settings.keepScreenOnDuringTransfer?.let { putBoolean(KEY_KEEP_AWAKE, it) }
                settings.notifyOnTransfer?.let { putBoolean(KEY_NOTIFY, it) }
                settings.knownDeviceIds?.let { putStringSet(KEY_KNOWN_DEVICES, it.toSet()) }
            }.apply()
        } catch (e: Exception) {
            Logs.w(TAG, "Could not write settings", e)
            throw e
        }
    }

    private fun android.content.SharedPreferences.getBooleanOrNull(key: String): Boolean? =
        if (contains(key)) getBoolean(key, false) else null

    private companion object {
        const val TAG = "PrefsSettingsStore"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_RECEIVE_ENABLED = "receive_enabled"
        const val KEY_AUTO_ACCEPT = "auto_accept_known"
        const val KEY_REQUIRE_PAIRING = "require_pairing"
        const val KEY_SAVE_LOCATION = "save_location"
        const val KEY_SAVE_LOCATION_LABEL = "save_location_label"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_SAVE_GALLERY = "save_images_gallery"
        const val KEY_KEEP_AWAKE = "keep_awake"
        const val KEY_NOTIFY = "notify_transfers"
        const val KEY_KNOWN_DEVICES = "known_devices"
    }
}
