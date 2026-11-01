package com.xestlab.assistant.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = TealAccent,
    onPrimary = Color(0xFF0E1412),
    primaryContainer = SurfaceSubtleDark,
    onPrimaryContainer = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    background = BgDark,
    onBackground = TextPrimaryDark,
    outline = BorderDark,
    error = WarningRed
)

private val LightColorScheme = lightColorScheme(
    primary = TealAccent,
    onPrimary = Color.White,
    primaryContainer = SurfaceSubtleLight,
    onPrimaryContainer = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    background = BgLight,
    onBackground = TextPrimaryLight,
    outline = BorderLight,
    error = WarningRed
)

val Shapes8px = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

@Composable
fun XestlabTheme(
    darkTheme: Boolean = true, // Dark by default per specification
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = Shapes8px,
        content = content
    )
}
