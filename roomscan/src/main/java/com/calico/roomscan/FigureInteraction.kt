package com.calico.roomscan

import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.opengl.GLSurfaceView
import kotlin.math.abs

/** All gesture and physics state belongs to the GL thread. Never retain pooled MotionEvents. */
class FigureInteraction {
    val body = GravityBody()
    var yaw = 0f
        private set
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var selected = false
    private var moved = false
    private var cancelled = false
    private var visible = false
    val isVisible get() = visible
    private var left = 0f
    private var right = 0f
    private var top = 0f
    private var bottom = 0f
    private var metresPerPixel = 0.003f
    private var lastSeconds: Float? = null
    private val matrix = FloatArray(16)
    private val point = FloatArray(4)
    private val projected = FloatArray(4)

    fun attach(view: GLSurfaceView, tap: (Float, Float) -> Unit = { _, _ -> }) {
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            val action = event.actionMasked
            val x = event.x; val y = event.y
            view.queueEvent { touch(action, x, y, slop.toFloat(), tap) }
            true
        }
    }

    internal fun touch(action: Int, x: Float, y: Float, slop: Float, tap: (Float, Float) -> Unit) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; lastX = x; lastY = y
                moved = false; cancelled = false
                selected = visible && x in left..right && y in top..bottom
                if (selected) body.grab()
            }
            MotionEvent.ACTION_MOVE -> {
                if (cancelled) return
                moved = moved || abs(x - downX) > slop || abs(y - downY) > slop
                if (selected && moved) {
                    yaw = (yaw + (x - lastX) * 0.35f) % 360f
                    body.lift((lastY - y) * metresPerPixel)
                }
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_UP -> {
                if (!cancelled && !selected && !moved && abs(x-downX) <= slop && abs(y-downY) <= slop) tap(x, y)
                selected = false; body.release()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                cancelled = true; selected = false; body.release()
            }
        }
    }

    fun advance(seconds: Float) {
        lastSeconds?.let { body.step(seconds - it) }
        lastSeconds = seconds
    }

    fun reset() { body.reset(); yaw = 0f; suspend() }
    fun suspend() {
        hide(); lastSeconds = null
    }
    fun hide() {
        body.release(); selected = false; cancelled = true; visible = false
    }

    fun transform(placement: FloatArray) {
        // Placement is Y-up; translation precedes heading rotation.
        Matrix.translateM(placement, 0, 0f, body.height, 0f)
        Matrix.rotateM(placement, 0, yaw, 0f, 1f, 0f)
    }

    /** Project the posed joint bounds, padded for the mesh and finger-sized selection. */
    fun bounds(vp: FloatArray, placement: FloatArray, box: FloatArray, width: Int, height: Int) {
        visible = false
        if (width <= 0 || height <= 0) return
        Matrix.multiplyMM(matrix, 0, vp, 0, placement, 0)
        left = Float.POSITIVE_INFINITY; top = left
        right = Float.NEGATIVE_INFINITY; bottom = right
        for (i in 0..7) {
            for (a in 0..2) point[a] = box[a + if (i and (1 shl a) == 0) 0 else 3]
            point[3] = 1f
            Matrix.multiplyMV(projected, 0, matrix, 0, point, 0)
            if (projected[3] <= 0.1f) return
            val x = (projected[0] / projected[3] + 1f) * width / 2f
            val y = (1f - projected[1] / projected[3]) * height / 2f
            left = minOf(left, x); right = maxOf(right, x)
            top = minOf(top, y); bottom = maxOf(bottom, y)
        }
        metresPerPixel = ((box[4] - box[1]) / (bottom-top).coerceAtLeast(1f)).coerceIn(0.0005f, 0.015f)
        val padding = width * 0.025f
        left -= padding; right += padding; top -= padding; bottom += padding
        visible = right >= 0 && left <= width && bottom >= 0 && top <= height
    }
}
