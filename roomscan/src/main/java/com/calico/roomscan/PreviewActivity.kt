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
import android.view.MotionEvent
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

    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val pointCloudRenderer = PointCloudRenderer()
    private lateinit var fallbackAvatar: PoseFigureRenderer
    private val depthSurfaces = DepthSurfaces()

    private var figure: SkinnedFigure? = null

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewProjectionMatrix = FloatArray(16)
    private val standMatrix = FloatArray(16)
    private val projectedCenter = FloatArray(4)
    private val worldCenter = floatArrayOf(0f, 0f, 0f, 1f)

    private var session: Session? = null
    private var installRequested = false
    private var anchor: Anchor? = null
    private val pendingTap = java.util.concurrent.atomic.AtomicReference<FloatArray?>()
    private var placementYaw = 0f

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
        topBar.addView(TextView(this).apply {
            text = getString(R.string.model_credits)
            setTextColor(android.graphics.Color.LTGRAY)
            setPadding(0, 12, 0, 12)
            setOnClickListener {
                val credits = TextView(this@PreviewActivity).apply {
                    text = android.text.Html.fromHtml(getString(R.string.model_credits_details), android.text.Html.FROM_HTML_MODE_LEGACY)
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                    setPadding(32, 24, 32, 24)
                }
                android.app.AlertDialog.Builder(this@PreviewActivity)
                    .setTitle(R.string.model_credits).setView(credits)
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        })
        actionBar = findViewById(R.id.actions)
        titleText.text = exercise.replace('_', ' ')
        figure = DemoFigure.read(assets, exercise)
        fallbackAvatar = PoseFigureRenderer(assets, exercise)

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) pendingTap.set(floatArrayOf(event.x, event.y))
            true
        }

        findViewById<Button>(R.id.back).setOnClickListener { finish() }
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
        val pad = (16 * density).toInt()
        val gap = (24 * density).toInt()
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
            topBar.setPadding(pad, top + pad, pad, pad)
            val params = actionBar.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = bottom + gap
            actionBar.layoutParams = params
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
            surfaceView.onResume()
            return
        }
        createSession()
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
        anchor?.detach()
        anchor = null
        RoomSession.release(this)
        session = null
        arMode = false
    }

    override fun onDestroy() {
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
        if (arMode) drawAugmented(seconds) else {
            drawPlain(seconds)
            postHint(getString(R.string.preview_no_ar))
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
            drawPlain(seconds)
            postHint(getString(R.string.scan_camera_unavailable))
            return
        }
        backgroundRenderer.draw(frame)

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            drawDemoInset(seconds)
            postHint(getString(ArFloor.trackingHint(camera.trackingFailureReason)))
            return
        }
        camera.getProjectionMatrix(projectionMatrix, 0, NEAR_PLANE_M, FAR_PLANE_M)
        camera.getViewMatrix(viewMatrix, 0)
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        val surfaces = ArFloor.surfaces(current)
        val floors = surfaces.filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
        frame.acquirePointCloud().use { pointCloudRenderer.update(it) }
        pointCloudRenderer.draw(viewProjectionMatrix)
        planeRenderer.draw(surfaces.map { RatedPlane(it, ArFloor.measure(it), false) },
            viewProjectionMatrix)
        if (floors.isEmpty()) depthSurfaces.update(current, frame)?.let {
            planeRenderer.drawDepth(it, viewProjectionMatrix)
        }

        place(frame, floors)
        val stand = anchor?.takeIf { it.trackingState == TrackingState.TRACKING }
        if (stand == null) {
            drawDemoInset(seconds)
            postHint(getString(R.string.preview_pending))
            return
        }

        // Preserve the placement heading so walking around reveals the rep from all sides.
        val position = FloatArray(3).also { stand.pose.getTranslation(it, 0) }
        Matrix.setIdentityM(standMatrix, 0)
        Matrix.translateM(standMatrix, 0, position[0], position[1], position[2])
        Matrix.rotateM(standMatrix, 0, placementYaw, 0f, 1f, 0f)
        drawFigure(seconds)
        worldCenter[0] = position[0]
        worldCenter[1] = position[1] + 0.7f
        worldCenter[2] = position[2]
        Matrix.multiplyMV(projectedCenter, 0, viewProjectionMatrix, 0, worldCenter, 0)
        val w = projectedCenter[3]
        if (w <= 0f || kotlin.math.abs(projectedCenter[0]) > w || kotlin.math.abs(projectedCenter[1]) > w) {
            drawDemoInset(seconds)
            postHint(getString(R.string.preview_offscreen))
            return
        }
        postHint(getString(R.string.preview_tap))
    }

    /** Reuse the scan location, otherwise try several visible floor rays. Never invent a floor. */
    private fun place(frame: Frame, floors: List<Plane>) {
        if (anchor?.trackingState == TrackingState.STOPPED) {
            anchor?.detach()
            anchor = null
        }
        val tap = pendingTap.getAndSet(null)
        if (anchor == null && tap == null) {
            RoomSession.selectedAnchor?.takeIf { it.trackingState == TrackingState.TRACKING }?.let {
                anchor = session?.createAnchor(it.pose)
                placementYaw = Math.toDegrees(atan2(frame.camera.pose.tx() - it.pose.tx(),
                    frame.camera.pose.tz() - it.pose.tz()).toDouble()).toFloat()
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
                        hit.hitPose.ty() < frame.camera.pose.ty()
                }
            }
            if (hit != null) {
                val replacement = hit.createAnchor()
                anchor?.detach()
                anchor = replacement
                placementYaw = Math.toDegrees(atan2(
                    frame.camera.pose.tx() - hit.hitPose.tx(),
                    frame.camera.pose.tz() - hit.hitPose.tz()).toDouble()).toFloat()
                return
            }
        }
    }

    /** An explicitly separate 3D preview stays visible while the real room is being mapped. */
    private fun drawDemoInset(seconds: Float) {
        if (viewportWidth == 0 || viewportHeight == 0) return
        val width = (viewportWidth * 0.48f).toInt()
        val height = (viewportHeight * 0.38f).toInt()
        val x = viewportWidth - width
        val y = (viewportHeight * 0.15f).toInt()
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(x, y, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glViewport(x, y, width, height)
        drawPlain(seconds, width.toFloat() / height)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    /** A plain turntable view, used when there is no AR session to put the figure in a room. */
    private fun drawPlain(seconds: Float, aspect: Float = if (viewportHeight == 0) 1f else viewportWidth.toFloat() / viewportHeight) {
        Matrix.perspectiveM(projectionMatrix, 0, 45f, aspect, NEAR_PLANE_M, FAR_PLANE_M)
        val angle = seconds * ORBIT_RADIANS_PER_SEC
        val radius = ORBIT_RADIUS_M / aspect.coerceAtMost(1f).coerceAtLeast(0.2f)
        Matrix.setLookAtM(
            viewMatrix, 0,
            sin(angle) * radius, ORBIT_HEIGHT_M, cos(angle) * radius,
            0f, LOOK_AT_HEIGHT_M, 0f,
            0f, 1f, 0f,
        )
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        Matrix.setIdentityM(standMatrix, 0)
        drawFigure(seconds)
    }

    private fun drawFigure(seconds: Float) {
        val rigged = figure
        if (rigged != null) rigged.draw(viewProjectionMatrix, standMatrix, seconds)
        else fallbackAvatar.draw(viewProjectionMatrix, standMatrix, seconds)
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
        const val ORBIT_RADIANS_PER_SEC = 0.25f
    }
}
