package com.univerflow.kaznu.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
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
 * Filters URIs returned by a file chooser or camera intent. Pure: tested without Android.
 *
 * A third-party picker can return a content: URI of this app's own FileProvider (such as a cached grade report or an
 * earlier photo), which WebView would read with the app's own UID access, or set FLAG_GRANT_READ_URI_PERMISSION on a
 * URI that was never actually granted to this app. Only the non-empty snapshot created for the current request may
 * come from an own provider; every other URI must come from another provider with both the read-grant flag on the
 * result Intent and an active Android URI read permission grant for this process.
 */
object FileChooserResults {
    data class PickedUri(val value: String, val scheme: String?, val authority: String?)

    fun bareAuthority(authority: String?): String? {
        val raw = authority?.substringAfterLast('@')?.trim() ?: return null
        if (raw.isEmpty() || raw.any { it.isWhitespace() || it.isISOControl() || it == '/' || it == '\\' }) return null
        return raw
    }

    fun isOwnAuthority(authority: String?, ownAuthority: String, resolvesToSelf: (String) -> Boolean = { false }): Boolean {
        val bare = bareAuthority(authority) ?: return false
        val host = bare.substringBefore(':')
        val normalized = bare.removeSuffix(".")
        val normalizedHost = host.removeSuffix(".")
        if (normalized.equals(ownAuthority, ignoreCase = true) || normalizedHost.equals(ownAuthority, ignoreCase = true)) return true
        return resolvesToSelf(bare) || (host != bare && resolvesToSelf(host))
    }

    fun <T> select(
        resultOk: Boolean,
        fromResult: List<T>,
        readFlag: Boolean,
        snapshot: T?,
        ownAuthority: String,
        describe: (T) -> PickedUri,
        resolvesToSelf: (String) -> Boolean = { false },
        hasUriReadGrant: (T) -> Boolean,
    ): List<T> {
        if (!resultOk) return emptyList()
        val candidates = if (fromResult.isNotEmpty()) fromResult else listOfNotNull(snapshot)
        val snapshotValue = snapshot?.let { describe(it).value }
        return candidates.filter { item ->
            val uri = describe(item)
            if (snapshotValue != null && uri.value == snapshotValue) true
            else readFlag &&
                uri.scheme == ContentResolver.SCHEME_CONTENT &&
                bareAuthority(uri.authority) != null &&
                !isOwnAuthority(uri.authority, ownAuthority, resolvesToSelf) &&
                hasUriReadGrant(item)
        }
    }
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
        val fromResult = data?.clipData?.let { clip ->
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.takeIf { it.isNotEmpty() }
        } ?: data?.data?.let { listOf(it) }.orEmpty()
        val readFlag = data != null && (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
        val ownAuthority = "${activity.packageName}.downloads"
        val pid = Process.myPid()
        val uid = Process.myUid()
        val picked = FileChooserResults.select(
            resultOk = resultCode == Activity.RESULT_OK,
            fromResult = fromResult,
            readFlag = readFlag,
            snapshot = taken?.second,
            ownAuthority = ownAuthority,
            describe = { FileChooserResults.PickedUri(it.toString(), it.scheme, it.authority) },
            resolvesToSelf = { authority ->
                runCatching { activity.packageManager.resolveContentProvider(authority, 0)?.packageName == activity.packageName }
                    .getOrDefault(false)
            },
            hasUriReadGrant = { uri ->
                runCatching {
                    activity.checkUriPermission(uri, pid, uid, Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
                        PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)
            },
        ).takeIf { it.isNotEmpty() }?.toTypedArray()
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
