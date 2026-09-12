package com.hackathon.calico.coach

/** Owned by one inference worker. Only cancellation may run on another thread. */
class NativeCoach : AutoCloseable {
    private val lock = Any()
    private var handle = 0L
    fun load(path: String) {
        check(synchronized(lock) { handle == 0L })
        val loaded = nativeLoad(path)
        synchronized(lock) { handle = loaded }
    }
    fun start(prompt: String) = nativeStart(synchronized(lock) { handle }, prompt.toByteArray(Charsets.UTF_8))
    fun next(): ByteArray? = nativeNext(synchronized(lock) { handle })
    fun cancel() = synchronized(lock) { if (handle != 0L) nativeCancel(handle) }
    override fun close() {
        val old = synchronized(lock) { handle.also { handle = 0L } }
        if (old != 0L) nativeFree(old)
    }
    private external fun nativeLoad(path: String): Long
    private external fun nativeStart(handle: Long, prompt: ByteArray)
    private external fun nativeNext(handle: Long): ByteArray?
    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)
    companion object { init { System.loadLibrary("calico_coach") } }
}
