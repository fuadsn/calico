package com.hackathon.calico

import androidx.test.platform.app.InstrumentationRegistry
import com.hackathon.calico.coach.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import android.os.SystemClock
import org.json.JSONObject

class OfflineCoachTest {
    @Test fun localModelAnswersAndCancellationStopsGeneration() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(android.content.Intent(context, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val model=CoachModel(context)
        assertTrue("Sideload the pinned model into files/models before running this device test",model.present())
        val loadAt=SystemClock.elapsedRealtime()
        NativeCoach().use { engine ->
            model.open().use { engine.load(it.path,context.applicationInfo.nativeLibraryDir) }
            val loadMs=SystemClock.elapsedRealtime()-loadAt
            val snapshot=CoachSnapshot("SQUAT",8,10,false,mapOf("Go deeper" to 3),86f,176f,1000)
            val report=JSONObject().put("loadMs",loadMs)
            val cases=listOf("Why did the app say Go deeper?","How do I do a pushup?",
                "Why did the app say Go deeper?","How do I do a pushup?","How can I make it easier?")
            for((i,question) in cases.withIndex()) {
                if(i<2 && InstrumentationRegistry.getArguments().getString("newOnly")=="true") continue
                val started=SystemClock.elapsedRealtime()
                val history=if(i==4) listOf(CoachMessage(true,"How do I do a pushup?"),CoachMessage(false,"Keep your torso and legs aligned.")) else emptyList()
                val saved=if(i==0 || i==2) snapshot else null
                val prompt=if(i<2) BaselineCoachPrompt.prompt(question,history,saved) else CoachKnowledge.prompt(question,history,saved)
                report.put("promptChars$i",prompt.length)
                engine.start(prompt)
                val output=ByteArrayOutputStream()
                var tokens=0
                var firstMs=0L
                while(true) {
                    val piece=engine.next() ?: break
                    if(tokens++==0) firstMs=SystemClock.elapsedRealtime()-started
                    output.write(piece)
                    if(i>=2 && CoachReplyPolicy.finished(output.toString("UTF-8"),CoachReplyPolicy.detailed(question))) break
                }
                val answer=if(i>=2) CoachReplyPolicy.visible(output.toString("UTF-8")) else output.toString("UTF-8")
                assertTrue("No meaningful answer: $answer",answer.length>30)
                assertFalse("Thinking leaked into answer",answer.contains("<think>"))
                assertTrue(tokens<=192)
                report.put("answer$i",answer).put("firstMs$i",firstMs).put("totalMs$i",SystemClock.elapsedRealtime()-started).put("tokens$i",tokens)
            }
            engine.start(CoachKnowledge.prompt("Explain pushups",emptyList(),null))
            val stopped=SystemClock.elapsedRealtime()
            engine.cancel()
            assertNull(engine.next())
            assertTrue(SystemClock.elapsedRealtime()-stopped<1000)
            File(context.filesDir,"coach-test-result.json").writeText(report.toString())
        }
    }
}
