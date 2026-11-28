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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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

    /** Back on the screen past midnight with the same records and filters: the date groups are drawn again for the new day. */
    @Test fun theRenderKeyChangesAtLocalMidnightSoDateGroupsAreRedrawn() {
        val zone = java.util.TimeZone.getTimeZone("Asia/Almaty")
        val calendar = java.util.Calendar.getInstance(zone).apply { clear(); set(2026, java.util.Calendar.NOVEMBER, 24, 23, 59) }
        val beforeMidnight = calendar.timeInMillis
        val afterMidnight = beforeMidnight + 2 * 60_000L
        val laterThatNight = beforeMidnight + 60_000L - 1
        val records = listOf(DownloadRecord("a", fileName = "a.pdf", started = beforeMidnight - 3_600_000L))
        fun key(now: Long) = DownloadList.RenderKey(records, "", null, null, false, 0, 0L, DownloadList.day(now, zone))
        assertEquals("Within one day the cached list is reused", key(beforeMidnight), key(laterThatNight))
        assertNotEquals("A new local day draws the list again", key(beforeMidnight), key(afterMidnight))
        // The groups the redraw shows really are different.
        assertEquals(DownloadList.Period.TODAY, DownloadList.period(records[0].started, beforeMidnight, zone))
        assertEquals(DownloadList.Period.YESTERDAY, DownloadList.period(records[0].started, afterMidnight, zone))
    }

    /** Whatever sequence of reads, re-reads, removals and oversized texts, the kept texts stay within both limits. */
    @Test fun textCacheStaysWithinItsCharacterBudgetAfterEveryEviction() {
        val random = java.util.Random(7)
        val cache = DownloadList.TextCache(maxFiles = 5, maxCharacters = 1_000)
        repeat(5_000) {
            val id = "id" + random.nextInt(12)
            when (random.nextInt(6)) {
                0 -> cache.remove(id)
                1 -> cache.yieldTo(id)
                2 -> cache.get(id)
                else -> cache.put(id, DownloadList.FileText("Ёß".repeat(random.nextInt(650))))
            }
            val kept = cache.snapshot().values.sumOf { it.text.length }
            assertTrue("Characters kept: $kept", kept <= 1_000)
            assertTrue("Files kept: ${cache.size}", cache.size <= 5)
            // The folded copy is the same length as the text, so the budget also bounds both strings together.
            cache.snapshot().values.forEach { assertEquals(it.text.length, it.folded.length) }
        }
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
        val scan = DownloadList.TextScan("query")
        var reads = 0
        while (true) {
            val round = scan.pending(library, cache)
            if (round.isEmpty()) break
            round.forEach { record ->
                reads++
                scan.read(record.id, cache.put(record.id, DownloadList.FileText("text")))
            }
            assertTrue("The scan must end", reads <= library.size)
        }
        assertEquals(library.size, reads)
        assertEquals("A new query reads what the cache no longer holds", 3, DownloadList.TextScan("other").pending(library, cache).size)
    }

    // Review finding: three matching textbooks overflowed the cache, the first one left it, and since the scan had read
    // it for this query it was never read again, so a file found by its text silently left the results.
    @Test fun aTextMatchStaysInTheResultsAfterTheCacheDropsItsText() {
        val books = (1..3).map { DownloadRecord("book$it", fileName = "Учебник $it.pdf", state = DownloadState.COMPLETED, started = it.toLong()) }
        val cache = DownloadList.TextCache(maxFiles = 2, maxCharacters = 10_000)
        val scan = DownloadList.TextScan("энтропия")
        scan.pending(books, cache).forEach { record ->
            val entry = cache.put(record.id, DownloadList.FileText("Глава о том, что такое энтропия."))
            assertTrue(scan.read(record.id, entry))
        }
        assertFalse("The first book's text left the cache", "book1" in cache)
        assertEquals("Nothing is read again for the same query", emptyList<DownloadRecord>(), scan.pending(books, cache))
        val view = DownloadList.view(books, "энтропия", null, null, cache.snapshot(), DownloadList.Order.NAME, scan)
        assertEquals(listOf("book1", "book2", "book3"), view.records.map { it.id })
        assertEquals("энтропия", view.snippets.getValue("book1").let { it.text.substring(it.start, it.end) })
        // A new query starts over: what the last one found is not carried into it.
        val next = DownloadList.TextScan("другое")
        assertTrue(next.found("другое").isEmpty())
        next.read("book2", DownloadList.FileText("другое")); next.forget("book2")
        assertTrue("A file removed from the list is forgotten", next.found("другое").isEmpty())
    }

    @Test fun changingTheQueryImmediatelyDropsOldTextMatchesAndTheirCounts() {
        val files = listOf(record("a"), record("b"), record("c").copy(fileName = "gravity.pdf"))
        val old = DownloadList.TextScan("entropy")
        val cache = DownloadList.TextCache(maxFiles = 1)
        old.read("a", cache.put("a", DownloadList.FileText("entropy")))
        cache.put("b", DownloadList.FileText("gravity"))
        assertFalse("The old match is retained only by its scan", "a" in cache)
        assertEquals(listOf("a"), DownloadList.view(files, "entropy", null, null, cache.snapshot(), DownloadList.Order.DATE, old).records.map { it.id })

        fun currentResults() = DownloadList.view(files, "gravity", null, null, cache.snapshot(), DownloadList.Order.DATE, old)
        assertEquals(listOf("b", "c"), currentResults().records.map { it.id })
        // A late result from the old scan must not supply a snippet for a new name match.
        old.read("c", DownloadList.FileText("entropy"))
        val current = currentResults()
        assertEquals(listOf("b", "c"), current.records.map { it.id })
        assertEquals(setOf("b"), current.snippets.keys)
        assertEquals(mapOf(FileKind.PDF to 2), DownloadList.kindCounts(files, "gravity", null, cache.snapshot(), old))
        assertEquals(listOf("a", "c"), DownloadList.TextScan("gravity").pending(files, cache).map { it.id })
    }

    // Review finding: the chips counted only name and course matches, so they read "PDF 0" over a card found by its text.
    @Test fun chipCountsIncludeFilesFoundByTheirText() {
        val files = listOf(DownloadRecord("p", fileName = "Лекция 3.pdf", started = 1), DownloadRecord("w", fileName = "Задание.docx", started = 2))
        val texts = mapOf("p" to DownloadList.FileText("Термодинамика и энтропия"))
        assertEquals(mapOf(FileKind.PDF to 1), DownloadList.kindCounts(files, "энтропия", null, texts))
        assertEquals("Without the inside-files search only names count", emptyMap<FileKind, Int>(), DownloadList.kindCounts(files, "энтропия", null))
        val scan = DownloadList.TextScan("энтропия").apply { read("p", DownloadList.FileText("энтропия")) }
        assertEquals("A match whose text left the cache still counts", mapOf(FileKind.PDF to 1),
            DownloadList.kindCounts(files, "энтропия", null, emptyMap(), scan))
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

    @Test fun dateOrderKeepsTodayTogetherWhenAnEarlierDownloadFinishesToday() {
        val zone = java.util.TimeZone.getTimeZone("UTC")
        val now = java.util.GregorianCalendar(zone).apply { set(2026, 10, 6, 12, 0, 0) }.timeInMillis
        val hour = 3_600_000L
        val running = record("running", state = DownloadState.RUNNING, finished = null).copy(started = now - hour)
        val yesterday = record("yesterday", finished = now - 23 * hour).copy(started = now - 24 * hour)
        val overnight = record("overnight", finished = now - 2 * hour).copy(started = now - 30 * hour)

        val sorted = DownloadList.view(listOf(yesterday, overnight, running), "", null, null, null, DownloadList.Order.DATE).records
        assertEquals(listOf(running, overnight, yesterday), sorted)
        assertEquals(listOf(DownloadList.Period.TODAY, DownloadList.Period.TODAY, DownloadList.Period.YESTERDAY),
            sorted.map { DownloadList.period(it.displayTime, now, zone) })
    }

    @Test fun precomputedSearchKeyIsBuiltOnceAndPresentMatchesViewAndCountsOffMainThread() = runBlocking {
        val a = DownloadRecord("a", fileName = "Учёба Лекция 1.pdf", source = "Психология (курс)", started = 3, total = 30, state = DownloadState.COMPLETED, uri = "content://x/a")
        val b = DownloadRecord("b", fileName = "Задание.docx", source = "Социология", started = 2, total = 90, state = DownloadState.COMPLETED, uri = "content://x/b")
        val c = DownloadRecord("c", fileName = "Отчёт.pdf", source = "Физика", started = 1, total = 10, state = DownloadState.COMPLETED, uri = "content://x/c")
        assertSame("searchKey is cached per record instance", a.searchKey, a.searchKey)
        assertEquals("учеба лекция 1.pdf психология (курс)", a.searchKey)
        assertTrue(FileKinds.matchesQuery(a, "УЧЕБА психология"))
        assertFalse(FileKinds.matchesQuery(a, "учеба социология"))

        val cache = DownloadList.TextCache()
        val textB = cache.put("b", DownloadList.FileText("Глава про учёбу и память."))
        val textC = cache.put("c", DownloadList.FileText("Механика и динамика."))
        val scan = DownloadList.TextScan("учеб")
        val records = listOf(a, b, c)
        val snapshotTexts = cache.copy()
        val snapshotScan = scan.snapshot("учеб")

        // Filtering runs safely off the main thread on immutable snapshots while cache/scan mutate on the caller thread.
        val job = async(Dispatchers.Default) {
            DownloadList.present(records, "учеб", FileKind.WORD, null, snapshotTexts, DownloadList.Order.DATE, snapshotScan)
        }
        cache.put("d", DownloadList.FileText("Ещё один текст"))
        val presented = job.await()
        scan.remember(presented.checked)

        val expectedView = DownloadList.view(records, "учеб", FileKind.WORD, null, snapshotTexts, DownloadList.Order.DATE, scan)
        val expectedCounts = DownloadList.kindCounts(records, "учеб", null, snapshotTexts, scan)
        assertEquals(expectedView.records, presented.view.records)
        assertEquals(expectedView.snippets, presented.view.snippets)
        assertEquals(expectedCounts, presented.counts)
        assertEquals(mapOf(FileKind.PDF to 1, FileKind.WORD to 1), presented.counts)
        assertEquals(setOf("b", "c"), presented.checked.keys)
        assertEquals("учёб", presented.checked.getValue("b").second?.let { it.text.substring(it.start, it.end) })
        assertNull(presented.checked.getValue("c").second)

        // A second pass with the same FileText references reuses the remembered results without re-checking them.
        val second = DownloadList.present(records, "учеб", null, null, snapshotTexts, DownloadList.Order.DATE, scan.snapshot("учеб"))
        assertTrue("unchanged cached texts are not scanned again", second.checked.isEmpty())
        assertEquals(listOf("a", "b"), second.view.records.map { it.id })
        assertSame(textB, snapshotTexts["b"])
        assertSame(textC, snapshotTexts["c"])
    }

    @Test fun evictedFileTextsAreNotRetainedByTextScanWhileEvictedMatchesRemainVisible() {
        val books = listOf(
            record("m1"), // match evaluated via scan.read, then evicted
            record("x1"), // miss evaluated via present + remember, then evicted
            record("m2"), // match evaluated via present + remember, then evicted
            record("k1"), // stays in cache to evict earlier entries
            record("k2"),
        )
        val cache = DownloadList.TextCache(maxFiles = 2, maxCharacters = 10_000)
        val scan = DownloadList.TextScan("энтропия")

        fun putAndRead(id: String, body: String): java.lang.ref.WeakReference<DownloadList.FileText> {
            val entry = cache.put(id, DownloadList.FileText(body))
            scan.read(id, entry)
            return java.lang.ref.WeakReference(entry)
        }
        fun putAndPresent(id: String, body: String): java.lang.ref.WeakReference<DownloadList.FileText> {
            val entry = cache.put(id, DownloadList.FileText(body))
            val presented = DownloadList.present(books, "энтропия", null, null, cache.copy(), DownloadList.Order.DATE, scan.snapshot("энтропия"))
            scan.remember(presented.checked)
            return java.lang.ref.WeakReference(entry)
        }

        val evictedReadMatch = putAndRead("m1", "Глава 1: энтропия и тепло " + "a".repeat(200))
        val evictedPresentMiss = putAndPresent("x1", "Механика без совпадений " + "b".repeat(200))
        val evictedPresentMatch = putAndPresent("m2", "Глава 2: энтропия и порядок " + "c".repeat(200))
        // Two newer reads overflow maxFiles = 2, evicting m1, x1 and m2 from TextCache.
        putAndRead("k1", "Нейтральный текст 1")
        putAndRead("k2", "Нейтральный текст 2")

        assertFalse("m1" in cache)
        assertFalse("x1" in cache)
        assertFalse("m2" in cache)

        // Evicted FileText instances (both hits and misses) must be reclaimable while TextScan stays alive.
        val refs = listOf(evictedReadMatch, evictedPresentMiss, evictedPresentMatch)
        for (attempt in 0 until 60) {
            if (refs.all { it.get() == null }) break
            System.gc()
            System.runFinalization()
            Thread.sleep(15)
        }
        assertNull("evicted match FileText from read() must not be pinned by TextScan", evictedReadMatch.get())
        assertNull("evicted miss FileText must not be pinned by TextScan", evictedPresentMiss.get())
        assertNull("evicted match FileText from present()/remember() must not be pinned by TextScan", evictedPresentMatch.get())

        // Evicted matches stay visible with their snippets in view(), present() and kindCounts(); evicted misses do not.
        val view = DownloadList.view(books, "энтропия", null, null, cache.snapshot(), DownloadList.Order.NAME, scan)
        assertEquals(listOf("m1", "m2"), view.records.map { it.id })
        assertEquals("энтропия", view.snippets.getValue("m1").let { it.text.substring(it.start, it.end) })
        assertEquals("энтропия", view.snippets.getValue("m2").let { it.text.substring(it.start, it.end) })

        val presented = DownloadList.present(books, "энтропия", null, null, cache.copy(), DownloadList.Order.NAME, scan.snapshot("энтропия"))
        assertEquals(listOf("m1", "m2"), presented.view.records.map { it.id })
        assertEquals(view.snippets, presented.view.snippets)
        assertEquals(mapOf(FileKind.PDF to 2), presented.counts)
        assertEquals(mapOf(FileKind.PDF to 2), DownloadList.kindCounts(books, "энтропия", null, cache.snapshot(), scan))
        assertTrue("evicted files are not re-queued for the same query", scan.pending(books, cache).isEmpty())

        // A fresh FileText instance for the same id gets a new token and is re-evaluated rather than returning a stale result.
        cache.put("x1", DownloadList.FileText("Обновлённый текст: энтропия"))
        val updated = DownloadList.present(books, "энтропия", null, null, cache.copy(), DownloadList.Order.NAME, scan.snapshot("энтропия"))
        scan.remember(updated.checked)
        assertEquals(listOf("m1", "m2", "x1"), updated.view.records.map { it.id })
        assertEquals(setOf("x1"), updated.checked.keys)
    }
}
