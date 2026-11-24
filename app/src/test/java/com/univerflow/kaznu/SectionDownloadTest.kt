package com.univerflow.kaznu

import com.univerflow.kaznu.domain.FilePresence
import com.univerflow.kaznu.domain.FilePresenceRules
import com.univerflow.kaznu.web.DownloadBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
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

    // A page script may change the list or the headings after the real tap: the batch goes only when the open page lists
    // every one of its files under the very heading row the tap marked, by number and text, with as many headings as at
    // the tap. Two sections with the same (or the same first 200 characters of) text never vouch for each other's files,
    // and a heading count that differs between the tap and the lookup refuses the whole batch rather than filling another
    // section.
    private fun place(course: String, section: String, head: Int, tapped: Boolean = true) =
        JSONObject().put("course", course).put("section", section).put("head", head).put("tapped", tapped)
    private fun answer(count: Int, vararg places: Pair<String, JSONObject>) =
        JSONObject.quote(JSONObject().put("count", count).put("files", JSONObject().apply { places.forEach { (k, v) -> put(k, v) } }).toString())
    private fun tap(head: Int, count: Int = 2) = DownloadBatch.Tap(head, count, 5)

    @Test fun theBatchGoesOnlyWhenThePageListsEveryFileUnderTheTappedHeading() {
        val other = "https://univer.kaznu.kz/student/umkd/get/7001/999/2026/1"
        val missing = "https://univer.kaznu.kz/student/umkd/get/7002/999/2026/1"
        val long = "Раздел ".repeat(40)
        val name = DownloadBatch.heading(long)
        val firstPlace = first to place("Алгоритмы", long, 0)
        // The heading text as the page shows it, its spaces as the fold module collapsed them.
        val secondPlace = second to place("Алгоритмы", "  Раздел\n Раздел ".repeat(20), 1)
        val otherPlace = other to place("Алгоритмы", "Задания", 1)
        // As the channel's lookup answers through evaluateJavascript: a JSON string holding the JSON text.
        assertEquals(mapOf(first to DownloadBatch.Place("Алгоритмы", long)), DownloadBatch.places(answer(2, firstPlace), listOf(first), tap(0), name))
        assertEquals(setOf(second), DownloadBatch.places(answer(2, secondPlace), listOf(second), tap(1), name)!!.keys)
        // A file of another heading number (even with the same text), not on the page, or a heading with other text: nothing.
        val none = emptyMap<String, DownloadBatch.Place>()
        assertEquals(none, DownloadBatch.places(answer(2, firstPlace, secondPlace), listOf(first, second), tap(0), name))
        assertEquals(none, DownloadBatch.places(answer(2, firstPlace, secondPlace), listOf(first, second), tap(1), name))
        assertEquals(none, DownloadBatch.places(answer(2, secondPlace, otherPlace), listOf(second, other), tap(1), name))
        assertEquals(none, DownloadBatch.places(answer(2, secondPlace, otherPlace), listOf(second, other), tap(1), "Задания"))
        assertEquals(none, DownloadBatch.places(answer(3, firstPlace), listOf(first), tap(2, 3), name))
        assertEquals(none, DownloadBatch.places(answer(2, firstPlace), listOf(first, missing), tap(0), name))
        assertEquals(none, DownloadBatch.places(answer(2), listOf(first), tap(0), name))
        assertNull(DownloadBatch.places(answer(2, firstPlace), listOf(first), tap(-1), name))
        assertNull("a heading number beyond the count", DownloadBatch.places(answer(2, firstPlace), listOf(first), tap(2), name))
        assertNull("no mark", DownloadBatch.places(answer(2, firstPlace), listOf(first), DownloadBatch.Tap(0, 2, 0), name))
    }

    // The lookup counts the heading rows with the same built-ins as the tap did: when the counts disagree (a heading was
    // added or removed after the tap), the batch is refused even when the files sit under the tapped number and text.
    @Test fun headingCountsThatDisagreeRefuseTheBatch() {
        val raw = answer(3, first to place("Алгоритмы", "Лекции", 1), second to place("Алгоритмы", "Лекции", 1))
        assertEquals(emptyMap<String, DownloadBatch.Place>(), DownloadBatch.places(raw, listOf(first, second), tap(1, 2), "Лекции"))
        assertEquals(emptyMap<String, DownloadBatch.Place>(), DownloadBatch.places(raw, listOf(first, second), tap(1, 4), "Лекции"))
        assertEquals(setOf(first, second), DownloadBatch.places(raw, listOf(first, second), tap(1, 3), "Лекции")!!.keys)
        assertEquals(emptyMap<String, DownloadBatch.Place>(), DownloadBatch.places(raw, listOf(first, second), tap(0, 3), "Лекции"))
        // The number agrees but the heading there is another one: refused too.
        val shifted = answer(2, first to place("Алгоритмы", "Практика", 0), second to place("Алгоритмы", "Практика", 0))
        assertEquals(emptyMap<String, DownloadBatch.Place>(), DownloadBatch.places(shifted, listOf(first, second), tap(0), "Лекции"))
    }

    // The tapped «Лекции» heading (number 1 of 3) is removed and a heading added elsewhere; its namesake slides into number
    // 1, the count is 3 again, and the files asked for sit under the namesake: number, text and count all agree, but the
    // row is not the one the student tapped, so nothing is fetched.
    @Test fun aNamesakeThatInheritedTheTappedNumberIsNotTheTappedHeading() {
        val raw = answer(3, first to place("Алгоритмы", "Лекции", 1, tapped = false), second to place("Алгоритмы", "Лекции", 1, tapped = false))
        assertEquals(emptyMap<String, DownloadBatch.Place>(), DownloadBatch.places(raw, listOf(first, second), tap(1, 3), "Лекции"))
        val mixed = answer(3, first to place("Алгоритмы", "Лекции", 1), second to place("Алгоритмы", "Лекции", 1, tapped = false))
        assertEquals(emptyMap<String, DownloadBatch.Place>(), DownloadBatch.places(mixed, listOf(first, second), tap(1, 3), "Лекции"))
    }

    // Only the lookup's exact shape is an answer: a page that forged or padded it gets nothing fetched.
    @Test fun downloadAllAsksOnACoursePageOfAnyTermWithOrWithoutAQuery() {
        val page = "https://univer.kaznu.kz/student/umkd/321/2026/1"
        for (url in listOf(page, "$page/", "https://univer.kaznu.kz/student/umkd/321/2026/3", "$page?lang=ru", "$page/?a=1&b=2#files", "$page#top"))
            assertTrue(url, DownloadBatch.coursePage(url))
        for (url in listOf(null, "https://univer.kaznu.kz/student/umkd/", "https://univer.kaznu.kz/student/umkd/x1/2026/1",
            "https://univer.kaznu.kz/student/umkd/321/26/1", "https://univer.kaznu.kz/student/umkd/321/2026/1/2",
            "https://univer.kaznu.kz/student/umkd/321/2026/", "https://univer.kaznu.kz/student/attendance/321/2026/1",
            "http://univer.kaznu.kz/student/umkd/321/2026/1", "https://univer.kaznu.kz.example.org/student/umkd/321/2026/1",
            "https://univerXkaznu.kz/student/umkd/321/2026/1", "https://evil.example/?https://univer.kaznu.kz/student/umkd/321/2026/1"))
            assertFalse(url.toString(), DownloadBatch.coursePage(url))
    }

    @Test fun anAnswerOfAnotherShapeIsNoAnswer() {
        val asked = listOf(first)
        val good = place("Алгоритмы", "Лекции", 0)
        fun files(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }
        fun wrapped(top: JSONObject) = JSONObject.quote(top.toString())
        assertEquals(setOf(first), DownloadBatch.places(answer(2, first to good), asked, tap(0), "Лекции")!!.keys)
        listOf(
            null, "null", JSONObject().put("count", 2).put("files", files(first to good)).toString(), JSONObject.quote("[1]"),
            JSONObject.quote("not json"), JSONObject.quote("{}x"),
            JSONObject.quote(JSONObject().put("count", 2).put("files", files(first to good)).toString() + " {}"),
            // The places without the count, or the count and files with something else beside them or mistyped.
            wrapped(files(first to good)),
            wrapped(JSONObject().put("files", files(first to good))),
            wrapped(JSONObject().put("count", 2).put("files", files(first to good)).put("extra", 1)),
            wrapped(JSONObject().put("count", "2").put("files", files(first to good))),
            wrapped(JSONObject().put("count", 2.5).put("files", files(first to good))),
            wrapped(JSONObject().put("count", -1).put("files", files(first to good))),
            wrapped(JSONObject().put("count", 2).put("files", JSONArray().put(good))),
            // An address nobody asked about, or a place that is not an object.
            answer(2, first to good, second to good), wrapped(JSONObject().put("count", 2).put("files", files(first to "Лекции"))),
            // Missing, extra or mistyped fields.
            answer(2, first to JSONObject().put("section", "Лекции").put("head", 0).put("tapped", true)),
            answer(2, first to JSONObject(good.toString()).put("extra", 1)),
            answer(2, first to JSONObject(good.toString()).apply { remove("tapped") }),
            answer(2, first to place("Алгоритмы", "Лекции", 0).put("tapped", "true")),
            answer(2, first to place("Алгоритмы", "Лекции", 0).put("tapped", 1)),
            answer(2, first to place("Алгоритмы", "Лекции", 0).put("head", "0")),
            answer(2, first to place("Алгоритмы", "Лекции", 0).put("head", 0.5)),
            answer(2, first to place("Алгоритмы", "Лекции", 0).put("head", -2)),
            answer(2, first to place("Алгоритмы", "Лекции", 0).put("course", 1)),
            answer(2, first to place("Алгоритмы", "Лекции", 0).put("section", JSONObject.NULL)),
            answer(2, first to place("Алгоритмы", "x".repeat(5_000), 0)),
            // The same key twice.
            JSONObject.quote("{\"count\":2,\"files\":{\"$first\":" + good + ",\"$first\":" + good + "}}"),
            JSONObject.quote("{\"count\":2,\"count\":2,\"files\":{\"$first\":" + good + "}}"),
        ).forEach { assertNull(it.toString(), DownloadBatch.places(it, asked, tap(0), "Лекции")) }
    }

    @Test fun sectionMessageNeedsItsHeadingNumberCountAndMark() {
        assertEquals(DownloadBatch.Tap(3, 4, 9), DownloadBatch.of("Лекции", listOf(first), 3, 4, 9)!!.tap)
        listOf(null, -1, "1", 1.5, 10_000, 4).forEach { assertNull(it.toString(), DownloadBatch.of("Лекции", listOf(first), it, 4, 9)) }
        listOf(null, 0, 3, "4", 4.5, 10_001).forEach { assertNull(it.toString(), DownloadBatch.of("Лекции", listOf(first), 3, it, 9)) }
        listOf(null, 0, -1, "9", 9.5).forEach { assertNull(it.toString(), DownloadBatch.of("Лекции", listOf(first), 3, 4, it)) }
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

    @Test fun beforeAndroid10OnlyAFileOfTheDownloadedSizeIsDeletedUnderItsName() {
        assertTrue(FilePresenceRules.sameFile(5_120, 5_120))
        assertTrue("an empty download is still its own file", FilePresenceRules.sameFile(0, 0))
        assertFalse("saved again under that name in another app", FilePresenceRules.sameFile(5_120, 7_000))
        assertFalse(FilePresenceRules.sameFile(-1, 0))
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
