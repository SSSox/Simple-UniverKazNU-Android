package com.univerflow.kaznu.web

import android.content.res.AssetManager
import android.content.res.Configuration
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.univerflow.kaznu.domain.AppLanguage
import kotlinx.coroutines.*
import org.json.JSONTokener
import kotlin.coroutines.resume

/** Owns the first adapted frame and its bounded, cancellable readiness work. */
class MobilePageAdapter(
    private val web: WebView,
    private val allowDocumentStart: Boolean = true,
    private val readinessTimeoutMillis: Long = 8_000,
    private val nativeChrome: Boolean = false,
    /** The main page's channel key; null (a document window) gives the modules no sender at all. */
    private val channel: ChannelGuard? = null,
    private val onConcealed: (Boolean) -> Unit = {},
    private val onContentReady: () -> Unit = {},
) {
    private val assets = web.context.assets
    private val bootstrap = assets.open("univer-page-start.js").bufferedReader().use { it.readText() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var applyJob: Job? = null
    private var startScript: ScriptHandler? = null
    private var navigationVersion = 0L
    private var enabled = true
    private var language = AppLanguage.RUSSIAN
    private var announcedVersion = -1L
    private var closed = false
    /** Windows-style motion switch and validated home tiles; applied with every presentation pass. */
    var animations = true
    var shortcuts = "[],[],[]"
    /** The app's dark theme gives the adapted (mobile) layout its dark palette; the original layout stays as the site draws it. */
    private val dark = web.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val documentStart = allowDocumentStart &&
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    private val messages = documentStart &&
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    init {
        if (messages && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            WebViewCompat.addWebMessageListener(web, EVENTS, setOf(SitePolicy.ORIGIN)) {
            _, message, origin, mainFrame, _ ->
            val token = runCatching { message.data }.getOrNull()
            if (!closed && mainFrame && origin.toString() == SitePolicy.ORIGIN && token != null && token.length in 5..64 &&
                token.matches(Regex("[a-z0-9]+-[a-z0-9]+"))) {
                val version = navigationVersion
                web.evaluateJavascript("(function(){return location.origin===${quote(SitePolicy.ORIGIN)}&&" +
                    "!!window.__univerPresentationV1&&window.__univerPresentationV1.token===${quote(token)};})()") {
                    if (!closed && version == navigationVersion && it == "true") requestApply()
                }
            }
        }
        updateDocumentStart()
    }

    private fun updateDocumentStart() {
        if (!documentStart || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        if (enabled && startScript == null)
            startScript = WebViewCompat.addDocumentStartJavaScript(web, bootstrap, setOf(SitePolicy.ORIGIN))
        else if (!enabled) { startScript?.remove(); startScript = null }
    }

    fun pageStarted(url: String? = web.url) {
        navigationVersion++
        applyJob?.cancel()
        conceal(enabled && SitePolicy.trusted(url))
    }

    // Alpha keeps the renderer/JS active, including visual-state callbacks. No stale page is exposed.
    private fun conceal(hidden: Boolean) {
        web.alpha = if (hidden) 0f else 1f
        onConcealed(hidden)
    }

    fun apply(enabled: Boolean, language: AppLanguage) {
        if (closed) return
        this.enabled = enabled
        this.language = language
        web.settings.loadWithOverviewMode = !enabled
        updateDocumentStart()
        if (!enabled) {
            conceal(false)
            if (SitePolicy.trusted(web.url))
                web.evaluateJavascript("(function(){if(location.origin===${quote(SitePolicy.ORIGIN)})" +
                    "window.__univerPresentationV1?.release();})()", null)
        }
        if (SitePolicy.trusted(web.url)) requestApply()
    }

    private fun requestApply() {
        applyJob?.cancel()
        val version = navigationVersion
        val url = web.url ?: return
        applyJob = scope.launch {
            try {
                val ready = withTimeoutOrNull(readinessTimeoutMillis) { applyTo(url, version) } == true
                if (!closed && version == navigationVersion && url == web.url) {
                    conceal(false)
                    if (ready && announcedVersion != version) {
                        announcedVersion = version
                        onContentReady()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!closed && version == navigationVersion) {
                    conceal(false)
                    web.evaluateJavascript("(function(){if(location.origin===${quote(SitePolicy.ORIGIN)})" +
                        "window.__univerPresentationV1?.release();})()", null)
                }
            }
        }
    }

    /** Waits for the DOM of [url], then runs the adapter in exactly that document, injecting it when absent. */
    private suspend fun applyTo(url: String, version: Long): Boolean {
        // onPageCommitVisible may precede DOM readiness. Polling also covers WebViews
        // without document-start injection or the message feature.
        while (currentCoroutineContext().isActive && version == navigationVersion && url == web.url) {
            val result = evaluate("(function(){return location.origin===${quote(SitePolicy.ORIGIN)}&&" +
                "location.href===${quote(url)}&&document.readyState!=='loading'&&!!document.body;})()")
            if (result == "true") break
            delay(40)
        }
        currentCoroutineContext().ensureActive()
        if (version != navigationVersion || url != web.url) return false
        val token = documentToken(url) ?: return false
        val sameDocument = "location.origin===${quote(SitePolicy.ORIGIN)}&&location.href===${quote(url)}&&" +
            "(window.__univerPresentationV1?.token||window.__univerMobileDocumentTokenV1)===${quote(token)}"
        val apply = "window.__univerMotion=$animations;window.__univerDark=$dark;" +
            "window.__univerShortcutsV1&&window.__univerShortcutsV1.configure($shortcuts);" +
            "window.__univerMobileV1.setNativeChrome($nativeChrome);" +
            "window.__univerMobileV1.setEnabled($enabled,${quote(language.siteCode)});" +
            "window.__univerStyleV1&&window.__univerStyleV1.apply($enabled);" +
            "window.__univerPresentationV1?.release();"
        val installed = evaluate("(function(){if(!($sameDocument)||" +
            "!window.__univerMobileV1)return false;$apply return true;})()")
        if (installed != "true") {
            val script = modules(assets)
            if (version != navigationVersion || url != web.url) return false
            val injected = evaluate(withSenders("if(!($sameDocument))return false;\n$script\n$apply return true;", channel?.senders() ?: "null"))
            if (injected != "true") return false
        }
        awaitVisualState(version)
        return true
    }

    /** A token naming the current document of [url], so later scripts cannot act on a page that replaced it. */
    private suspend fun documentToken(url: String): String? {
        val reply = evaluate("(function(){if(location.origin!==${quote(SitePolicy.ORIGIN)}||" +
            "location.href!==${quote(url)})return null;return window.__univerPresentationV1?.token||" +
            "(window.__univerMobileDocumentTokenV1=window.__univerMobileDocumentTokenV1||" +
            "Date.now().toString(36)+'-'+Math.random().toString(36).slice(2));})()") ?: return null
        return JSONTokener(reply).nextValue() as? String
    }

    private suspend fun evaluate(source: String): String? = web.evaluate(source)

    private suspend fun awaitVisualState(version: Long) = suspendCancellableCoroutine { continuation ->
        web.postVisualStateCallback(version, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        })
    }

    fun dismissNavigation(callback: (Boolean) -> Unit) {
        if (!SitePolicy.trusted(web.url)) { callback(false); return }
        web.evaluateJavascript("""(function(){
            if(location.origin!=='${SitePolicy.ORIGIN}'||!document.documentElement.classList.contains('univer-mobile'))return false;
            if(window.__univerTablesV1&&window.__univerTablesV1.close())return true;
            if(window.__univerShortcutsV1&&window.__univerShortcutsV1.close())return true;
            var menu=document.querySelector('.univer-app-menu[open]');
            if(!menu)return false;
            menu.open=false;menu.querySelector('summary').focus({preventScroll:true});return true;
        })();""", { callback(it == "true") })
    }

    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        startScript?.remove()
        startScript = null
        if (messages && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            WebViewCompat.removeWebMessageListener(web, EVENTS)
    }

    private fun quote(value: String) = jsString(value)
    companion object {
        private const val EVENTS = "__univerPageEvents"
        /**
         * The modules' only way to native code, visible to them and not to the site: pin requests and a UMKD section's
         * «Скачать все» go through the keyed app sender, nothing else. Strict, so a function the page swapped in for a
         * built-in cannot reach the sender through .caller; the prefix checks use no prototype method.
         */
        private const val NATIVE = "var __univerNative=__univerSend&&typeof __univerSend.app==='function'?function(v){'use strict';" +
            "if(typeof v!=='string'||v.length<11)return;var p='univer-pin:',d='univer-download-all:',i,a=true,b=true;" +
            "for(i=0;i<d.length;i++){if(i<p.length&&v[i]!==p[i])a=false;if(v[i]!==d[i])b=false;}" +
            "if(window===window.top&&((a&&v.length<=8192)||(b&&v.length<=65536)))__univerSend.app(v);}:null;"

        /**
         * [body] (the modules) run with [senders] (a JS expression, see [ChannelGuard.senders]) as their only sender.
         * Strict throughout, and the secret only in the call's arguments: no frame the site reaches through .caller or a
         * stack trace holds the senders, and no function's text holds the secret. [body] may `return` a value.
         */
        fun withSenders(body: String, senders: String) = "(function(__univerSend){'use strict';$NATIVE\n$body\n})($senders);"
        /**
         * Feature modules shared with the Windows app install first; the adapter calls them while its observer is paused.
         * The presentation layer installs last and owns motion.
         */
        val SCRIPTS = listOf("univer-palette.js", "univer-dom.js", "univer-lesson.js", "univer-news.js", "univer-attendance.js", "univer-study-links.js", "univer-materials.js", "univer-folds.js",
            "univer-overview.js", "univer-shortcuts-css.js", "univer-shortcuts.js", "univer-terms.js", "univer-attestation.js", "univer-discussion.js",
            "univer-tables.js", "univer-syllabus.js", "univer-top.js", "univer-mobile-css.js", "univer-polish.js", "univer-screens.js", "univer-mobile.js", "univer-motion.js", "univer-kit.js", "univer-style.js")

        /** [SCRIPTS] concatenated (about 940 KB), kept for the process: activity recreations and document windows reuse it. */
        @Volatile private var joined: String? = null

        /** Reads and joins [SCRIPTS] once, off the main thread; later calls return the same text without suspending. */
        private suspend fun modules(assets: AssetManager): String = joined ?: withContext(Dispatchers.IO) {
            joined ?: SCRIPTS.joinToString("\n") { name -> assets.open(name).bufferedReader().use { it.readText() } }.also { joined = it }
        }
    }
}
