package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SleekColorScheme = lightColorScheme(
    primary = SleekBluePrimary,
    onPrimary = Color.White,
    primaryContainer = SleekBlueLight,
    onPrimaryContainer = SleekBlueDark,
    secondary = AccentIndigo,
    onSecondary = Color.White,
    secondaryContainer = AccentIndigoBg,
    onSecondaryContainer = AccentIndigo,
    tertiary = AccentOrange,
    onTertiary = Color.White,
    tertiaryContainer = AccentOrangeBg,
    onTertiaryContainer = AccentOrange,
    background = SleekBg,
    onBackground = Slate900,
    surface = SleekSurface,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate600,
    outline = Slate200,
    outlineVariant = Slate100,
    error = ErrorRed,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SleekColorScheme,
        typography = Typography,
        content = content
    )
}
