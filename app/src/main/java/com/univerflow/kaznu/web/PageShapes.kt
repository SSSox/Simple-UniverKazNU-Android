package com.univerflow.kaznu.web

import java.net.URI

/** The adapted page a placeholder stands for while it loads, drawn from the student pages' measured phone layout. */
enum class PageShape {
    NEWS, BACHELOR, SERVICES, NEWS_LIST, FAQ, CATALOG, MESSAGES, CALCULATOR, APPEALS,
    SURVEYS, SURVEY, QUESTIONS, FORM, PRACTICE, ADVISOR, CONTRACTS,
    CALENDAR, EXAMS, MIDTERM, PLAN, ATTESTATION, PROFILE, IGPA, TESTS, DEBTS, RETAKES, RAISE,
    SCHEDULE, JOURNAL, MATERIALS, DISTANCE, LIST
}

/** Which placeholder a page gets: by its path, with every address under a section (a page of FAQ, a survey) matched by prefix. */
object PageShapes {
    private fun route(pattern: String, shape: PageShape) = Regex(pattern, RegexOption.IGNORE_CASE) to shape

    // First match wins: the survey list, a survey and its questions share one prefix.
    private val ROUTES = listOf(
        route("^/(?:news(?:/.*)?|my/news(?:/.*)?|lang/change/(?:ru|kz|en)/?)?$", PageShape.NEWS),
        route("^/student/bachelor/?$", PageShape.BACHELOR),
        route("^/ssc/?$", PageShape.SERVICES),
        route("^/student/monrknews(?:/|$)", PageShape.NEWS_LIST),
        route("^/my/faq(?:/|$)", PageShape.FAQ),
        route("^/my/docs(?:/|$)", PageShape.CATALOG),
        route("^/my/messages(?:/|$)", PageShape.MESSAGES),
        route("^/my/calculatorgpa(?:/|$)", PageShape.CALCULATOR),
        route("^/my/appeals(?:/|$)", PageShape.APPEALS),
        route("^/student/rating/?$", PageShape.SURVEYS),
        route("^/student/rating/\\d+/?$", PageShape.SURVEY),
        route("^/student/rating/\\d+/.", PageShape.QUESTIONS),
        route("^/student/mydata(?:/|$)", PageShape.FORM),
        route("^/student/practik(?:/|$)", PageShape.PRACTICE),
        route("^/student/advicer(?:/|$)", PageShape.ADVISOR),
        route("^/student/contract(?:/|$)", PageShape.CONTRACTS),
        route("^/student/academcalendar(?:/|$)", PageShape.CALENDAR),
        route("^/student/myexam(?:/|$)", PageShape.EXAMS),
        route("^/student/mymidterm(?:/|$)", PageShape.MIDTERM),
        route("^/student/educplan(?:/|$)", PageShape.PLAN),
        route("^/student/attestation(?:/|$)", PageShape.ATTESTATION),
        route("^/student/profile(?:/|$)", PageShape.PROFILE),
        route("^/student/stud/rating/anket(?:/|$)", PageShape.IGPA),
        route("^/student/testlist(?:/|$)", PageShape.TESTS),
        route("^/student/fail(?:/|$)", PageShape.DEBTS),
        route("^/student/failfx(?:/|$)", PageShape.RETAKES),
        route("^/student/gpaincrease(?:/|$)", PageShape.RAISE),
        route("^/student/myschedule(?:/|$)", PageShape.SCHEDULE),
        route("^/student/attendance(?:/|$)", PageShape.JOURNAL),
        route("^/student/umkd(?:/|$)", PageShape.MATERIALS),
        route("^/student/discussion(?:/|$)", PageShape.DISTANCE),
    )
    private val HOME_TAB = Regex("^/(?:$|news|my/|lang/|student/monrknews)", RegexOption.IGNORE_CASE)
    private val SERVICES_TAB = Regex("^/ssc(?:/|$)", RegexOption.IGNORE_CASE)

    private fun path(url: String?) = if (!SitePolicy.trusted(url)) null else runCatching { URI(url!!).path.orEmpty() }.getOrNull()

    /** A page of another site or one without its own drawing gets the plain list. */
    fun of(url: String?): PageShape {
        val path = path(url) ?: return PageShape.LIST
        return ROUTES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(path) }?.second ?: PageShape.LIST
    }

    /** The site's tab lit on that page: Главная (0), Бакалавр (1) or Е-услуги (2). */
    fun tab(url: String?): Int {
        val path = path(url) ?: return 1
        return when {
            HOME_TAB.containsMatchIn(path) -> 0
            SERVICES_TAB.containsMatchIn(path) -> 2
            else -> 1
        }
    }
}
