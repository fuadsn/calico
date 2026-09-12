package com.hackathon.calico.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hackathon.calico.*

@Composable
fun VoiceActionBindings(actions: Map<String,()->Unit>) {
    val activity=LocalContext.current as Activity
    SideEffect { VoiceButtons.register(activity,actions) }
    DisposableEffect(activity,actions.keys) { onDispose { VoiceButtons.unregister(activity,actions.keys) } }
}

@Composable
fun HandsFreeControl() {
    val context=LocalContext.current
    var enabled by remember { mutableStateOf(VoiceAgent.enabled) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enabled=granted; VoiceAgent.setEnabled(granted)
    }
    val status=VoiceAgent.status
    LaunchedEffect(status) { enabled=VoiceAgent.enabled }
    Row(Modifier.fillMaxWidth().padding(vertical=12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Hey Calico",style=MaterialTheme.typography.titleMedium,color=Ink)
            Text(status,style=MaterialTheme.typography.labelSmall,color=Muted)
            Text("Hands-free while Calico is open. Say Calico, then your command.",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        Switch(checked=enabled,onCheckedChange={ value ->
            if(value && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.RECORD_AUDIO)
            else { enabled=value; VoiceAgent.setEnabled(value) }
        },colors=SwitchDefaults.colors(checkedTrackColor=Accent,checkedThumbColor=OnAccent))
    }
}
