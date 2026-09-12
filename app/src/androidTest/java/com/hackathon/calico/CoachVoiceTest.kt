package com.hackathon.calico

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import androidx.test.platform.app.InstrumentationRegistry
import com.hackathon.calico.coach.CoachVoice
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File

class CoachVoiceTest {
    @Test fun offlineSpeechServicesAndStopWork() {
        val i=InstrumentationRegistry.getInstrumentation()
        val context=i.targetContext
        i.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)
        lateinit var voice: CoachVoice
        val support=CountDownLatch(1)
        var details=""
        i.runOnMainSync {
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            voice=CoachVoice(context) { }
            assertTrue("On-device recognition missing",voice.recognitionAvailable)
            val recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer.checkRecognitionSupport(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"en-US")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),context.mainExecutor,
                object: RecognitionSupportCallback {
                    override fun onSupportResult(result: RecognitionSupport) {
                        details="installed=${result.installedOnDeviceLanguages}; pending=${result.pendingOnDeviceLanguages}; supported=${result.supportedOnDeviceLanguages}"
                        recognizer.destroy(); support.countDown()
                    }
                    override fun onError(error: Int) { details="supportError=$error"; recognizer.destroy(); support.countDown() }
                })
        }
        try {
            assertTrue(support.await(20,TimeUnit.SECONDS))
            var ready=false
            repeat(100) {
                i.runOnMainSync { ready=voice.speechReady }
                if(!ready) SystemClock.sleep(100)
            }
            assertTrue("Offline TTS not ready",ready)
            i.runOnMainSync { voice.speak("Keep your movement controlled. Focus on one good rep at a time."); assertTrue(voice.speaking) }
            var finished=false
            repeat(150) {
                i.runOnMainSync { finished=!voice.speaking }
                if(!finished) SystemClock.sleep(100)
            }
            assertTrue("TTS did not finish",finished)
            i.runOnMainSync {
                assertNull(voice.error)
                voice.listen()
                assertTrue(voice.listening)
                voice.stop()
                assertFalse(voice.listening)
                assertFalse(voice.speaking)
            }
            File(context.filesDir,"voice-test-result.txt").writeText("$details\nOffline TTS completed; recognition start/stop passed. Acoustic transcription needs a spoken user test.")
        } finally { i.runOnMainSync { voice.close() } }
    }

    /** Streaming speech must start on the first finished sentence, not on the last token. */
    @Test fun streamedAnswersSpeakBeforeGenerationEnds() {
        val i=InstrumentationRegistry.getInstrumentation()
        val context=i.targetContext
        lateinit var voice: CoachVoice
        i.runOnMainSync { voice=CoachVoice(context) { } }
        try {
            var ready=false
            repeat(100) {
                i.runOnMainSync { ready=voice.speechReady }
                if(!ready) SystemClock.sleep(100)
            }
            assertTrue("Offline TTS not ready",ready)

            // A half-written sentence is not spoken yet.
            i.runOnMainSync { voice.speakStreaming(1,"Lower your hips until",false); assertFalse(voice.speaking) }
            // The first completed sentence starts the audio while the answer is still growing.
            i.runOnMainSync { voice.speakStreaming(1,"Lower your hips until your thighs are parallel.",false); assertTrue(voice.speaking) }
            i.runOnMainSync { voice.speakStreaming(1,"Lower your hips until your thighs are parallel. Keep your chest up. Then",false) }
            var idle=false
            // The turn stays open, so a drained queue must not look finished.
            repeat(20) { i.runOnMainSync { idle=!voice.speaking }; if(!idle) SystemClock.sleep(100) }
            assertFalse("Speech ended while the answer was still arriving",idle)

            i.runOnMainSync { voice.speakStreaming(1,"Lower your hips until your thighs are parallel. Keep your chest up. Then drive up.",true) }
            var finished=false
            repeat(200) { i.runOnMainSync { finished=!voice.speaking }; if(!finished) SystemClock.sleep(100) }
            assertTrue("Streamed speech did not finish",finished)
            i.runOnMainSync { assertNull(voice.error) }

            // Stopping a turn must not let a repeated update restart it.
            i.runOnMainSync { voice.speakStreaming(2,"Hold the plank for twenty seconds.",false); assertTrue(voice.speaking) }
            i.runOnMainSync { voice.stop(); assertFalse(voice.speaking) }
            i.runOnMainSync { voice.speakStreaming(2,"Hold the plank for twenty seconds.",true); assertFalse(voice.speaking) }
        } finally { i.runOnMainSync { voice.close() } }
    }
}
