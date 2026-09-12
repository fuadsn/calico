package com.calico.roomscan

import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState

/** Surface visibility is independent of the stricter workout-floor recommendation. */
object ArFloor {
    fun configure(session: Session) {
        session.configure(Config(session).apply {
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            depthMode = if (session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY))
                Config.DepthMode.RAW_DEPTH_ONLY else Config.DepthMode.DISABLED
            lightEstimationMode = Config.LightEstimationMode.DISABLED
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            focusMode = Config.FocusMode.AUTO
        })
    }

    fun surfaces(session: Session): List<Plane> =
        session.getAllTrackables(Plane::class.java).filter {
            it.trackingState == TrackingState.TRACKING && it.subsumedBy == null &&
                it.polygon.remaining() >= 6
        }

    fun candidates(surfaces: List<Plane>, cameraY: Float): List<Plane> {
        val upward = surfaces.filter {
                it.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                it.centerPose.ty() < cameraY - 0.1f
        }
        val lowest = upward.minOfOrNull { it.centerPose.ty() } ?: return emptyList()
        return upward.filter { it.centerPose.ty() <= lowest + 0.15f }
    }

    fun measure(plane: Plane): WorkoutZone {
        val polygon = plane.polygon
        val count = polygon.limit() / 2
        var twiceArea = 0f
        for (i in 0 until count) {
            val next = (i + 1) % count
            twiceArea += polygon.get(i * 2) * polygon.get(next * 2 + 1) -
                polygon.get(next * 2) * polygon.get(i * 2 + 1)
        }
        return ZoneSolver.classify(plane.extentX, plane.extentZ, kotlin.math.abs(twiceArea) * 0.5f)
    }

    fun trackingHint(reason: TrackingFailureReason): Int = when (reason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> R.string.scan_more_light
        TrackingFailureReason.EXCESSIVE_MOTION -> R.string.scan_slow_down
        TrackingFailureReason.INSUFFICIENT_FEATURES -> R.string.scan_more_detail
        TrackingFailureReason.CAMERA_UNAVAILABLE -> R.string.scan_camera_unavailable
        else -> R.string.scan_looking
    }
}
