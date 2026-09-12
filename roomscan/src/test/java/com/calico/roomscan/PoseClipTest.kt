package com.calico.roomscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PoseClipTest {

    private val clips = File("src/main/assets/clips")

    private fun frame(values: (Int) -> Double): String =
        (0 until Lm.COUNT * 3).joinToString(",") { values(it).toString() }

    @Test
    fun `mediapipe frames are flipped into rig axes on load`() {
        val clip = PoseClip.parse(
            """{"fps":1,"space":"mediapipe_world","frames":[[${frame { 1.0 }}]]}"""
        )
        val out = FloatArray(Lm.COUNT * 3)
        clip.sample(0f, out)
        assertEquals("x is unchanged", 1f, out[0], 1e-6f)
        assertEquals("y-down becomes y-up", -1f, out[1], 1e-6f)
        assertEquals("z flips with it, so handedness survives", -1f, out[2], 1e-6f)
    }

    @Test
    fun `a clip already in rig axes is left alone`() {
        val clip = PoseClip.parse("""{"fps":1,"space":"rig","frames":[[${frame { 1.0 }}]]}""")
        val out = FloatArray(Lm.COUNT * 3)
        clip.sample(0f, out)
        assertEquals(1f, out[1], 1e-6f)
    }

    @Test
    fun `sampling blends between frames and wraps at the end of the loop`() {
        val clip = PoseClip.parse(
            """{"fps":2,"loop":true,"space":"rig","frames":[[${frame { 0.0 }}],[${frame { 10.0 }}]]}"""
        )
        val out = FloatArray(Lm.COUNT * 3)
        clip.sample(0.25f, out)
        assertEquals("half way to the second frame", 5f, out[0], 1e-4f)
        clip.sample(0.75f, out)
        assertEquals("half way back to the first", 5f, out[0], 1e-4f)
        clip.sample(1.0f, out)
        assertEquals("a full cycle returns to the start", 0f, out[0], 1e-4f)
    }

    @Test
    fun `every shipped clip is a complete loop of 33 landmarks`() {
        val files = clips.listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue("no clips found in ${clips.absolutePath}", files.isNotEmpty())
        val out = FloatArray(Lm.COUNT * 3)
        for (file in files) {
            val clip = PoseClip.parse(file.readText())
            assertEquals("${file.name} is named after its file", file.nameWithoutExtension, clip.exercise)
            assertTrue("${file.name} has no frames", clip.frameCount > 1)
            assertTrue("${file.name} has no duration", clip.durationSeconds > 0f)
            // Reading past the end has to keep producing frames, since the preview loops forever.
            clip.sample(clip.durationSeconds * 3.5f, out)
            assertTrue("${file.name} produced a broken frame", out.all { it.isFinite() })
        }
    }
}
