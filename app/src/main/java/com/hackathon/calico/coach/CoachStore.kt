package com.hackathon.calico.coach

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CoachSnapshot(val exercise: String, val count: Int, val target: Int?, val hold: Boolean,
    val cues: Map<String, Int>, val minAngle: Float?, val maxAngle: Float?, val timeMs: Long,
    val sessionId: String = "legacy", val step: Int = 0, val frames: Int = 0,
    val trackedFrames: Int = 0, val completed: Boolean = false)

/** Bounded local history: summaries and conversation, never video or raw landmarks. */
class CoachStore(context: Context) {
    private val prefs=context.getSharedPreferences("offline_coach",Context.MODE_PRIVATE)
    fun save(s: CoachSnapshot) {
        val history=history().toMutableList()
        val index=history.indexOfFirst { it.sessionId==s.sessionId && it.step==s.step }
        val saved=s.copy(completed=s.completed || (history.getOrNull(index)?.completed==true))
        if(index<0) history.add(saved) else history[index]=saved
        prefs.edit().putString("latest",encode(saved).toString())
            .putString("history",JSONArray(history.takeLast(200).map(::encode)).toString()).apply()
    }
    fun read(): CoachSnapshot? = runCatching { decode(JSONObject(prefs.getString("latest",null) ?: return null)) }.getOrNull()
    fun history(): List<CoachSnapshot> = runCatching {
        val array=JSONArray(prefs.getString("history","[]"))
        (0 until array.length()).map { decode(array.getJSONObject(it)) }
    }.getOrDefault(emptyList()).ifEmpty { listOfNotNull(read()) }
    fun messages(): List<CoachMessage> = runCatching {
        val a=JSONArray(prefs.getString("messages","[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { j -> CoachMessage(j.getBoolean("user"),j.getString("text")) } }
    }.getOrDefault(emptyList())
    fun saveMessages(messages: List<CoachMessage>) {
        val json=JSONArray(messages.filter { it.text.isNotBlank() }.takeLast(20).map {
            JSONObject().put("user",it.user).put("text",it.text.take(1500))
        })
        prefs.edit().putString("messages",json.toString()).apply()
    }
    fun snapshotFor(question: String, conversation: List<CoachMessage>): CoachSnapshot? {
        fun named(text: String): String? {
            val q=text.lowercase().replace('-', ' ').replace("push up","pushup").replace("pull up","pullup").replace("sit up","situp")
            return com.hackathon.calico.Exercise.entries.sortedByDescending { it.name.length }
                .firstOrNull { q.contains(it.name.lowercase().replace('_',' ')) }?.name
        }
        val exercise=named(question) ?: conversation.asReversed().filter { it.user }.firstNotNullOfOrNull { named(it.text) }
        return if(exercise==null) read() else history().lastOrNull { it.exercise==exercise }
    }
    fun context(question: String): String {
        val all=history()
        val current=all.lastOrNull()?.sessionId
        val query=question.lowercase().replace("-", " ").replace("push up","pushup")
        val matching=all.filter { query.contains(it.exercise.lowercase().replace('_',' ')) }
        val selected=(all.filter { it.sessionId==current }.takeLast(24)+matching.takeLast(4)).distinctBy { it.sessionId to it.step }
        if(selected.isEmpty()) return "No recorded workout history yet."
        return "Saved workout history (not live; dates are UTC). Tracking coverage is not form accuracy:\n"+
            selected.joinToString("\n") { s ->
                "${java.time.Instant.ofEpochMilli(s.timeMs)} step ${s.step+1} ${s.exercise}: ${s.count}/${s.target ?: "open"} ${if(s.hold) "seconds" else "reps"}; ${if(s.completed) "finished" else "partial"}; cues=${s.cues}; tracking=${s.trackedFrames}/${s.frames} frames."
            }
    }
    fun overview(sessionId: String? = null): String {
        val all=history(); val selected=all.filter { it.sessionId==(sessionId ?: all.lastOrNull()?.sessionId) }
        if(selected.isEmpty()) return "No workout measurements saved yet."
        return selected.joinToString("\n\n") { s ->
            val coverage=if(s.frames>0) "Tracking coverage: ${(100L*s.trackedFrames/s.frames).coerceIn(0,100)}%." else "Not enough tracking data."
            "${CoachKnowledge.label(s.exercise)} · ${s.count} ${if(s.hold) "seconds" else "reps"}\n"+
                (if(s.cues.isEmpty()) "No detector cues recorded." else s.cues.entries.joinToString { "${it.key} ×${it.value}" })+" $coverage"
        }+"\n\nThese are detector estimates, not a measured form-accuracy score."
    }
    fun clear() { prefs.edit().remove("latest").remove("history").apply() }
    companion object {
        fun encode(s: CoachSnapshot)=JSONObject().put("exercise",s.exercise).put("count",s.count).put("target",s.target)
            .put("hold",s.hold).put("cues",JSONObject(s.cues)).put("minAngle",s.minAngle).put("maxAngle",s.maxAngle)
            .put("timeMs",s.timeMs).put("sessionId",s.sessionId).put("step",s.step).put("frames",s.frames)
            .put("trackedFrames",s.trackedFrames).put("completed",s.completed)
        fun decode(j: JSONObject): CoachSnapshot {
            val cues=j.getJSONObject("cues")
            return CoachSnapshot(j.getString("exercise"),j.getInt("count"),if(j.isNull("target")) null else j.getInt("target"),
                j.getBoolean("hold"),cues.keys().asSequence().associateWith { cues.getInt(it) },
                if(j.isNull("minAngle")) null else j.getDouble("minAngle").toFloat(),
                if(j.isNull("maxAngle")) null else j.getDouble("maxAngle").toFloat(),j.getLong("timeMs"),
                j.optString("sessionId","legacy"),j.optInt("step"),j.optInt("frames"),j.optInt("trackedFrames"),j.optBoolean("completed"))
        }
    }
}
