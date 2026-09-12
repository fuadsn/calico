package com.calico.roomscan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArCoachPlanTest {
    private fun scene(width: Double=2.0, stable: Boolean=true)=JSONObject().put("schemaVersion",1).put("revision","scene-a")
        .put("capturedAtMs",1000).put("tracking","TRACKING").put("zones",JSONArray().put(JSONObject()
            .put("id","zone-1").put("selectedFloor",true).put("stable",stable).put("widthM",width).put("depthM",2)
            .put("areaM2",width*2).put("rating","AMPLE"))).toString()
    private fun reply(exercise: String="SQUAT",id: String="zone-1")=JSONObject().put("zoneId",id).put("exercise",exercise).put("reason","Use the measured floor.").toString()
    @Test fun knownStableFloorProducesBoundProposal() {
        val plan=ArCoachPlan.validate(reply(),scene(),2000)!!
        assertEquals("scene-a",plan.getString("revision")); assertEquals("SQUAT",plan.getString("exercise"))
    }
    @Test fun rejectsStaleUnknownAndUnstablePlans() {
        assertNull(ArCoachPlan.validate(reply(),scene(),400000))
        assertNull(ArCoachPlan.validate(reply(id="made-up"),scene(),2000))
        assertNull(ArCoachPlan.validate(reply(),scene(stable=false),2000))
        assertNull(ArCoachPlan.validate(reply("INCLINE_PUSHUP"),scene(),2000))
        assertNull(ArCoachPlan.validate(JSONObject(reply()).put("position",JSONArray()).toString(),scene(),2000))
    }
    @Test fun remeasuresSpaceInsteadOfTrustingTheRatingLabel() {
        assertNull(ArCoachPlan.validate(reply(),scene(width=.7),2000))
        assertNotNull(ArCoachPlan.validate(reply("ARM_RAISE"),scene(width=.7),2000))
    }
}
