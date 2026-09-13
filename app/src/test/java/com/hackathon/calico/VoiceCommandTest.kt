package com.hackathon.calico

import com.hackathon.calico.voice.VoiceCommands
import org.junit.Assert.*
import org.junit.Test

class VoiceCommandTest {
    @Test fun conversationalControlsUseSensibleDefaults() {
        for(text in listOf("stop the workout", "Can you please stop my workout now", "finish this session", "I want to stop the workout"))
            assertEquals(text,"end",VoiceCommands.parse(text)?.action)
        for(text in listOf("start a workout", "please just start any workout", "can you begin a session for me", "let's start working out"))
            assertEquals(text,"today",VoiceCommands.parse(text)?.action)
        assertEquals("pause",VoiceCommands.parse("hold on")?.action)
        assertEquals("resume",VoiceCommands.parse("keep going")?.action)
        assertEquals("skip",VoiceCommands.parse("next one")?.action)
        assertNull(VoiceCommands.parse("please don't stop the workout"))
        assertNull(VoiceCommands.parse("how do I start a workout"))
        assertEquals("pause",VoiceCommands.parse("press the pause button")?.action)
        assertEquals("overview",VoiceCommands.similarLabel("overveiw",setOf("overview","scan","voice")))
        assertNull(VoiceCommands.similarLabel("cleer",setOf("clear")))
        assertNull(VoiceCommands.similarLabel("rate",setOf("late","date")))
    }
    @Test fun globalExitWakeResetAndDemoModesAreUnambiguous() {
        for(text in listOf("close","stop this","please exit from AR","quit Calico now","leave this page"))
            assertEquals(text,"exit",VoiceCommands.parse(text)?.action)
        assertEquals("end",VoiceCommands.parse("stop workout")?.action)
        assertTrue(VoiceCommands.isWakeMention("Hey Calico, listen again"))
        assertFalse(VoiceCommands.isWakeMention("Calico show squats"))
        val demo=VoiceCommands.parse("show me a squat demo")!!
        assertEquals("demo",demo.action); assertEquals(Exercise.SQUAT,demo.exercise); assertTrue(demo.expand)
        val ar=VoiceCommands.parse("show squats in AR")!!
        assertEquals("demo",ar.action); assertEquals(Exercise.SQUAT,ar.exercise); assertFalse(ar.expand)
        assertTrue(VoiceCommands.parse("show me a demo")!!.expand)
        assertFalse(VoiceCommands.parse("open AR")!!.expand)
    }
    @Test fun workoutOrbEditsAreExplicitAndBounded() {
        assertEquals("restart",VoiceCommands.parse("restart exercise")!!.action)
        assertEquals("restart_session",VoiceCommands.parse("restart workout")!!.action)
        assertEquals("resume",VoiceCommands.parse("start")!!.action)
        val target=VoiceCommands.parse("change rep count to twenty five")!!
        assertEquals("target",target.action); assertEquals(25,target.target)
        assertEquals("seconds",VoiceCommands.parse("set hold time to thirty seconds")!!.label)
        assertEquals(120,VoiceCommands.parse("set target to two minutes")!!.target)
        assertEquals("invalid",VoiceCommands.parse("set reps to zero")!!.action)
        assertEquals("invalid",VoiceCommands.parse("set reps to 9999999999999")!!.action)
        val change=VoiceCommands.parse("change workout type to incline pushups")!!
        assertEquals("change_exercise",change.action); assertEquals(Exercise.INCLINE_PUSHUP,change.exercise)
        assertEquals(Exercise.SQUAT,VoiceCommands.parse("switch to squats")!!.exercise)
        assertEquals("invalid",VoiceCommands.parse("switch to squats and lunges")!!.action)
        assertNull(VoiceCommands.parse("should I change my rep count"))
    }
    @Test fun changingHoldTargetPreservesTimeAlreadyHeld() {
        val hold=RepCounter(Exercise.PLANK,{}, {},holdSec=30)
        repeat(11) { hold.feed(170f,it*500L) }
        assertEquals(5,hold.count)
        hold.updateHoldTarget(45)
        assertEquals(5,hold.count); assertEquals(45,hold.holdSec)
        hold.feed(170f,5500)
        hold.feed(170f,6000)
        assertEquals(6,hold.count)
    }
    @Test fun spokenTargetsAndSpecificExercises() {
        val squat=VoiceCommands.parse("Calico, please start twenty five squats")!!
        assertEquals(Exercise.SQUAT,squat.exercise)
        assertEquals(25,squat.target)
        assertEquals(Exercise.INCLINE_PUSHUP,VoiceCommands.parse("start ten incline push ups")!!.exercise)
        assertEquals(Exercise.PIKE_PUSHUP,VoiceCommands.parse("start pike pushups")!!.exercise)
        assertEquals(30,VoiceCommands.parse("start plank for thirty seconds")!!.target)
        assertEquals(60,VoiceCommands.parse("start plank for one minute")!!.target)
        assertEquals("invalid",VoiceCommands.parse("start squats for thirty seconds")!!.action)
        assertEquals("invalid",VoiceCommands.parse("start 999999999999 squats")!!.action)
        for(e in Exercise.entries) assertEquals(e,VoiceCommands.parse("start ${e.name.replace('_',' ')}")!!.exercise)
    }
    @Test fun modelIntentsMapOntoTheSameBoundedCommands() {
        val squats=VoiceCommands.fromIntent("""{"action":"start_exercise","exercise":"SQUAT","target":15,"unit":"reps"}""")!!
        assertEquals("exercise",squats.action); assertEquals(Exercise.SQUAT,squats.exercise); assertEquals(15,squats.target)
        assertEquals(Exercise.INCLINE_PUSHUP,VoiceCommands.fromIntent("""{"action":"start_exercise","exercise":"incline push-ups"}""")!!.exercise)
        assertEquals(Exercise.JUMPING_JACK,VoiceCommands.fromIntent("""{"action":"demo","exercise":"jumping jacks"}""")!!.exercise)
        assertEquals("invalid",VoiceCommands.fromIntent("""{"action":"start_exercise","exercise":"SQUAT","target":9999}""")!!.action)
        assertEquals("today",VoiceCommands.fromIntent("""{"action":"start_session","name":"today"}""")!!.action)
        assertEquals("floor basics",VoiceCommands.fromIntent("""{"action":"start_session","name":"Floor Basics"}""")!!.label)
        val more=VoiceCommands.fromIntent("""{"action":"adjust_target","delta":5,"unit":"reps"}""")!!
        assertEquals("adjust",more.action); assertEquals(5,more.target); assertEquals("reps",more.label)
        assertEquals(-3,VoiceCommands.fromIntent("""{"action":"adjust_target","delta":-3}""")!!.target)
        assertNull(VoiceCommands.fromIntent("""{"action":"adjust_target","delta":0}"""))
        assertEquals("target",VoiceCommands.fromIntent("""{"action":"set_target","target":20}""")!!.action)
        assertEquals("journey",VoiceCommands.fromIntent("""{"action":"open","screen":"journey"}""")!!.action)
        assertEquals("end",VoiceCommands.fromIntent("""{"action":"end"} trailing text""")!!.action)
        // Anything the app cannot run safely falls through to the coach as a question.
        assertNull(VoiceCommands.fromIntent("""{"action":"question"}"""))
        assertNull(VoiceCommands.fromIntent("""{"action":"delete_everything"}"""))
        assertNull(VoiceCommands.fromIntent("""{"action":"start_exercise","exercise":"bench press"}"""))
        assertNull(VoiceCommands.fromIntent("""{"action":"open","screen":"settings"}"""))
        assertNull(VoiceCommands.fromIntent("""{"action":"pause"""))
        assertNull(VoiceCommands.fromIntent("not json at all"))
    }
    @Test fun controlsAreExplicitAndQuestionsAreNotCommands() {
        assertEquals("pause",VoiceCommands.parse("pause workout")!!.action)
        assertEquals("resume",VoiceCommands.parse("play")!!.action)
        assertEquals("skip",VoiceCommands.parse("skip rest")!!.action)
        assertNull(VoiceCommands.parse("Should I pause during squats?"))
        assertNull(VoiceCommands.parse("Don't start a workout"))
        assertEquals("invalid",VoiceCommands.parse("start 0 squats")!!.action)
        assertEquals("invalid",VoiceCommands.parse("start 9999 squats")!!.action)
        assertEquals("invalid",VoiceCommands.parse("start 10 squats and 20 pushups")!!.action)
        assertEquals("invalid",VoiceCommands.parse("start squats and pushups")!!.action)
        assertEquals("demo",VoiceCommands.parse("show jumping jacks")!!.action)
        assertEquals("level",VoiceCommands.parse("start Floor Basics")!!.action)
    }
    @Test fun pauseDoesNotCountElapsedHoldTimeOrAnUnfinishedRep() {
        val hold=RepCounter(Exercise.PLANK,{}, {})
        hold.feed(170f,0); hold.feed(170f,500)
        hold.suspendTiming()
        hold.feed(170f,20000)
        assertEquals(0,hold.count)
        val reps=RepCounter(Exercise.SQUAT,{}, {})
        reps.feed(80f,0)
        reps.suspendTiming()
        repeat(3) { reps.feed(180f,10000L+it*100) }
        assertEquals(0,reps.count)
    }
}
