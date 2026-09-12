package com.hackathon.calico

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val resumed = mutableIntStateOf(0)   // bumped on resume so screens re-read progress

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(0, 0), navigationBarStyle = SystemBarStyle.light(0, 0))
        setContent { CalicoTheme { App(resumed.intValue) } }
    }

    override fun onResume() { super.onResume(); resumed.intValue++ }
}

fun openScan(ctx: android.content.Context) {
    // Room-scan mode lives in the AR module; launch it by name once it exists.
    runCatching { ctx.startActivity(Intent(ctx, Class.forName("com.hackathon.calico.ScanActivity"))) }
        .onFailure { Toast.makeText(ctx, "Room scan is on its way", Toast.LENGTH_SHORT).show() }
}

@Composable
private fun App(resumed: Int) {
    val ctx = LocalContext.current
    val progress = remember { Progress(ctx) }
    var tab by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(Bg)) {
        if (tab == 0) Home(progress, resumed) else Journey(progress, resumed)
        BottomBar(tab, Modifier.align(Alignment.BottomCenter)) { i -> if (i == 2) openScan(ctx) else tab = i }
    }
}

/** Floating white pill with circular icon buttons, the selected one filled purple. */
@Composable
private fun BottomBar(selected: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    val items = listOf<Pair<ImageVector, String>>(Icons.Outlined.Home to "Home", Icons.Outlined.Map to "Journey", Icons.Outlined.CameraAlt to "Scan")
    Row(
        modifier.navigationBarsPadding().padding(bottom = 12.dp).shadow(16.dp, Pill, ambientColor = Navy.copy(0.15f)).background(Card, Pill).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items.forEachIndexed { i, (icon, name) ->
            val on = i == selected
            Box(
                Modifier.size(56.dp).background(if (on) Purple else Bg, CircleShape).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, name, tint = if (on) Snow else Ink) }
        }
    }
}

// ---------------- Home ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Home(progress: Progress, resumed: Int) {
    val ctx = LocalContext.current
    var resetsKey by remember { mutableIntStateOf(0) }
    val key = resumed + resetsKey
    val streak = remember(key) { progress.streak }
    val dates = remember(key) { progress.dates }
    val doneToday = remember(key) { progress.doneToday }
    val plan = remember(key) { progress.todayPlan }
    val completed = remember(key) { progress.completed }
    val level = LEVELS[remember(key) { progress.levelIndex }]
    var filter by remember { mutableIntStateOf(0) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 120.dp),
    ) {
        // header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).background(PurpleSoft, CircleShape).combinedClickable(onClick = {}, onLongClick = {
                    progress.reset(); resetsKey++   // demo rehearsals start from a zero streak
                    Toast.makeText(ctx, "Progress reset", Toast.LENGTH_SHORT).show()
                }),
                contentAlignment = Alignment.Center,
            ) { Text("🐈", fontSize = 26.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("HI THERE 👋", style = MaterialTheme.typography.titleLarge, color = Ink)
                Text("⚡ Level ${completed + 1} · ${level.title}", style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            Box(Modifier.background(Card, Pill).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("🔥 $streak", style = MaterialTheme.typography.titleMedium, color = Ink)
            }
        }

        Spacer(Modifier.height(24.dp))
        WeekStrip(dates)

        // Today's challenge
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().background(Lime, CardShape).padding(20.dp)) {
            Column {
                Text("Today's Challenge", style = MaterialTheme.typography.headlineSmall, color = Ink)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (doneToday) "Done for today. Go again?" else "${level.title} · ${plan.size} exercises · ${level.blurb}",
                    style = MaterialTheme.typography.bodyMedium, color = Ink.copy(0.7f),
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.background(Navy, Pill)
                            .clickable { ctx.startActivity(Intent(ctx, WorkoutActivity::class.java).putExtra("routine", plan.encode())) }
                            .padding(start = 22.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (doneToday) "GO AGAIN" else "START", style = MaterialTheme.typography.labelLarge, color = Lime)
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.size(36.dp).background(Snow, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.NorthEast, null, tint = Navy, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Ring(completed.toFloat() / LEVELS.size, "${completed * 100 / LEVELS.size}%")
                }
            }
        }

        // filter chips
        Spacer(Modifier.height(20.dp))
        Row(Modifier.background(Card, Pill).padding(6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("All", "Warm-up", "Workout").forEachIndexed { i, t ->
                val on = i == filter
                Text(
                    t, style = MaterialTheme.typography.labelMedium, color = if (on) Snow else Muted,
                    modifier = Modifier.background(if (on) Navy else Color.Transparent, Pill).clickable { filter = i }.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }

        // stat tiles
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).background(PurpleSoft, TileShape).padding(18.dp)) {
                Row { Text("Streak", style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.weight(1f)); Text("🔥", fontSize = 22.sp) }
                Spacer(Modifier.height(18.dp))
                Text("$streak", style = MaterialTheme.typography.displayMedium, color = Ink)
                Text(if (streak == 1) "day" else "days", style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            Column(Modifier.weight(1f).background(Sky, TileShape).padding(18.dp)) {
                Text("My Goals", style = MaterialTheme.typography.titleMedium, color = Ink)
                Spacer(Modifier.height(6.dp))
                Text("Keep it up, you can\nreach Absolute Beast.", style = MaterialTheme.typography.labelSmall, color = Ink.copy(0.7f))
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().background(Card, Pill).padding(4.dp)) {
                    Box(Modifier.fillMaxWidth(maxOf(0.18f, completed.toFloat() / LEVELS.size)).height(26.dp).background(Purple, Pill), contentAlignment = Alignment.Center) {
                        Text("$completed/${LEVELS.size}", style = MaterialTheme.typography.labelSmall, color = Snow)
                    }
                }
            }
        }

        // plan
        Spacer(Modifier.height(24.dp))
        Text("Your plan", style = MaterialTheme.typography.headlineSmall, color = Ink)
        Spacer(Modifier.height(12.dp))
        val shown = plan.filter { filter == 0 || (filter == 1) == it.warmup }
        shown.forEach { step ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).background(tileColor(plan.indexOf(step)), TileShape).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(step.exercise.label, style = MaterialTheme.typography.titleLarge, color = Ink)
                    Text(if (step.warmup) "Warm-up" else "Workout", style = MaterialTheme.typography.labelSmall, color = Ink.copy(0.6f))
                }
                Box(Modifier.size(58.dp).background(Card, CircleShape), contentAlignment = Alignment.Center) {
                    Text(
                        if (step.exercise.holdSec > 0) "${step.target}s" else "×${step.target}",
                        style = MaterialTheme.typography.titleMedium, color = Ink,
                    )
                }
            }
        }
    }
}

@Composable
private fun Ring(fraction: Float, text: String) {
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = 7.dp.toPx(); val inset = s / 2; val arc = Size(size.width - s, size.height - s)
            drawArc(Snow.copy(0.6f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(s))
            drawArc(Navy, -90f, 360f * fraction, false, Offset(inset, inset), arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = Ink)
    }
}

/** Mon..Sun of this week: letters, then day numbers. Today is a lime circle, done days purple. */
@Composable
private fun WeekStrip(dates: Set<LocalDate>) {
    val today = LocalDate.now()
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (i in 0..6) {
            val day = monday.plusDays(i.toLong())
            val isToday = day == today
            val done = day in dates
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(1),
                    style = MaterialTheme.typography.labelMedium, color = if (isToday) Ink else Muted,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.size(40.dp).background(if (isToday) Lime else if (done) Purple else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${day.dayOfMonth}", style = MaterialTheme.typography.titleMedium,
                        color = if (done && !isToday) Snow else if (isToday || day < today) Ink else Muted,
                    )
                }
            }
        }
    }
}

// ---------------- Journey ----------------

private val ROW_H = 132.dp
private val NODE = 72.dp
private fun wobble(i: Int) = (sin(i * 1.7) * 90).dp   // horizontal offset of node i on the winding path

@Composable
private fun Journey(progress: Progress, resumed: Int) {
    val ctx = LocalContext.current
    val completed = remember(resumed) { progress.completed }
    val current = remember(resumed) { progress.levelIndex }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Your Journey", style = MaterialTheme.typography.headlineLarge, color = Ink)
                Text("$completed of ${LEVELS.size} levels done", style = MaterialTheme.typography.bodyMedium, color = Muted)
                Spacer(Modifier.height(16.dp))
            }
        }
        itemsIndexed(LEVELS) { i, level ->
            val state = when {
                i < completed -> NodeState.DONE
                i == current -> NodeState.CURRENT
                else -> NodeState.LOCKED
            }
            JourneyRow(i, level, state, last = i == LEVELS.lastIndex) {
                if (state != NodeState.LOCKED)
                    ctx.startActivity(Intent(ctx, WorkoutActivity::class.java).putExtra("routine", level.steps.encode()))
                else Toast.makeText(ctx, "Finish ${LEVELS[i - 1].title} first", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private enum class NodeState { DONE, CURRENT, LOCKED }

@Composable
private fun JourneyRow(i: Int, level: Level, state: NodeState, last: Boolean, onClick: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        1f, 1.1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
    )
    Box(Modifier.fillMaxWidth().height(ROW_H)) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2 + wobble(i).toPx()
            val nx = size.width / 2 + wobble(i + 1).toPx()
            val cy = size.height / 2
            val stroke = 6.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 14f))
            val col = if (state == NodeState.DONE) Purple else Line
            if (!last) drawLine(col, Offset(cx, cy), Offset(nx, size.height), stroke, pathEffect = dash)
            if (i > 0) drawLine(col, Offset(cx, 0f), Offset(cx, cy), stroke, pathEffect = dash)
        }
        Column(
            Modifier.align(Alignment.Center).offset(x = wobble(i)).clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(NODE)
                    .scale(if (state == NodeState.CURRENT) pulse else 1f)
                    .shadow(if (state == NodeState.LOCKED) 0.dp else 10.dp, CircleShape, ambientColor = Purple.copy(0.3f))
                    .background(
                        when (state) { NodeState.DONE -> Purple; NodeState.CURRENT -> Lime; NodeState.LOCKED -> Card },
                        CircleShape,
                    )
                    .border(if (state == NodeState.LOCKED) 2.dp else 0.dp, Line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (state) { NodeState.DONE -> "✓"; NodeState.CURRENT -> "${i + 1}"; NodeState.LOCKED -> "🔒" },
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (state == NodeState.DONE) Snow else Ink,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(level.title, style = MaterialTheme.typography.titleMedium, color = if (state == NodeState.LOCKED) Muted else Ink, textAlign = TextAlign.Center)
        }
    }
}
