package com.hackathon.calico.coach

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** An answer being read aloud. The text grows while the model writes; [done] closes the turn. */
data class CoachSpeech(val turn: Int, val text: String, val done: Boolean)

data class CoachState(val ready: Boolean, val busy: Boolean = false, val downloading: Boolean = false, val progress: Float? = null,
    val status: String = "", val error: String? = null, val messages: List<CoachMessage> = emptyList(),
    val snapshot: CoachSnapshot? = null, val completedAnswer: String? = null, val overview: String = "", val arPlan: String? = null,
    val speech: CoachSpeech? = null)

class CoachViewModel(application: Application) : AndroidViewModel(application) {
    private val model = CoachModel(application)
    private val store = CoachStore(application)
    private val dispatcher = CoachEngine.dispatcher
    private var task: Job? = null
    private var speechTurn = 0
    private val mutable = MutableStateFlow(CoachState(model.present(), snapshot=store.read(),messages=store.messages(),overview=store.overview()))
    val state = mutable.asStateFlow()

    fun refresh() { mutable.update { it.copy(ready=model.present(),snapshot=store.read(),messages=if(it.busy) it.messages else store.messages(),overview=store.overview()) } }
    fun recordAction(question: String, answer: String) {
        mutable.update { it.copy(error=null,completedAnswer=answer,
            messages=(it.messages+CoachMessage(true,question)+CoachMessage(false,answer)).takeLast(20)) }
        store.saveMessages(mutable.value.messages)
    }
    init { observeDownload() }

    /**
     * [target] is the document the user created for the download; the file stays theirs. The
     * download itself runs in [ModelDownloadWorker], so leaving this screen, switching apps or
     * locking the phone does not stop it; this view model only mirrors its progress.
     */
    fun download(target: Uri) {
        if (mutable.value.busy) return
        try { model.retain(target) }   // the picker's grant ends with the activity; keep it for the worker
        catch (e: SecurityException) { mutable.update { it.copy(error="Calico cannot keep access to that location. Choose another folder.") }; return }
        mutable.update { it.copy(busy=true,downloading=true,error=null,progress=0f,status="Starting download…") }
        ModelDownloadWorker.start(getApplication(), target)
    }

    /** Explicit cancel from the user; the partial file is deleted. */
    fun cancelDownload() { ModelDownloadWorker.cancel(getApplication()) }

    private fun observeDownload() {
        val seen = getApplication<Application>().getSharedPreferences("coach_model", 0)
        viewModelScope.launch {
            androidx.work.WorkManager.getInstance(getApplication()).getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.WORK_NAME).collect { infos ->
                val info = infos.lastOrNull() ?: return@collect
                val key = "${info.id}:${info.state}"
                when (info.state) {
                    androidx.work.WorkInfo.State.RUNNING, androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.BLOCKED -> {
                        val fraction = info.progress.getFloat(ModelDownloadWorker.KEY_PROGRESS, mutable.value.progress ?: 0f)
                        val waiting = info.state != androidx.work.WorkInfo.State.RUNNING
                        mutable.update { it.copy(busy=true,downloading=true,error=null,progress=fraction,
                            status=if(waiting && info.runAttemptCount>0) "Waiting for a connection to resume… ${(fraction*100).toInt()}%"
                                else "Downloading model… ${(fraction*100).toInt()}% · continues if you leave or lock your phone") }
                    }
                    else -> {
                        // Finished work stays queryable for a while; report each outcome once.
                        val wasActive = mutable.value.downloading
                        if (!wasActive && seen.getString("download_reported", null) == key) return@collect
                        seen.edit().putString("download_reported", key).apply()
                        mutable.update { when (info.state) {
                            androidx.work.WorkInfo.State.SUCCEEDED -> it.copy(status="Ready. The model stays in ${model.location} even if Calico is reinstalled.")
                            androidx.work.WorkInfo.State.FAILED -> it.copy(error=info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Could not download the model. Please retry.")
                            else -> it.copy(status="Download cancelled. You can start it again any time.")
                        }.copy(busy=false,downloading=false,progress=null,ready=model.present()) }
                    }
                }
            }
        }
    }
    /** References an existing model file in place, then verifies it once. */
    fun link(uri: Uri) = transfer { progress ->
        model.link(uri)
        mutable.update { it.copy(status="Checking the model file…") }
        model.verify()
    }
    private fun transfer(action: suspend (suspend (Float)->Unit)->Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy=true,error=null,progress=0f,status="Preparing offline coach…") }
        task = viewModelScope.launch {
            try {
                withContext(dispatcher) {
                    CoachEngine.release()
                    action { value -> mutable.update { it.copy(progress=value,status="Saving model… ${(value*100).toInt()}%") } }
                }
                mutable.update { it.copy(ready=true,status="Ready. The model stays in ${model.location} even if Calico is reinstalled.") }
            } catch (_: CancellationException) {
                mutable.update { it.copy(status="Setup stopped. You can retry when ready.") }
            } catch (e: Exception) {
                mutable.update { it.copy(error=e.message ?: "Could not prepare the model. Please retry.") }
            } finally {
                mutable.update { it.copy(busy=false,progress=null,ready=model.present()) }
            }
        }
    }

    fun analyzeRoom() = send("Recommend a suitable exercise for my scanned floor.",planRequest=true)
    fun clearRoom() { com.calico.roomscan.ArSceneStore.clear(getApplication()); clear(); mutable.update { it.copy(arPlan=null,status="Saved room scan removed.") } }
    fun applyRoomPlan(): android.content.Intent? {
        val plan=mutable.value.arPlan ?: return null
        if(!com.calico.roomscan.ArSceneStore.queue(getApplication(),plan)) {
            mutable.update { it.copy(arPlan=null,error="This room map is no longer active. Scan the floor again.") }; return null
        }
        return android.content.Intent(getApplication(),com.calico.roomscan.PreviewActivity::class.java)
            .putExtra("exercise",org.json.JSONObject(plan).getString("exercise"))
    }
    fun send(question: String, planRequest: Boolean=false) {
        val text = question.trim()
        val planning=planRequest || (Regex("(?i)\\b(recommend|choose|pick)\\b").containsMatchIn(text) &&
            Regex("(?i)\\b(room|zone|floor|space|scan)\\b").containsMatchIn(text))
        refresh()
        val current = mutable.value
        if (current.busy || !current.ready || text.isEmpty()) return
        // The AR checkpoint predates scene export and model-directed placement.
        if (planning) {
            mutable.update { it.copy(arPlan=null,completedAnswer=null,speech=null,error="Room advice is temporarily unavailable while the previous AR version is restored. You can still scan and view exercise demos.") }
            return
        }
        CoachKnowledge.quickReply(text)?.let { answer ->
            mutable.update { it.copy(error=null,status="Ready",completedAnswer=answer,speech=null,
                messages=(it.messages+CoachMessage(true,text)+CoachMessage(false,answer)).takeLast(20)) }
            store.saveMessages(mutable.value.messages)
            return
        }
        val scene=com.calico.roomscan.ArSceneStore.read(getApplication())
        if(planning && !com.calico.roomscan.ArCoachPlan.hasCandidate(scene,System.currentTimeMillis())) {
            mutable.update { it.copy(arPlan=null,completedAnswer=null,speech=null,error="Scan a stable floor first. Room proposals need a scan from the last five minutes.") }
            return
        }
        val roomRelevant=planning || Regex("(?i)\\b(ar|room|scan|scanned|zone|floor|space|surface|there)\\b").containsMatchIn(text) ||
            current.messages.takeLast(2).any { it.text.contains("zone",ignoreCase=true) }
        val referenced=store.snapshotFor(text,current.messages)
        val context=store.context("$text ${referenced?.exercise?.lowercase()?.replace('_',' ').orEmpty()}")+if(roomRelevant) "\nSaved AR scene JSON: $scene\nA saved scene is not a live camera view. Unmeasured hazards are unknown." else ""
        val prompt = try { if(planning) CoachKnowledge.roomPrompt(scene,text,store.context(text)) else CoachKnowledge.prompt(text,current.messages,referenced,context) }
            catch(e: IllegalArgumentException) { mutable.update { it.copy(error=e.message) }; return }
        val turn=++speechTurn
        mutable.update { it.copy(busy=true,error=null,completedAnswer=null,status="Preparing answer…",
            speech=CoachSpeech(turn,"",false),
            messages=(it.messages + CoachMessage(true,text) + CoachMessage(false,"")).takeLast(20)) }
        task = viewModelScope.launch {
            try {
                withContext(dispatcher) {
                    val runtime=CoachEngine.acquire(model,getApplication()) { step ->
                        mutable.update { it.copy(status=step) }
                    }
                    ensureActive()
                    val started=SystemClock.elapsedRealtime()
                    runtime.start(prompt)
                    val output=ByteArrayOutputStream()
                    var tokens=0
                    var firstToken=0L
                    var lastPublished=0L
                    while(true) {
                        ensureActive()
                        val piece=runtime.next() ?: break
                        if (tokens++==0) firstToken=SystemClock.elapsedRealtime()-started
                        output.write(piece)
                        val answer=CoachReplyPolicy.visible(output.toString("UTF-8"))
                        val now=SystemClock.elapsedRealtime()
                        if(now-lastPublished>=80) {
                            lastPublished=now
                            // The reader starts on the first finished sentence, not on the last token.
                            mutable.update { it.copy(status="Answering on this phone…",speech=CoachSpeech(turn,answer,false),
                                messages=it.messages.dropLast(1)+CoachMessage(false,answer)) }
                        }
                        if(CoachReplyPolicy.finished(output.toString("UTF-8"),CoachReplyPolicy.detailed(text))) break
                    }
                    val duration=SystemClock.elapsedRealtime()-started
                    Log.i("CalicoCoach","tokens=$tokens firstMs=$firstToken totalMs=$duration")
                    mutable.update { it.copy(status=if(tokens>=192) "Response limit reached. Ask a follow-up to continue." else "Answered offline · ${duration/1000f}s") }
                    if (output.size()==0) error("The model returned no answer. Please retry.")
                    ensureActive()
                    val plan=if(planning) com.calico.roomscan.ArCoachPlan.validate(output.toString("UTF-8"),scene,System.currentTimeMillis()) else null
                    if(planning && plan==null) error("No usable AR proposal was returned. Scan a stable floor and try again.")
                    val answer=plan?.let { "${it.getString("reason")} Preview ${it.getString("exercise").lowercase().replace('_',' ')} in ${it.getString("zoneId")}." }
                        ?: CoachReplyPolicy.visible(output.toString("UTF-8"))
                    mutable.update { it.copy(completedAnswer=answer,arPlan=plan?.toString(),speech=CoachSpeech(turn,answer,true),
                        messages=it.messages.dropLast(1)+CoachMessage(false,answer)) }
                }
            } catch (_: CancellationException) {
                mutable.update { it.copy(status="Answer stopped.") }
            } catch(e: Exception) {
                mutable.update { it.copy(error=e.message ?: "Could not answer. Please retry.",status="") }
            } finally {
                // A stopped or failed answer still closes its turn, so the reader releases the microphone.
                mutable.update { it.copy(busy=false,speech=it.speech?.copy(done=true)) }
                store.saveMessages(mutable.value.messages)
            }
        }
    }
    fun stop() { task?.cancel(); CoachEngine.cancel() }
    fun clear() {
        if(mutable.value.busy) return
        mutable.update { it.copy(messages=emptyList(),completedAnswer=null,speech=null,error=null,status="Conversation cleared.") }
        store.saveMessages(emptyList())
    }
    fun forgetWorkout() { store.clear(); clear(); refresh() }
    /** Leaving a coach screen stops generation; the loaded model stays warm for the next question. */
    fun pause() { stop() }
    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
