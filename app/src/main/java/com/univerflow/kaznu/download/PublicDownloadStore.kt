package com.univerflow.kaznu.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.univerflow.kaznu.web.DownloadFileNames
import com.univerflow.kaznu.domain.StudyFolders
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.io.InputStream

/** A failed/cancelled transfer never publishes a partial document. Call on an IO dispatcher. */
class PublicDownloadStore(private val context: Context, private val now: () -> Long = System::currentTimeMillis) {
    interface Pending : Closeable {
        val output: OutputStream
        fun openInput(): InputStream
        fun publish(filename: String? = null, mime: String? = null): Uri
    }

    fun create(filename: String, mime: String, subfolder: String? = null): Pending {
        val name = DownloadFileNames.safe(filename)
        val initialMime = mime
        val relative = StudyFolders.checked(subfolder)
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + (relative?.let { "$it/" } ?: ""))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            val stream = try { checkNotNull(resolver.openOutputStream(uri)) }
            catch (failure: Exception) { resolver.delete(uri, null, null); throw failure }
            return object : Pending {
                override val output = stream
                private var published = false
                override fun openInput(): InputStream { output.flush(); return checkNotNull(resolver.openInputStream(uri)) }
                override fun publish(filename: String?, mime: String?): Uri {
                    output.close()
                    check(resolver.update(uri, ContentValues().apply {
                        if (filename != null) put(MediaStore.Downloads.DISPLAY_NAME, DownloadFileNames.safe(filename))
                        if (mime != null) put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }, null, null) == 1)
                    published = true
                    return uri
                }
                override fun close() { try { output.close() } finally { if (!published) resolver.delete(uri, null, null) } }
            }
        }
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = if (relative == null) downloads else File(downloads, relative).also {
            check(it.canonicalPath.startsWith(downloads.canonicalPath + File.separator))
        }
        check(folder.isDirectory || folder.mkdirs())
        // A process killed mid-download never reached close(): its partial file goes when a day has passed, longer
        // than any transfer runs. Another copy of the app downloading right now keeps its own.
        folder.listFiles { file -> file.isFile && PARTIAL_NAME.matches(file.name) && now() - file.lastModified() > STALE_AFTER }
            ?.forEach { it.delete() }
        val temporary = File.createTempFile(".univer-", ".part", folder)
        return object : Pending {
            override val output = temporary.outputStream()
            private var published = false
            override fun openInput(): InputStream { output.flush(); return temporary.inputStream() }
            override fun publish(filename: String?, mime: String?): Uri = synchronized(legacyNames) {
                output.close()
                val finalName = filename?.let(DownloadFileNames::safe) ?: name
                var destination = File(folder, finalName)
                var duplicate = 1
                // Reserve the name with O_EXCL semantics; another downloader cannot win the exists/rename gap.
                while (!destination.createNewFile()) destination = File(folder, DownloadFileNames.unique(finalName, (++duplicate).toString()))
                if (!temporary.renameTo(destination)) {
                    if (destination.length() == 0L) destination.delete()
                    error("Could not publish download")
                }
                published = true
                FileProvider.getUriForFile(context, context.packageName + ".downloads", destination).also { index(destination, mime ?: initialMime) }
            }
            override fun close() { try { output.close() } finally { if (!published) temporary.delete() } }
        }
    }

    /**
     * Before Android 10 a file written with java.io is not in MediaStore, so apps that query it (and MTP before Android 9)
     * miss the file until a later media scan. The scan runs asynchronously; a failed request must not fail a saved download.
     */
    private fun index(file: File, mime: String) {
        try { MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.absolutePath), arrayOf(mime), null) }
        catch (_: Exception) { }
    }
    companion object {
        private val legacyNames = Any()
        private const val STALE_AFTER = 86_400_000L
        private val PARTIAL_NAME = Regex("^\\.univer-[0-9]{8,20}\\.part$")
    }
}
