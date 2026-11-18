package com.univerflow.kaznu.web

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * The Google Analytics tag the site puts on every page. Its script and page-view beacons get an empty local reply, so
 * Google Analytics gets no page view and the page's main thread does not run the tag. The site's inline stub only fills
 * `dataLayer`, so its `gtag()` calls keep working without the script.
 */
object Trackers {
    private val HOSTS = listOf("googletagmanager.com", "google-analytics.com", "analytics.google.com")

    /** [host] is a request's host, as `Uri.host` gives it: the listed domains and their subdomains, nothing that only ends alike. */
    fun blocked(host: String?): Boolean {
        val name = host?.lowercase()?.removeSuffix(".") ?: return false
        return HOSTS.any { name == it || name.endsWith(".$it") }
    }

    fun emptyReply() = WebResourceResponse("text/javascript", "UTF-8", 204, "No Content", emptyMap(),
        ByteArrayInputStream(byteArrayOf()))
}
