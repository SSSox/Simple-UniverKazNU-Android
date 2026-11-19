package com.univerflow.kaznu.web

import android.content.res.AssetManager
import android.content.res.Configuration
import android.webkit.WebView
import androidx.annotation.VisibleForTesting
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.univerflow.kaznu.domain.AppLanguage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var applyKey: String? = null
    /** The bootstrap's DOM-ready message, waking a pass that waits for the document. */
    private val domReady = Channel<Unit>(Channel.CONFLATED)
    /** Passes started over this adapter's life. */
    @VisibleForTesting internal var passes = 0
        private set
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
                token.matches(PAGE_TOKEN)) {
                // Waking a pass that waits for the DOM only makes it probe now; a pass is started after the token is checked.
                domReady.trySend(Unit)
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
            if (SitePolicy.trusted(web.url)) releasePage()
        }
        if (SitePolicy.trusted(web.url)) requestApply()
    }

    /**
     * The document's DOM-ready message, its commit and its load each ask for a pass; one already running for the same
     * document and settings serves them all. Restarting it threw away 120–250 ms of work on every page.
     */
    private fun requestApply() {
        val url = web.url
        val key = "$navigationVersion|$url|$enabled|${language.siteCode}|$animations|$shortcuts"
        if (applyJob?.isActive == true && key == applyKey) return
        applyJob?.cancel()
        url ?: return
        val version = navigationVersion
        applyKey = key
        passes++
        applyJob = scope.launch {
            try {
                val ready = withTimeoutOrNull(readinessTimeoutMillis) { applyTo(url, version) } == true
                if (!closed && version == navigationVersion && url == web.url) {
                    conceal(false)
                    // A pass that failed or timed out still shows the page: the start script would hide it 8 s more.
                    if (!ready) releasePage(url)
                    if (ready && announcedVersion != version) {
                        announcedVersion = version
                        onContentReady()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!closed && version == navigationVersion) {
                    conceal(false)
                    // No address check here: a pushState during the pass must not leave the body hidden.
                    releasePage()
                }
            }
        }
    }

    /**
     * Waits for the DOM of [url], then runs the adapter in exactly that document, injecting it when absent. One probe
     * checks the document, names it and applies an adapter already there: a round trip to a loading page waits behind
     * its work, and four of them in a row cost up to a quarter of a second.
     */
    private suspend fun applyTo(url: String, version: Long): Boolean {
        val here = "location.origin===${quote(SitePolicy.ORIGIN)}&&location.href===${quote(url)}"
        val apply = "window.__univerMotion=$animations;window.__univerDark=$dark;" +
            "window.__univerShortcutsV1&&window.__univerShortcutsV1.configure($shortcuts);" +
            "window.__univerMobileV1.setNativeChrome($nativeChrome);" +
            "window.__univerMobileV1.setEnabled($enabled,${quote(language.siteCode)},true);" +
            "window.__univerStyleV1&&window.__univerStyleV1.apply($enabled);" +
            "window.__univerPresentationV1?.release();"
        // The token names this document, so the injection below cannot act on a page that replaced it.
        val probe = "(function(){if(!($here)||document.readyState==='loading'||!document.body)return null;" +
            "var token=window.__univerPresentationV1?.token||(window.__univerMobileDocumentTokenV1=" +
            "window.__univerMobileDocumentTokenV1||Date.now().toString(36)+'-'+Math.random().toString(36).slice(2));" +
            "if(window.__univerMobileV1){try{$apply}catch(e){return 'failed';}return 'applied';}return 'token:'+token;})()"
        var token: String? = null
        // onPageCommitVisible may precede DOM readiness; the bootstrap's DOM-ready message ends the wait at once. Polling
        // also covers WebViews without document-start injection or the message feature.
        while (currentCoroutineContext().isActive && version == navigationVersion && url == web.url) {
            val reply = evaluate(probe)?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
            if (reply == "applied") { awaitVisualState(version); return true }
            // A broken adapter shows the page now; polling would rerun it for the whole readiness timeout.
            if (reply == "failed") return false
            if (reply != null && reply.startsWith("token:")) { token = reply.removePrefix("token:"); break }
            // The DOM-ready message wakes the wait at once; polling is only the fallback, and every probe of a page still
            // being parsed interrupts the parser (a slow news page took 60 of them).
            // Without the start script (Original layout) no message comes.
            withTimeoutOrNull(if (messages && startScript != null) 250 else 40) { domReady.receive() }
        }
        currentCoroutineContext().ensureActive()
        if (version != navigationVersion || url != web.url || token == null) return false
        val sameDocument = "$here&&(window.__univerPresentationV1?.token||window.__univerMobileDocumentTokenV1)===${quote(token)}"
        val script = modules(assets)
        if (version != navigationVersion || url != web.url) return false
        val injected = evaluate(withSenders("if(!($sameDocument))return false;\n$script\n$apply return true;", channel?.senders() ?: "null"))
        if (injected != "true") return false
        awaitVisualState(version)
        return true
    }

    private suspend fun evaluate(source: String): String? = web.evaluate(source)

    /** Lets the document-start script show the body it keeps hidden until the adapter is done; with [url], only at that address. */
    private fun releasePage(url: String? = null) = web.evaluateJavascript("(function(){if(location.origin===${quote(SitePolicy.ORIGIN)}" +
        (if (url == null) "" else "&&location.href===${quote(url)}") + ")window.__univerPresentationV1?.release();})()", null)

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
        private val PAGE_TOKEN = Regex("[a-z0-9]+-[a-z0-9]+")
        /**
         * The modules' only way to native code, visible to them and not to the site: pin requests, a UMKD section's
         * «Скачать все» and the journal's grade report go through the keyed app sender, nothing else. Strict, so a
         * function the page swapped in for a built-in cannot reach the sender through .caller; the prefix checks use no
         * prototype method.
         */
        private const val NATIVE = "var __univerNative=__univerSend&&typeof __univerSend.app==='function'?function(v){'use strict';" +
            "if(typeof v!=='string'||v.length<11)return;var p='univer-pin:',d='univer-download-all:',r='univer-report:',i,a=true,b=true,c=true;" +
            "for(i=0;i<d.length;i++){if(i<p.length&&v[i]!==p[i])a=false;if(v[i]!==d[i])b=false;if(i<r.length&&v[i]!==r[i])c=false;}" +
            "if(window===window.top&&((a&&v.length<=8192)||((b||c)&&v.length<=65536)))__univerSend.app(v);}:null;"

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
        private val reading = Mutex()

        /**
         * Starts reading [SCRIPTS] while the WebView starts and the first page loads, so the first pass finds them read.
         * A failed read leaves nothing behind: the pass reads again and reports what fails.
         */
        fun preload(assets: AssetManager, scope: CoroutineScope) {
            if (joined == null) scope.launch {
                try { modules(assets) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            }
        }

        /** Reads and joins [SCRIPTS] once, off the main thread; later calls return the same text without suspending. */
        private suspend fun modules(assets: AssetManager): String = joined ?: reading.withLock {
            // Kept inside the read: a pass cancelled by a navigation while it reads still leaves the text for the next one.
            joined ?: withContext(Dispatchers.IO) {
                SCRIPTS.joinToString("\n") { name -> assets.open(name).bufferedReader().use { it.readText() } }.also { joined = it }
            }
        }
    }
}
