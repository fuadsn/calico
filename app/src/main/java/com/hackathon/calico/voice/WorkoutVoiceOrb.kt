package com.hackathon.calico.voice

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.hackathon.calico.*
import com.hackathon.calico.coach.*
import kotlinx.coroutines.delay

/** Wrap-content overlay: only the orb and its caption receive touches. */
@Composable
fun WorkoutVoiceOrb(modifier: Modifier=Modifier,onDismiss: ()->Unit) {
    val activity=LocalContext.current as ComponentActivity
    val dismiss by rememberUpdatedState(onDismiss)
    val coach=remember(activity) { ViewModelProvider(activity)[CoachViewModel::class.java] }
    val state by coach.state.collectAsState()
    var answer by remember { mutableStateOf("") }
    var turn by remember { mutableIntStateOf(0) }
    var spoken by remember { mutableStateOf(false) }
    var waitingForCoach by remember { mutableStateOf(false) }
    // An answer that had already finished before the orb opened must not be replayed.
    val restored=remember { state.speech?.takeIf { it.done }?.turn }
    val voice=remember(activity) { CoachVoice(activity) { question ->
        val result=VoiceAgent.dispatch(activity,question)
        if(result!=null) { coach.recordAction(question,result); answer=result; turn++; waitingForCoach=false }
        else if(VoiceCommands.normalize(question) in setOf("calico","hey calico")) { answer="I'm listening."; turn++ }
        else { waitingForCoach=true; coach.send(question) }
    } }
    val taps=remember { TapGate() }
    BackHandler { dismiss() }
    DisposableEffect(voice) {
        coach.refresh()
        onDispose { voice.close(); coach.pause() }
    }
    LaunchedEffect(Unit) { delay(250); voice.listen() }
    LaunchedEffect(waitingForCoach,state.busy,state.completedAnswer,state.error) {
        if(waitingForCoach && !coach.state.value.busy) {
            val settled=coach.state.value
            answer=settled.error ?: settled.completedAnswer ?: "Set up the offline coach for advice. Workout commands are ready."
            waitingForCoach=false
            // A streamed answer was already read sentence by sentence; only speak what wasn't.
            if(settled.speech==null || settled.error!=null) turn++
        }
    }
    LaunchedEffect(state.speech,voice.speechReady) {
        state.speech?.takeIf { it.turn!=restored }?.let { voice.speakStreaming(it.turn,it.text,it.done); spoken=true }
    }
    LaunchedEffect(turn,voice.speechReady) {
        if(turn>0 && answer.isNotBlank() && voice.speechReady) { voice.speak(answer); spoken=true }
    }
    LaunchedEffect(spoken,voice.speaking,state.busy) {
        if(spoken && !voice.speaking && !state.busy) { delay(500); voice.listen() }
    }
    LaunchedEffect(voice.error) {
        if(voice.error!=null) { delay(5000); dismiss() }
    }
    // Stay available for follow-up commands until dismissed or the activity leaves the foreground.
    Row(modifier.semantics { contentDescription="Workout voice orb" },verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Column(Modifier.widthIn(max=150.dp).background(Card,TileShape).padding(12.dp)) {
            Text("Calico",style=MaterialTheme.typography.labelMedium,color=Accent)
            val caption=when {
                voice.error!=null -> voice.error!!
                voice.finalizing -> "Finishing…"
                voice.listening -> voice.transcript.ifBlank { "Listening…" }
                waitingForCoach || state.busy -> state.speech?.text?.takeIf { it.isNotBlank() } ?: "Thinking…"
                answer.isNotBlank() -> answer
                else -> "Starting microphone…"
            }
            Text(caption,color=Ink,style=MaterialTheme.typography.bodySmall,maxLines=3,overflow=TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Surface(onClick={
                if(taps.accept(android.os.SystemClock.elapsedRealtime()) && !voice.finalizing) {
                    if(voice.listening) voice.finishListening()
                    else { coach.stop(); waitingForCoach=false; voice.listen() }
                }
            },enabled=!voice.finalizing,shape=CircleShape,color=Accent,
                shadowElevation=6.dp,modifier=Modifier.size(56.dp).scale(1f+if(voice.listening) voice.level*0.08f else 0f)) {
                Box(contentAlignment=Alignment.Center) {
                    Icon(if(voice.listening) Icons.Outlined.Check else Icons.Outlined.Mic,
                        if(voice.listening) "Finish command" else "Listen for command",tint=OnAccent)
                }
            }
            IconButton(onClick={ dismiss() },modifier=Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Close,"Close workout voice",tint=Accent,modifier=Modifier.background(Card,CircleShape).padding(4.dp))
            }
        }
    }
}
