package com.hackathon.calico

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val BTN = 56.dp
private val GAP = 12.dp
private val BUMP = 10.dp

/**
 * Floating pill of circular buttons. The pill's outline swells around the selected one and the
 * swell slides over when the selection changes.
 */
@Composable
fun BumpBar(items: List<Pair<ImageVector, String>>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val buttonSize = if (items.size > 4) 48.dp else BTN
    val gap = if (items.size > 4) 10.dp else GAP
    val sel by animateFloatAsState(selected.toFloat(), spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow), label = "sel")
    Row(
        modifier.navigationBarsPadding().padding(bottom = 12.dp)
            .drawBehind {
                // Pill outline pushed outwards by a bell curve centred on the selected button, so the
                // swell is smooth in the middle and wraps the end caps too. Polyline, ~130 points.
                val b = BUMP.toPx(); val top = b; val bot = size.height - b; val r = (bot - top) / 2
                val cy = (top + bot) / 2; val wd = size.width
                val cx = (BUMP + buttonSize / 2).toPx() + sel * (buttonSize + gap).toPx()
                val w = 46.dp.toPx()
                fun bell(x: Float): Float { val u = (x - cx) / w; return if (abs(u) >= 1f) 0f else { val t = 1 - u * u; t * t } }
                val p = Path()
                var first = true
                fun add(x: Float, y: Float, nx: Float, ny: Float) {
                    val o = b * bell(x)
                    if (first) { p.moveTo(x + nx * o, y + ny * o); first = false } else p.lineTo(x + nx * o, y + ny * o)
                }
                for (i in 0..40) add(r + (wd - 2 * r) * i / 40f, top, 0f, -1f)
                for (i in 1..24) { val a = Math.toRadians(-90.0 + 180.0 * i / 24); val nx = cos(a).toFloat(); val ny = sin(a).toFloat(); add(wd - r + r * nx, cy + r * ny, nx, ny) }
                for (i in 0..40) add(wd - r - (wd - 2 * r) * i / 40f, bot, 0f, 1f)
                for (i in 1..24) { val a = Math.toRadians(90.0 + 180.0 * i / 24); val nx = cos(a).toFloat(); val ny = sin(a).toFloat(); add(r + r * nx, cy + r * ny, nx, ny) }
                p.close()
                drawPath(p, PillBg)
            }
            .padding(horizontal = BUMP, vertical = BUMP + 8.dp),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        items.forEachIndexed { i, (icon, name) ->
            val on = i == selected
            val scale by animateFloatAsState(if (on) 1.12f else 1f, spring(dampingRatio = 0.6f), label = "scale")
            Box(
                Modifier.size(buttonSize).scale(scale).clip(CircleShape).background(if (on) Snow else Cloud).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, name, tint = if (on) Charcoal else onTile(Cloud)) }
        }
    }
}

/** Outlined pill with a filled portion, a label inside it, and a knob at its end. */
@Composable
fun GoalBar(fraction: Float, text: String, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(stiffness = Spring.StiffnessLow), label = "bar")
    Box(
        modifier.fillMaxWidth().height(36.dp).clip(Pill).border(2.dp, Ink.copy(0.35f), Pill)
            .drawBehind {   // hatched remainder
                val step = 7.dp.toPx(); var x = -size.height
                while (x < size.width) { drawLine(Ink.copy(0.25f), Offset(x, size.height), Offset(x + size.height, 0f), 1.5.dp.toPx()); x += step }
            }
            .padding(5.dp),
    ) {
        Box(Modifier.fillMaxWidth(maxOf(0.3f, f)).fillMaxHeight()) {
            Box(Modifier.fillMaxWidth().fillMaxHeight().background(Accent, Pill), contentAlignment = Alignment.Center) {
                Text(text, style = MaterialTheme.typography.labelSmall, color = OnAccent)
            }
            Box(Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 5.dp).size(12.dp).background(Snow, CircleShape))
        }
    }
}
