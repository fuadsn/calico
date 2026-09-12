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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
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
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(0), navigationBarStyle = SystemBarStyle.dark(0))
        setContent { CalicoTheme { App(resumed.intValue) } }
    }

    override fun onResume() { super.onResume(); resumed.intValue++ }
}

fun openScan(ctx: android.content.Context) {
    ctx.startActivity(Intent(ctx, com.calico.roomscan.ScanActivity::class.java))
}

private fun startRoutine(ctx: android.content.Context, steps: List<Step>) =
    ctx.startActivity(Intent(ctx, WorkoutActivity::class.java).putExtra("routine", steps.encode()))

@Composable
private fun App(resumed: Int) {
    val ctx = LocalContext.current
    val progress = remember { Progress(ctx) }
    var tab by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(Bg)) {
        when (tab) { 0 -> Home(progress, resumed); 1 -> Overview(progress, resumed); else -> Exercises() }
        BottomBar(tab, Modifier.align(Alignment.BottomCenter)) { i -> if (i == 3) openScan(ctx) else tab = i }
    }
}

/** Floating pill with circular icon buttons, the selected one filled with the accent. */
@Composable
private fun BottomBar(selected: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    val items = listOf<Pair<ImageVector, String>>(
        Icons.Outlined.Home to "Home", Icons.Outlined.Insights to "Overview",
        Icons.Outlined.FitnessCenter to "Exercises", Icons.Outlined.CameraAlt to "Scan",
    )
    Row(
        modifier.navigationBarsPadding().padding(bottom = 12.dp).shadow(16.dp, Pill, ambientColor = Color.Black.copy(0.4f)).background(Card, Pill).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items.forEachIndexed { i, (icon, name) ->
            val on = i == selected
            Box(
                Modifier.size(56.dp).background(if (on) Accent else Cloud, CircleShape).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, name, tint = if (on) OnAccent else onTile(Cloud)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(24.dp))
    Text(text, style = MaterialTheme.typography.headlineSmall, color = Ink)
    Spacer(Modifier.height(12.dp))
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 120.dp),
    ) {
        // header: avatar top-left, greeting, streak chip
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).background(Accent, CircleShape).combinedClickable(onClick = {}, onLongClick = {
                    progress.reset(); resetsKey++   // demo rehearsals start from a zero streak
                    Toast.makeText(ctx, "Progress reset", Toast.LENGTH_SHORT).show()
                }),
                contentAlignment = Alignment.Center,
            ) { Text("🐈", fontSize = 28.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("HI THERE 👋", style = MaterialTheme.typography.titleLarge, color = Ink)
                Text("⚡ Level ${completed + 1} · ${level.title}", style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            Box(Modifier.background(Charcoal, Pill).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("🔥 $streak", style = MaterialTheme.typography.titleMedium, color = Ink)
            }
        }

        Spacer(Modifier.height(24.dp))
        WeekStrip(dates)

        // Today's workout
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().background(Accent, CardShape).padding(20.dp)) {
            Column {
                Text("Today's Workout", style = MaterialTheme.typography.headlineSmall, color = OnAccent)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (doneToday) "Done for today. Go again?" else "${level.title} · ${plan.size} exercises · ${level.blurb}",
                    style = MaterialTheme.typography.bodyMedium, color = OnAccent.copy(0.7f),
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.background(Snow, Pill).clickable { startRoutine(ctx, plan) }.padding(start = 22.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (doneToday) "GO AGAIN" else "START", style = MaterialTheme.typography.labelLarge, color = Charcoal)
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.size(36.dp).background(Accent, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.NorthEast, null, tint = OnAccent, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Ring(completed.toFloat() / LEVELS.size, "${completed * 100 / LEVELS.size}%")
                }
            }
        }

        // splits: two per row
        SectionTitle("Exercise splits")
        SPLITS.chunked(2).forEachIndexed { r, pair ->
            Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEachIndexed { c, split ->
                    val tile = tileColor(r * 2 + c + 1)
                    Column(Modifier.weight(1f).background(tile, TileShape).clickable { startRoutine(ctx, split.steps) }.padding(18.dp)) {
                        Text(split.emoji, fontSize = 26.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(split.title, style = MaterialTheme.typography.titleLarge, color = onTile(tile))
                        Text("${split.steps.size} exercises", style = MaterialTheme.typography.labelSmall, color = onTile(tile).copy(0.6f))
                    }
                }
            }
        }

        // today's plan
        SectionTitle("Today's plan")
        plan.forEachIndexed { i, step ->
            val tile = tileColor(i)
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).background(tile, TileShape).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(step.exercise.label, style = MaterialTheme.typography.titleLarge, color = onTile(tile))
                    Text("View workout", color = onTile(tile).copy(0.8f), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable {
                            ctx.startActivity(Intent(ctx, com.calico.roomscan.PreviewActivity::class.java)
                                .putExtra("exercise", step.exercise.name))
                        }.padding(vertical = 6.dp))
                    Text(if (step.warmup) "Warm-up" else "Workout", style = MaterialTheme.typography.labelSmall, color = onTile(tile).copy(0.6f))
                }
                Box(Modifier.size(58.dp).background(Snow, CircleShape), contentAlignment = Alignment.Center) {
                    Text(
                        if (step.exercise.holdSec > 0) "${step.target}s" else "×${step.target}",
                        style = MaterialTheme.typography.titleMedium, color = Charcoal,
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
            drawArc(OnAccent.copy(0.15f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(s))
            drawArc(OnAccent, -90f, 360f * fraction, false, Offset(inset, inset), arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = OnAccent)
    }
}

/** Mon..Sun of this week: letters, then day numbers. Today is a strong circle, done days accent. */
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
                    Modifier.size(40.dp).background(if (isToday) Snow else if (done) Accent else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${day.dayOfMonth}", style = MaterialTheme.typography.titleMedium,
                        color = if (isToday) Charcoal else if (done) OnAccent else if (day < today) Ink else Muted,
                    )
                }
            }
        }
    }
}

// ---------------- Overview ----------------

private const val WEEKS = 16

@Composable
private fun Overview(progress: Progress, resumed: Int) {
    val ctx = LocalContext.current
    val today = LocalDate.now()
    val dates = remember(resumed) { progress.dates }
    val streak = remember(resumed) { progress.streak }
    val completed = remember(resumed) { progress.completed }
    val current = remember(resumed) { progress.levelIndex }
    val todayStats = remember(resumed) { progress.day(today) }
    val week = remember(resumed) { (0..6).map { progress.day(today.minusDays(it.toLong())) } }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Overview", style = MaterialTheme.typography.headlineLarge, color = Ink)
                Text("$streak day streak · ${dates.size} workouts total", style = MaterialTheme.typography.bodyMedium, color = Muted)

                // GitHub-style heatmap: one column per week, Mon at the top
                Spacer(Modifier.height(20.dp))
                Column(Modifier.fillMaxWidth().background(Card, CardShape).padding(16.dp)) {
                    Text("Activity", style = MaterialTheme.typography.titleMedium, color = Ink)
                    Spacer(Modifier.height(12.dp))
                    Heatmap(dates, today)
                }

                // goal left, calories right
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).background(Charcoal, TileShape).padding(18.dp)) {
                        Text("Goal", style = MaterialTheme.typography.titleMedium, color = Ink)
                        Spacer(Modifier.height(6.dp))
                        Text(LEVELS[current].title, style = MaterialTheme.typography.headlineSmall, color = Accent)
                        Text("level ${current + 1} of ${LEVELS.size}", style = MaterialTheme.typography.labelSmall, color = Muted)
                        Spacer(Modifier.height(14.dp))
                        Box(Modifier.fillMaxWidth().background(Snow.copy(0.15f), Pill).padding(4.dp)) {
                            Box(Modifier.fillMaxWidth(maxOf(0.18f, completed.toFloat() / LEVELS.size)).height(26.dp).background(Snow, Pill), contentAlignment = Alignment.Center) {
                                Text("$completed/${LEVELS.size}", style = MaterialTheme.typography.labelSmall, color = Charcoal)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("🔥 $streak day streak", style = MaterialTheme.typography.labelMedium, color = Ink)
                    }
                    Column(Modifier.weight(1f).background(Slate, TileShape).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Calories", style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        Donut(todayStats.kcal, KCAL_GOAL)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Stat("${todayStats.kcal}", "burned", Modifier.weight(1f))
                            Stat("${maxOf(0, KCAL_GOAL - todayStats.kcal)}", "left", Modifier.weight(1f))
                        }
                    }
                }

                // this week
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().background(Card, TileShape).padding(18.dp)) {
                    Stat("${week.count { it.kcal > 0 || it.reps > 0 }}", "workouts", Modifier.weight(1f))
                    Stat("${week.sumOf { it.reps }}", "reps", Modifier.weight(1f))
                    Stat("${week.sumOf { it.secs } / 60}", "minutes", Modifier.weight(1f))
                    Stat("${week.sumOf { it.kcal }}", "kcal", Modifier.weight(1f))
                }
                Text("last 7 days", style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(top = 6.dp, start = 4.dp))

                SectionTitle("Your journey")
            }
        }
        itemsIndexed(LEVELS) { i, level ->
            val state = when {
                i < completed -> NodeState.DONE
                i == current -> NodeState.CURRENT
                else -> NodeState.LOCKED
            }
            JourneyRow(i, level, state, last = i == LEVELS.lastIndex) {
                if (state != NodeState.LOCKED) startRoutine(ctx, level.steps)
                else Toast.makeText(ctx, "Finish ${LEVELS[i - 1].title} first", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, style = MaterialTheme.typography.headlineSmall, color = Ink)
    Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
}

/** WEEKS columns of 7 cells, this week last. Done days accent, today outlined. */
@Composable
private fun Heatmap(dates: Set<LocalDate>, today: LocalDate) {
    val start = today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks((WEEKS - 1).toLong())
    val cell = 6.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (w in 0 until WEEKS) Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (d in 0..6) {
                val day = start.plusWeeks(w.toLong()).plusDays(d.toLong())
                val done = day in dates
                Box(
                    Modifier.size(14.dp)
                        .background(if (done) Accent else if (day > today) Color.Transparent else Charcoal, RoundedCornerShape(3.dp))
                        .border(if (day == today) 2.dp else 0.dp, if (day == today) Snow else Color.Transparent, RoundedCornerShape(3.dp)),
                )
            }
        }
    }
    Spacer(Modifier.height(cell))
    Row(Modifier.fillMaxWidth()) {
        Text("${WEEKS} weeks ago", style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.weight(1f))
        Text("this week", style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

/** Calorie ring: burned in accent, the rest of the goal in a faint track. */
@Composable
private fun Donut(done: Int, goal: Int) {
    val f = (done.toFloat() / goal).coerceIn(0f, 1f)
    Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = 12.dp.toPx(); val inset = s / 2; val arc = Size(size.width - s, size.height - s)
            drawArc(Snow.copy(0.15f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(s))
            drawArc(Accent, -90f, 360f * f, false, Offset(inset, inset), arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$done", style = MaterialTheme.typography.headlineLarge, color = Ink)
            Text("of $goal", style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}

// ---------------- Exercises ----------------

/** Every exercise, free mode: tap one and count until you stop. */
@Composable
private fun Exercises() {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 120.dp),
    ) {
        Text("Exercises", style = MaterialTheme.typography.headlineLarge, color = Ink)
        Text("Free mode, no target. Tap one to start counting.", style = MaterialTheme.typography.bodyMedium, color = Muted)
        Spacer(Modifier.height(16.dp))
        Exercise.values().forEachIndexed { i, e ->
            val tile = tileColor(i)
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).background(tile, TileShape)
                    .clickable { ctx.startActivity(Intent(ctx, WorkoutActivity::class.java).putExtra("exercise", e.name)) }
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(e.label, style = MaterialTheme.typography.titleLarge, color = onTile(tile))
                    Text(
                        if (e.holdSec > 0) "Hold · ${e.holdSec}s" else "Reps · ${e.cue.lowercase()}",
                        style = MaterialTheme.typography.labelSmall, color = onTile(tile).copy(0.6f),
                    )
                }
                Icon(Icons.Outlined.NorthEast, null, tint = onTile(tile).copy(0.6f))
            }
        }
    }
}

// ---------------- Journey (inside Overview) ----------------

private val ROW_H = 132.dp
private val NODE = 72.dp
private fun wobble(i: Int) = (sin(i * 1.7) * 90).dp   // horizontal offset of node i on the winding path

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
            val col = if (state == NodeState.DONE) Accent else Line
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
                    .shadow(if (state == NodeState.LOCKED) 0.dp else 10.dp, CircleShape, ambientColor = Accent.copy(0.5f))
                    .background(
                        when (state) { NodeState.DONE -> Accent; NodeState.CURRENT -> Snow; NodeState.LOCKED -> Card },
                        CircleShape,
                    )
                    .border(if (state == NodeState.LOCKED) 2.dp else 0.dp, Line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (state) { NodeState.DONE -> "✓"; NodeState.CURRENT -> "${i + 1}"; NodeState.LOCKED -> "🔒" },
                    style = MaterialTheme.typography.headlineSmall,
                    color = when (state) { NodeState.DONE -> OnAccent; NodeState.CURRENT -> Charcoal; NodeState.LOCKED -> Muted },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(level.title, style = MaterialTheme.typography.titleMedium, color = if (state == NodeState.LOCKED) Muted else Ink, textAlign = TextAlign.Center)
        }
    }
}
