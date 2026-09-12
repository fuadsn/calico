package com.calico.roomscan

import android.content.res.AssetManager
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A rigged glTF humanoid, posed each frame from a [PoseClip] and drawn with GPU skinning.
 *
 * Loading splits in two on purpose: [read] parses the files and can run on any thread,
 * while [createOnGlThread] touches GL and must run on the renderer's thread.
 *
 * Skinning uses one uniform matrix per joint, and GLES 2.0 only promises room for a few
 * dozen of those. Rigs from Blender carry a joint per finger bone, well past that budget,
 * so [JointPalette] retains fingers when the GPU budget permits, and folds excess bones
 * into their nearest retained parent on smaller devices.
 */
class SkinnedFigure(private val model: GltfModel, private val clip: PoseClip) {

    private val retargeter = PoseRetargeter(model)
    private var palette = JointPalette(model, retargeter)
    private val landmarks = FloatArray(Lm.COUNT * 3)
    private val contacts = ContactRig(model, clip.exercise, FloatArray(99).also { clip.sample(0f, it) })

    /** Uniform scale putting the model at a believable height whatever units it was authored in. */
    private val fitScale = retargeter.scaleToHeight(HEIGHT_M)

    private val drawables = mutableListOf<Drawable>()
    private var program = 0
    private var positionAttrib = 0
    private var normalAttrib = 0
    private var colorAttrib = 0
    private var jointAttrib = 0
    private var weightAttrib = 0
    private var mvpUniform = 0
    private var modelUniform = 0
    private var nodeUniform = 0
    private var skinnedUniform = 0
    private var jointsUniform = 0

    private var jointMatrices = FloatArray(palette.size * 16)
    private val placement = FloatArray(16)
    private val scaleMatrix = M4.scaling(fitScale)
    private val mvp = FloatArray(16)

    val isUsable get() = retargeter.isUsable && drawables.isNotEmpty()
    val bounds = FloatArray(6)

    fun createOnGlThread() {
        drawables.clear()
        val limits = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_VERTEX_UNIFORM_VECTORS, limits, 0)
        // Three mat4 uniforms plus one scalar, with additional driver headroom.
        palette = JointPalette(model, retargeter, ((limits[0] - 20) / 4).coerceAtLeast(1))
        jointMatrices = FloatArray(palette.size * 16)
        program = GlUtil.buildProgram(
            VERTEX_SHADER.replace(JOINT_COUNT_TOKEN, palette.size.coerceAtLeast(1).toString()),
            FRAGMENT_SHADER,
        )
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        normalAttrib = GLES20.glGetAttribLocation(program, "a_Normal")
        colorAttrib = GLES20.glGetAttribLocation(program, "a_Color")
        jointAttrib = GLES20.glGetAttribLocation(program, "a_Joints")
        weightAttrib = GLES20.glGetAttribLocation(program, "a_Weights")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
        modelUniform = GLES20.glGetUniformLocation(program, "u_Model")
        nodeUniform = GLES20.glGetUniformLocation(program, "u_Node")
        skinnedUniform = GLES20.glGetUniformLocation(program, "u_Skinned")
        jointsUniform = GLES20.glGetUniformLocation(program, "u_Joints")

        model.nodes.forEachIndexed { nodeIndex, node ->
            if (node.mesh < 0 || node.mesh >= model.meshes.size) return@forEachIndexed
            if (model.meshes[node.mesh].name in SKIPPED_MESHES) return@forEachIndexed
            for (primitive in model.meshes[node.mesh].primitives) {
                if (primitive.vertexCount == 0) continue
                drawables += Drawable(nodeIndex, primitive, node.skin >= 0)
            }
        }
    }

    /**
     * @param viewProjection camera matrix for the frame.
     * @param anchor where the figure stands, Y up, usually a detected plane.
     * @param seconds playback position in the clip.
     */
    fun draw(viewProjection: FloatArray, anchor: FloatArray, seconds: Float,
        support: BodySupport? = ExerciseMotion.support(clip.exercise)) {
        if (drawables.isEmpty()) return
        clip.sample(seconds, landmarks)
        retargeter.pose(landmarks, dropToGround = !contacts.enabled, handGrip = when (clip.exercise) {
            "PULLUP" -> 0.9f
            "HIGH_KNEES" -> 0.55f
            "PUSHUP", "INCLINE_PUSHUP", "PIKE_PUSHUP", "MOUNTAIN_CLIMBER", "DIP", "PLANK" -> 0f
            else -> 0.22f
        })
        contacts.apply(retargeter, landmarks, seconds, clip.durationSeconds, support)
        val flight = ExerciseMotion.flight(clip.exercise, seconds, clip.durationSeconds) / fitScale
        for (m in retargeter.globals) m[13] += flight
        for (a in 0..2) {
            bounds[a] = retargeter.globals.minOf { it[12 + a] } * fitScale - 0.08f
            bounds[a + 3] = retargeter.globals.maxOf { it[12 + a] } * fitScale + 0.08f
        }
        palette.fill(retargeter, jointMatrices)

        M4.multiplyInto(anchor, scaleMatrix, placement)
        M4.multiplyInto(viewProjection, placement, mvp)

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(modelUniform, 1, false, placement, 0)
        if (palette.size > 0) GLES20.glUniformMatrix4fv(jointsUniform, palette.size, false, jointMatrices, 0)

        for (attrib in intArrayOf(positionAttrib, normalAttrib, colorAttrib, jointAttrib, weightAttrib)) {
            if (attrib >= 0) GLES20.glEnableVertexAttribArray(attrib)
        }
        for (drawable in drawables) drawable.draw()
        for (attrib in intArrayOf(positionAttrib, normalAttrib, colorAttrib, jointAttrib, weightAttrib)) {
            if (attrib >= 0) GLES20.glDisableVertexAttribArray(attrib)
        }
    }

    private inner class Drawable(
        private val nodeIndex: Int,
        primitive: GltfPrimitive,
        private val skinned: Boolean,
    ) {
        private val count = primitive.indices.size
        private val nodeMatrix = retargeter.globals[nodeIndex].copyOf()

        // Every attribute is fixed for the life of the figure, since the pose is applied by
        // the joint matrices rather than by rewriting vertices, so they upload once.
        private val positions = upload(expand(primitive.positions, primitive.indices, 3))
        private val normals = upload(
            if (primitive.normals.isEmpty()) FloatArray(count * 3) { if (it % 3 == 1) 1f else 0f }
            else expand(primitive.normals, primitive.indices, 3)
        )
        private val colors = upload(baseColors(primitive))
        private val joints = upload(
            if (primitive.joints.isEmpty()) FloatArray(count * 4)
            else expand(FloatArray(primitive.joints.size) { palette.remap(primitive.joints[it]).toFloat() },
                primitive.indices, 4)
        )
        private val weights = upload(
            if (primitive.weights.isEmpty()) FloatArray(count * 4)
            else expand(palette.mergedWeights(primitive), primitive.indices, 4)
        )

        /** Per-vertex colour, falling back to the material's base colour then to a neutral skin tone. */
        private fun baseColors(primitive: GltfPrimitive): FloatArray {
            if (primitive.colors.isNotEmpty()) {
                val stride = primitive.colors.size / primitive.vertexCount
                val rgb = FloatArray(primitive.vertexCount * 3) { i ->
                    primitive.colors[(i / 3) * stride + i % 3]
                }
                return expand(rgb, primitive.indices, 3)
            }
            val material = model.materials.getOrNull(primitive.material)
            val base = material?.baseColor ?: DEFAULT_COLOR
            return FloatArray(count * 3) { base[it % 3] }
        }

        fun draw() {
            bind(positionAttrib, positions, 3)
            bind(normalAttrib, normals, 3)
            bind(colorAttrib, colors, 3)
            bind(jointAttrib, joints, 4)
            bind(weightAttrib, weights, 4)
            GLES20.glUniform1f(skinnedUniform, if (skinned) 1f else 0f)
            // An unskinned mesh, such as an eye parented to the head bone, still has to
            // follow its bone, so its node matrix is re-read from the posed skeleton.
            if (!skinned) {
                System.arraycopy(retargeter.globals[nodeIndex], 0, nodeMatrix, 0, 16)
                GLES20.glUniformMatrix4fv(nodeUniform, 1, false, nodeMatrix, 0)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }

        private fun bind(attrib: Int, buffer: Int, components: Int) {
            if (attrib < 0) return
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer)
            GLES20.glVertexAttribPointer(attrib, components, GLES20.GL_FLOAT, false, 0, 0)
        }
    }

    companion object {
        /** How tall the avatar stands, regardless of the units the model was authored in. */
        const val HEIGHT_M = 1.75f

        /** Ground planes and backdrops some exports carry; they would hide the AR camera feed. */
        private val SKIPPED_MESHES = setOf("Plane", "Ground", "Floor", "Backdrop")

        private val DEFAULT_COLOR = floatArrayOf(0.78f, 0.68f, 0.62f, 1f)
        private const val JOINT_COUNT_TOKEN = "JOINT_COUNT"

        /** Indexed geometry flattened to a triangle list, which keeps the draw path simple. */
        private fun expand(source: FloatArray, indices: IntArray, components: Int): FloatArray {
            val out = FloatArray(indices.size * components)
            for (i in indices.indices) {
                val from = indices[i] * components
                for (c in 0 until components) {
                    out[i * components + c] = if (from + c < source.size) source[from + c] else 0f
                }
            }
            return out
        }

        /** Copies an attribute into a vertex buffer object and returns its name. */
        private fun upload(data: FloatArray): Int {
            val buffer = ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(data)
                    position(0)
                }
            val name = IntArray(1)
            GLES20.glGenBuffers(1, name, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, name[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, buffer, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            return name[0]
        }

        /**
         * Reads a model and the clip for one exercise out of the app's assets.
         * A `.gltf` with a side-car buffer and a self-contained `.glb` are both accepted.
         */
        fun read(assets: AssetManager, modelPath: String, exercise: String): SkinnedFigure {
            val folder = modelPath.substringBeforeLast('/', "")
            val gltf = GltfModel.parse(assets.open(modelPath).use { it.readBytes() }) { uri ->
                assets.open(if (folder.isEmpty()) uri else "$folder/$uri").use { it.readBytes() }
            }
            require(gltf.skins.size == 1 && gltf.nodes.any { node ->
                node.skin == 0 && gltf.meshes.getOrNull(node.mesh)?.primitives?.any {
                    it.vertexCount > 0 && it.joints.isNotEmpty() && it.weights.isNotEmpty()
                } == true
            }) { "Model must contain one skin and a weighted mesh: $modelPath" }
            val clip = MotionAssets.read(assets, exercise)
            return SkinnedFigure(gltf, clip).also {
                require(it.retargeter.isUsable) { "Model has no compatible humanoid rig: $modelPath" }
                require(!ExerciseMotion.needsRaisedSupport(exercise) || it.contacts.enabled) {
                    "Model cannot constrain the required surface contacts: $modelPath"
                }
            }
        }

        private const val VERTEX_SHADER = """
            uniform mat4 u_ModelViewProjection;
            uniform mat4 u_Model;
            uniform mat4 u_Node;
            uniform float u_Skinned;
            uniform mat4 u_Joints[JOINT_COUNT];
            attribute vec4 a_Position;
            attribute vec3 a_Normal;
            attribute vec3 a_Color;
            attribute vec4 a_Joints;
            attribute vec4 a_Weights;
            varying vec3 v_Color;
            varying float v_Shade;
            void main() {
                mat4 bone = u_Node;
                if (u_Skinned > 0.5) {
                    bone = a_Weights.x * u_Joints[int(a_Joints.x)]
                         + a_Weights.y * u_Joints[int(a_Joints.y)]
                         + a_Weights.z * u_Joints[int(a_Joints.z)]
                         + a_Weights.w * u_Joints[int(a_Joints.w)];
                }
                gl_Position = u_ModelViewProjection * bone * a_Position;
                vec3 worldNormal = normalize(mat3(u_Model) * mat3(bone) * a_Normal);
                vec3 light = normalize(vec3(0.35, 1.0, 0.55));
                v_Shade = 0.45 + 0.55 * max(dot(worldNormal, light), 0.0);
                v_Color = a_Color;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 v_Color;
            varying float v_Shade;
            void main() {
                gl_FragColor = vec4(v_Color * v_Shade, 1.0);
            }
        """
    }
}
