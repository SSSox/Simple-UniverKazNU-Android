package com.univerflow.kaznu

import android.net.Uri
import android.webkit.*
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.data.*
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.web.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class WebsiteLanguageSyncTest {
    private fun request(path: String, gesture: Boolean = true, main: Boolean = true, verb: String = "GET") = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(path)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = gesture
        override fun getMethod() = verb
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test fun userFlagsPersistImmediatelyWithoutAutomationRevertingAndSettingsStillApply() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "univer.test.flags.preferences"
        context.deleteSharedPreferences(name)
        val preferences = AppPreferences(context, name)
        val vault = CredentialVault(context, "univer.test.flags")
        val model = UniverViewModel(com.univerflow.kaznu.data.LocalSessionRepository(vault, preferences))
        val store = ViewModelStore().apply { put("flags", model) }
        runBlocking { model.state.first { it.ready } }
        val loaded = CountDownLatch(1)
        val switches = AtomicInteger()
        lateinit var web: WebView
        lateinit var controller: AutomationController
        lateinit var sync: WebsiteLanguageSync
        var selections = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    web = WebView(activity); activity.setContentView(web); web.settings.javaScriptEnabled = true
                    controller = AutomationController(web, model, activity.lifecycleScope) {}
                    sync = WebsiteLanguageSync(model, activity.lifecycleScope, { selections++; controller.websiteLanguageSelected() }, { fail("Language save failed") })
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean { sync.onNavigation(request); return false }
                        override fun onPageStarted(view: WebView, url: String?, icon: android.graphics.Bitmap?) { controller.pageStarted() }
                        override fun onPageFinished(view: WebView, url: String?) { loaded.countDown(); controller.pageFinished() }
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                            val language = SitePolicy.languageChange(request.url.toString())?.siteCode ?: "ru"
                            if (SitePolicy.languageChange(request.url.toString()) != null) switches.incrementAndGet()
                            val html = "<html><body><div class='header'><img src='/Content/icons/$language.png'></div><a href='/lang/change/ru/'>RU</a><a href='/lang/change/en/'>EN</a><a href='/lang/change/kz/'>KZ</a><p>Offline flag fixture</p></body></html>"
                            return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                        }
                    }
                    web.loadUrl(SitePolicy.ORIGIN + "/fixture/flags")
                }
                assertTrue(loaded.await(15, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    controller.resume()
                    assertFalse(sync.onNavigation(request(SitePolicy.ORIGIN + "/lang/change/en/", gesture = false)))
                    assertFalse(sync.onNavigation(request(SitePolicy.ORIGIN + "/lang/change/en/", main = false)))
                    assertFalse(sync.onNavigation(request(SitePolicy.ORIGIN + "/lang/change/en/", verb = "POST")))
                    assertFalse(sync.onNavigation(request("https://evil.test/lang/change/en/")))
                    assertFalse(sync.onNavigation(request(SitePolicy.ORIGIN + "/student/transcript/6/")))
                    assertTrue(sync.onNavigation(request(SitePolicy.ORIGIN + "/lang/change/en/")))
                    assertEquals(AppLanguage.ENGLISH, model.state.value.language)
                    web.loadUrl(SitePolicy.ORIGIN + "/lang/change/en/")
                }
                Thread.sleep(6_000)
                assertEquals(AppLanguage.ENGLISH, preferences.language)
                assertEquals(1, switches.get())
                assertEquals(1, selections)
                // Rapid choices serialize writes and retain the last choice across a new preferences instance.
                instrumentation.runOnMainSync {
                    sync.onNavigation(request(SitePolicy.ORIGIN + "/lang/change/kz/"))
                    sync.onNavigation(request(SitePolicy.ORIGIN + "/lang/change/ru/"))
                    sync.onNavigation(request(SitePolicy.ORIGIN + "/lang/change/kz/"))
                    web.loadUrl(SitePolicy.ORIGIN + "/lang/change/kz/")
                }
                Thread.sleep(1_000)
                assertEquals(AppLanguage.KAZAKH, AppPreferences(context, name).language)
                runBlocking { withContext(Dispatchers.Main) { model.setLanguage(AppLanguage.RUSSIAN); controller.applyLanguage(AppLanguage.RUSSIAN) } }
                Thread.sleep(1_500)
                assertEquals(AppLanguage.RUSSIAN, preferences.language)
                assertEquals(3, switches.get())
                assertEquals(4, selections) // The controller's scripted flag click is not a user selection.
            } finally { instrumentation.runOnMainSync { controller.pause(); web.destroyFixture(); store.clear() }; context.deleteSharedPreferences(name) }
        }
    }
}
