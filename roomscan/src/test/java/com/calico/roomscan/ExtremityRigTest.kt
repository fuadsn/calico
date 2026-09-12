package com.calico.roomscan

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class ExtremityRigTest {
    private val model = GltfModel.parse(File("src/main/assets/models/quaternius-base.glb").readBytes())
    private fun sample(exercise: String, seconds: Float): FloatArray = FloatArray(99).also {
        PoseClip.parse(File("src/main/assets/clips/$exercise.json").readText()).sample(seconds, it)
    }

    @Test fun `full GPU palette retains every finger joint`() {
        val rig = PoseRetargeter(model)
        val palette = JointPalette(model, rig, 59)
        assertEquals(model.skins[0].joints.size, palette.size)
        assertEquals(palette.size, model.skins[0].joints.indices.map { palette.remap(it) }.distinct().size)
        assertTrue(JointPalette(model, rig, 27).size <= 27)
    }

    @Test fun `grip changes finger skin matrices without moving wrist or elbow`() {
        val rig = PoseRetargeter(model)
        val pose = sample("PULLUP", 0.4f)
        rig.pose(pose, false, 0f)
        val open = rig.globals.map { it.copyOf() }
        rig.pose(pose, false, 0.9f)
        for (side in listOf("L", "R")) {
            for (part in listOf("hand", "forearm")) {
                val i = model.findNode("DEF-$part.$side")
                assertArrayEquals(open[i], rig.globals[i], 0.0001f)
            }
            for (finger in listOf("index", "middle", "ring", "pinky")) {
                val i = model.findNode("DEF-f_$finger.02.$side")
                assertTrue(open[i].indices.any { abs(open[i][it] - rig.globals[i][it]) > 0.01f })
            }
        }
    }

    @Test fun `planted squat soles keep their orientation through knee flexion`() {
        val rig = PoseRetargeter(model)
        rig.pose(sample("SQUAT", 0f), false)
        val top = rig.globals.map { it.copyOf() }
        rig.pose(sample("SQUAT", 1.2f), false)
        for (side in listOf("L", "R")) {
            val foot = model.findNode("DEF-foot.$side")
            for (i in 0..11) assertEquals(top[foot][i], rig.globals[foot][i], 0.003f)
            val shin = model.findNode("DEF-shin.$side")
            assertTrue((0..11).any { abs(top[shin][it] - rig.globals[shin][it]) > 0.05f })
        }
    }

    @Test fun `palm roll responds to pinky even with fixed wrist and index`() {
        val rig = PoseRetargeter(model)
        val pose = sample("ARM_RAISE", 0.4f)
        rig.pose(pose, false)
        val hand = model.findNode("DEF-hand.L")
        val before = rig.globals[hand].copyOf()
        pose[17 * 3 + 2] += 0.04f
        rig.pose(pose, false)
        assertTrue((0..11).any { abs(before[it] - rig.globals[hand][it]) > 0.05f })
        assertTrue(rig.globals.all { m -> m.all { it.isFinite() } })
    }
}
