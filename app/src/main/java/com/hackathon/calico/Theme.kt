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

// Palette: dark grey ground, lighter grey components, coral accent paired with dark only, light blocks as the second colour.
val Bg = Color(0xFF292929)          // screen ground: primary dark
val Card = Color(0xFF3A3E3B)        // components: slightly lighter grey
val Charcoal = Color(0xFF292929)    // dark text/icons on light blocks; empty heatmap cells
val Slate = Color(0xFF5C6268)       // secondary text on light blocks
val Accent = Color(0xFFF36B78)      // coral: only ever sits on dark
val AccentSoft = Color(0xFF4A4F4C)  // nested component grey inside Card
val OnAccent = Color(0xFF292929)    // dark text on coral
val Cloud = Color(0xFF3A3E3B)       // unselected nav circles
val Snow = Color(0xFFD8E1E8)        // light blocks, pills, knobs
val PillBg = Color(0xFF3A3E3B)      // floating bars
val Ink = Color(0xFFF2F4F6)         // primary text on dark
val Muted = Color(0xFF9DA3A6)       // secondary text on dark
val Line = Color(0xFF4A4F4C)        // hairlines, inactive path

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

/** Tile colour per step: mostly [base], a light one and a coral one every fourth. */
fun tileColor(i: Int, base: Color = Card) = listOf(base, Snow, base, Accent)[i % 4]

/** Text colour that reads on a given tile. */
fun onTile(c: Color) = if (c.luminance() > 0.25f) Charcoal else Ink
