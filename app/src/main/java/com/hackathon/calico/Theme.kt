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

// Palette: calico, less beige. White coat with warm-grey surfaces, muted ginger patches used
// sparingly, dark brown for text and pills, a light ginger tint as the fourth tile.
val Bg = Color(0xFFFFFCF8)          // screen background: near white
val Card = Color(0xFFF6F0E9)        // surfaces: warm light grey
val Charcoal = Color(0xFFF0E8DF)    // tiles, chips: warm grey; also text on Snow pills
val Slate = Color(0xFFE6DDD2)       // secondary tiles: deeper warm grey
val Accent = Color(0xFFE07A35)      // ginger: progress and the one main button
val AccentSoft = Accent             // no extra shade; patches are ginger or dark brown
val OnAccent = Color(0xFFFFFCF8)    // text and icons placed on the accent
val Cloud = Color(0xFFF3ECE4)       // unselected nav circles
val Snow = Color(0xFF3A2E27)        // dark brown for strong pills and bubbles
val PillBg = Color(0xFFFFFFFF)      // floating bars
val Ink = Color(0xFF2F2721)         // primary text
val Muted = Color(0xFF8E877F)       // secondary text
val Line = Color(0xFFE8E4DE)        // hairlines, inactive path

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
fun tileColor(i: Int) = listOf(Charcoal, Accent, Card, Snow)[i % 4]

/** Text colour that reads on a given tile. */
fun onTile(c: Color) = if (c.luminance() > 0.4f) Ink else Bg
