package com.hackathon.calico.coach

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CoachState(val ready: Boolean, val busy: Boolean = false, val progress: Float? = null,
    val status: String = "", val error: String? = null, val messages: List<CoachMessage> = emptyList(),
    val snapshot: CoachSnapshot? = null, val completedAnswer: String? = null, val overview: String = "", val arPlan: String? = null)

class CoachViewModel(application: Application) : AndroidViewModel(application) {
    private val model = CoachModel(application)
    private val store = CoachStore(application)
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    @Volatile private var engine: NativeCoach? = null
    private var loadedPath: String? = null
    private var task: Job? = null
    private val mutable = MutableStateFlow(CoachState(model.present(), snapshot=store.read(),messages=store.messages(),overview=store.overview()))
    val state = mutable.asStateFlow()

    fun refresh() { mutable.update { it.copy(ready=model.present(),snapshot=store.read(),messages=if(it.busy) it.messages else store.messages(),overview=store.overview()) } }
    fun recordAction(question: String, answer: String) {
        mutable.update { it.copy(error=null,completedAnswer=answer,
            messages=(it.messages+CoachMessage(true,question)+CoachMessage(false,answer)).takeLast(20)) }
        store.saveMessages(mutable.value.messages)
    }
    fun download() = transfer { progress -> model.download(progress) }
    fun import(uri: Uri) = transfer { progress -> model.import(uri,progress) }
    private fun transfer(action: suspend ((Float)->Unit)->Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy=true,error=null,progress=0f,status="Preparing offline coach…") }
        task = viewModelScope.launch {
            try {
                withContext(dispatcher) {
                    engine?.close(); engine=null
                    action { value -> mutable.update { it.copy(progress=value,status="Saving model… ${(value*100).toInt()}%") } }
                }
                mutable.update { it.copy(ready=true,status="Ready. Your conversations run on this phone.") }
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
            mutable.update { it.copy(arPlan=null,completedAnswer=null,error="Room advice is temporarily unavailable while the previous AR version is restored. You can still scan and view exercise demos.") }
            return
        }
        CoachKnowledge.quickReply(text)?.let { answer ->
            mutable.update { it.copy(error=null,status="Ready",completedAnswer=answer,
                messages=(it.messages+CoachMessage(true,text)+CoachMessage(false,answer)).takeLast(20)) }
            store.saveMessages(mutable.value.messages)
            return
        }
        val scene=com.calico.roomscan.ArSceneStore.read(getApplication())
        if(planning && !com.calico.roomscan.ArCoachPlan.hasCandidate(scene,System.currentTimeMillis())) {
            mutable.update { it.copy(arPlan=null,completedAnswer=null,error="Scan a stable floor first. Room proposals need a scan from the last five minutes.") }
            return
        }
        val roomRelevant=planning || Regex("(?i)\\b(ar|room|scan|scanned|zone|floor|space|surface|there)\\b").containsMatchIn(text) ||
            current.messages.takeLast(2).any { it.text.contains("zone",ignoreCase=true) }
        val referenced=store.snapshotFor(text,current.messages)
        val context=store.context("$text ${referenced?.exercise?.lowercase()?.replace('_',' ').orEmpty()}")+if(roomRelevant) "\nSaved AR scene JSON: $scene\nA saved scene is not a live camera view. Unmeasured hazards are unknown." else ""
        val prompt = try { if(planning) CoachKnowledge.roomPrompt(scene,text,store.context(text)) else CoachKnowledge.prompt(text,current.messages,referenced,context) }
            catch(e: IllegalArgumentException) { mutable.update { it.copy(error=e.message) }; return }
        mutable.update { it.copy(busy=true,error=null,completedAnswer=null,status="Preparing answer…",
            messages=(it.messages + CoachMessage(true,text) + CoachMessage(false,"")).takeLast(20)) }
        task = viewModelScope.launch {
            try {
                withContext(dispatcher) {
                    if(engine!=null && loadedPath!=model.file.absolutePath) { engine?.close(); engine=null }
                    if (engine==null) {
                        mutable.update { it.copy(status="Checking the local model…") }
                        model.verify()
                        ensureActive()
                        mutable.update { it.copy(status="Loading offline coach…") }
                        engine=NativeCoach().also { it.load(model.file.absolutePath) }
                        loadedPath=model.file.absolutePath
                    }
                    ensureActive()
                    val runtime=engine!!
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
                            mutable.update { it.copy(status="Answering on this phone…",
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
                    mutable.update { it.copy(completedAnswer=answer,arPlan=plan?.toString(),
                        messages=it.messages.dropLast(1)+CoachMessage(false,answer)) }
                }
            } catch (_: CancellationException) {
                mutable.update { it.copy(status="Answer stopped.") }
            } catch(e: Exception) {
                mutable.update { it.copy(error=e.message ?: "Could not answer. Please retry.",status="") }
            } finally { mutable.update { it.copy(busy=false) }; store.saveMessages(mutable.value.messages) }
        }
    }
    fun stop() { task?.cancel(); engine?.cancel() }
    fun clear() {
        if(mutable.value.busy) return
        mutable.update { it.copy(messages=emptyList(),completedAnswer=null,error=null,status="Conversation cleared.") }
        store.saveMessages(emptyList())
    }
    fun forgetWorkout() { store.clear(); clear(); refresh() }
    fun pause() {
        stop()
        viewModelScope.launch(dispatcher) { engine?.close(); engine=null }
    }
    override fun onCleared() {
        stop()
        CoroutineScope(dispatcher).launch { engine?.close(); engine=null; dispatcher.close() }
        super.onCleared()
    }
}
