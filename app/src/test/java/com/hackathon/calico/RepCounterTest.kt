package com.hackathon.calico

import org.junit.Assert.assertEquals
import org.junit.Test

class RepCounterTest {
    @Test fun angleOfStraightLineIs180() = assertEquals(180f, angle(0f, 0f, 1f, 0f, 2f, 0f), 0.01f)
    @Test fun angleOfRightAngleIs90() = assertEquals(90f, angle(0f, 0f, 0f, 1f, 1f, 1f), 0.01f)

    private fun run(vararg angles: Float, e: Exercise = Exercise.PUSHUP): Triple<Int, Int, List<String>> {
        val cues = mutableListOf<String>()
        val c = RepCounter(e, {}, { cues += it })
        angles.forEachIndexed { i, a -> c.feed(a, i * 100L) }
        return Triple(c.count, c.cues, cues)
    }

    @Test fun countsFullRep() {
        val (reps, _, _) = run(170f, 170f, 170f, 120f, 80f, 70f, 70f, 120f, 170f, 170f, 170f)
        assertEquals(1, reps)
    }

    @Test fun cuesPartialRep() {
        val (reps, _, cues) = run(170f, 170f, 170f, 135f, 135f, 135f, 170f, 170f, 170f)   // 135 is between down and down+20
        assertEquals(0, reps)
        assertEquals(listOf("Go lower"), cues)
    }

    @Test fun ignoresJitterAtTop() {
        val (reps, cues, _) = run(170f, 170f, 170f, 165f, 170f, 168f, 170f)
        assertEquals(0, reps); assertEquals(0, cues)
    }

    @Test fun smoothingSuppressesOneFrameSpike() {
        // a single 60° outlier between straight-arm frames must not open a rep
        val (reps, cues, _) = run(170f, 170f, 170f, 100f, 170f, 170f, 170f)
        assertEquals(0, reps); assertEquals(0, cues)
    }

    @Test fun everyExerciseHasSaneThresholds() = Exercise.values().filter { it.holdSec == 0 }.forEach {
        assert(it.down < it.up - 15f) { "${it.name}: down must be below up" }
        assertEquals(3, it.left.size); assertEquals(3, it.right.size)
    }
}

class HoldTest {
    @Test fun accumulatesOnlyWhileHeldAndAnnouncesEvery5s() {
        val announced = mutableListOf<Int>(); val cues = mutableListOf<String>()
        val c = RepCounter(Exercise.PLANK, { announced += it }, { cues += it })
        var t = 0L
        repeat(60) { c.feed(170f, t); t += 100 }   // 6 s held
        repeat(10) { c.feed(120f, t); t += 100 }   // 1 s broken -> one cue, no time added
        repeat(50) { c.feed(170f, t); t += 100 }   // 5 s more
        assert(c.count in 10..11) { "held ${c.count}s" }   // ~10.9 s minus smoothing lag
        assertEquals(listOf(5, 10), announced)
        assertEquals(listOf("Hips up"), cues)
        assertEquals(false, c.done)
    }

    @Test fun completesAtHoldSec() {
        var last = 0
        val c = RepCounter(Exercise.OVERHEAD_STRETCH, { last = it }, {})
        var t = 0L
        repeat(250) { c.feed(40f, t); t += 100 }
        assertEquals(true, c.done); assertEquals(20, last)
    }
}
