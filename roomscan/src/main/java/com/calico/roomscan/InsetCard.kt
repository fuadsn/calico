package com.calico.roomscan

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The rounded card behind the demo figure, in the app's grey component colour with a thin
 * light rim. The fill is written to the stencil buffer so the 3D demo drawn afterwards
 * with `GL_EQUAL 1` is clipped to the rounded corners.
 */
class InsetCard {
    private var program = 0
    private var positionAttrib = 0
    private var colorUniform = 0
    private val vertices = ByteBuffer.allocateDirect((2 + 4 * (SEGMENTS + 1)) * 2 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun createOnGlThread() {
        program = GlUtil.buildProgram("""
            attribute vec2 a_Position;
            void main() { gl_Position = vec4(a_Position, 0.0, 1.0); }
        """, """
            precision mediump float;
            uniform vec4 u_Color;
            void main() { gl_FragColor = u_Color; }
        """)
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
    }

    /** Pixel rectangle with a bottom-left origin, matching glViewport. Leaves stencil = 1 inside the fill. */
    fun draw(x: Int, y: Int, width: Int, height: Int, radius: Float, rim: Float, viewportWidth: Int, viewportHeight: Int) {
        GLES20.glUseProgram(program)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glStencilFunc(GLES20.GL_ALWAYS, 1, 0xFF)
        GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_REPLACE)

        GLES20.glStencilMask(0x00)
        fill(x - rim, y - rim, width + 2 * rim, height + 2 * rim, radius + rim, viewportWidth, viewportHeight)
        GLES20.glUniform4f(colorUniform, 0.847f, 0.882f, 0.910f, 0.45f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertices.limit() / 2)

        GLES20.glStencilMask(0xFF)
        fill(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), radius, viewportWidth, viewportHeight)
        GLES20.glUniform4f(colorUniform, 0.227f, 0.243f, 0.231f, 0.94f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertices.limit() / 2)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun fill(x: Float, y: Float, width: Float, height: Float, radius: Float, viewportWidth: Int, viewportHeight: Int) {
        val r = radius.coerceAtMost(minOf(width, height) / 2f)
        fun put(px: Float, py: Float) {
            vertices.put(px / viewportWidth * 2f - 1f).put(py / viewportHeight * 2f - 1f)
        }
        vertices.clear()
        put(x + width / 2f, y + height / 2f)
        // Corner centres counter-clockwise from bottom-right, each sweeping a quarter turn.
        val centres = listOf(x + width - r to y + r, x + width - r to y + height - r, x + r to y + height - r, x + r to y + r)
        centres.forEachIndexed { corner, (cx, cy) ->
            for (i in 0..SEGMENTS) {
                val angle = (corner - 1) * PI / 2 + i * PI / 2 / SEGMENTS
                put(cx + r * cos(angle).toFloat(), cy + r * sin(angle).toFloat())
            }
        }
        val (firstX, firstY) = centres[0]
        put(firstX + r * cos(-PI / 2).toFloat(), firstY + r * sin(-PI / 2).toFloat())
        vertices.flip()
        GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 0, vertices)
    }

    private companion object { const val SEGMENTS = 10 }
}
