package com.univerflow.kaznu.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The text file a student can attach to a fault report: the app's version, the phone, the settings as words and
 * numbers, and a few state flags. Every line goes through [clean], the one place that removes what could name the
 * student: the login and password (given as secrets), cookies, addresses' queries, ids and user info, e-mail
 * addresses, long numbers and long tokens. No page content is ever given to it.
 */
object DiagnosticFile {
    const val MAX_LINE = 300
    private const val MIN_SECRET = 3

    private val control = Regex("[\\u0000-\\u001F\\u007F-\\u009F\\u2028\\u2029]+")
    private val url = Regex("\\b(?:https?|file|ftp)://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    private val cookie = Regex("\\b(?:set-)?cookie\\s*[:=]\\s*\\S.*|[A-Za-z0-9_.\\-]*(?:session|token|auth|sid)[A-Za-z0-9_.\\-]*\\s*=\\s*[^;\\s]+", RegexOption.IGNORE_CASE)
    private val mail = Regex("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.\\p{L}{2,}")
    private val phone = Regex("\\+?\\d[\\d\\s()\\-]{7,}\\d")
    private val number = Regex("\\d{6,}")
    // Without «/» and «.», so an address's host and path are not taken for one.
    private val token = Regex("[A-Za-z0-9_\\-+=%]{24,}")
    private val addressParts = Regex("^([a-z]+)://(?:[^/?#@]*@)?([^/?#]*)([^?#]*)", RegexOption.IGNORE_CASE)
    private val pathNumbers = Regex("[^/]*\\d[^/]*")

    /** One line with nothing that could name the student. Secrets shorter than 3 characters are ignored (they would empty the file). */
    fun clean(text: String, secrets: Collection<String> = emptyList()): String {
        var line = control.replace(text, " ")
        if (line.length > 4 * MAX_LINE) line = line.substring(0, 4 * MAX_LINE)
        line = url.replace(line) { address(it.value) }
        line = cookie.replace(line, "<hidden>")
        secrets.map { it.trim() }.filter { it.length >= MIN_SECRET }.sortedByDescending { it.length }.forEach { secret ->
            line = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(secret) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).replace(line, "<hidden>")
        }
        line = mail.replace(line, "<e-mail>")
        line = token.replace(line) { if (it.value.any(Char::isDigit) || it.value.length >= 32) "<token>" else it.value }
        line = phone.replace(line) { if (it.value.count(Char::isDigit) >= 10) "<number>" else it.value }
        line = number.replace(line, "<number>")
        line = line.trim()
        return if (line.length > MAX_LINE) line.take(MAX_LINE - 1) + "…" else line
    }

    /** An address keeps its host and the shape of its path; query, fragment, user info and numbers in the path go. */
    internal fun address(value: String): String {
        val (scheme, host, path) = addressParts.find(value)?.destructured ?: return "<address>"
        if (scheme.equals("file", true)) return "file:///<file>"
        val shape = path.replace(pathNumbers, "{n}").let { if (it.length > 120) it.take(120) + "…" else it }
        return "$scheme://$host$shape"
    }

    /** The whole file: a heading, then «name: value» lines in the given order. */
    fun build(facts: List<Pair<String, String>>, secrets: Collection<String>, now: Date): String = buildString {
        append("Univer Android diagnostics ").append(stamp(now, "yyyy-MM-dd HH:mm:ss")).append('\n')
        append("No login, password, cookies, page contents or personal data are included.\n\n")
        facts.forEach { (name, value) -> append(clean("$name: $value", secrets)).append('\n') }
    }

    fun fileName(now: Date): String = "Univer-diagnostics-" + stamp(now, "yyyy-MM-dd-HHmm") + ".txt"

    private fun stamp(now: Date, pattern: String) = SimpleDateFormat(pattern, Locale.ROOT).format(now)
}
