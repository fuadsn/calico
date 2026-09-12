package com.calico.roomscan

/**
 * Turns a frame of MediaPipe landmarks into world matrices for every node of a glTF model.
 *
 * The model is never assumed to share MediaPipe's axes, its bind pose, or its bone naming.
 * For each bone the solver measures where the bone points in the model's own rest pose,
 * measures where the landmarks say it should point, and applies the shortest rotation
 * between the two in world space. Because the correction is derived from the model rather
 * than hard coded, a different rigged humanoid can be dropped in and still animate.
 *
 * Landmarks arrive as 33 triples in the space [PoseClip] produces: metres, hips at the
 * origin, +X to the subject's left, +Y up, +Z out of the subject's chest.
 *
 * Results are written into reused arrays, so a caller that keeps the matrices past the
 * next [pose] call has to copy them.
 */
class PoseRetargeter(val model: GltfModel) {

    private val restGlobals = model.restGlobals()
    private val restGlobalRotations = Array(model.nodes.size) { Quat.fromMatrix(restGlobals[it]) }
    private val extremities = ExtremityRig(model)

    /** Bones that both the rig and this particular model have. */
    private val solved: List<SolvedBone> = HumanRig.bones.mapNotNull { bone ->
        val node = HumanRig.resolve(model, bone.nodeNames)
        val tip = HumanRig.resolve(model, bone.tipNames).takeIf { it >= 0 }
            ?: model.nodes.getOrNull(node)?.children?.firstOrNull() ?: -1
        if (node < 0 || tip < 0) return@mapNotNull null
        val restDirection = V3.normalize(
            V3.sub(M4.translation(restGlobals[tip]), M4.translation(restGlobals[node]))
        )
        SolvedBone(
            node = node,
            // The rest direction expressed in the bone's own frame stays valid however
            // the bone's parents move, which is what makes the chain solve in one pass.
            restDirectionLocal = Quat.rotate(Quat.inverse(restGlobalRotations[node]), restDirection),
            from = bone.from,
            to = bone.to,
        )
    }

    private val boneByNode = HashMap<Int, SolvedBone>().apply { solved.forEach { put(it.node, it) } }

    val hipsNode = HumanRig.resolve(model, HumanRig.HIPS)
    private val restPelvisBasis = pelvisBasisFromRest()

    /** World matrices for every node, valid until the next call. */
    val globals: Array<FloatArray> = Array(model.nodes.size) { M4.identity() }

    /** True when the model exposes enough of the rig to be worth animating. */
    val isUsable get() = hipsNode >= 0 && restPelvisBasis != null && solved.size >= MIN_BONES

    /**
     * @param landmarks 33 xyz triples.
     * @param dropToGround shifts the whole figure so its lowest joint sits at y = 0,
     *        which keeps a pushup on the floor instead of floating where the hips were.
     */
    fun pose(landmarks: FloatArray, dropToGround: Boolean = true, handGrip: Float = 0.22f) {
        val pelvisDelta = restPelvisBasis?.let { rest ->
            pelvisBasisFromLandmarks(landmarks)?.let { target -> Quat.mul(target, Quat.inverse(rest)) }
        } ?: Quat.identity()

        for (i in model.hierarchyOrder) {
            val node = model.nodes[i]
            val parent = model.parents[i]
            val parentGlobal = if (parent < 0) M4.identity() else globals[parent]
            val parentRotation = if (parent < 0) Quat.identity() else Quat.fromMatrix(parentGlobal)
            val extremityWorld = extremities.worldRotation(i, landmarks)
            val fingerLocal = extremities.fingerRotation(i, landmarks, handGrip)

            val local = when {
                extremityWorld != null -> Quat.mul(Quat.inverse(parentRotation), extremityWorld)
                fingerLocal != null -> fingerLocal
                i == hipsNode -> {
                    val world = Quat.mul(pelvisDelta, restGlobalRotations[i])
                    Quat.mul(Quat.inverse(parentRotation), world)
                }
                boneByNode.containsKey(i) -> {
                    val bone = boneByNode.getValue(i)
                    val target = V3.sub(centroid(landmarks, bone.to), centroid(landmarks, bone.from))
                    if (V3.length(target) < MIN_SEGMENT_M) {
                        node.rotation
                    } else {
                        val unrotated = Quat.mul(parentRotation, node.rotation)
                        val current = Quat.rotate(unrotated, bone.restDirectionLocal)
                        val world = Quat.mul(Quat.fromTo(current, V3.normalize(target)), unrotated)
                        Quat.mul(Quat.inverse(parentRotation), world)
                    }
                }
                else -> node.rotation
            }
            globals[i] = M4.multiply(parentGlobal, M4.trs(node.translation, local, node.scale))
        }

        if (dropToGround) ground()
    }

    /** Uniform scale that makes the model [metres] tall in its rest pose. */
    fun scaleToHeight(metres: Float): Float {
        val (min, max) = model.bounds(restGlobals)
        val height = max[1] - min[1]
        return if (height < 1e-4f) 1f else metres / height
    }

    // ---- internals ----

    /**
     * Every joint counts, not just the ones the rig drives: a toe or a fingertip is often
     * what actually touches the floor, and ignoring those leaves the figure hovering.
     */
    private val groundNodes: IntArray =
        model.skins.firstOrNull()?.joints ?: IntArray(model.nodes.size) { it }

    fun ground() {
        if (groundNodes.isEmpty()) return
        val lowest = groundNodes.minOf { globals[it][13] }
        for (m in globals) m[13] -= lowest
    }

    /**
     * Orthonormal frame of the pelvis: X to the body's left, Y along the spine, Z out of
     * the chest. Taking the whole frame rather than a single direction is what carries
     * the facing and the lying-down cases.
     */
    private fun basis(hipLeft: FloatArray, hipRight: FloatArray, top: FloatArray, hipMid: FloatArray): FloatArray? {
        val up = V3.sub(top, hipMid)
        if (V3.length(up) < MIN_SEGMENT_M) return null
        val upUnit = V3.normalize(up)
        val across = V3.reject(V3.sub(hipLeft, hipRight), upUnit)
        if (V3.length(across) < MIN_SEGMENT_M) return null
        val left = V3.normalize(across)
        return Quat.fromBasis(left, upUnit, V3.cross(left, upUnit))
    }

    private fun pelvisBasisFromRest(): FloatArray? {
        val left = HumanRig.resolve(model, HumanRig.hipLeftNodes)
        val right = HumanRig.resolve(model, HumanRig.hipRightNodes)
        val top = HumanRig.resolve(model, HumanRig.NECK)
        if (left < 0 || right < 0 || top < 0 || hipsNode < 0) return null
        val hipLeft = M4.translation(restGlobals[left])
        val hipRight = M4.translation(restGlobals[right])
        return basis(hipLeft, hipRight, M4.translation(restGlobals[top]), V3.mid(hipLeft, hipRight))
    }

    private fun pelvisBasisFromLandmarks(landmarks: FloatArray): FloatArray? {
        val hipLeft = point(landmarks, Lm.HIP_L)
        val hipRight = point(landmarks, Lm.HIP_R)
        val shoulders = V3.mid(point(landmarks, Lm.SHOULDER_L), point(landmarks, Lm.SHOULDER_R))
        return basis(hipLeft, hipRight, shoulders, V3.mid(hipLeft, hipRight))
    }

    private fun point(landmarks: FloatArray, index: Int) =
        floatArrayOf(landmarks[index * 3], landmarks[index * 3 + 1], landmarks[index * 3 + 2])

    private fun centroid(landmarks: FloatArray, indices: IntArray): FloatArray {
        val out = floatArrayOf(0f, 0f, 0f)
        for (i in indices) {
            out[0] += landmarks[i * 3]; out[1] += landmarks[i * 3 + 1]; out[2] += landmarks[i * 3 + 2]
        }
        return V3.scale(out, 1f / indices.size)
    }

    private class SolvedBone(
        val node: Int,
        val restDirectionLocal: FloatArray,
        val from: IntArray,
        val to: IntArray,
    )

    private companion object {
        /** Below this the landmark pair is too close together for its direction to mean anything. */
        const val MIN_SEGMENT_M = 0.02f

        /** Fewer mapped bones than this and the model is not a humanoid we recognise. */
        const val MIN_BONES = 8
    }
}
