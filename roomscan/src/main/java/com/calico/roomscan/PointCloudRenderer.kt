package com.calico.roomscan

import android.opengl.GLES20
import com.google.ar.core.PointCloud

/**
 * Draws the ARCore feature points so the user can see the scan picking up detail.
 */
class PointCloudRenderer {

    private var program = 0
    private var positionAttrib = 0
    private var mvpUniform = 0
    private var colorUniform = 0
    private var pointSizeUniform = 0

    private var vbo = 0
    private var vboCapacityFloats = 0
    private var pointCount = 0
    private var lastTimestamp = 0L

    /** Feature points currently held, for on-screen progress feedback. */
    val visiblePoints: Int
        get() = pointCount

    fun createOnGlThread() {
        lastTimestamp = 0L
        pointCount = 0
        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        vbo = buffers[0]
        vboCapacityFloats = INITIAL_CAPACITY_FLOATS
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER, vboCapacityFloats * FLOAT_BYTES, null, GLES20.GL_DYNAMIC_DRAW
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        program = GlUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
        pointSizeUniform = GLES20.glGetUniformLocation(program, "u_PointSize")
    }

    /** Uploads a new cloud only when ARCore reports a fresh one. */
    fun update(cloud: PointCloud) {
        if (cloud.timestamp == lastTimestamp) return
        lastTimestamp = cloud.timestamp

        val points = cloud.points
        points.rewind()
        // ARCore packs each point as x, y, z, confidence.
        pointCount = points.remaining() / FLOATS_PER_POINT

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        val neededFloats = pointCount * FLOATS_PER_POINT
        if (neededFloats > vboCapacityFloats) {
            while (neededFloats > vboCapacityFloats) vboCapacityFloats *= 2
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER, vboCapacityFloats * FLOAT_BYTES, null, GLES20.GL_DYNAMIC_DRAW
            )
        }
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, neededFloats * FLOAT_BYTES, points)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun draw(viewProjection: FloatArray) {
        if (pointCount == 0) return

        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glVertexAttribPointer(
            positionAttrib, 4, GLES20.GL_FLOAT, false, FLOATS_PER_POINT * FLOAT_BYTES, 0
        )
        GLES20.glUniform4f(colorUniform, 0.4f, 0.9f, 1.0f, 1.0f)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, viewProjection, 0)
        GLES20.glUniform1f(pointSizeUniform, 6.0f)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pointCount)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private companion object {
        const val FLOAT_BYTES = 4
        const val FLOATS_PER_POINT = 4
        const val INITIAL_CAPACITY_FLOATS = 1000 * FLOATS_PER_POINT

        const val VERTEX_SHADER = """
            uniform mat4 u_ModelViewProjection;
            uniform float u_PointSize;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_ModelViewProjection * vec4(a_Position.xyz, 1.0);
                gl_PointSize = u_PointSize;
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """
    }
}
