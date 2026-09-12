package com.calico.roomscan

import org.json.JSONObject

/**
 * A looping animation held as MediaPipe pose landmarks, one frame at a time.
 *
 * Keeping the animation in landmark space rather than in a model's bone rotations is what
 * lets the same clip drive any rigged humanoid, and it means a clip captured from a real
 * video by `PoseLandmarker` is the same kind of file as a generated one.
 *
 * On disk:
 * ```
 * { "exercise": "PUSHUP", "fps": 8, "loop": true, "space": "mediapipe_world",
 *   "frames": [ [x0,y0,z0, x1,y1,z1, ... 33 triples ], ... ] }
 * ```
 * `mediapipe_world` is what `PoseLandmarkerResult.worldLandmarks()` returns: metres, origin
 * at the hip midpoint, +X to image right, +Y down, +Z away from the camera. It is converted
 * on load to the rig's axes, +Y up and +Z out of the chest, so the renderer sees one space.
 */
class PoseClip(
    val exercise: String,
    val fps: Float,
    val loop: Boolean,
    private val frames: List<FloatArray>,
    private val sampleTimesMs: LongArray? = null,
) {
    val frameCount get() = frames.size

    val durationSeconds get() = if (!loop && sampleTimesMs != null && sampleTimesMs.isNotEmpty())
        (sampleTimesMs.last() - sampleTimesMs.first()) / 1000f else if (fps <= 0f) 0f else frames.size / fps

    /**
     * Writes the landmarks at [seconds] into [out], blending between the two nearest frames.
     * [out] must hold 33 triples.
     */
    fun sample(seconds: Float, out: FloatArray) {
        if (frames.isEmpty()) return
        if (frames.size == 1) {
            frames[0].copyInto(out)
            return
        }
        if (!loop && sampleTimesMs != null) {
            val time = sampleTimesMs[0] + seconds.coerceAtLeast(0f) * 1000.0
            var low = 0
            var high = sampleTimesMs.lastIndex
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (sampleTimesMs[mid] <= time) low = mid else high = mid - 1
            }
            val next = (low + 1).coerceAtMost(frames.lastIndex)
            val span = sampleTimesMs[next] - sampleTimesMs[low]
            val blend = if (span == 0L) 0f else ((time - sampleTimesMs[low]) / span).toFloat().coerceIn(0f, 1f)
            for (i in out.indices) out[i] = frames[low][i] + (frames[next][i] - frames[low][i]) * blend
            return
        }
        val position = seconds * fps
        val span = if (loop) frames.size else frames.size - 1
        var index = Math.floorMod(position.toInt(), span)
        var blend = position - kotlin.math.floor(position)
        if (!loop && position >= span) {
            index = frames.size - 1
            blend = 0f
        }
        val a = frames[index]
        val b = frames[(index + 1) % frames.size]
        for (i in out.indices) out[i] = a[i] + (b[i] - a[i]) * blend
    }

    companion object {
        /** Axis conventions a clip file may be written in. */
        private const val MEDIAPIPE_WORLD = "mediapipe_world"

        fun parse(json: String): PoseClip {
            val root = JSONObject(json)
            val mediapipe = root.optString("space", MEDIAPIPE_WORLD) == MEDIAPIPE_WORLD
            val raw = root.getJSONArray("frames")
            val frames = (0 until raw.length()).map { f ->
                val values = raw.getJSONArray(f)
                require(values.length() == Lm.COUNT * 3) {
                    "frame $f has ${values.length() / 3} landmarks, expected ${Lm.COUNT}"
                }
                FloatArray(values.length()) { i ->
                    val v = values.getDouble(i).toFloat()
                    // Flipping Y and Z keeps the handedness and turns MediaPipe's
                    // y-down, z-into-scene frame into the rig's y-up, z-forward one.
                    if (mediapipe && i % 3 != 0) -v else v
                }
            }
            return PoseClip(
                exercise = root.optString("exercise", ""),
                fps = root.optDouble("fps", 8.0).toFloat(),
                loop = root.optBoolean("loop", true),
                frames = frames,
                sampleTimesMs = root.optJSONArray("sampleTimesMs")?.let { times ->
                    require(times.length() == frames.size) { "Pose/timestamp count mismatch" }
                    LongArray(times.length()) { times.getLong(it) }.also { values ->
                        require((1 until values.size).all { values[it] > values[it - 1] }) { "Pose timestamps must increase" }
                    }
                },
            )
        }
    }
}
