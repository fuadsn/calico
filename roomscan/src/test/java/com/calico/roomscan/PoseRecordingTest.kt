package com.calico.roomscan

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PoseRecordingTest {
    private fun PoseRecording.add(ms: Long, points: FloatArray?, confident: Boolean) =
        add(ms, points, points, FloatArray(33) { 0.9f }, confident)

    @Test fun `shared export preserves original image world and confidence frames at irregular timestamps`() {
        val recording = PoseRecording("PUSHUP")
        val times = (0..20).map { 500L + it * 70L + if (it % 2 == 0) 0 else 9 }
        for ((i, time) in times.withIndex()) {
            recording.add(time, FloatArray(99) { i / 100f }, FloatArray(99) { i / 50f }, FloatArray(33) { 0.8f }, true)
        }
        val text = recording.toJson("PUSHUP-side_1.mp4")
        val root = JSONObject(text)
        val clip = PoseClip.parse(text)
        val out = FloatArray(99)
        for ((i, time) in times.withIndex()) {
            assertEquals(time, root.getJSONArray("sampleTimesMs").getLong(i))
            assertEquals(i / 50.0, root.getJSONArray("normalizedFrames").getJSONArray(i).getDouble(0), 0.00001)
            assertEquals(0.8, root.getJSONArray("visibilityFrames").getJSONArray(i).getDouble(0), 0.00001)
            clip.sample((time - times[0]) / 1000f, out)
            assertEquals(i / 100f, out[0], 0.00001f)
        }
        assertEquals(times.size, clip.frameCount)
        assertEquals(1.4f, clip.durationSeconds, 0.00001f)
    }

    @Test fun `missing counting data splits the recording instead of mismatching streams`() {
        val recording = PoseRecording("HIGH_KNEES")
        for (i in 0..20) recording.add(i*70L, FloatArray(99), true)
        recording.add(1500, FloatArray(99), null, FloatArray(33) { 1f }, true)
        for (i in 0..4) recording.add(1600+i*70L, FloatArray(99) { 1f }, true)
        val root = JSONObject(recording.toJson("HIGH_KNEES.mp4"))
        assertEquals(21, root.getJSONArray("frames").length())
        assertEquals(1400L, root.getLong("endMs"))
    }
    @Test fun `export preserves timing coordinates and provenance`() {
        val recording = PoseRecording("SQUAT")
        for (i in 0..20) recording.add(i * 67L, FloatArray(99) { i * 0.01f }, true)
        val json = recording.toJson("SQUAT-side_1.mp4")
        val clip = PoseClip.parse(json)
        val out = FloatArray(99)
        clip.sample(0.67f, out)
        assertEquals(0.1f, out[0], 0.001f)
        assertEquals(-0.1f, out[1], 0.001f)
        assertFalse(clip.loop)
        assertEquals("rep_counter_video_pose_landmarker_lite", JSONObject(json).getString("source"))
    }

    @Test fun `detection gaps do not become invented movement`() {
        val recording = PoseRecording("SQUAT")
        for (i in 0..20) recording.add(i * 67L, FloatArray(99), true)
        recording.add(1500, null, false)
        for (i in 0..10) recording.add(3000 + i * 67L, FloatArray(99) { 10f }, true)
        val root = JSONObject(recording.toJson("source.mp4"))
        assertEquals(1340L, root.getLong("endMs"))
        assertEquals(0L, root.getLong("startMs"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `short or missing detections cannot replace demo clips`() {
        val recording = PoseRecording("SQUAT")
        recording.add(0, FloatArray(99), true)
        recording.add(100, FloatArray(99) { Float.NaN }, true)
        recording.toJson("bad.mp4")
    }
}
