package com.hackathon.calico

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Palette: muted calico. Off-white coat, dusty ginger used sparingly, dark brown for text and pills,
// muted sand, tan and apricot for tiles. No black tiles.
val Bg = Color(0xFFFBF7F1)          // screen background: off-white
val Card = Color(0xFFF3ECE2)        // surfaces: muted cream
val Charcoal = Color(0xFFE8DCCB)    // tiles, chips: muted sand; also text on Snow pills
val Slate = Color(0xFFD9C4A9)       // secondary tiles: muted tan
val Accent = Color(0xFFC97A4B)      // dusty ginger: progress and the one main button
val AccentSoft = Color(0xFFE3BE9C)  // muted apricot: fourth tile
val OnAccent = Color(0xFFFBF7F1)    // text and icons placed on the accent
val Cloud = Color(0xFFEFE7DC)       // unselected nav circles
val Snow = Color(0xFF4A3A31)        // muted dark brown for strong pills and bubbles
val PillBg = Color(0xFFFDFBF8)      // floating bars
val Ink = Color(0xFF3A2E27)         // primary text
val Muted = Color(0xFF9A8B7D)       // secondary text
val Line = Color(0xFFE6DCCF)        // hairlines, inactive path

// Radii: cards 24, tiles 20, everything tappable is a pill.
val CardShape = RoundedCornerShape(24.dp)
val TileShape = RoundedCornerShape(20.dp)
val Pill = RoundedCornerShape(50)

private val Scheme = lightColorScheme(
    primary = Accent, onPrimary = OnAccent,
    secondary = Snow, onSecondary = Bg,
    background = Bg, onBackground = Ink,
    surface = Card, onSurface = Ink,
    surfaceVariant = Charcoal, onSurfaceVariant = Muted,
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

/** Tile colour per step: greys with a sage one every fourth. */
fun tileColor(i: Int) = listOf(Charcoal, Slate, Card, AccentSoft)[i % 4]

/** Text colour that reads on a given tile. */
fun onTile(c: Color) = if (c.luminance() > 0.4f) Ink else Bg
