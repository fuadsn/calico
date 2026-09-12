package com.hackathon.calico

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Palette: deep purple #3E0F8D and violet #9564DD, darker and lighter variations of the same hue,
// white for the strong buttons and highlights.
val Bg = Color(0xFF120430)          // darkest: screen background
val Card = Color(0xFF1F0A4D)        // surfaces
val Deep = Color(0xFF3E0F8D)        // brand deep purple: tiles, chips
val Mid = Color(0xFF5B2BB5)         // between deep and violet
val Violet = Color(0xFF9564DD)      // brand violet: accent, selected, progress
val VioletSoft = Color(0xFFB99CEB)  // tint for secondary progress
val Lilac = Color(0xFFE9E0FA)       // near-white tint
val Snow = Color(0xFFFFFFFF)
val Ink = Color(0xFFF7F3FF)         // primary text on dark
val Muted = Color(0xFFB3A3D6)       // secondary text on dark
val Line = Color(0xFF3A2470)        // hairlines, inactive path

// Radii: cards 24, tiles 20, everything tappable is a pill.
val CardShape = RoundedCornerShape(24.dp)
val TileShape = RoundedCornerShape(20.dp)
val Pill = RoundedCornerShape(50)

private val Scheme = darkColorScheme(
    primary = Violet, onPrimary = Snow,
    secondary = Snow, onSecondary = Deep,
    background = Bg, onBackground = Ink,
    surface = Card, onSurface = Ink,
    surfaceVariant = Deep, onSurfaceVariant = Muted,
    outline = Line,
)

// Type scale: big friendly headings, medium-weight labels.
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

/** Tile colour per step, cycling through the purple variations. */
fun tileColor(i: Int) = listOf(Deep, Mid, Card, Violet)[i % 4]
