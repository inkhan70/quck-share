package com.quickshare.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickshare.app.R
import com.quickshare.app.ui.FileTypeIcon
import com.quickshare.app.ui.MainViewModel
import com.quickshare.app.ui.TransferStatusPill
import com.quickshare.app.ui.summarize
import com.quickshare.app.ui.uiStateOf
import com.quickshare.core.transfer.TransferDirection
import com.quickshare.core.transfer.TransferRecord
import com.quickshare.core.util.ByteFormat

/**
 * The history tab.
 *
 * Every row carries its verification outcome, because "did that file arrive intact?" is the question
 * history exists to answer - a list of filenames without it would be decoration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: MainViewModel) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val summary = summarize(history)
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                actions = {
                    if (summary.hasAny) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(
                                androidx.compose.material.icons.Icons.Filled.DeleteSweep,
                                contentDescription = stringResource(R.string.history_clear),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxWidth()) {
            if (!summary.hasAny) {
                EmptyHistory()
            } else {
                SummaryStrip(summary.sent, summary.received, summary.successfulBytes)
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(history, key = { it.transferId }) { record ->
                        HistoryRow(
                            record = record,
                            onRetry = { viewModel.retry(record) },
                            onRemove = { viewModel.removeHistory(record.transferId) },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_confirm)) },
            text = { Text(stringResource(R.string.history_clear_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    confirmClear = false
                }) { Text(stringResource(R.string.history_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.transfer_close))
                }
            },
        )
    }
}

@Composable
private fun SummaryStrip(sent: Int, received: Int, bytes: Long) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryItem(Icons.Filled.ArrowUpward, sent.toString())
            SummaryItem(Icons.Filled.ArrowDownward, received.toString())
            SummaryItem(Icons.Filled.Verified, ByteFormat.size(bytes))
        }
    }
}

@Composable
private fun SummaryItem(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun EmptyHistory() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.History,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.history_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryRow(record: TransferRecord, onRetry: () -> Unit, onRemove: () -> Unit) {
    val state = uiStateOf(record.status)
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileTypeIcon(
                    mime = "",
                    name = record.fileNames.firstOrNull().orEmpty(),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (record.direction == TransferDirection.SEND) R.string.history_sent_to
                            else R.string.history_received_from,
                            record.peerName,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // summary() already contains the size; appending it again showed it twice.
                        text = record.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.canRetry) {
                    IconButton(onClick = onRetry) {
                        Icon(
                            Icons.Filled.Replay,
                            contentDescription = stringResource(R.string.transfer_retry),
                        )
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        androidx.compose.material.icons.Icons.Filled.Close,
                        contentDescription = stringResource(R.string.send_remove),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TransferStatusPill(state = state, label = statusLabel(record, state))
            record.errorMessage?.let { message ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun statusLabel(record: TransferRecord, state: com.quickshare.app.ui.TransferUiState): String =
    when {
        state.isOk && record.verified -> stringResource(R.string.history_verified)
        state.isOk -> stringResource(R.string.transfer_done)
        state.isRunning -> stringResource(R.string.transfer_sending)
        state == com.quickshare.app.ui.TransferUiState.VERIFICATION_FAILED ->
            stringResource(R.string.transfer_verification_failed)
        state == com.quickshare.app.ui.TransferUiState.CANCELLED ->
            stringResource(R.string.transfer_cancelled)
        else -> stringResource(R.string.transfer_failed)
    }
