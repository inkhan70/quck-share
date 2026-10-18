package com.quickshare.core.data

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.quickshare.core.util.Logs

/**
 * Persistence port for [Settings].
 *
 * Kept as an interface so the core stays free of Android APIs and so tests can use the in-memory
 * implementation. On Android this is backed by `SharedPreferences`.
 */
interface SettingsStore {
    fun load(): Settings?
    fun save(settings: Settings)
}

/** In-memory store used by tests and as a fallback when persistence is unavailable. */
class InMemorySettingsStore(private var settings: Settings? = null) : SettingsStore {
    override fun load(): Settings? = settings
    override fun save(settings: Settings) {
        this.settings = settings
    }
}

/**
 * Typed accessor over a [SettingsStore] that hides JSON entirely.
 *
 * Load failures are non-fatal by design: a corrupt settings blob must not brick the app, so the
 * call falls back to defaults and (when [onCorrupt] is supplied) reports the problem so the user can
 * be told. This is directly covered by a unit test.
 */
class SettingsRepository(
    private val store: SettingsStore,
    private val gson: Gson = Gson(),
    private val onCorrupt: (String) -> Unit = {},
) {

    private companion object {
        const val TAG = "SettingsRepository"
    }

    /**
     * Loads and normalizes settings.
     *
     * @return the settings, never null: defaults are substituted for anything missing or unreadable.
     */
    fun load(deviceId: String, deviceName: String): Settings {
        val raw = try {
            store.load()
        } catch (e: Exception) {
            Logs.w(TAG, "Settings store read failed", e)
            onCorrupt("Could not read saved settings: ${e.message}")
            null
        } ?: return Settings.defaults(deviceId, deviceName)

        val normalized = raw.withDefaults(deviceId, deviceName)
        // Persist the normalization once so a partially-written file is repaired immediately.
        if (normalized != raw) {
            persistQuietly(normalized)
        }
        return normalized
    }

    fun save(settings: Settings) {
        persistQuietly(settings)
    }

    private fun persistQuietly(settings: Settings) {
        try {
            store.save(settings)
        } catch (e: Exception) {
            // A failed write must not crash the caller; the in-memory value is still authoritative
            // for this session, and the user is informed through the callback.
            Logs.w(TAG, "Settings store write failed", e)
            onCorrupt("Could not save settings: ${e.message}")
        }
    }

    /** Serializes settings to JSON. Exposed for the Android `SharedPreferences` adapter. */
    fun toJson(settings: Settings): String = gson.toJson(settings)

    /**
     * Parses settings JSON.
     *
     * @return null when the payload is unparseable, which the caller treats as "no settings yet".
     */
    fun fromJson(json: String?): Settings? {
        if (json.isNullOrBlank()) return null
        return try {
            gson.fromJson(json, Settings::class.java)
        } catch (e: JsonSyntaxException) {
            Logs.w(TAG, "Stored settings are not valid JSON", e)
            onCorrupt("Saved settings were unreadable and have been reset.")
            null
        }
    }
}
