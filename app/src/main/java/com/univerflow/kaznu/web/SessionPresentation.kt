package com.univerflow.kaznu.web

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Each controller owns the presentation of its known login page and AJAX authentication dialog. */
class SessionPresentation(private val web: WebView, timeoutMillis: Long = 25_000) {
    init { require(timeoutMillis in 1..MAX_TIMEOUT) }
    private val source = web.context.assets.open("univer-session-presentation.js").bufferedReader().use { it.readText() }
        .replace("var timeoutMillis = 25000;", "var timeoutMillis = $timeoutMillis;")
    private var startScript: ScriptHandler? = null
    private var enabled = false
    private var closed = false

    fun setEnabled(value: Boolean, retry: Boolean = false) {
        if (closed || (enabled == value && !retry)) return
        enabled = value
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            if (value && startScript == null)
                startScript = WebViewCompat.addDocumentStartJavaScript(web, source, setOf(SitePolicy.ORIGIN))
            else if (!value) { startScript?.remove(); startScript = null }
        }
        apply(retry)
    }

    fun apply(retry: Boolean = false) {
        if (closed || !SitePolicy.trusted(web.url)) return
        val install = if (enabled) source else ""
        web.evaluateJavascript("(function(){if(location.origin!=='${SitePolicy.ORIGIN}')return;" +
            "$install\nwindow.__univerSessionPresentationV1?.setEnabled($enabled,$retry);})()", null)
    }

    companion object { const val MAX_TIMEOUT = 90_000L }

    fun close() {
        if (closed) return
        closed = true
        enabled = false
        startScript?.remove(); startScript = null
    }
}
