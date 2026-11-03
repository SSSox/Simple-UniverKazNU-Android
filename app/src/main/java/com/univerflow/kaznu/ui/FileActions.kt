package com.univerflow.kaznu.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.univerflow.kaznu.R
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.DownloadRecord
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
        } catch (_: ActivityNotFoundException) { false }
    }

    /** Call off the main thread. A moved or deleted file no longer resolves through its content URI. */
    fun exists(context: Context, record: DownloadRecord): Boolean {
        val uri = record.uri?.toUri() ?: return false
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { it.moveToFirst() } ?: false
        } catch (_: Exception) { false }
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
