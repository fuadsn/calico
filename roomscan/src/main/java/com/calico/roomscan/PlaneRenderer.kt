package com.calico.roomscan

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Plane
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** A detected plane together with the verdict on whether it is worth using. */
data class RatedPlane(
    val plane: Plane,
    val zone: WorkoutZone,
    val isBest: Boolean
)

/**
 * Shades each detected plane by how usable it is, so the user can see at a glance
 * which patches of floor are big enough to work out on.
 */
class PlaneRenderer {

    private var program = 0
    private var positionAttrib = 0
    private var mvpUniform = 0
    private var colorUniform = 0

    private var vertices: FloatBuffer = allocate(INITIAL_VERTEX_CAPACITY)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    fun createOnGlThread() {
        program = GlUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
    }

    fun draw(rated: List<RatedPlane>, viewProjection: FloatArray) {
        if (rated.isEmpty()) return

        GLES20.glUseProgram(program)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glEnableVertexAttribArray(positionAttrib)

        for (entry in rated) {
            val count = loadPolygon(entry.plane)
            if (count < 3) continue

            entry.plane.centerPose.toMatrix(modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, viewProjection, 0, modelMatrix, 0)
            GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)

            vertices.position(0)
            GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, vertices)

            val colour = colourFor(entry.zone.rating)

            setColour(colour, if (entry.isBest) BEST_FILL_ALPHA else FILL_ALPHA)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, count)

            GLES20.glLineWidth(1f)
            setColour(colour, EDGE_ALPHA)
            GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, count)
        }

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun setColour(colour: FloatArray, alpha: Float) {
        GLES20.glUniform4f(colorUniform, colour[0], colour[1], colour[2], alpha)
    }

    fun drawDepth(patch: HorizontalPatch, viewProjection: FloatArray) {
        val count = patch.outline.size / 2
        if (count < 3) return
        if (count * 3 > vertices.capacity()) vertices = allocate(count * 6)
        vertices.clear()
        for (i in 0 until count) {
            vertices.put(patch.outline[i * 2]).put(patch.height).put(patch.outline[i * 2 + 1])
        }
        vertices.position(0)
        GLES20.glUseProgram(program)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, viewProjection, 0)
        GLES20.glUniform4f(colorUniform, 0.15f, 0.7f, 1f, 0.18f)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, count)
        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun colourFor(rating: ZoneRating): FloatArray = when (rating) {
        ZoneRating.AMPLE -> AMPLE_COLOUR
        ZoneRating.STANDING_ONLY -> STANDING_COLOUR
        ZoneRating.TOO_SMALL -> TOO_SMALL_COLOUR
    }

    /** Expands the plane's 2D boundary into 3D vertices. Returns the vertex count. */
    private fun loadPolygon(plane: Plane): Int {
        val polygon = plane.polygon
        polygon.rewind()
        val count = polygon.limit() / 2
        if (count * FLOATS_PER_VERTEX > vertices.capacity()) {
            vertices = allocate(count * FLOATS_PER_VERTEX * 2)
        }
        vertices.clear()
        for (i in 0 until count) {
            // Plane-local coordinates arrive as x, z with y flat on the plane.
            vertices.put(polygon.get(i * 2))
            vertices.put(0f)
            vertices.put(polygon.get(i * 2 + 1))
        }
        vertices.position(0)
        return count
    }

    private fun allocate(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floats * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    private companion object {
        const val FLOAT_BYTES = 4
        const val FLOATS_PER_VERTEX = 3
        const val INITIAL_VERTEX_CAPACITY = 256 * FLOATS_PER_VERTEX

        const val FILL_ALPHA = 0.22f
        const val BEST_FILL_ALPHA = 0.38f
        const val EDGE_ALPHA = 0.95f

        val AMPLE_COLOUR = floatArrayOf(0.20f, 0.95f, 0.45f)
        val STANDING_COLOUR = floatArrayOf(1.00f, 0.72f, 0.20f)
        val TOO_SMALL_COLOUR = floatArrayOf(0.65f, 0.65f, 0.70f)

        const val VERTEX_SHADER = """
            uniform mat4 u_ModelViewProjection;
            attribute vec4 a_Position;
            varying vec2 v_Plane;
            void main() {
                v_Plane = a_Position.xz;
                gl_Position = u_ModelViewProjection * vec4(a_Position.xyz, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            varying vec2 v_Plane;
            void main() {
                vec2 cell = abs(fract(v_Plane * 5.0 - 0.5) - 0.5);
                float grid = 1.0 - step(0.025, min(cell.x, cell.y));
                gl_FragColor = vec4(u_Color.rgb, mix(u_Color.a, 0.8, grid));
            }
        """
    }
}
