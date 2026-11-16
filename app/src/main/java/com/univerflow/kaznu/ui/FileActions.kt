package com.univerflow.kaznu.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.provider.MediaStore
import androidx.core.net.toUri
import com.univerflow.kaznu.R
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.DownloadRecord
import com.univerflow.kaznu.domain.FilePresence
import com.univerflow.kaznu.domain.FilePresenceRules
import java.io.FileOutputStream
import kotlin.concurrent.thread

/** Hand-offs of a downloaded file to other apps. Only the file's own content URI is shared. */
object FileActions {
    fun mime(record: DownloadRecord) = record.mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"

    fun openExternal(activity: Activity, record: DownloadRecord): Boolean {
        val uri = record.uri?.toUri() ?: return false
        return try {
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(record))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); true
        } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
    }

    fun share(activity: Activity, record: DownloadRecord, language: AppLanguage): Boolean {
        val uri = record.uri?.toUri() ?: return false
        val send = Intent(Intent.ACTION_SEND).setType(mime(record)).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            activity.startActivity(Intent.createChooser(send, language.context(activity).getString(R.string.share))); true
        } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
    }

    /** Call off the main thread: the file is where it was saved. */
    fun exists(context: Context, record: DownloadRecord): Boolean = locate(context, record) == FilePresence.PRESENT

    /**
     * Call off the main thread. Whether [record]'s file is still where it was saved: GONE only on a clear answer (its
     * MediaStore row is gone or now has another folder or name, or the file does not open on mounted storage), UNKNOWN
     * when the storage does not answer, so an unanswered check never empties the list.
     */
    fun locate(context: Context, record: DownloadRecord): FilePresence {
        val uri = record.uri?.toUri() ?: return FilePresence.UNKNOWN
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return FilePresence.UNKNOWN
        return try {
            if (uri.authority == MediaStore.AUTHORITY && Build.VERSION.SDK_INT >= 29) {
                val cursor = context.contentResolver.query(uri, PLACE, null, null, null) ?: return FilePresence.UNKNOWN
                cursor.use { val found = it.moveToFirst(); FilePresenceRules.ofRow(found, record.place, if (found) placeOf(it) else null) }
            } else {
                // Files saved before Android 10 are shared through the app's FileProvider: a moved or deleted file no longer opens.
                context.contentResolver.openFileDescriptor(uri, "r")?.close() ?: return FilePresence.UNKNOWN
                FilePresence.PRESENT
            }
        } catch (failure: Exception) { FilePresenceRules.ofFailure(failure, true) }
    }

    /** Call off the main thread. Where a MediaStore file is now ("Download/…/name.pdf"); null for other files or when unread. */
    fun place(context: Context, uri: Uri): String? {
        if (uri.authority != MediaStore.AUTHORITY || Build.VERSION.SDK_INT < 29) return null
        return try { context.contentResolver.query(uri, PLACE, null, null, null)?.use { if (it.moveToFirst()) placeOf(it) else null } }
        catch (_: Exception) { null }
    }

    @android.annotation.SuppressLint("InlinedApi") // Read only on Android 10+.
    private val PLACE = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME)
    private fun placeOf(cursor: Cursor): String? {
        val folder = cursor.getString(0) ?: return null
        val name = cursor.getString(1) ?: return null
        return (folder + name).takeIf { it.length <= 1024 }
    }

    /** Prints or saves the original PDF without re-rendering it. */
    class PdfPrintAdapter(private val context: Context, private val uri: Uri, private val name: String) : PrintDocumentAdapter() {
        private val main = Handler(Looper.getMainLooper())
        override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, cancellationSignal: CancellationSignal,
            callback: LayoutResultCallback, extras: android.os.Bundle?) {
            if (cancellationSignal.isCanceled) { callback.onLayoutCancelled(); return }
            callback.onLayoutFinished(PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
        }
        override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal,
            callback: WriteResultCallback) {
            thread(name = "univer-print") {
                val result = runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        FileOutputStream(destination.fileDescriptor).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (cancellationSignal.isCanceled) return@runCatching false
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    true
                }
                main.post {
                    when {
                        result.getOrNull() == true -> callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                        cancellationSignal.isCanceled -> callback.onWriteCancelled()
                        else -> callback.onWriteFailed(null)
                    }
                }
            }
        }
    }
}
