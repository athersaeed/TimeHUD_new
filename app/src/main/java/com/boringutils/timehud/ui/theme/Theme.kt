package com.boringutils.timehud.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val GraphiteColorScheme = darkColorScheme(
    primary = TimeHudColors.action,
    onPrimary = TimeHudColors.onAction,
    primaryContainer = TimeHudColors.textEmphasis,
    onPrimaryContainer = TimeHudColors.background,
    secondary = TimeHudColors.textEmphasis,
    onSecondary = TimeHudColors.background,
    secondaryContainer = TimeHudColors.surfaceSelected,
    onSecondaryContainer = TimeHudColors.textPrimary,
    tertiary = TimeHudColors.textSecondary,
    onTertiary = TimeHudColors.background,
    background = TimeHudColors.background,
    onBackground = TimeHudColors.textPrimary,
    surface = TimeHudColors.surface,
    onSurface = TimeHudColors.textPrimary,
    surfaceVariant = TimeHudColors.surfaceElevated,
    onSurfaceVariant = TimeHudColors.textSecondary,
    outline = TimeHudColors.border,
    outlineVariant = TimeHudColors.borderSubtle,
    error = TimeHudColors.statusDestructive,
    onError = TimeHudColors.background,
    errorContainer = TimeHudColors.surfaceElevated,
    onErrorContainer = TimeHudColors.textPrimary,
    surfaceTint = TimeHudColors.background,
    scrim = TimeHudColors.background
)

@Composable
fun TimeHUDTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = GraphiteColorScheme,
        typography = Typography,
        content = content
    )
}
