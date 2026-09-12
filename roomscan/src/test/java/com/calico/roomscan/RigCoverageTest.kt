package com.calico.roomscan

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Checks that every model shipped in assets carries the bones the pose rig drives.
 *
 * Only the node names are read, not the geometry, so this still reports usefully on a
 * `.gltf` whose side-car buffer has not been added yet: the rig either matches the
 * skeleton or it does not, and that is worth knowing before the file reaches a phone.
 */
class RigCoverageTest {

    private val models = File("src/main/assets/models")

    /** Node names of a `.glb` or a `.gltf`, without touching the buffers. */
    private fun nodeNames(file: File): List<String> {
        val bytes = file.readBytes()
        val text = if (file.extension == "glb") {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val jsonLength = buffer.getInt(12)
            String(bytes, 20, jsonLength, Charsets.UTF_8)
        } else {
            String(bytes, Charsets.UTF_8)
        }
        val nodes = JSONObject(text).getJSONArray("nodes")
        return (0 until nodes.length()).map { nodes.getJSONObject(it).optString("name", "") }
    }

    @Test
    fun `every shipped model carries the bones the rig drives`() {
        val files = models.listFiles { f -> f.extension in setOf("glb", "gltf") }.orEmpty()
        assertTrue("no models found in ${models.absolutePath}", files.isNotEmpty())

        for (file in files) {
            val names = nodeNames(file).toSet()
            val missing = HumanRig.bones.filter { bone -> bone.nodeNames.none { it in names } }
            // Fingers and toes vary between rigs, so a couple of unmatched bones are normal;
            // a model missing most of them is not a humanoid this app can animate.
            assertTrue(
                "${file.name} is missing ${missing.size} of ${HumanRig.bones.size} rig bones: " +
                    missing.joinToString { it.nodeNames.first() },
                missing.size <= 2,
            )
            assertTrue("${file.name} has no hips", HumanRig.HIPS.any { it in names })
            assertTrue("${file.name} has no neck", HumanRig.NECK.any { it in names })
        }
    }

    @Test
    fun `a gltf with an external buffer has that buffer alongside it`() {
        val gltfs = models.listFiles { f -> f.extension == "gltf" }.orEmpty()
        for (file in gltfs) {
            val buffers = JSONObject(file.readText()).optJSONArray("buffers") ?: continue
            for (i in 0 until buffers.length()) {
                val uri = buffers.getJSONObject(i).optString("uri", "")
                if (uri.isEmpty() || uri.startsWith("data:")) continue
                assertTrue(
                    "${file.name} references $uri, which is not in ${models.name}. " +
                        "Copy it next to the .gltf, or re-export as a single .glb.",
                    File(file.parentFile, uri).exists(),
                )
            }
        }
    }
}
