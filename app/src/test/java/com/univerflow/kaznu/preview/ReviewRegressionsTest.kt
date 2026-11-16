package com.univerflow.kaznu.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Files built so that a reader's work grows with the square of their size, or past any limit. Each of them used to
 * take minutes (or all memory); a test fails by its timeout when that returns.
 */
class ReviewRegressionsTest {
    private val cell = SheetCell(CellValue.Number(1.0), CellStyle())

    @Test(timeout = 10_000) fun aNumberFormatWithEndlessDecimalPlacesIsAppliedAtOnce() {
        val code = "0." + "#".repeat(60_000)
        repeat(400) { assertEquals("1.5", CellFormat.format(CellValue.Number(1.5), code, false, Locale.ROOT)) }
        // Excel itself shows at most 30 decimal places.
        assertEquals("0." + "3".repeat(15), CellFormat.format(CellValue.Number(1.0 / 3), "0." + "0".repeat(15), false, Locale.ROOT))
    }

    @Test(timeout = 10_000) fun thousandsOfSmallTablesAreMeasuredWithinTheirOwnMarkup() {
        val small = "<div class=\"table\" role=\"region\" tabindex=\"0\"><table><tbody><tr><td>a</td></tr><tr><td>b</td></tr></tbody></table></div>"
        assertEquals(0, DocumentTables.find(small.repeat(150_000), "open").count)
        val large = "<div class=\"table\" role=\"region\" tabindex=\"0\"><table><tbody><tr><th>a</th><td>b</td></tr><tr><td>c</td><td>d</td></tr></tbody></table></div>"
        assertEquals(2, DocumentTables.find(small + large + small + large, "open").count)
    }

    @Test(timeout = 20_000) fun aWebPageOfUnclosedMarkupIsReadInOnePass() {
        for (piece in listOf("<!-- ", "<p ", "< ", "<script ", "<style>")) {
            val text = HtmlText.text(("<html><body>text " + piece.repeat(300_000)).toByteArray())
            assertTrue(piece, text.startsWith("text"))
        }
        // What does close is still dropped, and what does not stays as the page's text.
        assertEquals("a b\nc <!-- d", HtmlText.text("<html>a<!-- x --> b<script>var x = '<p>';</script><style>p{}</STYLE><p>c <!-- d".toByteArray()))
        assertEquals("a < b", HtmlText.text("<html><b>a</b> < b".toByteArray()))
    }

    @Test fun anRtfWithAnImpossibleByteCountOrCharacterStillGivesItsText() {
        fun text(rtf: String) = TextExtract.text(rtf.toByteArray(Charsets.ISO_8859_1), "a.rtf")
        assertEquals("before", text("{\\rtf1 before{\\*\\unknown\\bin2147483647 } after}"))
        assertEquals("ab", text("{\\rtf1 a\\u1114112?b}"))
    }

    @Test(timeout = 20_000) fun overlappingMergedRangesAreDrawnWithinTheSizeOfTheGrid() {
        val sheet = Sheet("s")
        for (row in 0 until 600) sheet.put(row, 0, cell)
        sheet.put(0, 999, cell)
        // No range holds the first cell of a later one, so none is skipped as covered.
        (0 until 20).flatMap { row -> (0 until 1000).map { column -> row to column } }.sortedByDescending { it.first + it.second }
            .forEach { (row, column) -> sheet.merge(CellRange(row, column, 599, 999)) }
        assertTrue(SheetHtml(PreviewLabels(Locale.ROOT)).render(Workbook(listOf(sheet))).contains("<table"))
    }

    @Test fun mergedRangesThatDoNotOverlapAreAllDrawn() {
        val sheet = Sheet("s")
        for (row in 0 until 4) for (column in 0 until 4) sheet.put(row, column, cell)
        sheet.merge(CellRange(0, 0, 1, 1)); sheet.merge(CellRange(0, 2, 1, 3)); sheet.merge(CellRange(2, 0, 3, 3))
        val html = SheetHtml(PreviewLabels(Locale.ROOT)).render(Workbook(listOf(sheet)))
        assertEquals(2, Regex("colspan=\"2\" rowspan=\"2\"").findAll(html).count())
        assertEquals(1, Regex("colspan=\"4\" rowspan=\"2\"").findAll(html).count())
    }

    @Test(timeout = 6_000) fun thousandsOfColumnRangesAreLookedUpOncePerColumn() {
        val sheet = Sheet("s")
        // The first range, found last, hides the column of every cell.
        repeat(Sheet.MAX_COLUMNS) { sheet.column(it, it, 10.0, hidden = it == 0) }
        for (row in 0 until 499_000) sheet.put(row, 0, cell)
        sheet.put(0, 1, cell)
        val text = SheetView.text(Workbook(listOf(sheet, sheet, sheet)))
        assertEquals(List(3) { "s\n1" }.joinToString("\n\n"), text)
    }

    private fun zip(vararg parts: Pair<String, ByteArray>, zeroMegabytes: Int = 0): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            parts.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
            if (zeroMegabytes > 0) {
                zip.putNextEntry(ZipEntry("docProps/thumbnail.bin"))
                val megabyte = ByteArray(1024 * 1024)
                repeat(zeroMegabytes) { zip.write(megabyte) }
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private val document = "word/document.xml" to ("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
        "<w:body><w:p><w:r><w:t>hello</w:t></w:r></w:p></w:body></w:document>").toByteArray()

    @Test(timeout = 60_000) fun aPartThePreviewPassesOverCanStillBeCancelled() {
        val file = zip(document, zeroMegabytes = 300)
        var checks = 0
        assertTrue(DocxHtml.convert(ByteArrayInputStream(file), { checks++ }, PreviewMedia.inline()).contains("hello"))
        // One check for every block inflated, not one for the whole part.
        assertTrue("$checks", checks > 1000)
        checks = 0
        assertEquals("hello", TextExtract.text(file, "a.docx") { checks++ })
        assertTrue("$checks", checks > 1000)
    }

    @Test(timeout = 120_000) fun partsPassedOverCountAgainstALimit() {
        val file = zip(document, zeroMegabytes = 1100)
        try { DocxHtml.convert(ByteArrayInputStream(file), {}, PreviewMedia.inline()); fail("A package inflating past a gigabyte was read to its end") }
        catch (_: PreviewLimitException) { }
        try { TextExtract.text(file, "a.docx"); fail("A package inflating past a gigabyte was read to its end") }
        catch (_: PreviewLimitException) { }
    }

    @Test(timeout = 60_000) fun aWorkbookNamingOnePartForEverySheetReadsItOnce() {
        val main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val relationships = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val workbook = "<workbook xmlns=\"$main\" xmlns:r=\"$relationships\"><sheets>" +
            (1..1024).joinToString("") { "<sheet name=\"s$it\" sheetId=\"$it\" r:id=\"rId1\"/>" } + "</sheets></workbook>"
        val relations = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>"
        val sheet = "<worksheet xmlns=\"$main\"><sheetData>" + (1..30).joinToString("") { row ->
            "<row r=\"$row\">" + (0 until 100).joinToString("") { column -> "<c r=\"${SheetHtml.columnName(column)}$row\"><v>1</v></c>" } + "</row>"
        } + "</sheetData></worksheet>"
        val book = XlsxReader.read(zip("xl/workbook.xml" to workbook.toByteArray(), "xl/_rels/workbook.xml.rels" to relations.toByteArray(),
            "xl/worksheets/sheet1.xml" to sheet.toByteArray()))
        assertEquals(3000, book.sheets.sumOf { it.rows.values.sumOf { row -> row.size } })
    }

    @Test fun cellsOfAllSheetsTogetherStopAtTheWorkbooksLimit() {
        val budget = CellBudget(5)
        val first = Sheet("a", budget)
        val second = Sheet("b", budget)
        for (row in 0 until 4) first.put(row, 0, cell)
        for (row in 0 until 4) second.put(row, 0, cell)
        assertEquals(4, first.rows.size)
        assertEquals(1, second.rows.size)
        assertEquals(1, second.truncatedAt)
    }
}
