package com.calico.roomscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.acos

/**
 * Checks the rig end to end on the real shipped files: a glTF humanoid is loaded, a shipped
 * clip is played through it, and the posed skeleton is measured.
 *
 * The point is that these assertions fail for the mistakes that are otherwise invisible
 * until the model is on a phone: a mirrored rig, a limb that stretches, a figure that
 * floats, or joint angles that do not follow the clip.
 */
class PoseRetargeterTest {

    private val assets = File("src/main/assets")

    private fun model(name: String): GltfModel {
        val file = File(assets, "models/$name")
        return GltfModel.parse(file.readBytes()) { uri -> File(file.parentFile, uri).readBytes() }
    }

    private fun clip(name: String) = PoseClip.parse(File(assets, "clips/$name.json").readText())

    private fun position(retargeter: PoseRetargeter, name: String) =
        M4.translation(retargeter.globals[retargeter.model.findNode(name)])

    private fun angleAt(a: FloatArray, b: FloatArray, c: FloatArray): Float {
        val u = V3.normalize(V3.sub(a, b))
        val v = V3.normalize(V3.sub(c, b))
        return Math.toDegrees(acos(V3.dot(u, v).coerceIn(-1f, 1f)).toDouble()).toFloat()
    }

    private fun landmarkAngle(frame: FloatArray, a: Int, b: Int, c: Int): Float {
        fun at(i: Int) = floatArrayOf(frame[i * 3], frame[i * 3 + 1], frame[i * 3 + 2])
        return angleAt(at(a), at(b), at(c))
    }

    @Test
    fun `sample model exposes a humanoid rig`() {
        val retargeter = PoseRetargeter(model("human.glb"))
        assertTrue("humanoid rig not recognised", retargeter.isUsable)
        assertTrue("hips not found", retargeter.hipsNode >= 0)
    }

    @Test
    fun `elbow follows the angle the clip asks for`() {
        val model = model("human.glb")
        val retargeter = PoseRetargeter(model)
        val pushup = clip("PUSHUP")
        val frame = FloatArray(Lm.COUNT * 3)

        var minimum = 180f
        for (i in 0 until pushup.frameCount) {
            pushup.sample(i / pushup.fps, frame)
            retargeter.pose(frame)
            val expected = landmarkAngle(frame, Lm.SHOULDER_L, Lm.ELBOW_L, Lm.WRIST_L)
            val actual = angleAt(
                position(retargeter, "Skeleton_arm_joint_L__4_"),
                position(retargeter, "Skeleton_arm_joint_L__3_"),
                position(retargeter, "Skeleton_arm_joint_L__2_"),
            )
            assertEquals("frame $i", expected, actual, 2f)
            minimum = minOf(minimum, actual)
        }
        assertTrue("a pushup should bend the elbow past 100 degrees, got $minimum", minimum < 100f)
    }

    @Test
    fun `bones keep their length through the whole clip`() {
        val model = model("human.glb")
        val retargeter = PoseRetargeter(model)
        val squat = clip("SQUAT")
        val frame = FloatArray(Lm.COUNT * 3)
        val lengths = mutableListOf<Float>()

        for (i in 0 until squat.frameCount) {
            squat.sample(i / squat.fps, frame)
            retargeter.pose(frame)
            lengths += V3.length(
                V3.sub(position(retargeter, "leg_joint_L_2"), position(retargeter, "leg_joint_L_1"))
            )
        }
        val spread = lengths.max() - lengths.min()
        assertTrue("thigh length drifted by $spread", spread < 1e-4f)
    }

    @Test
    fun `figure stands on the ground rather than floating`() {
        val model = model("human.glb")
        val retargeter = PoseRetargeter(model)
        val frame = FloatArray(Lm.COUNT * 3)

        for (name in listOf("SQUAT", "PUSHUP", "PLANK")) {
            val clip = clip(name)
            clip.sample(0f, frame)
            retargeter.pose(frame)
            val lowest = model.skins.first().joints.minOf { retargeter.globals[it][13] }
            assertEquals("$name sits off the floor", 0f, lowest, 1e-3f)
        }
    }

    @Test
    fun `left and right are not mirrored`() {
        val model = model("human.glb")
        val retargeter = PoseRetargeter(model)
        val highKnees = clip("HIGH_KNEES")
        val frame = FloatArray(Lm.COUNT * 3)

        // A quarter of the way through the cycle the clip has the left knee lifted, so the
        // model's left knee has to be the higher one. Swapped sides fail here and nowhere else.
        highKnees.sample(0.25f * highKnees.durationSeconds, frame)
        val leftLandmark = frame[Lm.KNEE_L * 3 + 1]
        val rightLandmark = frame[Lm.KNEE_R * 3 + 1]
        assertNotEquals("clip does not lift one knee at this point", leftLandmark, rightLandmark, 0.05f)

        retargeter.pose(frame)
        val left = position(retargeter, "leg_joint_L_2")[1]
        val right = position(retargeter, "leg_joint_R_2")[1]
        // Landmarks arrive y-down from MediaPipe and are flipped on load, so the higher
        // landmark is the more negative one in the file.
        val leftIsHigher = leftLandmark > rightLandmark
        assertEquals("the wrong knee is raised", leftIsHigher, left > right)
        assertTrue("both knees are at the same height", abs(left - right) > 0.02f)
    }

    @Test
    fun `an unrecognised model is reported rather than drawn wrong`() {
        val cube = GltfModel(
            nodes = listOf(GltfNode("box", IntArray(0), floatArrayOf(0f, 0f, 0f),
                Quat.identity(), floatArrayOf(1f, 1f, 1f), -1, -1)),
            meshes = emptyList(), skins = emptyList(), materials = emptyList(), roots = intArrayOf(0),
        )
        assertTrue("a cube should not pass as a humanoid", !PoseRetargeter(cube).isUsable)
    }
}
