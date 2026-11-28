package com.univerflow.kaznu.web

import java.net.URI

/** Where a tapped link to another site opens. */
enum class LinkOpening { APP, BROWSER, BLOCKED }

/** What a main-frame navigation inside the app's link window does. */
enum class LinkStep { STAY, MAIN, SYSTEM, BLOCK }

/**
 * Pages of other sites (a teacher's page, Google Drive, a library) open in the app's own link window. It has a WebView
 * profile of its own: sign-ins and cookies there are kept between runs, never mixed with the Univer and Moodle session,
 * and cleared on their own from settings or with the account.
 */
object LinkPolicy {
    const val PROFILE = "links"

    /**
     * A tapped link: an https page on a named host opens in the app unless the user chose the browser or this WebView has
     * no separate profiles; plain http (the app allows no cleartext), mail and phone go to the system.
     */
    fun open(url: String?, preferBrowser: Boolean, windowAvailable: Boolean): LinkOpening = when {
        !SitePolicy.external(url) || SitePolicy.trusted(url) -> LinkOpening.BLOCKED
        !preferBrowser && windowAvailable && https(url) && SitePolicy.studyLink(url) -> LinkOpening.APP
        else -> LinkOpening.BROWSER
    }

    /**
     * A main-frame navigation in the link window. Https pages stay. A deliberate tap on a Univer or Moodle page goes back
     * to the main screen, signed in as usual; a redirect or a script never does, so another site cannot drive the
     * signed-in session. Mail, phone and plain http go to the system only on a tap; any other scheme is refused.
     */
    fun step(url: String?, gesture: Boolean, redirect: Boolean): LinkStep {
        val deliberate = gesture && !redirect
        return when {
            SitePolicy.trusted(url) || SingleSignOnPolicy.moodle(url) -> if (deliberate && handBack(url)) LinkStep.MAIN else LinkStep.BLOCK
            !SitePolicy.external(url) -> LinkStep.BLOCK
            https(url) -> LinkStep.STAY
            deliberate -> LinkStep.SYSTEM
            else -> LinkStep.BLOCK
        }
    }

    /** What the main screen takes back from the link window: a section page, a course file or a Moodle page; never logout, language or sign-in. */
    fun handBack(url: String?): Boolean =
        SitePolicy.resumable(url) || SitePolicy.materialFile(url) || SingleSignOnPolicy.moodleResumable(url)

    /**
     * A file the link window was handed as a download, which may come from a navigation no one tapped: it may only be
     * offered to the user, and only an https file of another site. Univer and Moodle never leave this way: logout,
     * language, sign-in, notices and session keys included, on any port, with or without a trailing DNS dot, and under
     * a name written in front of that host (www, or any other label). Exact-origin checks for trusted navigation stay
     * as they are.
     */
    fun download(url: String?): Boolean =
        https(url) && SitePolicy.external(url) && !SitePolicy.trusted(url) && !SingleSignOnPolicy.moodle(url) && !ownHost(url)

    /** Univer's and Moodle's hostnames. Compared by name only: case, a trailing DNS dot and any labels in front ignored. */
    private val OWN_HOSTS = setOf("univer.kaznu.kz", "dl.kaznu.kz", "dl.kaznu.edu.kz")

    private fun ownHost(url: String?): Boolean = runCatching {
        val host = URI(url!!).host ?: return true
        val name = host.lowercase().trimEnd('.')
        OWN_HOSTS.any { name == it || name.endsWith(".$it") }
    }.getOrDefault(true)

    private fun https(url: String?) = runCatching { URI(url!!).scheme.equals("https", true) }.getOrDefault(false)
}
