package com.univerflow.kaznu.web

import java.net.URI
import com.univerflow.kaznu.domain.AppLanguage

/** Native shortcuts to observed student routes; every other section stays in the site's own menu. */
enum class AppSection(val path: String) {
    HOME("/"), SCHEDULE("/student/myschedule/"), JOURNAL("/student/attendance/full/"), ATTESTATION("/student/attestation/"),
    MATERIALS("/student/umkd/"), DISTANCE("/student/discussion/index/");
    val url get() = SitePolicy.ORIGIN + path
}

object SitePolicy {
    const val ORIGIN = "https://univer.kaznu.kz"
    const val HOME = "$ORIGIN/"
    /** The shortcut that matches the current page, or null for an untrusted address. */
    fun section(url: String?): AppSection? = if (!trusted(url)) null else runCatching {
        val path = URI(url!!).path.orEmpty()
        when {
            Regex("^/student/myschedule(/|$)", RegexOption.IGNORE_CASE).containsMatchIn(path) -> AppSection.SCHEDULE
            Regex("^/student/attendance(/|$)", RegexOption.IGNORE_CASE).containsMatchIn(path) -> AppSection.JOURNAL
            Regex("^/student/attestation(/|$)", RegexOption.IGNORE_CASE).containsMatchIn(path) -> AppSection.ATTESTATION
            Regex("^/student/umkd(/|$)", RegexOption.IGNORE_CASE).containsMatchIn(path) -> AppSection.MATERIALS
            Regex("^/student/discussion(/|$)", RegexOption.IGNORE_CASE).containsMatchIn(path) -> AppSection.DISTANCE
            else -> AppSection.HOME
        }
    }.getOrNull()
    fun logout(url: String?): Boolean = trusted(url) && runCatching {
        URI(url!!).rawPath in setOf("/user/logout", "/user/logout/")
    }.getOrDefault(false)
    /** Only the site's real header flag endpoint changes the application language. */
    fun languageChange(url: String?): AppLanguage? = if (!trusted(url)) null else runCatching {
        val code = Regex("^/lang/change/(ru|kz|en)/?$").matchEntire(URI(url!!).rawPath)?.groupValues?.get(1)
        AppLanguage.entries.firstOrNull { it.siteCode == code }
    }.getOrNull()
    fun printDocument(url: String?): Boolean = trusted(url) && runCatching {
        Regex("^/student/(?:iup(?:/\\d+)*(?:/)?|transcript(?:/\\d+)?/?|recbook(?:/\\d+)?/?|interactive/syllabus/print/\\d+/\\d+/?|myschedule/print/\\d+/\\d+/?)$", RegexOption.IGNORE_CASE)
            .matches(URI(url!!).path)
    }.getOrDefault(false)
    /** Protected course materials and office/PDF files linked from the site (guides, forms). */
    fun documentDownload(url: String?): Boolean = trusted(url) && runCatching {
        val path = URI(url!!).path
        Regex("^/student/umkd/get/\\d+/\\d+/\\d+/\\d+/?$", RegexOption.IGNORE_CASE).matches(path) ||
            Regex("\\.(?:pdf|docx?|xlsx?|pptx?|odt|ods|rtf|zip|rar)$", RegexOption.IGNORE_CASE).containsMatchIn(path)
    }.getOrDefault(false)
    /**
     * "/" and the site's news pager (/my/news/{n}) pass through /student/attention, which re-posts the read
     * notice and lands on page 1; /news/student/{n} is the observed direct list. Used for user taps only.
     */
    fun newsShortcut(url: String?): String? = if (!trusted(url)) null else runCatching {
        val uri = URI(url!!)
        if (uri.rawQuery != null && uri.path == "/") return null
        val match = Regex("^/(?:|my/news/?|my/news/(\\d{1,4})/?)$").matchEntire(uri.path) ?: return null
        val page = match.groupValues[1].toIntOrNull()
        if (page != null && page > 1) "$ORIGIN/news/student/$page" else "$ORIGIN/news/student"
    }.getOrNull()
    /** The site's full-page login form, where an ended session is redirected. */
    fun loginPage(url: String?): Boolean = trusted(url) && runCatching {
        Regex("^/user/login/?$", RegexOption.IGNORE_CASE).matches(URI(url!!).rawPath)
    }.getOrDefault(false)
    /**
     * A section page worth reopening after an automatic login. Not the start/news route (it re-posts
     * the read notice), account/language/notice endpoints, files or print windows.
     */
    fun resumable(url: String?): Boolean = trusted(url) && newsShortcut(url) == null && !documentDownload(url) &&
        !printDocument(url) && runCatching {
            val path = URI(url!!).rawPath.orEmpty()
            path.length > 1 && !Regex("^/(user|lang|student/attention|__univer)", RegexOption.IGNORE_CASE).containsMatchIn(path)
        }.getOrDefault(false)
    fun trusted(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme.equals("https", true) && uri.host.equals("univer.kaznu.kz", true) &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)
}
