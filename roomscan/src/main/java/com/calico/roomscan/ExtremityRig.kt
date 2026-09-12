package com.calico.roomscan

/** Palm and sole frames supply twist that a single bone direction cannot recover.
 * Finger curl is a procedural grip, since Pose Landmarker has no finger knuckles. */
internal class ExtremityRig(private val model: GltfModel) {
    private val rest = model.restGlobals()
    private data class Frame(val node: Int, val side: Int, val hand: Boolean, val correction: FloatArray)
    private data class Finger(val side: Int, val axis: FloatArray, val factor: Float)
    private val frames = mutableMapOf<Int, Frame>()
    private val fingers = mutableMapOf<Int, Finger>()

    init {
        for ((side, suffix) in listOf(0 to "L", 1 to "R")) {
            val hand = model.findNode("DEF-hand.$suffix")
            val index = model.findNode("DEF-f_index.01.$suffix")
            val pinky = model.findNode("DEF-f_pinky.01.$suffix")
            val middle = model.findNode("DEF-f_middle.01.$suffix")
            if (listOf(hand, index, pinky, middle).all { it >= 0 }) {
                val across = V3.sub(pos(index), pos(pinky))
                val forward = V3.sub(pos(middle), pos(hand))
                frame(forward, across)?.let { basis ->
                    frames[hand] = Frame(hand, side, true,
                        Quat.mul(Quat.inverse(basis), Quat.fromMatrix(rest[hand])))
                }
                for (i in model.nodes.indices) {
                    val name = model.nodes[i].name
                    if (!name.endsWith(".$suffix") || !(name.startsWith("DEF-f_") || name.startsWith("DEF-thumb."))) continue
                    val axis = Quat.rotate(Quat.inverse(Quat.fromMatrix(rest[i])),
                        V3.scale(V3.normalize(across), if (side == 0) 1f else -1f))
                    fingers[i] = Finger(side, axis, when {
                        name.contains("thumb") -> 0.45f
                        name.contains(".02.") -> 1.1f
                        name.contains(".03.") -> 0.65f
                        else -> 0.8f
                    })
                }
            }
            val foot = model.findNode("DEF-foot.$suffix")
            val toe = model.findNode("DEF-toe.$suffix")
            if (foot >= 0 && toe >= 0) {
                val up = floatArrayOf(0f, 1f, 0f)
                val forward = V3.reject(V3.sub(pos(toe), pos(foot)), up)
                frame(forward, up)?.let { basis ->
                    frames[foot] = Frame(foot, side, false,
                        Quat.mul(Quat.inverse(basis), Quat.fromMatrix(rest[foot])))
                }
            }
        }
    }

    fun worldRotation(node: Int, landmarks: FloatArray): FloatArray? {
        val f = frames[node] ?: return null
        val s = f.side
        val basis = if (f.hand) {
            val index = point(landmarks, 19 + s)
            val pinky = point(landmarks, 17 + s)
            frame(V3.sub(V3.mid(index, pinky), point(landmarks, 15 + s)), V3.sub(index, pinky))
        } else {
            val heel = point(landmarks, 29 + s)
            frame(V3.sub(point(landmarks, 31 + s), heel), V3.sub(point(landmarks, 27 + s), heel))
        } ?: return null // Occluded/degenerate palm: retain the direction-only solver.
        return Quat.mul(basis, f.correction)
    }

    fun fingerRotation(node: Int, landmarks: FloatArray, grip: Float): FloatArray? {
        val f = fingers[node] ?: return null
        val s = f.side
        val forearm = V3.normalize(V3.sub(point(landmarks, 15 + s), point(landmarks, 13 + s)))
        val hand = V3.normalize(V3.sub(point(landmarks, 19 + s), point(landmarks, 15 + s)))
        // Relaxed fingers respond gently to wrist flexion; support palms stay open.
        val curl = (grip * (1f + 0.3f * (1f - V3.dot(forearm, hand)))).coerceIn(0f, 1f)
        return Quat.mul(model.nodes[node].rotation, Quat.axisAngle(f.axis, curl * f.factor * 1.35f))
    }

    private fun pos(node: Int) = M4.translation(rest[node])
    private fun point(p: FloatArray, i: Int) = p.copyOfRange(i * 3, i * 3 + 3)

    private fun frame(forward: FloatArray, secondary: FloatArray): FloatArray? {
        if (V3.length(forward) < 0.005f) return null
        val x = V3.normalize(forward)
        val rejected = V3.reject(secondary, x)
        if (V3.length(rejected) < 0.005f) return null
        val y = V3.normalize(rejected)
        return Quat.fromBasis(x, y, V3.cross(x, y))
    }
}
