package com.univerflow.kaznu.report

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import androidx.core.content.FileProvider
import com.univerflow.kaznu.ui.FileActions
import java.io.File

/**
 * The made report files: kept in the app's cache (never in public storage), shared through the FileProvider with a
 * read grant for the chosen app. Removed on logout, and an hour old at the next app start or report.
 */
object GradeReportFiles {
    private const val FOLDER = "reports"
    private const val KEEP_MS = 60 * 60 * 1000L
    private val UNSAFE = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+")
    private val SPACES = Regex("\\s+")

    fun folder(context: Context) = File(context.cacheDir, FOLDER)

    /** A new file named "[title].[extension]" in its own folder, so two reports a minute apart never overwrite each other. */
    fun create(context: Context, title: String, extension: String, now: Long = System.currentTimeMillis()): File {
        prune(context, now)
        val name = safeName(title).ifEmpty { "report" } + "." + extension
        val folder = File(folder(context), now.toString(36)).apply { mkdirs() }
        return File(folder, name)
    }

    fun safeName(title: String) = title.replace(UNSAFE, " ").replace(SPACES, " ").trim().take(80).trim()

    /** Older reports: a pending share needs its file, an hour is ample for that. */
    fun prune(context: Context, now: Long = System.currentTimeMillis()) {
        folder(context).listFiles()?.forEach { if (now - it.lastModified() > KEEP_MS) it.deleteRecursively() }
    }

    fun clear(context: Context) { folder(context).deleteRecursively() }

    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".downloads", file)

    /**
     * The system share sheet for one report's [files] (a PDF, or its page images in order); the receiving app may read
     * only these files. Which of several pages to send is chosen there, as for any set of pictures.
     */
    fun share(context: Context, files: List<File>, type: String, title: String): Intent {
        val uris = files.map { uri(context, it) }
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.setType(type).putExtra(Intent.EXTRA_TITLE, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The read grant goes with the clip: every file of the report is in it.
        send.clipData = ClipData.newRawUri(title, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        return Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun print(context: Context, file: File, title: String) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(title, FileActions.PdfPrintAdapter(context, uri(context, file), file.name),
            PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
    }
}
