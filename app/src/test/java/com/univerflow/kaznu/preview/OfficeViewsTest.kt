package com.univerflow.kaznu.preview

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        // An empty section hides its values (zero-hiding grade sheets, ";;;"); the other sections still show theirs.
        assertEquals("", format(0.0, "0;-0;;@"))
        assertEquals("", format(0.0, "#,##0.00;-#,##0.00;"))
        assertEquals("", format(0.0, "0;-0; ;@"))
        assertEquals("", format(-5.0, "0;;0"))
        assertEquals("", format(5.0, ";;;"))
        assertEquals("5", format(5.0, "0;-0;;@"))
        assertEquals("-5", format(-5.0, "0;-0;;@"))
        assertEquals("5", format(5.0, ""))
        // Elapsed time without seconds: the minutes after "[h]" are minutes, not the month of day 1.
        assertEquals("36:00", format(1.5, "[h]:mm"))
        assertEquals("36:30", format(1.5208333333, "[hh]:mm"))
        assertEquals("36", format(1.5, "[h]"))
        assertEquals("90.5", format(90.5 / 86_400, "[ss].0"))
        // "General" with text around it keeps the number and the text.
        assertEquals("5 шт", format(5.0, "General\" шт\""))
        assertEquals("-2.5 шт", format(-2.5, "General\" шт\""))
        assertEquals("0,1 кг", format(0.1, "General\" кг\"", Locale.forLanguageTag("ru")))
        assertEquals("-5", format(-5.0, "General;-General"))
    }

    private fun ByteBuffer.u16(value: Int): ByteBuffer = putShort(value.toShort())

    /** A BIFF8 record: type, body length, body. */
    private fun record(type: Int, body: ByteBuffer.() -> Unit = {}): ByteArray {
        val buffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN).apply(body)
        val size = buffer.position()
        return ByteBuffer.allocate(4 + size).order(ByteOrder.LITTLE_ENDIAN).u16(type).u16(size).put(buffer.array(), 0, size).array()
    }

    /** A compound file whose only stream is [name], padded past the mini-stream cutoff so it uses whole sectors. */
    private fun compound(name: String, data: ByteArray): ByteArray {
        val stream = data.copyOf((maxOf(data.size, 4096) + 511) / 512 * 512)
        val sectors = stream.size / 512
        val file = ByteBuffer.allocate(512 * (3 + sectors)).order(ByteOrder.LITTLE_ENDIAN)
        file.put(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))
        file.putShort(0x18, 0x3E).putShort(0x1A, 3).putShort(0x1C, 0xFFFE.toShort()).putShort(0x1E, 9).putShort(0x20, 6)
        file.putInt(0x2C, 1).putInt(0x30, 1).putInt(0x38, 4096).putInt(0x3C, -2).putInt(0x40, 0).putInt(0x44, -2).putInt(0x48, 0)
        for (index in 0 until 109) file.putInt(0x4C + index * 4, if (index == 0) 0 else -1)
        // Sector 0 is the FAT, sector 1 the directory, sectors 2 onward the stream.
        for (index in 0 until 128) file.putInt(512 + index * 4, when {
            index == 0 -> -3
            index == 1 || index == 1 + sectors -> -2
            index in 2 until 1 + sectors -> index + 1
            else -> -1
        })
        fun entry(at: Int, title: String, type: Int, child: Int, start: Int, size: Int) {
            title.forEachIndexed { index, char -> file.putChar(at + index * 2, char) }
            file.putShort(at + 0x40, ((title.length + 1) * 2).toShort()).put(at + 0x42, type.toByte()).put(at + 0x43, 1)
            file.putInt(at + 0x44, -1).putInt(at + 0x48, -1).putInt(at + 0x4C, child).putInt(at + 0x74, start).putInt(at + 0x78, size)
        }
        entry(1024, "Root Entry", 5, 1, -2, 0)
        entry(1024 + 128, name, 2, -1, 2, stream.size)
        file.position(1536)
        file.put(stream)
        return file.array()
    }

    @Test fun xlsKeepsTextResultsOfSharedArrayAndTableFormulas() {
        // Excel writes FORMULA, then SHRFMLA, ARRAY or TABLE for the first cell of such a formula, then the STRING result.
        fun formula(row: Int, vararg own: ByteArray) = record(0x0006) {
            u16(row); u16(0); u16(0); put(0); put(ByteArray(5)); u16(0xFFFF); u16(0x0008); putInt(0); u16(5); put(1); u16(0); u16(0)
        } + own.fold(ByteArray(0)) { all, next -> all + next }
        fun string(text: String) = record(0x0207) { u16(text.length); put(1); text.forEach { putChar(it) } }
        val shared = record(0x04BC) { u16(0); u16(1); put(0); put(0); put(0); put(2); u16(3); put(0x1E); u16(1) }
        val array = record(0x0221) { u16(2); u16(2); put(0); put(0); u16(0); putInt(0); u16(3); put(0x1E); u16(1) }
        val table = record(0x0236) { u16(3); u16(3); put(0); put(0); put(0); put(0); u16(0); u16(0); u16(0); u16(0) }
        val sheet = record(0x0809) { u16(0x0600); u16(0x0010); put(ByteArray(12)) } +
            formula(0, shared) + string("зачтено") + formula(1) + string("не зачтено") +
            formula(2, array) + string("массив") + formula(3, table) + string("кесте") + record(0x000A)
        val bof = record(0x0809) { u16(0x0600); u16(0x0005); put(ByteArray(12)) }
        fun boundSheet(position: Int) = record(0x0085) { putInt(position); put(0); put(0); put(5); put(1); "Лист1".forEach { putChar(it) } }
        val eof = record(0x000A)
        val globals = bof + boundSheet(bof.size + boundSheet(0).size + eof.size) + eof
        val book = SheetView.read(compound("Workbook", globals + sheet))
        assertEquals(listOf("зачтено", "не зачтено", "массив", "кесте"), (0..3).map { (book.sheets.single().cell(it, 0)?.value as? CellValue.Text)?.value })
        assertEquals("Лист1\nзачтено\nне зачтено\nмассив\nкесте", SheetView.text(book))
    }

    @Test fun aWorkbookNamingOneSheetAThousandTimesKeepsABoundedNumberOfCells() {
        val cells = (0 until 2000).fold(ByteArray(0)) { all, index ->
            all + record(0x0203) { u16(index / 100); u16(index % 100); u16(0); putDouble(1.0) }
        }
        val sheet = record(0x0809) { u16(0x0600); u16(0x0010); put(ByteArray(12)) } + cells + record(0x000A)
        val bof = record(0x0809) { u16(0x0600); u16(0x0005); put(ByteArray(12)) }
        fun boundSheet(position: Int) = record(0x0085) { putInt(position); put(0); put(0); put(1); put(1); putChar('s') }
        val eof = record(0x000A)
        val names = 1000
        val position = bof.size + names * boundSheet(0).size + eof.size
        val globals = bof + (1..names).fold(ByteArray(0)) { all, _ -> all + boundSheet(position) } + eof
        val book = SheetView.read(compound("Workbook", globals + sheet))
        // The sheet's cells are read; the 999 further names of the same sheet add none.
        assertEquals(2000, book.sheets.sumOf { it.rows.values.sumOf { row -> row.size } })
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
