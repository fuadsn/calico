package com.calico.roomscan

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ContactRigTest {
    private val model = GltfModel.parse(File("src/main/assets/models/quaternius-base.glb").readBytes())

    @Test fun `planted contacts do not slide and bones retain length`() {
        for (exercise in listOf("SQUAT", "PUSHUP", "PIKE_PUSHUP", "HIGH_KNEES", "MOUNTAIN_CLIMBER")) {
            val file = File("src/main/assets/${if (exercise == "SQUAT") "recorded-clips" else "clips"}/$exercise.json")
            val clip = PoseClip.parse(file.readText())
            val p = FloatArray(99).also { clip.sample(0f, it) }
            val rig = PoseRetargeter(model)
            val contacts = ContactRig(model, exercise, p)
            assertTrue(contacts.enabled)
            val scale = rig.scaleToHeight(1.75f)
            val hips = mutableListOf<Float>()
            for (frame in 0 until clip.frameCount * 2) {
                clip.sample(frame / (clip.fps * 2), p)
                rig.pose(p, false, if (exercise == "HIGH_KNEES") 0.55f else 0f)
                val lengths = model.nodes.indices.map { i ->
                    val parent = model.parents[i]
                    if (parent < 0) 0f else V3.length(V3.sub(M4.translation(rig.globals[i]), M4.translation(rig.globals[parent])))
                }
                contacts.apply(rig, p)
                assertTrue("$exercise $frame contacts ${contacts.errors(rig, p).map { it * scale }}",
                    contacts.errors(rig, p).all { it * scale < 0.005f })
                for (i in model.nodes.indices) {
                    val parent = model.parents[i]
                    if (parent >= 0) assertEquals("$exercise bone ${model.nodes[i].name}", lengths[i],
                        V3.length(V3.sub(M4.translation(rig.globals[i]), M4.translation(rig.globals[parent]))), 0.0001f)
                }
                if (exercise == "PUSHUP") for (side in listOf("L", "R")) {
                    val a = M4.translation(rig.globals[model.findNode("DEF-thigh.$side")])
                    val b = M4.translation(rig.globals[model.findNode("DEF-shin.$side")])
                    val c = M4.translation(rig.globals[model.findNode("DEF-foot.$side")])
                    val cosine = V3.dot(V3.normalize(V3.sub(a, b)), V3.normalize(V3.sub(c, b)))
                    assertTrue("Pushup knees should stay nearly straight", cosine < -0.98f)
                }
                hips += rig.globals[rig.hipsNode][13] * scale
                assertTrue(rig.globals.all { m -> m.all { it.isFinite() } })
            }
            if (exercise == "SQUAT") assertTrue("Pelvis must descend into squat", hips.max() - hips.min() > 0.15f)
        }
    }

    @Test fun `high knees palm plane stays lateral rather than horizontal`() {
        val clip = PoseClip.parse(File("src/main/assets/clips/HIGH_KNEES.json").readText())
        val p = FloatArray(99)
        fun point(i: Int) = p.copyOfRange(i * 3, i * 3 + 3)
        for (f in 0 until clip.frameCount) {
            clip.sample(f / clip.fps, p)
            for (s in 0..1) {
                val forward = V3.sub(point(19 + s), point(15 + s))
                val across = V3.sub(point(19 + s), point(17 + s))
                val normal = V3.normalize(V3.cross(forward, across))
                assertTrue("frame $f palm $s ${normal.toList()}", kotlin.math.abs(normal[0]) > 0.8f)
            }
        }
    }
}
