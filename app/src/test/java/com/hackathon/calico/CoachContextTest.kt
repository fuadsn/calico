package com.hackathon.calico

import com.hackathon.calico.coach.*
import org.junit.Assert.*
import org.junit.Test

class CoachContextTest {
    @Test fun doubleTapCannotSendThenCancel() {
        val gate=TapGate()
        assertTrue(gate.accept(1000)); assertFalse(gate.accept(1001)); assertFalse(gate.accept(1200)); assertTrue(gate.accept(1700))
    }
    @Test fun workoutSnapshotRoundTripsWithoutInventingAccuracy() {
        val s=CoachSnapshot("PUSHUP",7,10,false,mapOf("Go lower" to 2),88f,170f,1000,"session-1",2,100,70,true)
        assertEquals(s,CoachStore.decode(CoachStore.encode(s)))
        val p=CoachKnowledge.prompt("What happened during that workout?",emptyList(),s,"Saved workout: squat 8 reps; pushup 7 reps. Tracking coverage is not form accuracy.")
        assertTrue(p.contains("squat 8 reps")); assertTrue(p.contains("pushup 7 reps")); assertTrue(p.contains("not form accuracy"))
    }
}
