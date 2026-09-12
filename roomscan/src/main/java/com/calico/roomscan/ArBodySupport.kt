package com.calico.roomscan

import com.google.ar.core.Anchor
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import kotlin.math.*

/** One anchor per supporting surface. Limb contacts share their surface anchor. */
class ArBodySupport private constructor(
    private val footAnchor: Anchor, private val handAnchor: Anchor,
    private var floor: Plane, private var upper: Plane, private val exercise: String,
    private val originalYaw: Float,
) {
    data class Placement(val position: FloatArray, val yaw: Float, val support: BodySupport)

    fun placement(): Placement? {
        while (floor.subsumedBy != null) floor = floor.subsumedBy!!
        while (upper.subsumedBy != null) upper = upper.subsumedBy!!
        if (floor == upper || floor.trackingState != TrackingState.TRACKING ||
            upper.trackingState != TrackingState.TRACKING || footAnchor.trackingState != TrackingState.TRACKING ||
            handAnchor.trackingState != TrackingState.TRACKING) return null
        val foot = footAnchor.pose.translation
        val hand = handAnchor.pose.translation
        val height = hand[1] - foot[1]
        val expected = ExerciseMotion.support(exercise, height) ?: return null
        val sign = if (exercise == "DIP") -1f else 1f
        val span = hypot(hand[0] - foot[0], hand[2] - foot[2])
        if (abs(span - abs(expected.handZ)) > 0.15f) return null
        val yaw = if (exercise == "PULLUP" || span < 0.05f) originalYaw else atan2((hand[0] - foot[0]) * sign,
            (hand[2] - foot[2]) * sign) * 180f / PI.toFloat()
        val radians = yaw * PI.toFloat() / 180f
        // Recheck polygon coverage as ARCore refines/merges the two planes.
        if (!SupportPlanner.pairFits(snapshot(floor, 0), foot[0], foot[2], radians, 0.14f) ||
            !SupportPlanner.pairFits(snapshot(upper, 1), hand[0], hand[2], radians,
                if (exercise == "PULLUP") 0.32f else 0.27f)) return null
        if (exercise == "PULLUP") {
            foot[0] = hand[0]; foot[2] = hand[2]
        }
        return Placement(foot, yaw, BodySupport(height, if (exercise == "PULLUP") 0f else span * sign))
    }

    val stopped get() = footAnchor.trackingState == TrackingState.STOPPED || handAnchor.trackingState == TrackingState.STOPPED
    fun detach() { footAnchor.detach(); handAnchor.detach() }

    companion object {
        fun find(exercise: String, planes: List<Plane>, camera: FloatArray, preferred: FloatArray? = null): ArBodySupport? {
            val plan = SupportPlanner.find(exercise, planes.mapIndexed { i, p -> snapshot(p, i) }, camera, preferred) ?: return null
            val floor = planes[plan.floorId]; val upper = planes[plan.handId]
            val foot = floor.createAnchor(Pose.makeTranslation(plan.foot))
            val hand = try { upper.createAnchor(Pose.makeTranslation(plan.hand)) }
                catch (e: RuntimeException) { foot.detach(); throw e }
            return ArBodySupport(foot, hand, floor, upper, exercise, plan.yaw)
        }

        private fun snapshot(plane: Plane, id: Int): SupportPatch {
            val polygon = plane.polygon
            val world = FloatArray(polygon.limit())
            for (i in world.indices step 2) {
                val p = plane.centerPose.transformPoint(floatArrayOf(polygon[i], 0f, polygon[i + 1]))
                world[i] = p[0]; world[i + 1] = p[2]
            }
            return SupportPatch(id, plane.centerPose.ty(), world)
        }
    }
}
