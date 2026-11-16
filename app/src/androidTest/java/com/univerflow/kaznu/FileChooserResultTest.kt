package com.univerflow.kaznu

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.ui.FileChooser
import org.junit.Assert.assertEquals
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
        override fun getMode() = MODE_OPEN
        override fun getAcceptTypes() = arrayOf(".pdf")
        override fun isCaptureEnabled() = false
        override fun getTitle(): CharSequence? = null
        override fun getFilenameHint(): String? = null
        override fun createIntent() = Intent(Intent.ACTION_GET_CONTENT)
    }

    /** What the page's input receives when the picker returns [picked]. */
    private fun received(picked: Uri): List<Uri> {
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_CHOOSER),
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(picked)), true)
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

    @Test fun onlyWhatAProviderSharesReachesThePage() {
        val private = File(context.filesDir, "qa-chooser-private.txt").apply { writeText("private") }
        val shared = File(context.cacheDir, "camera/qa-chooser-shared.txt").apply { parentFile!!.mkdirs(); writeText("shared") }
        try {
            val content = FileProvider.getUriForFile(context, "${context.packageName}.downloads", shared)
            assertEquals(listOf(content), received(content))
            // A picker that answers with a path could name the app's own files, which WebView would read and upload.
            assertEquals(emptyList<Uri>(), received(Uri.fromFile(private)))
        } finally {
            private.delete(); shared.delete()
        }
    }
}
