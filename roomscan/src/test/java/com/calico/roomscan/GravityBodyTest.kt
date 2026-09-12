package com.calico.roomscan

import org.junit.Assert.*
import org.junit.Test

class GravityBodyTest {
    private fun lifted() = GravityBody().apply { grab(); lift(1f); release() }

    @Test fun `free fall follows earth gravity independently of frame rate`() {
        for (fps in listOf(30, 60, 120)) {
            val body = lifted()
            repeat(fps / 5) { body.step(1f / fps) }
            assertEquals(1f - 0.5f * 9.81f * 0.2f * 0.2f, body.height, 0.00001f)
            assertEquals(-9.81f * 0.2f, body.velocity, 0.00001f)
        }
    }

    @Test fun `landing cannot tunnel through support and remains at rest`() {
        val body = lifted()
        repeat(300) { body.step(1f / 30f); assertTrue(body.height >= 0f) }
        assertEquals(0f, body.height, 0f)
        assertEquals(0f, body.velocity, 0f)
    }

    @Test fun `grabbing arrests a fall and release resumes gravity`() {
        val body = lifted()
        body.step(0.1f)
        body.grab()
        val height = body.height
        repeat(60) { body.step(1f / 60f) }
        assertEquals(height, body.height, 0f)
        body.lift(-10f)
        assertEquals(0f, body.height, 0f)
        body.lift(10f)
        assertEquals(2f, body.height, 0f)
        body.release()
        body.step(0.1f)
        assertTrue(body.height < 2f)
    }

    @Test fun `invalid timing and lifecycle resets cannot inject energy`() {
        val body = lifted()
        body.step(Float.NaN); body.step(-1f)
        assertEquals(1f, body.height, 0f)
        body.step(60f)
        assertTrue(body.height > 0.9f)
        body.reset()
        assertFalse(body.held)
        assertEquals(0f, body.height, 0f)
        assertEquals(0f, body.velocity, 0f)
    }
}
