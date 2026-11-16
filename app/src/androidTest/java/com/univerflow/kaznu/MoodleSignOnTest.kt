package com.univerflow.kaznu

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelStore
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.data.AppPreferences
import com.univerflow.kaznu.data.CredentialVault
import com.univerflow.kaznu.data.LocalSessionRepository
import com.univerflow.kaznu.ui.DocumentViewer
import com.univerflow.kaznu.web.AutomationController
import com.univerflow.kaznu.web.AutomationStatus
import com.univerflow.kaznu.web.SingleSignOnPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** All requests, including credential POSTs, are intercepted; no synthetic data leaves the fixture. */
@RunWith(AndroidJUnit4::class)
class MoodleSignOnTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun dynamicUniverFormPreservesGetHandOffAndSource() = handOff("GET")
    @Test fun dynamicUniverFormPreservesPostHandOffAndSource() = handOff("POST")
    @Test fun failedRedirectStopsUntilExplicitRetryWithoutAffectingUniver() = handOff("FAIL")
    @Test fun endedMoodleSessionSignsInAgainThroughUniverAutomatically() = handOff("EXPIRED")
    @Test fun univerPageAfterLoginContinuesTheHandOffToMoodle() = handOff("DETOUR")
    @Test fun moodleGuestAccessAfterEndedSessionSignsInAgain() = handOff("GUEST")
    @Test fun renewedMoodleSessionReturnsToTheSamePageAndBackStaysInMoodle() = handOff("RESUME")
    @Test fun moodleOwnLogoutClosesTheWindowInsteadOfSigningInAgain() = handOff("LOGOUT")

    private fun handOff(method: String) {
        val context = instrumentation.targetContext
        val vault = CredentialVault(context, "univer.test.moodle.$method")
        val prefsName = "univer.test.moodle.$method.preferences"
        context.deleteSharedPreferences(prefsName)
        val model = UniverViewModel(LocalSessionRepository(vault, AppPreferences(context, prefsName)))
        val store = ViewModelStore().apply { put("fixture", model) }
        runBlocking {
            model.state.first { it.ready }
            withContext(Dispatchers.Main) { model.save("fixture-user", "fixture-password") }
        }
        val jquery = instrumentation.context.assets.open("site-jquery.js").bufferedReader().use { it.readText() }
        val landing = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val submissions = AtomicInteger()
        val retried = AtomicBoolean()
        val freshEntry = AtomicBoolean()
        val staleSubmissions = AtomicInteger()
        val landings = AtomicInteger()
        val entries = AtomicInteger()
        var requestMethod: String? = null
        var closed = 0
        var signedOut = 0
        val assignLoads = AtomicInteger()
        val assign = "https://dl.kaznu.edu.kz/mod/assign/view.php?id=5"
        lateinit var viewer: DocumentViewer
        lateinit var source: WebView
        lateinit var host: FrameLayout
        val login = """<html><head><script>$jquery
            $(document).ready(function(){ $('#login_frm').submit(function(e){e.preventDefault();
                if($('#dynamicLogin').attr('value')!=='fixture-user'||$('#dynamicPass').attr('value')!=='fixture-password'
                    ||document.getElementById('key').value!=='preserved')return false;
                document.title='submitted';
                ${if (method == "FAIL") "location.href='/user/loginMoodle?failed=1';" else if (method == "DETOUR")
                    "location.href='/news/student';" else if (method == "POST")
                    "document.getElementById('handoff').submit();" else "location.href='https://dl.kaznu.edu.kz/my/';"}
                return false;
            });});</script></head><body>
            <form id=login_frm action=/ method=post><input type=text id=dynamicLogin><input type=password id=dynamicPass>
            <input id=key type=hidden name=key value=preserved><input name=btn type=submit value=Login></form>
            <form id=handoff method=post action='https://dl.kaznu.edu.kz/my/'><input type=hidden name=fixture value=preserved></form>
            </body></html>"""
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    host = FrameLayout(activity)
                    ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
                        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
                        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                        insets
                    }
                    source = WebView(activity)
                    source.loadData("<html><body>Source page remains intact</body></html>", "text/html", "UTF-8")
                    host.addView(source); activity.setContentView(host)
                    viewer = DocumentViewer(activity, host, { AutomationController(it, model, activity.lifecycleScope) {} },
                        { fail("No external browser should be opened by the fixture") }, { _, _, _, _ -> }, { closed++ },
                        serviceSignedOut = { signedOut++ },
                        serviceAutomationFactory = { web ->
                            AutomationController(web, model, activity.lifecycleScope, model.singleSignOnGuard, false) {
                                viewer.automationStatus(it)
                                if (it == AutomationStatus.LOGIN_FAILED) failed.countDown()
                            }
                        })
                    viewer.open("about:blank", AppLanguage.RUSSIAN, service = true)
                    val web = viewer.webView!!
                    val client = web.webViewClient
                    serviceClient = client
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                            val html = if (request.isForMainFrame && request.url.path == "/user/loginMoodle") {
                                if (request.url.query != null) {
                                    submissions.incrementAndGet()
                                    if (retried.get() && !freshEntry.get()) staleSubmissions.incrementAndGet()
                                } else if (retried.get()) {
                                    // A slow answer keeps the failed form on screen after Retry.
                                    Thread.sleep(1_500)
                                    freshEntry.set(true)
                                }
                                // After a Univer login the entry hands off directly, as for a signed-in account.
                                if (entries.incrementAndGet() > 1 && method == "DETOUR")
                                    "<html><body><script>location.href='https://dl.kaznu.edu.kz/my/';</script></body></html>"
                                else login
                            } else if (request.isForMainFrame && request.url.path == "/news/student") {
                                "<html><body><h1>Univer news</h1><p>Signed-in landing page</p><a href='/user/logout'>Выход</a></body></html>"
                            } else if (request.isForMainFrame && request.url.host == "dl.kaznu.edu.kz" && request.url.path == "/enrol/index.php") {
                                // Observed: an expired Moodle session continues as guest access instead of the sign-in form.
                                "<html><body id=page-enrol-index><div class=usermenu><span class='login ps-2'>You are currently using guest access <a href='https://dl.kaznu.edu.kz/login/index.php'>Log in</a></span></div><h2>Enrolment options</h2></body></html>"
                            } else if (request.isForMainFrame && request.url.host == "dl.kaznu.edu.kz" && request.url.path == "/login/index.php") {
                                // Moodle's own sign-in page, as shown after its session ends.
                                "<html><body><form id=login method=post action='https://dl.kaznu.edu.kz/login/index.php'><input name=username><input type=password name=password></form></body></html>"
                            } else if (request.isForMainFrame && request.url.host == "dl.kaznu.edu.kz") {
                                submissions.incrementAndGet(); requestMethod = request.method
                                "<html><head><meta name=viewport content='width=device-width,initial-scale=1'></head><body><h1>Moodle fixture</h1><p>Authenticated service</p></body></html>"
                            } else "<html><body></body></html>"
                            return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                        }
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = client.onPageStarted(view, url, favicon)
                        override fun onPageCommitVisible(view: WebView, url: String?) = client.onPageCommitVisible(view, url)
                        override fun onPageFinished(view: WebView, url: String?) {
                            client.onPageFinished(view, url)
                            if (url?.startsWith("https://dl.kaznu.edu.kz/my/") == true) { landings.incrementAndGet(); landing.countDown() }
                            if (url == assign) assignLoads.incrementAndGet()
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = client.shouldOverrideUrlLoading(view, request)
                    }
                    web.loadUrl(SingleSignOnPolicy.ENTRY)
                }
                if (method == "FAIL") {
                    assertTrue("Repeated login document stops automation", failed.await(20, TimeUnit.SECONDS))
                    assertTrue(model.singleSignOnGuard.blocked)
                    assertFalse("Univer guard is independent", model.guard.blocked)
                    assertEquals(1, submissions.get())
                    scenario.onActivity {
                        assertEquals(android.view.View.VISIBLE, host.findViewById<android.view.View>(R.id.document_error).visibility)
                        assertEquals(SingleSignOnPolicy.ENTRY, viewer.save()!!.getString("service_entry"))
                        retried.set(true)
                        host.findViewById<android.view.View>(R.id.document_retry).performClick()
                    }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                    while (submissions.get() < 2 && System.nanoTime() < deadline) Thread.sleep(100)
                    assertEquals("Explicit retry permits exactly one more submission", 2, submissions.get())
                    assertEquals("Retry signs in from the fresh form, not the one being replaced", 0, staleSubmissions.get())
                } else {
                    assertTrue("Automatic Moodle hand-off completed", landing.await(20, TimeUnit.SECONDS))
                    assertEquals(if (method == "POST") "POST" else "GET", requestMethod)
                    assertEquals(1, submissions.get())
                    if (method == "DETOUR") assertEquals("The hand-off continued from the Univer page once", 2, entries.get())
                    // onPageFinished queues a status probe; wait for its hand-off callback too.
                    var handOffReady = false
                    val handOffDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (!handOffReady && System.nanoTime() < handOffDeadline) {
                        instrumentation.runOnMainSync { handOffReady = model.singleSignOnGuard.pendingDocument == null }
                        if (!handOffReady) Thread.sleep(50)
                    }
                    assertTrue("Moodle completion releases the pending login", handOffReady)
                    if (method == "EXPIRED" || method == "GUEST") {
                        scenario.onActivity { viewer.webView!!.loadUrl(if (method == "GUEST") "https://dl.kaznu.edu.kz/enrol/index.php?id=7"
                            else "https://dl.kaznu.edu.kz/login/index.php") }
                        val again = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                        while (landings.get() < 2 && System.nanoTime() < again) Thread.sleep(100)
                        assertEquals("Ended Moodle session returns to Moodle without a tap", 2, landings.get())
                        assertEquals("The viewer went back through the Univer hand-off once", 2, entries.get())
                        assertEquals(2, submissions.get())
                        scenario.onActivity { assertNotEquals(android.view.View.VISIBLE, host.findViewById<android.view.View>(R.id.document_error).visibility) }
                        // The second hand-off is confirmed by the same asynchronous status probe as the first.
                        var released = false
                        val releaseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (!released && System.nanoTime() < releaseDeadline) {
                            instrumentation.runOnMainSync { released = model.singleSignOnGuard.pendingDocument == null }
                            if (!released) Thread.sleep(50)
                        }
                        assertTrue("The repeated hand-off releases its pending login", released)
                    }
                    if (method == "RESUME") {
                        scenario.onActivity { viewer.webView!!.loadUrl(assign) }
                        waitFor { assignLoads.get() == 1 }
                        // Remembered after the page proved to be a signed-in one (asynchronous check).
                        waitFor { viewer.save()?.getString("service_resume") == assign }
                        // Moodle's session ends: its sign-in page appears instead of the next page.
                        scenario.onActivity { viewer.webView!!.loadUrl("https://dl.kaznu.edu.kz/login/index.php") }
                        waitFor { assignLoads.get() == 2 }
                        assertEquals("Renewed through Univer once", 2, entries.get())
                        assertEquals(2, landings.get())
                        var wentBack = false
                        scenario.onActivity { wentBack = viewer.goBack() }
                        assertTrue("Back opens the previous Moodle page", wentBack)
                        waitFor { landings.get() == 3 }
                        scenario.onActivity {
                            assertFalse("Univer's login and hand-off are not in the Back history", viewer.goBack())
                            assertTrue(viewer.isOpen)
                        }
                    }
                    if (method == "LOGOUT") {
                        scenario.onActivity {
                            val web = viewer.webView!!
                            val logout = object : WebResourceRequest {
                                override fun getUrl() = android.net.Uri.parse("https://dl.kaznu.edu.kz/login/logout.php?sesskey=fixture")
                                override fun isForMainFrame() = true
                                override fun isRedirect() = false
                                override fun hasGesture() = true
                                override fun getMethod() = "GET"
                                override fun getRequestHeaders() = emptyMap<String, String>()
                            }
                            assertFalse("Moodle's own logout request proceeds", serviceClient!!.shouldOverrideUrlLoading(web, logout))
                            // Moodle then shows its sign-in page.
                            web.loadUrl("https://dl.kaznu.edu.kz/login/index.php")
                        }
                        waitFor { closed == 1 }
                        assertEquals(1, signedOut)
                        assertEquals("No new hand-off after the user's own logout", 1, entries.get())
                        waitFor { host.childCount == 1 }
                        scenario.onActivity { source.destroyFixture() }
                        return@use
                    }
                    scenario.onActivity {
                        assertTrue(viewer.isService)
                        assertNull("Never save service POST/credential state", viewer.save()!!.getBundle("web"))
                        assertFalse(webCanAccessFiles(viewer.webView!!))
                        assertNull(model.singleSignOnGuard.pendingDocument)
                    }
                    instrumentation.waitForIdleSync()
                    if (method == "GET") {
                        // The isolated host contains only fixture text; no real credential UI is shown.
                        scenario.onActivity { it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
                        instrumentation.waitForIdleSync()
                        Thread.sleep(200)
                        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                            java.io.File(context.getExternalFilesDir(null), "moodle-fixture.png").outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            bitmap.recycle()
                        }
                    }
                    scenario.onActivity {
                        val web = viewer.webView!!
                        val unsafePost = object : WebResourceRequest {
                            override fun getUrl() = android.net.Uri.parse("https://evil.test/sso")
                            override fun isForMainFrame() = true
                            override fun isRedirect() = false
                            override fun hasGesture() = false
                            override fun getMethod() = "POST"
                            override fun getRequestHeaders() = emptyMap<String, String>()
                        }
                        // Our forwarding wrapper owns fixture responses; exercise the real client too.
                        assertEquals(403, serviceClient!!.shouldInterceptRequest(web, unsafePost)!!.statusCode)
                    }
                }
                scenario.onActivity {
                    assertEquals(2, host.childCount)
                    viewer.close()
                    assertEquals(1, closed)
                }
                waitFor { host.childCount == 1 }
                scenario.onActivity {
                    assertSame(source, host.getChildAt(0))
                    source.destroyFixture()
                }
            }
        } finally {
            instrumentation.runOnMainSync { runCatching { viewer.close(false) }; store.clear() }
            vault.delete(); context.deleteSharedPreferences(prefsName)
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var met = false
        while (System.nanoTime() < deadline) {
            instrumentation.runOnMainSync { met = condition() }
            if (met) break
            Thread.sleep(100)
        }
        assertTrue("Fixture reached the expected state", met)
    }

    private fun webCanAccessFiles(web: WebView) = web.settings.allowFileAccess || web.settings.allowContentAccess
    private var serviceClient: WebViewClient? = null
}
