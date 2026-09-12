package com.hackathon.calico

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hackathon.calico.coach.CoachEngine
import com.hackathon.calico.coach.CoachModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The model is loaded once per process, so leaving a coach screen must not unload it. */
@RunWith(AndroidJUnit4::class)
class CoachEngineDeviceTest {
    @Test fun secondAcquireReusesTheLoadedModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = CoachModel(context)
        assertTrue("Sideload the pinned model into files/models before running this device test", model.present())
        try {
            runBlocking {
                val firstAt = SystemClock.elapsedRealtime()
                val first = withContext(CoachEngine.dispatcher) { CoachEngine.acquire(model, context) {} }
                val firstMs = SystemClock.elapsedRealtime() - firstAt

                val secondAt = SystemClock.elapsedRealtime()
                val second = withContext(CoachEngine.dispatcher) { CoachEngine.acquire(model, context) {} }
                val secondMs = SystemClock.elapsedRealtime() - secondAt

                assertSame("A second question must reuse the loaded model", first, second)
                assertTrue("Reacquiring took $secondMs ms; the model was reloaded", secondMs < 250)
                assertTrue("The first load reported $firstMs ms, which is implausibly fast", firstMs > 0)
            }
        } finally {
            runBlocking { withContext(CoachEngine.dispatcher) { CoachEngine.release() } }
        }
    }

    /** A picked document reaches llama.cpp as fd:N through the patched ggml_fopen; the NPU load must accept that. */
    @Test fun loadsThroughAFileDescriptorPath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = CoachModel(context)
        assertTrue("Sideload the pinned model before running this device test", model.present())
        val handle = model.open()
        // A linked document already arrives as fd:N; a private file is wrapped to match.
        val wrapped = if (handle.path.startsWith("fd:")) null
            else android.os.ParcelFileDescriptor.open(java.io.File(handle.path), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val path = wrapped?.let { "fd:${it.fd}" } ?: handle.path
        try {
            com.hackathon.calico.coach.NativeCoach().use { engine ->
                engine.load(path, context.applicationInfo.nativeLibraryDir)
                handle.close(); wrapped?.close()   // the mapping must outlive our descriptor, as in the app
                engine.start("<|im_start|>user\nSay hello in three words.<|im_end|>\n<|im_start|>assistant\n")
                val out = java.io.ByteArrayOutputStream()
                while (true) out.write(engine.next() ?: break)
                assertTrue("Empty answer through the descriptor path", out.size() > 0)
            }
        } finally { runCatching { handle.close() }; runCatching { wrapped?.close() } }
    }
}
