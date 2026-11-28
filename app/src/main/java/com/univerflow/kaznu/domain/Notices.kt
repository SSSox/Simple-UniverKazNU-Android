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

/** Verification state of the questionnaire counter (`/student/rating/`), which the site keeps showing after answers. */
enum class SurveyCheck { PENDING, VERIFIED, FALLBACK, RECHECKING }

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
    val surveyStatus: SurveyCheck = Notices.defaultSurveyStatus(kind, url, open),
    /** The count (or verified open questionnaires) when marked read; only a larger one makes it unread again. */
    val readCount: Int = if (read) Notices.readNumber(kind, url, count, open) else 0,
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
    const val MAX_SURVEYS = 10
    const val SURVEYS = "https://univer.kaznu.kz/student/rating/"

    private fun counterKey(url: String) = "counter:$url"
    private fun messageKey(page: String, text: String) = "blocked:$page#${text.hashCode().toUInt().toString(16)}"

    fun isSurvey(notice: SiteNotice) = notice.kind == NoticeKind.COUNTER && notice.url == SURVEYS

    fun detailLimit(url: String) = if (url == SURVEYS) MAX_SURVEYS else MAX_DETAILS

    internal fun defaultSurveyStatus(kind: NoticeKind, url: String, open: Int): SurveyCheck =
        if (kind == NoticeKind.COUNTER && url == SURVEYS && open >= 0) SurveyCheck.VERIFIED else SurveyCheck.PENDING

    internal fun readNumber(kind: NoticeKind, url: String, count: Int, open: Int): Int =
        if (kind == NoticeKind.COUNTER && url == SURVEYS) maxOf(0, open) else count

    private fun readNumber(notice: SiteNotice): Int = readNumber(notice.kind, notice.url, notice.count, notice.open)

    /** The real count to show for a counter: verified unanswered questionnaires for surveys, else site/open count. */
    fun shown(notice: SiteNotice): Int {
        if (isSurvey(notice)) return if (notice.surveyStatus == SurveyCheck.VERIFIED) notice.open else notice.count
        return if (notice.open < 0) notice.count else minOf(notice.count, notice.open)
    }

    /** Whether [notice] represents something currently open (independent of its [SiteNotice.read] mark). */
    fun visible(notice: SiteNotice): Boolean {
        if (notice.kind == NoticeKind.BLOCKED) return true
        if (isSurvey(notice) && notice.surveyStatus != SurveyCheck.VERIFIED && notice.surveyStatus != SurveyCheck.FALLBACK) return false
        return shown(notice) > 0
    }

    /** The stored list updated by [scan]; unchanged items keep their identity, first-seen time and read mark. */
    fun merge(current: List<SiteNotice>, scan: NoticeScan, now: Long): List<SiteNotice> {
        val byKey = current.associateBy { it.key }.toMutableMap()
        val shown = scan.links.groupBy { it.url }
        if (scan.menu) byKey.values.filter { it.kind == NoticeKind.COUNTER && it.url !in shown &&
            (if (it.menu) it.page == scan.section else it.page.isEmpty() || it.page == scan.page) }.forEach { byKey.remove(it.key) }
        shown.forEach { (url, same) ->
            val survey = url == SURVEYS
            val links = if (survey) same.filter { !it.checking } else same
            val count = if (links.isEmpty()) 0 else links.maxOf { it.count }.coerceIn(0, 9999)
            val key = counterKey(url)
            val old = byKey[key]
            if (count <= 0) {
                val pending = if (survey) same.firstOrNull { it.checking } else null
                if (pending != null) {
                    // A changed site count needs a new check, while keeping the last verified count for announcements.
                    if (old != null && old.count != pending.count && old.surveyStatus != SurveyCheck.PENDING) {
                        byKey[key] = old.copy(surveyStatus = SurveyCheck.RECHECKING)
                    }
                    return@forEach
                }
                byKey.remove(key)
                return@forEach
            }
            val title = links.first { it.count == count }.title.take(TITLE_LENGTH).ifBlank { url }
            // Where a later complete read can prove it gone: its section's main menu, else the strip of the page that shows it.
            val inMenu = links.any { it.menu }
            val origin = when {
                inMenu -> scan.section
                scan.menu -> scan.page
                else -> old?.page ?: scan.page
            }
            val menu = if (inMenu || scan.menu) inMenu else old?.menu ?: false
            val described = links.firstOrNull { it.open >= 0 }
            if (survey) {
                val nextOpen = when {
                    described != null -> described.open.coerceIn(0, MAX_SURVEYS)
                    old == null || old.surveyStatus == SurveyCheck.PENDING -> -1
                    else -> old.open
                }
                // "All answered" for this very count outlives a failed check: the site keeps counting answered ones.
                val stillAnswered = old?.surveyStatus == SurveyCheck.VERIFIED && old.open == 0 && old.count == count
                val nextStatus = if (described != null || stillAnswered) SurveyCheck.VERIFIED else SurveyCheck.FALLBACK
                val nextDetails = described?.details.orEmpty().take(MAX_SURVEYS)
                val currentReadNum = maxOf(0, nextOpen)
                val prevRead = old?.read == true
                val prevReadCount = old?.readCount ?: 0
                val nextRead = if (described != null) prevRead && currentReadNum <= prevReadCount else prevRead
                // Answering lowers the read mark, so the next questionnaire is unread again.
                val nextReadCount = if (described != null) minOf(prevReadCount, currentReadNum) else prevReadCount
                byKey[key] = old?.copy(
                    title = title, count = count, read = nextRead, page = origin, open = nextOpen,
                    menu = menu, details = nextDetails, surveyStatus = nextStatus, readCount = nextReadCount
                ) ?: SiteNotice(
                    key, NoticeKind.COUNTER, title, url, count, page = origin, firstSeen = now,
                    read = nextRead, details = nextDetails, open = nextOpen, menu = menu,
                    surveyStatus = nextStatus, readCount = nextReadCount
                )
            } else {
                val countChanged = old != null && old.count != count
                val nextOpen = when {
                    described != null -> described.open.coerceIn(0, 9999)
                    countChanged -> -1
                    else -> old?.open ?: -1
                }
                val nextDetails = when {
                    described != null -> described.details.take(MAX_DETAILS)
                    links.any { it.details.isNotEmpty() } -> links.first { it.details.isNotEmpty() }.details.take(MAX_DETAILS)
                    countChanged -> emptyList()
                    else -> old?.details.orEmpty()
                }
                val prevRead = old?.read == true
                val prevReadCount = old?.readCount ?: 0
                val nextRead = prevRead && count <= prevReadCount
                val nextReadCount = if (nextRead) prevReadCount else 0
                byKey[key] = old?.copy(
                    title = title, count = count, read = nextRead, page = origin, open = nextOpen,
                    menu = menu, details = nextDetails, readCount = nextReadCount
                ) ?: SiteNotice(
                    key, NoticeKind.COUNTER, title, url, count, page = origin, firstSeen = now,
                    read = nextRead, details = nextDetails, open = nextOpen, menu = menu,
                    readCount = nextReadCount
                )
            }
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

    /** Visible unread first, then the newest; over [MAX], hidden, read and older ones go first. */
    fun ordered(notices: Collection<SiteNotice>) = notices.sortedWith(
        compareBy<SiteNotice> { if (visible(it)) 0 else 1 }
            .thenBy { it.read }
            .thenByDescending { it.firstSeen }
            .thenBy { it.key }
    ).take(MAX)

    fun markRead(current: List<SiteNotice>, key: String) =
        current.map { if (it.key == key) it.copy(read = true, readCount = readNumber(it)) else it }

    fun markAllRead(current: List<SiteNotice>) =
        current.map { if (visible(it)) it.copy(read = true, readCount = readNumber(it)) else it }

    fun unread(current: List<SiteNotice>) = current.count { visible(it) && !it.read }

    /** What the Notifications window lists: a read or answered item stays out of it. */
    fun listed(current: List<SiteNotice>) = current.filter { visible(it) && !it.read }

    /**
     * Items unread in [next] that should be announced: new ones and counters that grew.
     * An unverified questionnaire result (Pending, Rechecking or Fallback) is never announced.
     */
    fun newlyUnread(previous: List<SiteNotice>, next: List<SiteNotice>): List<SiteNotice> {
        val before = previous.associateBy { it.key }
        return next.filter { notice ->
            if (!visible(notice) || notice.read) return@filter false
            val old = before[notice.key]
            if (isSurvey(notice)) {
                notice.surveyStatus == SurveyCheck.VERIFIED &&
                    (old == null || old.surveyStatus == SurveyCheck.PENDING || notice.open > old.open)
            } else {
                old == null || old.read || notice.count > old.count
            }
        }
    }
}
