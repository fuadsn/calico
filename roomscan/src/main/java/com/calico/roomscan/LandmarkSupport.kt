package com.calico.roomscan

import kotlin.math.*

/** Two-bone contact solve for the line-model fallback, in metres. */
class LandmarkSupport(private val reference: FloatArray, private val exercise: String) {
    fun apply(p: FloatArray, support: BodySupport) {
        val foot = V3.mid(point(reference, 27), point(reference, 28))
        val hand = V3.mid(point(reference, 15), point(reference, 16))
        if (exercise != "PULLUP") {
            val q = Quat.fromTo(V3.sub(hand, foot), floatArrayOf(0f, support.handHeight, support.handZ))
            for (i in 0..32) put(p, i, Quat.rotate(q, point(p, i)))
        }
        val targets = mutableListOf<Pair<IntArray, FloatArray>>()
        for (side in 0..1) {
            targets += intArrayOf(11 + side, 13 + side, 15 + side) to floatArrayOf(
                (if (exercise == "PULLUP") 0.32f else 0.27f) * (if (side == 0) 1 else -1), support.handHeight + 0.025f, support.handZ)
            if (exercise != "PULLUP") targets += intArrayOf(23 + side, 25 + side, 27 + side) to floatArrayOf(
                0.14f * (if (side == 0) 1 else -1), 0.055f, 0f)
        }
        val shift = V3.sub(targets[0].second, point(p, 15))
        translate(p, shift)
        repeat(24) {
            for ((ids, target) in targets) {
                val a = point(p, ids[0]); val b = point(p, ids[1]); val end = point(p, ids[2])
                val reach = (V3.length(V3.sub(b, a)) + V3.length(V3.sub(end, b))) * 0.9999f
                val delta = V3.sub(target, a); val distance = V3.length(delta)
                if (distance > reach) translate(p, V3.scale(delta, (distance - reach) / distance))
            }
        }
        for ((ids, target) in targets) {
            val a = point(p, ids[0]); val b = point(p, ids[1]); val end = point(p, ids[2])
            val l1 = V3.length(V3.sub(b, a)); val l2 = V3.length(V3.sub(end, b))
            val delta = V3.sub(target, a); val axis = V3.normalize(delta)
            val d = V3.length(delta).coerceIn(abs(l1 - l2) + 0.00001f, l1 + l2 - 0.00001f)
            var bend = V3.reject(V3.sub(b, a), axis)
            if (V3.length(bend) < 0.0001f) bend = V3.reject(floatArrayOf(0f, 0f, 1f), axis)
            val x = (l1*l1 - l2*l2 + d*d) / (2*d)
            put(p, ids[1], V3.add(a, V3.add(V3.scale(axis, x), V3.scale(V3.normalize(bend), sqrt((l1*l1-x*x).coerceAtLeast(0f))))))
            val solvedEnd = V3.add(a, V3.scale(axis, d))
            put(p, ids[2], solvedEnd)
            // Preserve the source hand/sole shape downstream of the wrist/ankle.
            val offset = V3.sub(solvedEnd, end)
            val descendants = if (ids[2] <= 16) intArrayOf(ids[2]+2, ids[2]+4, ids[2]+6) else intArrayOf(ids[2]+2, ids[2]+4)
            for (i in descendants) put(p, i, V3.add(point(p, i), offset))
            if (ids[2] >= 27) {
                put(p, ids[2]+2, floatArrayOf(solvedEnd[0],0f,solvedEnd[2]-0.06f))
                put(p, ids[2]+4, floatArrayOf(solvedEnd[0],0f,solvedEnd[2]+0.17f))
            }
        }
    }
    private fun translate(p: FloatArray, shift: FloatArray) { for (i in p.indices) p[i] += shift[i % 3] }
    private fun point(p: FloatArray, i: Int) = p.copyOfRange(i*3, i*3+3)
    private fun put(p: FloatArray, i: Int, v: FloatArray) { for (a in 0..2) p[i*3+a] = v[a] }
}
