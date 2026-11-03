package com.univerflow.kaznu

import com.univerflow.kaznu.web.DownloadFileNames
import com.univerflow.kaznu.web.DownloadNeedsLogin
import com.univerflow.kaznu.web.DownloadAccessDenied
import com.univerflow.kaznu.web.DownloadProbe
import com.univerflow.kaznu.web.SitePolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/** [DownloadProbe.open] suspends because a `Set-Cookie` may have to reach the main thread; the tests simply await it. */
class DownloadProbeTest {
    private val url = "https://univer.kaznu.kz/student/umkd/get/3321866/99281/2026/1"
    private class Response(url: String, private val status: Int, private val headers: Map<String, String>,
        private val body: ByteArray = "%PDF-1.7 fixture".toByteArray()) : HttpURLConnection(URL(url)) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String?) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        override fun getHeaderFields(): Map<String, List<String>> = headers.mapValues { listOf(it.value) }
        override fun getContentType() = getHeaderField("Content-Type")
        override fun getInputStream() = ByteArrayInputStream(body)
    }

    @Test fun moodleFileUsesMoodleCookiesAndItsSignInPageMeansLogin() = runBlocking {
        val file = "https://dl.kaznu.edu.kz/pluginfile.php/12/mod_assign/intro/task.pdf"
        val ok = Response(file, 200, mapOf("Content-Type" to "application/pdf"))
        var asked: String? = null
        DownloadProbe { ok }.open(file, "agent", "https://dl.kaznu.edu.kz/mod/assign/view.php?id=1", { asked = it; "MoodleSession=x" }).close()
        assertEquals(file, asked)
        assertEquals("https://dl.kaznu.edu.kz/mod/assign/view.php?id=1", ok.getRequestProperty("Referer"))
        val expired = Response(file, 303, mapOf("Location" to "/login/index.php"))
        assertThrows(DownloadNeedsLogin::class.java) { runBlockingAwait { DownloadProbe { expired }.open(file, "agent", null) } }
        val away = Response(file, 302, mapOf("Location" to "https://univer.kaznu.kz/student/umkd/"))
        assertThrows(IllegalArgumentException::class.java) {
            runBlockingAwait { DownloadProbe { away }.open(file, "agent", null, { "MoodleSession=x" }) }
        }
        val foreignReferer = Response(file, 200, mapOf("Content-Type" to "application/pdf"))
        DownloadProbe { foreignReferer }.open(file, "agent", SitePolicy.HOME).close()
        assertNull("A Univer address is not sent to Moodle", foreignReferer.getRequestProperty("Referer"))
    }
    @Test fun extensionlessMaterialRouteIsRecognizedOnlyOnExactOrigin() {
        assertTrue(SitePolicy.documentDownload(url))
        assertFalse(SitePolicy.documentDownload(url.replace("kaznu.kz", "kaznu.kz.evil.test")))
        assertFalse(SitePolicy.documentDownload(url.replace("https:", "http:")))
        assertFalse(SitePolicy.documentDownload("https://univer.kaznu.kz/student/umkd/"))
    }
    @Test fun documentUsesSessionHeadersAndServerMetadata() = runBlocking {
        val response = Response(url, 200, mapOf("Content-Type" to "application/pdf; charset=binary",
            "Content-Disposition" to "attachment; filename=course.pdf"))
        val opened = DownloadProbe { response }.open(url, "fixture-agent", SitePolicy.HOME, { "fixture=session" })
        val result = opened.metadata
        assertEquals("application/pdf", result.mime)
        assertEquals("course.pdf", DownloadFileNames.fromDisposition(result.disposition))
        assertEquals("fixture=session", response.getRequestProperty("Cookie"))
        assertEquals("fixture-agent", response.getRequestProperty("User-Agent"))
        assertFalse(response.instanceFollowRedirects)
        assertFalse(response.closed)
        val saved = ByteArrayOutputStream()
        opened.use { it.saveTo(saved) }
        assertEquals("%PDF-1.7 fixture", saved.toString("UTF-8"))
        assertTrue(response.closed)
    }
    @Test fun expiredSessionHtmlWithStatus213CannotBecomeADownload() = runBlocking {
        val response = Response(url, 213, mapOf("Content-Type" to "text/html"))
        assertThrows(DownloadNeedsLogin::class.java) { runBlockingAwait { DownloadProbe { response }.open(url, "agent", null) } }
        assertTrue(response.closed)
    }
    @Test fun forbiddenFileDoesNotTriggerAnAuthenticationLoop() = runBlocking {
        val response = Response(url, 403, mapOf("Content-Type" to "text/html"))
        assertThrows(DownloadAccessDenied::class.java) { runBlockingAwait { DownloadProbe { response }.open(url, "agent", null) } }
        assertTrue(response.closed)
    }
    @Test fun loginRedirectIsNotFetchedAsAFile() = runBlocking {
        var connections = 0
        val probe = DownloadProbe { connections++; Response(it, 302, mapOf("Location" to "/user/login?ReturnUrl=x")) }
        assertThrows(DownloadNeedsLogin::class.java) { runBlockingAwait { probe.open(url, "agent", null) } }
        assertEquals(1, connections)
    }
    @Test fun externalRedirectNeverReceivesCookies() = runBlocking {
        var connections = 0
        val probe = DownloadProbe { connections++; Response(it, 302, mapOf("Location" to "https://external.test/file")) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlockingAwait { probe.open(url, "agent", null, { "fixture=session" }) }
        }
        assertEquals(1, connections)
    }
    @Test fun utf8FilenameAndDuplicateNamesKeepTheirExtensions() {
        val name = DownloadFileNames.fromDisposition("attachment; filename=course.pdf; filename*=UTF-8''%D0%9A%D1%83%D1%80%D1%81%2B1.pdf")
        assertEquals("Курс+1.pdf", name)
        assertEquals("Курс+1-abc123.pdf", DownloadFileNames.unique(name!!, "abc123"))
        assertFalse(DownloadFileNames.safe("../../bad\r\nfile.pdf").contains('/'))
        assertTrue(DownloadFileNames.safe("x".repeat(200) + ".pdf").endsWith(".pdf"))
    }
    @Test fun status213IsRejectedEvenIfServerCallsItAnAttachment() = runBlocking {
        val response = Response(url, 213, mapOf("Content-Type" to "application/pdf", "Content-Disposition" to "attachment; filename=fake.pdf"))
        assertThrows(DownloadNeedsLogin::class.java) { runBlockingAwait { DownloadProbe { response }.open(url, "agent", null) } }
        assertTrue(response.closed)
    }
    @Test fun mislabeledHtmlCannotBeSavedAsPdf() = runBlocking {
        val response = Response(url, 200, mapOf("Content-Type" to "application/octet-stream"), "\uFEFF <!DOCTYPE html><html><form id='login_frm'>".toByteArray())
        assertThrows(DownloadNeedsLogin::class.java) { runBlockingAwait { DownloadProbe { response }.open(url, "agent", null) } }
        assertTrue(response.closed)
    }
    @Test fun redirectUsesNewCookieAndPathScopedCookieInsteadOfOriginalHeader() = runBlocking {
        val destination = SitePolicy.ORIGIN + "/files/material.pdf"
        val first = Response(url, 302, mapOf("Location" to destination, "Set-Cookie" to "fixture=new; Path=/"))
        val last = Response(destination, 200, mapOf("Content-Type" to "application/pdf"))
        var updated = false
        var requests = 0
        val probe = DownloadProbe { requests++; if (it == url) first else last }
        probe.open(url, "agent", SitePolicy.HOME, { if (updated && it == destination) "fixture=new" else "fixture=old" },
            { _, value -> updated = value == "fixture=new; Path=/" }).use { it.saveTo(ByteArrayOutputStream()) }
        assertEquals(2, requests)
        assertEquals("fixture=new", last.getRequestProperty("Cookie"))
        assertTrue(first.closed && last.closed)
    }
    @Test fun aTruncatedOrEmptyResponseIsNotSuccessful() = runBlocking {
        listOf(Response(url, 200, mapOf("Content-Length" to "100")), Response(url, 200, emptyMap(), byteArrayOf())).forEach { response ->
            assertThrows(IOException::class.java) {
                runBlockingAwait { DownloadProbe { response }.open(url, "agent", null).use { it.saveTo(ByteArrayOutputStream()) } }
            }
            assertTrue(response.closed)
        }
    }
    @Test fun prefixInspectionPreservesAllBytesOfLargeBinaryFileInOneRequest() = runBlocking {
        val bytes = ByteArray(190_000) { (it % 251).toByte() }
        val response = Response(url, 200, mapOf("Content-Length" to bytes.size.toString()), bytes)
        var requests = 0
        val saved = ByteArrayOutputStream()
        DownloadProbe { requests++; response }.open(url, "agent", null).use { it.saveTo(saved) }
        assertEquals(1, requests)
        assertArrayEquals(bytes, saved.toByteArray())
    }
    @Test fun redirectLoopsAndPartialHttpResponsesAreRejected(): Unit = runBlocking {
        var requests = 0
        assertThrows(IllegalStateException::class.java) {
            runBlockingAwait { DownloadProbe { requests++; Response(it, 302, mapOf("Location" to url)) }.open(url, "agent", null) }
        }
        assertEquals(6, requests)
        assertThrows(IllegalStateException::class.java) {
            runBlockingAwait { DownloadProbe { Response(it, 206, emptyMap()) }.open(url, "agent", null) }
        }
    }
    @Test fun filenameDoesNotMatchAnotherParameterEndingInFilename() {
        assertEquals("actual.pdf", DownloadFileNames.fromDisposition("attachment; badfilename=wrong.pdf; filename=actual.pdf"))
    }
    @Test fun unicodeFilenameFitsFilesystemByteLimitAndKeepsExtension() {
        val name = DownloadFileNames.safe("课".repeat(150) + ".pdf")
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 220)
        assertTrue(name.endsWith(".pdf"))
        assertFalse(DownloadFileNames.safe("😀".repeat(60)).last().isHighSurrogate())
    }
}

/** Runs a suspending probe body so that `assertThrows` can capture what it throws. */
private fun runBlockingAwait(block: suspend () -> Unit) = runBlocking { block() }
