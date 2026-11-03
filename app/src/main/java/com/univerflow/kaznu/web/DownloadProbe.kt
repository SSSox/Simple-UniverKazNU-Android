package com.univerflow.kaznu.web

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

data class DownloadMetadata(val url: String, val disposition: String?, val mime: String?, val length: Long)
class DownloadNeedsLogin : Exception()
class DownloadAccessDenied : Exception()

/** The validated response is the response saved to disk: no second GET and no unchecked redirects. */
class DownloadResponse internal constructor(val metadata: DownloadMetadata, private val input: InputStream,
    private val connection: HttpURLConnection) : Closeable {
    fun saveTo(output: OutputStream, checkActive: () -> Unit = {}, progress: (Long, Long) -> Unit = { _, _ -> }) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            checkActive()
            output.write(buffer, 0, count)
            total += count
            progress(total, metadata.length)
        }
        if (total == 0L || (metadata.length >= 0 && total != metadata.length)) throw IOException("Incomplete download")
        output.flush()
    }
    override fun close() { try { input.close() } finally { connection.disconnect() } }
}

class DownloadProbe(private val connect: (String) -> HttpURLConnection = {
    URL(it).openConnection() as HttpURLConnection
}) {
    /** Suspending: a `Set-Cookie` may need the main thread, and blocking a download coroutine on it stalls the frame. */
    suspend fun open(url: String, agent: String, referer: String?,
        cookie: (String) -> String? = { null }, setCookie: suspend (String, String) -> Unit = { _, _ -> },
        connectionChanged: (HttpURLConnection) -> Unit = {}): DownloadResponse {
        require(SingleSignOnPolicy.downloadAllowed(url))
        var next = url
        repeat(6) {
            require(SingleSignOnPolicy.sameService(url, next))
            if (signInPage(next)) throw DownloadNeedsLogin()
            val connection = connect(next)
            var retained = false
            try {
                connectionChanged(connection)
                prepare(connection, next, url, agent, referer, cookie)
                val status = connection.responseCode
                for (value in connection.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value })
                    setCookie(next, value)
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val target = URI(next).resolve(connection.getHeaderField("Location") ?: error("Missing redirect")).toString()
                    require(SingleSignOnPolicy.sameService(url, target)) // Never send session cookies to another service.
                    next = target
                } else {
                    val response = accept(connection, status, next)
                    retained = true
                    return response
                }
            } finally { if (!retained) connection.disconnect() }
        }
        error("Too many redirects")
    }

    /** The Univer and Moodle sign-in pages and the read notice are never requested as a file. */
    private fun signInPage(url: String): Boolean {
        val path = URI(url).path
        return SingleSignOnPolicy.moodleSignIn(url) ||
            SitePolicy.trusted(url) && (path.startsWith("/user/login", true) || path.contains("/attentionStudents/", true))
    }

    /** One plain GET: no redirects followed, no cache, no compression; the referer only from the file's own service. */
    private fun prepare(connection: HttpURLConnection, target: String, origin: String, agent: String, referer: String?,
        cookie: (String) -> String?) {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.useCaches = false
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", agent)
        connection.setRequestProperty("Accept-Encoding", "identity")
        cookie(target)?.let { connection.setRequestProperty("Cookie", it) }
        referer?.takeIf { SingleSignOnPolicy.sameService(origin, it) }?.let { connection.setRequestProperty("Referer", it) }
    }

    /** The final response: a file with status 200, or a refusal (a login page, an expired session, no access). */
    private fun accept(connection: HttpURLConnection, status: Int, url: String): DownloadResponse {
        if (status == 213 || status == 401) throw DownloadNeedsLogin()
        if (status == 403) throw DownloadAccessDenied()
        check(status == 200) { "Download HTTP error" }
        val mime = connection.contentType?.substringBefore(';')?.trim()
        val disposition = connection.getHeaderField("Content-Disposition")
        if (mime.equals("text/html", true) || mime.equals("application/xhtml+xml", true))
            throw DownloadNeedsLogin()
        val input = BufferedInputStream(connection.inputStream, 64 * 1024)
        if (startsLikeLoginPage(input)) {
            input.close()
            throw DownloadNeedsLogin()
        }
        val length = if (connection.contentEncoding.isNullOrBlank() || connection.contentEncoding.equals("identity", true))
            connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L else -1L
        return DownloadResponse(DownloadMetadata(url, disposition, mime, length), input, connection)
    }

    // Some expired sessions are mislabeled as binary attachments. Inspect only a bounded prefix, then rewind.
    private fun startsLikeLoginPage(input: BufferedInputStream): Boolean {
        input.mark(4096)
        val prefix = ByteArray(4096)
        var size = 0
        while (size < prefix.size) {
            val count = input.read(prefix, size, prefix.size - size)
            if (count < 0) break
            size += count
        }
        input.reset()
        val beginning = String(prefix, 0, size, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        return Regex("^(?:<!doctype\\s+html|<html\\b|<head\\b|<body\\b|<form\\b)", RegexOption.IGNORE_CASE).containsMatchIn(beginning) ||
            beginning.contains("id=\"login_frm\"", true) || beginning.contains("id='login_frm'", true)
    }
}

object DownloadFileNames {
    fun fromDisposition(header: String?): String? {
        if (header == null) return null
        // RFC 5987 filename* takes priority over the often-ASCII filename fallback.
        val encoded = Regex("(?:^|;)\\s*filename\\*\\s*=\\s*\"?([^;\"]+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
        encoded?.let {
            val parts = it.trim().split('\'', limit = 3)
            if (parts.size == 3 && parts[0].equals("UTF-8", true))
                runCatching { URLDecoder.decode(parts[2].replace("+", "%2B"), "UTF-8") }.getOrNull()?.let { name -> return safe(name) }
        }
        return Regex("(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))", RegexOption.IGNORE_CASE).find(header)
            ?.let { safe(it.groupValues[1].ifEmpty { it.groupValues[2].trim() }) }
    }

    fun safe(name: String): String {
        val cleaned = name.replace(Regex("[\\p{Cc}\\p{Cf}/\\\\:*?\"<>|]"), "_").trim(' ', '.')
        val extension = cleaned.substringAfterLast('.', "").takeIf { it.length in 1..12 }
        val base = if (extension == null) cleaned else cleaned.substringBeforeLast('.')
        var bounded = base.take(100)
        // File systems limit names in bytes. CJK/emoji can exceed that limit at 100 characters.
        while (bounded.toByteArray(Charsets.UTF_8).size > 200 || bounded.lastOrNull()?.isHighSurrogate() == true)
            bounded = bounded.dropLast(1)
        return (bounded.ifBlank { "Univer-document" } + (extension?.let { ".$it" } ?: ""))
    }

    fun unique(name: String, suffix: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.take(dot) + "-$suffix" + name.substring(dot) else "$name-$suffix"
    }
}
