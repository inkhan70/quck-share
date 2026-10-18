package com.quickshare.app.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.quickshare.app.QuickShareApp
import com.quickshare.app.R
import com.quickshare.app.core.ServiceLocator
import com.quickshare.core.util.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Holds the foreground notification for outbound transfers.
 *
 * The bytes are written by the process (the coordinator owns the socket), so this service exists for
 * one reason: to give a running send the foreground priority it needs in order to survive the user
 * switching apps or the system reclaiming background CPU. That matters most exactly when it is least
 * noticeable - a multi-gigabyte video, where the screen is off and the app is not the foreground task.
 *
 * It discovers what to display by observing [com.quickshare.core.transfer.TransferCoordinator.activeSends]
 * rather than receiving the files through an Intent, so no content URI is ever marshalled across a
 * Binder boundary and the notification can never disagree with the in-app progress bar.
 */
class SendService : TransferServiceBase() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watching: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Logs.i(TAG, "Send service asked to cancel everything")
            ServiceLocator.coordinator().cancelAll()
            finishService()
            return START_NOT_STICKY
        }

        goForeground(QuickShareApp.NOTIFICATION_ID_TRANSFER, placeholderNotification())
        watchSends()
        return START_STICKY
    }

    /**
     * Mirrors the coordinator's sends into the notification, and stops when there are none left.
     *
     * "Is anything still running" is read from the coordinator's job table, not inferred from progress
     * events. Progress only starts once the receiver has accepted, which can take a person tens of
     * seconds; the previous logic stopped the service after four seconds without progress and so
     * dropped the foreground priority exactly while the sender was waiting on that human.
     */
    private fun watchSends() {
        if (watching?.isActive == true) return
        watching = scope.launch {
            val coordinator = ServiceLocator.coordinator()
            val startedAt = android.os.SystemClock.elapsedRealtime()

            while (isActive) {
                val running = coordinator.activeCount() > 0
                val first = coordinator.activeSends.value.values.firstOrNull()
                when {
                    first != null && !first.isIndeterminate ->
                        post(QuickShareApp.NOTIFICATION_ID_TRANSFER, buildProgressNotification(first, ""))
                    running ->
                        post(QuickShareApp.NOTIFICATION_ID_TRANSFER, placeholderNotification())
                }
                // Grace period: the service may be started a moment before the send is registered.
                if (!running && android.os.SystemClock.elapsedRealtime() - startedAt > GRACE_PERIOD_MS) {
                    Logs.i(TAG, "No sends running; stopping the send service")
                    finishService()
                    break
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * The standing notification shown before byte-level progress exists.
     *
     * Indeterminate on purpose: showing "0%" for a transfer that is still negotiating the handshake
     * reads as "stuck", whereas a spinner reads as "working", which is what is happening.
     */
    private fun placeholderNotification(): Notification =
        NotificationCompat.Builder(this, QuickShareApp.CHANNEL_TRANSFER)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.notif_sending))
            .setContentText(getString(R.string.transfer_background_hint))
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .build()

    override fun onDestroy() {
        watching?.cancel()
        watching = null
        scope.cancel()
        super.onDestroy()
        Logs.i(TAG, "Send service destroyed")
    }

    companion object {
        private const val TAG = "SendService"
        private const val GRACE_PERIOD_MS = 4_000L
        private const val POLL_INTERVAL_MS = 500L
        const val ACTION_STOP = "com.quickshare.app.action.STOP_SEND"

        fun start(context: Context) {
            TransferServiceBase.Factory.start(
                context,
                Intent(context, SendService::class.java),
                SendService::class.java,
            )
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, SendService::class.java).setAction(ACTION_STOP),
                )
            } catch (e: Exception) {
                Logs.w(TAG, "Could not send stop to the send service", e)
            }
        }
    }
}
