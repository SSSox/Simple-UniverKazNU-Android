package com.univerflow.kaznu.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import com.univerflow.kaznu.domain.DownloadRecord
import com.univerflow.kaznu.domain.DownloadState
import com.univerflow.kaznu.domain.FileKind
import com.univerflow.kaznu.preview.TextExtract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The text of downloaded files for the search in "Downloads". Each file is read once; its text is kept in the
 * app's cache folder (never backed up, re-created when missing) and deleted with the file's entry.
 */
class DownloadTextIndex(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val folder = File(context.applicationContext.cacheDir, "download-text")

    /** Whether this file's type has text the app can read. */
    fun searchable(record: DownloadRecord): Boolean =
        record.state == DownloadState.COMPLETED && record.uri != null && (record.kind == FileKind.TEXT || TextExtract.searchable(record.fileName))

    /** The file's text ("" when it has none, such as a scanned PDF), or null when it cannot be read now. */
    suspend fun text(record: DownloadRecord): String? = withContext(Dispatchers.IO) {
        if (!searchable(record)) return@withContext null
        val cached = AtomicFile(File(folder, record.id + ".txt"))
        runCatching { cached.readFully().toString(Charsets.UTF_8) }.getOrNull()?.let { return@withContext it }
        val coroutine = currentCoroutineContext()
        val text = try { extract(record) { coroutine.ensureActive() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            // A file too large for memory has no searchable text; the search goes on with the others.
            catch (_: OutOfMemoryError) { null } ?: return@withContext null
        folder.mkdirs()
        val output = runCatching { cached.startWrite() }.getOrNull()
        if (output != null) {
            try { output.write(text.toByteArray(Charsets.UTF_8)); cached.finishWrite(output) }
            catch (_: Exception) { cached.failWrite(output) } // The cache is a convenience; the text is still returned.
        }
        text
    }

    fun forget(id: String) { runCatching { AtomicFile(File(folder, "$id.txt")).delete() } }

    /** Drops the text of entries that left the list in other ways (the history keeps only the newest entries). */
    suspend fun prune(keep: Set<String>) = withContext(Dispatchers.IO) {
        // "id.txt" and AtomicFile's "id.txt.bak" / "id.txt.new".
        folder.listFiles()?.forEach { file -> if (file.name.substringBefore(".txt") !in keep) file.delete() }
    }

    /**
     * A PDF on Android 15+ is read page by page by the platform; everything else (and a PDF the platform
     * finds no text in) goes through the app's own readers, which take the format from the file's bytes.
     */
    private fun extract(record: DownloadRecord, checkActive: () -> Unit): String? {
        val uri = record.uri!!.toUri()
        if (record.kind == FileKind.PDF && Build.VERSION.SDK_INT >= 35) {
            runCatching { pdf(uri, checkActive) }.onFailure { if (it is CancellationException) throw it }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return resolver.openInputStream(uri)?.use { TextExtract.text(it, record.fileName, checkActive) }
    }

    /** Android 15 reads the text layer of PDF pages; scanned pages have none. */
    @RequiresApi(35)
    private fun pdf(uri: Uri, checkActive: () -> Unit): String? = resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            val text = StringBuilder()
            for (index in 0 until minOf(renderer.pageCount, PAGE_LIMIT)) {
                checkActive()
                renderer.openPage(index).use { page -> page.textContents.forEach { text.append(it.text).append('\n') } }
                if (text.length >= TextExtract.LIMIT) break
            }
            text.toString().take(TextExtract.LIMIT)
        }
    }

    private companion object { const val PAGE_LIMIT = 500 }
}
