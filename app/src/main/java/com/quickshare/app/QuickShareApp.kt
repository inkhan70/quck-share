package com.quickshare.app

import android.app.Application
import android.app.NotificationChannel
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.quickshare.app.core.ServiceLocator
import com.quickshare.app.util.AndroidLogSink
import com.quickshare.core.data.Settings
import com.quickshare.core.util.Logs

/**
 * Application entry point.
 *
 * Two things happen here that must happen exactly once and before any screen is created:
 * 1. the service locator is initialised (it owns the sockets and the settings), and
 * 2. the theme preference is applied to [AppCompatDelegate], so the very first frame already has the
 *    right colours instead of flashing the system default and then switching.
 */
class QuickShareApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Install the log sink ONLY in debug builds. In a release build the sink stays at its no-op
        // default, so nothing the app does can emit a log line - no debug logging ships.
        if (BuildConfig.DEBUG) {
            Logs.sink = AndroidLogSink()
        }

        // Startup must never be able to kill the process. An exception thrown here is not a
        // recoverable error - it is an immediate crash on launch, before the user can reach any
        // screen. Each step is therefore isolated, so a broken preference file or an unavailable
        // service degrades the app instead of bricking it.
        runCatching { ServiceLocator.init(this) }
            .onFailure { Logs.e(TAG, "Service locator initialisation failed", it) }
        runCatching { createNotificationChannels(this) }
            .onFailure { Logs.e(TAG, "Could not create the notification channels", it) }
        runCatching { applyStoredTheme() }
            .onFailure { Logs.e(TAG, "Could not apply the stored theme", it) }
    }

    /**
     * Applies the saved theme before the first activity is shown.
     *
     * `AppCompatDelegate.setDefaultNightMode` is used rather than recreating activities, because it
     * is the only way to influence a window that has not been created yet.
     */
    private fun applyStoredTheme() {
        val mode = ServiceLocator.settingsRepository
            .load(ServiceLocator.deviceId(), ServiceLocator.defaultDeviceName())
            .darkMode
            ?: Settings.DARK_MODE_SYSTEM

        val nightMode = when (mode) {
            Settings.DARK_MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            Settings.DARK_MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }

    companion object {
        private const val TAG = "QuickShareApp"

        const val CHANNEL_TRANSFER = "quickshare_transfer"
        const val CHANNEL_PROMPTS = "quickshare_prompts"

        /** Notification ID for the persistent "ready to receive" notification. */
        const val NOTIFICATION_ID_READY = 1001
        const val NOTIFICATION_ID_TRANSFER = 1002
        const val NOTIFICATION_ID_PROMPT = 1003

        /**
         * Creates the notification channels.
         *
         * Idempotent: Android ignores a repeat creation, so calling this on every launch is safe and
         * avoids the "channel missing" race that a lazily-created channel would have.
         */
        fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return

            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_TRANSFER,
                    context.getString(R.string.notif_channel_transfer_name),
                    android.app.NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notif_channel_transfer_desc)
                    setShowBadge(false)
                    enableVibration(false)
                },
            )

            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PROMPTS,
                    context.getString(R.string.notif_channel_prompt_name),
                    android.app.NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.notif_channel_prompt_desc)
                    enableVibration(true)
                },
            )
        }
    }
}
