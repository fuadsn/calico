package com.hackathon.calico.coach

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Network is used only for this pinned model download. Inference has no network API. */
class CoachModel(private val context: Context) {
    val file = File(context.filesDir,"models/$NAME")
    private var verifiedStamp: Pair<Long,Long>? = null
    fun present() = file.isFile && file.length() == SIZE
    suspend fun verify() {
        check(present()) { "Download or import the offline model first." }
        val stamp=file.length() to file.lastModified()
        if(verifiedStamp==stamp) return
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024*1024)
            var count = input.read(buffer)
            while(count != -1) { coroutineContext.ensureActive(); digest.update(buffer,0,count); count=input.read(buffer) }
        }
        check(digest.digest().hex() == SHA256) { "Model verification failed. Download or import it again." }
        check(stamp==(file.length() to file.lastModified())) { "Model changed during verification. Please retry." }
        verifiedStamp=stamp
    }
    suspend fun download(progress: (Float)->Unit) {
        val connection = java.net.URL(DOWNLOAD_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000; connection.readTimeout = 30_000
        try {
            check(connection.responseCode in 200..299) { "Download failed (${connection.responseCode}). Please retry." }
            connection.inputStream.use { copy(it,progress) }
        } finally { connection.disconnect() }
    }
    suspend fun import(uri: Uri, progress: (Float)->Unit) {
        (context.contentResolver.openInputStream(uri) ?: error("Could not open this file.")).use { copy(it,progress) }
    }
    private suspend fun copy(input: InputStream, progress: (Float)->Unit) {
        verifiedStamp=null
        file.parentFile!!.mkdirs()
        val partial = File(file.parentFile,"$NAME.partial")
        check(file.parentFile!!.usableSpace > SIZE + 128*1024*1024) { "Free at least 1.5 GB of storage before importing the model." }
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            var total=0L
            partial.outputStream().use { output ->
                val buffer=ByteArray(1024*1024)
                var count=input.read(buffer)
                while(count!=-1) {
                    coroutineContext.ensureActive()
                    total+=count
                    check(total<=SIZE) { "This file is not the supported Qwen model." }
                    output.write(buffer,0,count); digest.update(buffer,0,count)
                    progress(total.toFloat()/SIZE)
                    count=input.read(buffer)
                }
                output.fd.sync()
            }
            check(total==SIZE && digest.digest().hex()==SHA256) { "Model check failed. Choose Qwen3-1.7B-Q4_K_M from the supported download." }
            check(partial.renameTo(file)) { "Could not save the verified model." }
        } finally { partial.delete() }
    }
    private fun ByteArray.hex()=joinToString("") { "%02x".format(it) }
    companion object {
        const val NAME="Qwen3-1.7B-Q4_K_M.gguf"
        const val SIZE=1282439264L
        const val SHA256="d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5"
        const val DOWNLOAD_URL="https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF/resolve/daeb8e2d528a760970442092f6bf1e55c3b659eb/$NAME"
    }
}
