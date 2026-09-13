package com.hackathon.calico.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.*
import java.util.concurrent.Executors

/** One worker owns the microphone, native stream and model. No audio is saved. */
class WakeDetector(private val context: Context) {
    companion object {
        @Volatile var peakSinceStart = 0f; private set
        fun config() = KeywordSpotterConfig(
            modelConfig=OnlineModelConfig(
                transducer=OnlineTransducerModelConfig(
                    encoder="wake/encoder.int8.onnx", decoder="wake/decoder.onnx", joiner="wake/joiner.int8.onnx"),
                tokens="wake/tokens.txt", modelType="zipformer2", numThreads=1),
            keywordsFile="wake/keywords.txt", keywordsThreshold=0.35f, maxActivePaths=8)
    }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var epoch = 0
    private var model: KeywordSpotter? = null
    fun stop() { epoch++ }
    fun start(onKeyword: (String) -> Unit, onError: (String) -> Unit, onReady: () -> Unit = {}) {
        val run = ++epoch
        peakSinceStart=0f
        worker.execute {
            if(run != epoch) return@execute
            var audio: AudioRecord? = null
            var stream: OnlineStream? = null
            try {
                val kws = model ?: KeywordSpotter(context.assets, config()).also { model=it }
                if(run != epoch) return@execute
                stream=kws.createStream()
                val bytes=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                require(bytes>0) { "Microphone format unavailable" }
                audio=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,maxOf(bytes*2,6400))
                check(audio.state==AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
                audio.startRecording()
                check(audio.recordingState==AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
                main.post { if(run==epoch) onReady() }
                android.util.Log.i("CalicoWake","listening")
                val buffer=ShortArray(1600)
                var detected=""
                var gain=1f
                while(run==epoch && detected.isEmpty()) {
                    val n=audio.read(buffer,0,buffer.size)
                    check(n>=0) { "Microphone disconnected" }
                    if(n==0) continue
                    peakSinceStart=maxOf(peakSinceStart,(0 until n).maxOf { kotlin.math.abs(buffer[it].toInt()) }/32768f)
                    // Automatic gain: a quiet or distant voice is lifted toward a healthy level before spotting.
                    // Below the noise floor nothing is boosted, or amplified room noise fires the wake word.
                    val peak=(0 until n).maxOf { kotlin.math.abs(buffer[it].toInt()) }/32768f
                    val wanted=if(peak<0.03f) 1f else (0.6f/peak).coerceIn(1f,6f)
                    gain=gain*0.7f+wanted*0.3f
                    val samples=FloatArray(n) { (buffer[it]/32768f*gain).coerceIn(-1f,1f) }
                    stream.acceptWaveform(samples,16000)
                    while(kws.isReady(stream) && run==epoch) {
                        kws.decode(stream)
                        val result=kws.getResult(stream).keyword
                        if(result.isNotBlank()) { detected=result; android.util.Log.i("CalicoWake","heard $result peak=${"%.2f".format(peakSinceStart)} gain=${"%.1f".format(gain)}"); break }
                    }
                }
                android.util.Log.i("CalicoWake","run ended heard='$detected' peak=${"%.2f".format(peakSinceStart)}")
                // Release the mic before Android's command recognizer starts.
                audio.stop(); audio.release(); audio=null
                if(detected.isNotEmpty()) main.post { if(run==epoch) onKeyword(detected) }
            } catch(e: Exception) {
                main.post { if(run==epoch) onError(e.message ?: "Wake listening stopped") }
            } finally {
                runCatching { audio?.stop() }; audio?.release(); stream?.release()
            }
        }
    }
}
