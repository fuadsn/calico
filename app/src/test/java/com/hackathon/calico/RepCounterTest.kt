package com.hackathon.calico

import org.junit.Assert.assertEquals
import org.junit.Test

class RepCounterTest {
    @Test fun angleOfStraightLineIs180() = assertEquals(180f, angle(0f, 0f, 1f, 0f, 2f, 0f), 0.01f)
    @Test fun angleOfRightAngleIs90() = assertEquals(90f, angle(0f, 0f, 0f, 1f, 1f, 1f), 0.01f)

    @Test fun countsFullRepsAndCuesPartials() {
        val reps = mutableListOf<Int>(); val cues = mutableListOf<String>()
        val c = RepCounter(Exercise.PUSHUP, { reps += it }, { cues += it })
        listOf(170f, 120f, 80f, 120f, 170f).forEach(c::feed)   // full rep
        listOf(110f, 170f).forEach(c::feed)                     // partial: dipped but not below 90
        listOf(170f, 165f, 170f).forEach(c::feed)               // noise: nothing
        assertEquals(listOf(1), reps)
        assertEquals(listOf("Go lower"), cues)
        assertEquals(1, c.count)
    }
}
