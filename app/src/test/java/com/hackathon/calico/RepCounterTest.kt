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
        val (reps, _, cues) = run(170f, 170f, 170f, 110f, 110f, 110f, 170f, 170f, 170f)
        assertEquals(0, reps)
        assertEquals(listOf("Go lower"), cues)
    }

    @Test fun ignoresJitterAtTop() {
        val (reps, cues, _) = run(170f, 170f, 170f, 165f, 170f, 168f, 170f)
        assertEquals(0, reps); assertEquals(0, cues)
    }

    @Test fun smoothingSuppressesOneFrameSpike() {
        // a single 60° outlier between straight-arm frames must not open a rep
        val (reps, cues, _) = run(170f, 170f, 170f, 60f, 170f, 170f, 170f)
        assertEquals(0, reps); assertEquals(0, cues)
    }

    @Test fun everyExerciseHasSaneThresholds() = Exercise.values().forEach {
        assert(it.down < it.up - 30f) { "${it.name}: down must be well below up" }
        assertEquals(3, it.left.size); assertEquals(3, it.right.size)
    }
}
