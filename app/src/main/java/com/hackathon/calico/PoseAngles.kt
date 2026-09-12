package com.hackathon.calico

import kotlin.math.abs

/** Shared selection and orientation rules for live detections and recorded benchmark poses. */
object PoseAngles {
    data class Sample(val left: Float?, val right: Float?)

    fun select(e: Exercise, xyz: FloatArray, visibility: FloatArray): Sample? {
        if (xyz.size != 99 || visibility.size != 33 || xyz.any { !it.isFinite() }) return null
        val dx = xyz[11 * 3] + xyz[12 * 3] - xyz[23 * 3] - xyz[24 * 3]
        val dy = xyz[11 * 3 + 1] + xyz[12 * 3 + 1] - xyz[23 * 3 + 1] - xyz[24 * 3 + 1]
        if (e.orientation == Orientation.UPRIGHT && abs(dy) <= abs(dx) ||
            e.orientation == Orientation.HORIZONTAL && abs(dx) <= abs(dy)) return null
        fun vis(j: IntArray) = j.minOf { visibility[it] }
        fun ang(j: IntArray) = angle(xyz[j[0] * 3], xyz[j[0] * 3 + 1],
            xyz[j[1] * 3], xyz[j[1] * 3 + 1], xyz[j[2] * 3], xyz[j[2] * 3 + 1])
        val l = if (vis(e.left) >= 0.5f) ang(e.left) else null
        val r = if (vis(e.right) >= 0.5f) ang(e.right) else null
        if (l == null && r == null) return null
        if (e.sides == Sides.SUM) return Sample(l, r)
        return Sample(when {
            e.sides == Sides.EITHER && l != null && r != null -> maxOf(l, r)
            l != null && (r == null || vis(e.left) >= vis(e.right)) -> l
            else -> r
        }, null)
    }
}
