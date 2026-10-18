package com.quickshare.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickshare.app.R
import com.quickshare.app.ui.screens.HistoryScreen
import com.quickshare.app.ui.screens.InfoScreen
import com.quickshare.app.ui.screens.NearbyScreen
import com.quickshare.app.ui.screens.SendScreen
import com.quickshare.app.ui.screens.SettingsScreen

/**
 * The app shell: bottom navigation over the three tabs, plus the modal send flow.
 *
 * The send flow is rendered *instead of* the scaffold rather than inside it, because sending is a
 * task with a beginning and an end (pick, send, close) rather than a place in the tab hierarchy -
 * leaving the bottom bar visible would let the user wander away mid-selection with no way back.
 */
@Composable
fun AppShell(viewModel: MainViewModel) {
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val sendTarget by viewModel.sendTarget.collectAsStateWithLifecycle()
    val infoPage by viewModel.infoPage.collectAsStateWithLifecycle()

    // Declared before any early return, and shared with the send screen. They used to live below the
    // send-screen branch, so an error that happened while sending (declined, unreachable, ...) was
    // never shown - the progress card just vanished.
    val snackbarHostState = remember { SnackbarHostState() }
    val error by viewModel.error.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    // Errors and notices are one-shot: they are shown, then cleared, so a rotation does not re-show a
    // message the user already dismissed.
    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearError()
    }
    LaunchedEffect(notice) {
        val message = notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearNotice()
    }

    val info = infoPage
    if (info != null) {
        InfoScreen(page = info, versionName = viewModel.versionName, onClose = { viewModel.closeInfo() })
        PromptHost(viewModel)
        return
    }

    val target = sendTarget
    if (target != null) {
        SendScreen(
            viewModel = viewModel,
            peer = target,
            onClose = { viewModel.clearTarget() },
            snackbarHostState = snackbarHostState,
        )
        PromptHost(viewModel)
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { entry ->
                    val (icon, labelRes) = entry.visuals()
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { viewModel.setTab(entry) },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.padding(padding),
        ) {
            when (tab) {
                AppTab.NEARBY -> NearbyScreen(viewModel)
                AppTab.HISTORY -> HistoryScreen(viewModel)
                AppTab.SETTINGS -> SettingsScreen(viewModel)
            }
        }
    }

    PromptHost(viewModel)
}

/** Icon and label for each tab, kept here so the navigation is defined in one place. */
private fun AppTab.visuals(): Pair<ImageVector, Int> = when (this) {
    AppTab.NEARBY -> Icons.Filled.Devices to R.string.nav_home
    AppTab.HISTORY -> Icons.Filled.History to R.string.nav_history
    AppTab.SETTINGS -> Icons.Filled.Settings to R.string.nav_settings
}
