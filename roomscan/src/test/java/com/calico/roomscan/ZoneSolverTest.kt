package com.calico.roomscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneSolverTest {

    @Test
    fun `a small side table is too small`() {
        assertEquals(ZoneRating.TOO_SMALL, ZoneSolver.classify(0.5f, 0.4f).rating)
    }

    @Test
    fun `a modest patch allows standing work only`() {
        assertEquals(ZoneRating.STANDING_ONLY, ZoneSolver.classify(1.4f, 1.0f).rating)
    }

    @Test
    fun `a clear floor is ample`() {
        assertEquals(ZoneRating.AMPLE, ZoneSolver.classify(2.0f, 2.0f).rating)
    }

    @Test
    fun `a long narrow strip is not ample however big its area`() {
        // 6 square metres, but only 60cm across: no room to lie down or jump.
        val zone = ZoneSolver.classify(10.0f, 0.6f)
        assertEquals(ZoneRating.STANDING_ONLY, zone.rating)
        assertTrue(zone.areaM2 > ZoneSolver.AMPLE_MIN_AREA_M2)
    }

    @Test
    fun `a hairline strip is rejected outright`() {
        assertEquals(ZoneRating.TOO_SMALL, ZoneSolver.classify(20.0f, 0.05f).rating)
    }

    @Test
    fun `exactly on the ample threshold counts as ample`() {
        val zone = ZoneSolver.classify(2.5f, 1.2f)
        assertEquals(3.0f, zone.areaM2, 0.0001f)
        assertEquals(ZoneRating.AMPLE, zone.rating)
    }

    @Test
    fun `negative measurements are clamped rather than trusted`() {
        val zone = ZoneSolver.classify(-3.0f, 2.0f)
        assertEquals(0.0f, zone.areaM2, 0.0001f)
        assertEquals(ZoneRating.TOO_SMALL, zone.rating)
    }

    @Test
    fun `short side reports the narrow dimension`() {
        assertEquals(1.5f, ZoneSolver.classify(4.0f, 1.5f).shortSideM, 0.0001f)
    }

    @Test
    fun `usability excludes only the too small zones`() {
        assertFalse(ZoneSolver.isUsable(ZoneSolver.classify(0.3f, 0.3f)))
        assertTrue(ZoneSolver.isUsable(ZoneSolver.classify(1.4f, 1.0f)))
        assertTrue(ZoneSolver.isUsable(ZoneSolver.classify(2.0f, 2.0f)))
    }

    @Test
    fun `best prefers an ample zone over a larger standing only strip`() {
        val strip = ZoneSolver.classify(12.0f, 0.7f)   // 8.4 square metres, too narrow
        val floor = ZoneSolver.classify(2.0f, 1.8f)    // 3.6 square metres, usable
        val best = ZoneSolver.best(listOf(strip, floor))
        assertEquals(ZoneRating.AMPLE, best?.rating)
        assertEquals(3.6f, best?.areaM2 ?: 0f, 0.0001f)
    }

    @Test
    fun `best breaks ties on area`() {
        val small = ZoneSolver.classify(2.0f, 1.6f)
        val large = ZoneSolver.classify(3.0f, 2.5f)
        assertEquals(large.areaM2, ZoneSolver.best(listOf(small, large))?.areaM2 ?: 0f, 0.0001f)
    }

    @Test
    fun `best returns nothing when every zone is too small`() {
        val zones = listOf(ZoneSolver.classify(0.2f, 0.2f), ZoneSolver.classify(0.9f, 0.3f))
        assertNull(ZoneSolver.best(zones))
    }

    @Test
    fun `best returns nothing for an empty room`() {
        assertNull(ZoneSolver.best(emptyList()))
    }

    // --- traced outline area ---

    @Test
    fun `a traced L shape is demoted below its bounding box`() {
        // Bounding box is 2x2, which would read as ample, but the real outline
        // encloses only 2 square metres of floor.
        val zone = ZoneSolver.classify(2.0f, 2.0f, measuredAreaM2 = 2.0f)
        assertEquals(2.0f, zone.areaM2, 0.0001f)
        assertEquals(ZoneRating.STANDING_ONLY, zone.rating)
    }

    @Test
    fun `a traced area is never credited beyond its bounding box`() {
        val zone = ZoneSolver.classify(1.0f, 1.0f, measuredAreaM2 = 50.0f)
        assertEquals(1.0f, zone.areaM2, 0.0001f)
    }

    @Test
    fun `a negative traced area is clamped to zero`() {
        assertEquals(0.0f, ZoneSolver.classify(2.0f, 2.0f, -5.0f).areaM2, 0.0001f)
    }

    @Test
    fun `polygon area measures a square`() {
        val square = floatArrayOf(0f, 0f, 2f, 0f, 2f, 2f, 0f, 2f)
        assertEquals(4.0f, ZoneSolver.polygonArea(square), 0.0001f)
    }

    @Test
    fun `polygon area ignores winding direction`() {
        val clockwise = floatArrayOf(0f, 0f, 0f, 2f, 2f, 2f, 2f, 0f)
        assertEquals(4.0f, ZoneSolver.polygonArea(clockwise), 0.0001f)
    }

    @Test
    fun `polygon area measures a triangle`() {
        val triangle = floatArrayOf(0f, 0f, 4f, 0f, 0f, 3f)
        assertEquals(6.0f, ZoneSolver.polygonArea(triangle), 0.0001f)
    }

    @Test
    fun `polygon area of a degenerate outline is zero`() {
        assertEquals(0.0f, ZoneSolver.polygonArea(floatArrayOf(0f, 0f, 1f, 1f)), 0.0001f)
        assertEquals(0.0f, ZoneSolver.polygonArea(floatArrayOf()), 0.0001f)
    }

    // --- scan progress ---

    @Test
    fun `progress starts at zero on an empty scan`() {
        assertEquals(0.0f, ZoneSolver.scanProgress(0, 0.0f), 0.0001f)
    }

    @Test
    fun `feature points alone cannot fill the bar`() {
        val progress = ZoneSolver.scanProgress(100_000, 0.0f)
        assertEquals(ZoneSolver.EARLY_PHASE_SHARE, progress, 0.0001f)
    }

    @Test
    fun `mapped floor completes the bar`() {
        val progress = ZoneSolver.scanProgress(
            ZoneSolver.POINTS_FOR_FULL_EARLY_PHASE, ZoneSolver.AMPLE_MIN_AREA_M2
        )
        assertEquals(1.0f, progress, 0.0001f)
    }

    @Test
    fun `progress never exceeds one`() {
        assertEquals(1.0f, ZoneSolver.scanProgress(999_999, 500.0f), 0.0001f)
    }

    @Test
    fun `progress rises as more floor is mapped`() {
        val little = ZoneSolver.scanProgress(50, 0.5f)
        val more = ZoneSolver.scanProgress(50, 2.0f)
        assertTrue(more > little)
    }

    @Test
    fun `negative inputs do not drive progress below zero`() {
        assertEquals(0.0f, ZoneSolver.scanProgress(-10, -4.0f), 0.0001f)
    }
}
