package com.hackathon.calico

import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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

/** One set of motion specs so every screen moves the same way. */
object Motion {
    /** Fades and cross-fades: tabs, content swaps, appearing controls. */
    fun <T> fade() = tween<T>(220, easing = FastOutSlowInEasing)
    /** Elements that travel (selection swells, sliding discs, dips). */
    fun <T> move() = spring<T>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    /** Values that fill up (gauges, bars, rings). */
    fun <T> fill() = spring<T>(stiffness = Spring.StiffnessLow)
    /** Press-down feedback: quick in, soft out. */
    fun <T> press() = spring<T>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
}

/** Spacing scale shared by the screens. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    /** Screen side gutter. */
    val gutter = 20.dp
}

/**
 * Tap target with the app's press feedback: clipped ripple plus a slight shrink while held.
 * Use for every card, tile and button so they all respond identically.
 */
fun Modifier.pressable(shape: Shape, enabled: Boolean = true, label: String? = null, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.97f else 1f, Motion.press(), label = "press")
    this.scale(scale).clip(shape)
        .clickable(interactionSource = source, indication = ripple(), enabled = enabled, onClickLabel = label, onClick = onClick)
}

/**
 * Small round secondary action that sits on a row without competing with it: 36dp visual,
 * 48dp touch target, tinted from the surface it sits on.
 */
@Composable
fun SmallIconAction(icon: ImageVector, label: String, surface: Color, onClick: () -> Unit) {
    val fg = onTile(surface)
    Box(Modifier.size(48.dp).pressable(CircleShape, label = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp).background(fg.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = fg, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Floating pill of circular buttons. The pill's outline swells around the selected one and the
 * swell slides over when the selection changes.
 */
@Composable
fun BumpBar(items: List<Pair<ImageVector, String>>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val buttonSize = if (items.size > 4) 48.dp else BTN
    val gap = if (items.size > 4) 10.dp else GAP
    val sel by animateFloatAsState(selected.toFloat(), Motion.move(), label = "sel")
    Row(
        modifier.navigationBarsPadding().padding(bottom = 12.dp)
            .drawBehind {
                // Pill body unioned with a circle around the selected button, so the outline is a true circle where it swells.
                val b = BUMP.toPx(); val r = (buttonSize / 2 + 4.dp).toPx(); val cy = size.height / 2
                val cx = (BUMP + buttonSize / 2).toPx() + sel * (buttonSize + gap).toPx()
                val pill = Path().apply { addRoundRect(RoundRect(Rect(0f, b, size.width, size.height - b), CornerRadius(r))) }
                val circle = Path().apply { addOval(Rect(Offset(cx, cy), (buttonSize / 2).toPx() * 1.2f + 10.dp.toPx())) }
                val p = Path.combine(PathOperation.Union, pill, circle)
                drawPath(p, Snow)
                drawCircle(Charcoal, (buttonSize / 2).toPx() * 1.2f, Offset(cx, cy))   // the dark disc slides with the swell
            }
            .padding(horizontal = BUMP, vertical = BUMP + 4.dp),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        items.forEachIndexed { i, (icon, name) ->
            val on = i == selected
            val scale by animateFloatAsState(if (on) 1.2f else 1f, Motion.press(), label = "scale")
            Box(
                Modifier.size(buttonSize).scale(scale).pressable(CircleShape, label = name) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, name, tint = animateColorAsState(if (on) Accent else Charcoal, Motion.fade(), label = "tint").value) }
        }
    }
}

/** Outlined pill with a filled portion, a label inside it, and a knob at its end. */
@Composable
fun GoalBar(fraction: Float, text: String, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), Motion.fill(), label = "bar")
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
