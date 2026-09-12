package com.hackathon.calico

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Palette: #584D3D stone brown, #9F956C / #CBBF7A sand, #F4E87C pale amber, #EBF38B lime cream.
val Bg = Color(0xFF3F3728)          // screen background (deeper stone brown)
val Card = Color(0xFF584D3D)        // surfaces
val Charcoal = Color(0xFF6A5D4A)    // tiles, chips; also text on Snow pills
val Slate = Color(0xFF7D6E58)       // secondary tiles
val Accent = Color(0xFFEBF38B)      // lime cream: accent, selected, progress
val AccentSoft = Color(0xFFCBBF7A)  // sand: fourth tile
val OnAccent = Color(0xFF3F3728)    // text and icons placed on the accent
val Cloud = Color(0xFFCBBF7A)       // sand for unselected nav circles
val Snow = Color(0xFFF4E87C)        // pale amber for strong pills and bubbles
val Ink = Color(0xFFFAF6E4)         // primary text on dark
val Muted = Color(0xFFCBBF7A)       // secondary text on dark
val Line = Color(0xFF6A5D4A)        // hairlines, inactive path

// Radii: cards 24, tiles 20, everything tappable is a pill.
val CardShape = RoundedCornerShape(24.dp)
val TileShape = RoundedCornerShape(20.dp)
val Pill = RoundedCornerShape(50)

private val Scheme = darkColorScheme(
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
fun onTile(c: Color) = if (c.luminance() > 0.4f) Card else Ink
