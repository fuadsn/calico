package com.calico.roomscan

/** Retains the complete skin when the GPU has space; otherwise folds excess joints into ancestors. */
class JointPalette(model: GltfModel, retargeter: PoseRetargeter, maxJoints: Int = 28) {

    private val skin = model.skins.firstOrNull()

    /** Palette slot for each joint of the original skin. */
    private val slotOf: IntArray

    /** Index into the original skin for each palette slot. */
    private val sourceOf: IntArray

    val size get() = sourceOf.size

    init {
        if (skin == null) {
            slotOf = IntArray(0)
            sourceOf = IntArray(0)
        } else {
            val keep = choose(model, skin, retargeter, maxJoints.coerceAtLeast(1))
            sourceOf = keep
            val slotByNode = HashMap<Int, Int>()
            keep.forEachIndexed { slot, j -> slotByNode[skin.joints[j]] = slot }
            slotOf = IntArray(skin.joints.size) { j ->
                var node = skin.joints[j]
                // Walk up until a kept joint is found; the skeleton root always survives.
                while (node >= 0 && !slotByNode.containsKey(node)) node = model.parents[node]
                slotByNode[node] ?: 0
            }
        }
    }

    fun remap(joint: Int) = if (joint in slotOf.indices) slotOf[joint] else 0

    /**
     * Weights for [primitive] with any that collapsed onto the same palette slot added
     * together, so a vertex split across three finger bones still sums to one.
     */
    fun mergedWeights(primitive: GltfPrimitive): FloatArray {
        val out = primitive.weights.copyOf()
        for (v in 0 until primitive.vertexCount) {
            val base = v * 4
            for (a in 0 until 4) {
                if (out[base + a] == 0f) continue
                for (b in a + 1 until 4) {
                    if (out[base + b] == 0f) continue
                    if (remap(primitive.joints[base + a]) == remap(primitive.joints[base + b])) {
                        out[base + a] += out[base + b]
                        out[base + b] = 0f
                    }
                }
            }
        }
        return out
    }

    /** Writes the skinning matrices for this frame's pose. */
    fun fill(retargeter: PoseRetargeter, out: FloatArray) {
        val skin = skin ?: return
        for (slot in sourceOf.indices) {
            val joint = sourceOf[slot]
            M4.multiplyInto(retargeter.globals[skin.joints[joint]], skin.inverseBind[joint], out, slot * 16)
        }
    }

    private companion object {
        /** Bone name fragments that only ever carry finger geometry. */
        val FINGER_PARTS = listOf("f_index", "f_middle", "f_ring", "f_pinky", "thumb", "palm_")

        /**
         * Keeps the joints worth animating: never a finger, and preferring joints the pose
         * rig drives, then whichever remaining joints hold the most of the mesh.
         */
        fun choose(model: GltfModel, skin: GltfSkin, retargeter: PoseRetargeter, limit: Int): IntArray {
            if (skin.joints.size <= limit) return skin.joints.indices.toList().toIntArray()
            val driven = HashSet<Int>()
            HumanRig.bones.forEach { bone ->
                HumanRig.resolve(model, bone.nodeNames).takeIf { it >= 0 }?.let { driven += it }
                HumanRig.resolve(model, bone.tipNames).takeIf { it >= 0 }?.let { driven += it }
            }
            if (retargeter.hipsNode >= 0) driven += retargeter.hipsNode

            val candidates = skin.joints.indices.filter { j ->
                val name = model.nodes[skin.joints[j]].name
                skin.joints[j] in driven || FINGER_PARTS.none { name.contains(it) }
            }
            if (candidates.size <= limit) return candidates.toIntArray()

            val weight = jointWeights(model, skin)
            // Driven joints must survive even when the mesh barely leans on them, or the
            // limb they belong to would stop moving.
            val ranked = candidates.sortedByDescending {
                (if (skin.joints[it] in driven) 1e6f else 0f) + weight[it]
            }
            return ranked.take(limit).sorted().toIntArray()
        }

        /** Total skin weight each joint carries across every primitive of the model. */
        fun jointWeights(model: GltfModel, skin: GltfSkin): FloatArray {
            val totals = FloatArray(skin.joints.size)
            for (mesh in model.meshes) for (primitive in mesh.primitives) {
                if (primitive.joints.isEmpty() || primitive.weights.isEmpty()) continue
                for (i in primitive.joints.indices) {
                    val j = primitive.joints[i]
                    if (j in totals.indices && i < primitive.weights.size) totals[j] += primitive.weights[i]
                }
            }
            return totals
        }
    }
}
