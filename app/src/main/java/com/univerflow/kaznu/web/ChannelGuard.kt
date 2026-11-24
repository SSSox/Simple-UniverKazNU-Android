package com.univerflow.kaznu.web

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.security.SecureRandom

/**
 * Tells the app's own page scripts from the site's on the message channels (home-tile pins, «Скачать все», notices).
 * univer-channel.js runs at document start, before any site script, and keeps the channels' postMessage and a
 * per-WebView secret in a closure. The app's modules get senders that put the secret in front of each message only by
 * naming the secret, which reaches them inside the injected source and never the page. Messages without it are dropped.
 * The senders and the modules' wrapper are strict, so a built-in the site swaps cannot hand them over through .caller;
 * A swapped JSON.stringify or an inherited toJSON cannot change what «Скачать все» says: it is serialized with the
 * channel's own copy taken at document start (see univer-channel.js).
 * A WebView without document-start scripts keeps the unkeyed channel for what the pages report (the week, the totals);
 * the site could forge those messages there, so nothing that acts by itself (pins, «Скачать все») is taken from it.
 */
class ChannelGuard(web: WebView, val secret: String = newSecret()) {
    val active = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    private var script: ScriptHandler? = if (!active) null else WebViewCompat.addDocumentStartJavaScript(web,
        source(web.context.assets.open("univer-channel.js").bufferedReader().use { it.readText() }, secret), setOf(SitePolicy.ORIGIN))

    /** Whether a message may start an action (a pin, «Скачать все»): only when it came through the keyed channel. */
    val actions get() = active

    /** A message from the app's modules without its key, or null for anything the site sent itself. */
    fun unwrap(message: String?): String? = unwrap(message, secret, active)

    /** A JS expression giving {app, notice} senders for the app's modules, or null. */
    fun senders(): String = senders(secret, active)

    fun close() { script?.remove(); script = null }

    companion object {
        private const val PLACEHOLDER = "__UNIVER_CHANNEL_SECRET__"
        private val random = SecureRandom()
        private val HEX = Regex("[0-9a-f]{32}")

        fun newSecret(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        /** The asset with [secret] (32 hex digits, safe inside its quotes) in place of the placeholder. */
        fun source(asset: String, secret: String): String {
            require(HEX.matches(secret) && asset.contains(PLACEHOLDER))
            return asset.replace(PLACEHOLDER, secret)
        }

        fun unwrap(message: String?, secret: String, active: Boolean): String? = when {
            message == null -> null
            !active -> message
            message.startsWith(secret) -> message.substring(secret.length)
            else -> null
        }

        fun senders(secret: String, active: Boolean): String {
            require(HEX.matches(secret))
            // The origin is checked before the secret is handed over: a document of another origin never sees it.
            return if (active) "(location.origin==='${SitePolicy.ORIGIN}'&&window.__univerClaim?window.__univerClaim('$secret'):null)"
            // Unkeyed: the modules may still report what they read, but no action (see MobilePageAdapter's NATIVE).
            else "(function(a,n){function s(c){return c&&typeof c.postMessage==='function'?function(v){c.postMessage(v);}:null;}" +
                "return {app:s(a),notice:s(n),keyed:false};})(window.${ShortcutBridge.CHANNEL},window.${NoticeBridge.CHANNEL})"
        }
    }
}
