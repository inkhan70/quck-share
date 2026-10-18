package com.quickshare.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * The QuickShare palette, mirroring `res/values/colors.xml`.
 *
 * The XML copy drives the launch theme (which is applied before Compose starts, so the very first
 * frame is already teal rather than flashing the platform default) and this copy drives the Compose
 * tree. They are kept deliberately in step: the single teal/cyan family is what makes the app
 * recognisable, so it is fixed rather than derived from Material You, and no dynamic-colour overlay
 * is applied.
 */
private object Palette {
    // Light
    val primaryLight = Color(0xFF00696D)
    val onPrimaryLight = Color(0xFFFFFFFF)
    val primaryContainerLight = Color(0xFF6FF6FC)
    val onPrimaryContainerLight = Color(0xFF002021)
    val secondaryLight = Color(0xFF4A6363)
    val onSecondaryLight = Color(0xFFFFFFFF)
    val secondaryContainerLight = Color(0xFFCCE8E7)
    val onSecondaryContainerLight = Color(0xFF051F1F)
    val surfaceLight = Color(0xFFFAFDFC)
    val surfaceContainerLight = Color(0xFFEEF4F3)
    val onSurfaceLight = Color(0xFF191C1C)
    val onSurfaceVariantLight = Color(0xFF3F4949)
    val outlineLight = Color(0xFF6F7979)
    val outlineVariantLight = Color(0xFFBEC9C8)
    val errorLight = Color(0xFFBA1A1A)
    val onErrorLight = Color(0xFFFFFFFF)
    val errorContainerLight = Color(0xFFFFDAD6)

    // Dark
    val primaryDark = Color(0xFF4CDADE)
    val onPrimaryDark = Color(0xFF003739)
    val primaryContainerDark = Color(0xFF004F51)
    val onPrimaryContainerDark = Color(0xFF6FF6FC)
    val secondaryDark = Color(0xFFB0CCCB)
    val onSecondaryDark = Color(0xFF1B3534)
    val secondaryContainerDark = Color(0xFF324B4B)
    val onSecondaryContainerDark = Color(0xFFCCE8E7)
    val surfaceDark = Color(0xFF101414)
    val surfaceContainerDark = Color(0xFF1C2020)
    val onSurfaceDark = Color(0xFFE0E3E2)
    val onSurfaceVariantDark = Color(0xFFBEC9C8)
    val outlineDark = Color(0xFF899393)
    val outlineVariantDark = Color(0xFF3F4949)
    val errorDark = Color(0xFFFFB4AB)
    val onErrorDark = Color(0xFF690005)
    val errorContainerDark = Color(0xFF93000A)

    // Transfer-state accents, chosen to stay legible against both surfaces.
    val successLight = Color(0xFF1B7F4B)
    val successDark = Color(0xFF7BE0A8)
    val warningLight = Color(0xFF8B5000)
    val warningDark = Color(0xFFFFB77A)
}

/**
 * Extra colours Material 3 does not model.
 *
 * A transfer has three "good/attention/bad" states but the colour scheme only carries error, so the
 * success and warning tones are exposed through a composition local instead of being hard-coded at
 * each call site.
 */
data class QuickColors(
    val success: Color,
    val warning: Color,
    val onSuccess: Color,
)

val LocalQuickColors = staticCompositionLocalOf {
    QuickColors(Color(0xFF1B7F4B), Color(0xFF8B5000), Color.White)
}

private val LightQuickColors = QuickColors(
    success = Palette.successLight,
    warning = Palette.warningLight,
    onSuccess = Color.White,
)

private val DarkQuickColors = QuickColors(
    success = Palette.successDark,
    warning = Palette.warningDark,
    onSuccess = Color(0xFF00391F),
)

private val LightScheme = lightColorScheme(
    primary = Palette.primaryLight,
    onPrimary = Palette.onPrimaryLight,
    primaryContainer = Palette.primaryContainerLight,
    onPrimaryContainer = Palette.onPrimaryContainerLight,
    secondary = Palette.secondaryLight,
    onSecondary = Palette.onSecondaryLight,
    secondaryContainer = Palette.secondaryContainerLight,
    onSecondaryContainer = Palette.onSecondaryContainerLight,
    surface = Palette.surfaceLight,
    onSurface = Palette.onSurfaceLight,
    surfaceVariant = Palette.surfaceContainerLight,
    onSurfaceVariant = Palette.onSurfaceVariantLight,
    surfaceContainer = Palette.surfaceContainerLight,
    outline = Palette.outlineLight,
    outlineVariant = Palette.outlineVariantLight,
    error = Palette.errorLight,
    onError = Palette.onErrorLight,
    errorContainer = Palette.errorContainerLight,
)

private val DarkScheme = darkColorScheme(
    primary = Palette.primaryDark,
    onPrimary = Palette.onPrimaryDark,
    primaryContainer = Palette.primaryContainerDark,
    onPrimaryContainer = Palette.onPrimaryContainerDark,
    secondary = Palette.secondaryDark,
    onSecondary = Palette.onSecondaryDark,
    secondaryContainer = Palette.secondaryContainerDark,
    onSecondaryContainer = Palette.onSecondaryContainerDark,
    surface = Palette.surfaceDark,
    onSurface = Palette.onSurfaceDark,
    surfaceVariant = Palette.surfaceContainerDark,
    onSurfaceVariant = Palette.onSurfaceVariantDark,
    surfaceContainer = Palette.surfaceContainerDark,
    outline = Palette.outlineDark,
    outlineVariant = Palette.outlineVariantDark,
    error = Palette.errorDark,
    onError = Palette.onErrorDark,
    errorContainer = Palette.errorContainerDark,
)

/**
 * Applies the QuickShare theme.
 *
 * @param darkTheme resolved by the caller from the stored theme preference, so that changing the
 *        setting in-app takes effect immediately rather than only after a restart.
 */
@Composable
fun QuickShareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val extras = if (darkTheme) DarkQuickColors else LightQuickColors
    CompositionLocalProvider(LocalQuickColors provides extras) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MaterialTheme.typography.copy(
                // The system font is used rather than a bundled one: it keeps the APK small and
                // guarantees the script coverage of whatever locale the device is set to.
                titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                titleMedium = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                labelLarge = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            ),
            content = content,
        )
    }
}

/** Shorthand for the extra colours, so call sites read `quickColors.success`. */
val quickColors: QuickColors
    @Composable get() = LocalQuickColors.current

/** Monospace family used for the pairing code, where digit alignment matters. */
val CodeFont: FontFamily = FontFamily.Monospace
