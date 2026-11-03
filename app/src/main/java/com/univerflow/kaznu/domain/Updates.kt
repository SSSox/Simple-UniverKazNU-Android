package com.univerflow.kaznu.domain

import java.net.URI

/** Where the app's releases live and how an offered version is judged. */
object Updates {
    const val REPOSITORY = "https://github.com/SSSox/Simple-UniverKazNU-Android"
    const val LATEST = "$REPOSITORY/releases/latest"
    const val APK = "app-release.apk"
    /** Automatic checks run at most this often; the Settings button checks at once. */
    const val CHECK_INTERVAL_MS = 6 * 60 * 60_000L
    /** A failed automatic check waits this long, so an offline phone does not retry on every return to the app. */
    const val RETRY_INTERVAL_MS = 15 * 60_000L
    const val MAX_APK_BYTES = 64L * 1024 * 1024
    private val TAG = Regex("^" + Regex.escape(REPOSITORY) + "/releases/tag/v([0-9]+(?:\\.[0-9]+){0,3})/?$")
    private val CHECKSUM = Regex("^([0-9a-fA-F]{64})(?:\\s.*)?$", RegexOption.DOT_MATCHES_ALL)

    /** The version in the address that "latest release" redirects to; null without a released version tag. */
    fun version(location: String?): String? = location?.let { TAG.find(it.trim())?.groupValues?.get(1) }

    fun newer(candidate: String, current: String): Boolean {
        val next = parts(candidate) ?: return false
        val installed = parts(current) ?: return false
        for (index in 0 until maxOf(next.size, installed.size)) {
            val difference = next.getOrElse(index) { 0 }.compareTo(installed.getOrElse(index) { 0 })
            if (difference != 0) return difference > 0
        }
        return false
    }

    private fun parts(version: String): List<Int>? = version.split('.').map { it.toIntOrNull() ?: return null }

    fun apkUrl(version: String) = "$REPOSITORY/releases/download/v$version/$APK"
    fun checksumUrl(version: String) = "${apkUrl(version)}.sha256"

    /** The hash from a `.sha256` file: `<hash>  <name>`. */
    fun checksum(text: String): String? = CHECKSUM.find(text.trim())?.groupValues?.get(1)?.lowercase()

    /** GitHub and the host it redirects release files to, over plain HTTPS. */
    fun allowedHost(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase()
        uri.scheme.equals("https", true) && uri.userInfo == null && uri.port in listOf(-1, 443) && host != null &&
            (host == "github.com" || host.endsWith(".githubusercontent.com"))
    }.getOrDefault(false)

    /** A clock moved backwards counts as due, or the next check could be years away. */
    fun due(now: Long, checkedAt: Long) = checkedAt <= 0 || now < checkedAt || now - checkedAt >= CHECK_INTERVAL_MS

    /** The dialog at entry: once per run, and never for a version the student chose not to see again. */
    fun prompt(available: String?, skipped: String?, answered: Boolean) = available != null && available != skipped && !answered
}
