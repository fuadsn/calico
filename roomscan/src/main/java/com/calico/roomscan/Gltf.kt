package com.calico.roomscan

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** One node of the glTF scene graph. A node is a bone when a skin lists it as a joint. */
class GltfNode(
    val name: String,
    val children: IntArray,
    val translation: FloatArray,
    val rotation: FloatArray,
    val scale: FloatArray,
    val mesh: Int,
    val skin: Int,
) {
    val localMatrix: FloatArray get() = M4.trs(translation, rotation, scale)
}

/**
 * One draw call worth of geometry. Attributes the file omits come back empty, and
 * [joints]/[weights] are empty for meshes that are not skinned.
 */
class GltfPrimitive(
    val positions: FloatArray,
    val normals: FloatArray,
    val colors: FloatArray,
    val joints: IntArray,
    val weights: FloatArray,
    val indices: IntArray,
    val material: Int,
) {
    val vertexCount get() = positions.size / 3
}

class GltfMesh(val name: String, val primitives: List<GltfPrimitive>)

class GltfSkin(val joints: IntArray, val inverseBind: Array<FloatArray>)

class GltfMaterial(val baseColor: FloatArray)

/**
 * The parts of a glTF 2.0 file this app draws: hierarchy, geometry, skin and base colours.
 * Animations in the file are ignored on purpose, since the avatar is driven by pose clips.
 *
 * Both container forms are accepted. A `.glb` carries its buffer inline; a `.gltf` points at
 * an external `.bin` or a `data:` URI, and [resolveUri] fetches the former.
 */
class GltfModel(
    val nodes: List<GltfNode>,
    val meshes: List<GltfMesh>,
    val skins: List<GltfSkin>,
    val materials: List<GltfMaterial>,
    val roots: IntArray,
) {
    /** Index of each node's parent, or -1 for a root. */
    val parents: IntArray = IntArray(nodes.size) { -1 }.also { out ->
        nodes.forEachIndexed { i, node -> node.children.forEach { out[it] = i } }
    }

    /** Node indices in an order where every parent precedes its children. */
    val hierarchyOrder: IntArray = buildList {
        val stack = ArrayDeque(roots.toList())
        while (stack.isNotEmpty()) {
            val i = stack.removeFirst()
            add(i)
            nodes[i].children.forEach { stack.addLast(it) }
        }
    }.toIntArray()

    fun findNode(name: String) = nodes.indexOfFirst { it.name == name }

    /** World matrix of every node in the file's own rest pose. */
    fun restGlobals(): Array<FloatArray> {
        val out = Array(nodes.size) { M4.identity() }
        for (i in hierarchyOrder) {
            val parent = parents[i]
            out[i] = if (parent < 0) nodes[i].localMatrix else M4.multiply(out[parent], nodes[i].localMatrix)
        }
        return out
    }

    /** Axis aligned bounds of every mesh, posed by its node, as (min, max). */
    fun bounds(globals: Array<FloatArray> = restGlobals()): Pair<FloatArray, FloatArray> {
        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        nodes.forEachIndexed { i, node ->
            if (node.mesh < 0) return@forEachIndexed
            for (prim in meshes[node.mesh].primitives) {
                for (v in 0 until prim.vertexCount) {
                    val p = M4.transformPoint(
                        globals[i],
                        floatArrayOf(prim.positions[v * 3], prim.positions[v * 3 + 1], prim.positions[v * 3 + 2]),
                    )
                    for (c in 0 until 3) {
                        if (p[c] < min[c]) min[c] = p[c]
                        if (p[c] > max[c]) max[c] = p[c]
                    }
                }
            }
        }
        return min to max
    }

    companion object {
        private const val GLB_MAGIC = 0x46546C67
        private const val CHUNK_JSON = 0x4E4F534A
        private const val CHUNK_BIN = 0x004E4942

        /**
         * @param bytes the whole `.glb` or `.gltf` file.
         * @param resolveUri loads a file sitting next to the glTF, by its relative URI.
         */
        fun parse(bytes: ByteArray, resolveUri: (String) -> ByteArray = { error("no loader for $it") }): GltfModel {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return if (bytes.size >= 12 && buf.getInt(0) == GLB_MAGIC) parseGlb(buf, resolveUri)
            else parseJson(JSONObject(String(bytes, Charsets.UTF_8)), null, resolveUri)
        }

        private fun parseGlb(buf: ByteBuffer, resolveUri: (String) -> ByteArray): GltfModel {
            var offset = 12
            var json: JSONObject? = null
            var bin: ByteBuffer? = null
            while (offset + 8 <= buf.capacity()) {
                val length = buf.getInt(offset)
                val type = buf.getInt(offset + 4)
                val start = offset + 8
                when (type) {
                    CHUNK_JSON -> {
                        val slice = ByteArray(length)
                        buf.position(start); buf.get(slice)
                        json = JSONObject(String(slice, Charsets.UTF_8))
                    }
                    CHUNK_BIN -> {
                        val slice = ByteArray(length)
                        buf.position(start); buf.get(slice)
                        bin = ByteBuffer.wrap(slice).order(ByteOrder.LITTLE_ENDIAN)
                    }
                }
                offset = start + length + ((4 - length % 4) % 4)
            }
            return parseJson(requireNotNull(json) { "glb has no JSON chunk" }, bin, resolveUri)
        }

        private fun parseJson(root: JSONObject, glbBin: ByteBuffer?, resolveUri: (String) -> ByteArray): GltfModel {
            val buffers = root.optJSONArray("buffers").list().map { spec ->
                val uri = spec.optString("uri", "")
                val data = when {
                    uri.isEmpty() -> requireNotNull(glbBin) { "buffer has no uri and the file has no BIN chunk" }
                    uri.startsWith("data:") ->
                        ByteBuffer.wrap(Base64.getDecoder().decode(uri.substringAfter("base64,")))
                            .order(ByteOrder.LITTLE_ENDIAN)
                    else -> ByteBuffer.wrap(resolveUri(uri)).order(ByteOrder.LITTLE_ENDIAN)
                }
                data
            }
            val views = root.optJSONArray("bufferViews").list()
            val accessors = root.optJSONArray("accessors").list()

            val reader = AccessorReader(buffers, views, accessors)

            val nodes = root.optJSONArray("nodes").list().map { spec ->
                val matrix = spec.optJSONArray("matrix")
                var translation = spec.optJSONArray("translation").floats(floatArrayOf(0f, 0f, 0f))
                var rotation = spec.optJSONArray("rotation").floats(floatArrayOf(0f, 0f, 0f, 1f))
                var scale = spec.optJSONArray("scale").floats(floatArrayOf(1f, 1f, 1f))
                if (matrix != null) {
                    val m = matrix.floats(M4.identity())
                    translation = M4.translation(m)
                    rotation = Quat.fromMatrix(m)
                    scale = floatArrayOf(
                        V3.length(floatArrayOf(m[0], m[1], m[2])),
                        V3.length(floatArrayOf(m[4], m[5], m[6])),
                        V3.length(floatArrayOf(m[8], m[9], m[10])),
                    )
                }
                GltfNode(
                    name = spec.optString("name", ""),
                    children = spec.optJSONArray("children").ints(),
                    translation = translation,
                    rotation = rotation,
                    scale = scale,
                    mesh = spec.optInt("mesh", -1),
                    skin = spec.optInt("skin", -1),
                )
            }

            val meshes = root.optJSONArray("meshes").list().map { spec ->
                GltfMesh(
                    name = spec.optString("name", ""),
                    primitives = spec.optJSONArray("primitives").list()
                        // mode 4 is GL_TRIANGLES; the renderer draws nothing else.
                        .filter { it.optInt("mode", 4) == 4 }
                        .map { prim ->
                            val attributes = prim.optJSONObject("attributes") ?: JSONObject()
                            fun floats(name: String) =
                                if (attributes.has(name)) reader.floats(attributes.getInt(name)) else FloatArray(0)
                            val positions = floats("POSITION")
                            val indices = if (prim.has("indices")) reader.ints(prim.getInt("indices"))
                            else IntArray(positions.size / 3) { it }
                            GltfPrimitive(
                                positions = positions,
                                normals = floats("NORMAL"),
                                colors = floats("COLOR_0"),
                                joints = if (attributes.has("JOINTS_0")) reader.ints(attributes.getInt("JOINTS_0"))
                                else IntArray(0),
                                weights = floats("WEIGHTS_0"),
                                indices = indices,
                                material = prim.optInt("material", -1),
                            )
                        },
                )
            }

            val skins = root.optJSONArray("skins").list().map { spec ->
                val joints = spec.optJSONArray("joints").ints()
                val flat = if (spec.has("inverseBindMatrices")) reader.floats(spec.getInt("inverseBindMatrices"))
                else FloatArray(0)
                GltfSkin(
                    joints = joints,
                    inverseBind = Array(joints.size) { j ->
                        if (flat.size >= (j + 1) * 16) flat.copyOfRange(j * 16, j * 16 + 16) else M4.identity()
                    },
                )
            }

            val materials = root.optJSONArray("materials").list().map { spec ->
                val pbr = spec.optJSONObject("pbrMetallicRoughness")
                GltfMaterial(pbr?.optJSONArray("baseColorFactor").floats(floatArrayOf(1f, 1f, 1f, 1f)))
            }

            val sceneIndex = root.optInt("scene", 0)
            val scenes = root.optJSONArray("scenes").list()
            val roots = scenes.getOrNull(sceneIndex)?.optJSONArray("nodes").ints()

            return GltfModel(nodes, meshes, skins, materials, if (roots.isEmpty()) IntArray(0) else roots)
        }
    }
}

/** Pulls typed elements out of a glTF accessor, honouring byte strides and component types. */
private class AccessorReader(
    private val buffers: List<ByteBuffer>,
    private val views: List<JSONObject>,
    private val accessors: List<JSONObject>,
) {
    fun floats(index: Int): FloatArray {
        val accessor = accessors[index]
        val components = componentsOf(accessor.getString("type"))
        val count = accessor.getInt("count")
        val out = FloatArray(count * components)
        forEach(accessor) { element, offset, buffer, componentType, size ->
            for (c in 0 until components) {
                out[element * components + c] = readFloat(buffer, offset + c * size, componentType)
            }
        }
        return out
    }

    fun ints(index: Int): IntArray {
        val accessor = accessors[index]
        val components = componentsOf(accessor.getString("type"))
        val count = accessor.getInt("count")
        val out = IntArray(count * components)
        forEach(accessor) { element, offset, buffer, componentType, size ->
            for (c in 0 until components) {
                out[element * components + c] = readInt(buffer, offset + c * size, componentType)
            }
        }
        return out
    }

    private inline fun forEach(
        accessor: JSONObject,
        body: (element: Int, offset: Int, buffer: ByteBuffer, componentType: Int, componentSize: Int) -> Unit,
    ) {
        val view = views[accessor.getInt("bufferView")]
        val buffer = buffers[view.optInt("buffer", 0)]
        val componentType = accessor.getInt("componentType")
        val componentSize = sizeOf(componentType)
        val components = componentsOf(accessor.getString("type"))
        val base = view.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
        val stride = view.optInt("byteStride", 0).takeIf { it > 0 } ?: (componentSize * components)
        for (element in 0 until accessor.getInt("count")) {
            body(element, base + element * stride, buffer, componentType, componentSize)
        }
    }

    /** Integer component types are normalised to 0..1 the way glTF specifies. */
    private fun readFloat(buffer: ByteBuffer, at: Int, componentType: Int): Float = when (componentType) {
        FLOAT -> buffer.getFloat(at)
        UNSIGNED_BYTE -> (buffer.get(at).toInt() and 0xFF) / 255f
        BYTE -> (buffer.get(at).toInt() / 127f).coerceAtLeast(-1f)
        UNSIGNED_SHORT -> (buffer.getShort(at).toInt() and 0xFFFF) / 65535f
        SHORT -> (buffer.getShort(at) / 32767f).coerceAtLeast(-1f)
        else -> buffer.getInt(at).toFloat()
    }

    private fun readInt(buffer: ByteBuffer, at: Int, componentType: Int): Int = when (componentType) {
        UNSIGNED_BYTE, BYTE -> buffer.get(at).toInt() and 0xFF
        UNSIGNED_SHORT, SHORT -> buffer.getShort(at).toInt() and 0xFFFF
        FLOAT -> buffer.getFloat(at).toInt()
        else -> buffer.getInt(at)
    }

    private fun componentsOf(type: String) = when (type) {
        "SCALAR" -> 1
        "VEC2" -> 2
        "VEC3" -> 3
        "VEC4" -> 4
        "MAT4" -> 16
        else -> error("unsupported accessor type $type")
    }

    private fun sizeOf(componentType: Int) = when (componentType) {
        BYTE, UNSIGNED_BYTE -> 1
        SHORT, UNSIGNED_SHORT -> 2
        else -> 4
    }

    private companion object {
        const val BYTE = 5120
        const val UNSIGNED_BYTE = 5121
        const val SHORT = 5122
        const val UNSIGNED_SHORT = 5123
        const val FLOAT = 5126
    }
}

// ---- small JSON helpers, so the parser above stays readable ----

private fun JSONArray?.list(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

private fun JSONArray?.ints(): IntArray =
    if (this == null) IntArray(0) else IntArray(length()) { getInt(it) }

private fun JSONArray?.floats(fallback: FloatArray): FloatArray =
    if (this == null) fallback else FloatArray(length()) { getDouble(it).toFloat() }
