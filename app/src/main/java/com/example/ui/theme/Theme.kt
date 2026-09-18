package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DirectUsbColorScheme = darkColorScheme(
    primary = CyanAccent,
    onPrimary = AmoledBlack,
    primaryContainer = FocusBackground,
    onPrimaryContainer = CyanAccent,
    secondary = TextSecondary,
    onSecondary = AmoledBlack,
    background = AmoledBlack,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DirectUsbColorScheme,
        typography = Typography,
        content = content
    )
}

