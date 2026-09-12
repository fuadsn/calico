package com.calico.roomscan

import org.junit.Assert.*
import org.junit.Test

class HorizontalPatchFitTest {
    @Test fun `noisy floor with furniture outliers yields a floor patch`() {
        val floor = (0..8).flatMap { x -> (0..8).map { z ->
            floatArrayOf(x * 0.15f, ((x + z) % 3 - 1) * 0.005f, z * 0.15f)
        } }
        val furniture = (0..15).map { floatArrayOf(it * 0.05f, 0.7f, 0.3f) }
        val patch = requireNotNull(HorizontalPatchFit.fit(floor + furniture, 1.5f))
        assertEquals(0f, patch.height, 0.01f)
        assertEquals(1.44f, patch.area, 0.02f)
        assertTrue(patch.support >= 70)
    }

    @Test fun `wall slices and isolated points are not horizontal surfaces`() {
        val wall = (0..20).flatMap { x -> (0..20).map { y ->
            floatArrayOf(x * 0.1f, y * 0.1f, -2f)
        } }
        assertNull(HorizontalPatchFit.fit(wall, 1.5f))
        assertNull(HorizontalPatchFit.fit(wall.take(8), 1.5f))
    }

    @Test fun `invalid coordinates and ceiling are ignored`() {
        val ceiling = (0..8).flatMap { x -> (0..8).map { z -> floatArrayOf(x * 0.15f, 2f, z * 0.15f) } }
        assertNull(HorizontalPatchFit.fit(ceiling + listOf(floatArrayOf(Float.NaN, 0f, 0f)), 1.5f))
    }
}
