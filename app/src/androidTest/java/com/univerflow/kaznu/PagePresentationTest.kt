package com.univerflow.kaznu

import android.webkit.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.web.MobilePageAdapter
import com.univerflow.kaznu.web.SitePolicy
import com.univerflow.kaznu.web.configureSecureSettings
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Offline responses, slow subresources and synthetic DOM; never reads account data. */
@RunWith(AndroidJUnit4::class)
class PagePresentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var web: WebView
    private lateinit var adapter: MobilePageAdapter
    private val imageStarted = CountDownLatch(1)
    private val imageRelease = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private val navigationStarted = CountDownLatch(1)
    private var hiddenAtStart = false

    @Before fun setUp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            web = WebView(activity)
            web.configureSecureSettings()
            activity.setContentView(web)
        }
    }

    @After fun tearDown() {
        imageRelease.countDown()
        instrumentation.runOnMainSync {
            if (::adapter.isInitialized) adapter.close()
            web.destroyFixture()
        }
        scenario.close()
    }

    private val concealed = java.util.concurrent.CopyOnWriteArrayList<Boolean>()

    private fun response(type: String, data: String) = WebResourceResponse(type, "UTF-8", ByteArrayInputStream(data.toByteArray()))

    private fun load(documentStart: Boolean = true, mobile: Boolean = true, frame: Boolean = false,
        origin: String = SitePolicy.ORIGIN) {
        val html = """<html><head><meta name='viewport' content='width=1200'><style>
            body{width:1200px;background:#ddd}.wrappertable{width:1200px}.inner{width:1100px}
            </style><script>
            window.injectedBeforePageScript=!!window.__univerPresentationV1;window.rawFrames=0;
            function sample(){if(document.body&&getComputedStyle(document.body).visibility!=='hidden'&&
                !document.documentElement.classList.contains('univer-mobile'))window.rawFrames++;
                requestAnimationFrame(sample);}requestAnimationFrame(sample);
            </script></head><body><table class='wrappertable'><tr id='l4'><td>
            <table class='inner'><tr><th>Дата</th><th>Новость</th></tr><tr class='news'><td>01.01</td><td>Fixture news</td></tr></table>
            ${if (frame) "<iframe src='/fixture/presentation/frame'></iframe>" else "<img src='/fixture/presentation/slow.svg'>"}
            </td></tr></table></body></html>"""
        val frameHtml = """<html><body><table class='wrappertable' style='width:1200px'><tr id='l4'><td>
            Frame fixture<img src='/fixture/presentation/slow.svg'></td></tr></table></body></html>"""
        instrumentation.runOnMainSync {
            adapter = MobilePageAdapter(web, allowDocumentStart = documentStart, onConcealed = { concealed += it }, onContentReady = { ready.countDown() })
            adapter.apply(mobile, AppLanguage.RUSSIAN)
            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    adapter.pageStarted(url)
                    hiddenAtStart = view.alpha == 0f
                    navigationStarted.countDown()
                }
                override fun onPageCommitVisible(view: WebView, url: String?) { adapter.apply(mobile, AppLanguage.RUSSIAN) }
                override fun onPageFinished(view: WebView, url: String?) { adapter.apply(mobile, AppLanguage.RUSSIAN); finished.countDown() }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    return when (request.url.path) {
                        "/fixture/presentation/slow.svg" -> {
                            imageStarted.countDown()
                            imageRelease.await(15, TimeUnit.SECONDS)
                            response("image/svg+xml", "<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1'/>")
                        }
                        "/fixture/presentation/frame" -> response("text/html", frameHtml)
                        else -> response("text/html", html)
                    }
                }
            }
            web.loadUrl("$origin/fixture/presentation/page")
        }
        assertTrue("Navigation start callback delivered", navigationStarted.await(10, TimeUnit.SECONDS))
        assertTrue("Slow fixture resource requested", imageStarted.await(10, TimeUnit.SECONDS))
    }

    private fun snapshot(): JSONObject {
        var result = JSONObject()
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.evaluateJavascript("""JSON.stringify({state:document.readyState,
                mobile:document.documentElement.classList.contains('univer-mobile'),
                pending:document.documentElement.hasAttribute('data-univer-app-pending'),
                style:!!document.getElementById('univer-app-mobile-style'),
                injected:!!window.injectedBeforePageScript,rawFrames:window.rawFrames||0,
                viewport:document.querySelector('meta[name=viewport]')?.content,
                frameMobile:!!document.querySelector('iframe')?.contentDocument?.documentElement.classList.contains('univer-mobile'),
                framePending:!!document.querySelector('iframe')?.contentDocument?.documentElement.hasAttribute('data-univer-app-pending')})""") {
                result = JSONObject(JSONTokener(it).nextValue() as String)
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return result
    }

    @Test fun documentStartNeverExposesDesktopAndNewsIsReadableBeforeImageFinishes() {
        instrumentation.runOnMainSync { assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) }
        load()
        assertTrue("First adapted frame arrives before the image", ready.await(6, TimeUnit.SECONDS))
        assertEquals(1L, finished.count)
        val state = snapshot()
        assertEquals("interactive", state.getString("state"))
        assertTrue(state.getBoolean("injected"))
        assertTrue(state.getBoolean("mobile")); assertTrue(state.getBoolean("style"))
        assertFalse(state.getBoolean("pending")); assertEquals(0, state.getInt("rawFrames"))
        assertEquals("width=device-width, initial-scale=1", state.getString("viewport"))
        instrumentation.runOnMainSync { assertEquals(1f, web.alpha, 0f) }
        assertEquals("What stands over the page follows it", listOf(true, false), listOf(concealed.first(), concealed.last()))
    }

    @Test fun fallbackAlsoRevealsTheAdaptedDomBeforeSlowImagesFinish() {
        load(documentStart = false)
        assertTrue(hiddenAtStart)
        assertTrue(ready.await(6, TimeUnit.SECONDS))
        assertEquals(1L, finished.count)
        val state = snapshot()
        assertEquals("interactive", state.getString("state"))
        assertFalse(state.getBoolean("injected")); assertTrue(state.getBoolean("mobile"))
        instrumentation.runOnMainSync { assertEquals(1f, web.alpha, 0f) }
    }

    @Test fun originalModeUsesOriginalViewportAndRemovingMobileRestoresTheDom() {
        load()
        assertTrue(ready.await(6, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { adapter.apply(false, AppLanguage.RUSSIAN) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var state = snapshot()
        while (state.getBoolean("mobile") && System.nanoTime() < deadline) { Thread.sleep(40); state = snapshot() }
        assertFalse(state.getBoolean("mobile")); assertFalse(state.getBoolean("style"))
        assertFalse(state.getBoolean("pending")); assertEquals("width=1200", state.getString("viewport"))
        instrumentation.runOnMainSync { assertEquals(1f, web.alpha, 0f) }
    }

    @Test fun sameOriginFrameIsAdaptedBeforeItsSlowImageFinishes() {
        load(frame = true)
        assertTrue(ready.await(6, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var state = snapshot()
        while ((!state.getBoolean("frameMobile") || state.getBoolean("framePending")) && System.nanoTime() < deadline) {
            Thread.sleep(40); state = snapshot()
        }
        assertEquals(1L, finished.count)
        assertTrue(state.getBoolean("frameMobile")); assertFalse(state.getBoolean("framePending"))
    }

    @Test fun foreignOriginReceivesNoBootstrapOrAdapter() {
        load(origin = "https://univer.kaznu.kz.evil.test")
        val state = snapshot()
        assertFalse(state.getBoolean("injected")); assertFalse(state.getBoolean("mobile"))
        assertFalse(state.getBoolean("pending")); assertFalse(state.getBoolean("style"))
        instrumentation.runOnMainSync { assertEquals(1f, web.alpha, 0f) }
    }
}
