package com.univerflow.kaznu.web

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.univerflow.kaznu.domain.CalendarEvent
import com.univerflow.kaznu.domain.GradeReport
import com.univerflow.kaznu.domain.GradeTotals
import com.univerflow.kaznu.domain.HomeShortcuts
import com.univerflow.kaznu.domain.ShortcutRequest
import com.univerflow.kaznu.domain.Shortcuts
import com.univerflow.kaznu.domain.Timetable
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Receives home-tile pin and order taps, a UMKD section's «Скачать все», the journal's «Поделиться успеваемостью» and
 * «В календарь», the schedule's week and the journal's totals from the site's main frame. Only data crosses: a listener limited to the exact Univer origin, no callable host
 * objects, and only messages carrying [channel]'s key: the site's own scripts cannot pin a tile, start downloads or
 * open the share sheet.
 */
class ShortcutBridge(private val web: WebView, private val channel: ChannelGuard, private val onDownloadAll: (DownloadBatch) -> Unit = {},
    private val onReport: (GradeReport?) -> Unit = {}, private val onCalendar: (CalendarEvent?) -> Unit = {},
    private val onTimetable: (Timetable) -> Unit = {}, private val onGrades: (GradeTotals) -> Unit = {},
    private val onRequest: (ShortcutRequest) -> Unit) {
    private var closed = false

    init {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.addWebMessageListener(web, CHANNEL, setOf(SitePolicy.ORIGIN)) { _, message, origin, mainFrame, _ ->
            if (closed || !mainFrame || origin.toString() != SitePolicy.ORIGIN) return@addWebMessageListener
            val data = channel.unwrap(runCatching { message.data }.getOrNull()) ?: return@addWebMessageListener
            when {
                data.startsWith(DownloadBatch.MESSAGE_PREFIX) -> DownloadBatch.parse(data)?.let(onDownloadAll)
                // Our own journal sent it, so a report out of shape is said, not dropped in silence.
                data.startsWith(GradeReport.MESSAGE_PREFIX) -> onReport(ReportMessage.parse(data))
                // «В календарь» on an exam or a class; one out of shape is said as well.
                data.startsWith(CalendarEvent.MESSAGE_PREFIX) -> onCalendar(CalendarMessage.parse(data))
                // The schedule's week, sent without a tap: one out of shape, or not this week, is dropped.
                data.startsWith(Timetable.MESSAGE_PREFIX) -> TimetableMessage.parse(data)?.let(onTimetable)
                // The journal's totals, sent without a tap for the background check's base; one out of shape is dropped.
                data.startsWith(GradeTotals.MESSAGE_PREFIX) -> GradesMessage.parse(data)?.let(onGrades)
                else -> parse(data)?.let(onRequest)
            }
        }
    }

    /** Sends the stored list back; the page renders only this validated data. */
    fun configure(shortcuts: HomeShortcuts) {
        if (closed || !SitePolicy.trusted(web.url)) return
        web.evaluateJavascript("(function(){if(location.origin===${jsString(SitePolicy.ORIGIN)})" +
            "window.__univerShortcutsV1&&window.__univerShortcutsV1.configure(${script(shortcuts)});})()", null)
    }

    fun close() {
        if (closed) return
        closed = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(web, CHANNEL)
    }

    companion object {
        const val CHANNEL = "__univerAppEvents"

        /** "pins,order,hidden" arguments for __univerShortcutsV1.configure. */
        fun script(shortcuts: HomeShortcuts): String {
            val pins = JSONArray().apply {
                Shortcuts.normalize(shortcuts.pins).forEach { put(JSONObject().put("title", it.title).put("url", it.url)) }
            }
            val order = JSONArray().apply { Shortcuts.normalizeOrder(shortcuts.order).forEach { put(it) } }
            val hidden = JSONArray().apply { Shortcuts.normalizeHidden(shortcuts.hidden).forEach { put(it) } }
            return Shortcuts.scriptLiteral("$pins,$order,$hidden")
        }

        /** {"pin":bool,"title":"…","url":"…"} or {"order":["…"]} after the prefix; null for anything else. */
        fun parse(message: String?): ShortcutRequest? {
            if (message == null || message.length > 8192 || !message.startsWith(Shortcuts.MESSAGE_PREFIX)) return null
            val json = runCatching { JSONTokener(message.removePrefix(Shortcuts.MESSAGE_PREFIX)).nextValue() as? JSONObject }.getOrNull() ?: return null
            if (json.has("order")) {
                val array = json.optJSONArray("order") ?: return null
                val urls = (0 until array.length()).map { array.opt(it) as? String ?: return null }
                return ShortcutRequest.Order(urls)
            }
            val pin = json.opt("pin") as? Boolean ?: return null
            val url = json.opt("url") as? String ?: return null
            return ShortcutRequest.Pin(pin, json.opt("title") as? String, url)
        }
    }
}
