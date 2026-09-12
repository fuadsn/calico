package com.hackathon.calico

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hackathon.calico.voice.WakeDetector
import com.k2fsa.sherpa.onnx.KeywordSpotter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WakeDetectorDeviceTest {
    @Test fun detectsWakeAndWorkoutControlsFromOfflineSpeech() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val ready=CountDownLatch(1)
        lateinit var tts: TextToSpeech
        instrumentation.runOnMainSync { tts=TextToSpeech(context) { ready.countDown() } }
        assertTrue(ready.await(20,TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            val local=tts.voices.first { !it.isNetworkConnectionRequired && it.locale.toLanguageTag()=="en-US" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            assertEquals(TextToSpeech.SUCCESS,tts.setVoice(local))
        }
        val kws=KeywordSpotter(context.assets,WakeDetector.config())
        val report=StringBuilder()
        var allPassed=true
        try {
            for((phrase,expected) in listOf("Calico" to "CALICO","Hey Calico" to "CALICO","pause workout" to "PAUSE_WORKOUT","resume workout" to "RESUME_WORKOUT","skip exercise" to "SKIP_EXERCISE",
                "The weather is sunny today" to "", "Call me tomorrow" to "", "I'll leave now" to "", "Keep your knees aligned" to "", "One two three four five" to "", "Workout complete" to "", "Start the next session" to "")) {
                val done=CountDownLatch(1)
                val file=File(context.cacheDir,"wake-test.wav")
                instrumentation.runOnMainSync {
                    tts.setOnUtteranceProgressListener(object: UtteranceProgressListener() {
                        override fun onStart(id: String?) = Unit
                        override fun onDone(id: String?) { done.countDown() }
                        @Deprecated("Android callback") override fun onError(id: String?) { done.countDown() }
                    })
                    assertEquals(TextToSpeech.SUCCESS,tts.synthesizeToFile(phrase,Bundle(),file,"wake-test"))
                }
                assertTrue(done.await(20,TimeUnit.SECONDS))
                val data=file.readBytes()
                val wav=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                var offset=12; var rate=16000; var channels=1; var pcm=ShortArray(0)
                while(offset+8<=data.size) {
                    val id=String(data,offset,4,Charsets.US_ASCII)
                    val size=wav.getInt(offset+4)
                    if(id=="fmt ") { assertEquals(1,wav.getShort(offset+8).toInt()); channels=wav.getShort(offset+10).toInt(); rate=wav.getInt(offset+12); assertEquals(16,wav.getShort(offset+22).toInt()) }
                    if(id=="data") { pcm=ShortArray(minOf(size,data.size-offset-8)/2) { wav.getShort(offset+8+it*2) }; break }
                    offset+=8+size+(size%2)
                }
                assertTrue(pcm.isNotEmpty())
                val samples=FloatArray(pcm.size/channels*16000/rate+16000) { i ->
                    val source=i.toLong()*rate/16000*channels
                    if(source<pcm.size) pcm[source.toInt()]/32768f else 0f
                }
                val stream=kws.createStream()
                val found=mutableSetOf<String>()
                try {
                    for(chunk in samples.asList().chunked(1600)) {
                        stream.acceptWaveform(chunk.toFloatArray(),16000)
                        while(kws.isReady(stream)) {
                            kws.decode(stream)
                            val keyword=kws.getResult(stream).keyword
                            if(keyword.isNotBlank()) { found.add(keyword); kws.reset(stream) }
                        }
                    }
                } finally { stream.release(); file.delete() }
                report.appendLine("$phrase: $found")
                if(expected=="CALICO" && found.isEmpty()) {
                    val diagnostic=com.k2fsa.sherpa.onnx.OnlineRecognizer(context.assets,
                        com.k2fsa.sherpa.onnx.OnlineRecognizerConfig(modelConfig=WakeDetector.config().modelConfig))
                    val diagnosticStream=diagnostic.createStream()
                    try {
                        diagnosticStream.acceptWaveform(samples,16000)
                        while(diagnostic.isReady(diagnosticStream)) diagnostic.decode(diagnosticStream)
                        report.appendLine("Wake pronunciation: ${diagnostic.getResult(diagnosticStream).text}")
                    } finally { diagnosticStream.release(); diagnostic.release() }
                }
                File(context.filesDir,"wake-test-results.txt").writeText(report.toString())
                allPassed=allPassed && if(expected.isEmpty()) found.isEmpty() else expected in found
            }
            assertTrue(report.toString(),allPassed)
        } finally { kws.release(); instrumentation.runOnMainSync { tts.shutdown() } }
    }
}
