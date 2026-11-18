package com.univerflow.kaznu

import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.DownloadFailure
import com.univerflow.kaznu.domain.DownloadHistoryRules
import com.univerflow.kaznu.domain.DownloadRecord
import com.univerflow.kaznu.domain.DownloadState
import com.univerflow.kaznu.domain.FileKind
import com.univerflow.kaznu.domain.FileKinds
import com.univerflow.kaznu.domain.TextZoom
import org.junit.Assert.*
import org.junit.Test

class DownloadsTest {
    private fun record(name: String, source: String = "", state: DownloadState = DownloadState.COMPLETED, started: Long = 1, id: String = name) =
        DownloadRecord(id = id, fileName = name, state = state, started = started, source = source)

    @Test fun aFileSavedBeforeKeysIsFoundByNameAndSizeOrByNameAndPage() {
        fun saved(name: String, size: Long, source: String = "Психология", key: String = "", finished: Long = 5) =
            DownloadRecord(id = "$name/$size/$key/$finished", fileName = name, uri = "content://media/1", state = DownloadState.COMPLETED,
                total = size, received = size, started = 1, finished = finished, source = source, key = key)
        val syllabus = saved("Силлабус.docx", 40_255)
        val records = listOf(syllabus, saved("Лекция 1.docx", 900, source = "Культурология"),
            saved("Силлабус.docx", 40_255, key = "0123456789abcdef0123456789abcdef", finished = 9))
        assertEquals(syllabus, DownloadHistoryRules.unkeyed(records, "Силлабус.docx", 40_255, "Другое"))
        // The extension the type check corrected after saving still names the same file.
        assertEquals(syllabus, DownloadHistoryRules.unkeyed(records, "Силлабус.doc", 40_255, ""))
        assertNull(DownloadHistoryRules.unkeyed(records, "Силлабус.docx", 41_000, "Психология"))
        assertNull("the same size alone is not the same file", DownloadHistoryRules.unkeyed(records, "Программа.docx", 40_255, "Психология"))
        // Without a size, a lecture of the same name from another course is a different file.
        assertNull(DownloadHistoryRules.unkeyed(records, "Лекция 1.docx", -1, "Психология"))
        assertNotNull(DownloadHistoryRules.unkeyed(records, "Лекция 1.docx", -1, "Культурология"))
        // Without a size, another type of the same name on the same page, or a page without a heading, is not it either.
        assertNull(DownloadHistoryRules.unkeyed(records, "Лекция 1.pdf", -1, "Культурология"))
        assertNull(DownloadHistoryRules.unkeyed(listOf(records.first { it.fileName == "Лекция 1.docx" }.copy(source = "")), "Лекция 1.docx", -1, ""))
        assertNull(DownloadHistoryRules.unkeyed(listOf(syllabus.copy(state = DownloadState.FAILED)), "Силлабус.docx", 40_255, ""))
        assertNull(DownloadHistoryRules.unkeyed(records, "", 40_255, ""))
    }

    @Test fun kindsFollowTheWindowsGroupsAndFallBackToMime() {
        assertEquals(FileKind.PDF, FileKinds.of("Лекция 5.PDF"))
        assertEquals(FileKind.WORD, FileKinds.of("method.docx"))
        assertEquals(FileKind.EXCEL, FileKinds.of("grades.csv"))
        assertEquals(FileKind.POWERPOINT, FileKinds.of("slides.ppsx"))
        listOf("xltx", "xltm").forEach { assertEquals(FileKind.EXCEL, FileKinds.of("template.$it")) }
        listOf("potx", "potm", "ppsm").forEach { assertEquals(FileKind.POWERPOINT, FileKinds.of("template.$it")) }
        assertEquals(FileKind.IMAGE, FileKinds.of("scan.jpeg"))
        assertEquals(FileKind.ARCHIVE, FileKinds.of("work.7z"))
        assertEquals(FileKind.PDF, FileKinds.of("server-name", "application/pdf; charset=binary"))
        assertEquals(FileKind.OTHER, FileKinds.of("", null))
        assertEquals("FILE", FileKinds.badge(FileKind.OTHER))
    }

    @Test fun searchMatchesEveryWordInNameOrCourseIgnoringCaseAndYo() {
        val item = record("Силлабус ВИБ.pdf", source = "Введение в информационную безопасность")
        assertTrue(FileKinds.matchesQuery(item, "  силлабус   безопасность "))
        assertTrue(FileKinds.matches(item, "ВВЕДЕНИЕ", FileKind.PDF))
        assertFalse(FileKinds.matches(item, "введение", FileKind.WORD))
        assertFalse(FileKinds.matchesQuery(item, "силлабус физика"))
        assertTrue(FileKinds.matchesQuery(record("Учёба.txt"), "учеба"))
        assertTrue(FileKinds.matchesQuery(record("Учеба.txt"), "учёба"))
    }

    @Test fun otherFilterAlsoCoversTextAndArchives() {
        assertTrue(FileKinds.matchesFilter(record("notes.txt"), FileKind.OTHER))
        assertTrue(FileKinds.matchesFilter(record("labs.zip"), FileKind.OTHER))
        assertTrue(FileKinds.matchesFilter(record("data.bin"), FileKind.OTHER))
        assertFalse(FileKinds.matchesFilter(record("lecture.pdf"), FileKind.OTHER))
        assertTrue(FileKinds.matchesFilter(record("lecture.pdf"), null))
    }

    @Test fun sizesUseAppLanguageUnits() {
        assertEquals("93 Б", FileKinds.size(93, AppLanguage.RUSSIAN))
        assertEquals("1,0 КБ", FileKinds.size(1024, AppLanguage.RUSSIAN))
        assertEquals("632 KB", FileKinds.size(647_168, AppLanguage.ENGLISH))
        assertEquals("1.1 MB", FileKinds.size(1_153_434, AppLanguage.ENGLISH))
        assertEquals("", FileKinds.size(-1, AppLanguage.KAZAKH))
    }

    @Test fun restoredHistoryMarksInterruptedTransfersAndKeepsNewestThousand() {
        val saved = (1..1005).map { record("f$it.pdf", started = it.toLong(), id = "id$it") } +
            record("running.pdf", state = DownloadState.RUNNING, started = 2000, id = "running") + record("dup.pdf", started = 3, id = "id1000")
        val restored = DownloadHistoryRules.restore(saved, now = 5000)
        assertEquals(DownloadHistoryRules.LIMIT, restored.size)
        assertEquals("running", restored.first().id)
        assertEquals(DownloadState.FAILED, restored.first().state)
        assertEquals(DownloadFailure.INTERRUPTED, restored.first().failure)
        assertEquals(5000L, restored.first().finished)
        assertEquals(listOf(1000L), restored.filter { it.id == "id1000" }.map { it.started })
        assertTrue(restored.zipWithNext().all { (a, b) -> a.started >= b.started })
    }

    @Test fun upsertReplacesInPlaceAndAddsNewFirst() {
        val list = listOf(record("a.pdf", id = "a"), record("b.pdf", id = "b"))
        val updated = DownloadHistoryRules.upsert(list, record("b.pdf", id = "b").copy(received = 10))
        assertEquals(listOf("a", "b"), updated.map { it.id })
        assertEquals(10, updated[1].received)
        assertEquals("c", DownloadHistoryRules.upsert(list, record("c.pdf", id = "c")).first().id)
    }

    @Test fun progressIsBoundedAndUnknownWithoutLength() {
        assertEquals(37, record("x.pdf").copy(received = 37, total = 100).progress)
        assertEquals(100, record("x.pdf").copy(received = 150, total = 100).progress)
        assertNull(record("x.pdf").copy(received = 10, total = -1).progress)
    }

    @Test fun textZoomSnapsToTenPercentStepsWithinBounds() {
        assertEquals(100, TextZoom.clamp(100))
        assertEquals(130, TextZoom.clamp(127))
        assertEquals(60, TextZoom.clamp(10))
        assertEquals(160, TextZoom.clamp(400))
    }
}
