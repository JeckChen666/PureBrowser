package com.example.purebrowser.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.purebrowser.data.browser.ThemeMode

private val Light = lightColorScheme(
    primary = Color(0xFF356DE8), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EFFF), onPrimaryContainer = Color(0xFF183E89),
    secondary = Color(0xFF53627B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9EDF5), onSecondaryContainer = Color(0xFF243047),
    background = Color(0xFFF3F5F8), onBackground = Color(0xFF243047),
    surface = Color.White, onSurface = Color(0xFF243047), onSurfaceVariant = Color(0xFF53627B),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF3F5F8), surfaceContainerHigh = Color(0xFFEBEFF5),
    surfaceContainerHighest = Color(0xFFE1E6EE), outline = Color(0xFF718096), outlineVariant = Color(0xFFE1E6EE),
    error = Color(0xFFB3261E), onError = Color.White, errorContainer = Color(0xFFFFEDEA), onErrorContainer = Color(0xFF7B201A),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA9C2FF), onPrimary = Color(0xFF113373),
    primaryContainer = Color(0xFF203F77), onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFBAC6DE), onSecondary = Color(0xFF243047),
    secondaryContainer = Color(0xFF303C52), onSecondaryContainer = Color(0xFFE2E9F5),
    background = Color(0xFF111722), onBackground = Color(0xFFE3EAF5),
    surface = Color(0xFF171F2D), onSurface = Color(0xFFE3EAF5), onSurfaceVariant = Color(0xFFBBC7DA),
    surfaceContainerLowest = Color(0xFF101620), surfaceContainerLow = Color(0xFF171F2D),
    surfaceContainer = Color(0xFF1D2737), surfaceContainerHigh = Color(0xFF263247),
    surfaceContainerHighest = Color(0xFF303C52), outline = Color(0xFF8B9AB1), outlineVariant = Color(0xFF3B475D),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF542523), onErrorContainer = Color(0xFFFFDAD6),
)

/** Small visual surfaces; controls keep their independent accessible hit targets. */
private val CompactShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(20.dp),
)
@Composable
fun PureBrowserTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Typography, shapes = CompactShapes, content = content)
}
