package com.hackathon.calico

import kotlin.math.abs
import kotlin.math.atan2

/** Which joint triple to measure and the angle thresholds that define one rep. MediaPipe indices. */
enum class Exercise(val left: IntArray, val right: IntArray, val down: Float, val up: Float, val cue: String) {
    // elbow angle: shoulder-elbow-wrist
    PUSHUP(intArrayOf(11, 13, 15), intArrayOf(12, 14, 16), down = 90f, up = 160f, cue = "Go lower"),
    // knee angle: hip-knee-ankle
    SQUAT(intArrayOf(23, 25, 27), intArrayOf(24, 26, 28), down = 100f, up = 165f, cue = "Go deeper"),
}

/** Angle at b (degrees, 0..180) formed by a-b-c. */
fun angle(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float {
    val deg = Math.toDegrees((atan2(cy - by, cx - bx) - atan2(ay - by, ax - bx)).toDouble()).toFloat()
    val a = abs(deg)
    return if (a > 180f) 360f - a else a
}

/**
 * Two-state machine: UP -> (angle < down) -> DOWN -> (angle > up) -> UP, count++.
 * onRep(count) fires on each completed rep; onCue(text) fires when the user comes back up
 * without having gone deep enough.
 */
class RepCounter(val exercise: Exercise, private val onRep: (Int) -> Unit, private val onCue: (String) -> Unit) {
    var count = 0; private set
    private var isDown = false
    private var minAngle = 180f

    fun feed(angle: Float) {
        minAngle = minOf(minAngle, angle)
        if (!isDown && angle < exercise.down) isDown = true
        if (angle > exercise.up) {
            if (isDown) { count++; onRep(count) }
            // ponytail: partial-rep cue only if they dipped at least 30° but not to threshold
            else if (minAngle < exercise.up - 30f) onCue(exercise.cue)
            isDown = false
            minAngle = 180f
        }
    }
}
