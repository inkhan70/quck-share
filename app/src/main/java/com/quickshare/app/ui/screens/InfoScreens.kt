package com.quickshare.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quickshare.app.R
import com.quickshare.app.ui.InfoPage
import com.quickshare.app.ui.SectionCard

/**
 * The two full-screen information pages: "How to use QuickShare" and "About".
 *
 * They are separate screens (not dialogs) because the guide is long enough to scroll, and because a
 * user following it wants to read a step, switch to the tab it describes and come back - a dialog
 * would be dismissed by the first tap outside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(page: InfoPage, versionName: String, onClose: () -> Unit) {
    // System back returns to Settings / Nearby instead of leaving the app.
    BackHandler(onBack = onClose)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (page) {
                                InfoPage.HOW_TO_USE -> R.string.howto_title
                                InfoPage.ABOUT -> R.string.about_title
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.transfer_close),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                InfoPage.HOW_TO_USE -> HowToUseContent()
                InfoPage.ABOUT -> AboutContent(versionName)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun HowToUseContent() {
    Text(
        text = stringResource(R.string.howto_intro),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )

    SectionCard(title = stringResource(R.string.howto_before_title)) {
        StepList(
            listOf(R.string.howto_before_1, R.string.howto_before_2, R.string.howto_before_3),
        )
    }
    SectionCard(title = stringResource(R.string.howto_send_title)) {
        StepList(
            listOf(
                R.string.howto_send_1,
                R.string.howto_send_2,
                R.string.howto_send_3,
                R.string.howto_send_4,
                R.string.howto_send_5,
            ),
        )
    }
    SectionCard(title = stringResource(R.string.howto_receive_title)) {
        StepList(
            listOf(
                R.string.howto_receive_1,
                R.string.howto_receive_2,
                R.string.howto_receive_3,
                R.string.howto_receive_4,
            ),
        )
    }
    SectionCard(title = stringResource(R.string.howto_missing_title)) {
        StepList(
            listOf(R.string.howto_missing_1, R.string.howto_missing_2, R.string.howto_missing_3),
            numbered = false,
        )
    }
    SectionCard(title = stringResource(R.string.howto_tips_title)) {
        StepList(
            listOf(R.string.howto_tips_1, R.string.howto_tips_2, R.string.howto_tips_3),
            numbered = false,
        )
    }
}

@Composable
private fun AboutContent(versionName: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_version, versionName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionCard(title = stringResource(R.string.settings_about)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.about_what), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.about_how), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.about_permissions), style = MaterialTheme.typography.bodyMedium)
        }
    }

    SectionCard(title = stringResource(R.string.aboutus_title)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.aboutus_created_by),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.aboutus_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.aboutus_copyright),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A list of instructions, numbered (a sequence to follow) or bulleted (independent tips). */
@Composable
private fun StepList(steps: List<Int>, numbered: Boolean = true) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        steps.forEachIndexed { index, resId ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = if (numbered) "${index + 1}." else "\u2022",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(28.dp),
                )
                Text(
                    text = stringResource(resId),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
