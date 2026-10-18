package com.quickshare.app.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.CaptureActivity
import com.quickshare.app.R
import com.quickshare.app.ui.MainViewModel
import com.quickshare.app.ui.TransferUiState
import com.quickshare.app.ui.formatSize
import com.quickshare.core.net.Peer
import com.quickshare.core.pairing.PairingPayload
import com.quickshare.core.transfer.TransferDirection
import com.quickshare.core.transfer.TransferProgress

/**
 * The extra key zxing's capture activity puts the decoded text in.
 *
 * Stated here rather than imported: the constant lives on zxing's internal `Intents` class, while the
 * string itself is the contract with the capture activity, so naming it keeps the coupling visible.
 */
private const val QR_RESULT_EXTRA = "SCAN_RESULT"

/**
 * The home tab: is this device visible, and who else is on the network.
 *
 * This is the screen the primary flow starts from (open, discover, tap a device), so the two things
 * that decide whether that flow can work at all - the receive switch and whether there is a usable
 * local network - are both surfaced above the device list rather than buried in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(viewModel: MainViewModel) {
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val onWifi by viewModel.onLocalNetwork.collectAsStateWithLifecycle()
    val sends by viewModel.activeSends.collectAsStateWithLifecycle()
    val receives by viewModel.activeReceives.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // The capture screen is hosted by zxing's own CaptureActivity and launched directly: its intent
    // extras are the documented configuration surface and the decoded text comes back under
    // QR_RESULT_EXTRA, so scanning needs no extra scan UI of our own. A cancelled scan reports a
    // non-OK result code and is simply ignored.
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val payload = PairingPayload.decode(result.data?.getStringExtra(QR_RESULT_EXTRA))
        // A code that is not ours used to be dropped silently, which looked like a broken scanner.
        if (payload != null) viewModel.applyPairingPayload(payload) else viewModel.showInvalidQrNotice()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
                        Text(
                            text = stringResource(R.string.app_tagline),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshPeers() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.home_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {

                // ---- Receive switch ---------------------------------------------------------
                item {
                    ReceiveCard(
                        enabled = settings.receiveEnabled != false,
                        onWifi = onWifi,
                        onToggle = { viewModel.setReceiveEnabled(it) },
                    )
                }

                // ---- Pairing helpers --------------------------------------------------------
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { viewModel.openQr() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.home_show_qr))
                        }
                        FilledTonalButton(
                            onClick = {
                                scanner.launch(
                                    Intent(context, CaptureActivity::class.java).apply {
                                        action = "com.google.zxing.client.android.SCAN"
                                        // Only QR: the app has no other barcode payload, and narrowing
                                        // the format makes the scan noticeably faster to lock on.
                                        putExtra("SCAN_FORMATS", "QR_CODE")
                                        putExtra("PROMPT_MESSAGE", "")
                                        putExtra("BEEP_ENABLED", false)
                                        putExtra("BARCODE_IMAGE_ENABLED", false)
                                    },
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.home_scan_qr))
                        }
                    }
                }

                item {
                    FilledTonalButton(
                        onClick = { viewModel.openManualConnect() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        Text(stringResource(R.string.home_manual_connect))
                    }
                }

                item {
                    androidx.compose.material3.TextButton(
                        onClick = { viewModel.openInfo(com.quickshare.app.ui.InfoPage.HOW_TO_USE) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        Text(stringResource(R.string.home_how_to_use))
                    }
                }

                // ---- Active transfers -------------------------------------------------------
                val active = sends.values.toList() + receives.values.toList()
                if (active.isNotEmpty()) {
                    item {
                        SectionLabel(stringResource(R.string.home_active_transfers))
                    }
                    items(active, key = { it.transferId + it.direction.name }) { progress ->
                        ActiveTransferCard(
                            progress = progress,
                            onCancel = { viewModel.cancelTransfer(progress.transferId) },
                        )
                    }
                }

                // ---- Device list ------------------------------------------------------------
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.home_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        if (peers.isNotEmpty()) {
                            Text(
                                text = peers.size.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (peers.isEmpty()) {
                    item { EmptyDevices(onWifi = onWifi, receiving = settings.receiveEnabled != false) }
                } else {
                    items(peers, key = { it.deviceId }) { peer ->
                        DeviceCard(peer = peer, onClick = { viewModel.selectTarget(peer) })
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun ReceiveCard(enabled: Boolean, onWifi: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (onWifi) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_receive_toggle),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        when {
                            !onWifi -> R.string.home_offline_title
                            enabled -> R.string.home_receive_on
                            else -> R.string.home_receive_off
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun EmptyDevices(onWifi: Boolean, receiving: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = if (onWifi) Icons.Filled.Devices else Icons.Filled.WifiOff,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(if (onWifi) R.string.home_no_devices else R.string.home_offline_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(if (onWifi) R.string.home_no_devices_hint else R.string.home_offline_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        if (onWifi && !receiving) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_receive_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(16.dp))
        // An indeterminate bar rather than a spinner: discovery never "finishes", so this reads as
        // \"listening\" instead of \"loading\".
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_scanning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One discovered device; tapping it opens the send flow. */
@Composable
private fun DeviceCard(peer: Peer, onClick: () -> Unit) {
    val compatible = peer.isCompatible
    Card(
        onClick = onClick,
        enabled = compatible,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (compatible) "${peer.address}:${peer.controlPort}"
                    else stringResource(R.string.home_device_incompatible),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (compatible) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
            Icon(
                imageVector = Icons.Filled.Devices,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** A compact live progress row for an in-flight transfer. */
@Composable
fun ActiveTransferCard(progress: TransferProgress, onCancel: (() -> Unit)? = null) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        if (progress.direction == TransferDirection.SEND) R.string.transfer_sending
                        else R.string.transfer_receiving,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (onCancel != null) {
                    IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.transfer_cancel),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Text(
                text = progress.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            if (progress.isIndeterminate) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { progress.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${progress.percent}%  \u00b7  ${formatSize(progress.bytesTransferred)} / ${formatSize(progress.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // This label always said "Sending", even for a transfer being received.
                Text(
                    text = com.quickshare.core.util.ByteFormat.speed(progress.bytesPerSecond),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The transfer-state vocabulary, derived from the shared UI mapping so it never drifts. */
internal fun isRunning(state: TransferUiState): Boolean = state.isRunning
