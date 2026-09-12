package com.hackathon.calico.coach

/** Owned by one inference worker. Only cancellation may run on another thread. */
class NativeCoach : AutoCloseable {
    private val lock = Any()
    private var handle = 0L
    private var legacyThinkingTemplate = false
    fun load(path: String) {
        check(synchronized(lock) { handle == 0L })
        val loaded = nativeLoad(path)
        legacyThinkingTemplate=path.endsWith("Qwen3-1.7B-Q4_K_M.gguf")
        synchronized(lock) { handle = loaded }
    }
    fun start(prompt: String) {
        val adapted=if(legacyThinkingTemplate && prompt.endsWith("<|im_start|>assistant\n")) prompt+"<think>\n\n</think>\n\n" else prompt
        nativeStart(synchronized(lock) { handle }, adapted.toByteArray(Charsets.UTF_8))
    }
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
