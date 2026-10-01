package com.example.purebrowser.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.purebrowser.data.browser.ThemeMode

private val Light = lightColorScheme(
    primary = Color(0xFF007F73), onPrimary = Color.White, primaryContainer = Color(0xFFCCF4EC), onPrimaryContainer = Color(0xFF003C36),
    secondary = Color(0xFF506562), secondaryContainer = Color(0xFFE2EEE8), onSecondaryContainer = Color(0xFF233C32), background = Color(0xFFF6F8F7), surface = Color(0xFFF6F8F7),
    surfaceContainer = Color(0xFFEAF0ED), surfaceContainerHigh = Color(0xFFE4EBE7), onSurface = Color(0xFF142C28),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF73DBC6), onPrimary = Color(0xFF00382F), primaryContainer = Color(0xFF005147), onPrimaryContainer = Color(0xFF94EDD7),
    secondary = Color(0xFFB4CBC0), secondaryContainer = Color(0xFF253D35), onSecondaryContainer = Color(0xFFCFECE0),
    background = Color(0xFF101B19), surface = Color(0xFF101B19), surfaceContainer = Color(0xFF192824), surfaceContainerHigh = Color(0xFF22322D),
    onSurface = Color(0xFFE4EFE9), onSurfaceVariant = Color(0xFFB4C9BF),
)
@Composable
fun PureBrowserTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Typography, shapes = Shapes(), content = content)
}
