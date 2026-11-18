package com.univerflow.kaznu

import com.univerflow.kaznu.domain.DownloadHistoryRules
import com.univerflow.kaznu.domain.DownloadKeys
import com.univerflow.kaznu.domain.DownloadList
import com.univerflow.kaznu.domain.DownloadRecord
import com.univerflow.kaznu.domain.DownloadState
import com.univerflow.kaznu.domain.FileKind
import com.univerflow.kaznu.domain.FileKinds
import com.univerflow.kaznu.domain.TextSearch
import com.univerflow.kaznu.preview.TextExtract
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Repeated downloads, the course filter and the search inside downloaded files. */
class DownloadSearchTest {
    private fun record(id: String, source: String = "", key: String = "", state: DownloadState = DownloadState.COMPLETED,
                       uri: String? = "content://media/external/downloads/$id", finished: Long? = 1) =
        DownloadRecord(id = id, fileName = "$id.pdf", state = state, started = 1, finished = finished, source = source, key = key, uri = uri)

    @Test fun theSameFileGetsTheSameKeyWhateverTheDownloadSwitchOrParameterOrder() {
        val key = DownloadKeys.of("https://univer.kaznu.kz/student/umkd/get/1/2/2026/1")
        assertEquals(32, key.length)
        assertTrue(key.matches(Regex("[0-9a-f]{32}")))
        assertEquals(key, DownloadKeys.of("HTTPS://Univer.KazNU.kz/student/umkd/get/1/2/2026/1/"))
        assertNotEquals(key, DownloadKeys.of("https://univer.kaznu.kz/student/umkd/get/1/3/2026/1"))
        // Moodle: the same file opened inline or with ?forcedownload=1, parameters in any order.
        val moodle = DownloadKeys.of("https://dl.kaznu.kz/pluginfile.php/5/mod_resource/content/1/a.pdf?forcedownload=1&time=7")
        assertEquals(moodle, DownloadKeys.of("https://dl.kaznu.kz/pluginfile.php/5/mod_resource/content/1/a.pdf?time=7"))
        assertEquals(moodle, DownloadKeys.of("https://dl.kaznu.kz/pluginfile.php/5/mod_resource/content/1/a.pdf?time=7&forcedownload=0"))
        assertNotEquals(moodle, DownloadKeys.of("https://dl.kaznu.kz/pluginfile.php/5/mod_resource/content/1/a.pdf?time=8"))
        assertEquals("", DownloadKeys.of("not a url"))
        assertEquals("", DownloadKeys.of("relative/path"))
    }

    @Test fun aStaticDocumentOfUniverIgnoresTheVersionTheSiteAddsOnEveryRender() {
        // «Документы» link /Content/…pdf?v=<ticks>, and the ticks change on every load of the page (seen on 18 Nov 2026).
        val doc = "https://univer.kaznu.kz/Content/instructions/%D0%9F%D0%BE%D0%BB%D0%B8%D1%82%D0%B8%D0%BA%D0%B0-2026.pdf"
        assertEquals(DownloadKeys.of("$doc?v=639305994485158777"), DownloadKeys.of("$doc?v=639305994486748721"))
        assertEquals(DownloadKeys.of(doc), DownloadKeys.of("$doc?v=1#page=3"))
        assertEquals(DownloadKeys.of(doc), DownloadKeys.of("$doc/?v=2"))
        assertNotEquals(DownloadKeys.of(doc), DownloadKeys.of("https://univer.kaznu.kz/Content/instructions/other.pdf"))
        // Only the version goes: a document named by the rest of its query stays apart.
        assertNotEquals(DownloadKeys.of("https://univer.kaznu.kz/print/report.pdf?id=1&v=1"), DownloadKeys.of("https://univer.kaznu.kz/print/report.pdf?id=2&v=1"))
        assertEquals(DownloadKeys.of("https://univer.kaznu.kz/print/report.pdf?id=1&v=1"), DownloadKeys.of("https://univer.kaznu.kz/print/report.pdf?v=2&id=1"))
        // Other addresses keep their query: it names which file they serve.
        assertNotEquals(DownloadKeys.of("https://univer.kaznu.kz/student/files/get?id=1"), DownloadKeys.of("https://univer.kaznu.kz/student/files/get?id=2"))
        assertNotEquals(DownloadKeys.of("https://univer.kaznu.kz/student/umkd/get/1/2/2026/1?part=1"),
            DownloadKeys.of("https://univer.kaznu.kz/student/umkd/get/1/2/2026/1?part=2"))
        // Another host's file, or Univer over another port, is not the site's static document.
        assertNotEquals(DownloadKeys.of("https://dl.kaznu.kz/files/a.pdf?x=1"), DownloadKeys.of("https://dl.kaznu.kz/files/a.pdf?x=2"))
        assertNotEquals(DownloadKeys.of("https://univer.kaznu.kz:8443/Content/a.pdf?v=1"), DownloadKeys.of("https://univer.kaznu.kz:8443/Content/a.pdf?v=2"))
    }

    @Test fun theNewestFinishedCopyIsOfferedAndOtherStatesAreNot() {
        val key = DownloadKeys.of("https://univer.kaznu.kz/student/umkd/get/1/2/2026/1")
        val records = listOf(
            record("old", key = key, finished = 10),
            record("new", key = key, finished = 20),
            record("failed", key = key, state = DownloadState.FAILED, finished = 30),
            record("running", key = key, state = DownloadState.RUNNING, finished = null),
            record("lost", key = key, uri = null, finished = 40),
            record("other", key = DownloadKeys.of("https://univer.kaznu.kz/student/umkd/get/9/9/2026/1"), finished = 50),
            record("unkeyed", finished = 60))
        assertEquals("new", DownloadHistoryRules.previous(records, key)?.id)
        assertNull("Records from before keys existed never match", DownloadHistoryRules.previous(records, ""))
        assertNull(DownloadHistoryRules.previous(records.filter { it.id in setOf("failed", "running", "lost") }, key))
    }

    @Test fun coursesComeFromTheSavedPageHeading() {
        assertEquals("Психология", FileKinds.subject(record("a", source = "Психология")))
        assertEquals("Социология", FileKinds.subject(record("b", source = "Социология (Системы -6618): СРС 1. | СДО КазНУ")))
        assertEquals("История Казахстана", FileKinds.subject(record("c", source = "История   Казахстана: лекция")))
        assertEquals("", FileKinds.subject(record("d")))
        val records = listOf(record("1", source = "социология"), record("2", source = "Психология"), record("3", source = "Психология (x)"), record("4"))
        assertEquals(listOf("Психология", "социология"), FileKinds.subjects(records))
        assertEquals(listOf("2", "3"), records.filter { FileKinds.matchesSubject(it, "Психология") }.map { it.id })
        assertEquals(4, records.count { FileKinds.matchesSubject(it, null) })
    }

    @Test fun textSearchNeedsSomeWordAndShowsThatWordInContext() {
        val text = "Тема 1. Введение.\n\nЛекция посвящена   истории Казахского ханства и его учёным. " + "Дополнительно ".repeat(20) + "конец."
        val folded = TextSearch.fold(text)
        assertEquals(text.length, folded.length)
        assertTrue(TextSearch.matches(folded, "ХАНСТВА ученым"))
        assertTrue("One remembered word is enough", TextSearch.matches(folded, "ханства физика"))
        assertTrue(TextSearch.matches(folded, "физика ханства"))
        assertFalse("A blank query finds nothing inside files", TextSearch.matches(folded, "   "))
        assertFalse(TextSearch.matches(folded, "теорема"))
        val snippet = TextSearch.snippet(text, folded, "ханства ученым", radius = 30)!!
        assertEquals("ханства", snippet.text.substring(snippet.start, snippet.end))
        // The first word of the query is absent: the snippet still shows a word that is there.
        val second = TextSearch.snippet(text, folded, "физика ханства", radius = 30)!!
        assertEquals("ханства", second.text.substring(second.start, second.end))
        assertTrue(snippet.text.startsWith("…") && snippet.text.endsWith("…"))
        assertFalse("Runs of spaces and line breaks collapse", snippet.text.contains("  ") || snippet.text.contains('\n'))
        val head = TextSearch.snippet(text, folded, "тема")!!
        assertEquals(0, head.start)
        assertEquals("Тема", head.text.substring(head.start, head.end))
        assertNull(TextSearch.snippet(text, folded, "физика"))
    }

    @Test fun theListCombinesTypeCourseNameAndTextSearchAndKeepsItsOrder() {
        fun file(id: String, name: String, source: String, started: Long, size: Long) =
            DownloadRecord(id = id, fileName = name, state = DownloadState.COMPLETED, started = started, total = size, source = source, uri = "content://x/$id")
        val records = listOf(file("a", "Лекция 1.docx", "Психология", 3, 30), file("b", "Силлабус.pdf", "Социология", 2, 90),
            file("c", "Задания.docx", "Психология", 1, 10))
        val texts = mapOf("c" to DownloadList.FileText("Тема: учёба и память. Конец."), "a" to DownloadList.FileText("Лекция про память"))
        // Name and course only: nothing found inside files without the switch.
        assertEquals(emptyList<String>(), DownloadList.view(records, "память", null, null, null, DownloadList.Order.DATE).records.map { it.id })
        val inside = DownloadList.view(records, "ПАМЯТЬ", null, null, texts, DownloadList.Order.DATE)
        assertEquals(listOf("a", "c"), inside.records.map { it.id })
        assertEquals("память", inside.snippets.getValue("c").let { it.text.substring(it.start, it.end) })
        // A course chip and a type chip narrow the same search.
        assertEquals(listOf("c"), DownloadList.view(records, "учеба", FileKind.WORD, "Психология", texts, DownloadList.Order.DATE).records.map { it.id })
        assertEquals(listOf("b"), DownloadList.view(records, "", FileKind.PDF, null, null, DownloadList.Order.DATE).records.map { it.id })
        assertEquals(listOf("a", "b", "c"), DownloadList.view(records, "", null, null, null, DownloadList.Order.DATE).records.map { it.id })
        assertEquals(listOf("b", "a", "c"), DownloadList.view(records, "", null, null, null, DownloadList.Order.SIZE).records.map { it.id })
        assertEquals(listOf("c", "a", "b"), DownloadList.view(records, "", null, null, null, DownloadList.Order.NAME).records.map { it.id })
    }

    /** The search reads a text only when it needs it, and the map of read texts cannot grow past its budget. */
    @Test fun textCacheDropsTheLeastRecentlyUsedTextsAndFoldsOnlyWhatItReads() {
        // Only the file count is in play here: the character budget is far above three short texts.
        val cache = DownloadList.TextCache(maxFiles = 2, maxCharacters = 10_000)
        val first = cache.put("a", DownloadList.FileText("a".repeat(400)))
        cache.put("b", DownloadList.FileText("b".repeat(400)))
        assertEquals(2, cache.size)
        assertEquals("The folded copy is built on demand", 400, first.folded.length)
        cache.get("a") // "b" is now the least recently used.
        cache.put("c", DownloadList.FileText("c".repeat(400)))
        assertEquals("Within the file limit", 2, cache.size)
        assertTrue("a stayed: it was used last", "a" in cache)
        assertFalse("b left: it was the least recently used", "b" in cache)

        // The character budget alone evicts too, even when the count would allow more files.
        val budget = DownloadList.TextCache(maxFiles = 10, maxCharacters = 1_000)
        budget.put("x", DownloadList.FileText("x".repeat(400)))
        budget.put("y", DownloadList.FileText("y".repeat(400)))
        budget.put("z", DownloadList.FileText("z".repeat(400)))
        assertEquals("The budget wins over the count", 2, budget.size)
        assertTrue("z" in budget && "y" in budget && "x" !in budget)

        // A single text larger than the whole budget is still returned, but the cache does not keep it.
        val oversized = DownloadList.TextCache(maxFiles = 10, maxCharacters = 100)
        assertEquals("The caller still gets its text", 1_000, oversized.put("big", DownloadList.FileText("b".repeat(1_000))).text.length)
        assertEquals(0, oversized.size)

        cache.remove("a")
        assertEquals(1, cache.size)
        cache.clear()
        assertEquals(0, cache.size)
    }

    private fun zip(vararg parts: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> parts.forEach { (name, body) -> zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry() } }
        return output.toByteArray()
    }

    @Test fun officeTextIsReadFromWordSlidesInOrderAndSharedStrings() {
        val word = zip("word/document.xml" to """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
            <w:p><w:r><w:t>Силлабус</w:t></w:r></w:p><w:p><w:r><w:t>Неделя</w:t></w:r><w:r><w:tab/><w:t xml:space="preserve"> 1 &amp; 2</w:t></w:r></w:p></w:body></w:document>""",
            "word/styles.xml" to """<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:t>не текст</w:t></w:styles>""")
        assertEquals("Силлабус\nНеделя  1 & 2", TextExtract.text(word, "a.docx"))
        val slide = { text: String -> """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:sld>""" }
        val slides = zip("ppt/slides/slide10.xml" to slide("десятый"), "ppt/slides/slide2.xml" to slide("второй"), "ppt/slides/slide1.xml" to slide("первый"),
            "ppt/notesSlides/notesSlide2.xml" to slide("заметка ко второму"))
        assertEquals("первый\nвторой\nзаметка ко второму\nдесятый", TextExtract.text(slides, "a.pptx"))
        val sheet = zip("xl/sharedStrings.xml" to """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>Фамилия</t></si><si><r><t>Оцен</t></r><r><t>ка</t></r></si></sst>""")
        assertEquals("Фамилия\nОценка", TextExtract.text(sheet, "a.xlsx"))
        // The name does not decide: a .docx renamed to .doc is still read as one.
        assertEquals("Силлабус\nНеделя  1 & 2", TextExtract.text(word, "renamed.doc"))
        assertTrue(TextExtract.searchable("Лекция.PDF") && TextExtract.searchable("old.doc") && !TextExtract.searchable("photo.jpg"))
    }

    @Test fun aDocumentTypeDeclarationIsRefused() {
        val hostile = zip("word/document.xml" to """<?xml version="1.0"?><!DOCTYPE w [<!ENTITY x SYSTEM "file:///etc/hosts">]>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>""")
        val result = runCatching { TextExtract.text(hostile, "a.docx") }
        assertTrue("No external file content: $result", result.isFailure || !result.getOrThrow().contains("localhost"))
    }

    @Test fun aLibraryLargerThanTheTextCacheIsReadOncePerQuery() {
        val library = (1..5).map { DownloadRecord("id$it", fileName = "file$it.txt", started = it.toLong()) }
        val cache = DownloadList.TextCache(maxFiles = 2, maxCharacters = 1_000)
        val scan = DownloadList.TextScan()
        var reads = 0
        while (true) {
            val round = scan.pending("query", library, cache)
            if (round.isEmpty()) break
            round.forEach { record -> reads++; cache.put(record.id, DownloadList.FileText("text")); scan.read(record.id) }
            assertTrue("The scan must end", reads <= library.size)
        }
        assertEquals(library.size, reads)
        assertEquals("A new query reads what the cache no longer holds", 3, scan.pending("other", library, cache).size)
    }

    // Review finding: three matching textbooks overflowed the cache, the first one left it, and since the scan had read
    // it for this query it was never read again, so a file found by its text silently left the results.
    @Test fun aTextMatchStaysInTheResultsAfterTheCacheDropsItsText() {
        val books = (1..3).map { DownloadRecord("book$it", fileName = "Учебник $it.pdf", state = DownloadState.COMPLETED, started = it.toLong()) }
        val cache = DownloadList.TextCache(maxFiles = 2, maxCharacters = 10_000)
        val scan = DownloadList.TextScan()
        scan.pending("энтропия", books, cache).forEach { record ->
            val entry = cache.put(record.id, DownloadList.FileText("Глава о том, что такое энтропия."))
            scan.read(record.id)
            if (TextSearch.matches(entry.folded, "энтропия")) scan.found(record.id, TextSearch.snippet(entry.text, entry.folded, "энтропия")!!)
        }
        assertFalse("The first book's text left the cache", "book1" in cache)
        assertEquals("Nothing is read again for the same query", emptyList<DownloadRecord>(), scan.pending("энтропия", books, cache))
        val view = DownloadList.view(books, "энтропия", null, null, cache.snapshot(), DownloadList.Order.NAME, scan.found)
        assertEquals(listOf("book1", "book2", "book3"), view.records.map { it.id })
        assertEquals("энтропия", view.snippets.getValue("book1").let { it.text.substring(it.start, it.end) })
        // A new query starts over: what the last one found is not carried into it.
        scan.pending("другое", books, cache)
        assertTrue(scan.found.isEmpty())
        scan.found("book2", TextSearch.Snippet("x", 0, 1)); scan.forget("book2")
        assertTrue("A file removed from the list is forgotten", scan.found.isEmpty())
    }

    // Review finding: the chips counted only name and course matches, so they read "PDF 0" over a card found by its text.
    @Test fun chipCountsIncludeFilesFoundByTheirText() {
        val files = listOf(DownloadRecord("p", fileName = "Лекция 3.pdf", started = 1), DownloadRecord("w", fileName = "Задание.docx", started = 2))
        val texts = mapOf("p" to DownloadList.FileText("Термодинамика и энтропия"))
        assertEquals(mapOf(FileKind.PDF to 1), DownloadList.kindCounts(files, "энтропия", null, texts))
        assertEquals("Without the inside-files search only names count", emptyMap<FileKind, Int>(), DownloadList.kindCounts(files, "энтропия", null))
        val dropped = mapOf("p" to TextSearch.Snippet("энтропия", 0, 8))
        assertEquals("A match whose text left the cache still counts", mapOf(FileKind.PDF to 1),
            DownloadList.kindCounts(files, "энтропия", null, emptyMap(), dropped))
        assertEquals(mapOf(FileKind.WORD to 1), DownloadList.kindCounts(files, "задание", null, texts))
    }

    @Test fun aFullCacheKeepsMatchesAndLetsATextThatFoundNothingGo() {
        val cache = DownloadList.TextCache(maxFiles = 2, maxCharacters = 1_000)
        cache.put("match", DownloadList.FileText("found"))
        cache.put("miss", DownloadList.FileText("nothing"))
        cache.yieldTo("miss")
        assertFalse("miss" in cache)
        assertTrue("match" in cache)
        cache.yieldTo("match")
        assertTrue("A cache with room keeps what it read", "match" in cache)
    }

    @Test fun aServerNameWithRunsOfSpacesReadsAsOneLineAndIsStillFoundByItsWords() {
        // Observed on /student/umkd: "методические рекомендации  СРО 1.docx", "Н. Силлабус  2025-2026 Психология.docx".
        assertEquals("методические рекомендации СРО 1.docx", FileKinds.title(" методические рекомендации  СРО 1.docx\t"))
        assertEquals("Н. Силлабус 2025-2026 Психология.docx", FileKinds.title("Н. Силлабус  2025-2026 Психология.docx"))
        val record = DownloadRecord("a", fileName = "Н. Силлабус  2025-2026 Психология.docx", started = 0)
        assertTrue(FileKinds.matchesQuery(record, "силлабус  психология"))
        assertFalse(FileKinds.matchesQuery(record, "силлабус  2027"))
    }

    @Test fun textAndArchivesAreCountedUnderTheOtherChipThatShowsThem() {
        val files = listOf(DownloadRecord("t", fileName = "notes.txt", started = 0), DownloadRecord("z", fileName = "lab.zip", started = 0),
            DownloadRecord("p", fileName = "lecture.pdf", started = 0))
        assertEquals(mapOf(FileKind.OTHER to 2, FileKind.PDF to 1), DownloadList.kindCounts(files, "", null))
        assertEquals(listOf("t", "z"), files.filter { FileKinds.matchesFilter(it, FileKind.OTHER) }.map { it.id })
        assertEquals(FileKind.OTHER, FileKinds.filterKind(FileKind.ARCHIVE))
        assertEquals(FileKind.WORD, FileKinds.filterKind(FileKind.WORD))
    }

    @Test fun filesGroupByWholeDaysInTheStudentsZone() {
        val zone = java.util.TimeZone.getTimeZone("GMT+05:00")
        val now = java.util.GregorianCalendar(zone).apply { set(2026, 10, 6, 0, 30, 0) }.timeInMillis
        val hour = 3_600_000L
        // Half an hour into the day: an hour ago is already yesterday.
        assertEquals(DownloadList.Period.TODAY, DownloadList.period(now - 10 * 60_000L, now, zone))
        assertEquals(DownloadList.Period.YESTERDAY, DownloadList.period(now - hour, now, zone))
        assertEquals(DownloadList.Period.WEEK, DownloadList.period(now - 6 * 24 * hour, now, zone))
        assertEquals(DownloadList.Period.MONTH, DownloadList.period(now - 7 * 24 * hour, now, zone))
        assertEquals(DownloadList.Period.MONTH, DownloadList.period(now - 30 * 24 * hour, now, zone))
        assertEquals(DownloadList.Period.EARLIER, DownloadList.period(now - 31 * 24 * hour, now, zone))
        assertEquals("A file from the future counts as today", DownloadList.Period.TODAY, DownloadList.period(now + hour, now, zone))
    }
}
