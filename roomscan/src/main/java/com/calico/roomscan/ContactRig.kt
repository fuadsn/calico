package com.calico.roomscan

import kotlin.math.sqrt

/** Contacts live in avatar/room-anchor space, not independent ARCore anchors.
 * Root translation follows the supporting limbs; analytic IK preserves bone lengths. */
class ContactRig(private val model: GltfModel, private val exercise: String, reference: FloatArray) {
    private data class Contact(val upper: Int, val middle: Int, val end: Int, val side: Int,
        val hand: Boolean, val rotation: FloatArray, val offset: FloatArray, val target: FloatArray,
        val pole: FloatArray, val source: FloatArray, var wasActive: Boolean = false)
    private val contacts = mutableListOf<Contact>()
    private val descendants = Array(model.nodes.size) { root ->
        model.hierarchyOrder.filter { n ->
            var i = n
            while (i >= 0 && i != root) i = model.parents[i]
            i == root
        }.toIntArray()
    }
    private val standing = exercise in setOf("SQUAT", "ARM_RAISE", "ARM_CIRCLE",
        "OVERHEAD_STRETCH", "CROSS_BODY_STRETCH", "HIGH_KNEES", "JUMPING_JACK", "LUNGE")
    private val prone = exercise in setOf("PUSHUP", "PIKE_PUSHUP", "MOUNTAIN_CLIMBER", "INCLINE_PUSHUP", "PLANK")
    private val raised = ExerciseMotion.needsRaisedSupport(exercise)
    private val seated = exercise in setOf("SITUP", "LEG_RAISE")
    private val fitScale = PoseRetargeter(model).scaleToHeight(SkinnedFigure.HEIGHT_M)
    private var flight = 0f
    private var phase = 0f
    private val pelvisSupport = if (seated) model.findNode("DEF-hips") else -1
    val enabled get() = contacts.isNotEmpty() || pelvisSupport >= 0

    init {
        if (standing || prone || raised || exercise == "SITUP") {
            val rig = PoseRetargeter(model)
            rig.pose(reference, false, 0f)
            val rest = model.restGlobals()
            for (side in 0..1) for (hand in listOf(false, true)) {
                if (hand && !prone && !raised) continue
                if (!hand && exercise == "PULLUP") continue
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
                val surface = lowestSurface(rig.globals, if (exercise == "PLANK" && hand) middle else end) ?: pos
                val offset = Quat.rotate(Quat.inverse(rotation), V3.sub(surface, pos))
                contacts += Contact(upper, middle, end, side, hand, rotation, offset,
                    floatArrayOf(surface[0], 0f, surface[2]), M4.translation(rig.globals[middle]), surface)
            }
        }
    }

    private fun active(c: Contact, p: FloatArray): Boolean {
        if (flight > 0.00001f && !c.hand) return false
        if (c.hand) return true
        if (exercise == "LUNGE") {
            val half = (phase * 2f) % 1f
            return half in 0.25f..0.75f
        }
        if (exercise == "HIGH_KNEES" || exercise == "MOUNTAIN_CLIMBER") {
            val y = p[(27 + c.side) * 3 + 1]
            return y <= minOf(p[27 * 3 + 1], p[28 * 3 + 1]) + 0.045f
        }
        return true
    }

    fun apply(rig: PoseRetargeter, landmarks: FloatArray, seconds: Float = 0f, duration: Float = 1f,
        support: BodySupport? = ExerciseMotion.support(exercise)) {
        flight = ExerciseMotion.flight(exercise, seconds, duration)
        phase = ExerciseMotion.phase(seconds, duration)
        val g = rig.globals
        // High-knees is a stationary drill. Alternating foot contacts can otherwise introduce
        // tiny lateral IK corrections that accumulate into visible walking/drift over a loop.
        val lockedHipX = if (exercise == "HIGH_KNEES" && rig.hipsNode >= 0) g[rig.hipsNode][12] else 0f
        val lockedHipZ = if (exercise == "HIGH_KNEES" && rig.hipsNode >= 0) g[rig.hipsNode][14] else 0f
        if (raised && support != null) adaptSupports(g, support)
        if (exercise == "JUMPING_JACK" || exercise == "LUNGE") {
            rig.ground()
            for (c in contacts) {
                val now = active(c, landmarks)
                if (now && !c.wasActive) {
                    val surface = V3.add(M4.translation(g[c.end]), Quat.rotate(c.rotation, c.offset))
                    c.target[0] = surface[0]; c.target[2] = surface[2]
                }
                c.wasActive = now
            }
        }
        if (pelvisSupport >= 0) {
            // Supine exercises pivot about the pelvis, rather than grounding a moving foot.
            translate(g, floatArrayOf(0f, 0.12f / fitScale - g[pelvisSupport][13], 0f))
        }
        val selected = contacts.filter { active(it, landmarks) }
        if (selected.isEmpty()) {
            if (!seated) rig.ground()
            return
        }
        for (c in selected) orient(g, c)
        val drivers = if (prone || raised) selected.filter { it.hand } else selected
        val shift = FloatArray(3)
        for (c in drivers) {
            val error = V3.sub(endTarget(c), M4.translation(g[c.end]))
            for (a in 0..2) shift[a] += error[a] / drivers.size
        }
        if (!seated) translate(g, shift)
        // Project the root back inside each limb's reachable sphere before solving.
        repeat(16) {
            for (c in selected) {
                val a = M4.translation(g[c.upper]); val b = M4.translation(g[c.middle]); val e = M4.translation(g[c.end])
                val max = (V3.length(V3.sub(b, a)) + V3.length(V3.sub(e, b))) * 0.9999f
                val delta = V3.sub(endTarget(c), a)
                val distance = V3.length(delta)
                val min = if (prone && !c.hand && exercise != "PLANK") max * 0.999f else 0f
                if (distance > max) translate(g, V3.scale(delta, (distance - max) / distance))
                else if (distance < min && distance > 0.00001f)
                    translate(g, V3.scale(delta, (distance - min) / distance))
            }
        }
        for (c in selected) {
            solve(g, c, endTarget(c))
            orient(g, c)
        }
        if (exercise == "HIGH_KNEES" && rig.hipsNode >= 0) {
            val correction = floatArrayOf(
                lockedHipX - g[rig.hipsNode][12],
                0f,
                lockedHipZ - g[rig.hipsNode][14],
            )
            translate(g, correction)
            // Move the active contact targets with the same correction so the lock does not
            // leave a several-millimetre residual that would make the foot visibly pop.
            for (c in selected) {
                c.target[0] += correction[0]
                c.target[2] += correction[2]
            }
        }
    }

    private fun adaptSupports(g: Array<FloatArray>, support: BodySupport) {
        val hands = contacts.filter { it.hand }
        val feet = contacts.filter { !it.hand }
        fun centre(group: List<Contact>) = FloatArray(3) { a -> group.map { it.source[a] }.average().toFloat() }
        if (hands.isEmpty()) return
        val hand = centre(hands)
        val foot = if (feet.isNotEmpty()) centre(feet) else floatArrayOf(hand[0], 0f, hand[2])
        if (feet.isNotEmpty()) {
            val q = Quat.fromTo(V3.sub(hand, foot), floatArrayOf(0f, support.handHeight / fitScale, support.handZ / fitScale))
            val rotation = Quat.toMatrix(q)
            for (i in g.indices) g[i] = M4.multiply(rotation, g[i])
        }
        for (c in contacts) {
            val centre = if (c.hand) hand else foot
            val halfWidth = if (!c.hand) 0.14f else if (exercise == "PULLUP") 0.32f else 0.27f
            c.target[0] = halfWidth * (if (c.side == 0) 1f else -1f) / fitScale
            c.target[1] = if (c.hand) support.handHeight / fitScale else 0f
            c.target[2] = c.source[2] - centre[2] + if (c.hand) support.handZ / fitScale else 0f
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
