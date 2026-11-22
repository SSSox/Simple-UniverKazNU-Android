package com.univerflow.kaznu.web

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * The site's low-vision version (`/Content/js/bvi.min.js`, started by an inline `new isvek.Bvi({target:'.bvi-open'})`)
 * reads every one of its cookies on each page: 20–90 ms of the main thread on the emulator. Its button sits in the site
 * header, which the reading layout hides under the app bar, so there the script gets a stand-in that only keeps the
 * options. Original layout loads the real script and starts it with them (univer-mobile.js `disable()`); the script
 * starts synchronously, so a late start works. A student who turned the version on (`bvi_panelActive=true`) always gets
 * the real script.
 */
object BviStub {
    private const val PATH = "/content/js/bvi.min.js"

    /** The query the reading layout adds when Original asks for the real script. */
    const val FULL = "univer-full"

    private const val SCRIPT = "(function(){'use strict';var k=window.isvek=window.isvek||{};if(k.Bvi)return;" +
        "var q=window.__univerBviQueue=window.__univerBviQueue||[];" +
        "k.Bvi=function(o){q.push(o||{});};k.Bvi.univerStub=true;})();"

    /** Whether the request for [url] (method [method]) gets the stand-in, given the layout and the site's [cookies]. */
    fun stubs(url: String, method: String, readingLayout: Boolean, cookies: String?): Boolean {
        if (!readingLayout || !method.equals("GET", true)) return false
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.host?.lowercase() != "univer.kaznu.kz" || (uri.port != -1 && uri.port != 443)) return false
        if (uri.rawPath?.lowercase() != PATH || uri.rawQuery?.split('&')?.contains(FULL) == true) return false
        return cookies.orEmpty().split(';').none { it.trim().equals("bvi_panelActive=true", ignoreCase = true) }
    }

    fun reply() = WebResourceResponse("text/javascript", "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(SCRIPT.toByteArray()))
}
