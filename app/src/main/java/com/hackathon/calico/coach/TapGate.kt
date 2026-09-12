package com.hackathon.calico.coach

/** One deliberate action per gesture; shared by mic and send controls. */
class TapGate(private val intervalMs: Long = 600) {
    private var last: Long? = null
    fun accept(now: Long): Boolean {
        if(last?.let { now-it < intervalMs } == true) return false
        last=now
        return true
    }
}
