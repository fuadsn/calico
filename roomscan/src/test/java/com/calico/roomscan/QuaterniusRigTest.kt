package com.calico.roomscan

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class QuaterniusRigTest {
    @Test fun `mannequin elbows and knees follow the exercise angles`() {
        val folder = File("src/main/assets")
        val model = GltfModel.parse(File(folder, "models/quaternius-base.glb").readBytes())
        val rig = PoseRetargeter(model)
        val frame = FloatArray(Lm.COUNT * 3)
        fun angle(a: FloatArray, b: FloatArray, c: FloatArray): Float {
            val dot = V3.dot(V3.normalize(V3.sub(a, b)), V3.normalize(V3.sub(c, b)))
            return Math.toDegrees(kotlin.math.acos(dot.coerceIn(-1f, 1f)).toDouble()).toFloat()
        }
        val chains = listOf(
            listOf("DEF-upper_arm.L", "DEF-forearm.L", "DEF-hand.L") to listOf(11, 13, 15),
            listOf("DEF-upper_arm.R", "DEF-forearm.R", "DEF-hand.R") to listOf(12, 14, 16),
            listOf("DEF-thigh.L", "DEF-shin.L", "DEF-foot.L") to listOf(23, 25, 27),
            listOf("DEF-thigh.R", "DEF-shin.R", "DEF-foot.R") to listOf(24, 26, 28))
        for (file in File(folder, "clips").listFiles()!!.filter { it.extension == "json" }) {
            val clip = PoseClip.parse(file.readText())
            for (f in 0 until clip.frameCount) {
                clip.sample(f / clip.fps, frame)
                rig.pose(frame)
                for ((bones, landmarks) in chains) {
                    val actual = bones.map { M4.translation(rig.globals[model.findNode(it)]) }
                    val expected = landmarks.map { frame.copyOfRange(it * 3, it * 3 + 3) }
                    assertEquals("${file.name} frame $f ${bones[1]}",
                        angle(expected[0], expected[1], expected[2]), angle(actual[0], actual[1], actual[2]), 2f)
                }
            }
        }
    }

    @Test fun `all exercise clips produce a finite human sized weighted mannequin`() {
        val folder = File("src/main/assets")
        val model = GltfModel.parse(File(folder, "models/quaternius-base.glb").readBytes())
        val rig = PoseRetargeter(model)
        assertTrue(rig.isUsable)
        val palette = JointPalette(model, rig)
        assertTrue(palette.size in 1..28)
        val matrices = FloatArray(palette.size * 16)
        val frame = FloatArray(Lm.COUNT * 3)
        val scale = rig.scaleToHeight(1.75f)
        val primitives = model.meshes.flatMap { it.primitives }
        for (file in File(folder, "clips").listFiles()!!.filter { it.extension == "json" }) {
            val clip = PoseClip.parse(file.readText())
            for (f in 0 until clip.frameCount) {
                clip.sample(f / clip.fps, frame)
                rig.pose(frame)
                palette.fill(rig, matrices)
                assertTrue("${file.name} frame $f", matrices.all { it.isFinite() })
                for (mesh in primitives) {
                    for (v in 0 until mesh.vertexCount step 17) {
                        val p = FloatArray(3)
                        for (weight in 0..3) {
                            val m = palette.remap(mesh.joints[v * 4 + weight]) * 16
                            val w = mesh.weights[v * 4 + weight]
                            for (axis in 0..2) p[axis] += w * (
                                matrices[m + axis] * mesh.positions[v * 3] +
                                matrices[m + 4 + axis] * mesh.positions[v * 3 + 1] +
                                matrices[m + 8 + axis] * mesh.positions[v * 3 + 2] + matrices[m + 12 + axis]) * scale
                        }
                        assertTrue("${file.name} frame $f vertex $v ${p.toList()}",
                            p.all { it.isFinite() && abs(it) < 4f } && p[1] > -0.5f)
                    }
                }
            }
        }
    }
}
