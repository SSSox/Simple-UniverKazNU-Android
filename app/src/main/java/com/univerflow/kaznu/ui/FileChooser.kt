package com.univerflow.kaznu.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File

/** Maps a page's `accept` list to MIME types for the system picker. Pure: tested without Android. */
object FileChooserTypes {
    /** Empty means any file. One unknown extension also means any file: the site validates the upload itself. */
    fun mimeTypes(accept: Array<String>?, byExtension: (String) -> String?): List<String> {
        val entries = accept.orEmpty().flatMap { it.split(',', ' ', ';') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (entries.isEmpty() || entries.any { it == "*" || it == "*/*" }) return emptyList()
        val types = entries.map { entry ->
            when {
                entry.startsWith('.') -> byExtension(entry.drop(1)) ?: return emptyList()
                Regex("^[a-z0-9.+-]+/(\\*|[a-z0-9.+-]+)$").matches(entry) -> entry
                else -> return emptyList()
            }
        }.distinct()
        return types
    }

    /** A photo is offered when the page accepts any file or an image. */
    fun acceptsPhotos(types: List<String>) = types.isEmpty() || types.any { it == "image/*" || it == "image/jpeg" }
}

/**
 * The WebView `<input type=file>` bridge: documents from the system picker or a new camera photo. Photos stay in the
 * app cache, shared only through the app's FileProvider; logout deletes them.
 */
class FileChooser(private val activity: ComponentActivity, private val title: () -> String, private val failed: () -> Unit) {
    private var callback: ValueCallback<Array<Uri>>? = null
    private var photo: Pair<File, Uri>? = null
    private val directory get() = File(activity.cacheDir, "camera")
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        deliver(result.resultCode, result.data)
    }

    fun show(next: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        cancel()
        callback = next
        val types = FileChooserTypes.mimeTypes(params.acceptTypes) { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        val content = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(types.singleOrNull() ?: "*/*")
        if (types.size > 1) content.putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
        if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) content.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        val chooser = Intent.createChooser(content, title())
        if (FileChooserTypes.acceptsPhotos(types)) cameraIntent()?.let { chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(it)) }
        return try { launcher.launch(chooser); true }
        catch (_: ActivityNotFoundException) { deliver(Activity.RESULT_CANCELED, null); failed(); true }
    }

    private fun cameraIntent(): Intent? = runCatching {
        val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (capture.resolveActivity(activity.packageManager) == null) return null
        directory.mkdirs()
        // Earlier photos were uploaded or abandoned; a day is enough for a slow form.
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
        val file = File.createTempFile("photo-", ".jpg", directory)
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.downloads", file)
        photo = file to uri
        capture.putExtra(MediaStore.EXTRA_OUTPUT, uri).apply {
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.getOrNull()

    private fun deliver(resultCode: Int, data: Intent?) {
        val pending = callback ?: return
        callback = null
        val taken = photo?.takeIf { it.first.length() > 0 }
        val picked = if (resultCode != Activity.RESULT_OK) null else data?.clipData?.let { clip ->
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.takeIf { it.isNotEmpty() }?.toTypedArray()
        } ?: data?.data?.let { arrayOf(it) } ?: taken?.let { arrayOf(it.second) }
        if (picked == null || taken == null || picked.none { it == taken.second }) photo?.first?.delete()
        photo = null
        pending.onReceiveValue(picked)
    }

    /** WebView requires exactly one answer for every chooser it opened. */
    fun cancel() {
        callback?.onReceiveValue(null)
        callback = null
        photo?.first?.delete(); photo = null
    }

    fun clear() { cancel(); directory.deleteRecursively() }
}
