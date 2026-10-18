package com.quickshare.app.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickshare.app.ui.theme.QuickShareTheme
import com.quickshare.core.pairing.PairingPayload
import com.quickshare.core.util.Logs

/**
 * The single activity.
 *
 * One activity and a Compose tree rather than several activities: the app is three tabs and a modal
 * send flow, so separate activities would add task-switching edges (a transfer running in a second
 * activity's process state) for no benefit.
 *
 * `singleTask` is set in the manifest so a scanned QR deep link that arrives while the app is already
 * open lands on this instance and is delivered through [onNewIntent], instead of starting a second
 * copy that would fight the first over the receive socket.
 */
class MainActivity : ComponentActivity() {

    private companion object {
        const val TAG = "MainActivity"
    }

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val theme = ThemeOption.from(settings.darkMode)
            val dark = when (theme) {
                ThemeOption.LIGHT -> false
                ThemeOption.DARK -> true
                ThemeOption.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            // POST_NOTIFICATIONS is a runtime permission from API 33 onward, and nothing on Android
            // grants it implicitly. Without it every transfer notification is silently dropped: the
            // transfer still runs, but the persistent progress bar the app promises never appears, and
            // the user has no way to see that a receive is waiting for them from outside the app.
            // It is asked for once, on first composition, and a refusal is reported rather than
            // ignored - the app stays fully usable either way.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (!granted) viewModel.showNotificationsDisabledNotice()
                }
                LaunchedEffect(Unit) {
                    val granted = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            QuickShareTheme(darkTheme = dark) {
                AppShell(viewModel)
            }
        }

        // A deep link that started the activity is handled here; one that arrives later goes through
        // onNewIntent. Both funnel into the same parser.
        consumePairingIntent(intent?.data?.toString())
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumePairingIntent(intent.data?.toString())
    }

    /**
     * Turns a `quickshare://pair?...` URI into a staged peer.
     *
     * The payload is untrusted input from outside the app, so it is parsed strictly and a malformed
     * code is reported rather than partially applied.
     */
    private fun consumePairingIntent(raw: String?) {
        if (raw.isNullOrBlank()) return
        // A deep link is untrusted input from outside the app, and it is handled during onCreate,
        // so a malformed one must be ignored rather than allowed to crash the launch.
        val payload = runCatching { PairingPayload.decode(raw) }.getOrNull()
        if (payload == null) {
            Logs.w(TAG, "Ignoring an unrecognised pairing link")
            return
        }
        runCatching { viewModel.applyPairingPayload(payload) }
            .onFailure { Logs.w(TAG, "Could not apply the pairing link", it) }
    }

    override fun onDestroy() {
        // The ViewModel is retained across configuration changes, so it must not be the thing that
        // clears pending prompts on a mere rotation. This only runs when the activity is really
        // finishing, and leaving a prompt parked would strand the sender's socket.
        if (isFinishing) {
            ReceivePromptController.cancelAll()
        }
        super.onDestroy()
    }
}
