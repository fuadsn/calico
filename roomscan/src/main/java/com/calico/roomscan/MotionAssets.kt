package com.calico.roomscan

import android.content.res.AssetManager

/** One motion source for both the mannequin and the skeleton fallback. Recorded clips
 * are reviewed benchmark detections; authored clips cover exercises without footage. */
object MotionAssets {
    fun read(assets: AssetManager, exercise: String): PoseClip {
        require(exercise.matches(Regex("[A-Z_]+"))) { "Invalid exercise" }
        val recorded = "recorded-clips/$exercise.json"
        val path = if (assets.list("recorded-clips")?.contains("$exercise.json") == true)
            recorded else "clips/$exercise.json"
        return PoseClip.parse(assets.open(path).bufferedReader().use { it.readText() }).also {
            require(it.exercise == exercise) { "Motion exercise mismatch: $path" }
        }
    }
}
