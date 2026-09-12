package com.calico.roomscan

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ExerciseSupportTest {
    private fun patch(id: Int, y: Float, x0: Float, z0: Float, x1: Float, z1: Float) =
        SupportPatch(id, y, floatArrayOf(x0,z0,x1,z0,x1,z1,x0,z1))

    @Test fun `incline and dips require two reachable surfaces fitting both pairs`() {
        val floor = patch(0, 0f, -3f,-3f,3f,3f)
        val bench = patch(1, 0.6f, -0.8f,0f,0.8f,0.8f)
        for (exercise in listOf("INCLINE_PUSHUP", "DIP")) {
            assertNull(SupportPlanner.find(exercise, listOf(floor), floatArrayOf(0f,1.5f,-2f)))
            val plan = SupportPlanner.find(exercise, listOf(floor, bench), floatArrayOf(0f,1.5f,-2f))!!
            assertEquals(0, plan.floorId); assertEquals(1, plan.handId)
            assertEquals(0.6f, plan.support.handHeight, 0f)
            assertFalse(bench.contains(plan.foot[0], plan.foot[2]))
        }
        val tiny = patch(1, 0.6f, -0.1f,0f,0.1f,0.2f)
        assertNull(SupportPlanner.find("INCLINE_PUSHUP", listOf(floor,tiny), floatArrayOf(0f,1.5f,-2f)))
        val tall = patch(1, 1.5f, -1f,0f,1f,1f)
        assertNull(SupportPlanner.find("INCLINE_PUSHUP", listOf(floor,tall), floatArrayOf(0f,1.6f,-2f)))
    }

    @Test fun `jump has two ballistic flights and planted ends per cycle`() {
        val duration = 1.1f
        assertEquals(0f, ExerciseMotion.flight("JUMPING_JACK", 0f, duration), 0f)
        assertEquals(0f, ExerciseMotion.flight("JUMPING_JACK", duration/2, duration), 0f)
        assertTrue(ExerciseMotion.flight("JUMPING_JACK", duration/4, duration) > 0.15f)
        assertEquals(ExerciseMotion.flight("JUMPING_JACK", duration/4, duration),
            ExerciseMotion.flight("JUMPING_JACK", duration*0.75f, duration), 0.0001f)
        assertEquals(0f, ExerciseMotion.flight("SQUAT", 0.4f, duration), 0f)
    }

    @Test fun `support IK keeps contacts and bone lengths across exercise cycles`() {
        val model = GltfModel.parse(File("src/main/assets/models/quaternius-base.glb").readBytes())
        for (exercise in listOf("INCLINE_PUSHUP", "DIP", "PULLUP", "PLANK", "SITUP", "LUNGE", "JUMPING_JACK")) {
            val clip = PoseClip.parse(File("src/main/assets/clips/$exercise.json").readText())
            val p = FloatArray(99).also { clip.sample(0f, it) }
            val rig = PoseRetargeter(model)
            val contact = ContactRig(model, exercise, p)
            val scale = rig.scaleToHeight(1.75f)
            assertTrue("No profile: $exercise", contact.enabled)
            val heights = if (exercise == "INCLINE_PUSHUP") listOf(0.3f,0.6f,0.9f) else listOf<Float?>(null)
            for (height in heights) for (frame in 0 until 120) {
                val seconds = frame / clip.fps
                clip.sample(seconds, p)
                rig.pose(p, false, 0f)
                val lengths = model.nodes.indices.map { i ->
                    val parent = model.parents[i]
                    if (parent < 0) 0f else V3.length(V3.sub(M4.translation(rig.globals[i]), M4.translation(rig.globals[parent])))
                }
                val support = if (height == null) ExerciseMotion.support(exercise) else ExerciseMotion.support(exercise, height)
                contact.apply(rig, p, seconds, clip.durationSeconds, support)
                if (exercise == "PLANK") for (side in listOf("L","R")) {
                    val elbowY = rig.globals[model.findNode("DEF-forearm.$side")][13] * scale
                    assertTrue("Plank elbow floats: $elbowY", elbowY in 0f..0.08f)
                }
                assertTrue("$exercise height=$height frame=$frame errors=${contact.errors(rig,p).map { it*scale }}",
                    contact.errors(rig,p).all { it*scale < 0.01f })
                for (i in model.nodes.indices) {
                    assertTrue(rig.globals[i].all { it.isFinite() })
                    val parent = model.parents[i]
                    if (parent >= 0) assertEquals("$exercise bone $i", lengths[i],
                        V3.length(V3.sub(M4.translation(rig.globals[i]),M4.translation(rig.globals[parent]))),0.0001f)
                }
            }
        }
    }
}
