package com.hackathon.calico

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import com.hackathon.calico.coach.*
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

private val VoiceOrange=Color(0xFFFF8515)
private val VoiceBlue=Color(0xFF2864F0)
private val VoiceInk=Color(0xFF111414)

@Composable
fun VoiceSheet(onDismiss: () -> Unit) {
    MaterialTheme(colorScheme=lightColorScheme()) { VoiceContent(onDismiss) }
}

@Composable
private fun VoiceContent(onDismiss: () -> Unit) {
    val activity=LocalContext.current as ComponentActivity
    val coach=remember(activity) { ViewModelProvider(activity)[CoachViewModel::class.java] }
    val state by coach.state.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    var typing by rememberSaveable { mutableStateOf(false) }
    var pendingSpeech by remember { mutableStateOf(false) }
    var permissionError by remember { mutableStateOf<String?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    val voice=remember(activity) { CoachVoice(activity) { question ->
        draft=""; pendingSpeech=true; coach.send(question)
    } }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) voice.listen()
        else permissionError="Allow microphone access, or choose Type instead."
    }
    fun stop() { pendingSpeech=false; voice.stop(); coach.stop() }
    fun microphone() {
        if(voice.listening || voice.speaking || state.busy) stop()
        else {
            typing=false; seconds=0; permissionError=null
            if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) voice.listen()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    BackHandler { stop(); onDismiss() }
    DisposableEffect(activity) {
        coach.refresh()
        val bars=androidx.core.view.WindowCompat.getInsetsController(activity.window,activity.window.decorView)
        val previousStatus=bars.isAppearanceLightStatusBars
        val previousNavigation=bars.isAppearanceLightNavigationBars
        bars.isAppearanceLightStatusBars=true
        bars.isAppearanceLightNavigationBars=true
        val observer=LifecycleEventObserver { _, event ->
            if(event==Lifecycle.Event.ON_STOP) { pendingSpeech=false; voice.stop(); coach.pause() }
            if(event==Lifecycle.Event.ON_RESUME) coach.refresh()
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer); voice.close(); coach.pause()
            bars.isAppearanceLightStatusBars=previousStatus
            bars.isAppearanceLightNavigationBars=previousNavigation
        }
    }
    LaunchedEffect(voice.listening) { while(voice.listening) { delay(1000); seconds++ } }
    LaunchedEffect(state.busy,state.completedAnswer,pendingSpeech) {
        if(pendingSpeech && !state.busy) { pendingSpeech=false; state.completedAnswer?.let(voice::speak) }
    }
    val question=state.messages.lastOrNull { it.user }?.text
    val answer=state.messages.lastOrNull { !it.user }?.text.orEmpty()
    val active=voice.listening || voice.speaking || state.busy
    Column(Modifier.fillMaxSize().background(Color.White)
        .clickable(remember { MutableInteractionSource() },null) {}.safeDrawingPadding().imePadding()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=28.dp),
            horizontalAlignment=Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Text(if(voice.listening) "I'm listening. What's on your mind?" else question ?: "Hey Coach, how can I improve my next workout?",
                color=Color(0xFFABADAC),fontSize=25.sp,lineHeight=32.sp,fontWeight=FontWeight.Medium,textAlign=TextAlign.Center)
            Spacer(Modifier.height(38.dp))
            if(typing) {
                OutlinedTextField(value=draft,onValueChange={ draft=it.take(500) },placeholder={ Text("Ask your coach…") },
                    modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),maxLines=5,enabled=!state.busy)
            } else {
                val mainText=when {
                    voice.listening -> voice.transcript.ifBlank { "Go ahead, I'm listening" }
                    state.busy -> answer.ifBlank { "Let me think…" }
                    answer.isNotBlank() -> answer
                    else -> "Tap the mic.\nLet's make your next rep better."
                }
                Text(mainText + if(voice.listening) " ▏" else "",color=VoiceInk,fontSize=26.sp,lineHeight=34.sp,
                    fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center)
            }
            Spacer(Modifier.height(38.dp))
            SpeechWave(voice.listening,voice.speaking || state.busy,voice.level)
            Spacer(Modifier.height(18.dp))
            Text(when { voice.listening -> "Listening…"; voice.speaking -> "Speaking…"; state.busy -> "Thinking…"; else -> "Your offline coach" },
                fontSize=12.sp,color=Color(0xFF929594))
            listOfNotNull(permissionError,voice.error,state.error).distinct().forEach {
                Text(it,color=MaterialTheme.colorScheme.error,textAlign=TextAlign.Center,modifier=Modifier.padding(top=14.dp))
            }
            if(!state.ready) TextButton(onClick={ stop(); activity.startActivity(Intent(activity,CoachActivity::class.java)); onDismiss() }) { Text("Set up offline coach") }
            else TextButton(onClick={ stop(); typing=!typing },enabled=!state.busy) {
                Text(if(typing) "Use microphone" else "Type instead",fontSize=12.sp,color=Color(0xFF929594))
            }
        }
        Box(Modifier.fillMaxWidth().height(178.dp)) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(122.dp)
                .background(Color(0xFFF5F5F5),RoundedCornerShape(topStart=36.dp,topEnd=36.dp)))
            Surface(onClick=::microphone,enabled=state.ready,
                modifier=Modifier.align(Alignment.TopCenter).size(88.dp).shadow(8.dp,RoundedCornerShape(30.dp))
                    .border(3.dp,Color(0xFFE1E3E1),RoundedCornerShape(30.dp)),
                color=VoiceInk,shape=RoundedCornerShape(30.dp)) {
                Box(contentAlignment=Alignment.Center) { Icon(if(active) Icons.Outlined.Pause else Icons.Outlined.Mic,
                    if(active) "Stop voice coach" else "Talk to coach",tint=Color.White,modifier=Modifier.size(30.dp)) }
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal=20.dp,vertical=22.dp),verticalAlignment=Alignment.CenterVertically) {
                VoiceAction(Icons.Outlined.Close,"Close voice coach",VoiceOrange) { stop(); onDismiss() }
                Text("%02d:%02d".format(seconds/60,seconds%60),modifier=Modifier.weight(1f),textAlign=TextAlign.Center,
                    color=VoiceInk,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
                VoiceAction(Icons.Outlined.Check,if(voice.listening) "Finish speaking" else "Send question",VoiceBlue,
                    enabled=voice.listening || (state.ready && !state.busy && draft.isNotBlank())) {
                    if(voice.listening) voice.finishListening()
                    else { voice.stop(); pendingSpeech=true; coach.send(draft); draft=""; typing=false }
                }
            }
        }
    }
}

@Composable
private fun VoiceAction(icon: ImageVector,label: String,color: Color,enabled: Boolean=true,onClick: () -> Unit) {
    Surface(onClick=onClick,enabled=enabled,color=color.copy(alpha=if(enabled) 1f else 0.4f),shape=RoundedCornerShape(20.dp),modifier=Modifier.size(58.dp)) {
        Box(contentAlignment=Alignment.Center) { Icon(icon,label,tint=Color.White,modifier=Modifier.size(23.dp)) }
    }
}

@Composable
private fun SpeechWave(listening: Boolean,working: Boolean,level: Float) {
    val phase by rememberInfiniteTransition(label="voice-wave").animateFloat(0f,6.283f,
        infiniteRepeatable(tween(1300,easing=LinearEasing)),label="voice-phase")
    val strength by animateFloatAsState(if(listening) 0.2f+level*0.8f else if(working) 0.65f else 0.12f,label="voice-level")
    Canvas(Modifier.width(180.dp).height(68.dp)) {
        val heights=floatArrayOf(.3f,.55f,.8f,.48f,.65f,1f,.4f,.55f,.8f,.98f,.55f,.32f)
        val gap=size.width/heights.size
        heights.forEachIndexed { i, base ->
            val motion=if(listening || working) .65f+.35f*abs(sin(phase+i*.7f)) else 1f
            val h=size.height*(.15f+base*strength*motion)
            drawRoundRect(if(i==5 || i==7) Color(0xFF777D7B) else Color(0xFFD2D5D3),
                Offset(i*gap+gap*.18f,(size.height-h)/2),Size(gap*.64f,h),CornerRadius(gap/2))
        }
    }
}
