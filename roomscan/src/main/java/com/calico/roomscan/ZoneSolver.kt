package com.calico.roomscan

import kotlin.math.abs
import kotlin.math.min

/** How much room a tracked surface gives someone who wants to exercise on it. */
enum class ZoneRating {
    /** Not worth offering: too cramped to stand and move safely. */
    TOO_SMALL,

    /** Room to stand and work the upper body, but not to lie down or jump. */
    STANDING_ONLY,

    /** Enough floor for mat work, lunges and jumping jacks. */
    AMPLE
}

/** A candidate patch of floor, measured in metres. */
data class WorkoutZone(
    val widthM: Float,
    val depthM: Float,
    /** Usable floor. Traced from the real outline where one is available. */
    val areaM2: Float,
    val rating: ZoneRating
) {
    /** The narrow dimension, which is what actually limits movement. */
    val shortSideM: Float get() = minOf(widthM, depthM)
}

/**
 * Decides whether a measured surface is usable for a workout, and how far along
 * the scan is.
 *
 * Deliberately free of Android and ARCore types so it stays a plain JVM unit
 * under test. Callers pass plain metre measurements taken from wherever.
 */
object ZoneSolver {

    /** Below this there is no point offering the surface at all. */
    const val STANDING_MIN_AREA_M2 = 1.2f

    /** Full floor work needs roughly a yoga mat plus swing room on each side. */
    const val AMPLE_MIN_AREA_M2 = 3.0f

    /** A strip narrower than this is unusable however long it runs. */
    const val STANDING_MIN_SIDE_M = 0.6f

    /** Lying down or jumping sideways needs real width, not just total area. */
    const val AMPLE_MIN_SIDE_M = 1.2f

    /** Feature points at which the early, pre-surface phase reads as complete. */
    const val POINTS_FOR_FULL_EARLY_PHASE = 350

    /** The early phase can only ever account for this much of the bar. */
    const val EARLY_PHASE_SHARE = 0.35f

    /**
     * Rates a surface from its bounding side lengths, using the bounding area.
     * Prefer the overload taking a traced area when the real outline is known.
     */
    fun classify(widthM: Float, depthM: Float): WorkoutZone =
        classify(widthM, depthM, widthM.coerceAtLeast(0f) * depthM.coerceAtLeast(0f))

    /**
     * Rates a surface. Area alone is not enough: a long narrow strip can measure
     * several square metres and still be useless, so the short side has to clear
     * its own bar as well.
     *
     * @param measuredAreaM2 the genuinely usable area. ARCore reports a bounding
     *   box, which overstates any surface that is not roughly rectangular, so the
     *   traced outline area gives a far truer verdict.
     */
    fun classify(widthM: Float, depthM: Float, measuredAreaM2: Float): WorkoutZone {
        val width = widthM.coerceAtLeast(0f)
        val depth = depthM.coerceAtLeast(0f)
        val bounding = width * depth
        // Never credit a surface with more room than its own bounding box holds.
        val area = measuredAreaM2.coerceAtLeast(0f).coerceAtMost(bounding)
        val shortSide = min(width, depth)

        val rating = when {
            area >= AMPLE_MIN_AREA_M2 && shortSide >= AMPLE_MIN_SIDE_M -> ZoneRating.AMPLE
            area >= STANDING_MIN_AREA_M2 && shortSide >= STANDING_MIN_SIDE_M -> ZoneRating.STANDING_ONLY
            else -> ZoneRating.TOO_SMALL
        }
        return WorkoutZone(width, depth, area, rating)
    }

    /**
     * Area enclosed by a closed outline, by the shoelace formula. Vertices arrive
     * as flat x, z pairs in any winding order.
     */
    fun polygonArea(vertices: FloatArray): Float {
        val corners = vertices.size / 2
        if (corners < 3) return 0f
        var doubled = 0f
        for (i in 0 until corners) {
            val j = (i + 1) % corners
            doubled += vertices[i * 2] * vertices[j * 2 + 1] -
                vertices[j * 2] * vertices[i * 2 + 1]
        }
        return abs(doubled) / 2f
    }

    /** True once a zone is worth showing the user as a workout spot. */
    fun isUsable(zone: WorkoutZone): Boolean = zone.rating != ZoneRating.TOO_SMALL

    /**
     * Picks the spot to recommend. Prefers the better rating, and breaks ties on
     * area, so a roomy floor always wins over a barely-qualifying one.
     */
    fun best(zones: List<WorkoutZone>): WorkoutZone? = bestBy(zones) { it }

    /**
     * Same ranking, but over whatever the caller is carrying the zone inside, so
     * the choice rule lives here rather than being restated at the call site.
     */
    fun <T> bestBy(items: List<T>, zoneOf: (T) -> WorkoutZone): T? =
        items.filter { isUsable(zoneOf(it)) }
            .maxWithOrNull(
                compareBy({ zoneOf(it).rating.ordinal }, { zoneOf(it).areaM2 })
            )

    /**
     * How far along the scan is, from 0 to 1, for a progress bar.
     *
     * Before any surface is found only feature points are available, and they fill
     * the first part of the bar so it moves immediately instead of sitting at zero
     * while the user waves the phone around. Mapped floor area fills the rest.
     */
    fun scanProgress(featurePoints: Int, mappedAreaM2: Float): Float {
        val early = (featurePoints.coerceAtLeast(0).toFloat() / POINTS_FOR_FULL_EARLY_PHASE)
            .coerceAtMost(1f) * EARLY_PHASE_SHARE
        val mapped = (mappedAreaM2.coerceAtLeast(0f) / AMPLE_MIN_AREA_M2)
            .coerceAtMost(1f) * (1f - EARLY_PHASE_SHARE)
        return (early + mapped).coerceIn(0f, 1f)
    }
}
