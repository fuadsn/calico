package com.calico.roomscan

import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Samples at most 600 confident raw-depth pixels at 4 Hz; releases both images every time. */
class DepthSurfaces {
    private var sampledAt = 0L
    private var sourceTimestamp = 0L
    private var observedAt = 0L
    private var patch: HorizontalPatch? = null

    fun reset() {
        sampledAt = 0L
        sourceTimestamp = 0L
        observedAt = 0L
        patch = null
    }

    fun update(session: Session, frame: Frame): HorizontalPatch? {
        if (session.config.depthMode == Config.DepthMode.DISABLED) return null
        if (frame.timestamp - observedAt > 750_000_000L) patch = null
        if (frame.timestamp - sampledAt < 250_000_000L) return patch
        sampledAt = frame.timestamp
        try {
            frame.acquireRawDepthImage16Bits().use { depth ->
                frame.acquireRawDepthConfidenceImage().use { confidence ->
                    if (depth.timestamp == sourceTimestamp) return patch
                    sourceTimestamp = depth.timestamp
                    val intrinsics = frame.camera.textureIntrinsics
                    val dimensions = intrinsics.imageDimensions
                    val focal = intrinsics.focalLength
                    val principal = intrinsics.principalPoint
                    val scaleX = depth.width.toFloat() / dimensions[0]
                    val scaleY = depth.height.toFloat() / dimensions[1]
                    val depthPlane = depth.planes[0]
                    val confidencePlane = confidence.planes[0]
                    val data = depthPlane.buffer.order(ByteOrder.LITTLE_ENDIAN)
                    val quality = confidencePlane.buffer
                    val step = kotlin.math.ceil(sqrt(depth.width * depth.height / 600.0)).toInt().coerceAtLeast(1)
                    val points = ArrayList<FloatArray>(600)
                    val pose = frame.camera.pose
                    for (y in 0 until depth.height step step) for (x in 0 until depth.width step step) {
                        val score = quality.get(y * confidencePlane.rowStride + x * confidencePlane.pixelStride).toInt() and 255
                        if (score < 180) continue
                        val z = (data.getShort(y * depthPlane.rowStride + x * depthPlane.pixelStride).toInt() and 65535) / 1000f
                        if (z !in 0.25f..5f) continue
                        points += pose.transformPoint(floatArrayOf(
                            (x - principal[0] * scaleX) * z / (focal[0] * scaleX),
                            -(y - principal[1] * scaleY) * z / (focal[1] * scaleY), -z))
                    }
                    patch = HorizontalPatchFit.fit(points, pose.ty())
                    observedAt = frame.timestamp
                }
            }
        } catch (_: NotYetAvailableException) {
            // Normal during startup or with insufficient motion/texture.
        }
        return patch
    }
}
