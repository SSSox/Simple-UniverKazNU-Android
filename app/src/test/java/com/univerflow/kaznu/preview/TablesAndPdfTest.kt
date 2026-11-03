package com.univerflow.kaznu.preview

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/**
 * Tables that open on their own screen, and PDF pages that show before the whole file is measured. The Word, web page
 * and workbook fixtures are the ones of [DocumentFormatsTest] and [OfficeViewsTest].
 */
class TablesAndPdfTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("documents/$name")!!.use { it.readBytes() }
    private val labels = PreviewLabels(Locale.forLanguageTag("ru"), sheets = "Листы", emptySheet = "Лист пуст", emptyDeck = "Нет слайдов",
        notes = "Заметки", slide = { number, total -> "Слайд $number из $total" }, sheetTruncated = { rows -> "Первые $rows" })
    private val link = Regex("<p class=\"open-table\"><a href=\"[^\"]+\">Открыть таблицу</a></p>")

    private fun cells(rows: Int, columns: Int, inner: String = "") = "<div class=\"table\" role=\"region\" tabindex=\"0\"><table><tbody>" +
        (1..rows).joinToString("") { r -> "<tr>" + (1..columns).joinToString("") { c -> "<td><p>$r.$c</p>${if (r == 1 && c == 1) inner else ""}</td>" } + "</tr>" } +
        "</tbody></table></div>"

    @Test fun largeTopLevelTablesGetALinkToTheirOwnScreen() {
        val nested = cells(2, 2)
        val large = cells(3, 2, inner = nested)
        val heading = cells(1, 3) // One row: a layout table, no link.
        val body = "<p>До</p>$heading<div class=\"note\"><p>текст</p></div>$large<p>После</p>${cells(2, 2)}"
        val tables = DocumentTables.find(body, "Открыть таблицу")
        assertEquals(2, tables.count)
        assertEquals(large, tables.markup(0))
        assertEquals(cells(2, 2), tables.markup(1))
        assertNull(tables.markup(2))
        // Only the links are added, each just before its table, and they point to the tables in order.
        assertEquals(body, link.replace(tables.body, ""))
        assertTrue(tables.body.contains("<p class=\"open-table\"><a href=\"${PreviewHtml.tableUrl(0)}\">Открыть таблицу</a></p>$large"))
        assertTrue(tables.body.contains("<a href=\"${PreviewHtml.tableUrl(1)}\">"))
        // A body without such tables is kept as it is.
        val plain = "<p>Текст</p>$heading"
        assertSame(plain, DocumentTables.find(plain, "Открыть таблицу").body)
        // An element that never closes gets no link.
        assertEquals(0, DocumentTables.find("<div class=\"table\" role=\"region\"><table><tr><td>", "x").count)
    }

    @Test fun wordWebPageAndWorkbookTablesOpenOnTheirOwnScreen() {
        val word = DocumentTables.find(WordView.html(resource("word.docx")), "Открыть таблицу")
        assertEquals(1, word.count)
        assertTrue(word.markup(0)!!.contains("<td colspan=\"2\"><p>Итоговый тест</p></td>"))
        val page = PreviewHtml.table("word.docx", "ru", word.markup(0)!!, PreviewHtml.Style(zoom = 50, dark = true))
        // The table page opens zoomed out: no initial or minimum scale, and the layout zoom of the document does not apply.
        assertTrue(page.contains("content=\"width=device-width,maximum-scale=6\""))
        assertFalse(page.contains("html{zoom"))
        assertTrue(page.contains("<html lang=\"ru\" class=\"dark\">"))
        assertTrue(page.contains("default-src 'none'; img-src ${PreviewHtml.ORIGIN} data:"))
        // A Word table keeps its own width; a web page's table is laid out at most 1200 px wide.
        assertTrue(page.contains("<article class=\"paper solo\"><div class=\"table\""))
        val web = DocumentTables.find(HtmlView.html(resource("page.html")), "Открыть таблицу")
        assertTrue(web.count >= 1)
        assertTrue(PreviewHtml.table("page.html", "ru", web.markup(0)!!).contains("<article class=\"paper solo fluid\">"))
        // Every sheet of a workbook has its link, whatever its size.
        val sheets = DocumentTables.find(SheetView.html(resource("table.xlsx"), labels), "Открыть таблицу")
        assertEquals(Regex("<div class=\"grid\"").findAll(sheets.body).count(), sheets.count)
        assertTrue(sheets.markup(0)!!.startsWith("<div class=\"grid\""))
        assertTrue(PreviewHtml.table("table.xlsx", "ru", sheets.markup(0)!!).contains("<div class=\"solo\"><div class=\"grid\""))
        // The document page keeps everything else and only gains the links.
        val sheet = SheetView.html(resource("table.xlsx"), labels)
        assertEquals(sheet, link.replace(sheets.body, ""))
    }

    @Test fun pdfPagesAreWrittenWhileTheyAreReadAndAskedForOnlyNearTheScreen() {
        val sizes = List(40) { if (it % 2 == 0) 595 to 842 else 842 to 595 }
        val whole = PreviewHtml.pdf("a.pdf", "ru", sizes, notice = "Показаны первые 40") { "Страница $it" }
        var asked = 0
        val stream = PreviewHtml.pdfStream("a.pdf", "ru", sizes.size, { index -> asked++; sizes[index] }, notice = "Показаны первые 40") { "Страница $it" }
        // The start of the page, with its first pages, is there before most sizes are read.
        val start = ByteArray(16 * 1024)
        var read = 0
        while (!String(start, 0, read, Charsets.UTF_8).contains("Страница 1<")) read += stream.read(start, read, start.size - read)
        assertTrue("Sizes read for the first pages: $asked", asked < sizes.size / 2)
        assertEquals(whole, String(start, 0, read, Charsets.UTF_8) + stream.readBytes().toString(Charsets.UTF_8))
        assertEquals(sizes.size, asked)
        // No <img>: lazy loading needs JavaScript. A background in a box skipped off screen is asked for only near it.
        assertFalse(whole.contains("<img"))
        assertTrue(whole.contains(".page .leaf{position:relative;height:0;content-visibility:auto"))
        assertTrue(whole.contains("<div class=\"leaf\" style=\"padding-top:70.665%\"><div role=\"img\" aria-label=\"Страница 2\" " +
            "style=\"background-image:url(${PreviewHtml.pageUrl(1)})\"></div></div><figcaption>Страница 2</figcaption>"))
        // A document closed while the page is read ends the page early, still well formed.
        val closed = PreviewHtml.pdfStream("a.pdf", "ru", 40, { index -> if (index < 3) 595 to 842 else null }) { "Страница $it" }.readBytes().toString(Charsets.UTF_8)
        assertEquals(3, Regex("<figure").findAll(closed).count())
        assertTrue(closed.endsWith("</body></html>"))
    }

    @Test fun aPdfPageIsDrawnOnlyWhenItsAnswerIsRead() {
        var drawn = 0
        val page = DeferredStream { drawn++; byteArrayOf(1, 2, 3) }
        assertEquals(0, drawn)
        assertArrayEquals(byteArrayOf(1, 2, 3), page.readBytes())
        assertEquals(1, drawn)
        assertEquals(-1, page.read())
        // An answer never read draws nothing; a page without memory for it is empty.
        DeferredStream { drawn++; null }.close()
        assertEquals(1, drawn)
        assertEquals(0, DeferredStream { null }.readBytes().size)
        assertEquals(0, DeferredStream { throw IllegalStateException("closed") }.readBytes().size)
    }
}
