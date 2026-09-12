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
import android.widget.ProgressBar
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/**
 * First screen in the flow. Scans the floor with ARCore, shades the areas that are
 * big enough to exercise in, stands an animated figure on the best one, then
 * offers a button through to the exercise picker in :app.
 */
class ScanActivity : Activity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var nextButton: Button
    private lateinit var skipButton: Button
    private lateinit var topBar: LinearLayout
    private lateinit var actionBar: LinearLayout

    private val backgroundRenderer = BackgroundRenderer()
    private val pointCloudRenderer = PointCloudRenderer()
    private val planeRenderer = PlaneRenderer()
    private lateinit var avatarRenderer: PoseFigureRenderer
    private val depthSurfaces = DepthSurfaces()
    private var figure: SkinnedFigure? = null
    private val interaction = FigureInteraction()

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewProjectionMatrix = FloatArray(16)
    private val bestPlaneMatrix = FloatArray(16)

    private var lastUiUpdateMs = 0L
    private var lastDiagnosticMs = 0L

    private var session: Session? = null
    private var installRequested = false
    private var foundSpot = false
    private val stability = ScanStability()
    private var lastStatus = ""
    private var lastProgressPercent = -1
    private val startedAtMs = SystemClock.uptimeMillis()

    private var viewportWidth = 0
    private var viewportHeight = 0
    private var viewportChanged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        surfaceView = findViewById(R.id.surface)
        statusText = findViewById(R.id.status)
        progressBar = findViewById(R.id.progress)
        nextButton = findViewById(R.id.next)
        skipButton = findViewById(R.id.skip)
        topBar = findViewById(R.id.topBar)
        actionBar = findViewById(R.id.actions)
        figure = DemoFigure.read(assets, "SQUAT")
        avatarRenderer = PoseFigureRenderer(assets, "SQUAT")

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        interaction.attach(surfaceView)

        nextButton.setOnClickListener { goToWorkout() }
        skipButton.setOnClickListener { goToWorkout() }
        applyWindowInsets()
    }

    /**
     * targetSdk 36 draws edge to edge, so without this the status text sits under
     * the camera cutout and the buttons under the gesture bar.
     */
    private fun applyWindowInsets() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val buttonGap = (24 * density).toInt()

        window.decorView.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
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
            params.bottomMargin = bottom + buttonGap
            actionBar.layoutParams = params
            insets
        }
    }

    /**
     * :roomscan cannot reference :app directly, since :app already depends on this
     * module, so the next screen is addressed by name. The picker is preferred; the
     * workout screen is the fallback if :app has not got a picker.
     */
    private fun goToWorkout() {
        for (target in NEXT_ACTIVITIES) {
            try {
                startActivity(Intent().setClassName(this, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Next screen not found: $target", e)
            }
        }
        statusText.text = getString(R.string.scan_workout_missing)
    }

    override fun onResume() {
        super.onResume()

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
            return
        }

        if (!createSession()) return
        viewportChanged = true
        stability.reset()
        depthSurfaces.reset()
        surfaceView.onResume()
    }

    /** Returns true once a session exists; false if ARCore still has to be installed. */
    private fun createSession(): Boolean {
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return false
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
            session = RoomSession.acquire(this, this)
            return true
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera unavailable", e)
            statusText.text = getString(R.string.scan_camera_unavailable)
            return false
        } catch (e: UnavailableException) {
            Log.e(TAG, "ARCore unavailable", e)
            statusText.text = getString(R.string.scan_ar_unavailable)
            return false
        }
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        interaction.suspend()
        RoomSession.release(this)
        session = null
    }

    override fun onDestroy() {
        session = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            statusText.text = getString(R.string.scan_camera_denied)
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
        pointCloudRenderer.createOnGlThread()
        planeRenderer.createOnGlThread()
        avatarRenderer.createOnGlThread()
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
            return
        }

        backgroundRenderer.draw(frame)

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            interaction.suspend()
            resetSpot()
            postStatus(getString(ArFloor.trackingHint(camera.trackingFailureReason)))
            return
        }

        camera.getProjectionMatrix(projectionMatrix, 0, NEAR_PLANE_M, FAR_PLANE_M)
        camera.getViewMatrix(viewMatrix, 0)
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        frame.acquirePointCloud().use { pointCloudRenderer.update(it) }
        pointCloudRenderer.draw(viewProjectionMatrix)

        val rated = rateVisiblePlanes(current, camera.pose.ty())
        planeRenderer.draw(rated, viewProjectionMatrix)
        val depthPatch = depthSurfaces.update(current, frame)
        val now = SystemClock.uptimeMillis()
        if (now - lastDiagnosticMs >= 2000L) {
            lastDiagnosticMs = now
            Log.d("CalicoAR", "scan surfaces=${rated.size} points=${pointCloudRenderer.visiblePoints} depth=${depthPatch?.support ?: 0}")
        }
        if (depthPatch != null && rated.none { it.isBest })
            planeRenderer.drawDepth(depthPatch, viewProjectionMatrix)

        val mappedArea = rated.filter { it.plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
            .maxOfOrNull { it.zone.areaM2 } ?: 0f
        val points = pointCloudRenderer.visiblePoints
        postProgress(ZoneSolver.scanProgress(points, mappedArea))

        val best = rated.firstOrNull { it.isBest }
        if (best == null) {
            interaction.suspend()
            // Require a fresh stability check after the candidate disappears.
            resetSpot()
            if (depthPatch != null) {
                postStatus(getString(R.string.scan_depth_patch, depthPatch.area))
                return
            }
            postStatus(
                getString(
                    if (rated.isEmpty()) R.string.scan_points_only else R.string.scan_surfaces,
                    if (rated.isEmpty()) points else rated.size
                )
            )
            return
        }

        best.plane.centerPose.toMatrix(bestPlaneMatrix, 0)
        val seconds = (SystemClock.uptimeMillis() - startedAtMs) / 1000f
        interaction.advance(seconds)
        interaction.transform(bestPlaneMatrix)
        val rigged = figure?.takeIf { it.isUsable }
        if (rigged != null) rigged.draw(viewProjectionMatrix, bestPlaneMatrix, seconds)
        else avatarRenderer.draw(viewProjectionMatrix, bestPlaneMatrix, seconds)
        interaction.bounds(viewProjectionMatrix, bestPlaneMatrix, rigged?.bounds ?: avatarRenderer.bounds,
            viewportWidth, viewportHeight)

        // Require the spot to hold still for a moment so a flickering early plane
        // does not unlock the button and then vanish.
        val stable = stability.observe(best.plane, best.plane.centerPose.ty(), frame.timestamp)
        if (stable) RoomSession.select(best.plane)
        if (stable != foundSpot) {
            foundSpot = stable
            runOnUiThread { nextButton.visibility = if (stable) View.VISIBLE else View.GONE }
        }

        postStatus(
            when (best.zone.rating) {
                ZoneRating.AMPLE -> getString(R.string.scan_zone_ample, best.zone.areaM2)
                else -> getString(R.string.scan_zone_standing, best.zone.areaM2)
            } + "\n" + getString(R.string.figure_interaction)
        )
    }

    /** Measures every tracked plane and marks the one worth recommending. */
    private fun rateVisiblePlanes(current: Session, cameraY: Float): List<RatedPlane> {
        val tracked = ArFloor.surfaces(current)
        if (tracked.isEmpty()) return emptyList()
        val candidates = ArFloor.candidates(tracked, cameraY).toSet()
        val measured = tracked.map { plane -> plane to ArFloor.measure(plane) }
        val winner = ZoneSolver.bestBy(measured.filter { it.first in candidates }) { it.second }
        return measured.map { (plane, zone) ->
            RatedPlane(plane, zone, isBest = winner != null && plane == winner.first)
        }
    }

    private fun resetSpot() {
        stability.reset()
        if (foundSpot) {
            foundSpot = false
            runOnUiThread { nextButton.visibility = View.GONE }
        }
    }

    /** Pushes to the UI thread only when the wording actually changed. */
    private fun postStatus(text: String) {
        if (text == lastStatus) return
        val now = SystemClock.uptimeMillis()
        if (now - lastUiUpdateMs < 250L) return
        lastUiUpdateMs = now
        lastStatus = text
        runOnUiThread { statusText.text = text }
    }

    /** Pushes to the UI thread only on whole percentage point changes. */
    private fun postProgress(fraction: Float) {
        val percent = (fraction * 100).roundToInt()
        if (percent == lastProgressPercent) return
        lastProgressPercent = percent
        runOnUiThread { progressBar.progress = percent }
    }

    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: 0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

    private companion object {
        const val TAG = "ScanActivity"
        const val CAMERA_REQUEST = 1

        /** Tried in order, so the picker wins when :app provides one. */
        val NEXT_ACTIVITIES = listOf(
            "com.hackathon.calico.MainActivity",
            "com.hackathon.calico.WorkoutActivity"
        )

        const val NEAR_PLANE_M = 0.1f
        const val FAR_PLANE_M = 100f

    }
}
