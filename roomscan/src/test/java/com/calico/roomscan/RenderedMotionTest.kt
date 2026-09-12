package com.calico.roomscan

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RenderedMotionTest {
    @Test fun `final solved motion has jumps body travel and stationary supports`() {
        val model = GltfModel.parse(File("src/main/assets/models/quaternius-base.glb").readBytes())
        for (name in listOf("JUMPING_JACK", "INCLINE_PUSHUP", "PUSHUP", "DIP", "PULLUP", "SITUP", "LEG_RAISE")) {
            val clip = PoseClip.parse(File("src/main/assets/clips/$name.json").readText())
            val p = FloatArray(99).also { clip.sample(0f,it) }
            val rig = PoseRetargeter(model)
            val contact = ContactRig(model,name,p)
            val scale = rig.scaleToHeight(1.75f)
            val hipY = mutableListOf<Float>(); val footY = mutableListOf<Float>(); val shoulderY = mutableListOf<Float>()
            val angles = mutableListOf<Float>()
            fun point(node: String) = M4.translation(rig.globals[model.findNode(node)])
            for (f in 0 until 120) {
                val seconds = f / (clip.fps*2)
                clip.sample(seconds,p); rig.pose(p,false,0f)
                contact.apply(rig,p,seconds,clip.durationSeconds)
                val flight = ExerciseMotion.flight(name,seconds,clip.durationSeconds)
                hipY += point("DEF-hips")[1]*scale + flight
                footY += minOf(point("DEF-foot.L")[1],point("DEF-foot.R")[1])*scale + flight
                shoulderY += point("DEF-upper_arm.L")[1]*scale + flight
                val a = V3.sub(point("DEF-upper_arm.L"),point("DEF-forearm.L"))
                val b = V3.sub(point("DEF-hand.L"),point("DEF-forearm.L"))
                angles += Math.toDegrees(kotlin.math.acos(V3.dot(V3.normalize(a),V3.normalize(b)).coerceIn(-1f,1f)).toDouble()).toFloat()
            }
            if (name == "JUMPING_JACK") assertTrue("Jack feet never leave floor",footY.max()-footY.min()>0.14f)
            if (name in listOf("INCLINE_PUSHUP","PUSHUP","DIP","PULLUP")) {
                assertTrue("$name torso travel ${hipY.max()-hipY.min()}",hipY.max()-hipY.min()>0.12f)
                assertTrue("$name elbows ${angles.min()}..${angles.max()}",angles.max()-angles.min()>45f)
            }
            if (name == "PULLUP") assertTrue("Pullup feet should hang clear",footY.min()>0.1f)
            if (name == "SITUP") assertTrue("Situp shoulders static",shoulderY.max()-shoulderY.min()>0.2f)
            if (name == "LEG_RAISE") {
                assertTrue("Leg raise pelvis drifts",hipY.max()-hipY.min()<0.01f)
                assertTrue("Leg raise feet static",footY.max()-footY.min()>0.3f)
            }
        }
    }
}
