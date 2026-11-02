package com.xestlab.assistant.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val CardShape8 = RoundedCornerShape(8.dp)

val Shapes8px = Shapes(
    extraSmall = CardShape8,
    small = CardShape8,
    medium = CardShape8,
    large = CardShape8,
    extraLarge = CardShape8
)

val XestlabTextSelectionColors = TextSelectionColors(
    handleColor = TealAccent,
    backgroundColor = TealAccent.copy(alpha = 0.35f)
)

private val XestlabDarkColorScheme = darkColorScheme(
    primary = TealAccent,
    onPrimary = Color(0xFF061A18),
    primaryContainer = TealAccentContainer,
    onPrimaryContainer = TextPrimaryDark,
    secondary = TealAccent,
    onSecondary = Color(0xFF061A18),
    secondaryContainer = SurfaceSubtleDark,
    onSecondaryContainer = TextPrimaryDark,
    background = BgDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceSubtleDark,
    onSurfaceVariant = TextMutedDark,
    outline = BorderDark,
    error = WarningRed,
    onError = Color.White,
    errorContainer = WarningRedContainer,
    onErrorContainer = TextPrimaryDark
)

private val XestlabTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        color = TextPrimaryDark
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        color = TextPrimaryDark
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = TextPrimaryDark
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        color = TextPrimaryDark
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = TextPrimaryDark
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
        color = TextMutedDark
    )
)

@Composable
fun XestlabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = XestlabDarkColorScheme,
        typography = XestlabTypography,
        shapes = Shapes8px
    ) {
        CompositionLocalProvider(
            LocalTextSelectionColors provides XestlabTextSelectionColors,
            content = content
        )
    }
}
