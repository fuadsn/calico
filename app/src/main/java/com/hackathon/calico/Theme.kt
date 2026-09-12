package com.hackathon.calico

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Palette: lavender + lime on near-white, navy for the strong buttons.
val Bg = Color(0xFFF4F4F8)
val Card = Color(0xFFFFFFFF)
val Purple = Color(0xFF8B7CF6)
val PurpleSoft = Color(0xFFE7E3FF)
val Lime = Color(0xFFD9F56B)
val LimeSoft = Color(0xFFF2FAD0)
val Sky = Color(0xFFC6EFFB)
val Pink = Color(0xFFF9CDE3)
val Navy = Color(0xFF1E1E2E)
val Ink = Color(0xFF1B1B2B)
val Muted = Color(0xFF8E8EA0)
val Line = Color(0xFFE9E9F0)
val Snow = Color(0xFFFFFFFF)

// Radii: cards 24, tiles 20, everything tappable is a pill.
val CardShape = RoundedCornerShape(24.dp)
val TileShape = RoundedCornerShape(20.dp)
val Pill = RoundedCornerShape(50)

private val Scheme = lightColorScheme(
    primary = Purple, onPrimary = Snow,
    secondary = Lime, onSecondary = Navy,
    background = Bg, onBackground = Ink,
    surface = Card, onSurface = Ink,
    surfaceVariant = PurpleSoft, onSurfaceVariant = Muted,
    outline = Line,
)

// Type scale lifted from the reference: big friendly headings, medium-weight labels.
private val Type = Typography(
    displayLarge = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp, lineHeight = 68.sp),
    displayMedium = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, lineHeight = 46.sp),
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun CalicoTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)

/** "PIKE_PUSHUP" -> "Pike pushup" */
val Exercise.label get() = name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

/** Tile colour per step, cycling like the reference cards. */
fun tileColor(i: Int) = listOf(PurpleSoft, Pink, Sky, LimeSoft)[i % 4]
