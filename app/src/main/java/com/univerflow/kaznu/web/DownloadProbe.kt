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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.io.encoding.Base64

data class DownloadMetadata(val url: String, val disposition: String?, val mime: String?, val length: Long)
class DownloadNeedsLogin : Exception()
class DownloadAccessDenied : Exception()
class DownloadTooLarge : IOException("Download exceeds size limit")
/** The material is a teacher's link to [target], handed back unvisited: no Univer cookie leaves the site. */
class DownloadIsLink(val target: String) : Exception()

/** The validated response is the response saved to disk: no second GET and no unchecked redirects. */
class DownloadResponse internal constructor(val metadata: DownloadMetadata, private val input: InputStream,
    private val connection: HttpURLConnection, private val maxBytes: Long, val prefix: ByteArray) : Closeable {
    fun saveTo(output: OutputStream, checkActive: () -> Unit = {}, progress: (Long, Long) -> Unit = { _, _ -> }) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            checkActive()
            if (count.toLong() > maxBytes - total) throw DownloadTooLarge()
            output.write(buffer, 0, count)
            total += count
            progress(total, metadata.length)
        }
        if (total == 0L || (metadata.length >= 0 && total != metadata.length)) throw IOException("Incomplete download")
        output.flush()
    }
    override fun close() { try { input.close() } finally { connection.disconnect() } }
}

class DownloadProbe(private val maxBytes: Long = 1024L * 1024 * 1024, private val connect: (String) -> HttpURLConnection = {
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
                    // Never send session cookies to another service.
                    if (!SingleSignOnPolicy.sameService(url, target)) {
                        if (SitePolicy.materialLink(url, target)) throw DownloadIsLink(target)
                        throw IllegalArgumentException("Redirect to another service")
                    }
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
        if (!connection.contentEncoding.isNullOrBlank() && !connection.contentEncoding.equals("identity", true))
            throw IOException("Unexpected content encoding")
        val lengthHeader = connection.getHeaderField("Content-Length")
        val length = if (lengthHeader == null) -1L else lengthHeader.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw IOException("Invalid content length")
        if (length > maxBytes) throw DownloadTooLarge()
        val mime = connection.contentType?.substringBefore(';')?.trim()
        val disposition = connection.getHeaderField("Content-Disposition")
        if (mime.equals("text/html", true) || mime.equals("application/xhtml+xml", true))
            throw DownloadNeedsLogin()
        val input = BufferedInputStream(connection.inputStream, 64 * 1024)
        val prefix = readPrefix(input)
        if (startsLikeLoginPage(prefix)) {
            input.close()
            throw DownloadNeedsLogin()
        }
        return DownloadResponse(DownloadMetadata(url, disposition, mime, length), input, connection, maxBytes, prefix)
    }

    // Some expired sessions are mislabeled as binary attachments. Inspect only a bounded prefix, then rewind.
    private fun readPrefix(input: BufferedInputStream): ByteArray {
        input.mark(4096)
        val prefix = ByteArray(4096)
        var size = 0
        while (size < prefix.size) {
            val count = input.read(prefix, size, prefix.size - size)
            if (count < 0) break
            size += count
        }
        input.reset()
        return prefix.copyOf(size)
    }
    private fun startsLikeLoginPage(prefix: ByteArray): Boolean {
        val beginning = prefix.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        return Regex("^(?:<!doctype\\s+html|<html\\b|<head\\b|<body\\b|<form\\b)", RegexOption.IGNORE_CASE).containsMatchIn(beginning) ||
            beginning.contains("id=\"login_frm\"", true) || beginning.contains("id='login_frm'", true)
    }
}

object DownloadFileNames {
    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        .takeIf { it.matches(Regex("(?=[a-z0-9]*[a-z])[a-z0-9]{1,8}")) }.orEmpty()

    fun fromUrl(url: String): String = runCatching {
        val last = URI(url).rawPath.orEmpty().substringAfterLast('/')
        val decoded = URLDecoder.decode(last.replace("+", "%2B"), "UTF-8")
        if (extension(decoded).isEmpty()) "Univer-document" else safe(decoded)
    }.getOrDefault("Univer-document")
    fun fromDisposition(header: String?): String? {
        if (header == null) return null
        // RFC 5987 filename* takes priority over the often-ASCII filename fallback.
        val encoded = Regex("(?:^|;)\\s*filename\\*\\s*=\\s*\"?([^;\"]+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
        encoded?.let {
            val parts = it.trim().split('\'', limit = 3)
            if (parts.size == 3)
                runCatching { URLDecoder.decode(parts[2].replace("+", "%2B"), Charset.forName(parts[0]).name()) }.getOrNull()
                    ?.takeIf { name -> name.isNotBlank() }?.let { name -> return safe(name) }
        }
        return Regex("(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))", RegexOption.IGNORE_CASE).find(header)
            ?.let { it.groupValues[1].ifEmpty { it.groupValues[2].trim() } }?.takeIf { it.isNotBlank() }?.let { safe(decodeLegacy(it)) }
    }

    private fun decodeLegacy(value: String): String {
        val word = Regex("^=\\?([^?]+)\\?([bqBQ])\\?([^?]*)\\?=$").matchEntire(value)
        if (word != null) runCatching {
            val bytes = if (word.groupValues[2].equals("b", true)) Base64.decode(word.groupValues[3]) else {
                val text = word.groupValues[3].replace('_', ' ')
                val result = ByteArrayOutputStream()
                var i = 0
                while (i < text.length) {
                    if (text[i] == '=' && i + 2 < text.length) { result.write(text.substring(i + 1, i + 3).toInt(16)); i += 3 }
                    else { result.write(text[i].code); i++ }
                }
                result.toByteArray()
            }
            String(bytes, Charset.forName(word.groupValues[1]))
        }.getOrNull()?.let { return it }
        var text = value
        repeat(2) {
            if (text.any { it.code > 255 } || text.none { it in "ÐÑÃÂ" }) return text
            text = runCatching {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(text.toByteArray(Charsets.ISO_8859_1))).toString()
            }.getOrNull() ?: return text
        }
        return text
    }

    fun safe(name: String): String {
        val cleaned = name.replace(Regex("[\\p{Cc}\\p{Cf}/\\\\:*?\"<>|]"), "_").trim(' ', '.')
        val extension = extension(cleaned).takeIf { it.isNotEmpty() }
        val base = if (extension == null) cleaned else cleaned.substringBeforeLast('.')
        var bounded = base.take(100)
        // File systems limit names in bytes. CJK/emoji can exceed that limit at 100 characters.
        while (bounded.toByteArray(Charsets.UTF_8).size > 200 || bounded.lastOrNull()?.isHighSurrogate() == true)
            bounded = bounded.dropLast(1)
        val result = bounded.ifBlank { "Univer-document" } + (extension?.let { ".$it" } ?: "")
        return if (Regex("^(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(?:\\.|$)", RegexOption.IGNORE_CASE).containsMatchIn(result)) "_$result" else result
    }

    fun unique(name: String, suffix: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.take(dot) + "-$suffix" + name.substring(dot) else "$name-$suffix"
    }
}
