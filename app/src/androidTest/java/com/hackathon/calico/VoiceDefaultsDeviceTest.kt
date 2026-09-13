package com.hackathon.calico

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.hackathon.calico.voice.VoiceAgent
import com.hackathon.calico.voice.VoiceButtons
import com.hackathon.calico.coach.CoachStore
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class VoiceDefaultsDeviceTest {
    @Test fun genericStartLaunchesTodayAndStopFromChatControlsIt() {
        val i=InstrumentationRegistry.getInstrumentation()
        val ctx=i.targetContext
        val original=CoachStore(ctx).history()
        val home=i.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val monitor=i.addMonitor(WorkoutActivity::class.java.name,null,false)
        var workout: WorkoutActivity?=null
        var coach: android.app.Activity?=null
        var session: String?=null
        try {
            i.waitForIdleSync()
            i.runOnMainSync { assertNotNull(VoiceButtons.find(home,"voice")); VoiceButtons.find(home,"voice")!!.invoke() }
            i.waitForIdleSync()
            i.runOnMainSync { assertTrue(VoiceAgent.dispatch(home,"Could you please start a workout for me")!!.startsWith("Starting today's workout. First up, ")) }
            workout=monitor.waitForActivityWithTimeout(5000) as? WorkoutActivity
            assertNotNull(workout)
            session=WorkoutActivity::class.java.getDeclaredField("coachSession").apply { isAccessible=true }.get(workout) as String
            i.waitForIdleSync()
            val users=VoiceAgent::class.java.getDeclaredField("voiceUsers").apply { isAccessible=true }
            val releaseDeadline=android.os.SystemClock.elapsedRealtime()+5000
            while(users.getInt(VoiceAgent)!=0 && android.os.SystemClock.elapsedRealtime()<releaseDeadline) android.os.SystemClock.sleep(100)
            assertEquals("Hidden voice page retained the microphone",0,users.getInt(VoiceAgent))
            i.runOnMainSync {
                assertTrue(workout!!.voiceControl("status").contains("Running"))
                assertTrue(VoiceAgent.dispatch(workout!!,"stop the workout")!!.contains("stopped"))
                assertTrue(workout!!.voiceControl("status").contains("Paused"))
                VoiceAgent.dispatch(workout!!,"keep going")
                assertTrue(workout!!.voiceControl("status").contains("Running"))
            }
            coach=i.startActivitySync(Intent(ctx,CoachActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            i.runOnMainSync {
                assertTrue(VoiceAgent.dispatch(coach!!,"stop my workout please")!!.contains("stopped"))
                assertTrue(workout!!.voiceControl("status").contains("Paused"))
            }
        } finally {
            i.removeMonitor(monitor)
            i.runOnMainSync { coach?.finish(); workout?.finish(); home.finish() }
            i.waitForIdleSync()
            val records=(original+CoachStore(ctx).history().filter { it.sessionId!=session })
                .associateBy { it.sessionId to it.step }.values.sortedBy { it.timeMs }
            val edit=ctx.getSharedPreferences("offline_coach",0).edit().putString("history",JSONArray(records.map(CoachStore::encode)).toString())
            if(records.isEmpty()) edit.remove("latest") else edit.putString("latest",CoachStore.encode(records.last()).toString())
            edit.commit()
        }
    }
}
