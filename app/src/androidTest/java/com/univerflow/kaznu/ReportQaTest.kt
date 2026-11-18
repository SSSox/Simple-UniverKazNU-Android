package com.univerflow.kaznu

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.Band
import com.univerflow.kaznu.domain.GradeReport
import com.univerflow.kaznu.report.GradeReportDrawing
import com.univerflow.kaznu.report.GradeReportFiles
import com.univerflow.kaznu.ui.context
import com.univerflow.kaznu.web.ReportMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The grade report a parent reads: drawn from made-up courses into qa/report-*.png and .pdf to look at, every band's
 * colour on the image, courses never cut by a page, a long report as one picture a page shared together, and the files
 * gone after an hour and on logout.
 */
@RunWith(AndroidJUnit4::class)
class ReportQaTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val qa get() = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }

    private fun score(label: String, value: String, max: String = "100") = GradeReport.Score(label, max, value)
    private val demo = GradeReport(GradeReport.Term(2026, 1), "Демо Студент", listOf(
        GradeReport.Course("Социология", listOf(score("РК1", "63"), score("РК2", "48"))),
        GradeReport.Course("Психология", listOf(score("РК1", "92"), score("РК2", ""))),
        GradeReport.Course("Математический анализ и аналитическая геометрия, продвинутый курс для бакалавриата", listOf(score("РК1", "74,5"), score("РК2", "12,5", "25"))),
        GradeReport.Course("Физическая культура", emptyList()),
    ))

    private fun drawing(report: GradeReport, language: AppLanguage) =
        GradeReportDrawing(language.context(context), language, report, "18.11.2026")

    @Test fun theReportShowsEveryBandAndSavesAsAnImageAndA4Pages() {
        for (language in AppLanguage.entries) {
            val name = "report-" + language.siteCode
            val png = File(qa, "$name.png")
            assertEquals("four courses are one picture", listOf(png), drawing(demo, language).writePng(png))
            val image = BitmapFactory.decodeFile(png.path)
            assertEquals("twice the A4 width in points", 1190, image.width)
            assertTrue("cut below its last course, not a whole A4 sheet: ${image.height}", image.height < 1684)
            val colours = HashSet<Int>()
            for (y in 0 until image.height step 2) for (x in 0 until image.width step 2) colours += image.getPixel(x, y)
            Band.entries.forEach { assertTrue("$language shows the ${it.name} colour", GradeReportDrawing.colour(it) in colours) }
            image.recycle()

            val pdf = File(qa, "$name.pdf")
            assertEquals("four courses fit one A4 page", 1, drawing(demo, language).writePdf(pdf))
            // Two more run onto a second page, which turns between control periods.
            assertEquals(2, drawing(demo.copy(courses = demo.courses + demo.courses.take(2)), language).writePdf(File(qa, "$name-2.pdf")))
            assertEquals("%PDF", pdf.readBytes().copyOf(4).toString(Charsets.US_ASCII))
        }
    }

    @Test fun aLongReportTurnsTheSamePagesAsAPdfAndAsPictures() {
        val course = GradeReport.Course("Длинное название предмета ".repeat(6).trim(), List(GradeReport.MAX_SCORES) { score("РК${it + 1}", "${40 + it * 7}") })
        val long = GradeReport(GradeReport.Term(2026, 2), "Демо Студент", List(GradeReport.MAX_COURSES) { course })
        val drawing = drawing(long, AppLanguage.RUSSIAN)
        drawing.blocks.forEach { assertTrue("a course fits on one page", it.height <= GradeReportDrawing.PAGE_HEIGHT - 80f) }
        val pages = drawing.writePdf(File(qa, "report-long.pdf"))
        assertTrue("$pages pages", pages >= GradeReport.MAX_COURSES / 2)
        assertTrue("a course going on over a page break is named again on the new page", drawing.carriedOver > 0)
        // Where the app makes them (the share sheet reads only the reports folder); a copy goes to qa/ to look at.
        val pictures = drawing.writePng(GradeReportFiles.create(context, "report-long", "png"))
        val look = File(qa, "report-long").apply { deleteRecursively(); mkdirs() }
        pictures.forEach { it.copyTo(File(look, it.name)) }
        assertEquals("one picture a page", pages, pictures.size)
        assertEquals((1..pages).map { "report-long-$it.png" }, pictures.map { it.name })
        assertTrue("a course going on over a picture's edge is named again too", drawing.carriedOver > 0)
        pictures.forEach { picture ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(picture.path, bounds)
            assertEquals("${picture.name}: the text keeps its size", 1190, bounds.outWidth)
            assertTrue("${picture.name}: no taller than an A4 page", bounds.outHeight in 1..1684)
        }
        // Every page goes to the share sheet in order, readable by the chosen app only.
        val chooser = GradeReportFiles.share(context, pictures, "image/png", "Успеваемость")
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        @Suppress("DEPRECATION") val streams = send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(pictures.map { GradeReportFiles.uri(context, it) }, streams)
        assertEquals(pages, send.clipData!!.itemCount)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        // One file stays a plain single share.
        @Suppress("DEPRECATION") val single = GradeReportFiles.share(context, pictures.take(1), "image/png", "Успеваемость")
            .getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, single.action)
        pictures.first().parentFile!!.deleteRecursively()
        // A report cancelled between pages (logout) draws nothing further.
        val stopped = GradeReportFiles.create(context, "report-stopped", "png")
        var started = 0
        val stop = runCatching { drawing.writePng(stopped) { if (++started == 2) throw java.util.concurrent.CancellationException() } }
        assertTrue(stop.exceptionOrNull() is java.util.concurrent.CancellationException)
        assertEquals(listOf("report-stopped-1.png"), stopped.parentFile!!.list()!!.toList())
        stopped.parentFile!!.deleteRecursively()
    }

    @Test fun reportFilesStayPrivateForAnHourAndGoOnLogout() {
        GradeReportFiles.clear(context)
        val now = System.currentTimeMillis()
        val old = GradeReportFiles.create(context, "Успеваемость 2026-11-18", "pdf", now - 2 * 60 * 60 * 1000L).apply { writeText("old") }
        old.parentFile!!.setLastModified(now - 2 * 60 * 60 * 1000L)
        val fresh = GradeReportFiles.create(context, "Успеваемость: 2026/11/18", "png", now).apply { writeText("new") }
        assertEquals("Успеваемость 2026 11 18.png", fresh.name)
        assertTrue(fresh.canonicalPath.startsWith(context.cacheDir.canonicalPath))
        GradeReportFiles.prune(context, now)
        assertFalse("an hour-old report is removed", old.exists())
        assertTrue("a report being shared stays", fresh.exists())
        assertTrue(GradeReportFiles.uri(context, fresh).toString().startsWith("content://${context.packageName}.downloads/reports/"))
        GradeReportFiles.clear(context)
        assertFalse(GradeReportFiles.folder(context).exists())
    }

    @Test fun thePagesMessageBecomesAReportOnlyInItsOwnShape() {
        val prefix = GradeReport.MESSAGE_PREFIX
        val good = prefix + """{"v":1,"term":null,"student":"","courses":[{"name":"Социология","scores":[{"label":"РК1","max":"100","value":"63"}]}]}"""
        val report = ReportMessage.parse(good)
        assertNotNull(report); assertNull(report!!.term)
        assertEquals(GradeReport.Score("РК1", "100", "63"), report.courses.single().scores.single())
        assertNull(ReportMessage.parse(good.replace("\"63\"", "63")))
        assertNull(ReportMessage.parse(prefix + "[1]"))
        assertNull(ReportMessage.parse(prefix + "{\"v\":1"))
        assertNotNull(ReportMessage.parse(good.replace("{\"v\":1", "{\"x\":[[1]],\"v\":1")))
        assertNull("nesting deeper than a report voids it", ReportMessage.parse(good.replace("{\"v\":1", "{\"x\":" + "[".repeat(40) + "]".repeat(40) + ",\"v\":1")))
        assertNull(ReportMessage.parse(prefix + " ".repeat(GradeReport.MAX_LENGTH) + good.removePrefix(prefix)))
    }
}
