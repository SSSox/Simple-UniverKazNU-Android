package com.univerflow.kaznu

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.ui.FileChooser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** What the app hands to a page's file input when the picker answers; the picker itself is replaced by its answer. */
@RunWith(AndroidJUnit4::class)
class FileChooserResultTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private val anyFile = object : WebChromeClient.FileChooserParams() {
        override fun getMode() = MODE_OPEN_MULTIPLE
        override fun getAcceptTypes() = arrayOf(".pdf")
        override fun isCaptureEnabled() = false
        override fun getTitle(): CharSequence? = null
        override fun getFilenameHint(): String? = null
        override fun createIntent() = Intent(Intent.ACTION_GET_CONTENT)
    }

    /** What the page's input receives when the picker returns [picked] (single data URI or ClipData for multiple). */
    private fun received(picked: List<Uri>, grantRead: Boolean = false): List<Uri> {
        val resultIntent = Intent().apply {
            when (picked.size) {
                0 -> Unit
                1 -> setData(picked.single())
                else -> {
                    val clip = ClipData.newRawUri("", picked.first())
                    picked.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                    clipData = clip
                }
            }
            if (grantRead) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val monitor = instrumentation.addMonitor(
            IntentFilter(Intent.ACTION_CHOOSER),
            Instrumentation.ActivityResult(Activity.RESULT_OK, resultIntent),
            true,
        )
        val answers = LinkedBlockingQueue<List<Uri>>()
        try {
            return ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val chooser = MainActivity::class.java.getDeclaredField("fileChooser").apply { isAccessible = true }.get(activity) as FileChooser
                    chooser.show(ValueCallback { answers.put(it?.toList().orEmpty()) }, anyFile)
                }
                checkNotNull(answers.poll(15, TimeUnit.SECONDS)) { "The picker's answer never reached the page" }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun received(picked: Uri, grantRead: Boolean = false): List<Uri> = received(listOf(picked), grantRead)

    private fun pickerCall(method: String, uri: Uri) {
        context.contentResolver.call(Uri.parse("content://com.univerflow.kaznu.test.picker"), method, uri.toString(), null)
    }

    @Test fun onlyExplicitlyGrantedExternalProvidersReachThePage() {
        val private = File(context.filesDir, "qa-chooser-private.txt").apply { writeText("private") }
        val report = File(context.cacheDir, "reports/qa-chooser-report.pdf").apply { parentFile!!.mkdirs(); writeText("report") }
        val stalePhoto = File(context.cacheDir, "camera/qa-chooser-shared.jpg").apply { parentFile!!.mkdirs(); writeText("shared") }
        val grantedExternal = Uri.parse("content://com.univerflow.kaznu.test.picker/document/granted")
        val ungrantedExternal = Uri.parse("content://com.univerflow.kaznu.test.picker/document/ungranted")
        val openExternal = Uri.parse("content://com.univerflow.kaznu.test.open/document/1")
        val nonexistentExternal = Uri.parse("content://com.example.picker/document/1")
        val reportUri = FileProvider.getUriForFile(context, "${context.packageName}.downloads", report)
        val stalePhotoUri = FileProvider.getUriForFile(context, "${context.packageName}.downloads", stalePhoto)
        val pid = Process.myPid()
        val uid = Process.myUid()
        try {
            // Confirm openExternal is readable by the app without any URI grant, yet checkUriPermission is denied.
            context.contentResolver.query(openExternal, null, null, null, null).use { assertNotNull(it) }
            assertEquals(
                PackageManager.PERMISSION_DENIED,
                context.checkUriPermission(openExternal, pid, uid, Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            // Flag-only results without an Android URI grant are refused for nonexistent, ungranted, and open providers.
            assertEquals(
                PackageManager.PERMISSION_DENIED,
                context.checkUriPermission(grantedExternal, pid, uid, Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            assertEquals(emptyList<Uri>(), received(nonexistentExternal, grantRead = true))
            assertEquals(emptyList<Uri>(), received(openExternal, grantRead = true))
            assertEquals(emptyList<Uri>(), received(grantedExternal, grantRead = true))

            // Have the external provider issue a real Android read grant for grantedExternal only.
            pickerCall("grant", grantedExternal)
            assertEquals(
                PackageManager.PERMISSION_GRANTED,
                context.checkUriPermission(grantedExternal, pid, uid, Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            assertEquals(
                PackageManager.PERMISSION_DENIED,
                context.checkUriPermission(ungrantedExternal, pid, uid, Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )

            // Own FileProvider URIs (cached reports, earlier photos) are never handed back to a page,
            // even with FLAG_GRANT_READ_URI_PERMISSION and an explicit grant.
            context.grantUriPermission(context.packageName, reportUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertEquals(emptyList<Uri>(), received(reportUri, grantRead = false))
            assertEquals(emptyList<Uri>(), received(reportUri, grantRead = true))
            assertEquals(emptyList<Uri>(), received(stalePhotoUri, grantRead = true))

            // A file: URI or a granted URI missing the result Intent flag is refused.
            assertEquals(emptyList<Uri>(), received(Uri.fromFile(private), grantRead = true))
            assertEquals(emptyList<Uri>(), received(grantedExternal, grantRead = false))

            // A real provider grant with FLAG_GRANT_READ_URI_PERMISSION reaches the page,
            // while an ungranted URI on the same provider is refused.
            assertEquals(listOf(grantedExternal), received(grantedExternal, grantRead = true))
            assertEquals(emptyList<Uri>(), received(ungrantedExternal, grantRead = true))

            // Mixed ClipData results are checked per URI: only the genuinely granted external URI survives.
            assertEquals(
                listOf(grantedExternal),
                received(
                    listOf(
                        grantedExternal,
                        ungrantedExternal,
                        openExternal,
                        nonexistentExternal,
                        reportUri,
                        Uri.fromFile(private),
                    ),
                    grantRead = true,
                ),
            )
            assertEquals(
                emptyList<Uri>(),
                received(listOf(grantedExternal, ungrantedExternal), grantRead = false),
            )
        } finally {
            runCatching { pickerCall("revoke", grantedExternal) }
            runCatching { context.revokeUriPermission(reportUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            private.delete(); report.delete(); stalePhoto.delete()
        }
    }

    @Test fun exactCurrentCameraSnapshotStillReachesThePageWithoutGrantFlag() {
        val snapshotFile = File(context.cacheDir, "camera/qa-chooser-current-snapshot.jpg").apply {
            parentFile!!.mkdirs()
            writeText("jpeg-bytes")
        }
        val snapshotUri = FileProvider.getUriForFile(context, "${context.packageName}.downloads", snapshotFile)
        val report = File(context.cacheDir, "reports/qa-chooser-report-2.pdf").apply {
            parentFile!!.mkdirs()
            writeText("report")
        }
        val reportUri = FileProvider.getUriForFile(context, "${context.packageName}.downloads", report)
        val photoField = FileChooser::class.java.getDeclaredField("photo").apply { isAccessible = true }
        val deliverMethod = FileChooser::class.java.getDeclaredMethod("deliver", Int::class.javaPrimitiveType, Intent::class.java).apply {
            isAccessible = true
        }
        val callbackField = FileChooser::class.java.getDeclaredField("callback").apply { isAccessible = true }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val chooser = MainActivity::class.java.getDeclaredField("fileChooser").apply { isAccessible = true }.get(activity) as FileChooser

                    // Camera saves to EXTRA_OUTPUT and returns empty data with no grant flag -> snapshot accepted.
                    var got: List<Uri>? = null
                    callbackField.set(chooser, ValueCallback<Array<Uri>> { got = it?.toList().orEmpty() })
                    photoField.set(chooser, snapshotFile to snapshotUri)
                    deliverMethod.invoke(chooser, Activity.RESULT_OK, null)
                    assertEquals(listOf(snapshotUri), got)

                    // Camera echoes the exact snapshot URI without grant flag -> snapshot accepted.
                    got = null
                    callbackField.set(chooser, ValueCallback<Array<Uri>> { got = it?.toList().orEmpty() })
                    photoField.set(chooser, snapshotFile to snapshotUri)
                    deliverMethod.invoke(chooser, Activity.RESULT_OK, Intent().setData(snapshotUri))
                    assertEquals(listOf(snapshotUri), got)

                    // A different own-provider URI during the same request is still rejected.
                    got = null
                    callbackField.set(chooser, ValueCallback<Array<Uri>> { got = it?.toList().orEmpty() })
                    photoField.set(chooser, snapshotFile to snapshotUri)
                    deliverMethod.invoke(
                        chooser,
                        Activity.RESULT_OK,
                        Intent().setData(reportUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                    assertEquals(emptyList<Uri>(), got)
                }
            }
        } finally {
            snapshotFile.delete(); report.delete()
        }
    }
}
