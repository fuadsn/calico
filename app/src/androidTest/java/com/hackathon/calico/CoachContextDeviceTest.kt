package com.hackathon.calico

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.hackathon.calico.coach.*
import com.calico.roomscan.ArCoachPlan
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class CoachContextDeviceTest {
    @Test fun savedHistoryAndSceneJsonReachTheLocalModel() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val isolated=object: ContextWrapper(target) {
            override fun getSharedPreferences(name: String,mode: Int)=super.getSharedPreferences("context_test_$name",mode)
        }
        val store=CoachStore(isolated)
        try {
            store.clear(); store.saveMessages(emptyList())
            store.save(CoachSnapshot("SQUAT",8,10,false,mapOf("Go deeper" to 2),85f,170f,1000,"test-session",0,100,80,true))
            store.save(CoachSnapshot("PUSHUP",5,10,false,mapOf("Go lower" to 1),88f,170f,2000,"test-session",1,100,90))
            store.save(CoachSnapshot("PUSHUP",6,10,false,mapOf("Go lower" to 1),88f,170f,3000,"test-session",1,110,100,true))
            assertEquals(2,store.history().size)
            assertEquals("SQUAT",store.snapshotFor("How were my squats?",emptyList())?.exercise)
            assertEquals("SQUAT",store.snapshotFor("How many did I count?",listOf(CoachMessage(true,"Tell me about my squats")))?.exercise)
            assertTrue(store.overview().contains("Tracking coverage"))
            assertFalse(store.overview().contains("100% accuracy"))
            store.saveMessages(listOf(CoachMessage(true,"Tell me about my pushups"),CoachMessage(false,"You counted six.")))
            assertEquals(2,CoachStore(isolated).messages().size)
            val now=System.currentTimeMillis()
            val scene=JSONObject().put("schemaVersion",1).put("revision","synthetic-scene")
                .put("capturedAtMs",now).put("tracking","TRACKING").put("zones",JSONArray().put(JSONObject()
                    .put("id","zone-1").put("stable",true).put("selectedFloor",true).put("widthM",2).put("depthM",2)
                    .put("areaM2",4).put("rating","AMPLE").put("relativeHeightM",0))).toString()
            NativeCoach().use { engine ->
                engine.load(CoachModel(target).file.absolutePath)
                fun answer(prompt: String): String {
                    engine.start(prompt)
                    val out=ByteArrayOutputStream()
                    while(true) out.write(engine.next() ?: break)
                    return out.toString("UTF-8")
                }
                val question="How many pushups did I do in my last workout?"
                val text=answer(CoachKnowledge.prompt(question,store.messages(),store.read(),store.context(question)))
                assertTrue("Missing saved count: $text",text.contains("6") || text.contains("six",true))
                val proposal=answer(CoachKnowledge.roomPrompt(scene))
                assertNotNull("Invalid proposal: $proposal",ArCoachPlan.validate(proposal,scene,System.currentTimeMillis()))
                File(target.filesDir,"coach-context-test.json").writeText(JSONObject().put("workoutAnswer",text).put("sceneProposal",proposal).toString())
            }
        } finally { isolated.getSharedPreferences("offline_coach",Context.MODE_PRIVATE).edit().clear().commit() }
    }
}
