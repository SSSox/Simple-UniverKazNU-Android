package com.univerflow.kaznu.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.univerflow.kaznu.web.DownloadFileNames
import java.io.Closeable
import java.io.File
import java.io.OutputStream

/** A failed/cancelled transfer never publishes a partial document. Call on an IO dispatcher. */
class PublicDownloadStore(private val context: Context) {
    interface Pending : Closeable {
        val output: OutputStream
        fun publish(): Uri
    }

    fun create(filename: String, mime: String): Pending {
        val name = DownloadFileNames.safe(filename)
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            val stream = try { checkNotNull(resolver.openOutputStream(uri)) }
            catch (failure: Exception) { resolver.delete(uri, null, null); throw failure }
            return object : Pending {
                override val output = stream
                private var published = false
                override fun publish(): Uri {
                    output.close()
                    check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
                    published = true
                    return uri
                }
                override fun close() { try { output.close() } finally { if (!published) resolver.delete(uri, null, null) } }
            }
        }
        @Suppress("DEPRECATION")
        val folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        check(folder.isDirectory || folder.mkdirs())
        val temporary = File.createTempFile(".univer-", ".part", folder)
        return object : Pending {
            override val output = temporary.outputStream()
            private var published = false
            override fun publish(): Uri = synchronized(legacyNames) {
                output.close()
                var destination = File(folder, name)
                var duplicate = 1
                while (destination.exists()) destination = File(folder, DownloadFileNames.unique(name, (++duplicate).toString()))
                check(temporary.renameTo(destination))
                published = true
                FileProvider.getUriForFile(context, context.packageName + ".downloads", destination)
            }
            override fun close() { try { output.close() } finally { if (!published) temporary.delete() } }
        }
    }
    companion object { private val legacyNames = Any() }
}
