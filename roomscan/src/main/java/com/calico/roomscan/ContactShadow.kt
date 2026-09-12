package com.calico.roomscan

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Soft dark ellipse on the floor under the figure. Without a contact cue, centimetre-level
 * tracking noise reads as the figure sliding; with one it reads as standing on the surface.
 */
class ContactShadow {
    private var program = 0
    private var cornerAttrib = 0
    private var mvpUniform = 0
    private var rectUniform = 0
    private val mvp = FloatArray(16)
    private val corners = ByteBuffer.allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f)); position(0) }

    fun createOnGlThread() {
        program = GlUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        cornerAttrib = GLES20.glGetAttribLocation(program, "a_Corner")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_Mvp")
        rectUniform = GLES20.glGetUniformLocation(program, "u_Rect")
    }

    /** [bounds] is the posed figure's local min xyz then max xyz, in metres. */
    fun draw(viewProjection: FloatArray, placement: FloatArray, bounds: FloatArray) {
        val halfX = (bounds[3] - bounds[0]) / 2f
        val halfZ = (bounds[5] - bounds[2]) / 2f
        if (!(halfX > 0f && halfZ > 0f)) return
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, placement, 0)
        GLES20.glUseProgram(program)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvp, 0)
        GLES20.glUniform4f(rectUniform, (bounds[0] + bounds[3]) / 2f, (bounds[2] + bounds[5]) / 2f,
            halfX * 0.85f + 0.08f, halfZ * 0.85f + 0.08f)
        GLES20.glEnableVertexAttribArray(cornerAttrib)
        GLES20.glVertexAttribPointer(cornerAttrib, 2, GLES20.GL_FLOAT, false, 0, corners)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
        GLES20.glDisableVertexAttribArray(cornerAttrib)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDepthMask(true)
    }

    private companion object {
        const val VERTEX_SHADER = """
            uniform mat4 u_Mvp;
            uniform vec4 u_Rect;
            attribute vec2 a_Corner;
            varying vec2 v_Uv;
            void main() {
                v_Uv = a_Corner;
                // Lifted a few millimetres so it never z-fights with the plane grid.
                gl_Position = u_Mvp * vec4(u_Rect.x + a_Corner.x * u_Rect.z, 0.004,
                    u_Rect.y + a_Corner.y * u_Rect.w, 1.0);
            }
        """
        const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 v_Uv;
            void main() {
                float fade = 1.0 - smoothstep(0.25, 1.0, length(v_Uv));
                gl_FragColor = vec4(0.0, 0.0, 0.0, 0.42 * fade * fade);
            }
        """
    }
}
