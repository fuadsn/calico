package com.hackathon.calico

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.hackathon.calico.coach.*
import java.text.DateFormat
import java.util.Date

class CoachActivity : ComponentActivity() {
    private lateinit var coach: CoachViewModel
    private fun sendMessage(text: String) {
        val result=com.hackathon.calico.voice.VoiceAgent.dispatch(this,text)
        if(result!=null) coach.recordAction(text,result) else coach.send(text)
    }
    // Both keep the model in the user's storage: the download goes into a document they
    // create, and an existing file is referenced in place rather than copied.
    private val locator = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { coach.link(it) } }
    private val downloader = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { coach.download(it) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(0),navigationBarStyle=SystemBarStyle.dark(0))
        coach=ViewModelProvider(this)[CoachViewModel::class.java]
        // Opening the coach almost always ends in a question; start the load now.
        com.hackathon.calico.coach.CoachEngine.prewarm(this)
        setContent { CalicoTheme {
            val state by coach.state.collectAsState()
            var draft by rememberSaveable { mutableStateOf(intent.getStringExtra("question") ?: "") }
            var setup by rememberSaveable { mutableStateOf(false) }
            var about by remember { mutableStateOf(false) }
            var overview by remember { mutableStateOf(false) }
            com.hackathon.calico.voice.VoiceActionBindings(buildMap {
                put("Back",::finish)
                put("About & licenses") { about=true }
                put("Workout overview") { overview=true }
                if(!state.busy) {
                    put("Clear",coach::clear)
                    put("Model setup") { setup=true }
                    put("Hide setup") { setup=false }
                    put("Forget",coach::forgetWorkout)
                    if(setup) put("Forget saved room",coach::clearRoom)
                    if(!state.ready || setup) {
                        put("Download offline coach") { downloader.launch(CoachModel.NAME) }
                        put("Locate model file") { locator.launch(arrayOf("*/*")) }
                    }
                    if(state.ready) {
                        put("Explain my cues") { coach.send("Explain my latest recorded form cues and what I should check next.") }
                        if(draft.isNotBlank()) put("Send") { sendMessage(draft); draft="" }
                    }
                } else put("Stop",coach::stop)
                if(overview) {
                    put("Close") { overview=false }
                    put("Ask coach") { overview=false; coach.send("Review my whole last workout and the repeated cues. What should I focus on next?") }
                }
                if(about) put("Done") { about=false }
            })
            if(overview) AlertDialog(onDismissRequest={ overview=false },title={ Text("Workout overview") },
                text={ Text(state.overview,modifier=Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())) },
                confirmButton={ TextButton(onClick={ overview=false; coach.send("Review my whole last workout and the repeated cues. What should I focus on next?") }) { Text("Ask coach") } },
                dismissButton={ TextButton(onClick={ overview=false }) { Text("Close") } })
            if (about) AlertDialog(onDismissRequest={ about=false },
                title={ Text("About offline coach") },
                text={ Text(remember { "Exercise guidance: ${CoachKnowledge.SOURCE_URL}\n\n" + listOf("offline-coach.txt","sherpa-onnx.txt","onnxruntime.txt").joinToString("\n\n") { name -> assets.open("licenses/$name").bufferedReader().use { it.readText() } } },
                    modifier=Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())) },
                confirmButton={ TextButton(onClick={ about=false }) { Text("Done") } })
            val list=rememberLazyListState()
            LaunchedEffect(state.messages.lastOrNull()?.text) {
                if(state.messages.isNotEmpty()) list.animateScrollToItem(state.messages.lastIndex)
            }
            Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding().imePadding().padding(horizontal=20.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick=::finish) { Text("Back",color=Ink) }
                    Column(Modifier.weight(1f)) {
                        Text("Offline coach",style=MaterialTheme.typography.headlineSmall,color=Ink)
                        Text("Private · On this phone",style=MaterialTheme.typography.labelSmall,color=Muted)
                    }
                    TextButton(onClick=coach::clear,enabled=!state.busy) { Text("Clear") }
                }
                Row {
                    TextButton(onClick={ setup=!setup },enabled=!state.busy) { Text(if(setup) "Hide setup" else "Model setup") }
                    TextButton(onClick={ about=true }) { Text("About & licenses") }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(onClick={ overview=true }) { Text("Workout overview") }
                    TextButton(onClick=coach::analyzeRoom,enabled=state.ready && !state.busy) { Text("Analyze room") }
                }
                if(state.arPlan!=null) Button(onClick={ coach.applyRoomPlan()?.let(::startActivity) },enabled=!state.busy) { Text("Preview proposal in AR") }
                if(setup) TextButton(onClick=coach::clearRoom,enabled=!state.busy) { Text("Forget saved room") }
                if(!state.ready || setup) {
                    Column(Modifier.fillMaxWidth().background(Charcoal,CardShape).padding(20.dp)) {
                        Text("Meet your offline coach",style=MaterialTheme.typography.titleLarge)
                        Text("Ask exercise questions and understand your saved form cues. Download the 2.38 GB model once into a folder you choose, or locate a copy already on this phone. The file stays in your storage, so reinstalling Calico never deletes it. Keep this screen open during setup.",Modifier.padding(vertical=12.dp))
                        Button(onClick={ downloader.launch(CoachModel.NAME) },enabled=!state.busy,modifier=Modifier.fillMaxWidth(),
                            colors=ButtonDefaults.buttonColors(containerColor=Accent),shape=Pill) { Text("Download offline coach") }
                        OutlinedButton(onClick={ locator.launch(arrayOf("*/*")) },enabled=!state.busy,modifier=Modifier.fillMaxWidth(),shape=Pill) { Text("Locate model file") }
                        Text("Qwen3-4B Instruct · Apache 2.0 · runs on this phone's NPU",style=MaterialTheme.typography.labelSmall,color=Muted)
                    }
                }
                state.snapshot?.let { s ->
                    Column(Modifier.fillMaxWidth().padding(top=10.dp).background(Slate,TileShape).padding(14.dp)) {
                        Text("Latest exercise · ${CoachKnowledge.label(s.exercise)}",style=MaterialTheme.typography.titleMedium)
                        Text("${s.count} ${if(s.hold) "seconds held" else "reps"} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(s.timeMs))}",style=MaterialTheme.typography.labelSmall)
                        Text(s.cues.keys.joinToString().ifEmpty { "No form cues recorded" },style=MaterialTheme.typography.bodyMedium)
                        Row {
                            TextButton(onClick={ coach.send("Explain my latest recorded form cues and what I should check next.") },enabled=state.ready && !state.busy) { Text("Explain my cues") }
                            TextButton(onClick=coach::forgetWorkout,enabled=!state.busy) { Text("Forget") }
                        }
                    }
                }
                state.progress?.let { LinearProgressIndicator(progress={ it },modifier=Modifier.fillMaxWidth().padding(top=10.dp)) }
                if(state.error!=null) Text(state.error!!,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(vertical=8.dp))
                if(state.messages.isEmpty() && state.ready) {
                    Text("What would you like to work on?",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(top=20.dp,bottom=8.dp))
                    listOf("Why might my reps not count?","How do I do a pushup?","What does Go deeper mean for squats?").forEach { question ->
                        TextButton(onClick={ coach.send(question) },enabled=!state.busy) { Text(question,color=Ink) }
                    }
                }
                LazyColumn(state=list,modifier=Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=12.dp)) {
                    itemsIndexed(state.messages) { _, message ->
                        Column(Modifier.fillMaxWidth().background(if(message.user) Charcoal else Card,TileShape).padding(16.dp)) {
                            Text(if(message.user) "You" else "Calico",style=MaterialTheme.typography.labelSmall,color=Muted)
                            Text(message.text.ifEmpty { "Preparing your answer…" },style=MaterialTheme.typography.bodyLarge,color=Ink,modifier=Modifier.padding(top=6.dp))
                        }
                    }
                }
                if(state.status.isNotBlank()) Text(state.status,style=MaterialTheme.typography.labelSmall,color=Muted)
                Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(value=draft,onValueChange={ draft=it.take(500) },modifier=Modifier.weight(1f),
                        enabled=state.ready && !state.busy,maxLines=3,placeholder={ Text("Ask your coach") },shape=TileShape)
                    Spacer(Modifier.width(8.dp))
                    Button(onClick={ if(state.busy) coach.stop() else { sendMessage(draft); draft="" } },
                        enabled=state.busy || (state.ready && draft.isNotBlank()),shape=Pill,
                        colors=ButtonDefaults.buttonColors(containerColor=Accent)) { Text(if(state.busy) "Stop" else "Send") }
                }
                Text("AI answers can be wrong. Uses saved cues, not a live camera view.",style=MaterialTheme.typography.labelSmall,color=Muted,modifier=Modifier.padding(vertical=10.dp))
            }
        } }
    }
    override fun onResume() { super.onResume(); if(::coach.isInitialized) coach.refresh() }
    override fun onStop() { if(!isChangingConfigurations && ::coach.isInitialized) coach.pause(); super.onStop() }
}
