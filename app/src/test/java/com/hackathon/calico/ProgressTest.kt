package com.hackathon.calico

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ProgressTest {
    private val d = LocalDate.of(2026, 9, 12)

    @Test fun streakCountsBackFromToday() =
        assertEquals(3, streakOf(setOf(d, d.minusDays(1), d.minusDays(2), d.minusDays(5)), d))

    @Test fun streakSurvivesIfTodayNotYetDone() =
        assertEquals(2, streakOf(setOf(d.minusDays(1), d.minusDays(2)), d))

    @Test fun streakBreaksAfterAMissedDay() =
        assertEquals(0, streakOf(setOf(d.minusDays(2), d.minusDays(3)), d))

    @Test fun stepsRoundTrip() {
        val s = listOf(Step(Exercise.JUMPING_JACK, 20, warmup = true), Step(Exercise.PUSHUP, 10), Step(Exercise.PLANK, 30))
        assertEquals(s, decodeSteps(s.encode()))
    }
}
