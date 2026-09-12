package com.calico.roomscan

import android.content.res.AssetManager
import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal fallback driven by the same exercise clip as the skinned model. */
class PoseFigureRenderer(assets: AssetManager, exercise: String) {
    private val clip = MotionAssets.read(assets, exercise)
    private val landmarks = FloatArray(Lm.COUNT * 3)
    private val supportRig = LandmarkSupport(FloatArray(99).also { clip.sample(0f, it) }, exercise)
    private val vertices = ByteBuffer.allocateDirect(EDGES.size * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val mvp = FloatArray(16)
    private var program = 0
    private var position = 0
    private var transform = 0
    val bounds = FloatArray(6)

    fun createOnGlThread() {
        program = GlUtil.buildProgram("""
            uniform mat4 u_Mvp;
            attribute vec4 a_Position;
            void main() { gl_Position = u_Mvp * a_Position; gl_PointSize = 9.0; }
        """, """
            precision mediump float;
            void main() { gl_FragColor = vec4(0.3, 0.95, 0.8, 1.0); }
        """)
        position = GLES20.glGetAttribLocation(program, "a_Position")
        transform = GLES20.glGetUniformLocation(program, "u_Mvp")
    }

    fun draw(viewProjection: FloatArray, placement: FloatArray, seconds: Float,
        support: BodySupport? = ExerciseMotion.support(clip.exercise)) {
        clip.sample(seconds, landmarks)
        if (support != null) supportRig.apply(landmarks, support)
        val flight = ExerciseMotion.flight(clip.exercise, seconds, if (clip.loop) clip.durationSeconds else 0f)
        val ground = (if (support == null) EDGES.minOf { landmarks[it * 3 + 1] } else 0f) - flight
        for (a in 0..2) {
            val offset = if (a == 1) ground else 0f
            bounds[a] = EDGES.minOf { landmarks[it * 3 + a] } - offset - 0.08f
            bounds[a + 3] = EDGES.maxOf { landmarks[it * 3 + a] } - offset + 0.08f
        }
        vertices.clear()
        for (joint in EDGES) vertices.put(landmarks[joint * 3])
            .put(landmarks[joint * 3 + 1] - ground).put(landmarks[joint * 3 + 2])
        vertices.position(0)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, placement, 0)
        GLES20.glUseProgram(program)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glUniformMatrix4fv(transform, 1, false, mvp, 0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glLineWidth(1f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, EDGES.size)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, EDGES.size)
        GLES20.glDisableVertexAttribArray(position)
    }

    private companion object {
        val EDGES = intArrayOf(7, 8, 7, 11, 8, 12, 11, 12, 11, 13, 13, 15, 12, 14,
            14, 16, 11, 23, 12, 24, 23, 24, 23, 25, 25, 27, 27, 31, 24, 26, 26, 28, 28, 32)
    }
}
