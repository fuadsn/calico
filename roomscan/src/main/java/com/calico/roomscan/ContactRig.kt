package com.calico.roomscan

import kotlin.math.sqrt

/** Contacts live in avatar/room-anchor space, not independent ARCore anchors.
 * Root translation follows the supporting limbs; analytic IK preserves bone lengths. */
class ContactRig(private val model: GltfModel, private val exercise: String, reference: FloatArray) {
    private data class Contact(val upper: Int, val middle: Int, val end: Int, val side: Int,
        val hand: Boolean, val rotation: FloatArray, val offset: FloatArray, val target: FloatArray,
        val pole: FloatArray)
    private val contacts = mutableListOf<Contact>()
    private val descendants = Array(model.nodes.size) { root ->
        model.hierarchyOrder.filter { n ->
            var i = n
            while (i >= 0 && i != root) i = model.parents[i]
            i == root
        }.toIntArray()
    }
    private val standing = exercise in setOf("SQUAT", "ARM_RAISE", "ARM_CIRCLE",
        "OVERHEAD_STRETCH", "CROSS_BODY_STRETCH", "HIGH_KNEES")
    private val prone = exercise in setOf("PUSHUP", "PIKE_PUSHUP", "MOUNTAIN_CLIMBER")
    val enabled get() = contacts.isNotEmpty()

    init {
        if (standing || prone) {
            val rig = PoseRetargeter(model)
            rig.pose(reference, false, 0f)
            val rest = model.restGlobals()
            for (side in 0..1) for (hand in listOf(false, true)) {
                if (hand && !prone) continue
                val suffix = if (side == 0) "L" else "R"
                val names = if (hand) listOf("upper_arm", "forearm", "hand") else listOf("thigh", "shin", "foot")
                val ids = names.map { model.findNode("DEF-$it.$suffix") }
                if (ids.any { it < 0 }) continue
                val (upper, middle, end) = ids
                // Standing soles follow world up, even if captured toe height is noisy.
                if (standing) {
                    val localUp = Quat.rotate(Quat.inverse(Quat.fromMatrix(rest[end])), floatArrayOf(0f, 1f, 0f))
                    val actualUp = Quat.rotate(Quat.fromMatrix(rig.globals[end]), localUp)
                    rotate(rig.globals, end, Quat.fromTo(actualUp, floatArrayOf(0f, 1f, 0f)))
                }
                val rotation = Quat.fromMatrix(rig.globals[end])
                val pos = M4.translation(rig.globals[end])
                // Measure the actual skinned sole/palm surface, not just the bone origin.
                val surface = lowestSurface(rig.globals, end) ?: pos
                val offset = Quat.rotate(Quat.inverse(rotation), V3.sub(surface, pos))
                contacts += Contact(upper, middle, end, side, hand, rotation, offset,
                    floatArrayOf(surface[0], 0f, surface[2]), M4.translation(rig.globals[middle]))
            }
        }
    }

    private fun active(c: Contact, p: FloatArray): Boolean {
        if (c.hand) return true
        if (exercise == "HIGH_KNEES" || exercise == "MOUNTAIN_CLIMBER") {
            val y = p[(27 + c.side) * 3 + 1]
            return y <= minOf(p[27 * 3 + 1], p[28 * 3 + 1]) + 0.045f
        }
        return true
    }

    fun apply(rig: PoseRetargeter, landmarks: FloatArray) {
        val selected = contacts.filter { active(it, landmarks) }
        if (selected.isEmpty()) return
        val g = rig.globals
        for (c in selected) orient(g, c)
        val drivers = if (prone) selected.filter { it.hand } else selected
        val shift = FloatArray(3)
        for (c in drivers) {
            val error = V3.sub(endTarget(c), M4.translation(g[c.end]))
            for (a in 0..2) shift[a] += error[a] / drivers.size
        }
        translate(g, shift)
        // Project the root back inside each limb's reachable sphere before solving.
        repeat(16) {
            for (c in selected) {
                val a = M4.translation(g[c.upper]); val b = M4.translation(g[c.middle]); val e = M4.translation(g[c.end])
                val max = (V3.length(V3.sub(b, a)) + V3.length(V3.sub(e, b))) * 0.9999f
                val delta = V3.sub(endTarget(c), a)
                val distance = V3.length(delta)
                val min = if (prone && !c.hand) max * 0.999f else 0f
                if (distance > max) translate(g, V3.scale(delta, (distance - max) / distance))
                else if (distance < min && distance > 0.00001f)
                    translate(g, V3.scale(delta, (distance - min) / distance))
            }
        }
        for (c in selected) {
            solve(g, c, endTarget(c))
            orient(g, c)
        }
    }

    /** Contact residuals in model units, for verification and diagnostics. */
    fun errors(rig: PoseRetargeter, p: FloatArray): List<Float> = contacts.filter { active(it, p) }.map {
        V3.length(V3.sub(V3.add(M4.translation(rig.globals[it.end]),
            Quat.rotate(Quat.fromMatrix(rig.globals[it.end]), it.offset)), it.target))
    }

    private fun endTarget(c: Contact) = V3.sub(c.target, Quat.rotate(c.rotation, c.offset))
    private fun orient(g: Array<FloatArray>, c: Contact) = rotate(g, c.end,
        Quat.mul(c.rotation, Quat.inverse(Quat.fromMatrix(g[c.end]))))

    private fun solve(g: Array<FloatArray>, c: Contact, target: FloatArray) {
        val a = M4.translation(g[c.upper]); val b = M4.translation(g[c.middle]); val e = M4.translation(g[c.end])
        val l1 = V3.length(V3.sub(b, a)); val l2 = V3.length(V3.sub(e, b))
        val delta = V3.sub(target, a)
        val d = V3.length(delta).coerceIn(kotlin.math.abs(l1 - l2) + 0.00001f, l1 + l2 - 0.00001f)
        val axis = V3.normalize(delta)
        var bend = V3.reject(V3.sub(b, a), axis)
        if (V3.length(bend) < 0.0001f) bend = V3.reject(V3.sub(c.pole, a), axis)
        if (V3.length(bend) < 0.0001f) bend = V3.reject(floatArrayOf(0f, 0f, 1f), axis)
        val x = (l1 * l1 - l2 * l2 + d * d) / (2f * d)
        val h = sqrt((l1 * l1 - x * x).coerceAtLeast(0f))
        val desired = V3.add(a, V3.add(V3.scale(axis, x), V3.scale(V3.normalize(bend), h)))
        rotate(g, c.upper, Quat.fromTo(V3.sub(b, a), V3.sub(desired, a)))
        val mid = M4.translation(g[c.middle])
        rotate(g, c.middle, Quat.fromTo(V3.sub(M4.translation(g[c.end]), mid), V3.sub(target, mid)))
    }

    private fun translate(g: Array<FloatArray>, shift: FloatArray) {
        for (m in g) for (i in 0..2) m[12 + i] += shift[i]
    }

    private fun rotate(g: Array<FloatArray>, node: Int, q: FloatArray) {
        val pivot = M4.translation(g[node]); val rotation = Quat.toMatrix(q)
        for (i in descendants[node]) {
            val position = V3.add(pivot, Quat.rotate(q, V3.sub(M4.translation(g[i]), pivot)))
            g[i] = M4.multiply(rotation, g[i]).also { for (a in 0..2) it[12 + a] = position[a] }
        }
    }

    private fun lowestSurface(g: Array<FloatArray>, end: Int): FloatArray? {
        val skin = model.skins.firstOrNull() ?: return null
        val matrices = skin.joints.indices.map { M4.multiply(g[skin.joints[it]], skin.inverseBind[it]) }
        val members = descendants[end].toSet()
        var lowest: FloatArray? = null
        for (mesh in model.meshes) for (p in mesh.primitives) {
            if (p.weights.isEmpty()) continue
            for (v in 0 until p.vertexCount) {
                var influence = 0f
                val point = FloatArray(3)
                val source = p.positions.copyOfRange(v * 3, v * 3 + 3)
                for (w in 0..3) {
                    val j = p.joints[v * 4 + w]; val weight = p.weights[v * 4 + w]
                    if (skin.joints[j] in members) influence += weight
                    val vertex = M4.transformPoint(matrices[j], source)
                    for (a in 0..2) point[a] += weight * vertex[a]
                }
                if (influence >= 0.6f && point[1] < (lowest?.get(1) ?: Float.MAX_VALUE)) lowest = point
            }
        }
        return lowest
    }
}
