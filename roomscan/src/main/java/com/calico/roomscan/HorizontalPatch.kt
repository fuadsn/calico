package com.calico.roomscan

import kotlin.math.abs

data class HorizontalPatch(val height: Float, val outline: FloatArray, val support: Int) {
    val area: Float get() = ZoneSolver.polygonArea(outline)
}

/** Bounded horizontal consensus fit. A patch is an observation, never a clearance claim. */
object HorizontalPatchFit {
    fun fit(points: List<FloatArray>, cameraY: Float): HorizontalPatch? {
        val valid = points.filter { p -> p.size == 3 && p.all { it.isFinite() } &&
            p[1] < cameraY - 0.1f }.take(600)
        if (valid.size < 16) return null
        var best = emptyList<FloatArray>()
        // At most 32 hypotheses x 600 points, independent of image resolution.
        for (i in valid.indices step maxOf(1, (valid.size + 31) / 32)) {
            val inliers = valid.filter { abs(it[1] - valid[i][1]) <= 0.025f }
            if (inliers.size > best.size) best = inliers
        }
        if (best.size < 16) return null
        val sorted = best.distinctBy { it[0] to it[2] }.sortedWith(compareBy({ it[0] }, { it[2] }))
        if (sorted.size < 3) return null
        fun cross(a: FloatArray, b: FloatArray, c: FloatArray) =
            (b[0] - a[0]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[0] - a[0])
        fun half(sequence: List<FloatArray>): List<FloatArray> {
            val hull = mutableListOf<FloatArray>()
            for (p in sequence) {
                while (hull.size >= 2 && cross(hull[hull.lastIndex - 1], hull.last(), p) <= 0f)
                    hull.removeAt(hull.lastIndex)
                hull += p
            }
            return hull.dropLast(1)
        }
        val hull = half(sorted) + half(sorted.asReversed())
        val outline = FloatArray(hull.size * 2) { i -> hull[i / 2][if (i % 2 == 0) 0 else 2] }
        val area = ZoneSolver.polygonArea(outline)
        // Reject narrow wall slices and sparse, widely separated samples.
        if (area < 0.2f || area > 12f || best.size / area < 12f) return null
        return HorizontalPatch(best.map { it[1].toDouble() }.average().toFloat(), outline, best.size)
    }
}
