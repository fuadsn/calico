package com.hackathon.calico

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Dark, bold, one hot accent. Always dark: the workout screen is a camera feed.
val Ink = Color(0xFF0B0B0F)
val Surface1 = Color(0xFF16161D)
val Surface2 = Color(0xFF22222C)
val Lime = Color(0xFFC6FF3D)
val Ember = Color(0xFFFF5C1A)
val Fog = Color(0xFF8A8A99)
val Snow = Color(0xFFF4F4F8)

private val Scheme = darkColorScheme(
    primary = Lime, onPrimary = Ink,
    secondary = Ember, onSecondary = Ink,
    background = Ink, onBackground = Snow,
    surface = Surface1, onSurface = Snow,
    surfaceVariant = Surface2, onSurfaceVariant = Fog,
    outline = Surface2,
)

private val Type = Typography(
    displayLarge = TextStyle(fontSize = 120.sp, fontWeight = FontWeight.Black, letterSpacing = (-6).sp, lineHeight = 120.sp),
    displayMedium = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp, lineHeight = 60.sp),
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp),
)

@Composable
fun CalicoTheme(content: @Composable () -> Unit) {
    isSystemInDarkTheme() // intentionally ignored: always dark
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}

/** "PIKE_PUSHUP" -> "Pike pushup" */
val Exercise.label get() = name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
