package com.quickshare.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickshare.app.R
import com.quickshare.app.core.ServiceLocator
import com.quickshare.app.ui.InfoPage
import com.quickshare.app.ui.MainViewModel
import com.quickshare.app.ui.SectionCard
import com.quickshare.app.ui.SettingSwitchRow
import com.quickshare.app.ui.ThemeOption

/**
 * The settings tab.
 *
 * Note what is *absent*: there is no storage permission request, no account, and no cloud toggle,
 * because the app has none of those things. The save-folder row is the only place a system permission
 * is involved, and it goes through the Storage Access Framework picker, which grants exactly one
 * directory rather than the whole filesystem.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val label = ServiceLocator.documentRegistry.setTree(uri)
        if (label != null) viewModel.setSaveLocation(uri.toString(), label)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {

            // ---- This device ---------------------------------------------------------------
            SectionCard(title = stringResource(R.string.settings_device_section)) {
                var name by remember(settings.deviceName) { mutableStateOf(settings.deviceName.orEmpty()) }
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    TextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.settings_device_name)) },
                        supportingText = { Text(stringResource(R.string.settings_device_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    // This button was labelled "Add more" (a string from the send screen).
                    TextButton(onClick = { viewModel.setDeviceName(name) }) {
                        Text(stringResource(R.string.settings_device_name_save))
                    }
                    Text(
                        text = stringResource(R.string.settings_device_id) + ": " + viewModel.deviceId(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- Transfers -----------------------------------------------------------------
            SectionCard(title = stringResource(R.string.settings_transfer_section)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_save_location),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = settings.saveLocationLabel
                                ?: stringResource(R.string.settings_save_location_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { folderPicker.launch(null) }) {
                        Text(stringResource(R.string.settings_save_location))
                    }
                }
                Text(
                    text = stringResource(R.string.settings_save_location_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                SettingSwitchRow(
                    title = stringResource(R.string.settings_require_pairing),
                    subtitle = stringResource(R.string.settings_require_pairing_hint),
                    checked = settings.requirePairingCode != false,
                    onCheckedChange = { viewModel.setRequirePairing(it) },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.settings_auto_accept),
                    subtitle = stringResource(R.string.settings_auto_accept_hint),
                    checked = settings.autoAcceptFromKnownDevices == true,
                    onCheckedChange = { viewModel.setAutoAccept(it) },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.settings_save_gallery),
                    subtitle = null,
                    checked = settings.saveImagesToGallery != false,
                    onCheckedChange = { viewModel.setSaveImagesToGallery(it) },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.settings_keep_screen_on),
                    subtitle = null,
                    checked = settings.keepScreenOnDuringTransfer != false,
                    onCheckedChange = { viewModel.setKeepScreenOn(it) },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.settings_notifications),
                    subtitle = null,
                    checked = settings.notifyOnTransfer != false,
                    onCheckedChange = { viewModel.setNotifyOnTransfer(it) },
                )
            }

            // ---- Appearance ----------------------------------------------------------------
            SectionCard(title = stringResource(R.string.settings_appearance_section)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_theme),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                val current = ThemeOption.from(settings.darkMode)
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ThemeOption.entries.forEach { option ->
                        FilterChip(
                            selected = option == current,
                            onClick = { viewModel.setTheme(option) },
                            label = {
                                Text(
                                    stringResource(
                                        when (option) {
                                            ThemeOption.SYSTEM -> R.string.settings_theme_system
                                            ThemeOption.LIGHT -> R.string.settings_theme_light
                                            ThemeOption.DARK -> R.string.settings_theme_dark
                                        },
                                    ),
                                )
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ---- About ---------------------------------------------------------------------
            SectionCard(title = stringResource(R.string.settings_about_section)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_about),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.settings_version, viewModel.versionName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { viewModel.openInfo(InfoPage.ABOUT) }) {
                        Text(stringResource(R.string.settings_about_us))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_how_to_use),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.settings_how_to_use_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { viewModel.openInfo(InfoPage.HOW_TO_USE) }) {
                        Text(stringResource(R.string.about_title))
                    }
                }
                TextButton(
                    onClick = { confirmReset = true },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text(stringResource(R.string.settings_reset))
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_reset_confirm)) },
            text = { Text(stringResource(R.string.settings_reset_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetSettings()
                    confirmReset = false
                }) { Text(stringResource(R.string.settings_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.transfer_close))
                }
            },
        )
    }
}

/** A dismissible informational card, used for the one-off notices on this screen. */
