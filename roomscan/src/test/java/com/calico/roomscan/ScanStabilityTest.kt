package com.calico.roomscan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanStabilityTest {
    @Test fun `duplicate frames never complete a scan`() {
        val stability = ScanStability()
        repeat(120) { assertFalse(stability.observe("floor", 0f, 1_000_000_000L)) }
    }

    @Test fun `one second of fresh consistent observations completes`() {
        val stability = ScanStability()
        for (i in 0..9) assertFalse(stability.observe("floor", 0f, 1_000_000_000L + i * 100_000_000L))
        assertTrue(stability.observe("floor", 0f, 2_000_000_000L))
    }

    @Test fun `changed surface drift interruption and reset require new observations`() {
        for (change in 0..3) {
            val stability = ScanStability()
            for (i in 0..10) stability.observe("floor", 0f, 1_000_000_000L + i * 100_000_000L)
            when (change) {
                0 -> assertFalse(stability.observe("table", 0f, 2_100_000_000L))
                1 -> assertFalse(stability.observe("floor", 0.06f, 2_100_000_000L))
                2 -> assertFalse(stability.observe("floor", 0f, 3_000_000_000L))
                else -> {
                    stability.reset()
                    assertFalse(stability.observe("floor", 0f, 2_100_000_000L))
                }
            }
        }
    }
}
