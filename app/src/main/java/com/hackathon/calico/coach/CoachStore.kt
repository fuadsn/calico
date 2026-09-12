package com.hackathon.calico.coach

import android.content.Context
import org.json.JSONObject

data class CoachSnapshot(val exercise: String, val count: Int, val target: Int?, val hold: Boolean,
    val cues: Map<String, Int>, val minAngle: Float?, val maxAngle: Float?, val timeMs: Long)

/** Only numerical summaries/cues are kept here; no video, landmarks, or chat transcripts. */
class CoachStore(context: Context) {
    private val prefs = context.getSharedPreferences("offline_coach", Context.MODE_PRIVATE)
    fun save(s: CoachSnapshot) {
        val json = JSONObject().put("exercise",s.exercise).put("count",s.count).put("target",s.target)
            .put("hold",s.hold).put("cues",JSONObject(s.cues)).put("minAngle",s.minAngle)
            .put("maxAngle",s.maxAngle).put("timeMs",s.timeMs)
        prefs.edit().putString("latest",json.toString()).apply()
    }
    fun read(): CoachSnapshot? = runCatching {
        val json = JSONObject(prefs.getString("latest",null) ?: return null)
        val cues = json.getJSONObject("cues")
        CoachSnapshot(json.getString("exercise"),json.getInt("count"),
            if(json.isNull("target")) null else json.getInt("target"),json.getBoolean("hold"),
            cues.keys().asSequence().associateWith { cues.getInt(it) },
            if(json.isNull("minAngle")) null else json.getDouble("minAngle").toFloat(),
            if(json.isNull("maxAngle")) null else json.getDouble("maxAngle").toFloat(),json.getLong("timeMs"))
    }.getOrNull()
    fun clear() { prefs.edit().remove("latest").apply() }
}
