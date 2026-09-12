package com.calico.roomscan

import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test

class FigureInteractionTest {
    private fun interaction() = FigureInteraction().apply {
        bounds(M4.identity(), M4.identity(), floatArrayOf(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f), 1000, 1000)
    }

    @Test fun selectedDragLiftsAndRotatesThenLandsWithoutMovingAnchor() {
        val i = interaction()
        var taps = 0
        val tap: (Float, Float) -> Unit = { _, _ -> taps++ }
        i.touch(MotionEvent.ACTION_DOWN, 500f, 500f, 10f, tap)
        i.touch(MotionEvent.ACTION_MOVE, 600f, 300f, 10f, tap)
        assertTrue(i.body.held)
        assertEquals(0.4f, i.body.height, 0.0001f)
        assertEquals(35f, i.yaw, 0.0001f)
        val matrix = M4.identity()
        i.transform(matrix)
        assertEquals(0.4f, matrix[13], 0.0001f)
        i.touch(MotionEvent.ACTION_UP, 600f, 300f, 10f, tap)
        assertFalse(i.body.held)
        repeat(121) { i.advance(it / 60f) }
        assertEquals(0f, i.body.height, 0f)
        assertEquals(0, taps)
    }

    @Test fun floorTapsWorkButCancelledOrMultiTouchGesturesNeverReposition() {
        val i = interaction()
        var taps = 0
        val tap: (Float, Float) -> Unit = { _, _ -> taps++ }
        i.touch(MotionEvent.ACTION_DOWN, 50f, 50f, 10f, tap)
        i.touch(MotionEvent.ACTION_UP, 50f, 50f, 10f, tap)
        assertEquals(1, taps)
        for (cancel in listOf(MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN)) {
            i.touch(MotionEvent.ACTION_DOWN, 50f, 50f, 10f, tap)
            i.touch(cancel, 50f, 50f, 10f, tap)
            i.touch(MotionEvent.ACTION_UP, 50f, 50f, 10f, tap)
        }
        assertEquals(1, taps)
        i.touch(MotionEvent.ACTION_DOWN, 500f, 500f, 10f, tap)
        i.suspend()
        assertFalse(i.body.held)
        i.touch(MotionEvent.ACTION_MOVE, 500f, 100f, 10f, tap)
        assertEquals(0f, i.body.height, 0f)
    }

    @Test fun offscreenBodyKeepsFalling() {
        val i = interaction()
        i.body.grab(); i.body.lift(1f)
        i.advance(0f)
        repeat(60) {
            i.hide()
            i.advance((it + 1) / 60f)
        }
        assertEquals(0f, i.body.height, 0f)
        assertFalse(i.body.held)
    }
}
