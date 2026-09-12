package com.hackathon.calico

import android.content.Context
import java.time.LocalDate

/** One exercise with a rep target (or seconds for holds). */
data class Step(val exercise: Exercise, val target: Int, val warmup: Boolean = false)

/** Serialise as "JUMPING_JACK:20w,PUSHUP:10,PLANK:30" (w = warm-up) so other modes can hand a plan over via a String. */
fun List<Step>.encode() = joinToString(",") { "${it.exercise.name}:${it.target}${if (it.warmup) "w" else ""}" }
fun decodeSteps(s: String) = s.split(",").filter { it.isNotBlank() }.map {
    val (e, n) = it.split(":"); Step(Exercise.valueOf(e.trim()), n.trim().trimEnd('w').toInt(), warmup = n.trim().endsWith("w"))
}

private fun warm(e: Exercise, n: Int) = Step(e, n, warmup = true)

data class Level(val title: String, val blurb: String, val steps: List<Step>)

val LEVELS = listOf(
    Level("Floor Basics", "Hands on a chair, then the floor.", listOf(
        warm(Exercise.JUMPING_JACK, 15), warm(Exercise.HIGH_KNEES, 20),
        Step(Exercise.INCLINE_PUSHUP, 8), Step(Exercise.SQUAT, 10), Step(Exercise.SITUP, 10), Step(Exercise.PLANK, 20),
    )),
    Level("Living Room", "Full pushups. No gear.", listOf(
        warm(Exercise.JUMPING_JACK, 20), warm(Exercise.HIGH_KNEES, 30),
        Step(Exercise.PUSHUP, 10), Step(Exercise.SQUAT, 15), Step(Exercise.SITUP, 15), Step(Exercise.PLANK, 30),
    )),
    Level("Pike Master", "Shoulders take the load.", listOf(
        warm(Exercise.JUMPING_JACK, 25), warm(Exercise.HIGH_KNEES, 30),
        Step(Exercise.PIKE_PUSHUP, 6), Step(Exercise.SQUAT, 20), Step(Exercise.SITUP, 20), Step(Exercise.PLANK, 45),
    )),
    Level("Chair Master", "Any chair is a bench.", listOf(
        warm(Exercise.JUMPING_JACK, 25), Step(Exercise.DIP, 8), Step(Exercise.PIKE_PUSHUP, 8), Step(Exercise.LUNGE, 12), Step(Exercise.LEG_RAISE, 10),
    )),
    Level("Doorframe Puller", "Pull-up bar unlocked.", listOf(
        warm(Exercise.HIGH_KNEES, 30), Step(Exercise.PULLUP, 3), Step(Exercise.PUSHUP, 15), Step(Exercise.MOUNTAIN_CLIMBER, 20), Step(Exercise.PLANK, 45),
    )),
    Level("Absolute Beast", "The whole room is a gym.", listOf(
        warm(Exercise.JUMPING_JACK, 30), warm(Exercise.HIGH_KNEES, 40),
        Step(Exercise.PIKE_PUSHUP, 12), Step(Exercise.PULLUP, 8), Step(Exercise.SQUAT, 30), Step(Exercise.SITUP, 30), Step(Exercise.PLANK, 60),
    )),
)

/** Exercise splits: pick a body part and go. Shown on Home under today's workout. */
data class Split(val title: String, val steps: List<Step>) {
    /** Rough length: 3 s a rep, holds as-is, 5 s rest between steps. */
    val minutes get() = (steps.sumOf { if (it.exercise.holdSec > 0) it.target else it.target * 3 } + 5 * (steps.size - 1) + 59) / 60
}

val SPLITS = listOf(
    Split("Push", listOf(warm(Exercise.JUMPING_JACK, 15), Step(Exercise.INCLINE_PUSHUP, 8), Step(Exercise.PUSHUP, 8), Step(Exercise.PIKE_PUSHUP, 5), Step(Exercise.DIP, 6))),
    Split("Legs", listOf(warm(Exercise.HIGH_KNEES, 20), Step(Exercise.SQUAT, 15), Step(Exercise.LUNGE, 10), Step(Exercise.MOUNTAIN_CLIMBER, 20))),
    Split("Core", listOf(warm(Exercise.HIGH_KNEES, 20), Step(Exercise.SITUP, 15), Step(Exercise.LEG_RAISE, 10), Step(Exercise.PLANK, 30))),
    Split("Cardio", listOf(Step(Exercise.JUMPING_JACK, 30), Step(Exercise.HIGH_KNEES, 40), Step(Exercise.MOUNTAIN_CLIMBER, 30))),
    Split("Pull", listOf(warm(Exercise.ARM_CIRCLE, 15), Step(Exercise.PULLUP, 5), Step(Exercise.ARM_RAISE, 15))),
    Split("Stretch", listOf(Step(Exercise.OVERHEAD_STRETCH, 20), Step(Exercise.CROSS_BODY_STRETCH, 20), Step(Exercise.PLANK, 20))),
)

/** Daily calorie goal and a rough burn per rep / per held second. ponytail: flat MET-ish guesses, no body weight. */
const val KCAL_GOAL = 300
fun kcalOf(step: Step, count: Int): Float = count * when {
    step.exercise.holdSec > 0 -> 0.07f
    else -> when (step.exercise) {
        Exercise.PULLUP -> 0.6f
        Exercise.PIKE_PUSHUP -> 0.45f
        Exercise.PUSHUP, Exercise.DIP -> 0.4f
        Exercise.SQUAT, Exercise.LUNGE -> 0.35f
        Exercise.INCLINE_PUSHUP -> 0.3f
        Exercise.SITUP, Exercise.LEG_RAISE -> 0.25f
        Exercise.JUMPING_JACK, Exercise.MOUNTAIN_CLIMBER -> 0.2f
        Exercise.HIGH_KNEES -> 0.15f
        else -> 0.1f
    }
}

/** What got done on one day. */
data class Day(val reps: Int = 0, val secs: Int = 0, val kcal: Int = 0)

/** Consecutive days ending today or yesterday. Pure so it's testable. */
fun streakOf(dates: Set<LocalDate>, today: LocalDate): Int {
    var day = if (today in dates) today else today.minusDays(1)
    var n = 0
    while (day in dates) { n++; day = day.minusDays(1) }
    return n
}

/** Local-only progress. SharedPreferences is plenty; no sync, no accounts. */
class Progress(ctx: Context) {
    private val p = ctx.getSharedPreferences("progress", Context.MODE_PRIVATE)

    val dates: Set<LocalDate> get() = p.getStringSet("dates", emptySet())!!.map(LocalDate::parse).toSet()
    val completed: Int get() = p.getInt("completed", 0)
    val streak: Int get() = streakOf(dates, LocalDate.now())
    val doneToday: Boolean get() = LocalDate.now() in dates

    /** Current level index: one past the last completed, capped at the final level. */
    val levelIndex get() = minOf(completed, LEVELS.lastIndex)

    /** Today's plan: whatever the room scan wrote, else the current level. Room scan writes pref "plan". */
    val todayPlan: List<Step> get() = p.getString("plan", null)?.let(::decodeSteps) ?: LEVELS[levelIndex].steps
    fun setPlan(steps: List<Step>?) = p.edit().apply { if (steps == null) remove("plan") else putString("plan", steps.encode()) }.apply()

    /** Dev/demo: back to a zero-day streak. */
    fun reset() = p.edit().clear().apply()

    fun day(d: LocalDate): Day = p.getString("day:$d", null)?.split(",")?.let { Day(it[0].toInt(), it[1].toInt(), it[2].toInt()) } ?: Day()

    fun recordWorkout(today: LocalDate = LocalDate.now(), reps: Int = 0, secs: Int = 0, kcal: Int = 0) {
        val d = day(today)
        p.edit()
            .putStringSet("dates", (dates + today).map(LocalDate::toString).toSet())
            .putInt("completed", completed + 1)
            .putString("day:$today", "${d.reps + reps},${d.secs + secs},${d.kcal + kcal}")
            .remove("plan")
            .apply()
    }
}
