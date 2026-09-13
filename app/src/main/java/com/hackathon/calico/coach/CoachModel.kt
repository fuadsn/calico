package com.hackathon.calico.coach

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * The model is a document in the user's own storage, referenced in place through a
 * persisted URI grant, so uninstalling or reinstalling Calico never deletes it. An
 * older copy under the app's private files keeps working until a document is linked.
 * Network is used only for the pinned download. Inference has no network API.
 */
class CoachModel(private val context: Context) {
    private val prefs = context.getSharedPreferences("coach_model", 0)
    private val privateFile = File(context.filesDir, "models/$NAME")
    private val document: Uri? get() = prefs.getString("uri", null)?.let(Uri::parse)

    /** Where the model lives, for the setup screen. */
    val location: String get() = document?.let { displayName(it) } ?: if (privateFile.isFile) "Calico's private storage" else ""

    fun present(): Boolean {
        if (document != null && documentSize() != SIZE) prefs.edit().remove("uri").apply()  // grant lost or file gone
        return document != null || privateFile.length() == SIZE
    }

    /**
     * A path llama.cpp can open. Close it once the model has loaded; mmap keeps the mapping.
     * A document becomes `fd:N`: scoped storage refuses to reopen it by path, so the
     * patched ggml_fopen in our llama.cpp build adopts the descriptor instead.
     */
    class Handle(val path: String, val key: String, private val descriptor: ParcelFileDescriptor?) : AutoCloseable {
        override fun close() { descriptor?.close() }
    }
    fun open(): Handle {
        val uri = document
        if (uri != null) {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("The model file could not be opened. Locate it again in Model setup.")
            return Handle("fd:${descriptor.fd}", uri.toString(), descriptor)
        }
        check(privateFile.length() == SIZE) { "Download or locate the offline model first." }
        return Handle(privateFile.absolutePath, privateFile.absolutePath, null)
    }

    /** One SHA-256 pass per file; the result is remembered until size or modification time changes. */
    suspend fun verify() {
        check(present()) { "Download or locate the offline model first." }
        val stamp = stamp()
        if (prefs.getString("verified", null) == stamp) return
        val digest = MessageDigest.getInstance("SHA-256")
        openInput().use { input ->
            val buffer = ByteArray(1024 * 1024)
            var count = input.read(buffer)
            while (count != -1) { coroutineContext.ensureActive(); digest.update(buffer, 0, count); count = input.read(buffer) }
        }
        check(digest.digest().hex() == SHA256) { "Model verification failed. Download or locate it again." }
        check(stamp == stamp()) { "Model changed during verification. Please retry." }
        prefs.edit().putString("verified", stamp).apply()
    }

    /** References a file the user picked, without copying it. */
    fun link(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val size = querySize(uri)
        check(size == SIZE) { "This is not the supported Qwen3-4B-Instruct-2507-Q4_0 file (${displayName(uri)})." }
        adopt(uri)
    }

    /** The downloaded bytes are not the pinned model; the partial file is useless and is deleted. */
    class ModelMismatchException(message: String) : IllegalStateException(message)

    /** Keeps read/write access to the download target beyond the picker's temporary grant. */
    fun retain(target: Uri) {
        context.contentResolver.takePersistableUriPermission(target,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    /**
     * Downloads into a document the user chose, so the file outlives the app. Resumable: bytes
     * already in [target] are re-hashed and the rest is fetched with an HTTP Range request, so a
     * download interrupted by a lost network or a killed process continues instead of restarting.
     * Network errors leave the partial file for the next attempt; a checksum mismatch deletes it.
     */
    suspend fun download(target: Uri, progress: suspend (Float) -> Unit) {
        runCatching { retain(target) }  // already held when started from the setup screen
        val digest = MessageDigest.getInstance("SHA-256")
        var total = resumeFrom(target, digest)
        if (total in 1 until SIZE && !canAppend(target)) { digest.reset(); total = 0 }  // provider cannot append: start over
        if (total < SIZE) {
            val connection = java.net.URL(DOWNLOAD_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000; connection.readTimeout = 30_000
            if (total > 0) connection.setRequestProperty("Range", "bytes=$total-")
            try {
                val code = connection.responseCode
                if (total > 0 && code == HttpURLConnection.HTTP_OK) {
                    // The server ignored the range: start over from the first byte.
                    digest.reset(); total = 0
                }
                if (code !in 200..299) throw java.io.IOException("Download failed ($code). It will retry.")
                val mode = if (total > 0) "wa" else "wt"
                connection.inputStream.use { input ->
                    (context.contentResolver.openOutputStream(target, mode) ?: error("Could not write to the chosen location.")).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var count = input.read(buffer)
                        while (count != -1) {
                            coroutineContext.ensureActive()
                            total += count
                            if (total > SIZE) mismatch(target, "The download did not match the supported Qwen model.")
                            output.write(buffer, 0, count); digest.update(buffer, 0, count)
                            progress(total.toFloat() / SIZE)
                            count = input.read(buffer)
                        }
                        output.flush()
                    }
                }
            } finally { connection.disconnect() }
        }
        if (total != SIZE) throw java.io.IOException("The download ended early. It will retry.")
        if (digest.digest().hex() != SHA256) mismatch(target, "Model check failed. Please download again.")
        prefs.edit().putString("verified", stamp(target)).apply()
        adopt(target)
    }

    /** Hashes what an earlier attempt already wrote and returns how many bytes that is. */
    private suspend fun resumeFrom(target: Uri, digest: MessageDigest): Long {
        val existing = runCatching { querySize(target) }.getOrNull() ?: 0L
        if (existing <= 0L) return 0L
        if (existing > SIZE) return 0L.also { digest.reset() }  // not ours; "wt" rewrites it
        var read = 0L
        runCatching {
            context.contentResolver.openInputStream(target)?.use { input ->
                val buffer = ByteArray(1024 * 1024)
                var count = input.read(buffer)
                while (count != -1 && read < existing) {
                    coroutineContext.ensureActive()
                    digest.update(buffer, 0, count); read += count
                    count = input.read(buffer)
                }
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; digest.reset(); read = 0L }
        return read
    }

    private fun canAppend(target: Uri): Boolean =
        runCatching { context.contentResolver.openOutputStream(target, "wa")?.use { } != null }.getOrDefault(false)

    /** Removes a partial download, for a user cancel or a mismatch. */
    fun discard(target: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
    }

    private fun mismatch(target: Uri, message: String): Nothing {
        discard(target)
        throw ModelMismatchException(message)
    }

    private fun adopt(uri: Uri) {
        document?.takeIf { it != uri }?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        prefs.edit().putString("uri", uri.toString()).apply()
        // Private copies only duplicate the document now, and Q4_K builds cannot run on the NPU.
        listOf(NAME, "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", "Qwen3-1.7B-Q4_K_M.gguf")
            .forEach { File(privateFile.parentFile, it).delete() }
    }

    private fun openInput(): InputStream = document?.let { context.contentResolver.openInputStream(it) } ?: privateFile.inputStream()
    private fun stamp(uri: Uri? = document): String = uri?.let { "$it:${querySize(it)}:${queryLastModified(it)}" }
        ?: "${privateFile.absolutePath}:${privateFile.length()}:${privateFile.lastModified()}"
    private fun documentSize(): Long? = document?.let { runCatching { querySize(it) }.getOrNull() }
    private fun querySize(uri: Uri): Long? = query(uri, OpenableColumns.SIZE) { getLong(0) }
    private fun queryLastModified(uri: Uri): Long? = runCatching { query(uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED) { getLong(0) } }.getOrNull()
    private fun displayName(uri: Uri): String = runCatching { query(uri, OpenableColumns.DISPLAY_NAME) { getString(0) } }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    private fun <T> query(uri: Uri, column: String, read: android.database.Cursor.() -> T): T? =
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.read() else null }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        // Q4_0 rather than Q4_K_M: the Hexagon NPU accepts Q4_0/Q8_0/MXFP4 weights only.
        const val NAME = "Qwen3-4B-Instruct-2507-Q4_0.gguf"
        const val SIZE = 2375773280L
        const val SHA256 = "e0ba675d86ab277c61701c6793659b2ae801d95e3be791464c321e6fbf613be2"
        const val DOWNLOAD_URL = "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/a06e946bb6b655725eafa393f4a9745d460374c9/$NAME"
    }
}
