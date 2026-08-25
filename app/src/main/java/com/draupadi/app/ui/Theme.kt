package com.draupadi.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFFF6F3F6)
val Ink2 = Color(0xFFB8B0BD)
val Ink3 = Color(0xFF7E7686)
val Bg = Color(0xFF07060A)
val Surface1 = Color(0xFF141019)
val Red = Color(0xFFFF2247)
val RedDeep = Color(0xFFC50F30)
val Safe = Color(0xFF1FD18D)
val Warn = Color(0xFFFFB020)

private val scheme = darkColorScheme(
    primary = Red,
    onPrimary = Color.White,
    secondary = Safe,
    background = Bg,
    onBackground = Ink,
    surface = Surface1,
    onSurface = Ink,
    error = Red,
    onError = Color.White
)

private val type = Typography(
    displayLarge = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.4).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
)

@Composable
fun DraupadiTheme(content: @Composable () -> Unit) {
    // The app is dark whatever the phone is set to: a red-on-black screen at
    // 2 a.m. is easier to read and harder to see over a shoulder.
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
