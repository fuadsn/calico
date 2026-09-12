package com.hackathon.calico

import com.calico.roomscan.PoseClip
import com.calico.roomscan.GltfModel
import com.calico.roomscan.PoseRetargeter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class RecordedMotionTest {
    @Test fun `mannequin motion and live counter consume the same benchmark recording`() {
        val assetRoot = File("../roomscan/src/main/assets")
        val file = File(assetRoot, "recorded-clips/SQUAT.json")
        val json = JSONObject(file.readText())
        val clip = PoseClip.parse(file.readText())
        val exercise = Exercise.valueOf(clip.exercise)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(File("src/main/assets/pose_landmarker_lite.task").readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(digest, json.getString("modelSha256"))
        val normalized = json.getJSONArray("normalizedFrames")
        val visibility = json.getJSONArray("visibilityFrames")
        val times = json.getJSONArray("sampleTimesMs")
        assertEquals(clip.frameCount, normalized.length())
        assertEquals("authored_fallback", json.getJSONObject("quality").getString("arms"))
        val sourceWorld = json.getJSONArray("sourceWorldFrames")
        val playback = json.getJSONArray("frames")
        // Quality corrections must not alter the source legs that drive squat counting.
        for (f in 0 until clip.frameCount) for (i in 23 * 3 until 99) {
            assertEquals(sourceWorld.getJSONArray(f).getDouble(i), playback.getJSONArray(f).getDouble(i), 0.000001)
        }
        val counter = RepCounter(exercise, {}, {})
        val rig = PoseRetargeter(GltfModel.parse(File(assetRoot, "models/quaternius-base.glb").readBytes()))
        val world = FloatArray(99)
        // A complete source repetition should count once per loop, with actual app rules.
        for (cycle in 0..2) for (f in 0 until clip.frameCount) {
            val points = normalized.getJSONArray(f)
            val confidence = visibility.getJSONArray(f)
            val sample = PoseAngles.select(exercise, FloatArray(99) { points.getDouble(it).toFloat() },
                FloatArray(33) { confidence.getDouble(it).toFloat() })
            assertNotNull(sample)
            sample!!.left?.let { counter.feed(it, times.getLong(f) + cycle * (json.getLong("endMs") - json.getLong("startMs"))) }
            clip.sample(f / clip.fps, world)
            rig.pose(world)
            assertTrue(rig.globals.all { m -> m.all { it.isFinite() } })
        }
        assertEquals(3, counter.count)
    }
}
