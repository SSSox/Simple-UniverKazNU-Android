package com.univerflow.kaznu.web

import java.net.URI

/** Observed Univer Moodle entry and both current/legacy Moodle origins. No wildcard trust. */
object SingleSignOnPolicy {
    const val ENTRY = "${SitePolicy.ORIGIN}/user/loginMoodle"

    fun entry(url: String?): Boolean = SitePolicy.trusted(url) && runCatching {
        Regex("^/user/loginMoodle/?$", RegexOption.IGNORE_CASE).matches(URI(url!!).rawPath)
    }.getOrDefault(false)

    fun moodle(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme.equals("https", true) && uri.host?.lowercase() in setOf("dl.kaznu.kz", "dl.kaznu.edu.kz") &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)

    /** Moodle's own sign-in page, shown after its session ends. */
    fun moodleSignIn(url: String?): Boolean = moodle(url) && runCatching {
        Regex("""^/login(/index\.php)?/?$""", RegexOption.IGNORE_CASE).matches(URI(url!!).rawPath)
    }.getOrDefault(false)

    /** Moodle's own Log out: the user ends the Moodle session, so it is not signed in again. */
    fun moodleLogout(url: String?): Boolean = moodle(url) && runCatching {
        Regex("""^/login/logout\.php$""",RegexOption.IGNORE_CASE).matches(URI(url!!).rawPath)
    }.getOrDefault(false)

    /**
     * A Moodle page to reopen after its session was renewed: an ordinary GET address, never the
     * sign-in/logout/SSO steps or an address carrying a session key or token.
     */
    fun moodleResumable(url: String?): Boolean = moodle(url) && !moodleSignIn(url) && runCatching {
        val uri = URI(url!!)
        val path = uri.rawPath.orEmpty()
        url.length <= 2_000 && !Regex("^/(login|auth|admin)(/|$)", RegexOption.IGNORE_CASE).containsMatchIn(path) &&
            !Regex("(^|&)(sesskey|token|wstoken|key|ticket|code)=", RegexOption.IGNORE_CASE).containsMatchIn(uri.rawQuery.orEmpty())
    }.getOrDefault(false)

    /** Files may come from Univer or Moodle; every hop stays within the service it started on. */
    fun downloadAllowed(url: String?) = SitePolicy.trusted(url) || moodle(url)
    fun sameService(first: String?, second: String?) =
        (SitePolicy.trusted(first) && SitePolicy.trusted(second)) || (moodle(first) && moodle(second))

    fun opensViewer(url: String?) = entry(url) || moodle(url)
    fun allowed(url: String?) = SitePolicy.trusted(url) || moodle(url)
}
