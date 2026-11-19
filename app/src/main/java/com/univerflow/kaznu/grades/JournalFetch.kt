package com.univerflow.kaznu.grades

import android.webkit.CookieManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/** What one background request found: the journal's HTML, a session that is over, or nothing to go on. */
sealed interface JournalPage {
    class Found(val html: String) : JournalPage
    data object SignedOut : JournalPage
    data object Failed : JournalPage
}

/**
 * The background check's only request: a GET of the current journal with the main WebView's cookies, as opening the
 * page would send. No redirect is followed (one means the session is over) and the response is read up to [MAX_BYTES];
 * a renewed session cookie on a 200 is kept, as a browser would. Never on the main thread.
 */
object JournalFetch {
    const val ADDRESS = "https://univer.kaznu.kz/student/attendance/full/"
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val READ_NANOS = 60_000_000_000L

    /** [current]: false once a sign-out came after the request began, so its renewed cookie must not revive the session. */
    fun get(userAgent: String, cookies: CookieManager = CookieManager.getInstance(), current: () -> Boolean = { true }): JournalPage {
        val cookie = cookies.getCookie(ADDRESS)?.takeIf { it.isNotBlank() } ?: return JournalPage.SignedOut
        val connection = URL(ADDRESS).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "text/html")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cookie", cookie)
            val status = connection.responseCode
            when {
                status in 300..399 || status == 213 || status == 401 || status == 403 -> JournalPage.SignedOut
                status != 200 || connection.contentType?.startsWith("text/html", ignoreCase = true) != true -> JournalPage.Failed
                else -> {
                    val html = read(connection, encoding(connection.contentType))
                    val renewed = connection.headerFields.filterKeys { it?.equals("Set-Cookie", ignoreCase = true) == true }.values.flatten()
                    if (renewed.isNotEmpty() && current()) { renewed.forEach { cookies.setCookie(ADDRESS, it) }; cookies.flush() }
                    if (html == null) JournalPage.Failed
                    else if (html.contains("id=\"login_frm\"") || html.contains("id='login_frm'")) JournalPage.SignedOut
                    else JournalPage.Found(html)
                }
            }
        } catch (_: IOException) {
            JournalPage.Failed
        } catch (_: SecurityException) {
            JournalPage.Failed
        } finally {
            connection.disconnect()
        }
    }

    private fun encoding(contentType: String?): Charset = contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()
        ?.takeIf { it.isNotEmpty() }?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8

    private fun read(connection: HttpURLConnection, charset: Charset): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        // The read timeout counts per read: a page trickling in byte by byte still ends here.
        val until = System.nanoTime() + READ_NANOS
        connection.inputStream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > MAX_BYTES || System.nanoTime() > until) return null
                out.write(buffer, 0, count)
            }
        }
        return out.toByteArray().toString(charset)
    }
}
