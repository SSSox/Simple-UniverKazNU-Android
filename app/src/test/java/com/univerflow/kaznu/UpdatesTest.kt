package com.univerflow.kaznu

import com.univerflow.kaznu.domain.Updates
import com.univerflow.kaznu.update.UpdateClient
import com.univerflow.kaznu.update.UpdatePackage
import com.univerflow.kaznu.update.attemptFile
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest

class UpdatesTest {
    private val repository = Updates.REPOSITORY

    private class Response(url: String, private val status: Int, private val headers: Map<String, String> = emptyMap(),
        private val body: ByteArray = ByteArray(0)) : HttpURLConnection(URL(url)) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String?) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        override fun getContentLengthLong() = headers["Content-Length"]?.toLong() ?: -1L
        override fun getInputStream() = ByteArrayInputStream(body)
    }

    private fun client(vararg responses: Pair<String, (String) -> HttpURLConnection>) = UpdateClient { url ->
        responses.first { url.startsWith(it.first) }.second(url)
    }

    @Test fun onlyAHigherVersionIsNewer() {
        assertTrue(Updates.newer("2.3.1", "2.3"))
        assertTrue(Updates.newer("2.10", "2.9"))
        assertTrue(Updates.newer("3", "2.9.9"))
        assertFalse(Updates.newer("2.3", "2.3.0"))
        assertFalse(Updates.newer("2.3", "2.3.1"))
        assertFalse(Updates.newer("2.3.1", "2.3.1"))
        assertFalse(Updates.newer("beta", "2.3"))
        assertFalse(Updates.newer("2.4", "unknown"))
    }

    @Test fun theVersionComesOnlyFromThisRepositorysReleaseTag() {
        assertEquals("2.3.1", Updates.version("$repository/releases/tag/v2.3.1"))
        assertNull("no release yet", Updates.version("$repository/releases"))
        assertNull(Updates.version("https://github.com/someone/else/releases/tag/v9.9"))
        assertNull(Updates.version("$repository/releases/tag/v2.3-beta"))
        assertNull(Updates.version(null))
    }

    @Test fun releaseFilesAreOnlyAcceptedFromGitHubOverHttps() {
        assertTrue(Updates.allowedHost(Updates.apkUrl("2.3.1")))
        assertTrue(Updates.allowedHost("https://release-assets.githubusercontent.com/file?sig=1"))
        assertFalse(Updates.allowedHost("http://github.com/x"))
        assertFalse(Updates.allowedHost("https://github.com.evil.test/x"))
        assertFalse(Updates.allowedHost("https://evilgithubusercontent.com/x"))
        assertFalse(Updates.allowedHost("https://user@github.com/x"))
        assertFalse(Updates.allowedHost("https://github.com:8443/x"))
    }

    @Test fun aChecksumFileGivesItsHash() {
        val hash = "AE8C2254353DCACBECD79AD7FC0F4146612E9835060EF2660B720D6E9DC6617F"
        assertEquals(hash.lowercase(), Updates.checksum("$hash  Simple-UniverKazNU.apk\n"))
        assertEquals(hash.lowercase(), Updates.checksum(hash))
        assertNull(Updates.checksum("not a hash"))
        assertNull(Updates.checksum(""))
    }

    @Test fun everyLaunchChecksAndALaterReturnChecksAfterHalfAnHour() {
        val checked = 1_000_000_000L
        assertTrue("a launch checks even right after the last check", Updates.due(checked + 1, checked, firstInRun = true))
        assertTrue("never checked", Updates.due(checked, 0, firstInRun = false))
        assertFalse(Updates.due(checked + Updates.CHECK_INTERVAL_MS - 1, checked, firstInRun = false))
        assertTrue(Updates.due(checked + Updates.CHECK_INTERVAL_MS, checked, firstInRun = false))
        assertTrue("clock moved back", Updates.due(checked - 1, checked, firstInRun = false))
    }

    @Test fun theDialogRespectsLaterAndDoNotShowAgainForThatVersionOnly() {
        assertTrue(Updates.prompt("2.4", null, answered = null))
        assertFalse("nothing to offer", Updates.prompt(null, null, answered = null))
        assertFalse("Later in this run", Updates.prompt("2.4", null, answered = "2.4"))
        assertTrue("a version found after Later in the same run is offered", Updates.prompt("2.5", null, answered = "2.4"))
        assertFalse("turned off for this version", Updates.prompt("2.4", "2.4", answered = null))
        assertTrue("a newer version is offered again", Updates.prompt("2.5", "2.4", answered = null))
    }

    @Test fun theLatestVersionIsReadFromTheRedirect() {
        val found = client(Updates.LATEST to { url -> Response(url, 302, mapOf("Location" to "$repository/releases/tag/v2.4")) })
        assertEquals("2.4", found.latestVersion())
        val none = client(Updates.LATEST to { url -> Response(url, 302, mapOf("Location" to "$repository/releases")) })
        assertNull(none.latestVersion())
        val broken = client(Updates.LATEST to { url -> Response(url, 500) })
        assertThrows(IOException::class.java) { broken.latestVersion() }
    }

    @Test fun aDownloadFollowsGitHubAndReturnsTheHashOfTheBytes() {
        val body = "apk bytes".toByteArray()
        val cdn = "https://release-assets.githubusercontent.com/file"
        val client = client(Updates.apkUrl("2.4") to { url -> Response(url, 302, mapOf("Location" to cdn)) },
            cdn to { url -> Response(url, 200, mapOf("Content-Length" to body.size.toString()), body) })
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Int>()
        val hash = client.download("2.4", output, {}) { progress += it }
        assertArrayEquals(body, output.toByteArray())
        assertEquals(MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }, hash)
        assertEquals(100, progress.last())
    }

    @Test fun aRedirectAwayFromGitHubIsRefusedBeforeItIsRequested() {
        var asked = false
        val client = UpdateClient { url ->
            if (url.startsWith("https://evil.test")) asked = true
            Response(url, 302, mapOf("Location" to "https://evil.test/app.apk"))
        }
        assertThrows(IOException::class.java) { client.download("2.4", ByteArrayOutputStream(), {}) { } }
        assertFalse(asked)
    }

    @Test fun aShortOrOversizedFileIsRefused() {
        val short = client(Updates.apkUrl("2.4") to { url -> Response(url, 200, mapOf("Content-Length" to "100"), "abc".toByteArray()) })
        assertThrows(IOException::class.java) { short.download("2.4", ByteArrayOutputStream(), {}) { } }
        val huge = client(Updates.apkUrl("2.4") to { url -> Response(url, 200, mapOf("Content-Length" to (Updates.MAX_APK_BYTES + 1).toString())) })
        assertThrows(IOException::class.java) { huge.download("2.4", ByteArrayOutputStream(), {}) { } }
        val missing = client(Updates.apkUrl("2.4") to { url -> Response(url, 404) })
        assertThrows(IOException::class.java) { missing.download("2.4", ByteArrayOutputStream(), {}) { } }
    }

    @Test fun theChecksumFileIsReadFromTheRelease() {
        val hash = "a".repeat(64)
        val client = client(Updates.checksumUrl("2.4") to { url -> Response(url, 200, body = "$hash  Simple-UniverKazNU.apk\n".toByteArray()) })
        assertEquals(hash, client.checksum("2.4"))
        val empty = client(Updates.checksumUrl("2.4") to { url -> Response(url, 200, body = "oops".toByteArray()) })
        assertThrows(IOException::class.java) { empty.checksum("2.4") }
    }

    @Test fun aRetryOfTheSameVersionDownloadsToItsOwnFile() {
        val cache = Files.createTempDirectory("update-test").toFile()
        try {
            val folder = File(cache, "updates")
            val cancelled = attemptFile(folder, "2.4")
            val retry = attemptFile(folder, "2.4")
            retry.writeText("apk bytes")
            assertNotEquals(cancelled, retry)
            // What the cancelled attempt does once its stalled read ends.
            cancelled.delete()
            assertEquals("apk bytes", retry.readText())
        } finally { cache.deleteRecursively() }
    }

    @Test fun aRotatedKeyStillUpdatesButAnotherKeyDoesNot() {
        assertTrue("same key", UpdatePackage.sameSigners(setOf("old"), listOf("old"), setOf("old")))
        assertTrue("v3 rotation old -> new", UpdatePackage.sameSigners(setOf("new"), listOf("old", "new"), setOf("old")))
        assertFalse("another key", UpdatePackage.sameSigners(setOf("other"), listOf("other"), setOf("old")))
        assertFalse("a lineage without the installed key", UpdatePackage.sameSigners(setOf("new"), listOf("older", "new"), setOf("old")))
        assertFalse("the old key does not replace a rotated install", UpdatePackage.sameSigners(setOf("old"), listOf("old"), setOf("new")))
        assertFalse("before Android 9 there is no lineage", UpdatePackage.sameSigners(setOf("new"), null, setOf("old")))
        assertFalse("unsigned", UpdatePackage.sameSigners(emptySet(), null, setOf("old")))
        assertTrue("several signers in any order", UpdatePackage.sameSigners(setOf("a", "b"), null, setOf("b", "a")))
        assertFalse("several signers must all match", UpdatePackage.sameSigners(setOf("a", "b"), null, setOf("a")))
    }
}
