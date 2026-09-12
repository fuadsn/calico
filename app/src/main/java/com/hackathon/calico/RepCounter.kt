package com.hackathon.calico

import kotlin.math.abs
import kotlin.math.atan2

// MediaPipe pose landmark indices, as (a, b, c) triples; the angle is measured at b.
private val ELBOW_L = intArrayOf(11, 13, 15); private val ELBOW_R = intArrayOf(12, 14, 16)  // shoulder-elbow-wrist
private val KNEE_L = intArrayOf(23, 25, 27);  private val KNEE_R = intArrayOf(24, 26, 28)   // hip-knee-ankle
private val HIP_L = intArrayOf(11, 23, 25);   private val HIP_R = intArrayOf(12, 24, 26)    // shoulder-hip-knee
private val HIPLEG_L = intArrayOf(11, 23, 27); private val HIPLEG_R = intArrayOf(12, 24, 28) // shoulder-hip-ankle
private val ARM_L = intArrayOf(23, 11, 13);   private val ARM_R = intArrayOf(24, 12, 14)    // hip-shoulder-elbow (wrists leave the frame overhead)

/** Torso direction required for a frame to count. Blocks e.g. arm-folding from counting as pushups. */
enum class Orientation { UPRIGHT, HORIZONTAL, ANY }

/** Which limbs drive the count. ONE = the more visible side. SUM = each side counted separately and added
 *  (alternating legs). EITHER = max of both, so one raised arm counts even if the other left the frame. */
enum class Sides { ONE, SUM, EITHER }

/**
 * Rep exercise (holdSec == 0): one rep = angle drops below `down`, then rises above `up`.
 * Hold exercise (holdSec > 0): time accumulates while angle is within [down, up]; done at holdSec.
 */
// ponytail: thresholds tuned from bench traces with the phone on the floor, which foreshortens
// joints by ~30°. Upgrade path: calibrate min/max from the user's first rep if camera height varies.
enum class Exercise(
    val left: IntArray, val right: IntArray,
    val down: Float, val up: Float,
    val cue: String, val orientation: Orientation,
    val holdSec: Int = 0,
    val sides: Sides = Sides.ONE,
) {
    // ---- reps ----
    INCLINE_PUSHUP(ELBOW_L, ELBOW_R, 130f, 150f, "Go lower", Orientation.ANY),   // hands on a chair/counter
    PUSHUP(ELBOW_L, ELBOW_R, 125f, 150f, "Go lower", Orientation.HORIZONTAL),
    PIKE_PUSHUP(ELBOW_L, ELBOW_R, 120f, 150f, "Go lower", Orientation.ANY),
    DIP(ELBOW_L, ELBOW_R, 90f, 160f, "Go lower", Orientation.UPRIGHT),
    PULLUP(ELBOW_L, ELBOW_R, 70f, 150f, "Chin over the bar", Orientation.UPRIGHT),
    SQUAT(KNEE_L, KNEE_R, 130f, 155f, "Go deeper", Orientation.UPRIGHT),
    LUNGE(KNEE_L, KNEE_R, 100f, 160f, "Go deeper", Orientation.UPRIGHT),
    SITUP(HIP_L, HIP_R, 95f, 130f, "All the way up", Orientation.ANY),
    LEG_RAISE(HIPLEG_L, HIPLEG_R, 100f, 160f, "Legs higher", Orientation.HORIZONTAL),
    MOUNTAIN_CLIMBER(KNEE_L, KNEE_R, 90f, 150f, "Knee to chest", Orientation.HORIZONTAL, sides = Sides.SUM),
    HIGH_KNEES(KNEE_L, KNEE_R, 120f, 155f, "Knees higher", Orientation.UPRIGHT, sides = Sides.SUM),
    JUMPING_JACK(ARM_L, ARM_R, 40f, 110f, "Arms all the way up", Orientation.UPRIGHT, sides = Sides.EITHER),
    // ---- warm-up reps: shoulder angle sweeps low -> high -> low ----
    ARM_RAISE(ARM_L, ARM_R, 40f, 140f, "Reach higher", Orientation.UPRIGHT),
    ARM_CIRCLE(ARM_L, ARM_R, 40f, 140f, "Bigger circles", Orientation.UPRIGHT),
    // ---- holds (timed) ----
    PLANK(HIPLEG_L, HIPLEG_R, 155f, 180f, "Hips up", Orientation.HORIZONTAL, holdSec = 30),
    // ponytail: stretches only check one joint angle; a 2D view can't verify the actual stretch
    OVERHEAD_STRETCH(ELBOW_L, ELBOW_R, 0f, 70f, "Hand behind your head", Orientation.UPRIGHT, holdSec = 20),
    CROSS_BODY_STRETCH(ARM_L, ARM_R, 50f, 120f, "Arm across your chest", Orientation.UPRIGHT, holdSec = 20),
}

/** Angle at b (degrees, 0..180) formed by a-b-c. */
fun angle(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float {
    val deg = Math.toDegrees((atan2(cy - by, cx - bx) - atan2(ay - by, ax - bx)).toDouble()).toFloat()
    val a = abs(deg)
    return if (a > 180f) 360f - a else a
}

/**
 * Reps: two-state machine UP -> (angle < down) -> DOWN -> (angle > up) -> UP, count++.
 * Holds: `count` is seconds held; onRep fires every 5 s and at completion.
 * Angles are smoothed over the last 3 samples and reps closer than MIN_REP_MS apart are ignored,
 * so landmark jitter at the turnaround can't double count.
 */
class RepCounter(
    val exercise: Exercise,
    private val onRep: (Int) -> Unit,
    private val onCue: (String) -> Unit,
    holdSec: Int = exercise.holdSec,   // a level can ask for a longer or shorter hold
) {
    var holdSec = holdSec; private set
    fun updateHoldTarget(seconds: Int) { require(exercise.holdSec>0 && seconds in 1..300); holdSec=seconds }
    var count = 0; private set
    var cues = 0; private set
    val done get() = holdSec > 0 && count >= holdSec
    private var isDown = false
    private var minAngle = 180f
    private var lastRepAt = -MIN_REP_MS
    private val window = FloatArray(3); private var n = 0
    // hold state
    private var lastT = -1L; private var heldMs = 0L; private var announced = 0; private var wasHeld = false

    /** A paused session must not add hold time or complete a half-rep on resume. */
    fun suspendTiming() { lastT=-1L; wasHeld=false; n=0; isDown=false; minAngle=180f }

    fun feed(rawAngle: Float, timeMs: Long) {
        window[n++ % window.size] = rawAngle
        val angle = window.take(minOf(n, window.size)).average().toFloat()
        if (exercise.holdSec > 0) feedHold(angle, timeMs) else feedRep(angle, timeMs)
    }

    private fun feedRep(angle: Float, timeMs: Long) {
        minAngle = minOf(minAngle, angle)
        if (!isDown && angle < exercise.down) isDown = true
        if (angle > exercise.up) {
            if (isDown) {
                if (timeMs - lastRepAt >= MIN_REP_MS) { count++; lastRepAt = timeMs; onRep(count) }
            } else if (minAngle < exercise.down + 20f) { cues++; onCue(exercise.cue) }  // dipped, but not enough
            isDown = false
            minAngle = 180f
        }
    }

    private fun feedHold(angle: Float, timeMs: Long) {
        if (done) return
        val held = angle in exercise.down..exercise.up
        if (held && lastT >= 0) heldMs += minOf(timeMs - lastT, 500)   // cap gaps from dropped frames
        if (!held && wasHeld) { cues++; onCue(exercise.cue) }
        wasHeld = held; lastT = timeMs
        count = (heldMs / 1000).toInt()
        if (done) onRep(count)
        else if (count / 5 > announced) { announced = count / 5; onRep(count) }
    }

    companion object { const val MIN_REP_MS = 300L }
}
