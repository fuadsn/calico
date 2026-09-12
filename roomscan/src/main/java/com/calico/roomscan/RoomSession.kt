package com.calico.roomscan

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Plane
import com.google.ar.core.Session

/** One room map across scan, picker and preview. Only the resumed renderer owns the camera. */
object RoomSession : Application.ActivityLifecycleCallbacks {
    private var session: Session? = null
    private var owner: Any? = null
    private var registered = false
    private var startedActivities = 0
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { if (owner == null) close() }
    var selectedAnchor: Anchor? = null
        private set
    private var selectedPlane: Plane? = null

    fun acquire(context: Context, client: Any): Session {
        check(owner == null || owner === client) { "Another renderer owns the AR camera" }
        handler.removeCallbacks(expire)
        if (!registered) {
            (context.applicationContext as Application).registerActivityLifecycleCallbacks(this)
            registered = true
            startedActivities = 1 // First acquire happens after the caller's onStart.
        }
        val current = session ?: Session(context.applicationContext).also {
            ArFloor.configure(it)
            session = it
            Log.i("CalicoAR", "Created room session; depth=${it.config.depthMode}")
        }
        current.resume()
        owner = client
        Log.i("CalicoAR", "Resumed room session for ${client.javaClass.simpleName}")
        return current
    }

    /** Call after GLSurfaceView.onPause has stopped all frame processing. */
    fun release(client: Any) {
        if (owner !== client) return
        session?.pause()
        owner = null
        Log.i("CalicoAR", "Paused room session; map retained")
    }

    /** Called on the active GL thread once a measured floor is stable. */
    fun select(plane: Plane) {
        if (selectedPlane == plane && selectedAnchor != null) return
        val replacement = plane.createAnchor(plane.centerPose)
        selectedAnchor?.detach()
        selectedAnchor = replacement
        selectedPlane = plane
    }

    private fun close() {
        selectedAnchor?.detach()
        selectedAnchor = null
        selectedPlane = null
        session?.close()
        session = null
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        handler.removeCallbacks(expire)
    }
    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) handler.postDelayed(expire, 30_000L)
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
