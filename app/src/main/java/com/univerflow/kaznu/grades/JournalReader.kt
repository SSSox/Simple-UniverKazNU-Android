package com.univerflow.kaznu.grades

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import com.univerflow.kaznu.domain.GradeTotals
import com.univerflow.kaznu.web.GradesMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/**
 * Reads a fetched journal with the journal's own reader (the first part of univer-attendance.js) in a WebView of its
 * own on about:blank: a DOMParser document is inert, so none of the page's scripts run and nothing it names is loaded;
 * the blank page has no origin, so no cookies or storage of the site. The WebView lives only for one read and is
 * destroyed on the main thread, also when the read is cancelled or its renderer is lost.
 */
class JournalReader(private val context: Context) {
    suspend fun read(html: String): GradeTotals? {
        val script = withContext(Dispatchers.IO) { context.assets.open(SCRIPT).bufferedReader().use { it.readText() } }
        return withContext(Dispatchers.Main) { evaluate(script, html) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun evaluate(script: String, html: String): GradeTotals? = suspendCancellableCoroutine { result ->
        val web = WebView(context.applicationContext)
        var done = false
        fun finish(totals: GradeTotals?) {
            if (done) return
            done = true
            web.stopLoading()
            web.destroy()
            if (result.isActive) result.resume(totals)
        }
        web.settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))

            override fun onPageFinished(view: WebView, url: String?) {
                if (done || url != BLANK) return
                val call = "$script\n;JSON.stringify(window.__univerGradeReadV1.read(new DOMParser().parseFromString(" +
                    JSONObject.quote(html) + ",'text/html'),'/student/attendance/full/'))"
                view.evaluateJavascript(call) { value -> finish(GradesMessage.fromScript(value)) }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                finish(null)
                return true
            }
        }
        result.invokeOnCancellation { ContextCompat.getMainExecutor(context).execute { finish(null) } }
        web.loadUrl(BLANK)
    }

    private companion object {
        const val SCRIPT = "univer-attendance.js"
        const val BLANK = "about:blank"
    }
}
