package com.stefan73.swipessh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = TerminalGreen,
    onPrimary = TerminalInk,
    primaryContainer = TerminalPanel,
    onPrimaryContainer = TerminalMint,
    secondary = TerminalAccent,
    onSecondary = TerminalSand,
    secondaryContainer = TerminalAccent.copy(alpha = 0.35f),
    onSecondaryContainer = TerminalMint,
    background = TerminalInk,
    onBackground = TerminalSand,
    surface = TerminalInk,
    onSurface = TerminalSand,
    surfaceContainerLow = TerminalPanel,
    surfaceContainerLowest = ColorBlackSoft,
    onSurfaceVariant = TerminalMint.copy(alpha = 0.78f),
)

private val LightColors = lightColorScheme(
    primary = TerminalAccent,
    onPrimary = TerminalSand,
    primaryContainer = TerminalMint,
    onPrimaryContainer = TerminalInk,
    secondary = TerminalGreen,
    onSecondary = TerminalInk,
    secondaryContainer = TerminalGreen.copy(alpha = 0.25f),
    onSecondaryContainer = TerminalInk,
    background = ColorPaper,
    onBackground = TerminalInk,
    surface = ColorPaper,
    onSurface = TerminalInk,
    surfaceContainerLow = ColorSurfaceWarm,
    surfaceContainerLowest = ColorPaper,
    onSurfaceVariant = TerminalPanel.copy(alpha = 0.8f),
)

@Composable
fun SshTerminalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}

