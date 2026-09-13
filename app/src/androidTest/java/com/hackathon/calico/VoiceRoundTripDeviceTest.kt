package com.hackathon.calico

import android.Manifest
import android.app.Application
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.hackathon.calico.coach.CoachModel
import com.hackathon.calico.coach.CoachViewModel
import com.hackathon.calico.coach.CoachVoice
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * The Calico voice turn past the microphone: a finished transcript goes to the local Qwen model
 * and the answer is read back. [CoachVoiceTest] covers the capture side; the acoustic half needs
 * a person to speak, so this drives the same calls VoiceSheet makes once a transcript exists.
 */
class VoiceRoundTripDeviceTest {
    @Test fun transcriptIsAnsweredOnTheNpuAndSpokenBack() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        i.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        assertTrue("Link the Qwen model on this device first", CoachModel(context).present())

        lateinit var coach: CoachViewModel
        lateinit var voice: CoachVoice
        var asked = ""
        i.runOnMainSync {
            coach = CoachViewModel(context.applicationContext as Application)
            voice = CoachVoice(context) { question -> asked = question; coach.send(question) }
        }
        try {
            var ready = false
            repeat(100) {
                i.runOnMainSync { ready = voice.speechReady }
                if (!ready) SystemClock.sleep(100)
            }
            assertTrue("Offline TTS not ready", ready)

            val question = "How do I do a pushup?"
            val before = coach.state.value.speech?.turn ?: 0
            i.runOnMainSync { asked = question; coach.send(question) }
            var answer: String? = null
            repeat(1800) {   // model load plus generation
                val state = coach.state.value
                if (!state.busy && state.speech?.turn != before && state.speech?.done == true) { answer = state.completedAnswer; return@repeat }
                SystemClock.sleep(100)
            }
            val state = coach.state.value
            assertNull("Coach reported an error: ${state.error}", state.error)
            assertTrue("One completed answer per question", state.speech?.done == true && state.speech?.turn != before)
            val text = answer ?: state.completedAnswer
            assertNotNull("No answer came back from the NPU", text)
            assertTrue("Answer too short to be useful: $text", text!!.length > 30)
            // Both halves of the turn are on screen: the question and the reply.
            assertEquals(question, state.messages.lastOrNull { it.user }?.text)
            assertEquals(text, state.messages.lastOrNull { !it.user }?.text)
            assertEquals(question, asked)

            i.runOnMainSync { voice.speak(text) }
            var played = true
            repeat(600) {
                i.runOnMainSync { played = voice.speaking }
                if (played) SystemClock.sleep(100)
            }
            assertFalse("The answer never finished playing", played)

            File(context.filesDir, "voice-roundtrip-result.txt")
                .writeText("Q: $question\nA: $text\nSpoken to completion offline.")
        } finally {
            i.runOnMainSync { voice.close(); coach.stop() }
        }
    }
}
