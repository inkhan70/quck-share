package com.quickshare.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickshare.app.R
import com.quickshare.app.core.ServiceLocator
import com.quickshare.app.ui.theme.CodeFont
import com.quickshare.core.util.ByteFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders every blocking question and dialog the app can raise.
 *
 * Collected in one composable so the received-transfer prompts (which arrive from a service thread
 * and suspend the engine) and the pairing/QR dialogs cannot both be on screen at once - overlapping
 * dialogs are the classic way an accept tap lands on the wrong prompt.
 */
@Composable
fun PromptHost(viewModel: MainViewModel) {
    val prompt by viewModel.prompt.collectAsStateWithLifecycle()
    val showQr by viewModel.showQr.collectAsStateWithLifecycle()
    val showManual by viewModel.showManualConnect.collectAsStateWithLifecycle()

    when (val current = prompt) {
        is ReceivePrompt.Pairing -> PairingDialog(current)
        is ReceivePrompt.Offer -> OfferDialog(current)
        ReceivePrompt.Destination -> DestinationDialog(viewModel)
        null -> Unit
    }

    if (showQr) QrDialog(viewModel)
    if (showManual) ManualConnectDialog(viewModel)
}

@Composable
private fun PairingDialog(prompt: ReceivePrompt.Pairing) {
    AlertDialog(
        onDismissRequest = { ReceivePromptController.answer(false) },
        title = { Text(stringResource(R.string.pair_title)) },
        text = {
            Column {
                Text(stringResource(R.string.pair_message, prompt.peerName))
                Spacer(Modifier.height(16.dp))
                Text(
                    text = com.quickshare.core.pairing.PairingCode.format(prompt.code),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = CodeFont,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            Button(onClick = { ReceivePromptController.answer(true) }) {
                Text(stringResource(R.string.pair_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = { ReceivePromptController.answer(false) }) {
                Text(stringResource(R.string.pair_decline))
            }
        },
    )
}

@Composable
private fun OfferDialog(prompt: ReceivePrompt.Offer) {
    val offer = prompt.offer
    AlertDialog(
        onDismissRequest = { ReceivePromptController.answer(false) },
        title = { Text(stringResource(R.string.offer_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.offer_message,
                        prompt.peerName,
                        offer.fileCount,
                        offer.totalSizeText,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = offer.previewNames(limit = 4),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { ReceivePromptController.answer(true) }) {
                Text(stringResource(R.string.offer_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = { ReceivePromptController.answer(false) }) {
                Text(stringResource(R.string.offer_decline))
            }
        },
    )
}

/**
 * Asks for a save folder when none is configured.
 *
 * The system picker is opened straight away, because declining the dialog and choosing nothing would
 * leave the sender waiting on a socket for a folder the user already knows they need to pick.
 */
@Composable
private fun DestinationDialog(viewModel: MainViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            ReceivePromptController.answerDestination(null)
            return@rememberLauncherForActivityResult
        }
        val label = ServiceLocator.documentRegistry.setTree(uri)
        if (label == null) {
            ReceivePromptController.answerDestination(null)
        } else {
            viewModel.setSaveLocation(uri.toString(), label)
            ReceivePromptController.answerDestination(uri.toString())
        }
    }

    LaunchedEffect(Unit) { picker.launch(null) }

    AlertDialog(
        onDismissRequest = { ReceivePromptController.answerDestination(null) },
        title = { Text(stringResource(R.string.settings_save_location)) },
        text = { Text(stringResource(R.string.settings_save_location_hint)) },
        confirmButton = {
            Button(onClick = { picker.launch(null) }) {
                Text(stringResource(R.string.settings_save_location)) }
        },
        dismissButton = {
            TextButton(onClick = { ReceivePromptController.answerDestination(null) }) {
                Text(stringResource(R.string.offer_decline))
            }
        },
    )
}

/**
 * Shows this device's pairing QR code.
 *
 * The bitmap is produced off the main thread (encoding a QR is real CPU work, and doing it during
 * composition would drop frames), and a failure to encode falls back to a message that points at the
 * manual-IP path rather than leaving an empty dialog.
 */
@Composable
private fun QrDialog(viewModel: MainViewModel) {
    val payload by viewModel.qrPayload.collectAsStateWithLifecycle()
    val deviceName by viewModel.settings.collectAsStateWithLifecycle()
    val code by viewModel.pairingCode.collectAsStateWithLifecycle()
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(payload) {
        val value = payload
        bitmap = if (value == null) null else withContext(Dispatchers.Default) {
            QrCode.generate(value, QR_SIZE_PX)
        }
    }

    AlertDialog(
        onDismissRequest = { viewModel.closeQr() },
        title = { Text(stringResource(R.string.home_show_qr)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val image = bitmap
                if (image != null) {
                    androidx.compose.foundation.Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = stringResource(R.string.home_scan_qr),
                        modifier = Modifier.size(240.dp).clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    Text(stringResource(R.string.home_scan_qr), textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = deviceName.deviceName.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.pair_show_code_message,
                        com.quickshare.core.pairing.PairingCode.format(code),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.closeQr() }) {
                Text(stringResource(R.string.transfer_close))
            }
        },
    )
}

/** The manual-IP fallback, used when broadcast discovery cannot see the other device. */
@Composable
private fun ManualConnectDialog(viewModel: MainViewModel) {
    val host by viewModel.manualHost.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = { viewModel.closeManualConnect() },
        title = { Text(stringResource(R.string.home_manual_connect)) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = host,
                    onValueChange = { viewModel.setManualHost(it) },
                    singleLine = true,
                    label = { Text("192.168.1.42") },
                    isError = host.isNotBlank() && ManualConnect.parse(host, 8988) !is ManualConnect.Result.Ok,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.error_invalid_ip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.connectManually() }) {
                Text(stringResource(R.string.home_manual_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.closeManualConnect() }) {
                Text(stringResource(R.string.transfer_close))
            }
        },
    )
}

private const val QR_SIZE_PX = 720

/** Formats a byte count for display, re-exported so every screen formats sizes identically. */
internal fun formatSize(bytes: Long): String = ByteFormat.size(bytes)
