package com.univerflow.kaznu

import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.data.AppPreferences
import com.univerflow.kaznu.data.CredentialVault
import com.univerflow.kaznu.data.LocalSessionRepository
import com.univerflow.kaznu.ui.DocumentViewer
import com.univerflow.kaznu.web.AutomationController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A Moodle-style `<input type=file>` in the service window reaches the native picker and the chosen
 * file is readable by the page, with file/content URL access still disabled. Offline fixture only.
 */
@RunWith(AndroidJUnit4::class)
class FileUploadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun moodleUploadInputReceivesThePickedFile() {
        val context = instrumentation.targetContext
        val vault = CredentialVault(context, "univer.test.upload")
        val prefsName = "univer.test.upload.preferences"
        context.deleteSharedPreferences(prefsName)
        val model = UniverViewModel(LocalSessionRepository(vault, AppPreferences(context, prefsName)))
        val store = ViewModelStore().apply { put("fixture", model) }
        runBlocking { model.state.first { it.ready } }
        val file = File(context.cacheDir, "camera/upload-fixture.txt").apply { parentFile!!.mkdirs(); writeText("Fixture upload body") }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.downloads", file)
        val page = """<html><head><meta name=viewport content='width=device-width,initial-scale=1'></head><body style='margin:0'>
            <input type=file id=f accept='.txt,.pdf' style='position:fixed;left:0;top:0;width:100vw;height:100vh;opacity:.01'>
            <script>document.getElementById('f').onchange=function(){var r=new FileReader(),x=this.files[0];
              r.onload=function(){document.title='read:'+x.name.length+':'+r.result;};r.readAsText(x);};</script></body></html>"""
        var accept: List<String> = emptyList()
        var choosers = 0
        lateinit var viewer: DocumentViewer
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val host = FrameLayout(activity)
                    activity.setContentView(host)
                    viewer = DocumentViewer(activity, host, { AutomationController(it, model, activity.lifecycleScope) {} },
                        { fail("No navigation expected") }, { _, _, _, _ -> }, {},
                        chooseFiles = { callback, params ->
                            choosers++
                            accept = params.acceptTypes.toList()
                            callback.onReceiveValue(arrayOf(uri))
                            true
                        })
                    viewer.open("about:blank", AppLanguage.RUSSIAN, service = true)
                    val web = viewer.webView!!
                    val client = web.webViewClient
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                            WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(
                                (if (request.url.host == "dl.kaznu.edu.kz") page else "<html></html>").toByteArray()))
                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) = client.onPageStarted(view, url, favicon)
                        override fun onPageFinished(view: WebView, url: String?) = client.onPageFinished(view, url)
                    }
                    web.loadUrl("https://dl.kaznu.edu.kz/mod/assign/view.php?id=5&action=editsubmission")
                }
                await(viewer, "!!document.getElementById('f')") { it == "true" }
                Thread.sleep(300)
                scenario.onActivity {
                    val web = viewer.webView!!
                    assertFalse(web.settings.allowFileAccess || web.settings.allowContentAccess)
                    val x = web.width / 2f; val y = web.height / 2f; val time = SystemClock.uptimeMillis()
                    MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0).also { web.dispatchTouchEvent(it); it.recycle() }
                    MotionEvent.obtain(time, time + 60, MotionEvent.ACTION_UP, x, y, 0).also { web.dispatchTouchEvent(it); it.recycle() }
                }
                await(viewer, "document.title") { it.contains("read:") }
                assertEquals("\"read:${file.name.length}:Fixture upload body\"", js(viewer, "document.title"))
                assertEquals(1, choosers)
                assertEquals(listOf(".txt", ".pdf"), accept.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() })
            }
        } finally {
            instrumentation.runOnMainSync { runCatching { viewer.close(false) }; store.clear() }
            file.delete(); vault.delete(); context.deleteSharedPreferences(prefsName)
        }
    }

    private fun js(viewer: DocumentViewer, script: String): String {
        var result = ""
        val latch = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync { viewer.webView?.evaluateJavascript(script) { result = it; latch.countDown() } ?: latch.countDown() }
        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    private fun await(viewer: DocumentViewer, script: String, condition: (String) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline && !condition(js(viewer, script))) Thread.sleep(150)
        assertTrue("Upload fixture reached the expected state: ${js(viewer, script)}", condition(js(viewer, script)))
    }
}
