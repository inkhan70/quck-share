package com.quickshare.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quickshare.app.ui.theme.quickColors
import com.quickshare.core.util.FileCategory
import com.quickshare.core.util.MimeTypes

/**
 * Shared, presentation-only building blocks.
 *
 * Kept in one file so the visual language (card shape, spacing, which tone means "failed") is defined
 * once rather than repeated per screen, which is what keeps the three tabs looking like one app.
 */

/** A titled section that groups related rows. */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) { content() }
        }
    }
}

/** A labelled switch row, the primary control in Settings. */
@Composable
fun SettingSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** An icon chosen from the file's category, so a .zip and a .jpg do not look alike. */
fun iconFor(category: FileCategory): ImageVector = when (category) {
    FileCategory.IMAGE -> Icons.Filled.Image
    FileCategory.VIDEO -> Icons.Filled.Videocam
    FileCategory.AUDIO -> Icons.Filled.MusicNote
    FileCategory.PDF -> Icons.Filled.PictureAsPdf
    FileCategory.ARCHIVE -> Icons.Filled.FolderZip
    FileCategory.APK -> Icons.Filled.Android
    FileCategory.DOCUMENT -> Icons.AutoMirrored.Filled.Article
    FileCategory.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
fun FileTypeIcon(mime: String, name: String, modifier: Modifier = Modifier) {
    Icon(
        imageVector = iconFor(MimeTypes.categoryOf(mime, name)),
        contentDescription = null,
        modifier = modifier.size(28.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A status pill for a transfer.
 *
 * Colour carries the meaning (green verified, amber in progress or cancelled, red problem) and the
 * icon repeats it, so the state is still readable without colour vision.
 */
@Composable
fun TransferStatusPill(state: TransferUiState, label: String, modifier: Modifier = Modifier) {
    val colors = quickColors
    val (tint, icon) = when {
        state.isOk -> colors.success to Icons.Filled.CheckCircle
        state.isRunning -> MaterialTheme.colorScheme.primary to Icons.Filled.Timer
        state.isProblem -> MaterialTheme.colorScheme.error to Icons.Filled.ErrorOutline
        else -> colors.warning to Icons.Filled.ErrorOutline
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
