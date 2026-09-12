package com.hackathon.calico

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/** Draws the 33-point skeleton over the camera preview. Assumes PreviewView FILL_CENTER. */
class OverlayView(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    private var points: List<NormalizedLandmark> = emptyList()
    private var imgW = 1; private var imgH = 1
    var highlight: IntArray = IntArray(0)   // joint triple currently being measured

    private val line = Paint().apply { color = 0xFFB99CEB.toInt(); strokeWidth = 8f; style = Paint.Style.STROKE }
    private val dot = Paint().apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    private val hot = Paint().apply { color = 0xFF9564DD.toInt(); style = Paint.Style.FILL }

    fun update(landmarks: List<NormalizedLandmark>, imageWidth: Int, imageHeight: Int) {
        points = landmarks; imgW = imageWidth; imgH = imageHeight
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (points.isEmpty()) return
        val scale = maxOf(width.toFloat() / imgW, height.toFloat() / imgH)
        val offX = (width - imgW * scale) / 2f
        val offY = (height - imgH * scale) / 2f
        fun x(i: Int) = points[i].x() * imgW * scale + offX
        fun y(i: Int) = points[i].y() * imgH * scale + offY

        for ((a, b) in BONES) canvas.drawLine(x(a), y(a), x(b), y(b), line)
        for (i in points.indices) canvas.drawCircle(x(i), y(i), 10f, if (i in highlight) hot else dot)
    }

    companion object {
        val BONES = listOf(
            11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16,      // arms
            11 to 23, 12 to 24, 23 to 24,                          // torso
            23 to 25, 25 to 27, 24 to 26, 26 to 28,                // legs
        )
    }
}
