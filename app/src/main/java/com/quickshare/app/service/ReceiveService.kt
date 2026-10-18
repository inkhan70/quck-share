package com.quickshare.app.service

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.quickshare.app.QuickShareApp
import com.quickshare.app.R
import com.quickshare.app.core.ServiceLocator
import com.quickshare.app.data.AndroidSinkFactory
import com.quickshare.app.ui.ReceivePromptController
import com.quickshare.core.transfer.OfferSummary
import com.quickshare.core.transfer.SinkFactory
import com.quickshare.core.transfer.TransferDirection
import com.quickshare.core.transfer.TransferListener
import com.quickshare.core.transfer.TransferProgress
import com.quickshare.core.transfer.TransferRecord
import com.quickshare.core.util.Logs

/**
 * Holds the receiving socket open and bridges it to the UI.
 *
 * Runs as a foreground service so that a transfer continues while the user switches apps, which is
 * the normal case: people send a video and then go do something else. The wake lock is held only for
 * the duration of the service, so it cannot leak.
 *
 * The listener implementation is the interesting part. `onPairingRequested`, `onOfferReceived` and
 * `onDestinationRequired` are suspending, so the engine naturally waits for a human decision without
 * blocking a thread: the callbacks hand over to [ReceivePromptController] and suspend until the UI
 * answers.
 */
class ReceiveService : TransferServiceBase() {

    private var server: com.quickshare.core.transfer.TransferServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Logs.i(TAG, "Receive service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Logs.i(TAG, "Receive service asked to stop")
                stopReceiver()
                return START_NOT_STICKY
            }
            else -> startReceiver()
        }
        // START_STICKY is used so that a receiver the user switched on resumes after the system
        // reclaims the process; it is cheap because the socket is simply re-bound.
        return START_STICKY
    }

    private fun startReceiver() {
        // Enter the foreground FIRST, on every start. Android requires startForeground() within a few
        // seconds of every startForegroundService() call; the early "already running" return below
        // used to skip it, which crashes the app with ForegroundServiceDidNotStartInTimeException.
        goForeground(QuickShareApp.NOTIFICATION_ID_READY, readyNotification())

        if (server?.isRunning() == true) {
            Logs.d(TAG, "Receiver already running")
            return
        }

        val sinkFactory: SinkFactory = AndroidSinkFactory(this, ServiceLocator.documentRegistry)

        val listener = object : TransferListener {

            override fun onProgress(progress: TransferProgress) {
                acquireWakeLock()
                ServiceLocator.coordinator().publishReceiveProgress(progress)
                // Re-read per tick rather than capturing at start: the user can change these
                // settings while a transfer is running, and a captured copy would ignore them.
                if (ServiceLocator.settings().notifyOnTransfer != false) {
                    post(
                        QuickShareApp.NOTIFICATION_ID_TRANSFER,
                        buildProgressNotification(progress, peerName = ""),
                    )
                }
            }

            override suspend fun onPairingRequested(peerName: String, code: String): Boolean {
                // When the user has turned pairing confirmation off, we accept automatically - but only
                // because they explicitly asked for that convenience.
                if (ServiceLocator.settings().requirePairingCode == false) {
                    Logs.i(TAG, "Auto-accepting pairing: code checking is disabled in Settings")
                    return true
                }
                postPromptNotification(
                    getString(R.string.notif_pair_request),
                    getString(R.string.notif_pair_request_text, peerName),
                )
                try {
                    return ReceivePromptController.awaitPairingDecision(peerName, code)
                } finally {
                    clearPromptNotification()
                }
            }

            override suspend fun onOfferReceived(peerName: String, offer: OfferSummary): Boolean {
                val trusted = ServiceLocator.settings().autoAcceptFromKnownDevices == true &&
                    ServiceLocator.coordinator().hasTradedWith(peerName)
                if (trusted) {
                    Logs.i(TAG, "Auto-accepting offer from known device $peerName")
                    return true
                }
                postPromptNotification(
                    getString(R.string.notif_offer_request),
                    getString(R.string.notif_offer_request_text, peerName, offer.fileCount),
                )
                try {
                    return ReceivePromptController.awaitOfferDecision(peerName, offer)
                } finally {
                    clearPromptNotification()
                }
            }

            override suspend fun onDestinationRequired(): String? {
                val folder = ServiceLocator.documentRegistry.ensureTargetFolder()
                if (folder != null) return folder.toString()
                // No folder yet: ask the user. The prompt returns null if they back out, which the
                // engine turns into a clean rejection with a visible reason.
                return ReceivePromptController.awaitDestinationChoice()
            }

            override fun onFinished(record: TransferRecord) {
                ServiceLocator.coordinator().recordReceived(record)
                Logs.i(TAG, "Receive finished: ${record.status}")
                // The receiver must keep running: the user's "Receive files" switch is still on, and
                // stopping the service here (as this used to) shut the socket after the FIRST transfer
                // so every later one failed until the switch was toggled. Only the per-transfer
                // notification and the wake lock go away.
                if (record.status.isTerminal) {
                    releaseWakeLock()
                    try {
                        notificationManager.cancel(QuickShareApp.NOTIFICATION_ID_TRANSFER)
                    } catch (e: Exception) {
                        Logs.d(TAG, "Could not clear the progress notification: ${e.message}")
                    }
                }
            }
        }

        val newServer = ServiceLocator.transferServer(sinkFactory, listener)
        server = newServer
        newServer.start {
            // Gate: only accept while the user's receive switch is on.
            ServiceLocator.settings().receiveEnabled != false
        }

    }

    private fun readyNotification() =
        androidx.core.app.NotificationCompat.Builder(this, QuickShareApp.CHANNEL_TRANSFER)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.notif_ready))
            .setContentText(getString(R.string.home_receive_on))
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .build()

    /**
     * Holds the CPU awake, but only while bytes are actually flowing.
     *
     * It used to be taken when the receiver started and held for up to three hours of idle waiting,
     * which is a pointless battery drain; the first progress tick of a transfer now takes it and the
     * end of the transfer releases it.
     */
    private fun acquireWakeLock() {
        if (ServiceLocator.settings().keepScreenOnDuringTransfer == false) return
        if (wakeLock?.isHeld == true) return
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuickShare::receive").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Logs.w(TAG, "Could not acquire a wake lock; the transfer may pause in deep sleep", e)
        }
    }

    private fun stopReceiver() {
        try {
            server?.stop()
        } catch (e: Exception) {
            Logs.w(TAG, "Error stopping the receiver", e)
        }
        server = null
        // Forget the cached server: it references this (about to be destroyed) service through its
        // listener, so a later receive must build a new one.
        ServiceLocator.discardTransferServer()
        releaseWakeLock()
        clearPromptNotification()
        finishService()
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Logs.d(TAG, "Wake lock release failed: ${e.message}")
        }
        wakeLock = null
    }

    override fun onDestroy() {
        stopReceiver()
        super.onDestroy()
        Logs.i(TAG, "Receive service destroyed")
    }

    companion object {
        private const val TAG = "ReceiveService"
        private const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L // 3 hours, a hard ceiling.
        const val ACTION_STOP = "com.quickshare.app.action.STOP_RECEIVE"

        fun start(context: Context) {
            TransferServiceBase.Factory.start(
                context,
                Intent(context, ReceiveService::class.java),
                ReceiveService::class.java,
            )
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, ReceiveService::class.java).setAction(ACTION_STOP),
                )
            } catch (e: Exception) {
                Logs.w("ReceiveService", "Could not send stop to the receiver", e)
            }
        }
    }
}
