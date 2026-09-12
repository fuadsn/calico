package com.calico.roomscan

import android.content.Context
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Compact semantic scene snapshot; never exports images, point clouds or reusable world poses. */
object ArSceneStore {
    private var lastWrite=0L
    private var lastStable=false
    private val planes=linkedMapOf<String,Plane>() // active GL thread only
    @Volatile private var liveRevision: String? = null
    @Volatile private var liveExpiresAt=0L
    @Volatile private var pending: String? = null
    fun publish(context: Context, rated: List<RatedPlane>, stable: Boolean) {
        val now=System.currentTimeMillis()
        if(now-lastWrite<3000 && stable==lastStable) return
        lastStable=stable
        lastWrite=now
        val best=rated.firstOrNull { it.isBest } ?: return
        val revision=UUID.randomUUID().toString()
        val floorY=best.plane.centerPose.ty()
        val selected=rated.filter { it.plane.type==Plane.Type.HORIZONTAL_UPWARD_FACING }.sortedByDescending { it.isBest }.take(6)
        planes.clear()
        val zones=JSONArray()
        selected.forEachIndexed { i,r ->
            val id="zone-${i+1}"; planes[id]=r.plane
            zones.put(JSONObject().put("id",id).put("widthM",r.zone.widthM).put("depthM",r.zone.depthM)
                .put("areaM2",r.zone.areaM2).put("rating",r.zone.rating.name)
                .put("relativeHeightM",r.plane.centerPose.ty()-floorY).put("selectedFloor",r.isBest)
                .put("stable",r.isBest && stable))
        }
        val scene=JSONObject().put("schemaVersion",1).put("revision",revision).put("capturedAtMs",now)
            .put("tracking","TRACKING").put("zones",zones)
            .put("limitations","Measured surfaces only; obstacles, grip, furniture strength and body clearance are not verified.")
        liveExpiresAt=now+300_000
        liveRevision=revision
        context.getSharedPreferences("ar_coach",Context.MODE_PRIVATE).edit().putString("scene",scene.toString()).apply()
    }
    fun read(context: Context): String = context.getSharedPreferences("ar_coach",Context.MODE_PRIVATE).getString("scene",null) ?: "No saved room scan."
    fun clear(context: Context) { context.getSharedPreferences("ar_coach",Context.MODE_PRIVATE).edit().clear().apply(); pending=null; liveRevision=null }
    fun invalidate() { liveRevision=null; pending=null }
    fun queue(context: Context, json: String): Boolean {
        val candidate=runCatching { JSONObject(json) }.getOrNull() ?: return false
        if(candidate.optString("revision")!=liveRevision) return false
        candidate.remove("revision")
        val valid=ArCoachPlan.validate(candidate.toString(),read(context),System.currentTimeMillis()) ?: return false
        if(valid.getString("revision")!=liveRevision) return false
        pending=valid.toString()
        return true
    }
    /** Revalidate the actual plane after AR resumes, before the existing placement code runs. */
    fun applyPending(): Boolean {
        val text=pending ?: return false
        if(System.currentTimeMillis()>liveExpiresAt) { pending=null; return false }
        val plan=runCatching { JSONObject(text) }.getOrNull() ?: return false
        if(plan.optString("revision")!=liveRevision) return false
        val plane=planes[plan.optString("zoneId")] ?: return false
        if(plane.trackingState==TrackingState.PAUSED) return false
        pending=null
        if(plane.trackingState!=TrackingState.TRACKING || plane.subsumedBy!=null) return false
        val zone=ArFloor.measure(plane)
        if(!ArCoachPlan.allowed(plan.optString("exercise"),zone.rating.name)) return false
        RoomSession.select(plane)
        return true
    }
}

/** Strict structured-output boundary: the model can select only an observed stable floor and known demo. */
object ArCoachPlan {
    private val standing=setOf("ARM_RAISE","ARM_CIRCLE","OVERHEAD_STRETCH","CROSS_BODY_STRETCH")
    private val floor=standing+setOf("SQUAT","PUSHUP","LUNGE","HIGH_KNEES","MOUNTAIN_CLIMBER","JUMPING_JACK","PLANK","SITUP","LEG_RAISE")
    fun allowed(exercise: String,rating: String)=exercise in floor && (rating=="AMPLE" || (rating=="STANDING_ONLY" && exercise in standing))
    fun hasCandidate(sceneText: String,now: Long): Boolean = runCatching {
        val zones=JSONObject(sceneText).getJSONArray("zones")
        (0 until zones.length()).any { i ->
            val id=zones.getJSONObject(i).getString("id")
            validate(JSONObject().put("zoneId",id).put("exercise","ARM_RAISE").put("reason","Candidate check").toString(),sceneText,now)!=null
        }
    }.getOrDefault(false)
    fun validate(reply: String,sceneText: String,now: Long): JSONObject? = runCatching {
        val scene=JSONObject(sceneText)
        require(scene.getInt("schemaVersion")==1 && scene.getString("tracking")=="TRACKING")
        require(now-scene.getLong("capturedAtMs") in 0..300_000)
        val json=JSONObject(reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        require(json.keys().asSequence().toSet()==setOf("zoneId","exercise","reason"))
        val zones=scene.getJSONArray("zones")
        val zone=(0 until zones.length()).map { zones.getJSONObject(it) }.first { it.getString("id")==json.getString("zoneId") }
        require(zone.getBoolean("stable") && zone.getBoolean("selectedFloor"))
        val width=zone.getDouble("widthM").toFloat(); val depth=zone.getDouble("depthM").toFloat(); val area=zone.getDouble("areaM2").toFloat()
        require(listOf(width,depth,area).all { it.isFinite() && it>0 })
        val measured=ZoneSolver.classify(width,depth,area)
        require(allowed(json.getString("exercise"),measured.rating.name))
        require(json.getString("reason").length in 1..400)
        JSONObject().put("revision",scene.getString("revision")).put("zoneId",json.getString("zoneId"))
            .put("exercise",json.getString("exercise")).put("reason",json.getString("reason"))
    }.getOrNull()
}
