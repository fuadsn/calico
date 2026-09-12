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

    fun recordWorkout(today: LocalDate = LocalDate.now()) {
        p.edit()
            .putStringSet("dates", (dates + today).map(LocalDate::toString).toSet())
            .putInt("completed", completed + 1)
            .remove("plan")
            .apply()
    }
}
