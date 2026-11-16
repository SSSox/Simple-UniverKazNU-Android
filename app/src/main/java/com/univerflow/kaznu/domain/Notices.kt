package com.univerflow.kaznu.domain

/**
 * A notification from the site: a menu count ([COUNTER], "Анкетирование студентов (2)") or a page message asking for
 * a questionnaire or test ([BLOCKED]). A [read] one is not listed until the count, or what is left to answer, grows.
 */
enum class NoticeKind(val code: String) {
    COUNTER("counter"), BLOCKED("blocked");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code }
    }
}

data class SiteNotice(
    val key: String,
    val kind: NoticeKind,
    val title: String,
    /** The trusted page that resolves it: the menu link, or the link in the message (else the page itself). */
    val url: String,
    val count: Int = 0,
    val text: String = "",
    /**
     * The page whose message this is; for a counter, the page whose strip shows it, or with [menu] the section whose
     * main menu shows it (empty: a main menu, as stored before sections were told apart).
     */
    val page: String = "",
    val firstSeen: Long = 0,
    val read: Boolean = false,
    /** What the counted page lists: its open questionnaires or tests, with their last dates. */
    val details: List<NoticeDetail> = emptyList(),
    /** How many of the counted questionnaires are still to answer at the last check of this count; -1: not checked. */
    val open: Int = -1,
    val menu: Boolean = false,
)

/** One entry of a counted page ("Удовлетворенность обучающихся качеством преподавания", until "30.11.2026"). */
data class NoticeDetail(val title: String, val until: String)

/**
 * A menu or strip link with its count (0: none). [open]: questionnaires their own pages show unanswered (-1: not
 * checked); [checking]: a check is under way and reports separately.
 */
data class NoticeLink(val url: String, val title: String, val count: Int, val details: List<NoticeDetail> = emptyList(),
    val menu: Boolean = false, val open: Int = -1, val checking: Boolean = false)

/** A page message that asks for a questionnaire or test; [url] is the link inside it, or the page. */
data class NoticeMessage(val title: String, val text: String, val url: String)

/**
 * One read of a page: its links and messages; [section] names the main menu it shows. A counter goes when seen without
 * a count or missing from a complete read ([menu]) of its own menu or strip; a message when its page lacks it.
 */
data class NoticeScan(val page: String, val links: List<NoticeLink>, val messages: List<NoticeMessage>,
    val menu: Boolean = false, val section: String = page)

object Notices {
    const val MAX = 30
    const val TITLE_LENGTH = 100
    const val TEXT_LENGTH = 280
    const val MAX_DETAILS = 3

    private fun counterKey(url: String) = "counter:$url"
    private fun messageKey(page: String, text: String) = "blocked:$page#${text.hashCode().toUInt().toString(16)}"

    /** The stored list updated by [scan]; unchanged items keep their identity, first-seen time and read mark. */
    fun merge(current: List<SiteNotice>, scan: NoticeScan, now: Long): List<SiteNotice> {
        val byKey = current.associateBy { it.key }.toMutableMap()
        val shown = scan.links.groupBy { it.url }
        if (scan.menu) byKey.values.filter { it.kind == NoticeKind.COUNTER && it.url !in shown &&
            (if (it.menu) it.page == scan.section else it.page.isEmpty() || it.page == scan.page) }.forEach { byKey.remove(it.key) }
        shown.forEach { (url, same) ->
            val count = same.maxOf { it.count }.coerceIn(0, 9999)
            val key = counterKey(url)
            if (count <= 0) { byKey.remove(key); return@forEach }
            // A count whose check is under way waits for that result: a new one is not listed before it.
            if (same.any { it.checking }) return@forEach
            val title = same.first { it.count == count }.title.take(TITLE_LENGTH).ifBlank { url }
            val details = same.firstOrNull { it.details.isNotEmpty() }?.details.orEmpty().take(MAX_DETAILS)
            val old = byKey[key]
            // Where a later complete read can prove it gone: its section's main menu, else the strip of the page that shows it.
            val inMenu = same.any { it.menu }
            val origin = when {
                inMenu -> scan.section
                scan.menu -> scan.page
                else -> old?.page ?: scan.page
            }
            val menu = if (inMenu || scan.menu) inMenu else old?.menu ?: false
            // A read without a check keeps the last check while the count it was made for stays.
            val open = same.maxOf { it.open }.coerceIn(-1, 9999)
                .let { if (it < 0 && old != null && count == old.count) old.open else it }
            // The site counts a questionnaire until it closes, answered or not: with none left to answer there is
            // nothing to do. A read mark stays until the count, or the number left to answer, grows.
            val grew = old != null && (count > old.count || (open > 0 && old.open >= 0 && open > old.open))
            val read = open == 0 || (old?.read == true && !grew)
            // A menu read carries no details: the last ones stay while the count they were read with stays.
            byKey[key] = old?.copy(title = title, count = count, read = read, page = origin, open = open, menu = menu,
                details = details.ifEmpty { if (count == old.count && open != 0) old.details else emptyList() })
                ?: SiteNotice(key, NoticeKind.COUNTER, title, url, count, page = origin, firstSeen = now, read = read,
                    details = details, open = open, menu = menu)
        }
        val messages = scan.messages.filter { it.text.isNotBlank() }.associateBy { messageKey(scan.page, it.text.take(TEXT_LENGTH)) }
        byKey.values.filter { it.kind == NoticeKind.BLOCKED && it.page == scan.page && it.key !in messages }.forEach { byKey.remove(it.key) }
        messages.forEach { (key, message) ->
            val old = byKey[key]
            byKey[key] = old?.copy(title = message.title.take(TITLE_LENGTH), url = message.url)
                ?: SiteNotice(key, NoticeKind.BLOCKED, message.title.take(TITLE_LENGTH), message.url,
                    text = message.text.take(TEXT_LENGTH), page = scan.page, firstSeen = now)
        }
        return ordered(byKey.values)
    }

    /** Unread first, then the newest; over [MAX], read and older ones go first. */
    fun ordered(notices: Collection<SiteNotice>) =
        notices.sortedWith(compareBy<SiteNotice> { it.read }.thenByDescending { it.firstSeen }.thenBy { it.key }).take(MAX)

    fun markRead(current: List<SiteNotice>, key: String) = current.map { if (it.key == key) it.copy(read = true) else it }

    fun markAllRead(current: List<SiteNotice>) = current.map { it.copy(read = true) }

    fun unread(current: List<SiteNotice>) = current.count { !it.read }

    /** What the Notifications window lists: a read item is done with and stays out of it. */
    fun listed(current: List<SiteNotice>) = current.filter { !it.read }

    /** Items unread in [next] that were not unread in [previous]: new ones and counters that grew. */
    fun newlyUnread(previous: List<SiteNotice>, next: List<SiteNotice>): List<SiteNotice> {
        val before = previous.filter { !it.read }.associateBy { it.key }
        return next.filter { !it.read && (before[it.key]?.let { old -> it.count > old.count } ?: true) }
    }
}
