package com.calico.roomscan

import org.json.JSONArray
import org.json.JSONObject

/** An explicit video export from the same detection results used by the rep counter.
 * Keeps the longest uninterrupted, confident segment; never bridges detection losses. */
class PoseRecording(private val exercise: String) {
    private data class Sample(val ms: Long, val points: FloatArray,
        val normalized: FloatArray, val visibility: FloatArray)
    private var current = mutableListOf<Sample>()
    private var best = emptyList<Sample>()

    fun add(ms: Long, points: FloatArray?, normalized: FloatArray?, visibility: FloatArray?, confident: Boolean) {
        if (!confident || points == null || points.size != 99 || points.any { !it.isFinite() } ||
            normalized == null || normalized.size != 99 || normalized.any { !it.isFinite() } ||
            visibility == null || visibility.size != 33 || visibility.any { !it.isFinite() || it !in 0f..1f }) {
            finishSegment()
            return
        }
        current.lastOrNull()?.let {
            if (ms <= it.ms || ms - it.ms > 250) finishSegment()
        }
        if (current.isNotEmpty() && ms - current.first().ms > 60_000) finishSegment()
        current += Sample(ms, points.copyOf(), normalized.copyOf(), visibility.copyOf())
    }

    private fun finishSegment() {
        if (current.size > best.size) best = current.toList()
        current = mutableListOf()
    }

    fun toJson(sourceVideo: String, sourceSha256: String? = null, modelSha256: String? = null): String {
        finishSegment()
        require(best.size >= 16 && best.last().ms - best.first().ms >= 1000) {
            "No continuous confident pose segment of at least one second"
        }
        val duration = best.last().ms - best.first().ms
        val fps = (best.size - 1) * 1000.0 / duration
        // Preserve the exact detections fed to counting. Playback interpolates by their timestamps.
        val frames = JSONArray(best.map { JSONArray(it.points.toList()) })
        return JSONObject().put("exercise", exercise).put("fps", fps).put("loop", false)
            .put("schemaVersion", 2)
            .put("sourceSha256", sourceSha256).put("modelSha256", modelSha256)
            .put("runtime", "mediapipe-android")
            .put("space", "mediapipe_world").put("source", "rep_counter_video_pose_landmarker_lite")
            .put("sourceVideo", sourceVideo).put("startMs", best.first().ms)
            .put("endMs", best.last().ms).put("frames", frames)
            .put("normalizedFrames", JSONArray(best.map { JSONArray(it.normalized.toList()) }))
            .put("visibilityFrames", JSONArray(best.map { JSONArray(it.visibility.toList()) }))
            .put("sampleTimesMs", JSONArray(best.map { it.ms }))
            .put("smoothing", "none").toString()
    }
}
