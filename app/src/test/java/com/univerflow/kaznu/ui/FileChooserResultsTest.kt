package com.univerflow.kaznu.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI

class FileChooserResultsTest {
    private val own = "com.univerflow.kaznu.downloads"

    private fun describe(raw: String): FileChooserResults.PickedUri {
        // Parses scheme and decoded authority the same way Android's Uri does for content/file URIs.
        val scheme = raw.substringBefore(':', missingDelimiterValue = "").ifEmpty { null }
        val authority = if (raw.contains("://")) {
            val after = raw.substringAfter("://")
            val rawAuth = after.substringBefore('/').substringBefore('?').substringBefore('#')
            if (rawAuth.isEmpty()) null else runCatching { URI("content://$rawAuth/").authority }.getOrDefault(rawAuth)?.ifEmpty { null }
        } else null
        return FileChooserResults.PickedUri(raw, scheme, authority)
    }

    private fun select(
        fromResult: List<String>,
        readFlag: Boolean,
        grantedUris: Set<String> = emptySet(),
        snapshot: String? = null,
        resultOk: Boolean = true,
        resolvesToSelf: (String) -> Boolean = { false },
    ): List<String> = FileChooserResults.select(
        resultOk = resultOk,
        fromResult = fromResult,
        readFlag = readFlag,
        snapshot = snapshot,
        ownAuthority = own,
        describe = ::describe,
        resolvesToSelf = resolvesToSelf,
        hasUriReadGrant = { it in grantedUris },
    )

    @Test fun rejectsOwnProviderUrisIncludingReportsDownloadsAndEarlierPhotos() {
        val ownUris = listOf(
            "content://$own/reports/1a2b/grades.pdf",
            "content://$own/camera/photo-earlier.jpg",
            "content://$own/downloads/syllabus.pdf",
            "content://0@$own/reports/1a2b/grades.pdf",
            "content://0%40$own/reports/1a2b/grades.pdf",
            "content://COM.UNIVERFLOW.KAZNU.DOWNLOADS/reports/1a2b/grades.pdf",
            "content://$own:443/reports/1a2b/grades.pdf",
            "content://$own./reports/1a2b/grades.pdf",
            "content://com.univerflow.kaznu.other/secret",
        )
        val selfCheck: (String) -> Boolean = { it.equals("com.univerflow.kaznu.other", ignoreCase = true) }
        val allGranted = ownUris.toSet()
        for (uri in ownUris) {
            assertEquals("rejected without grant: $uri", emptyList<String>(), select(listOf(uri), readFlag = false, grantedUris = allGranted, resolvesToSelf = selfCheck))
            assertEquals("rejected with flag and grant: $uri", emptyList<String>(), select(listOf(uri), readFlag = true, grantedUris = allGranted, resolvesToSelf = selfCheck))
        }
    }

    @Test fun allowsOnlyTheCurrentRequestsCameraSnapshotFromOwnProvider() {
        val snapshot = "content://$own/camera/photo-current.jpg"
        // Camera writing to EXTRA_OUTPUT with null/empty Intent data.
        assertEquals(listOf(snapshot), select(emptyList(), readFlag = false, snapshot = snapshot))
        // Camera echoing the EXTRA_OUTPUT URI back without FLAG_GRANT_READ_URI_PERMISSION or URI grant.
        assertEquals(listOf(snapshot), select(listOf(snapshot), readFlag = false, snapshot = snapshot))
        // Another own-provider URI during the same request is still rejected even with a flag and grant.
        val report = "content://$own/reports/1/grades.pdf"
        val userQualifiedSnapshot = "content://0@$own/camera/photo-current.jpg"
        val querySnapshot = "$snapshot?extra=1"
        val grants = setOf(report, userQualifiedSnapshot, querySnapshot)
        assertEquals(emptyList<String>(), select(listOf(report), readFlag = true, grantedUris = grants, snapshot = snapshot))
        assertEquals(emptyList<String>(), select(listOf(userQualifiedSnapshot), readFlag = true, grantedUris = grants, snapshot = snapshot))
        assertEquals(emptyList<String>(), select(listOf(querySnapshot), readFlag = true, grantedUris = grants, snapshot = snapshot))
        // Cancelled capture never returns the snapshot.
        assertEquals(emptyList<String>(), select(emptyList(), readFlag = false, snapshot = snapshot, resultOk = false))
    }

    @Test fun requiresBothResultFlagAndPerUriReadGrantForExternalUris() {
        val doc = "content://com.android.providers.downloads.documents/document/42"
        val workProfileDoc = "content://10@com.android.providers.media.documents/document/image%3A9"
        val granted = setOf(doc, workProfileDoc)
        // Both result flag and actual URI grant must be present.
        assertEquals(listOf(doc), select(listOf(doc), readFlag = true, grantedUris = granted))
        assertEquals(listOf(workProfileDoc), select(listOf(workProfileDoc), readFlag = true, grantedUris = granted))
        // Flag-only without an actual URI permission grant is rejected.
        assertEquals(emptyList<String>(), select(listOf(doc), readFlag = true, grantedUris = emptySet()))
        assertEquals(emptyList<String>(), select(listOf(workProfileDoc), readFlag = true, grantedUris = emptySet()))
        // URI permission grant without FLAG_GRANT_READ_URI_PERMISSION on the result Intent is rejected.
        assertEquals(emptyList<String>(), select(listOf(doc), readFlag = false, grantedUris = granted))
        assertEquals(emptyList<String>(), select(listOf(workProfileDoc), readFlag = false, grantedUris = granted))
        // Non-content schemes and malformed/missing authorities are rejected even with both flag and grant.
        val badUris = listOf(
            "file:///data/user/0/com.univerflow.kaznu/files/secret",
            "CONTENT://com.android.providers.downloads.documents/document/42",
            "https://example.org/file.pdf",
            "content:///data/local/tmp/x",
            "content:com.example.picker/doc",
            "content://0@/doc",
        )
        badUris.forEach { bad ->
            assertEquals("rejected: $bad", emptyList<String>(), select(listOf(bad), readFlag = true, grantedUris = setOf(bad)))
        }
    }

    @Test fun mixedClipDataChecksEachExternalUriIndividually() {
        val grantedA = "content://com.android.providers.downloads.documents/document/7"
        val grantedB = "content://com.android.providers.downloads.documents/document/8"
        val ungranted = "content://com.android.providers.downloads.documents/document/9"
        val report = "content://$own/reports/z/grades.pdf"
        val fileUri = "file:///sdcard/notes.txt"
        val snapshot = "content://$own/camera/photo-current.jpg"
        val grants = setOf(grantedA, grantedB, report, fileUri)
        assertEquals(
            listOf(grantedA, grantedB),
            select(listOf(grantedA, ungranted, report, fileUri, grantedB), readFlag = true, grantedUris = grants),
        )
        // Missing result flag drops even granted external URIs, while still honoring the exact current snapshot.
        assertEquals(
            emptyList<String>(),
            select(listOf(grantedA, ungranted, report), readFlag = false, grantedUris = grants),
        )
        assertEquals(
            listOf(snapshot),
            select(listOf(snapshot, grantedA, ungranted), readFlag = false, grantedUris = grants, snapshot = snapshot),
        )
    }
}
