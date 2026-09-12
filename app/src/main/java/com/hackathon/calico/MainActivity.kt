package com.hackathon.calico

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
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
        enableEdgeToEdge()
        setContent { CalicoTheme { App(resumed.intValue) } }
    }

    override fun onResume() { super.onResume(); resumed.intValue++ }
}

@Composable
private fun App(resumed: Int) {
    val ctx = LocalContext.current
    val progress = remember { Progress(ctx) }
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        containerColor = Ink,
        bottomBar = {
            NavigationBar(containerColor = Surface1) {
                val colors = NavigationBarItemDefaults.colors(indicatorColor = Lime.copy(alpha = 0.18f), selectedTextColor = Lime, unselectedTextColor = Fog)
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("🏠") }, label = { Text("Today") }, colors = colors)
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("🗺️") }, label = { Text("Journey") }, colors = colors)
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            // key on resumed so a finished workout is reflected immediately
            remember(resumed) { progress.streak }
            if (tab == 0) Home(progress, resumed) else Journey(progress, resumed)
        }
    }
}

// ---------------- Today ----------------

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
    val level = LEVELS[remember(key) { progress.levelIndex }]

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        // long-press the wordmark to reset progress (demo rehearsals start from a zero streak)
        Text(
            "CALICO", style = MaterialTheme.typography.labelSmall, color = Lime,
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = {
                progress.reset(); resetsKey++
                Toast.makeText(ctx, "Progress reset", Toast.LENGTH_SHORT).show()
            }),
        )
        Spacer(Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Text("🔥", fontSize = 56.sp, modifier = Modifier.padding(bottom = 10.dp))
            Spacer(Modifier.width(8.dp))
            Text("$streak", style = MaterialTheme.typography.displayLarge, color = if (streak > 0) Snow else Fog)
        }
        Text(
            if (streak == 0) "Start your streak today" else if (streak == 1) "day streak" else "day streak",
            style = MaterialTheme.typography.titleMedium, color = Fog,
        )
        Spacer(Modifier.height(20.dp))
        WeekStrip(dates)

        Spacer(Modifier.height(32.dp))
        Text("TODAY'S PLAN", style = MaterialTheme.typography.labelSmall, color = Fog)
        Spacer(Modifier.height(8.dp))
        Text(level.title, style = MaterialTheme.typography.headlineLarge)
        Text(level.blurb, style = MaterialTheme.typography.bodyMedium, color = Fog)
        Spacer(Modifier.height(16.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = Surface1, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                plan.forEach { StepRow(it) }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { ctx.startActivity(Intent(ctx, WorkoutActivity::class.java).putExtra("routine", plan.encode())) },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink),
        ) { Text(if (doneToday) "GO AGAIN" else "START WORKOUT", style = MaterialTheme.typography.labelLarge) }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                // Room-scan mode lives in the AR module; launch it by name if it's been added.
                runCatching { ctx.startActivity(Intent(ctx, Class.forName("com.hackathon.calico.ScanActivity"))) }
                    .onFailure { Toast.makeText(ctx, "Room scan is on its way", Toast.LENGTH_SHORT).show() }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(20.dp),
        ) { Text("📷  Scan my room", color = Snow) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StepRow(step: Step) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(Lime, CircleShape))
        Spacer(Modifier.width(14.dp))
        Text(step.exercise.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(
            if (step.exercise.holdSec > 0) "${step.target}s" else "×${step.target}",
            style = MaterialTheme.typography.titleMedium, color = Lime,
        )
    }
}

/** Last 7 days, today on the right. */
@Composable
private fun WeekStrip(dates: Set<LocalDate>) {
    val today = LocalDate.now()
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        for (i in 6 downTo 0) {
            val day = today.minusDays(i.toLong())
            val done = day in dates
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(36.dp)
                        .background(if (done) Lime else Surface1, CircleShape)
                        .border(if (i == 0) 2.dp else 0.dp, if (i == 0) Snow else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (done) Text("✓", color = Ink, style = MaterialTheme.typography.labelLarge) }
                Spacer(Modifier.height(6.dp))
                Text(
                    day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2),
                    style = MaterialTheme.typography.labelSmall, color = if (i == 0) Snow else Fog,
                )
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("YOUR JOURNEY", style = MaterialTheme.typography.labelSmall, color = Lime)
                Text("$completed of ${LEVELS.size} levels", style = MaterialTheme.typography.headlineLarge)
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
        1f, 1.12f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
    )
    Box(Modifier.fillMaxWidth().height(ROW_H)) {
        // path: straight down into this node, then slanted toward the next node's x
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2 + wobble(i).toPx()
            val nx = size.width / 2 + wobble(i + 1).toPx()
            val cy = size.height / 2
            val stroke = 6.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 14f))
            val col = if (state == NodeState.DONE) Lime else Surface2
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
                    .background(
                        when (state) { NodeState.DONE -> Lime; NodeState.CURRENT -> Ink; NodeState.LOCKED -> Surface1 },
                        CircleShape,
                    )
                    .border(4.dp, if (state == NodeState.LOCKED) Surface2 else Lime, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (state) { NodeState.DONE -> "✓"; NodeState.CURRENT -> "${i + 1}"; NodeState.LOCKED -> "🔒" },
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (state == NodeState.DONE) Ink else Snow,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(level.title, style = MaterialTheme.typography.titleMedium, color = if (state == NodeState.LOCKED) Fog else Snow, textAlign = TextAlign.Center)
        }
    }
}
