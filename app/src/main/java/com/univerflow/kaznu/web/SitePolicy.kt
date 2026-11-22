package com.univerflow.kaznu.web

import java.net.URI
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.StartPage

/** Native shortcuts to observed student routes; every other section stays in the site's own menu. */
enum class AppSection(val path: String) {
    HOME("/"), SCHEDULE("/student/myschedule/"), JOURNAL("/student/attendance/full/"), ATTESTATION("/student/attestation/"),
    MATERIALS("/student/umkd/"), DISTANCE("/student/discussion/index/");
    val url get() = SitePolicy.ORIGIN + path
}

object SitePolicy {
    const val ORIGIN = "https://univer.kaznu.kz"
    const val HOME = "$ORIGIN/"

    // Compiled once: these checks run on every navigation, link tap, render and placeholder of the main screen.
    private val SECTIONS = listOf(
        Regex("^/student/myschedule(/|$)", RegexOption.IGNORE_CASE) to AppSection.SCHEDULE,
        Regex("^/student/attendance(/|$)", RegexOption.IGNORE_CASE) to AppSection.JOURNAL,
        Regex("^/student/attestation(/|$)", RegexOption.IGNORE_CASE) to AppSection.ATTESTATION,
        Regex("^/student/umkd(/|$)", RegexOption.IGNORE_CASE) to AppSection.MATERIALS,
        Regex("^/student/discussion(/|$)", RegexOption.IGNORE_CASE) to AppSection.DISTANCE,
    )
    private val LANGUAGE_CHANGE = Regex("^/lang/change/(ru|kz|en)/?$")
    private val PRINT_DOCUMENT = Regex("^/student/(?:iup(?:/\\d+)*(?:/)?|transcript(?:/\\d+)?/?|recbook(?:/\\d+)?/?|interactive/syllabus/print/\\d+/\\d+/?|myschedule/print/\\d+/\\d+/?)$", RegexOption.IGNORE_CASE)
    private val MATERIAL_FILE = Regex("^/student/umkd/get/\\d+/\\d+/\\d+/\\d+/?$", RegexOption.IGNORE_CASE)
    private val FILE_EXTENSION = Regex("\\.(?:pdf|docx?|xlsx?|pptx?|odt|ods|rtf|zip|rar)$", RegexOption.IGNORE_CASE)
    private val NEWS_ROUTE = Regex("^/(?:|my/news/?|my/news/(\\d{1,4})/?)$")
    private val LOGIN_FORM = Regex("^/user/login/?$", RegexOption.IGNORE_CASE)
    private val JOURNAL = Regex("^/student/attendance/full(/.*)?$")
    private val BACHELOR = Regex("^/student/bachelor/?$")
    private val NOT_RESUMABLE = Regex("^/(user|lang|student/attention|__univer)", RegexOption.IGNORE_CASE)
    private val READ_NOTICE = Regex("^/student/attentionStudents/[0-9]+/?$")
    private val TOP_LEVEL_LABEL = Regex("^[a-z][a-z0-9-]*$", RegexOption.IGNORE_CASE)
    private val ONLINE_TEST = Regex("^/student/AuthorizationOnOnlineTestForm/?$", RegexOption.IGNORE_CASE)
    const val ONLINE_TEST_HOST = "online-test.kaznu.kz"

    /** The shortcut that matches the current page, or null for an untrusted address. */
    fun section(url: String?): AppSection? = if (!trusted(url)) null else runCatching {
        val path = URI(url!!).path.orEmpty()
        SECTIONS.firstOrNull { (pattern, _) -> pattern.containsMatchIn(path) }?.second ?: AppSection.HOME
    }.getOrNull()
    fun logout(url: String?): Boolean = trusted(url) && runCatching {
        URI(url!!).rawPath in setOf("/user/logout", "/user/logout/")
    }.getOrDefault(false)
    /**
     * Where a session starts. The site keeps its language in the server session (ASP.NET_SessionId), and a new one answers
     * in Kazakh: without that cookie (first launch, after signing out) the start goes through the site's own flag link,
     * which sets [language] and redirects to the start page, so no Kazakh page shows first. An existing session keeps
     * its language: an unconditional switch would add a request and a redirect to every launch.
     */
    fun startUrl(cookies: String?, language: AppLanguage, page: StartPage = StartPage.NEWS): String =
        if (hasSession(cookies)) startPageUrl(page) else "$ORIGIN/lang/change/${language.siteCode}/"
    fun hasSession(cookies: String?): Boolean = cookies.orEmpty().split(';').any { it.trim().startsWith("ASP.NET_SessionId=") }
    /** The student's chosen start page. News is the site's own start route, which is also where the read notice is re-posted. */
    fun startPageUrl(page: StartPage): String = when (page) {
        StartPage.NEWS -> HOME
        StartPage.BACHELOR -> "$ORIGIN/student/bachelor/"
    }
    /** Only the site's real header flag endpoint changes the application language. */
    fun languageChange(url: String?): AppLanguage? = if (!trusted(url)) null else runCatching {
        val code = LANGUAGE_CHANGE.matchEntire(URI(url!!).rawPath)?.groupValues?.get(1)
        AppLanguage.entries.firstOrNull { it.siteCode == code }
    }.getOrNull()
    fun printDocument(url: String?): Boolean = trusted(url) && runCatching {
        PRINT_DOCUMENT.matches(URI(url!!).path)
    }.getOrDefault(false)
    /** Protected course materials and office/PDF files linked from the site (guides, forms). */
    fun materialFile(url: String?): Boolean = trusted(url) && runCatching { MATERIAL_FILE.matches(URI(url!!).path) }.getOrDefault(false)
    /**
     * A teacher's link from УМКД: a plain web page on a named host. Mail and phone links, addresses with a user name,
     * IP addresses (the last label of a real domain starts with a letter) and local names are not study sources.
     */
    fun studyLink(url: String?): Boolean = external(url) && runCatching {
        val uri = URI(url!!)
        val host = uri.host ?: return false
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && host.indexOf('.') > 0 && !host.endsWith(".") &&
            !host.equals("univer.kaznu.kz", true) && TOP_LEVEL_LABEL.matches(host.substringAfterLast('.'))
    }.getOrDefault(false)
    /** A course material the server sends on to a page on another site: the teacher left a link there, not a file. */
    fun materialLink(material: String?, target: String?): Boolean = materialFile(material) && !trusted(target) && studyLink(target)
    fun documentDownload(url: String?): Boolean = trusted(url) && runCatching {
        val path = URI(url!!).path
        MATERIAL_FILE.matches(path) || FILE_EXTENSION.containsMatchIn(path)
    }.getOrDefault(false)
    /**
     * "/" and the site's news pager (/my/news/{n}) pass through /student/attention, which re-posts the read
     * notice and lands on page 1; /news/student/{n} is the observed direct list. Used for user taps only.
     */
    fun newsShortcut(url: String?): String? = if (!trusted(url)) null else runCatching {
        val uri = URI(url!!)
        if (uri.rawQuery != null && uri.path == "/") return null
        val match = NEWS_ROUTE.matchEntire(uri.path) ?: return null
        val page = match.groupValues[1].toIntOrNull()
        if (page != null && page > 1) "$ORIGIN/news/student/$page" else "$ORIGIN/news/student"
    }.getOrNull()
    /** The site's full-page login form, where an ended session is redirected. */
    fun loginPage(url: String?): Boolean = trusted(url) && runCatching {
        LOGIN_FORM.matches(URI(url!!).rawPath)
    }.getOrDefault(false)
    /** The journal (/student/attendance/full/…), which the app reads for the background check's base. */
    fun journalPage(url: String?): Boolean = trusted(url) && runCatching {
        JOURNAL.matches(URI(url!!).rawPath.orEmpty())
    }.getOrDefault(false)
    /** The journal or Кабинет (/student/bachelor/), whose grades panel shows the same totals. */
    fun gradesPage(url: String?): Boolean = journalPage(url) || trusted(url) && runCatching {
        BACHELOR.matches(URI(url!!).rawPath.orEmpty())
    }.getOrDefault(false)
    /** The supplied read notice's own page (/student/attentionStudents/{id}), which the automation confirms. */
    fun readNotice(url: String?): Boolean = trusted(url) && runCatching {
        READ_NOTICE.matches(URI(url!!).path.orEmpty())
    }.getOrDefault(false)
    /**
     * A section page worth reopening after an automatic login. Not the start/news route (it re-posts
     * the read notice), account/language/notice endpoints, files or print windows.
     */
    fun resumable(url: String?): Boolean = trusted(url) && newsShortcut(url) == null && !documentDownload(url) &&
        !printDocument(url) && runCatching {
            val path = URI(url!!).rawPath.orEmpty()
            path.length > 1 && !NOT_RESUMABLE.containsMatchIn(path)
        }.getOrDefault(false)
    fun trusted(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme.equals("https", true) && uri.host.equals("univer.kaznu.kz", true) &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)

    /** Addresses a deliberate user action may hand to another application. */
    fun external(url: String?): Boolean = runCatching {
        if (url == null || url.any { it.isISOControl() }) return false
        val uri = URI(url)
        when (uri.scheme?.lowercase()) {
            "https", "http" -> !uri.host.isNullOrBlank() && uri.userInfo == null
            "mailto", "tel" -> !uri.rawSchemeSpecificPart.isNullOrBlank()
            else -> false
        }
    }.getOrDefault(false)

    fun externalNavigation(url: String?, userGesture: Boolean, redirect: Boolean, method: String) =
        userGesture && !redirect && method.equals("GET", true) && external(url)

    /** «Онлайн тест»: the site's route that signs the student in to the test portal with a server redirect. */
    fun onlineTest(url: String?): Boolean = trusted(url) && runCatching { ONLINE_TEST.matches(URI(url!!).path.orEmpty()) }.getOrDefault(false)
    /** That portal: HTTPS on port 443 at exactly online-test.kaznu.kz, without a user name. */
    fun onlineTestPortal(url: String?): Boolean = runCatching {
        if (url == null || url.any { it.isISOControl() }) return false
        val uri = URI(url)
        uri.scheme.equals("https", true) && uri.host.equals(ONLINE_TEST_HOST, true) && uri.userInfo == null && (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)
    /** Only the server redirect that answers the student's own tap on «Онлайн тест» leaves for the portal; a script, a late or a repeated redirect does not. */
    fun onlineTestExit(url: String?, method: String, redirect: Boolean, armed: Boolean) =
        armed && redirect && method.equals("GET", true) && onlineTestPortal(url)
    /** The tap that arms that redirect: a GET of the route with a gesture, not a redirect. */
    fun onlineTestTap(url: String?, method: String, userGesture: Boolean, redirect: Boolean) =
        userGesture && !redirect && method.equals("GET", true) && onlineTest(url)
}
