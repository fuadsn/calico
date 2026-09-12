package com.calico.roomscan

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PoseRecordingTest {
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
