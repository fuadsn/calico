package com.hackathon.calico

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.hackathon.calico.voice.VoiceAgent
import com.hackathon.calico.voice.VoiceAgentActivity
import org.junit.Assert.*
import org.junit.Test

class VoiceAgentDeviceTest {
    @Test fun spokenWakeReachesTheLiveMicrophone() {
        val i=InstrumentationRegistry.getInstrumentation()
        val context=i.targetContext
        i.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)
        val home=i.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val monitor=i.addMonitor(VoiceAgentActivity::class.java.name,null,false)
        val ready=java.util.concurrent.CountDownLatch(1)
        lateinit var tts: android.speech.tts.TextToSpeech
        var previouslyEnabled=false
        var agent: android.app.Activity?=null
        i.runOnMainSync {
            previouslyEnabled=VoiceAgent.enabled
            VoiceAgent.setEnabled(true)
            tts=android.speech.tts.TextToSpeech(context) { ready.countDown() }
        }
        try {
            assertTrue(ready.await(15,java.util.concurrent.TimeUnit.SECONDS))
            var listening=false
            val deadline=SystemClock.elapsedRealtime()+8000
            while(!listening && SystemClock.elapsedRealtime()<deadline) {
                i.runOnMainSync { listening=VoiceAgent.status=="Say Calico · listening on this phone" }
                if(!listening) SystemClock.sleep(100)
            }
            assertTrue("The actual microphone did not start",listening)
            SystemClock.sleep(1000)
            i.runOnMainSync {
                val local=tts.voices.first { !it.isNetworkConnectionRequired && it.locale.toLanguageTag()=="en-US" && !it.features.orEmpty().contains(android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                tts.setVoice(local)
                assertEquals(android.speech.tts.TextToSpeech.SUCCESS,tts.speak("Hey Calico",android.speech.tts.TextToSpeech.QUEUE_FLUSH,null,"acoustic-wake-test"))
            }
            agent=monitor.waitForActivityWithTimeout(10000)
            assertNotNull("Phone-speaker speech did not trigger the live microphone wake path; peak=${com.hackathon.calico.voice.WakeDetector.peakSinceStart}",agent)
        } finally {
            i.removeMonitor(monitor)
            i.runOnMainSync { tts.stop(); tts.shutdown(); agent?.finish(); home.finish(); VoiceAgent.setEnabled(previouslyEnabled) }
        }
    }

    @Test fun wakeOpensListeningScreenAndRoutesToOriginalScreen() {
        val i=InstrumentationRegistry.getInstrumentation()
        val context=i.targetContext
        i.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)
        val home=i.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        i.waitForIdleSync()
        val monitor=i.addMonitor(VoiceAgentActivity::class.java.name,null,false)
        var agent: android.app.Activity?=null
        try {
            i.runOnMainSync { VoiceAgent.acceptKeyword("CALICO") }
            agent=monitor.waitForActivityWithTimeout(5000)
            assertNotNull("Wake did not open the voice screen",agent)
            fun contains(node: AccessibilityNodeInfo?, text: String): Boolean {
                if(node==null) return false
                if(node.text?.toString()?.contains(text,ignoreCase=true)==true) return true
                for(n in 0 until node.childCount) if(contains(node.getChild(n),text)) return true
                return false
            }
            val deadline=SystemClock.elapsedRealtime()+5000
            var listening=false
            while(!listening && SystemClock.elapsedRealtime()<deadline) {
                listening=contains(i.uiAutomation.rootInActiveWindow,"I'm listening")
                if(!listening) SystemClock.sleep(100)
            }
            assertTrue("Voice screen did not start listening automatically",listening)
            i.runOnMainSync {
                assertSame(home,VoiceAgent.target(agent!!))
                assertEquals("Done.",VoiceAgent.dispatch(agent!!,"tap Journey"))
            }
            i.waitForIdleSync()
            assertTrue(agent!!.isFinishing)
        } finally {
            i.removeMonitor(monitor)
            i.runOnMainSync { agent?.finish(); home.finish() }
        }
    }
}
