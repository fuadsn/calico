package com.hackathon.calico

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.hackathon.calico.voice.VoiceAgent
import com.hackathon.calico.voice.VoiceAgentActivity
import com.hackathon.calico.coach.CoachStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkoutOrbDeviceTest {
    @Test fun wakeKeepsWorkoutResumedAndEditsStayOnScreen() {
        val i=InstrumentationRegistry.getInstrumentation()
        val context=i.targetContext
        val originalHistory=CoachStore(context).history()
        i.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.CAMERA)
        i.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)
        val monitor=i.addMonitor(VoiceAgentActivity::class.java.name,null,false)
        val activity=i.startActivitySync(Intent(context,WorkoutActivity::class.java)
            .putExtra("routine","SQUAT:300,PLANK:30").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WorkoutActivity
        val sessions=mutableSetOf<String>()
        val sessionField=WorkoutActivity::class.java.getDeclaredField("coachSession").apply { isAccessible=true }
        fun keepSession() { sessions.add(sessionField.get(activity) as String) }
        keepSession()
        try {
            i.waitForIdleSync()
            i.runOnMainSync {
                assertEquals(Lifecycle.State.RESUMED,activity.lifecycle.currentState)
                assertTrue(activity.voiceControl("status").contains("Running"))
                VoiceAgent.acceptKeyword("CALICO")
            }
            fun hasOrb(node: AccessibilityNodeInfo?): Boolean {
                if(node==null) return false
                if(node.contentDescription?.toString()=="Workout voice orb") return true
                return (0 until node.childCount).any { hasOrb(node.getChild(it)) }
            }
            val deadline=SystemClock.elapsedRealtime()+5000
            var shown=false
            while(!shown && SystemClock.elapsedRealtime()<deadline) {
                shown=hasOrb(i.uiAutomation.rootInActiveWindow)
                if(!shown) SystemClock.sleep(100)
            }
            assertTrue("Side orb was not visible",shown)
            fun listening(node: AccessibilityNodeInfo?): Boolean {
                if(node==null) return false
                if(node.text?.toString()=="Listening…") return true
                return (0 until node.childCount).any { listening(node.getChild(it)) }
            }
            val framesField=WorkoutActivity::class.java.getDeclaredField("coachFrames").apply { isAccessible=true }
            val frames=framesField.get(activity) as java.util.concurrent.atomic.AtomicInteger
            val readyDeadline=SystemClock.elapsedRealtime()+8000
            var micReady=false
            while((!micReady || frames.get()==0) && SystemClock.elapsedRealtime()<readyDeadline) {
                micReady=micReady || listening(i.uiAutomation.rootInActiveWindow)
                SystemClock.sleep(100)
            }
            fun captions(node: AccessibilityNodeInfo?): String = if(node==null) "" else
                node.text?.toString().orEmpty()+" "+(0 until node.childCount).joinToString(" ") { captions(node.getChild(it)) }
            assertTrue("Orb did not start speech recognition: ${captions(i.uiAutomation.rootInActiveWindow)}",micReady)
            assertTrue("Pose camera stopped while orb was open",frames.get()>0)
            i.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.cacheDir,"workout-orb-test.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
            i.runOnMainSync {
                assertEquals(0,monitor.hits)
                assertEquals(Lifecycle.State.RESUMED,activity.lifecycle.currentState)
                assertTrue(activity.voiceControl("status").contains("Running"))
                VoiceAgent.dispatch(activity,"pause")
                assertTrue(activity.voiceControl("status").contains("Paused"))
                assertTrue(VoiceAgent.dispatch(activity,"set reps to twenty")!!.contains("20"))
                assertEquals(20,CoachStore(context).read()!!.target)
                VoiceAgent.dispatch(activity,"change exercise to plank")
                assertTrue(activity.voiceControl("status").contains("Paused"))
                VoiceAgent.dispatch(activity,"set hold time to forty five seconds")
                assertEquals(45,CoachStore(context).read()!!.target)
                assertTrue(CoachStore(context).history().count { it.sessionId in sessions }>=2)
                VoiceAgent.dispatch(activity,"restart exercise")
                assertTrue(activity.voiceControl("status").contains("Running"))
                VoiceAgent.dispatch(activity,"switch to pushups")
                VoiceAgent.dispatch(activity,"set reps to fifteen")
                assertEquals("PUSHUP",CoachStore(context).read()!!.exercise)
                assertEquals(15,CoachStore(context).read()!!.target)
                VoiceAgent.dispatch(activity,"can you please stop the workout")
                assertFalse(activity.isFinishing)
                assertTrue(activity.voiceControl("status").contains("Paused"))
                VoiceAgent.dispatch(activity,"start a workout")
                assertTrue(activity.voiceControl("status").contains("Running"))
                VoiceAgent.dispatch(activity,"restart workout"); keepSession()
                assertEquals(Lifecycle.State.RESUMED,activity.lifecycle.currentState)
                assertEquals(0,monitor.hits)
                activity.closeVoiceOrb()
            }
        } finally {
            i.removeMonitor(monitor)
            i.runOnMainSync { keepSession(); activity.closeVoiceOrb(); activity.finish() }
            i.waitForIdleSync()
            val deadline=SystemClock.elapsedRealtime()+5000
            while(activity.lifecycle.currentState!=Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100)
            // Remove only the synthetic sessions created by this test, preserving user history/chat.
            val prefs=context.getSharedPreferences("offline_coach",0)
            val history=(originalHistory+CoachStore(context).history().filter { it.sessionId !in sessions })
                .associateBy { it.sessionId to it.step }.values.sortedBy { it.timeMs }
            val edit=prefs.edit().putString("history",JSONArray(history.map(CoachStore::encode)).toString())
            val latest=prefs.getString("latest",null)?.let(::JSONObject)
            if(latest?.optString("sessionId") in sessions) {
                if(history.isEmpty()) edit.remove("latest") else edit.putString("latest",CoachStore.encode(history.last()).toString())
            }
            edit.commit()
        }
    }
}
