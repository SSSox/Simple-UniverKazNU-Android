package com.univerflow.kaznu.preview

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Spreadsheet, presentation and web page views. Each Office fixture pair was saved from one synthetic file by Excel or
 * PowerPoint 16, and both formats of a family must look the same; page.html is a hand-written hostile page.
 */
class OfficeViewsTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("documents/$name")!!.use { it.readBytes() }
    private val russian = PreviewLabels(Locale.forLanguageTag("ru"), sheets = "Листы", emptySheet = "Лист пуст", emptyDeck = "Нет слайдов",
        notes = "Заметки", slide = { number, total -> "Слайд $number из $total" }, sheetTruncated = { rows -> "Первые $rows" })

    @Test fun xlsAndXlsxOfOneWorkbookLookExactlyTheSame() {
        val xlsx = SheetView.html(resource("table.xlsx"), russian)
        assertEquals(xlsx, SheetView.html(resource("table.xls"), russian))
        // Visible sheets only, with links between them; merges, formats, alignment and fonts are kept.
        listOf("<nav class=\"sheets\" aria-label=\"Листы\"><a href=\"#sheet-1\">Ведомость</a><a href=\"#sheet-2\">Итог</a></nav>",
            "<td colspan=\"5\" class=\"ac b\">Ведомость: Информационная безопасность</td>",
            "<th scope=\"row\">3</th><td class=\"ar\">1</td><td>Иванов Иван</td><td rowspan=\"2\" class=\"vm\">ИБ-21</td><td class=\"ar\">95</td><td class=\"ar\">15.10.2026</td>",
            "<td>Әлия Сәрсенова</td><td class=\"ar\">87,5</td><td class=\"ar\">16.10.2026</td>",
            "<td class=\"ar\">75,5%</td>", "<td class=\"ar i\">Итого</td>", "<td class=\"ar\">182,50</td><td class=\"ac\">TRUE</td>",
            "<col style=\"width:173px\">", "<th scope=\"col\">E</th></tr>",
            // Unwrapped text runs over empty neighbours (past the used range too), as Excel draws it.
            "<th scope=\"row\">1</th><td colspan=\"4\">Қазақ тілі: әліпби, ұлттық, үшін.</td></tr>", "<td></td><td colspan=\"2\">Конец таблицы.</td><td></td></tr>",
        ).forEach { assertTrue("$it in\n$xlsx", xlsx.contains(it)) }
        // The hidden column and the hidden sheet stay hidden; nothing past the last value is drawn.
        listOf("скрытая колонка", "скрытый лист", "Служебный", ">G</th>", ">F</th>").forEach { assertFalse(it, xlsx.contains(it)) }
    }

    @Test fun spreadsheetSearchTextMatchesTheTableInBothFormats() {
        val xlsx = TextExtract.text(resource("table.xlsx"), "table.xlsx")
        assertEquals(xlsx, TextExtract.text(resource("table.xls"), "table.xls"))
        assertTrue(xlsx, xlsx.startsWith("Ведомость\nВедомость: Информационная безопасность\n№\tСтудент\tГруппа\tБалл\tДата\n1\tИванов Иван\tИБ-21\t95\t15.10.2026\n"))
        assertTrue(xlsx, xlsx.contains("Итого\t182.50\tTRUE") && xlsx.contains("Итог\nҚазақ тілі") && !xlsx.contains("скрыт"))
    }

    @Test fun numberFormatsFollowExcel() {
        fun format(value: Double, code: String, locale: Locale = Locale.ROOT) = CellFormat.format(CellValue.Number(value), code, false, locale)
        assertEquals("1 234,50", format(1234.5, "#,##0.00", Locale.forLanguageTag("ru")))
        assertEquals("1,234.50", format(1234.5, "#,##0.00", Locale.US))
        assertEquals("(1,235)", format(-1234.5, "#,##0;(#,##0)", Locale.US))
        assertEquals("-", format(0.0, "0.00;-0.00;\"-\""))
        assertEquals("12.5%", format(0.125, "0.0%"))
        assertEquals("1.23E+05", format(123456.0, "0.00E+00"))
        assertEquals("150 ₸", format(150.0, "0 [$₸-43F]"))
        // Seconds that are not shown are cut off, not rounded into the minutes.
        assertEquals("05.03.2024 14:06", format(45356.588, "dd.mm.yyyy hh:mm"))
        assertEquals("29.02.1900", format(60.0, "dd.mm.yyyy"))
        assertEquals("36:30:00", format(1.5208333333, "[h]:mm:ss"))
        assertEquals("2:06 PM", format(0.588, "h:mm AM/PM"))
        assertEquals("0.1", format(0.1, "General"))
        assertEquals("33333.33333", format(100000.0 / 3, "General"))
        assertEquals("01.01.1904", CellFormat.format(CellValue.Number(0.0), "dd.mm.yyyy", true, Locale.ROOT))
    }

    @Test fun pptAndPptxOfOnePresentationLookExactlyTheSame() {
        val pptx = SlideView.html(resource("deck.pptx"), russian)
        assertEquals(pptx, SlideView.html(resource("deck.ppt"), russian))
        val picture = WordHtml.base64(java.util.zip.ZipInputStream(resource("deck.pptx").inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "ppt/media/image1.png" }; zip.readBytes()
        })
        listOf("<section class=\"slide\" id=\"slide-1\" aria-label=\"Слайд 1 из 3\"><p class=\"number\">Слайд 1 из 3</p>" +
                "<h2>Лекция 2: Шифрование</h2><p>Симметричные и асимметричные алгоритмы</p></section>",
            "<h2>Основные понятия</h2><ul><li>Ключ</li><li>Открытый текст</li><li class=\"l1\">Длина ключа: 128–256 бит</li><li>Қазақша: шифрлау</li></ul>" +
                "<details class=\"notes\"><summary>Заметки</summary><p>Напомнить о домашнем задании</p></details>",
            "<p class=\"number\">Слайд 3 из 3</p><figure><img alt=\"\" loading=\"lazy\" src=\"data:image/png;base64,$picture\"></figure><p>Схема шифрования</p></section>",
        ).forEach { assertTrue("$it in\n$pptx", pptx.contains(it)) }
        // Slide numbers and slide images of the notes pages are not text.
        assertEquals(1, Regex("<details").findAll(pptx).count())
    }

    @Test fun olderPresentationsAndWorkbooksOpenInTheSameViews() {
        val slides = SlideView.html(resource("slides.ppt"), russian)
        assertTrue(slides, slides.contains("<h2>Лекция 1: Введение</h2><p>Криптография и шифры</p>") &&
            slides.contains("<p>Қорытынды: Final summary</p><details class=\"notes\"><summary>Заметки</summary><p>Заметки докладчика</p></details>"))
        val sheet = SheetView.html(resource("sheet.xls"), russian)
        assertTrue(sheet, sheet.contains("<td>Иванов</td><td class=\"ar\">95</td><td>Отлично</td>") && !sheet.contains("<nav"))
    }

    @Test fun webPagesKeepTheirTextTablesAndPlainLinksOnly() {
        val html = HtmlView.html(resource("page.html"))
        listOf("<h1>Расписание экзаменов — осень 2026</h1>", "<p style=\"text-align:center\">Қазақ тілі: әліпби, ұлттық, үшін. <b>Важно</b>, <i>курсив</i> &amp; x<sup>2</sup>.",
            "<ul>\n  <li>Первый пункт\n  </li><li>Второй пункт <a href=\"https://www.kaznu.kz/\">Сайт КазНУ</a>", "<a>Опасная ссылка</a> и <a>файл</a>",
            "<div class=\"table\" role=\"region\" tabindex=\"0\"><table>", "<caption>Экзамены</caption>", "<th colspan=\"2\">Дата и аудитория</th>",
            "<td rowspan=\"2\">Криптография</td>", "</table></div>", "<span class=\"alt\">логотип</span>",
            "<img alt=\"точка\" loading=\"lazy\" src=\"data:image/png;base64,iVBORw0KGgo", "Текст внутри формы остаётся.", "Конец страницы.",
        ).forEach { assertTrue("$it in\n$html", html.contains(it)) }
        listOf("<script", "script", "onload", "onclick", "onerror", "example.com", "javascript:", "file:", "style=\"color", "<iframe", "<form", "<input",
            "секрет", "Войти", "вектор", "не текст", "Объект", "<base", "<meta", "refresh", "<title", "<style", "class=\"title\"").forEach { assertFalse(it, html.contains(it)) }
    }

    @Test fun webPagesSurviveBrokenMarkupAndDeclaredCharsets() {
        val broken = HtmlView.html("<p>один<p>два<table><tr><td>а<td>б</table><b><i>жирный</b> курсив</i> </div></span><head><title>x".toByteArray())
        assertEquals("<p>один</p><p>два</p><div class=\"table\" role=\"region\" tabindex=\"0\"><table><tr><td>а</td><td>б</td></tr></table></div><b><i>жирный</i></b> курсив ", broken)
        val windows = "<html><head><meta charset=windows-1251></head><body><p>Привет</p></body></html>".toByteArray(charset("windows-1251"))
        assertEquals("<p>Привет</p>", HtmlView.html(windows))
        // An unclosed head ends where the body starts; a script without its end tag hides only itself.
        assertEquals("<p>Текст</p>", HtmlView.html("<head><title>t</title><body><p>Текст</p>".toByteArray()))
        assertEquals("<p>a</p>", HtmlView.html("<p>a<script>b</p>".toByteArray()))
        val page = PreviewHtml.docx("p.html", "ru", "notice", HtmlView.html(resource("page.html")), PreviewHtml.Style(dark = true))
        assertTrue(page.contains("<html lang=\"ru\" class=\"dark\">") && page.contains("default-src 'none'; img-src ${PreviewHtml.ORIGIN} data:"))
    }

    private fun zip(vararg parts: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> parts.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
    }.toByteArray()

    @Test fun workbooksWithoutReferencesHugeGridsAndForeignFilesFailCleanly() {
        val main = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
        fun book(sheet: String) = zip("xl/workbook.xml" to "<workbook $main><sheets><sheet name=\"A\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\" Type=\"x\"/></Relationships>",
            "xl/worksheets/sheet1.xml" to "<worksheet $main><sheetData>$sheet</sheetData></worksheet>")
        // Rows and cells without "r" continue from the previous position; inline strings count.
        val implicit = SheetView.html(book("<row><c t=\"inlineStr\"><is><t>a</t></is></c><c><v>2</v></c></row><row><c r=\"C2\" t=\"str\"><v>c</v></c></row>"), russian)
        assertTrue(implicit, implicit.contains("<th scope=\"row\">1</th><td>a</td><td class=\"ar\">2</td><td></td></tr><tr><th scope=\"row\">2</th><td></td><td></td><td>c</td>"))
        // A lone value in the last cell of the sheet would draw seventeen billion empty cells: the sheet is shown from its start.
        val huge = SheetView.html(book("<row r=\"1\"><c r=\"A1\"><v>1</v></c></row><row r=\"1048576\"><c r=\"XFD1048576\"><v>2</v></c></row>"), russian)
        assertTrue(huge.take(2000), huge.contains("<p class=\"notice\">Первые 300</p>") && huge.contains("<th scope=\"col\">BXX</th>") && !huge.contains(">BXY</th>"))
        assertThrows(UnsupportedDocumentException::class.java) { SheetView.html(resource("word.doc"), russian) }
        assertThrows(UnsupportedDocumentException::class.java) { SlideView.html(resource("table.xls"), russian) }
        assertThrows(UnsupportedDocumentException::class.java) { SlideView.html(resource("word.docx"), russian) }
        assertThrows(UnsupportedDocumentException::class.java) { SheetView.html("plain".toByteArray(), russian) }
        // Damaged bytes inside binary files never hang or crash the readers.
        listOf("table.xls" to { bytes: ByteArray -> SheetView.html(bytes, russian) }, "deck.ppt" to { bytes: ByteArray -> SlideView.html(bytes, russian) }).forEach { (name, open) ->
            val damaged = resource(name).also { bytes -> for (index in 700 until bytes.size step 89) bytes[index] = (bytes[index] + 41).toByte() }
            runCatching { open(damaged) }.exceptionOrNull()?.let { assertTrue(it.toString(), it is UnsupportedDocumentException || it is PreviewLimitException) }
        }
    }
}
