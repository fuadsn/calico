package com.hackathon.calico

import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.PathOperation

private val BTN = 56.dp
private val GAP = 12.dp
private val BUMP = 12.dp

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
                // Pill body unioned with a circle around the selected button, so the outline is a true circle where it swells.
                val b = BUMP.toPx(); val r = (buttonSize / 2 + 4.dp).toPx(); val cy = size.height / 2
                val cx = (BUMP + buttonSize / 2).toPx() + sel * (buttonSize + gap).toPx()
                val pill = Path().apply { addRoundRect(RoundRect(Rect(0f, b, size.width, size.height - b), CornerRadius(r))) }
                val circle = Path().apply { addOval(Rect(Offset(cx, cy), (buttonSize / 2).toPx() * 1.2f + 10.dp.toPx())) }
                val p = Path.combine(PathOperation.Union, pill, circle)
                drawPath(p, PillBg)
                drawPath(p, onTile(Cloud), style = Stroke(3.dp.toPx()))
                drawCircle(Accent, (buttonSize / 2).toPx() * 1.2f, Offset(cx, cy))   // the coral disc slides with the swell
            }
            .padding(horizontal = BUMP, vertical = BUMP + 4.dp),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        items.forEachIndexed { i, (icon, name) ->
            val on = i == selected
            val scale by animateFloatAsState(if (on) 1.2f else 1f, spring(dampingRatio = 0.6f), label = "scale")
            Box(
                Modifier.size(buttonSize).scale(scale).clip(CircleShape).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, name, tint = animateColorAsState(if (on) OnAccent else onTile(Cloud), label = "tint").value) }
        }
    }
}

/** Outlined pill with a filled portion, a label inside it, and a knob at its end. */
@Composable
fun GoalBar(fraction: Float, text: String, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(stiffness = Spring.StiffnessLow), label = "bar")
    Box(
        modifier.fillMaxWidth().height(36.dp).clip(Pill).border(2.dp, Charcoal.copy(0.35f), Pill)
            .drawBehind {   // hatched remainder
                val step = 7.dp.toPx(); var x = -size.height
                while (x < size.width) { drawLine(Charcoal.copy(0.35f), Offset(x, size.height), Offset(x + size.height, 0f), 1.5.dp.toPx()); x += step }
            }
            .padding(5.dp),
    ) {
        Box(Modifier.fillMaxWidth(maxOf(0.3f, f)).fillMaxHeight()) {
            Box(Modifier.fillMaxWidth().fillMaxHeight().background(Accent, Pill), contentAlignment = Alignment.Center) {
                Text(text, style = MaterialTheme.typography.labelSmall, color = OnAccent)
            }
            Box(Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 5.dp).size(12.dp).background(Charcoal, CircleShape))
        }
    }
}
