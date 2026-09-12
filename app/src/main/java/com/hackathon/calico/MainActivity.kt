package com.hackathon.calico

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
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
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val resumed = mutableIntStateOf(0)   // bumped on resume so screens re-read progress
    private val requestedTab = mutableIntStateOf(0)
    private val navigationRequest = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(0, 0), navigationBarStyle = SystemBarStyle.light(0, 0))
        requestedTab.intValue=intent.getIntExtra("tab",0)
        setContent { CalicoTheme { App(resumed.intValue,requestedTab.intValue,navigationRequest.intValue) } }
    }

    override fun onResume() { super.onResume(); resumed.intValue++ }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); requestedTab.intValue=intent.getIntExtra("tab",0); navigationRequest.intValue++; resumed.intValue++ }
}

fun openScan(ctx: android.content.Context) {
    ctx.startActivity(Intent(ctx, com.calico.roomscan.ScanActivity::class.java))
}

private fun startRoutine(ctx: android.content.Context, steps: List<Step>) =
    ctx.startActivity(Intent(ctx, WorkoutActivity::class.java).putExtra("routine", steps.encode()))

@Composable
private fun App(resumed: Int, requestedTab: Int, navigationRequest: Int) {
    val ctx = LocalContext.current
    val progress = remember { Progress(ctx) }
    var tab by remember { mutableIntStateOf(0) }
    var voice by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(requestedTab, navigationRequest) { tab = requestedTab.coerceIn(0, 2) }
    com.hackathon.calico.voice.VoiceActionBindings(mapOf(
        "Home" to { tab = 0 }, "Journey" to { tab = 1 }, "Overview" to { tab = 1 },
        "Exercises" to { tab = 2 }, "Scan" to { openScan(ctx) }, "Voice" to { voice = true },
        "Open coach" to { ctx.startActivity(Intent(ctx, CoachActivity::class.java)) }
    ))
    Box(Modifier.fillMaxSize().background(Bg)) {
        Crossfade(tab, label = "tab", animationSpec = tween(220)) { t ->
            when (t) { 0 -> Home(progress, resumed) { voice = true }; 1 -> Overview(progress, resumed); else -> Exercises() }
        }
        BumpBar(TABS, if (voice) 4 else tab, Modifier.align(Alignment.BottomCenter)) { i ->
            when (i) {
                3 -> openScan(ctx)
                4 -> voice = true
                else -> tab = i
            }
        }
        if (voice) VoiceSheet(autoListen = true) { voice = false }
    }
}

private val TABS = listOf<Pair<ImageVector, String>>(
    Icons.Outlined.Home to "Home", Icons.Outlined.BarChart to "Overview",
    Icons.Outlined.FitnessCenter to "Exercises", Icons.Outlined.CameraAlt to "Scan",
    Icons.Outlined.Mic to "Voice",
)

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(24.dp))
    Text(text, style = MaterialTheme.typography.headlineSmall, color = Ink)
    Spacer(Modifier.height(12.dp))
}

// ---------------- Home ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Home(progress: Progress, resumed: Int, onVoice: () -> Unit) {
    val ctx = LocalContext.current
    var resetsKey by remember { mutableIntStateOf(0) }
    val key = resumed + resetsKey
    val streak = remember(key) { progress.streak }
    val dates = remember(key) { progress.dates }
    val doneToday = remember(key) { progress.doneToday }
    val plan = remember(key) { progress.todayPlan }
    val completed = remember(key) { progress.completed }
    val level = LEVELS[remember(key) { progress.levelIndex }]
    com.hackathon.calico.voice.VoiceActionBindings(mapOf(
        "Start" to { startRoutine(ctx, plan) }, "Go again" to { startRoutine(ctx, plan) }
    ))
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 120.dp),
    ) {
        // header: avatar top-left, greeting, streak chip
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(Accent).combinedClickable(onClick = {}, onLongClick = {
                    progress.reset(); resetsKey++   // demo rehearsals start from a zero streak
                    Toast.makeText(ctx, "Progress reset", Toast.LENGTH_SHORT).show()
                }),
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.Cat, "Calico", tint = OnAccent, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("HI THERE", style = MaterialTheme.typography.titleLarge, color = Ink)
                    Spacer(Modifier.width(6.dp))
                    Icon(Lucide.Hand, null, tint = Ink, modifier = Modifier.size(18.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.Zap, null, tint = Muted, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Level ${completed + 1} · ${level.title}", style = MaterialTheme.typography.labelMedium, color = Muted)
                }
            }
            Row(
                Modifier.background(Snow, Pill).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Flame, null, tint = OnAccent, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("$streak", style = MaterialTheme.typography.titleMedium, color = OnAccent)
            }
        }

        Spacer(Modifier.height(24.dp))
        WeekStrip(dates)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Offline coach", style = MaterialTheme.typography.labelLarge, color = Accent,
                modifier = Modifier.weight(1f).clip(Pill).clickable {
                    ctx.startActivity(Intent(ctx, CoachActivity::class.java))
                }.padding(12.dp))
            Box(Modifier.size(48.dp).clip(CircleShape).background(Accent).clickable(onClick = onVoice),
                contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Mic, "Voice", tint = OnAccent) }
        }

        // Today's workout
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().background(Card, CardShape).padding(20.dp)) {
            Column {
                Text("Today's Workout", style = MaterialTheme.typography.headlineSmall, color = Ink)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (doneToday) "Done for today. Go again?" else "${level.title} · ${plan.size} exercises · ${level.blurb}",
                    style = MaterialTheme.typography.bodyMedium, color = Muted,
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clip(Pill).background(Accent).clickable { startRoutine(ctx, plan) }.padding(start = 22.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (doneToday) "GO AGAIN" else "START", style = MaterialTheme.typography.labelLarge, color = OnAccent)
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.size(36.dp).background(Snow, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.NorthEast, null, tint = Charcoal, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Ring(completed.toFloat() / LEVELS.size, "${completed * 100 / LEVELS.size}%")
                }
            }
        }

        // splits: two per row, calico patches in turn
        SectionTitle("Exercise splits")
        val patches = listOf(Card, Snow, Accent, Card, Card, Snow)   // white, dark and coral like the reference
        SPLITS.chunked(2).forEachIndexed { r, pair ->
            Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEachIndexed { c, split -> SplitCard(split, patches[(r * 2 + c) % patches.size], Modifier.weight(1f)) { startRoutine(ctx, split.steps) } }
            }
        }

        // today's plan: dark lower block with dark-grey and coral rows
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth().background(Snow, RoundedCornerShape(28.dp)).padding(16.dp)) {
        Text("Today's plan", style = MaterialTheme.typography.headlineSmall, color = Card, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
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
                Box(Modifier.size(58.dp).background(onTile(tile), CircleShape), contentAlignment = Alignment.Center) {
                    Text(
                        if (step.exercise.holdSec > 0) "${step.target}s" else "×${step.target}",
                        style = MaterialTheme.typography.titleMedium, color = tile,
                    )
                }
            }
        }
        }
        Spacer(Modifier.height(16.dp))
        com.hackathon.calico.voice.HandsFreeControl()
    }
}

@Composable
private fun SplitCard(split: Split, tile: Color, modifier: Modifier, onClick: () -> Unit) {
    val fg = onTile(tile)
    Column(modifier.clip(TileShape).background(tile).clickable(onClick = onClick).padding(18.dp)) {
        Icon(splitIcon(split.title), null, tint = fg, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(14.dp))
        Text(split.title, style = MaterialTheme.typography.titleLarge, color = fg)
        Text("${split.steps.size} exercises", style = MaterialTheme.typography.labelSmall, color = fg.copy(0.65f))
    }
}

@Composable
private fun Ring(fraction: Float, text: String) {
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = 7.dp.toPx(); val inset = s / 2; val arc = Size(size.width - s, size.height - s)
            drawArc(Line, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(s))
            drawArc(Accent, -90f, 360f * fraction, false, Offset(inset, inset), arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = Ink)
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
                Text("$streak day streak · ${dates.size} active days", style = MaterialTheme.typography.bodyMedium, color = Muted)

                // GitHub-style heatmap: one column per week, Mon at the top
                Spacer(Modifier.height(20.dp))
                Column(Modifier.fillMaxWidth().background(Card, CardShape).padding(16.dp)) {
                    Text("Activity", style = MaterialTheme.typography.titleMedium, color = Ink)
                    Spacer(Modifier.height(12.dp))
                    Heatmap(dates, today)
                }

                // daily calorie gauge
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().background(Card, CardShape).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Estimated daily calories", style = MaterialTheme.typography.labelMedium, color = Muted)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (todayStats.kcal >= KCAL_GOAL) "Goal smashed" else if (todayStats.kcal > 0) "Almost there" else "Let's get moving",
                            style = MaterialTheme.typography.headlineSmall, color = Ink,
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            if (todayStats.kcal > 0) Lucide.Flame else Lucide.Cat, null,
                            tint = Ink, modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Gauge(todayStats.kcal, KCAL_GOAL)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.background(Accent, Pill).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Lucide.ThumbsUp, null, tint = OnAccent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (todayStats.kcal >= KCAL_GOAL) "About ${todayStats.kcal} kcal today"
                            else "${KCAL_GOAL - todayStats.kcal} kcal more to finish it",
                            style = MaterialTheme.typography.labelMedium, color = OnAccent,
                        )
                    }
                }

                // today stats: pastel tiles with mini charts of the last 7 days
                SectionTitle("Today Stats")
                val reps = week.reversed().map { it.reps.toFloat() }
                val kcal = week.reversed().map { it.kcal.toFloat() }
                val mins = week.reversed().map { it.secs / 60f }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(Lucide.Repeat, "Reps", "${todayStats.reps}", "reps", Charcoal, Modifier.weight(1f)) { Sparkline(reps, Accent) }
                    StatTile(Lucide.Flame, "Calories", "${todayStats.kcal}", "kcal", Accent, Modifier.weight(1f)) { Bars(kcal, OnAccent) }
                    StatTile(Lucide.Timer, "Time", "${todayStats.secs / 60}", "min", Snow, Modifier.weight(1f)) { Bars(mins, OnAccent) }
                }
                Text("Last 7 days. Calories are estimates.", style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(top = 6.dp, start = 4.dp))

                // goal: one wide card
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().background(Accent, CardShape).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Goal", style = MaterialTheme.typography.labelMedium, color = OnAccent.copy(0.8f))
                        Text(LEVELS[current].title, style = MaterialTheme.typography.headlineSmall, color = OnAccent)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("level ${current + 1} of ${LEVELS.size} ·", style = MaterialTheme.typography.labelSmall, color = OnAccent.copy(0.8f))
                            Spacer(Modifier.width(4.dp))
                            Icon(Lucide.Flame, null, tint = OnAccent.copy(0.8f), modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("$streak day streak", style = MaterialTheme.typography.labelSmall, color = OnAccent.copy(0.8f))
                        }
                        Spacer(Modifier.height(12.dp))
                        GoalBar(completed.toFloat() / LEVELS.size, "${completed * 100 / LEVELS.size}%")
                    }
                    Spacer(Modifier.width(16.dp))
                    Box(Modifier.size(64.dp).background(Snow, CircleShape), contentAlignment = Alignment.Center) {
                        Text("${current + 1}", style = MaterialTheme.typography.headlineLarge, color = Charcoal)
                    }
                }

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
        Text("${WEEKS - 1} weeks ago", style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.weight(1f))
        Text("this week", style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

/** Half-ring gauge: progress in accent, the rest a hatched track, percentage in the middle. */
@Composable
private fun Gauge(done: Int, goal: Int) {
    val target = (done.toFloat() / goal).coerceIn(0f, 1f)
    val f by animateFloatAsState(target, spring(stiffness = Spring.StiffnessLow), label = "gauge")
    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.size(220.dp, 120.dp)) {
            val s = 30.dp.toPx(); val inset = s / 2
            val arc = Size(size.width - s, size.width - s)
            val off = Offset(inset, inset + 6.dp.toPx())
            // hatched track: thin diagonal ticks along the arc
            drawArc(Slate, 180f, 180f, false, off, arc, style = Stroke(s))
            val cx = size.width / 2; val cy = off.y + arc.height / 2; val r = arc.width / 2
            for (i in 0..36) {
                val a = Math.toRadians(180.0 + 5.0 * i)
                val p1 = Offset(cx + (r - inset + 4) * cos(a).toFloat(), cy + (r - inset + 4) * sin(a).toFloat())
                val p2 = Offset(cx + (r + inset - 4) * cos(a).toFloat(), cy + (r + inset - 4) * sin(a).toFloat())
                drawLine(Muted.copy(0.35f), p1, p2, 2.dp.toPx())
            }
            drawArc(Accent, 180f, 180f * f, false, off, arc, style = Stroke(s, cap = StrokeCap.Butt))
        }
        Column(Modifier.padding(bottom = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${(target * 100).toInt()}", style = MaterialTheme.typography.displayMedium, color = Ink)
                Text("%", style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.padding(bottom = 8.dp))
            }
            Text("Progress", style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}

/** Pastel tile: emoji chip, mini chart, big number. */
@Composable
private fun StatTile(icon: ImageVector, label: String, value: String, unit: String, tile: Color, modifier: Modifier, chart: @Composable () -> Unit) {
    val fg = onTile(tile)
    Column(modifier.background(tile, TileShape).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).background(Card.copy(if (fg == Ink) 0.7f else 0.15f), CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = fg, modifier = Modifier.size(13.dp)) }
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = fg)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(48.dp)) { chart() }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = fg)
            Spacer(Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.labelSmall, color = fg.copy(0.7f), modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/** Smooth line through the values with a soft fill under it. */
@Composable
private fun Sparkline(values: List<Float>, color: Color) {
    val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
    Canvas(Modifier.fillMaxSize()) {
        val n = values.size; if (n < 2) return@Canvas
        val pts = values.mapIndexed { i, v -> Offset(size.width * i / (n - 1), size.height * (1 - v / max * 0.9f) - 2.dp.toPx()) }
        val line = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until n) {
                val p = pts[i - 1]; val q = pts[i]; val mx = (p.x + q.x) / 2
                cubicTo(mx, p.y, mx, q.y, q.x, q.y)
            }
        }
        val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, color.copy(0.25f))
        drawPath(line, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Rounded bars, one per value. */
@Composable
private fun Bars(values: List<Float>, color: Color) {
    val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
    Canvas(Modifier.fillMaxSize()) {
        val n = values.size; val gap = 4.dp.toPx(); val w = (size.width - gap * (n - 1)) / n
        values.forEachIndexed { i, v ->
            val h = (size.height * v / max).coerceAtLeast(4.dp.toPx())
            drawRoundRect(color.copy(if (i == n - 1) 1f else 0.45f), Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(w / 2))
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
                Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(TileShape).background(tile)
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
                val fg = when (state) { NodeState.DONE -> OnAccent; NodeState.CURRENT -> Charcoal; NodeState.LOCKED -> Muted }
                when (state) {
                    NodeState.DONE -> Icon(Lucide.Check, "Done", tint = fg, modifier = Modifier.size(30.dp))
                    NodeState.LOCKED -> Icon(Lucide.Lock, "Locked", tint = fg, modifier = Modifier.size(26.dp))
                    NodeState.CURRENT -> Text("${i + 1}", style = MaterialTheme.typography.headlineSmall, color = fg)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(level.title, style = MaterialTheme.typography.titleMedium, color = if (state == NodeState.LOCKED) Muted else Ink, textAlign = TextAlign.Center)
        }
    }
}
