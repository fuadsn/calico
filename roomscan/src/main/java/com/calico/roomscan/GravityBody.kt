package com.calico.roomscan

/** A driven animated body: metres, seconds, Y up, with an inelastic support at Y=0.
 * Internal joints remain animation/IK driven; this is not an unconstrained ragdoll. */
class GravityBody {
    var height = 0f
        private set
    var velocity = 0f
        private set
    var held = false
        private set

    fun grab() { held = true; velocity = 0f }
    fun lift(metres: Float) {
        if (held && metres.isFinite()) height = (height + metres).coerceIn(0f, 2f)
    }
    fun release() { held = false }
    fun reset() { height = 0f; velocity = 0f; held = false }

    fun step(seconds: Float) {
        if (held || !seconds.isFinite() || seconds <= 0f) return
        // Ignore time spent suspended/backgrounded; exact ballistic integration is FPS independent.
        val dt = seconds.coerceAtMost(0.1f)
        height += velocity * dt - 0.5f * GRAVITY * dt * dt
        velocity -= GRAVITY * dt
        if (height <= 0f) { height = 0f; velocity = 0f }
    }

    companion object { const val GRAVITY = 9.81f }
}
