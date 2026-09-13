package com.hackathon.calico

import android.app.Application
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.hackathon.calico.coach.CoachModel
import com.hackathon.calico.coach.CoachViewModel
import com.hackathon.calico.voice.VoiceCommand
import com.hackathon.calico.voice.VoiceCommands
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Requests the regex parser cannot read go to the local Qwen model, which must pick one app
 * action with the right parameters, or hand a real question back to the coach. Runs on the
 * phone's NPU; the model file must be linked first.
 */
class VoiceIntentDeviceTest {
    private data class Case(val request: String, val context: String, val action: String?, val exercise: Exercise? = null, val target: Int? = null)

    @Test fun looseRequestsBecomeBoundedActionsOrQuestions() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        assertTrue("Link the Qwen model on this device first", CoachModel(context).present())
        lateinit var coach: CoachViewModel
        i.runOnMainSync { coach = CoachViewModel(context.applicationContext as Application) }
        val idle = "No workout is open."
        val running = "A workout is open. Squat: 4 of 10 reps. Running."
        val cases = listOf(
            Case("let's do some squats, maybe fifteen of them", idle, "exercise", Exercise.SQUAT, 15),
            Case("put five more reps on this one", running, "adjust", target = 5),
            Case("I'm done for today, save it", running, "end"),
            Case("can you show me how a plank is done", idle, "demo", Exercise.PLANK),
            Case("I feel like doing the floor basics session", idle, "level"),
            Case("swap this for lunges", running, "change_exercise", Exercise.LUNGE),
            Case("why do my knees hurt when I squat", running, null),
        )
        val report = StringBuilder()
        val failures = mutableListOf<String>()
        try {
            for (case in cases) {
                // The parser must not already cover these, or the model is never consulted.
                assertNull("Parser handled '${case.request}' itself", VoiceCommands.parse(case.request))
                var picked: VoiceCommand? = null
                var executed = false
                val started = SystemClock.elapsedRealtime()
                i.runOnMainSync { coach.ask(case.request, case.context) { picked = it; executed = true; "Done." } }
                // First call loads the model; later ones should take about a second.
                repeat(900) { if (coach.state.value.busy || (!executed && case.action != null && SystemClock.elapsedRealtime() - started < 2000)) SystemClock.sleep(100) else return@repeat }
                if (case.action == null) {
                    // A question starts a normal answer; do not wait for the whole generation.
                    repeat(50) { if (!coach.state.value.busy && !executed) SystemClock.sleep(100) else return@repeat }
                    i.runOnMainSync { coach.stop() }
                    repeat(50) { if (coach.state.value.busy) SystemClock.sleep(100) else return@repeat }
                }
                val ms = SystemClock.elapsedRealtime() - started
                val got = picked
                val ok = got?.action == case.action && (case.exercise == null || got?.exercise == case.exercise) && (case.target == null || got?.target == case.target)
                report.append("${if (ok) "PASS" else "FAIL"} ${ms}ms '${case.request}' [${case.context}] -> ${got?.let { "${it.action} ${it.exercise ?: ""} ${it.target ?: ""}".trim() } ?: "question"}\n")
                if (!ok) failures.add("'${case.request}' -> ${got?.action ?: "question"}, expected ${case.action ?: "question"}")
            }
        } finally {
            i.runOnMainSync { coach.stop() }
            File(context.filesDir, "voice-intent-result.txt").writeText(report.toString())
            android.util.Log.i("VoiceIntentDeviceTest", "\n$report")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
