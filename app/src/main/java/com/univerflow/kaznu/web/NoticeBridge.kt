package com.univerflow.kaznu.web

import android.os.SystemClock
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.univerflow.kaznu.domain.NoticeDetail
import com.univerflow.kaznu.domain.NoticeLink
import com.univerflow.kaznu.domain.NoticeMessage
import com.univerflow.kaznu.domain.NoticeScan
import com.univerflow.kaznu.domain.Notices
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Runs univer-notices.js on each finished page; sections are fetched at most every [fetchIntervalMillis]. Only bounded
 * data from the Univer origin comes back, tagged with its session so [onScan] can drop an outdated read.
 */
class NoticeBridge(private val web: WebView, private val fetchIntervalMillis: Long = 10 * 60_000L,
    private val onScan: (scan: NoticeScan, session: Int) -> Unit) {
    private val script by lazy(LazyThreadSafetyMode.NONE) {
        web.context.assets.open("univer-notices.js").bufferedReader().use { it.readText() }
    }
    private val messages = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    private var lastFetch = Long.MIN_VALUE
    private var session = 0
    private var closed = false

    init {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.addWebMessageListener(web, CHANNEL, setOf(SitePolicy.ORIGIN)) { _, message, origin, mainFrame, _ ->
            if (closed || !mainFrame || origin.toString() != SitePolicy.ORIGIN) return@addWebMessageListener
            val json = decode(runCatching { message.data }.getOrNull()) ?: return@addWebMessageListener
            // A message without the session it was started for is never trusted to be the current one.
            val from = json.opt("g") as? Int ?: return@addWebMessageListener
            parse(json, section = true)?.let { onScan(it, from) }
        }
    }

    /**
     * Reads the current page for [session]; [refresh] also fetches the sections now instead of on the interval.
     * A different session than the last one fetches the sections again on its first page.
     */
    fun scan(refresh: Boolean = false, session: Int = 0) {
        val url = web.url
        if (closed || !SitePolicy.trusted(url)) return
        if (session != this.session) { this.session = session; lastFetch = Long.MIN_VALUE }
        val now = SystemClock.elapsedRealtime()
        // A check on the same interval is not skipped for arriving a little early.
        val fetch = messages && (refresh || lastFetch == Long.MIN_VALUE || now - lastFetch >= fetchIntervalMillis - 30_000)
        web.evaluateJavascript("$script($fetch, $session)") { result ->
            if (closed || url != web.url) return@evaluateJavascript
            val json = decode(runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()) ?: return@evaluateJavascript
            // The interval starts when the page really began fetching (it had section links to read).
            if (fetch && json.optBoolean("fetching") && session == this.session) lastFetch = now
            parse(json, section = false)?.let { onScan(it, session) }
        }
    }

    /** A new session (or account) fetches the sections again on its first page. */
    fun reset() { lastFetch = Long.MIN_VALUE }

    fun close() {
        if (closed) return
        closed = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(web, CHANNEL)
    }

    companion object {
        const val CHANNEL = "__univerNoticeEvents"
        private const val MAX_MESSAGE = 65_536
        private val DATE = Regex("\\d{2}\\.\\d{2}\\.\\d{4}")
        private val SPACE = Regex("[\\p{Cntrl}\\s]+")

        /**
         * The scan of one page, or null for anything malformed. Links outside the trusted origin, files and
         * oversized values are dropped; a section result must be a "section" read and a page result a "page" read.
         */
        fun parse(message: String?, section: Boolean): NoticeScan? = decode(message)?.let { parse(it, section) }

        /** A bounded JSON object, or null for anything else. */
        private fun decode(message: String?): JSONObject? {
            if (message == null || message.length > MAX_MESSAGE) return null
            return runCatching { JSONTokener(message).nextValue() as? JSONObject }.getOrNull()
        }

        private fun parse(json: JSONObject, section: Boolean): NoticeScan? {
            if (json.optInt("v") != 1 || json.optString("kind") != (if (section) "section" else "page")) return null
            val page = json.optString("page").takeIf(::path) ?: return null
            val links = json.optJSONArray("links")?.let { array ->
                (0 until minOf(array.length(), 120)).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val url = address(item.optString("u")) ?: return@mapNotNull null
                    val details = item.optJSONArray("d")?.let { list ->
                        (0 until minOf(list.length(), Notices.MAX_DETAILS)).mapNotNull { at ->
                            val entry = list.optJSONObject(at) ?: return@mapNotNull null
                            val name = clean(entry.optString("t"), Notices.TITLE_LENGTH).ifEmpty { return@mapNotNull null }
                            NoticeDetail(name, entry.optString("e").takeIf { DATE.matches(it) }.orEmpty())
                        }
                    }.orEmpty()
                    NoticeLink(url, clean(item.optString("t"), Notices.TITLE_LENGTH), item.optInt("n").coerceIn(0, 9999), details,
                        menu = item.optInt("m") == 1, open = item.optInt("o", -1).coerceIn(-1, 9999), checking = item.optInt("c") == 1)
                }
            }.orEmpty()
            val menu = json.optBoolean("menu") && (json.optJSONArray("links")?.length() ?: 0) < 120 && links.any { it.menu }
            val messages = json.optJSONArray("blocks")?.let { array ->
                (0 until minOf(array.length(), 5)).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val text = clean(item.optString("x"), Notices.TEXT_LENGTH).ifEmpty { return@mapNotNull null }
                    val target = address(item.optString("l")) ?: address(page) ?: return@mapNotNull null
                    NoticeMessage(clean(item.optString("t"), Notices.TITLE_LENGTH), text, target)
                }
            }.orEmpty()
            return NoticeScan(page, links, messages, menu, section = json.optString("s").takeIf(::path) ?: page)
        }

        private fun path(value: String) = value.length in 1..512 && value.startsWith("/") && !value.startsWith("//")

        /** An absolute trusted page address for a site path, never a file or a download. */
        private fun address(value: String): String? {
            if (!path(value)) return null
            val url = SitePolicy.ORIGIN + value
            return url.takeIf { SitePolicy.trusted(it) && !SitePolicy.documentDownload(it) && !SitePolicy.logout(it) }
        }

        private fun clean(value: String, limit: Int) = value.replace(SPACE, " ").trim().take(limit)
    }
}
