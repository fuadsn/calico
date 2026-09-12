package com.calico.roomscan

/** Contact timing is separate from hip-relative pose landmarks. All distances are metres. */
object ExerciseMotion {
    fun phase(seconds: Float, duration: Float): Float =
        if (duration > 0f) ((seconds / duration) % 1f + 1f) % 1f else 0f

    /** Two ballistic hops per jack cycle; short planted intervals at open/closed stance. */
    fun flight(exercise: String, seconds: Float, duration: Float): Float {
        if (exercise != "JUMPING_JACK" && exercise != "HIGH_KNEES") return 0f
        val halfSeconds = duration / 2f
        val halfPhase = (phase(seconds, duration) * 2f) % 1f
        val start = if (exercise == "JUMPING_JACK") 0.16f else 0.06f
        val end = if (exercise == "JUMPING_JACK") 0.84f else 0.34f
        if (halfPhase <= start || halfPhase >= end) return 0f
        val t = (halfPhase - start) * halfSeconds
        val flightTime = (end - start) * halfSeconds
        return 0.5f * GravityBody.GRAVITY * t * (flightTime - t)
    }

    fun needsRaisedSupport(exercise: String) = exercise in setOf("INCLINE_PUSHUP", "DIP", "PULLUP")

    fun support(exercise: String, height: Float = when (exercise) {
        "DIP" -> 0.55f; "PULLUP" -> 2.05f; else -> 0.65f
    }): BodySupport? {
        val range = when (exercise) {
            "INCLINE_PUSHUP" -> 0.25f..0.95f
            "DIP" -> 0.35f..0.7f
            "PULLUP" -> 1.8f..2.4f
            else -> return null
        }
        if (!height.isFinite() || height !in range) return null
        val distance = if (exercise == "DIP") 0.9f else 1.22f
        val span = if (exercise == "PULLUP") 0f else
            kotlin.math.sqrt(distance * distance - height * height) * if (exercise == "DIP") -1f else 1f
        return BodySupport(height, span)
    }
}

/** Local foot-pair centre is (0,0,0); palm-pair centre is (0,handHeight,handZ). */
data class BodySupport(val handHeight: Float, val handZ: Float)
