package com.urmindkilla.svodkalocal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF10120E)
private val Paper = Color(0xFF1C2118)
private val Card = Color(0xFF262C20)
private val Amber = Color(0xFFE0B15A)
private val Cream = Color(0xFFF4F1E6)
private val Muted = Color(0xFFC4C0B2)
private val Clay = Color(0xFFE07A5F)

private val Scheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1A1408),
    secondary = Color(0xFFB7C4A1),
    onSecondary = Color(0xFF17210F),
    background = Ink,
    onBackground = Cream,
    surface = Paper,
    onSurface = Cream,
    surfaceVariant = Card,
    onSurfaceVariant = Muted,
    error = Clay,
    onError = Color(0xFF2A100C),
    outline = Color(0xFF3C4234),
)

private val Type = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 34.sp, color = Cream),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 22.sp, lineHeight = 28.sp, color = Cream),
    titleMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 24.sp, color = Cream),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 23.sp, color = Cream),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, color = Cream),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, letterSpacing = 0.6.sp),
)

@Composable
fun SvodkaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
