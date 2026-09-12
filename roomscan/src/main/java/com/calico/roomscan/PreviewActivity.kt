package com.calico.roomscan

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shows one exercise as a life-size animated figure before the user tries it.
 *
 * Reached from the exercise picker. The figure stands on a detected floor plane and
 * demonstrates the movement on a loop, so the user can walk around it and see the shape of
 * the rep from any angle. Tapping the floor moves it.
 *
 * Two things can be missing on a given phone, and neither should end up as a dead screen:
 * ARCore, in which case the figure is shown on a plain backdrop that the user can spin, and
 * the rigged model, in which case a clip-driven [PoseFigureRenderer] stands in.
 *
 * Intent extras: `exercise` = the clip to play, defaults to PUSHUP.
 */
class PreviewActivity : Activity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var titleText: TextView
    private lateinit var hintText: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var actionBar: LinearLayout

    private lateinit var insetLabel: TextView

    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val pointCloudRenderer = PointCloudRenderer()
    private lateinit var fallbackAvatar: PoseFigureRenderer
    private val depthSurfaces = DepthSurfaces()
    private val shadow = ContactShadow()
    private val insetCard = InsetCard()

    /** Demo card as left, top, width, height in view pixels; laid out on the UI thread, read on GL. */
    @Volatile private var insetRect: IntArray? = null
    private var insetDrawn = false
    private var insetLabelShown = false

    private var figure: SkinnedFigure? = null

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewProjectionMatrix = FloatArray(16)
    private val standMatrix = FloatArray(16)
    private val shadowMatrix = FloatArray(16)
    private val supportProjection = FloatArray(16)
    private val supportRotation = FloatArray(16)
    private val projectedCenter = FloatArray(4)
    private val worldCenter = floatArrayOf(0f, 0f, 0f, 1f)

    private var session: Session? = null
    private var installRequested = false
    private var anchor: Anchor? = null
    private var anchorsFrom: Session? = null
    private var bodySupport: ArBodySupport? = null
    private var supportLostAt = 0L
    private val pendingTap = java.util.concurrent.atomic.AtomicReference<FloatArray?>()
    private var placementYaw = 0f
    private val interaction = FigureInteraction()

    private var viewportWidth = 0
    private var viewportHeight = 0
    private var viewportChanged = false
    private var arMode = false
    private var lastHint = ""
    private var lastPlacementAttempt = 0L

    private val startedAtMs = SystemClock.uptimeMillis()
    private var exercise = DEFAULT_EXERCISE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)
        exercise = intent.getStringExtra("exercise")?.takeIf { it.isNotBlank() } ?: DEFAULT_EXERCISE

        surfaceView = findViewById(R.id.surface)
        titleText = findViewById(R.id.title)
        hintText = findViewById(R.id.hint)
        topBar = findViewById(R.id.topBar)
        findViewById<View>(R.id.credits).setOnClickListener {
            val credits = TextView(this).apply {
                text = android.text.Html.fromHtml(getString(R.string.model_credits_details), android.text.Html.FROM_HTML_MODE_LEGACY)
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                setPadding(32, 24, 32, 24)
            }
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.model_credits).setView(credits)
                .setPositiveButton(android.R.string.ok, null).show()
        }
        actionBar = findViewById(R.id.actions)
        insetLabel = findViewById(R.id.insetLabel)
        titleText.text = exercise.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
        figure = DemoFigure.read(assets, exercise)
        fallbackAvatar = PoseFigureRenderer(assets, exercise)
        // Any layout pass can move the sheet (hints change length), so re-centre the card on each.
        findViewById<View>(R.id.root).viewTreeObserver.addOnGlobalLayoutListener { layoutInset() }

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        // Stencil bits clip the demo card's 3D content to its rounded corners.
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 8)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        interaction.attach(surfaceView) { x, y -> pendingTap.set(floatArrayOf(x, y)) }

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<Button>(R.id.start).setOnClickListener { startWorkout() }
        applyWindowInsets()

        if (figure == null) Log.w(TAG, "No rigged model loaded, falling back to the block figure")
    }

    private fun startWorkout() {
        try {
            startActivity(
                Intent().setClassName(this, WORKOUT_ACTIVITY).putExtra("exercise", exercise)
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Workout screen not found", e)
            hintText.text = getString(R.string.scan_workout_missing)
        }
    }

    /** targetSdk 36 draws edge to edge, so the bars have to clear the cutout themselves. */
    private fun applyWindowInsets() {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val gap = (20 * density).toInt()
        window.decorView.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            topBar.setPadding(pad, top + pad, pad, pad * 2)
            // The sheet runs to the screen edge; only its content clears the gesture bar.
            actionBar.setPadding(actionBar.paddingLeft, actionBar.paddingTop, actionBar.paddingRight, bottom + gap)
            insets
        }
    }

    /** Centres the demo card in the space between the header text and the bottom sheet. */
    private fun layoutInset() {
        val density = resources.displayMetrics.density
        val root = findViewById<View>(R.id.root)
        val top = topBar.bottom - topBar.paddingBottom
        val bottom = actionBar.top
        val available = bottom - top - (48 * density).toInt()
        val width = minOf(root.width - (48 * density).toInt(), (340 * density).toInt())
        val height = minOf(available, (width * 1.2f).toInt())
        if (width <= 0 || height <= 0) { insetRect = null; return }
        val left = (root.width - width) / 2
        val cardTop = top + (bottom - top - height) / 2 + (8 * density).toInt()
        insetRect = intArrayOf(left, cardTop, width, height)
        insetLabel.translationY = cardTop - insetLabel.height / 2f
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
            surfaceView.onResume()
            return
        }
        createSession()
        // RoomSession closes the map after 30 s in the background; anchors from a closed session are dead.
        if (session != null && session !== anchorsFrom) {
            anchor = null
            bodySupport = null
            anchorsFrom = session
        }
        arMode = session != null
        viewportChanged = true
        depthSurfaces.reset()
        surfaceView.onResume()
    }

    /** Leaves [session] null when AR is unavailable, which switches the screen to the plain view. */
    private fun createSession() {
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
            session = RoomSession.acquire(this, this)
        } catch (e: CameraNotAvailableException) {
            Log.w(TAG, "Camera unavailable, showing the 3D demo", e)
            session = null
        } catch (e: UnavailableException) {
            Log.w(TAG, "ARCore unavailable, showing the figure without AR", e)
            session = null
        }
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        interaction.suspend()
        pendingTap.set(null)
        // Anchors stay valid while the session is paused, so coming back from the workout screen
        // keeps the figure exactly where it was instead of re-placing it somewhere new.
        supportLostAt = 0L
        RoomSession.release(this)
        session = null
        arMode = false
    }

    override fun onDestroy() {
        try {
            anchor?.detach()
            bodySupport?.detach()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Room session already closed", e)
        }
        anchor = null
        bodySupport = null
        session = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Without the camera the demonstration still works, just without the room behind it.
        if (requestCode == CAMERA_REQUEST) postHint(getString(R.string.preview_no_ar))
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(BACKDROP, BACKDROP, BACKDROP * 1.1f, 1f)
        backgroundRenderer.createOnGlThread()
        planeRenderer.createOnGlThread()
        pointCloudRenderer.createOnGlThread()
        fallbackAvatar.createOnGlThread()
        shadow.createOnGlThread()
        insetCard.createOnGlThread()
        figure = DemoFigure.prepare(figure)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val seconds = (SystemClock.uptimeMillis() - startedAtMs) / 1000f
        interaction.advance(seconds)
        insetDrawn = false
        if (arMode) drawAugmented(seconds) else {
            pendingTap.set(null)
            drawPlain(seconds)
            postHint(getString(R.string.preview_no_ar) + "\n" + getString(R.string.figure_interaction))
        }
        if (insetDrawn != insetLabelShown) {
            insetLabelShown = insetDrawn
            val visibility = if (insetDrawn) View.VISIBLE else View.INVISIBLE
            runOnUiThread { insetLabel.visibility = visibility }
        }
    }

    private fun drawAugmented(seconds: Float) {
        val current = session ?: return
        if (viewportChanged) {
            current.setDisplayGeometry(displayRotation(), viewportWidth, viewportHeight)
            viewportChanged = false
        }
        current.setCameraTextureName(backgroundRenderer.textureId)

        val frame = try {
            current.update()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Lost camera during update", e)
            interaction.suspend()
            drawPlain(seconds)
            postHint(getString(R.string.scan_camera_unavailable))
            return
        }
        backgroundRenderer.draw(frame)

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            interaction.suspend()
            pendingTap.set(null)
            drawDemoInset(seconds)
            postHint(getString(ArFloor.trackingHint(camera.trackingFailureReason)))
            return
        }
        camera.getProjectionMatrix(projectionMatrix, 0, NEAR_PLANE_M, FAR_PLANE_M)
        camera.getViewMatrix(viewMatrix, 0)
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        val surfaces = ArFloor.surfaces(current)
        val horizontal = surfaces.filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
        val floors = ArFloor.candidates(horizontal, camera.pose.ty())
        frame.acquirePointCloud().use { pointCloudRenderer.update(it) }
        pointCloudRenderer.draw(viewProjectionMatrix)
        planeRenderer.draw(surfaces.map { RatedPlane(it, ArFloor.measure(it), false) },
            viewProjectionMatrix)
        if (floors.isEmpty()) depthSurfaces.update(current, frame)?.let {
            planeRenderer.drawDepth(it, viewProjectionMatrix)
        }

        if (ExerciseMotion.needsRaisedSupport(exercise)) {
            drawSupported(frame, horizontal, seconds)
            return
        }
        place(frame, floors)
        val stand = anchor?.takeIf { it.trackingState == TrackingState.TRACKING }
        if (stand == null) {
            interaction.suspend()
            drawDemoInset(seconds)
            postHint(getString(R.string.preview_pending))
            return
        }

        // Preserve the placement heading so walking around reveals the rep from all sides.
        val position = FloatArray(3).also { stand.pose.getTranslation(it, 0) }
        Matrix.setIdentityM(standMatrix, 0)
        Matrix.translateM(standMatrix, 0, position[0], position[1], position[2])
        Matrix.rotateM(standMatrix, 0, placementYaw, 0f, 1f, 0f)
        interaction.transform(standMatrix)
        drawFigure(seconds)
        interaction.bounds(viewProjectionMatrix, standMatrix, figure?.bounds ?: fallbackAvatar.bounds,
            viewportWidth, viewportHeight)
        worldCenter[0] = position[0]
        worldCenter[1] = position[1] + interaction.body.height + 0.7f
        worldCenter[2] = position[2]
        Matrix.multiplyMV(projectedCenter, 0, viewProjectionMatrix, 0, worldCenter, 0)
        val w = projectedCenter[3]
        if (w <= 0f || kotlin.math.abs(projectedCenter[0]) > w || kotlin.math.abs(projectedCenter[1]) > w) {
            interaction.hide()
            drawDemoInset(seconds)
            postHint(getString(R.string.preview_offscreen))
            return
        }
        postHint(getString(R.string.preview_tap) + "\n" + getString(R.string.figure_interaction))
    }

    private fun drawSupported(frame: Frame, planes: List<Plane>, seconds: Float) {
        val tap = pendingTap.getAndSet(null)
        if (bodySupport?.stopped == true) { bodySupport?.detach(); bodySupport = null }
        if (tap != null || bodySupport == null) {
            if (tap != null || frame.timestamp - lastPlacementAttempt >= 500_000_000L) {
                lastPlacementAttempt = frame.timestamp
                val preferred = tap?.let { point -> frame.hitTest(point[0], point[1]).firstOrNull {
                    it.trackable in planes && (it.trackable as? Plane)?.isPoseInPolygon(it.hitPose) == true
                }?.hitPose?.translation }
                val replacement = try {
                    ArBodySupport.find(exercise, planes, frame.camera.pose.translation, preferred)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Support planes changed while placing", e); null
                }
                if (replacement != null) {
                    bodySupport?.detach(); bodySupport = replacement; interaction.reset()
                    supportLostAt = 0L
                }
            }
        }
        val placed = bodySupport?.placement()
        if (placed == null) {
            if (bodySupport != null) {
                if (supportLostAt == 0L) supportLostAt = frame.timestamp
                if (frame.timestamp - supportLostAt > 1_000_000_000L) {
                    bodySupport?.detach(); bodySupport = null; supportLostAt = 0L
                }
            }
            interaction.suspend()
            drawDemoInset(seconds)
            postHint(getString(if (exercise == "PULLUP") R.string.preview_find_overhead else R.string.preview_find_support))
            return
        }
        supportLostAt = 0L
        Matrix.setIdentityM(standMatrix, 0)
        Matrix.translateM(standMatrix, 0, placed.position[0], placed.position[1] + interaction.body.height, placed.position[2])
        // Heading follows the two physical supports. A lifted demo returns to those contacts.
        Matrix.rotateM(standMatrix, 0, placed.yaw, 0f, 1f, 0f)
        drawFigure(seconds, placed.support)
        interaction.bounds(viewProjectionMatrix, standMatrix, figure?.bounds ?: fallbackAvatar.bounds,
            viewportWidth, viewportHeight)
        if (!interaction.isVisible) {
            interaction.hide()
            drawDemoInset(seconds)
            postHint(getString(R.string.preview_offscreen))
            return
        }
        postHint(getString(if (exercise == "PULLUP") R.string.preview_overhead_ready else R.string.preview_support_ready))
    }

    /** Reuse the scan location, otherwise try several visible floor rays. Never invent a floor. */
    private fun place(frame: Frame, floors: List<Plane>) {
        if (anchor?.trackingState == TrackingState.STOPPED) {
            anchor?.detach()
            anchor = null
        }
        val tap = pendingTap.getAndSet(null)
        if (anchor == null && tap == null) {
            val saved = RoomSession.selectedAnchor?.takeIf { it.trackingState == TrackingState.TRACKING }
            val floor = saved?.let { spot -> floors.firstOrNull { plane ->
                kotlin.math.abs(plane.centerPose.ty() - spot.pose.ty()) < 0.15f && plane.isPoseInPolygon(spot.pose)
            } }
            if (saved != null && floor != null) {
                // Attached to the floor plane, like tap placements, rather than floating in world space.
                anchor = try { floor.createAnchor(saved.pose) } catch (e: RuntimeException) {
                    Log.w(TAG, "Saved floor changed while placing", e); null
                }
                interaction.reset()
                placementYaw = Math.toDegrees(atan2(frame.camera.pose.tx() - saved.pose.tx(),
                    frame.camera.pose.tz() - saved.pose.tz()).toDouble()).toFloat()
                return
            }
        }
        if (tap != null || anchor == null) {
            if (tap == null && frame.timestamp - lastPlacementAttempt < 250_000_000L) return
            lastPlacementAttempt = frame.timestamp
            val rays = if (tap != null) listOf(tap) else listOf(
                floatArrayOf(viewportWidth * 0.5f, viewportHeight * 0.65f),
                floatArrayOf(viewportWidth * 0.35f, viewportHeight * 0.8f),
                floatArrayOf(viewportWidth * 0.65f, viewportHeight * 0.8f),
                floatArrayOf(viewportWidth * 0.5f, viewportHeight * 0.5f))
            val hit = rays.firstNotNullOfOrNull { ray ->
                frame.hitTest(ray[0], ray[1]).firstOrNull { hit ->
                    val plane = hit.trackable as? Plane
                    hit.distance in 0.25f..5f && plane in floors &&
                        plane?.isPoseInPolygon(hit.hitPose) == true &&
                        hit.hitPose.ty() < frame.camera.pose.ty() &&
                        // A tap is a deliberate choice; automatic placement waits for a settled floor.
                        (tap != null || plane.extentX * plane.extentZ >= MIN_AUTO_PLACE_AREA_M2)
                }
            }
            if (hit != null) {
                val replacement = hit.createAnchor()
                anchor?.detach()
                anchor = replacement
                interaction.reset()
                placementYaw = Math.toDegrees(atan2(
                    frame.camera.pose.tx() - hit.hitPose.tx(),
                    frame.camera.pose.tz() - hit.hitPose.tz()).toDouble()).toFloat()
                return
            }
        }
    }

    /**
     * A clearly separate 3D demo on a rounded card, centred between the header and the sheet,
     * while the real room is being mapped. The card's fill marks the stencil so the figure and
     * its supports are clipped to the rounded corners.
     */
    private fun drawDemoInset(seconds: Float) {
        val rect = insetRect ?: return
        if (viewportWidth == 0 || viewportHeight == 0) return
        val density = resources.displayMetrics.density
        val (x, top, width, height) = rect
        val y = viewportHeight - top - height
        insetDrawn = true

        GLES20.glEnable(GLES20.GL_STENCIL_TEST)
        GLES20.glClearStencil(0)
        GLES20.glClear(GLES20.GL_STENCIL_BUFFER_BIT)
        insetCard.draw(x, y, width, height, 28 * density, 1.5f * density, viewportWidth, viewportHeight)
        GLES20.glStencilFunc(GLES20.GL_EQUAL, 1, 0xFF)
        GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_KEEP)

        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(x, y, width, height)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glViewport(x, y, width, height)
        drawPlain(seconds, width.toFloat() / height, interactive = false)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_STENCIL_TEST)
    }

    /** A plain turntable view, used when there is no AR session to put the figure in a room. */
    private fun drawPlain(seconds: Float, aspect: Float = if (viewportHeight == 0) 1f else viewportWidth.toFloat() / viewportHeight,
        interactive: Boolean = true) {
        Matrix.perspectiveM(projectionMatrix, 0, 45f, aspect, NEAR_PLANE_M, FAR_PLANE_M)
        val angle = 0.35f
        val radius = ORBIT_RADIUS_M / aspect.coerceAtMost(1f).coerceAtLeast(0.2f)
        Matrix.setLookAtM(
            viewMatrix, 0,
            sin(angle) * radius, ORBIT_HEIGHT_M, cos(angle) * radius,
            0f, LOOK_AT_HEIGHT_M, 0f,
            0f, 1f, 0f,
        )
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        Matrix.setIdentityM(standMatrix, 0)
        // The card demo turns slowly on its own so every angle of the rep is shown.
        val turntable = if (interactive) 0f else seconds * TURNTABLE_DEG_PER_S
        if (interactive) interaction.transform(standMatrix)
        else Matrix.rotateM(standMatrix, 0, turntable, 0f, 1f, 0f)
        ExerciseMotion.support(exercise)?.let { support ->
            // Explicit demonstration supports in the non-AR view; never presented as room geometry.
            Matrix.setIdentityM(supportRotation, 0)
            Matrix.rotateM(supportRotation, 0, if (interactive) interaction.yaw else turntable, 0f, 1f, 0f)
            Matrix.multiplyMM(supportProjection, 0, viewProjectionMatrix, 0, supportRotation, 0)
            planeRenderer.drawDepth(HorizontalPatch(0f,
                floatArrayOf(-0.7f, -0.5f, 0.7f, -0.5f, 0.7f, 0.5f, -0.7f, 0.5f), 100), supportProjection)
            planeRenderer.drawDepth(HorizontalPatch(support.handHeight,
                floatArrayOf(-0.65f, support.handZ - 0.25f, 0.65f, support.handZ - 0.25f,
                    0.65f, support.handZ + 0.25f, -0.65f, support.handZ + 0.25f), 100), supportProjection)
        }
        drawFigure(seconds, lift = if (interactive) interaction.body.height else 0f)
        if (interactive) interaction.bounds(viewProjectionMatrix, standMatrix,
            figure?.bounds ?: fallbackAvatar.bounds, viewportWidth, viewportHeight)
    }

    private fun drawFigure(seconds: Float, support: BodySupport? = ExerciseMotion.support(exercise),
        lift: Float = interaction.body.height) {
        val rigged = figure
        if (rigged != null) rigged.draw(viewProjectionMatrix, standMatrix, seconds, support)
        else fallbackAvatar.draw(viewProjectionMatrix, standMatrix, seconds, support)
        // After the figure, so the bounds are this frame's pose and the body occludes the shadow.
        // The drag-lift is taken back out so the shadow stays on the floor.
        standMatrix.copyInto(shadowMatrix)
        Matrix.translateM(shadowMatrix, 0, 0f, -lift, 0f)
        shadow.draw(viewProjectionMatrix, shadowMatrix, rigged?.bounds ?: fallbackAvatar.bounds)
    }

    private fun postHint(text: String) {
        if (text == lastHint) return
        lastHint = text
        runOnUiThread { hintText.text = text }
    }

    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: 0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

    private companion object {
        const val TAG = "PreviewActivity"
        const val CAMERA_REQUEST = 2
        const val DEFAULT_EXERCISE = "PUSHUP"

        const val WORKOUT_ACTIVITY = "com.hackathon.calico.WorkoutActivity"

        const val NEAR_PLANE_M = 0.1f
        const val FAR_PLANE_M = 100f

        const val BACKDROP = 0.09f
        const val ORBIT_RADIUS_M = 3.4f
        const val ORBIT_HEIGHT_M = 1.4f
        const val LOOK_AT_HEIGHT_M = 0.85f
        const val TURNTABLE_DEG_PER_S = 18f

        /** Brand-new floor patches still shift while ARCore refines them; anchors on them drift. */
        const val MIN_AUTO_PLACE_AREA_M2 = 0.35f
    }
}
