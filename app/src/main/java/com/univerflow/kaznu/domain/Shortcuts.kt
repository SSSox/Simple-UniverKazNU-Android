package com.univerflow.kaznu.domain

import java.net.URI

/** A section or useful link the student pinned to the home screen. */
data class PinnedLink(val title: String, val url: String)

/**
 * Home tiles state: pinned links, the order of all tiles, and the built-in tiles (Расписание, УМКД, Moodle…)
 * the student removed, as canonical addresses. Survives logout.
 */
data class HomeShortcuts(
    val pins: List<PinnedLink> = emptyList(),
    val order: List<String> = emptyList(),
    val hidden: List<String> = emptyList())

/** A request from a trusted page tap, already parsed; every value is validated again here. */
sealed interface ShortcutRequest {
    data class Pin(val pin: Boolean, val title: String?, val url: String) : ShortcutRequest
    data class Order(val urls: List<String>) : ShortcutRequest
}

/** Port of the Windows Shortcuts rules, so both apps accept and keep the same links. */
object Shortcuts {
    const val LIMIT = 24
    const val MESSAGE_PREFIX = "univer-pin:"
    private const val TITLE_LIMIT = 120
    private const val ORIGIN_HOST = "univer.kaznu.kz"
    private val control = Regex("\\p{Cc}")
    private val fixedPaths = Regex("^/(student/(myschedule|attendance/full|transcript|umkd)|user/loginMoodle|ssc(/UserServiceList|/StatementList)?)$", RegexOption.IGNORE_CASE)

    private fun uri(url: String?): URI? = runCatching { URI(url ?: return null) }.getOrNull()?.takeIf { it.isAbsolute }

    private fun trusted(uri: URI) = uri.scheme.equals("https", true) && uri.host.equals(ORIGIN_HOST, true) &&
        uri.userInfo == null && (uri.port == -1 || uri.port == 443)

    /** The always-present home tiles (Расписание, Успеваемость, Транскрипт, УМКД, Moodle, Е-услуги) are never pins. */
    fun fixedHomeLink(url: String?): Boolean {
        val uri = uri(url) ?: return false
        if (!trusted(uri) || uri.rawQuery != null || uri.rawFragment != null) return false
        return fixedPaths.matches(uri.path.orEmpty().trimEnd('/'))
    }

    fun allowed(url: String?): Boolean {
        if (url.isNullOrEmpty() || url.length > 2048 || control.containsMatchIn(url)) return false
        val uri = uri(url) ?: return false
        if (trusted(uri)) return !Regex("^/user/(logout|login)/?$", RegexOption.IGNORE_CASE).matches(uri.path.orEmpty())
        // Off-site useful links open in the user's browser; only plain web addresses qualify.
        return (uri.scheme == "https" || uri.scheme == "http") && uri.userInfo == null && !uri.host.isNullOrEmpty() &&
            !uri.host.equals(ORIGIN_HOST, true)
    }

    fun cleanTitle(title: String?): String {
        // U+2028/U+2029 are line breaks, not \\s, and they terminate a string in older JavaScript.
        val value = (title ?: "").replace(Regex("[\\p{Cc}\\p{Zl}\\p{Zp}\\s]+"), " ").trim()
        return if (value.length > TITLE_LIMIT) value.take(TITLE_LIMIT).trimEnd() else value
    }

    /** JSON placed inside evaluateJavascript. Line separators must stay inside the string. */
    fun scriptLiteral(json: String) = json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    fun normalize(pins: List<PinnedLink>): List<PinnedLink> {
        val result = ArrayList<PinnedLink>()
        for (pin in pins) {
            if (!allowed(pin.url) || fixedHomeLink(pin.url)) continue
            val title = cleanTitle(pin.title)
            if (title.isEmpty() || result.any { it.url == pin.url }) continue
            result += PinnedLink(title, pin.url)
            if (result.size == LIMIT) break
        }
        return result
    }

    fun normalizeOrder(order: List<String>): List<String> = order.filter(::allowed).distinct().take(LIMIT + 6)

    /** One address per built-in tile: no trailing slash, lower case, so every spelling of a link names the same tile. */
    fun canonicalFixed(url: String?): String? {
        if (!fixedHomeLink(url)) return null
        val uri = uri(url) ?: return null
        return "https://$ORIGIN_HOST${uri.path.orEmpty().trimEnd('/').lowercase()}"
    }

    /** Removed built-in tiles; anything that is not one is dropped. */
    fun normalizeHidden(hidden: List<String>): List<String> = hidden.mapNotNull(::canonicalFixed).distinct()

    /** The next state, or null when the request is malformed or over the limit (the page then shows the stored list). */
    fun apply(current: HomeShortcuts, request: ShortcutRequest): HomeShortcuts? = when (request) {
        is ShortcutRequest.Order -> {
            val order = normalizeOrder(request.urls)
            if (request.urls.size > LIMIT + 6 || order.size != request.urls.size) null else current.copy(order = order)
        }
        is ShortcutRequest.Pin -> {
            if (!allowed(request.url)) null else {
                val list = normalize(current.pins).toMutableList()
                val hidden = normalizeHidden(current.hidden)
                val fixed = canonicalFixed(request.url)
                when {
                    // A built-in tile is removed by hiding it and comes back when it is pinned again.
                    fixed != null -> current.copy(pins = list, hidden = if (request.pin) hidden - fixed else (hidden + fixed).distinct())
                    !request.pin -> { list.removeAll { it.url == request.url }; current.copy(pins = list, hidden = hidden) }
                    cleanTitle(request.title).isEmpty() || list.any { it.url == request.url } -> current.copy(pins = list)
                    list.size >= LIMIT -> null
                    else -> current.copy(pins = list + PinnedLink(cleanTitle(request.title), request.url))
                }
            }
        }
    }
}
