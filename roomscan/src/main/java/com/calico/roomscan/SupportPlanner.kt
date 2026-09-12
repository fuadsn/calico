package com.calico.roomscan

import kotlin.math.*

/** Snapshot of a tracked upward-facing convex polygon in world XZ, independent of ARCore. */
data class SupportPatch(val id: Int, val y: Float, val polygon: FloatArray) {
    fun contains(x: Float, z: Float, margin: Float = 0f): Boolean {
        if (polygon.size < 6) return false
        var sign = 0
        for (i in polygon.indices step 2) {
            val j = (i + 2) % polygon.size
            val dx = polygon[j] - polygon[i]; val dz = polygon[j + 1] - polygon[i + 1]
            val length = hypot(dx, dz)
            if (length < 1e-5f) continue
            val cross = dx * (z - polygon[i + 1]) - dz * (x - polygon[i])
            if (abs(cross) < margin * length) return false
            val next = if (cross >= 0f) 1 else -1
            if (sign != 0 && next != sign) return false
            sign = next
        }
        return sign != 0
    }
}

data class PlannedSupport(val floorId: Int, val handId: Int, val foot: FloatArray,
    val hand: FloatArray, val yaw: Float, val support: BodySupport)

/** Both pairs must fit inside their own polygons; feet may not land underneath the upper patch. */
object SupportPlanner {
    fun find(exercise: String, patches: List<SupportPatch>, camera: FloatArray,
        preferred: FloatArray? = null): PlannedSupport? {
        val lowest = patches.filter { it.y < camera[1] - 0.1f }.minOfOrNull { it.y } ?: return null
        val floors = patches.filter { it.y <= lowest + 0.15f }
        var best: PlannedSupport? = null
        var score = Float.POSITIVE_INFINITY
        for (floor in floors) for (upper in patches) {
            if (floor.id == upper.id) continue
            val body = ExerciseMotion.support(exercise, upper.y - floor.y) ?: continue
            val p = upper.polygon
            if (p.size < 6) continue
            val cx = p.indices.step(2).map { p[it] }.average().toFloat()
            val cz = p.indices.step(2).map { p[it + 1] }.average().toFloat()
            val centres = mutableListOf(floatArrayOf(cx, cz))
            for (i in p.indices step 2) {
                val j = (i + 2) % p.size
                val mx = (p[i] + p[j]) / 2f; val mz = (p[i + 1] + p[j + 1]) / 2f
                val distance = hypot(cx - mx, cz - mz).coerceAtLeast(0.001f)
                val inset = (0.14f / distance).coerceAtMost(0.8f)
                centres += floatArrayOf(mx + (cx - mx) * inset, mz + (cz - mz) * inset)
            }
            for (centre in centres) for (turn in 0 until 16) {
                val yaw = turn * PI.toFloat() / 8f
                val dx = sin(yaw); val dz = cos(yaw)
                val fx = centre[0] - dx * body.handZ; val fz = centre[1] - dz * body.handZ
                val handHalf = if (exercise == "PULLUP") 0.32f else 0.27f
                if (!pairFits(upper, centre[0], centre[1], yaw, handHalf)) continue
                if (!pairFits(floor, fx, fz, yaw, 0.14f)) continue
                if (exercise != "PULLUP" && upper.contains(fx, fz)) continue
                val target = preferred ?: camera
                val distance = hypot(fx - target[0], fz - target[2])
                if (preferred != null && distance > 0.65f || distance > 4f) continue
                if (distance < score) {
                    score = distance
                    best = PlannedSupport(floor.id, upper.id, floatArrayOf(fx, floor.y, fz),
                        floatArrayOf(centre[0], upper.y, centre[1]), yaw * 180f / PI.toFloat(), body)
                }
            }
        }
        return best
    }

    fun pairFits(patch: SupportPatch, x: Float, z: Float, yaw: Float, halfWidth: Float): Boolean =
        listOf(-1, 1).all { side -> patch.contains(x + cos(yaw) * halfWidth * side,
            z - sin(yaw) * halfWidth * side, 0.09f) }
}
