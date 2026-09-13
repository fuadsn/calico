package com.hackathon.calico.voice

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.hackathon.calico.*
import java.lang.ref.WeakReference

class CalicoApplication : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() { super.onCreate(); VoiceAgent.initialize(this); registerActivityLifecycleCallbacks(this) }
    /**
     * The loaded model is the largest thing in the process, so give it back under pressure.
     * UI_HIDDEN is excluded on purpose: every trip to the background would unload it.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if(level in setOf(TRIM_MEMORY_RUNNING_LOW,TRIM_MEMORY_RUNNING_CRITICAL,TRIM_MEMORY_COMPLETE))
            com.hackathon.calico.coach.CoachEngine.requestRelease()
    }
    override fun onActivityResumed(activity: Activity) = VoiceAgent.resumed(activity)
    override fun onActivityPaused(activity: Activity) = VoiceAgent.paused(activity)
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) { VoiceButtons.clear(activity) }
}

object VoiceAgent {
    private lateinit var app: Application
    private lateinit var wake: WakeDetector
    private val main=Handler(Looper.getMainLooper())
    private var foreground=WeakReference<Activity>(null)
    private var origin=WeakReference<Activity>(null)
    private var activeWorkout=WeakReference<WorkoutActivity>(null)
    private var voiceUsers=0
    private var restart=0
    private var nativeLabels=emptySet<String>()
    private var pendingNative: Pair<WeakReference<Activity>,String>?=null
    var status by androidx.compose.runtime.mutableStateOf("Hands-free off"); private set
    val enabled get()=app.getSharedPreferences("voice_agent",0).getBoolean("enabled",false)
    fun initialize(application: Application) { app=application; wake=WakeDetector(app) }
    fun setEnabled(value: Boolean) {
        app.getSharedPreferences("voice_agent",0).edit().putBoolean("enabled",value).apply()
        refresh()
    }
    fun resumed(activity: Activity) {
        if(activity is WorkoutActivity) activeWorkout=WeakReference(activity)
        foreground=WeakReference(activity); refresh()
        pendingNative?.takeIf { it.first.get()===activity }?.let { request ->
            pendingNative=null
            main.postDelayed({
                val matches=nativeButtons(activity).filter { it.first==request.second }
                if(foreground.get()===activity && matches.size==1) matches.single().second.performClick()
                else Toast.makeText(activity,"That button is no longer available.",Toast.LENGTH_SHORT).show()
            },300)
        }
    }
    fun paused(activity: Activity) { if(foreground.get()===activity) { foreground.clear(); refresh() } }
    fun voiceOpened() { voiceUsers++; refresh() }
    fun voiceClosed() { voiceUsers=(voiceUsers-1).coerceAtLeast(0); refresh() }
    private var listening=false
    private var pending: Runnable?=null
    /**
     * Lifecycle events arrive in bursts (a sheet closing, its activity finishing, the home screen
     * resuming), so the decision is debounced and the listener is only stopped or started when
     * the wanted state actually changes. Every needless restart dropped about a second of audio.
     */
    private fun refresh() {
        pending?.let(main::removeCallbacks)
        val r=Runnable { pending=null; apply() }
        pending=r; main.postDelayed(r,400)
    }
    private fun apply() {
        val front=foreground.get()
        val want=when {
            !enabled -> { status="Hands-free off"; false }
            voiceUsers>0 || front==null || front is VoiceAgentActivity -> { status="Wake listening paused"; false }
            app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED -> { status="Microphone permission needed"; false }
            else -> true
        }
        if(!want) { if(listening) { wake.stop(); listening=false }; return }
        if(listening) return
        // Load the model while the user is still talking, so the first question answers at once.
        com.hackathon.calico.coach.CoachEngine.prewarm(app)
        status="Starting Calico wake listening…"
        listening=true
        val ticket=++restart
        wake.start({ keyword -> listening=false; acceptKeyword(keyword) },
            { error -> listening=false; status="Hands-free stopped: $error. Turn it off and on to retry." },
            { if(ticket==restart) status="Say Calico · listening on this phone" })
    }
    fun acceptKeyword(keyword: String) {
        val activity=foreground.get() ?: return
        if(activity is VoiceAgentActivity || voiceUsers>0) return
        if(keyword=="CALICO") {
            wake.stop()
            if(activity is WorkoutActivity) { activity.openVoiceOrb(); return }
            nativeLabels=nativeButtons(activity).map { it.first }.toSet()
            origin=WeakReference(activity)
            activity.startActivity(Intent(activity,VoiceAgentActivity::class.java))
        } else {
            val answer=dispatch(activity,keyword.lowercase().replace('_',' '))
            if(answer!=null) Toast.makeText(activity,answer,Toast.LENGTH_SHORT).show()
            refresh()
        }
    }
    private fun nativeButtons(activity: Activity): List<Pair<String,View>> {
        val matches=mutableListOf<Pair<String,View>>()
        fun visit(view: View) {
            if(!view.isShown || !view.isEnabled) return
            val label=(view as? TextView)?.text?.toString() ?: view.contentDescription?.toString()
            if(view.isClickable && !label.isNullOrBlank()) matches.add(VoiceCommands.normalize(label) to view)
            if(view is ViewGroup) for(i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(activity.window.decorView)
        return matches
    }
    fun target(activity: Activity): Activity = if(activity is VoiceAgentActivity) origin.get()?.takeUnless { it.isDestroyed || it.isFinishing } ?: activity else activity
    fun dispatch(activity: Activity, text: String): String? {
        val host=target(activity)
        val command=VoiceCommands.parse(text) ?: run {
            // A spoken visible control may be used directly; "tap" is optional. This is the
            // generic bridge for controls registered by each Compose screen.
            VoiceButtons.find(activity,text)?.let { it(); return "Done." }
            VoiceButtons.find(host,text)?.let { it(); if(activity is VoiceAgentActivity) activity.finish(); return "Done." }
            val label=VoiceCommands.similarLabel(text,if(activity is VoiceAgentActivity) nativeLabels else nativeButtons(host).map { it.first }.toSet())
            if(label!=null) {
                if(activity is VoiceAgentActivity) { pendingNative=WeakReference(host) to label; activity.finish(); return "Returning to the screen to press $label." }
                nativeButtons(host).singleOrNull { it.first==label }?.second?.performClick()?.let { return "Done." }
            }
            return null
        }
        return execute(activity,command)
    }
    /** Without the model to interpret it, a request that sounds like an action gets an honest miss. */
    fun unmatchedActionReply(text: String): String? =
        if(Regex("^(open|start|begin|stop|pause|resume|restart|set|change|switch|skip|tap|click|press|select|choose|delete|clear|close|exit)\\b").containsMatchIn(VoiceCommands.normalize(text)))
            "I couldn't match that to an available action. Nothing was changed. Say voice commands for examples." else null
    private fun workoutFor(host: Activity)=(host as? WorkoutActivity) ?: activeWorkout.get()?.takeUnless { it.isDestroyed || it.isFinishing }
    /** What the intent model needs to resolve "this exercise" or "five more": the open workout, if any. */
    fun intentContext(activity: Activity): String =
        workoutFor(target(activity))?.let { "A workout is open. ${it.voiceControl("status")}" } ?: "No workout is open."
    /** Runs one command, from the parser or from the model, and returns what to say about it. */
    private var announced: String? = null
    /**
     * A screen change kills the voice sheet's speech mid-word, so the workout screen reads its
     * own start line from the intent. The sheet asks here before speaking the same text and
     * stays quiet if the next screen has taken it.
     */
    fun claimAnnouncement(text: String): Boolean = (announced==text).also { if(it) announced=null }
    fun execute(activity: Activity, command: VoiceCommand): String? {
        if(activity.isDestroyed || activity.isFinishing) return "That screen has closed. Nothing was changed."
        val host=target(activity)
        val workout=workoutFor(host)
        fun launch(intent: Intent) { activity.startActivity(intent); if(activity is VoiceAgentActivity) activity.finish() }
        fun goal(step: Step)="${step.target} ${if(step.exercise.holdSec>0) "seconds" else "reps"}"
        /** A routine is named and its first exercise read out, so the user knows what to get into position for. */
        fun startRoutine(steps: List<Step>, name: String): String {
            val announcement="Starting $name."+(steps.firstOrNull()?.let { " First up, ${it.exercise.label}, ${goal(it)}." } ?: "")
            announced=announcement
            launch(Intent(activity,WorkoutActivity::class.java).putExtra("routine",steps.encode()).putExtra("announce",announcement))
            return announcement
        }
        when(command.action) {
            "pause", "resume", "skip", "end", "restart", "restart_session", "record", "record_stop", "status" -> {
                if(workout!=null) {
                    if(command.action in setOf("resume", "restart", "restart_session") && host!==workout)
                        launch(Intent(activity,WorkoutActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    return workout.voiceControl(command.action)
                }
                if(command.action=="resume") return startRoutine(Progress(activity).todayPlan,"today's workout")
                return if(command.action in setOf("pause","end")) "No workout is running. You're already stopped." else "Start a workout to use that control."
            }
            "target" -> return workout?.voiceTarget(command.target!!,command.label) ?: "Open a workout before changing its target."
            "adjust" -> return workout?.voiceAdjustTarget(command.target!!,command.label) ?: "Open a workout before changing its target."
            "change_exercise" -> return workout?.voiceExercise(command.exercise!!,command.target) ?: "Open a workout before changing its exercise."
            "home", "journey" -> { launch(Intent(activity,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab",if(command.action=="home") 0 else 1)); return "Opening ${command.action}." }
            "exercises" -> { launch(Intent(activity,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab",2)); return "Opening exercises." }
            "scan" -> { launch(Intent(activity,com.calico.roomscan.ScanActivity::class.java)); return "Opening scanner." }
            "coach" -> { launch(Intent(activity,CoachActivity::class.java)); return "Opening coach." }
            "voice" -> return "I'm listening. What would you like to do?"
            "today", "exercise", "level" -> {
                if(workout!=null && host===workout) {
                    if(command.action=="today") return workout.voiceControl("resume")
                    if(command.action=="exercise") return workout.voiceExercise(command.exercise!!,command.target,start=true)
                    return "End this workout before starting a different saved session."
                }
                // The line names what was asked for, so "jumping jacks" is heard in full.
                return when(command.action) {
                    "today" -> startRoutine(Progress(activity).todayPlan,"today's workout")
                    "exercise" -> {
                        val exercise=command.exercise!!
                        val step=Step(exercise,command.target ?: if(exercise.holdSec>0) exercise.holdSec else 10)
                        val announcement="Starting ${exercise.label}, ${goal(step)}."
                        announced=announcement
                        launch(Intent(activity,WorkoutActivity::class.java).putExtra("routine",listOf(step).encode()).putExtra("announce",announcement))
                        announcement
                    }
                    else -> {
                        val title=VoiceCommands.sessionFor(command.label)   // titles, or body parts like "arm workout"
                        val level=LEVELS.firstOrNull { it.title==title }
                        val split=SPLITS.firstOrNull { it.title==title }
                        val steps=level?.steps ?: split?.steps ?: return "I couldn't find that session. Say start a workout for today's plan, or name an exercise."
                        startRoutine(steps,level?.title ?: split!!.title)
                    }
                }
            }
            "demo" -> {
                val exercise=command.exercise ?: workout?.voiceDemoExercise()
                    ?: return "Name the exercise you want to see, or open a workout first."
                launch(Intent(activity,com.calico.roomscan.PreviewActivity::class.java)
                    .putExtra("exercise",exercise.name).putExtra("expand_demo",command.expand))
                return if(command.expand) "Opening the full exercise demo." else "Opening the exercise in AR."
            }
            "disable" -> { setEnabled(false); return "Hands-free listening is off." }
            "exit" -> {
                launch(Intent(activity,MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("tab",0))
                return "Returning home."
            }
            "back" -> { if(host is ComponentActivity) host.onBackPressedDispatcher.onBackPressed() else host.finish(); if(activity is VoiceAgentActivity) activity.finish(); return "Going back." }
            "close" -> { if(workout!=null) workout.closeVoiceOrb() else VoiceButtons.find(activity,"close voice coach")?.invoke() ?: run { if(activity is VoiceAgentActivity) activity.finish() }; return "Voice closed." }
            "button" -> {
                val action=VoiceButtons.find(activity,command.label) ?: VoiceButtons.find(host,command.label)
                if(action!=null) { action(); if(activity is VoiceAgentActivity) activity.finish(); return "Done." }
                if(activity is VoiceAgentActivity && command.label in nativeLabels) {
                    pendingNative=WeakReference(host) to command.label
                    activity.finish()
                    return "Returning to the screen to press ${command.label}."
                }
                val matches=nativeButtons(host).filter { it.first==command.label }
                if(matches.size==1) { matches.single().second.performClick(); return "Done." }
                return "That button isn't available here. Say voice commands for help."
            }
            "invalid" -> return command.label
            "help" -> return "Say open workouts, start ten squats, start Floor Basics, or show pushups. During a workout, say pause workout, resume workout, or skip exercise. Say tap followed by a button name for other controls."
        }
        return null
    }
}

/** UI registers actual callbacks rather than asking a model to invent screen coordinates. */
object VoiceButtons {
    private val actions=java.util.WeakHashMap<Activity,MutableMap<String,()->Unit>>()
    fun register(activity: Activity, values: Map<String,()->Unit>) { actions.getOrPut(activity) { mutableMapOf() }.putAll(values.mapKeys { VoiceCommands.normalize(it.key) }) }
    fun unregister(activity: Activity, labels: Set<String>) { labels.forEach { actions[activity]?.remove(VoiceCommands.normalize(it)) } }
    fun find(activity: Activity,label: String): (() -> Unit)? {
        val available=actions[activity] ?: return null
        val match=VoiceCommands.similarLabel(label,available.keys) ?: return null
        return available[match]
    }
    fun clear(activity: Activity) { actions.remove(activity) }
}

class VoiceAgentActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { CalicoTheme { VoiceSheet(autoListen=true,onDismiss=::finish) } }
    }
}
