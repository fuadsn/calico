package com.calico.roomscan

import kotlin.math.abs

/** Fresh camera observations of the same surface must agree for a full second. */
class ScanStability {
    private var candidate: Any? = null
    private var since = 0L
    private var last = 0L
    private var height = 0f

    fun reset() {
        candidate = null
        since = 0L
        last = 0L
    }

    fun observe(id: Any, heightM: Float, timestampNs: Long): Boolean {
        if (!heightM.isFinite() || timestampNs <= 0) {
            reset()
            return false
        }
        if (candidate != id || timestampNs < last || timestampNs - last > 500_000_000L ||
            abs(heightM - height) > 0.05f) {
            candidate = id
            since = timestampNs
            height = heightM
        }
        last = timestampNs
        return timestampNs - since >= 1_000_000_000L
    }
}
