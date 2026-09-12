package com.hackathon.calico.coach

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * The one loaded model for the whole process. Loading costs several seconds, and three
 * activity-scoped view models would otherwise each pay it, so ownership lives here and
 * survives closing the coach screen or the workout orb.
 */
object CoachEngine {
    /** Inference is thread-affine: every native call must run on this worker. */
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    @Volatile private var engine: NativeCoach? = null
    private var loadedKey: String? = null

    /** Loads the model if needed and returns it. Must be called on [dispatcher]. */
    suspend fun acquire(model: CoachModel, context: Context, status: (String)->Unit): NativeCoach {
        status("Checking the local model…")
        // A 2.4 GB checksum is skipped when the same file was already verified.
        model.verify()
        val handle = model.open()
        try {
            if(engine!=null && loadedKey!=handle.key) release()
            engine?.let { return it }
            status("Loading offline coach…")
            val loaded = NativeCoach()
            try { loaded.load(handle.path, context.applicationInfo.nativeLibraryDir) }
            catch(e: Throwable) { loaded.close(); throw e }
            engine = loaded
            loadedKey = handle.key
            return loaded
        } finally { handle.close() }
    }

    fun cancel() { engine?.cancel() }

    /** Frees the model. Only safe on [dispatcher]; callers elsewhere use [requestRelease]. */
    fun release() {
        engine?.close(); engine = null; loadedKey = null
    }

    /** Stops any generation and frees the model from whatever thread is asking. */
    fun requestRelease() {
        cancel()
        CoroutineScope(dispatcher).launch { release() }
    }

    /** Loads the model ahead of the first question so waking Calico answers immediately. */
    fun prewarm(context: Context) {
        val application = context.applicationContext
        val model = CoachModel(application)
        if(!model.present() || engine!=null) return
        CoroutineScope(dispatcher).launch {
            runCatching { acquire(model, application) {} }
        }
    }
}
