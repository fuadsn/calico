package com.hackathon.calico.coach

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var streamTurn = -1
    private var stoppedTurn = -1
    private var consumed = ""
    private var queued = 0
    private var finished = 0
    private var streamOpen = false
    private var recognitionEpoch = 0
    private var startupRetries = 0
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
            override fun onDone(id: String?) { main.post { retire(id) } }
            @Deprecated("Android callback")
            override fun onError(id: String?) { main.post {
                if (mine(id)) error="Could not speak this answer. You can read it below."
                retire(id)
            } }
        })
    }
    fun listen() {
        if (closed || listening) return
        stop()
        if (!recognitionAvailable) { error="Offline speech recognition is unavailable on this phone. Type your question below."; return }
        error=null; transcript=""; accepting=true; listening=true
        val epoch=++recognitionEpoch
        try {
            recognizer?.destroy()
            recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { speech ->
                speech.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() { startupRetries=0 }
                    override fun onRmsChanged(rmsdB: Float) { if(accepting && epoch==recognitionEpoch) level=((rmsdB+2f)/12f).coerceIn(0f,1f) }
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() { if(accepting && epoch==recognitionEpoch) markFinalizing() }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onPartialResults(results: Bundle?) {
                        if(accepting && epoch==recognitionEpoch) transcript=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().take(500)
                    }
                    override fun onResults(results: Bundle?) {
                        if(!accepting || closed || epoch!=recognitionEpoch) return
                        accepting=false; listening=false; finalizing=false
                        startupRetries=0
                        transcript=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim().take(500)
                        if(transcript.isNotBlank()) onQuestion(transcript) else error="I didn't catch that. Tap the mic and try again."
                    }
                    override fun onError(code: Int) {
                        if(!accepting || closed || epoch!=recognitionEpoch) return
                        accepting=false; listening=false; finalizing=false
                        val silence=code==SpeechRecognizer.ERROR_NO_MATCH || code==SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        val temporary=code==SpeechRecognizer.ERROR_RECOGNIZER_BUSY || code==SpeechRecognizer.ERROR_AUDIO || code==SpeechRecognizer.ERROR_SERVER_DISCONNECTED
                        if(silence || (temporary && startupRetries++ < 2)) {
                            error=null
                            main.postDelayed({ if(!closed && epoch==recognitionEpoch && !speaking) listen() },if(silence) 600 else 1000)
                            return
                        }
                        error=when(code) {
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow microphone access to talk to your coach."
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Install English offline speech recognition in Android speech settings, or type below."
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Tap the mic and try again."
                            else -> "Speech input stopped ($code). Try the mic again or type below."
                        }
                    }
                })
                speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"en-US")
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true))
            }
        } catch (_: Exception) { accepting=false; listening=false; finalizing=false; error="Could not start the microphone. Try again or type below." }
    }
    fun speak(answer: String) {
        if(closed) return
        if(!speechReady) { error="Offline speech output isn't ready. Your answer is shown below."; return }
        openTurn()
        val queuedOk=enqueue(answer)
        streamOpen=false
        updateSpeaking()
        if(!queuedOk) error="Could not speak this answer. You can read it below."
    }

    /**
     * Speaks an answer while the model is still writing it. [text] is the whole answer so
     * far, so repeated calls are harmless; each one speaks only the sentences that have
     * completed since the last. [turn] identifies the answer: a new value starts over.
     * Pass [done] when generation ends, to speak whatever tail is left.
     */
    fun speakStreaming(turn: Int, text: String, done: Boolean) {
        if(closed || !speechReady || turn==stoppedTurn) return
        if(turn!=streamTurn) { openTurn(); streamTurn=turn }
        // A late edit can shorten the answer; never re-speak what was already said.
        val fresh=if(text.length>consumed.length && text.startsWith(consumed)) text.substring(consumed.length) else ""
        val cut=if(done) fresh.length else CoachReplyPolicy.speakableCut(fresh)
        if(cut>0) { consumed+=fresh.substring(0,cut); enqueue(fresh.substring(0,cut)) }
        if(done) streamOpen=false
        updateSpeaking()
    }

    private fun openTurn() {
        accepting=false; recognizer?.cancel(); listening=false
        utterance++; streamTurn=-1; consumed=""; queued=0; finished=0; streamOpen=true
    }
    private fun enqueue(text: String): Boolean {
        val words=text.replace(Regex("[*#`]"),"").trim()
        if(words.isEmpty()) return true
        val mode=if(queued==0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val ok=tts?.speak(words,mode,null,"$utterance#$queued") == TextToSpeech.SUCCESS
        if(ok) queued++
        return ok
    }
    private fun mine(id: String?) = !closed && id?.substringBefore('#')==utterance.toString()
    private fun retire(id: String?) {
        if(!mine(id)) return
        finished=maxOf(finished,(id!!.substringAfter('#').toIntOrNull() ?: 0)+1)
        updateSpeaking()
    }
    /** Still speaking while more sentences are queued, or while the answer is still arriving. */
    private fun updateSpeaking() { speaking = queued>0 && (streamOpen || finished<queued) }
    private fun markFinalizing() {
        if(finalizing) return
        finalizing=true
        val epoch=recognitionEpoch
        main.postDelayed({ if(!closed && finalizing && epoch==recognitionEpoch) {
            stop(); error="Speech took too long to finish. Tap the mic to try again."
        } },8000)
    }
    fun finishListening() { if(listening && accepting && !finalizing) { markFinalizing(); recognizer?.stopListening() } }
    fun stop() {
        level=0f; recognitionEpoch++; accepting=false; listening=false; finalizing=false; speaking=false
        utterance++; stoppedTurn=streamTurn; streamTurn=-1; consumed=""; queued=0; finished=0; streamOpen=false
        recognizer?.cancel(); tts?.stop()
    }
    override fun close() {
        if(closed) return
        stop(); closed=true; recognizer?.destroy(); recognizer=null; tts?.shutdown(); tts=null
        com.hackathon.calico.voice.VoiceAgent.voiceClosed()
    }
}
