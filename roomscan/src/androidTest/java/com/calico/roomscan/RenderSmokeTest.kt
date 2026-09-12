package com.calico.roomscan

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.Matrix
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.io.File

/** Exercise the real GLES driver: successful parsing alone does not prove visible geometry. */
class RenderSmokeTest {
    @Test fun rigAndSurfaceProduceVisiblePixels() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val versions = IntArray(2)
        assertTrue(EGL14.eglInitialize(display, versions, 0, versions, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0))
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, SIZE, EGL14.EGL_HEIGHT, SIZE, EGL14.EGL_NONE), 0)
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
        try {
            val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
            val projection = FloatArray(16)
            val view = FloatArray(16)
            val vp = FloatArray(16)
            Matrix.perspectiveM(projection, 0, 45f, 1f, 0.1f, 100f)
            Matrix.setLookAtM(view, 0, 2.2f, 1.6f, 3.2f, 0f, 0.7f, 0f, 0f, 1f, 0f)
            Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
            for (exercise in listOf("SQUAT", "PUSHUP", "JUMPING_JACK", "PULLUP", "HIGH_KNEES")) {
                val rig = SkinnedFigure.read(assets, DemoFigure.modelAssets.first(), exercise)
                rig.createOnGlThread()
                assertTrue(rig.isUsable)
                clear()
                rig.draw(vp, M4.identity(), 0.75f)
                verifyPixels("rig-$exercise")
                val fallback = PoseFigureRenderer(assets, exercise)
                fallback.createOnGlThread()
                clear()
                fallback.draw(vp, M4.identity(), 0.75f)
                verifyPixels("fallback-$exercise", 100)
            }
            val planes = PlaneRenderer()
            planes.createOnGlThread()
            clear()
            planes.drawDepth(HorizontalPatch(0f, floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f), 100), vp)
            verifyPixels("surface")
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private fun clear() {
        GLES20.glViewport(0, 0, SIZE, SIZE)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDepthMask(true)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
    }

    private fun verifyPixels(name: String, minimum: Int = 500) {
        assertEquals("GL error drawing $name", GLES20.GL_NO_ERROR, GLES20.glGetError())
        val pixels = ByteBuffer.allocateDirect(SIZE * SIZE * 4)
        GLES20.glReadPixels(0, 0, SIZE, SIZE, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        val visible = (0 until SIZE * SIZE).count { i ->
            (pixels.get(i * 4).toInt() and 255) > 20 ||
                (pixels.get(i * 4 + 1).toInt() and 255) > 20
        }
        assertTrue("$name only drew $visible pixels", visible > minimum)
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        pixels.rewind()
        bitmap.copyPixelsFromBuffer(pixels)
        // GL readback starts at the bottom; save images upright for visual review.
        val upright = Bitmap.createBitmap(bitmap, 0, 0, SIZE, SIZE,
            android.graphics.Matrix().apply { setScale(1f, -1f) }, false)
        val folder = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("render-tests")!!
        File(folder, "$name.png").outputStream().use { upright.compress(Bitmap.CompressFormat.PNG, 100, it) }
        upright.recycle()
        bitmap.recycle()
    }

    companion object { const val SIZE = 512 }
}
