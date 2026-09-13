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
                // A muted phone is reported as a notice on the same field; it is not a speech failure.
                assertTrue("Speech failed: ${voice.error}",voice.error==null || voice.error!!.startsWith("Turn up the media volume"))
                voice.listen()
                // Starting is intentionally distinct from listening: only Android's
                // onReadyForSpeech callback is allowed to light the listening UI.
                assertFalse(voice.listening)
            }
            var listening=false
            repeat(50) {
                i.runOnMainSync { listening=voice.listening }
                if(!listening) SystemClock.sleep(100)
            }
            i.runOnMainSync {
                assertTrue("Recognizer never became ready: ${voice.error}",listening)
                voice.stop()
                assertFalse(voice.listening)
                assertFalse(voice.speaking)
            }
            File(context.filesDir,"voice-test-result.txt").writeText("$details\nOffline TTS completed; recognition start/stop passed. Acoustic transcription needs a spoken user test.")
        } finally { i.runOnMainSync { voice.close() } }
    }
}
