package com.univerflow.kaznu

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.ui.DocumentViewer
import com.univerflow.kaznu.web.AutomationController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DocumentViewerTest {
    @Test fun aDocumentRejectsAnOffOriginPostBeforeTransportAndAllowsItsOwnOrigin() {
        ActivityScenario.launch<com.univerflow.kaznu.ui.DownloadsActivity>(com.univerflow.kaznu.ui.DownloadsActivity.intent(
            InstrumentationRegistry.getInstrumentation().targetContext, AppLanguage.RUSSIAN)).use { scenario ->
            scenario.onActivity { activity ->
                val host = FrameLayout(activity)
                activity.setContentView(host)
                val model = ViewModelProvider(activity, viewModelFactory {
                    initializer { UniverViewModel(com.univerflow.kaznu.data.LocalSessionRepository(
                        com.univerflow.kaznu.data.CredentialVault(activity), com.univerflow.kaznu.data.AppPreferences(activity))) }
                })[UniverViewModel::class.java]
                val viewer = DocumentViewer(activity, host, { AutomationController(it, model, activity.lifecycleScope) {} },
                    {}, { _, _, _, _ -> }, {})
                viewer.open("about:blank", AppLanguage.RUSSIAN)
                val web = viewer.webView!!
                fun request(url: String) = object : android.webkit.WebResourceRequest {
                    override fun getUrl() = android.net.Uri.parse(url)
                    override fun isForMainFrame() = true
                    override fun isRedirect() = false
                    override fun hasGesture() = false
                    override fun getMethod() = "POST"
                    override fun getRequestHeaders() = emptyMap<String, String>()
                }
                val blocked = web.webViewClient.shouldInterceptRequest(web, request("https://external.invalid/collect"))
                assertEquals("A main-frame POST must be blocked before any network request", 403, blocked?.statusCode)
                blocked?.data?.close()
                assertNull(web.webViewClient.shouldInterceptRequest(web, request("https://univer.kaznu.kz/user/ajl")))
                viewer.close(false)
            }
        }
    }

    @Test fun failedDocumentCanRetryInsideViewerWithoutLosingSourceOrErrorAfterLanguageUpdate() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        lateinit var viewer:DocumentViewer
        lateinit var host:FrameLayout
        val loaded=CountDownLatch(1)
        val initialized=CountDownLatch(1)
        var requests=0
        val recovered=CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                host=FrameLayout(activity);host.addView(WebView(activity));activity.setContentView(host)
                val model=ViewModelProvider(activity)[UniverViewModel::class.java]
                viewer=DocumentViewer(activity,host,{AutomationController(it,model,activity.lifecycleScope){}},{},{_,_,_,_->},{})
                viewer.open("about:blank",AppLanguage.RUSSIAN)
                val original=viewer.webView!!.webViewClient
                viewer.webView!!.webViewClient=object:android.webkit.WebViewClient(){
                    override fun shouldInterceptRequest(view:WebView,request:android.webkit.WebResourceRequest):android.webkit.WebResourceResponse {
                        if(!request.isForMainFrame)return android.webkit.WebResourceResponse("text/plain","UTF-8",java.io.ByteArrayInputStream(byteArrayOf()))
                        requests++
                        val status=if(requests==1)500 else 200
                        return android.webkit.WebResourceResponse("text/html","UTF-8",status,if(status==200)"OK" else "Fixture error",emptyMap(),
                            java.io.ByteArrayInputStream("<html><body>Offline document fixture</body></html>".toByteArray()))
                    }
                    override fun onPageStarted(view:WebView,url:String?,favicon:android.graphics.Bitmap?){original.onPageStarted(view,url,favicon)}
                    override fun onReceivedHttpError(view:WebView,request:android.webkit.WebResourceRequest,response:android.webkit.WebResourceResponse){original.onReceivedHttpError(view,request,response)}
                    override fun onPageFinished(view:WebView,url:String?){
                        original.onPageFinished(view,url)
                        if(url=="about:blank")initialized.countDown()
                        if(url?.contains("/student/transcript/")==true){
                            if(requests==1){
                                // Intercepted resources do not consistently emit HTTP errors across providers.
                                // Deliver the real WebView callback; all loading/retry requests remain offline.
                                val request=object:android.webkit.WebResourceRequest{
                                    override fun getUrl()=android.net.Uri.parse(url)
                                    override fun isForMainFrame()=true
                                    override fun isRedirect()=false
                                    override fun hasGesture()=false
                                    override fun getMethod()="GET"
                                    override fun getRequestHeaders()=emptyMap<String,String>()
                                }
                                original.onReceivedHttpError(view,request,android.webkit.WebResourceResponse("text/html","UTF-8",500,"Fixture error",emptyMap(),java.io.ByteArrayInputStream(byteArrayOf())))
                                loaded.countDown()
                            } else recovered.countDown()
                        }
                    }
                }
            }
            assertTrue(initialized.await(10,TimeUnit.SECONDS))
            scenario.onActivity {viewer.webView!!.loadUrl("https://univer.kaznu.kz/student/transcript/")}
            assertTrue(loaded.await(15,TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                assertEquals(android.view.View.VISIBLE,activity.findViewById<android.view.View>(R.id.document_error).visibility)
                assertTrue(activity.findViewById<android.widget.TextView>(R.id.document_error_message).text.contains("HTTP 500"))
                viewer.update(AppLanguage.ENGLISH)
                assertEquals(android.view.View.VISIBLE,activity.findViewById<android.view.View>(R.id.document_error).visibility)
                assertTrue(activity.findViewById<android.widget.TextView>(R.id.document_error_message).text.contains("HTTP 500"))
                activity.findViewById<android.view.View>(R.id.document_retry).performClick()
            }
            assertTrue(recovered.await(15,TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                assertEquals(android.view.View.GONE,activity.findViewById<android.view.View>(R.id.document_error).visibility)
                assertTrue(viewer.isOpen);assertEquals(2,host.childCount)
                // Retry is still on the layer while it fades out: it must not bring the closed document back.
                activity.findViewById<android.view.View>(R.id.document_back).performClick()
                activity.findViewById<android.view.View?>(R.id.document_retry)?.performClick()
                assertFalse(viewer.isOpen)
                viewer.close(false);(host.getChildAt(0) as WebView).destroyFixture()
            }
        }
    }
    @Test fun legacySiteBackClosesDocumentWithoutNavigatingSource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var viewer: DocumentViewer
        lateinit var host: FrameLayout
        var closed = 0
        val loaded = CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                host = FrameLayout(activity); host.addView(WebView(activity)); activity.setContentView(host)
                val model = ViewModelProvider(activity)[UniverViewModel::class.java]
                viewer = DocumentViewer(activity, host, { AutomationController(it, model, activity.lifecycleScope) {} },
                    {}, { _, _, _, _ -> }, { closed++ })
                viewer.open("about:blank", AppLanguage.RUSSIAN)
                val original = viewer.webView!!.webViewClient
                viewer.webView!!.webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse =
                        android.webkit.WebResourceResponse("text/html", "UTF-8", java.io.ByteArrayInputStream(
                            "<html><body><div class=noprint><a id=back href=# onclick='window.close();return false'>Назад.</a></div><p>Offline document fixture</p></body></html>".toByteArray()))
                    override fun onPageFinished(view: WebView, url: String?) {
                        original.onPageFinished(view, url)
                        if (url?.contains("/student/transcript/") == true) loaded.countDown()
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                        original.shouldOverrideUrlLoading(view, request)
                }
                // Intercepted locally: no request or test values are sent to Univer.
                viewer.webView!!.loadUrl("https://univer.kaznu.kz/student/transcript/")
            }
            assertTrue("Offline document loaded", loaded.await(15, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                val latch = CountDownLatch(1)
                instrumentation.runOnMainSync { viewer.webView!!.evaluateJavascript("!!document.getElementById('back')?.__univerDocBack") { ready = it == "true"; latch.countDown() } }
                assertTrue(latch.await(5, TimeUnit.SECONDS))
                if (!ready) Thread.sleep(100)
            }
            assertTrue(ready)
            instrumentation.runOnMainSync { viewer.webView!!.evaluateJavascript("document.getElementById('back').click()", null) }
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            scenario.onActivity {
                assertFalse(viewer.isOpen); assertEquals(1, closed); assertEquals(1, host.childCount)
                (host.getChildAt(0) as WebView).destroyFixture()
            }
        }
    }
    @Test fun separateWindowModesSavedStateAndBackLeaveSourceIntact() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var host: FrameLayout
            lateinit var source: WebView
            scenario.onActivity { activity ->
                host = FrameLayout(activity)
                source = WebView(activity)
                host.addView(source)
                activity.setContentView(host)
                val model = ViewModelProvider(activity)[UniverViewModel::class.java]
                var closed = 0
                val viewer = DocumentViewer(activity, host, { AutomationController(it, model, activity.lifecycleScope) {} },
                    {}, { _, _, _, _ -> }, { closed++ })
                viewer.open("about:blank", AppLanguage.RUSSIAN)
                assertTrue(viewer.isOpen)
                assertEquals(2, host.childCount)
                assertSame(source, host.getChildAt(0))
                assertFalse(viewer.webView!!.settings.allowFileAccess)
                assertEquals("Оригинал", activity.findViewById<android.widget.TextView>(R.id.document_mode).text.toString())
                activity.findViewById<android.view.View>(R.id.document_mode).performClick()
                val saved = viewer.save()!!
                assertFalse(saved.getBoolean("readable"))
                viewer.close(false)
                assertEquals("A session that ends removes the layer at once", 1, host.childCount)
                viewer.open("about:blank", AppLanguage.ENGLISH, saved)
                assertEquals("Read", activity.findViewById<android.widget.TextView>(R.id.document_mode).text.toString())
                activity.findViewById<android.view.View>(R.id.document_back).performClick()
                assertFalse(viewer.isOpen)
                assertNull(viewer.webView)
                assertEquals(1, closed)
            }
            // The layer fades out over the page first; the page itself is never touched.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var left = false
            while (!left && System.nanoTime() < deadline) {
                scenario.onActivity { left = host.childCount == 1 }
                if (!left) Thread.sleep(20)
            }
            assertTrue("The closed layer leaves its host", left)
            scenario.onActivity {
                assertSame(source, host.getChildAt(0))
                source.destroyFixture()
            }
        }
    }

    // Review finding: the reading-mode button captured the language the window opened in, so after a switch to English
    // one tap turned the bar (and the page's mobile labels) back to Russian.
    @Test fun theReadingModeButtonKeepsTheCurrentLanguage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val host = FrameLayout(activity)
                activity.setContentView(host)
                val model = ViewModelProvider(activity)[UniverViewModel::class.java]
                val viewer = DocumentViewer(activity, host, { AutomationController(it, model, activity.lifecycleScope) {} }, {}, { _, _, _, _ -> }, {})
                viewer.open("about:blank", AppLanguage.RUSSIAN)
                viewer.applyLanguage(AppLanguage.ENGLISH)
                val mode = activity.findViewById<android.widget.TextView>(R.id.document_mode)
                assertEquals("Original", mode.text.toString())
                mode.performClick()
                assertEquals("Read", mode.text.toString())
                assertEquals("Document", activity.findViewById<android.widget.TextView>(R.id.document_title).text.toString())
                viewer.close(false)
            }
        }
    }
}
