package com.calico.roomscan

import org.json.JSONArray
import org.json.JSONObject

/** An explicit video export from the same detection results used by the rep counter.
 * Keeps the longest uninterrupted, confident segment; never bridges detection losses. */
class PoseRecording(private val exercise: String) {
    private data class Sample(val ms: Long, val points: FloatArray)
    private var current = mutableListOf<Sample>()
    private var best = emptyList<Sample>()

    fun add(ms: Long, points: FloatArray?, confident: Boolean) {
        if (!confident || points == null || points.size != 99 || points.any { !it.isFinite() }) {
            finishSegment()
            return
        }
        current.lastOrNull()?.let {
            if (ms <= it.ms || ms - it.ms > 250) finishSegment()
        }
        if (current.isNotEmpty() && ms - current.first().ms > 60_000) finishSegment()
        current += Sample(ms, points.copyOf())
    }

    private fun finishSegment() {
        if (current.size > best.size) best = current.toList()
        current = mutableListOf()
    }

    fun toJson(sourceVideo: String): String {
        finishSegment()
        require(best.size >= 16 && best.last().ms - best.first().ms >= 1000) {
            "No continuous confident pose segment of at least one second"
        }
        val fps = 30
        val frames = JSONArray()
        var index = 0
        val duration = best.last().ms - best.first().ms
        for (f in 0..(duration * fps / 1000).toInt()) {
            val time = best.first().ms + f * 1000.0 / fps
            while (index + 1 < best.lastIndex && best[index + 1].ms < time) index++
            val a = best[index]
            val b = best[index + 1]
            val blend = ((time - a.ms) / (b.ms - a.ms)).coerceIn(0.0, 1.0)
            frames.put(JSONArray(List(99) { i -> a.points[i] + (b.points[i] - a.points[i]) * blend }))
        }
        return JSONObject().put("exercise", exercise).put("fps", fps).put("loop", false)
            .put("space", "mediapipe_world").put("source", "rep_counter_video_pose_landmarker_lite")
            .put("sourceVideo", sourceVideo).put("startMs", best.first().ms)
            .put("endMs", best.last().ms).put("frames", frames).toString()
    }
}
