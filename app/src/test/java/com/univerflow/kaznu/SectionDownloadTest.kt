package com.univerflow.kaznu

import com.univerflow.kaznu.domain.FilePresence
import com.univerflow.kaznu.domain.FilePresenceRules
import com.univerflow.kaznu.web.DownloadBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.FileNotFoundException

/** «Скачать все» in a UMKD section, and the Downloads list dropping files that left their place. */
class SectionDownloadTest {
    private val first = "https://univer.kaznu.kz/student/umkd/get/9002/321/2026/1"
    private val second = "https://univer.kaznu.kz/student/umkd/get/9003/321/2026/1"

    @Test fun sectionMessageCarriesTheSectionsUmkdFilesOnceEach() {
        val batch = DownloadBatch.of(" Конспекты лекций ", listOf(first, second, first))!!
        assertEquals("Конспекты лекций", batch.section)
        assertEquals(listOf(first, second), batch.urls)
    }

    // The same file spelled another way is one file: a page script cannot make the app fetch it over and over.
    @Test fun sameFileSpelledAnotherWayCountsOnce() {
        val variants = listOf(first, "$first?x=1", "$first/", "https://univer.kaznu.kz/STUDENT/UMKD/GET/9002/321/2026/1",
            "https://univer.kaznu.kz/student/umkd/get/009002/0321/2026/01", second)
        assertEquals(listOf(first, second), DownloadBatch.of("Лекции", variants)!!.urls)
    }

    // Any script on the site can send the message: one address that is not a UMKD file of the site voids all of it.
    @Test fun anythingElseVoidsTheWholeMessage() {
        listOf(
            listOf(first, "https://univer.kaznu.kz/user/logout"),
            listOf("https://univer.kaznu.kz.example.org/student/umkd/get/1/2/2026/1"),
            listOf("http://univer.kaznu.kz/student/umkd/get/1/2/2026/1"),
            listOf("https://univer.kaznu.kz:8443/student/umkd/get/1/2/2026/1"),
            listOf("https://user@univer.kaznu.kz/student/umkd/get/1/2/2026/1"),
            listOf("https://example.org/lecture.pdf"),
            listOf(5),
            listOf(null),
            emptyList(),
        ).forEach { urls -> assertNull(urls.toString(), DownloadBatch.of("Лекции", urls)) }
        assertNull(DownloadBatch.of("  ", listOf(first)))
        assertNull(DownloadBatch.of(null, listOf(first)))
        assertNull(DownloadBatch.of("x".repeat(201), listOf(first)))
        assertNull(DownloadBatch.parse(null))
        assertNull(DownloadBatch.parse("univer-pin:{}"))
    }

    @Test fun sectionMessageHasALimit() {
        val urls = (1..DownloadBatch.LIMIT + 1).map { "https://univer.kaznu.kz/student/umkd/get/$it/321/2026/1" }
        assertNull(DownloadBatch.of("Лекции", urls))
        assertEquals(DownloadBatch.LIMIT, DownloadBatch.of("Лекции", urls.take(DownloadBatch.LIMIT))!!.urls.size)
    }

    @Test fun fileIsGoneWhenItsRowLeftOrItIsNoLongerWhereItWasSaved() {
        val saved = "Download/Univer УМКД/Социология/Лекции/Лекция 1.pdf"
        assertEquals(FilePresence.PRESENT, FilePresenceRules.ofRow(true, saved, saved))
        assertEquals(FilePresence.GONE, FilePresenceRules.ofRow(false, saved, null))
        assertEquals(FilePresence.GONE, FilePresenceRules.ofRow(true, saved, "Download/Лекция 1.pdf"), "moved")
        assertEquals(FilePresence.GONE, FilePresenceRules.ofRow(true, saved, "Download/Univer УМКД/Социология/Лекции/Лекция.pdf"), "renamed")
        // Saved before places were kept: the row alone answers, and its current place becomes the saved one.
        assertEquals(FilePresence.PRESENT, FilePresenceRules.ofRow(true, null, saved))
    }

    // Storage that does not answer must never empty the list. Android turns every failed open into FileNotFoundException
    // with the errno in its message: a revoked storage permission (EACCES) or a disk error (EIO) is no answer.
    @Test fun onlyNoSuchFileOnMountedStorageIsGone() {
        val noSuchFile = FileNotFoundException("open failed: ENOENT (No such file or directory)")
        assertEquals(FilePresence.GONE, FilePresenceRules.ofFailure(noSuchFile, mounted = true))
        assertEquals(FilePresence.UNKNOWN, FilePresenceRules.ofFailure(noSuchFile, mounted = false))
        assertEquals(FilePresence.UNKNOWN, FilePresenceRules.ofFailure(FileNotFoundException("open failed: EACCES (Permission denied)"), mounted = true))
        assertEquals(FilePresence.UNKNOWN, FilePresenceRules.ofFailure(FileNotFoundException("open failed: EIO (I/O error)"), mounted = true))
        assertEquals(FilePresence.UNKNOWN, FilePresenceRules.ofFailure(FileNotFoundException(), mounted = true))
        // Android 7 gives the reason without the errno name.
        assertEquals(FilePresence.GONE, FilePresenceRules.ofFailure(FileNotFoundException("No such file or directory"), mounted = true))
        assertEquals(FilePresence.UNKNOWN, FilePresenceRules.ofFailure(FileNotFoundException("Permission denied"), mounted = true))
        assertEquals(FilePresence.UNKNOWN, FilePresenceRules.ofFailure(SecurityException(), mounted = true))
        assertEquals(FilePresence.UNKNOWN, FilePresenceRules.ofFailure(IllegalStateException(), mounted = true))
    }

    private fun assertEquals(expected: FilePresence, actual: FilePresence, message: String) = assertEquals(message, expected, actual)
}
