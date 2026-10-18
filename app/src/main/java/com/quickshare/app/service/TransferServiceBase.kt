package com.quickshare.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.quickshare.app.QuickShareApp
import com.quickshare.app.R
import com.quickshare.app.ui.MainActivity
import com.quickshare.core.transfer.TransferDirection
import com.quickshare.core.transfer.TransferProgress
import com.quickshare.core.util.ByteFormat
import com.quickshare.core.util.Logs

/**
 * Shared notification plumbing for the send and receive services.
 *
 * Progress notifications are built here rather than in each service so the two directions cannot
 * drift apart visually, and so the "is this a determinate bar or an indeterminate spinner" decision
 * is made in exactly one place.
 *
 * Note that [onBind] returns null: neither service is bound to, because both are started to hold a
 * foreground priority rather than to expose an interface.
 */
abstract class TransferServiceBase : Service() {

    protected val notificationManager by lazy { NotificationManagerCompat.from(this) }

    /** True when notifications can be posted; on API 33+ the user may have denied the permission. */
    protected fun canNotify(): Boolean = try {
        notificationManager.areNotificationsEnabled()
    } catch (e: Exception) {
        false
    }

    /** Tapping the notification returns to the running app rather than starting a second copy. */
    protected fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Builds the progress notification.
     *
     * Deliberately not a spinner when the total size is known: a byte-accurate bar is what lets the
     * user decide whether to wait or to keep the screen on.
     */
    protected fun buildProgressNotification(progress: TransferProgress, peerName: String): Notification {
        val isSend = progress.direction == TransferDirection.SEND
        val title = getString(if (isSend) R.string.notif_sending else R.string.notif_receiving)

        val speedAndEta = buildString {
            append(ByteFormat.speed(progress.bytesPerSecond))
            val eta = progress.etaSeconds
            if (eta > 0) append(" \u00b7 ").append(ByteFormat.duration(eta)).append(" left")
        }

        val text = "${progress.fileName} \u00b7 ${progress.percent}% \u00b7 $speedAndEta"

        val builder = NotificationCompat.Builder(this, QuickShareApp.CHANNEL_TRANSFER)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(if (peerName.isBlank()) title else "$title \u2014 $peerName")
            .setContentText(text)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (progress.isIndeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, progress.percent, false)
            builder.setSubText(
                "${ByteFormat.size(progress.bytesTransferred)} of ${ByteFormat.size(progress.totalBytes)}",
            )
        }
        return builder.build()
    }

    /**
     * Alerts the user that a nearby device is asking for something.
     *
     * The pairing and offer dialogs are drawn by the activity, so when QuickShare is in the
     * background nothing was visible and the request silently timed out after 90 seconds. This
     * high-priority notification brings the user back to the app to answer it.
     */
    protected fun postPromptNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, QuickShareApp.CHANNEL_PROMPTS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(false)
            // The request is abandoned after this long anyway; do not leave a dead prompt behind.
            .setTimeoutAfter(com.quickshare.app.ui.ReceivePromptController.DEFAULT_TIMEOUT_MS)
            .build()
        post(QuickShareApp.NOTIFICATION_ID_PROMPT, notification)
    }

    protected fun clearPromptNotification() {
        try {
            notificationManager.cancel(QuickShareApp.NOTIFICATION_ID_PROMPT)
        } catch (e: Exception) {
            Logs.d(TAG, "Could not clear the prompt notification: ${e.message}")
        }
    }

    protected fun post(id: Int, notification: Notification) {
        if (!canNotify()) {
            // Never fatal: the transfer continues, the user just sees progress inside the app.
            return
        }
        // On API 33+ posting without POST_NOTIFICATIONS throws SecurityException. It is caught
        // below, but checking first keeps the log clean and makes the intent explicit.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            notificationManager.notify(id, notification)
        } catch (e: SecurityException) {
            Logs.w(TAG, "Notification permission missing; progress stays in-app", e)
        } catch (e: Exception) {
            Logs.w(TAG, "Could not post notification $id", e)
        }
    }

    /** Promotes the service to the foreground, tolerating the API 34 typed-service requirement. */
    protected fun goForeground(id: Int, notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    id,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(id, notification)
            }
        } catch (e: Exception) {
            // On API 34 a foreground service can be refused if the app is in the background; the
            // transfer still proceeds, so this is logged rather than thrown.
            Logs.w(TAG, "Could not enter the foreground; continuing in the background", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Stops the foreground state and the service together. */
    protected fun finishService() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Logs.d(TAG, "stopForeground failed: ${e.message}")
        }
        stopSelf()
    }

    /**
     * The shared tag and the launcher used by both concrete services.
     *
     * A single companion object: Kotlin allows exactly one per class, so the tag and [start] live
     * together rather than in two competing companions.
     */
    companion object Factory {
        const val TAG = "TransferService"

        /** Starts a service in a way that works from both the foreground and the background. */
        fun start(context: Context, intent: Intent, clazz: Class<out Service>) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Android 12+ can throw ForegroundServiceStartNotAllowedException. Falling back to a
                // plain start lets the transfer still run while the app is visible.
                Logs.w(TAG, "Foreground start refused; falling back to startService", e)
                try {
                    context.startService(intent)
                } catch (inner: Exception) {
                    Logs.e(TAG, "Could not start ${clazz.simpleName}", inner)
                }
            }
        }
    }
}
