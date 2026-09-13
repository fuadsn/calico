package com.hackathon.calico.coach

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Main-thread owner of microphone and local speech playback. Never falls back to cloud ASR. */
class CoachVoice(private val context: Context, private val onQuestion: (String) -> Unit) : AutoCloseable {
    var starting by mutableStateOf(false); private set
    var listening by mutableStateOf(false); private set
    var finalizing by mutableStateOf(false); private set
    var level by mutableStateOf(0f); private set
    var speaking by mutableStateOf(false); private set
    var transcript by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null); private set
    var speechReady by mutableStateOf(false); private set
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var accepting = false
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var utterance = 0
    private var recognitionEpoch = 0
    private var startupRetries = 0
    private var restartAfter = 0L
    val recognitionAvailable get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    init {
        com.hackathon.calico.voice.VoiceAgent.voiceOpened()
        tts = TextToSpeech(context) { result -> main.post {
            if (!closed) {
                val engine = tts
                val voice = engine?.voices?.filter { !it.isNetworkConnectionRequired && it.locale.language == "en" &&
                    !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                    ?.sortedByDescending { it.locale == Locale.getDefault() }?.firstOrNull()
                speechReady = result == TextToSpeech.SUCCESS && voice != null && engine?.setVoice(voice) == TextToSpeech.SUCCESS
                if (speechReady) engine?.setSpeechRate(1.0f)
                else error = "Install an English offline voice in Android text-to-speech settings. Answers still appear here."
            }
        } }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { main.post { if (!closed && id == utterance.toString()) speaking=false } }
            @Deprecated("Android callback")
            override fun onError(id: String?) { main.post { if (!closed && id == utterance.toString()) {
                speaking=false; error="Could not speak this answer. You can read it below."
            } } }
        })
    }
    fun listen() {
        if (closed || starting || listening || finalizing) return
        if (!recognitionAvailable) { error="Offline speech recognition is unavailable on this phone. Type your question below."; return }
        utterance++; tts?.stop(); speaking=false
        startupRetries=0
        error=null; transcript=""
        scheduleListening(250)
    }
    private fun scheduleListening(delay: Long) {
        starting=true
        val epoch=++recognitionEpoch
        main.postDelayed({
            if(!closed && starting && epoch==recognitionEpoch) startRecognition(epoch)
        },maxOf(delay,restartAfter-SystemClock.elapsedRealtime()))
    }
    private fun startRecognition(epoch: Int) {
        accepting=true
        try {
            // A completed session can be reused. Replacing it after every silence
            // races the service's asynchronous teardown and can exhaust its capacity.
            val speech=recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer=it }
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if(!accepting || closed || epoch!=recognitionEpoch) return
                    starting=false; listening=true
                    android.util.Log.i("CoachVoice","ready epoch=$epoch")
                }
                override fun onBeginningOfSpeech() { if(accepting && epoch==recognitionEpoch) startupRetries=0 }
                override fun onRmsChanged(rmsdB: Float) { if(accepting && epoch==recognitionEpoch) level=((rmsdB+2f)/12f).coerceIn(0f,1f) }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { android.util.Log.i("CoachVoice","endOfSpeech epoch=$epoch/$recognitionEpoch"); if(accepting && epoch==recognitionEpoch) markFinalizing() }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onPartialResults(results: Bundle?) {
                    android.util.Log.i("CoachVoice","partial epoch=$epoch/$recognitionEpoch accepting=$accepting text=${results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()}")
                    if(accepting && epoch==recognitionEpoch) transcript=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().take(500)
                }
                override fun onResults(results: Bundle?) {
                    android.util.Log.i("CoachVoice","results epoch=$epoch/$recognitionEpoch accepting=$accepting closed=$closed text=${results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)}")
                    if(!accepting || closed || epoch!=recognitionEpoch) return
                    accepting=false; starting=false; listening=false; finalizing=false; level=0f
                    startupRetries=0
                    transcript=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim().take(500)
                    if(transcript.isNotBlank()) onQuestion(transcript) else error="I didn't catch that. Tap the mic and try again."
                }
                override fun onError(code: Int) {
                    android.util.Log.i("CoachVoice","error code=$code epoch=$epoch/$recognitionEpoch accepting=$accepting closed=$closed retries=$startupRetries")
                    if(!accepting || closed || epoch!=recognitionEpoch) return
                    accepting=false; starting=false; listening=false; finalizing=false; level=0f
                    val silence=code==SpeechRecognizer.ERROR_NO_MATCH || code==SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    val temporary=code==SpeechRecognizer.ERROR_RECOGNIZER_BUSY || code==SpeechRecognizer.ERROR_AUDIO || code==SpeechRecognizer.ERROR_SERVER_DISCONNECTED
                    if(silence) {
                        error=null
                        scheduleListening(600)
                        return
                    }
                    releaseRecognizer()
                    if(temporary && startupRetries < 2) {
                        startupRetries++
                        error=null
                        scheduleListening(1500L*startupRetries)
                        return
                    }
                    error=when(code) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow microphone access to talk to your coach."
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Install English offline speech recognition in Android speech settings, or type below."
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Tap the mic and try again."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Android speech recognition is busy. Wait a moment, then tap the mic again."
                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Android's offline speech service disconnected. Tap the mic to reconnect."
                        SpeechRecognizer.ERROR_AUDIO -> "Android couldn't record audio. Close other microphone apps, then try again."
                        else -> "Speech input stopped ($code). Try the mic again or type below."
                    }
                }
            })
            speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"en-US")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true))
            main.postDelayed({ if(!closed && starting && accepting && epoch==recognitionEpoch) {
                stop(); error="Android speech recognition didn't become ready. Tap the mic to try again."
            } },10000)
        } catch (_: Exception) { stop(); error="Could not start the microphone. Try again or type below." }
    }
    fun speak(answer: String) {
        if(closed) return
        // Invalidate pending retries before playback, including retries that have
        // not opened the microphone yet. Keep a successfully completed session.
        recognitionEpoch++
        if(accepting || starting || finalizing) releaseRecognizer()
        accepting=false; starting=false; listening=false; finalizing=false; level=0f
        if(!speechReady) { error="Offline speech output isn't ready. Your answer is shown below."; return }
        utterance++
        speaking=tts?.speak(answer.replace(Regex("[*#`]"),""),TextToSpeech.QUEUE_FLUSH,null,utterance.toString()) == TextToSpeech.SUCCESS
        if(!speaking) error="Could not speak this answer. You can read it below."
    }
    private fun markFinalizing() {
        if(finalizing) return
        finalizing=true
        val epoch=recognitionEpoch
        main.postDelayed({ if(!closed && finalizing && epoch==recognitionEpoch) {
            stop(); error="Speech took too long to finish. Tap the mic to try again."
        } },8000)
    }
    fun finishListening() { if(listening && accepting && !finalizing) { markFinalizing(); recognizer?.stopListening() } }
    private fun releaseRecognizer() {
        recognizer?.destroy()
        if(recognizer!=null) restartAfter=SystemClock.elapsedRealtime()+1000
        recognizer=null
    }
    fun stop() { level=0f; recognitionEpoch++; accepting=false; starting=false; listening=false; finalizing=false; speaking=false; utterance++; releaseRecognizer(); tts?.stop() }
    override fun close() {
        if(closed) return
        stop(); closed=true; tts?.shutdown(); tts=null
        com.hackathon.calico.voice.VoiceAgent.voiceClosed()
    }
}
