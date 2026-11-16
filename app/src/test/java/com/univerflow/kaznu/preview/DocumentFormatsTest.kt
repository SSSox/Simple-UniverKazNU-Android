package com.univerflow.kaznu.preview

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Old and other document formats read by the app's own code. The fixtures were saved from one synthetic document by
 * Word, Excel and PowerPoint 16, including a web page renamed to .doc and a password-protected .doc.
 */
class DocumentFormatsTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("documents/$name")!!.use { it.readBytes() }

    @Test fun docLooksExactlyLikeTheSameDocumentSavedAsDocx() {
        val docx = WordView.html(resource("word.docx"))
        assertEquals(docx, WordView.html(resource("word.doc")))
        // Heading, formatting, list, link, tab, line break, picture and the merged table are all there.
        listOf("<h1>Силлабус: Информационная безопасность</h1>", "<b>жирный</b>", "<i>курсив</i>", "<u>подчёркнутый</u>", "x<sup>2</sup>",
            "<p style=\"text-align:center\">По центру</p>", "<p class=\"li\">• Первый пункт</p>", "<a href=\"https://www.kaznu.kz/\">Сайт КазНУ</a>",
            "Имя<span class=\"tab\"></span>Значение", "Строка один<br>строка два", "<img alt=\"\" loading=\"lazy\" src=\"data:image/png;base64,",
            "<td rowspan=\"2\"><p>1–2</p></td>", "<td colspan=\"2\"><p>Итоговый тест</p></td>").forEach { assertTrue(it, docx.contains(it)) }
    }

    @Test fun rtfLooksLikeDocxExceptForItsExplicitLinkUnderline() {
        val rtf = WordView.html(resource("word.rtf"))
        assertEquals(WordView.html(resource("word.docx")),
            rtf.replace("<a href=\"https://www.kaznu.kz/\"><u>Сайт КазНУ</u></a>", "<a href=\"https://www.kaznu.kz/\">Сайт КазНУ</a>"))
    }

    @Test fun aWebPageSavedAsDocOpensAsParagraphsOfText() {
        val html = WordView.html(resource("word-html.doc"))
        assertTrue(html, html.contains("<p>Обычный текст, жирный, курсив, подчёркнутый и x2.</p>"))
        assertFalse(html.contains("<script") || html.contains("mso-"))
    }

    @Test fun everyFormatGivesItsTextToTheSearch() {
        val phrases = listOf("Силлабус: Информационная безопасность", "Қазақ тілі: әліпби, ұлттық, үшін. Final exam.", "Сайт КазНУ", "Итоговый тест", "Конец документа.")
        listOf("word.doc", "word.docx", "word.rtf", "word.odt", "word.pdf", "word-html.doc").forEach { name ->
            val text = TextExtract.text(resource(name), name)
            phrases.forEach { assertTrue("$name: $it in\n$text", text.contains(it)) }
        }
        val pdf = TextExtract.text(resource("word.pdf"), "word.pdf")
        assertTrue(pdf, pdf.contains("Обычный текст, жирный, курсив, подчёркнутый и x") && pdf.contains("Неделя Тема Баллы"))
        val doc = TextExtract.text(resource("word.doc"), "word.doc")
        assertTrue("Field codes are not text", !doc.contains("HYPERLINK"))
    }

    @Test fun spreadsheetsGiveCellsRowByRowAndPresentationsTheirSlidesAndNotes() {
        val xls = TextExtract.text(resource("sheet.xls"), "sheet.xls")
        assertTrue(xls, xls.contains("Оценки") && xls.contains("Студент\tБалл\tКомментарий") && xls.contains("Иванов\t95\tОтлично") &&
            xls.contains("Әлия Сәрсенова\t87.5\tFinal exam passed"))
        assertTrue(TextExtract.text(resource("sheet.ods"), "sheet.ods").contains("Әлия Сәрсенова"))
        // Masters and the notes master hold prompts like "Образец текста", not the presentation's text.
        assertEquals("Лекция 1: Введение\nКриптография и шифры\nҚорытынды: Final summary\nЗаметки докладчика", TextExtract.text(resource("slides.ppt"), "slides.ppt"))
        assertEquals("Лекция 1: Введение\nКриптография и шифры\nҚорытынды: Final summary\nЗаметки докладчика", TextExtract.text(resource("slides.odp"), "slides.odp"))
    }

    @Test fun encryptedTruncatedAndForeignFilesFailCleanly() {
        assertThrows(UnsupportedDocumentException::class.java) { WordView.html(resource("encrypted.doc")) }
        assertThrows(UnsupportedDocumentException::class.java) { WordBinary.text(resource("encrypted.doc")) }
        val doc = resource("word.doc")
        assertThrows(UnsupportedDocumentException::class.java) { WordView.html(doc.copyOf(4096)) }
        // A compound file that is a spreadsheet, opened as a Word document.
        assertThrows(UnsupportedDocumentException::class.java) { WordView.html(resource("sheet.xls")) }
        assertThrows(UnsupportedDocumentException::class.java) { WordView.html("plain text".toByteArray()) }
        // Damaged bytes inside the file never hang or crash the reader.
        val damaged = doc.copyOf().also { bytes -> for (index in 600 until bytes.size step 97) bytes[index] = (bytes[index] + 37).toByte() }
        runCatching { WordView.html(damaged) }.exceptionOrNull()?.let { assertTrue(it.toString(), it is UnsupportedDocumentException || it is PreviewLimitException) }
        val pdf = resource("word.pdf")
        assertNotNull(runCatching { TextExtract.text(pdf.copyOf(pdf.size / 3), "cut.pdf") }.getOrNull())
    }

    private fun pdf(vararg objects: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray(Charsets.ISO_8859_1))
        objects.forEachIndexed { index, body -> out.write("${index + 1} 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1)) }
        out.write("trailer\n<< /Root 1 0 R >>\n%%EOF".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private fun stream(content: ByteArray, flate: Boolean): String {
        val data = if (!flate) content else ByteArrayOutputStream().also { DeflaterOutputStream(it, Deflater()).use { zip -> zip.write(content) } }.toByteArray()
        return "<< /Length ${data.size}${if (flate) " /Filter /FlateDecode" else ""} >>\nstream\n${String(data, Charsets.ISO_8859_1)}\nendstream"
    }

    @Test fun pdfFontsWithDifferencesAndToUnicodeAreDecodedInPageOrder() {
        // Page 2 comes first in the file; the page tree decides the order. Font F1: Cyrillic glyph names
        // through Differences; F2: a two-byte font with a ToUnicode bfrange and widths for word gaps.
        val cmap = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap 1 begincodespacerange <0000> <FFFF> endcodespacerange " +
            "1 beginbfrange <0001> <0003> [<041A> <0430> <043D>] endbfrange 1 beginbfchar <0004> <0020> endbfchar endcmap end end"
        val page1 = "BT /F1 12 Tf 72 700 Td (ÀÁÂ) Tj 0 -20 Td [(À) -50 (Á)] TJ ET".toByteArray(Charsets.ISO_8859_1)
        val page2 = byteArrayOf() + "BT /F2 10 Tf 1 0 0 1 72 700 Tm <0001> Tj 300 0 Td <0002> Tj ET".toByteArray(Charsets.ISO_8859_1)
        val file = pdf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [4 0 R 3 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F2 7 0 R >> >> /Contents 6 0 R >>",
            "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 8 0 R >> >> /Contents 5 0 R >>",
            stream(page1, flate = false),
            stream(page2, flate = true),
            "<< /Type /Font /Subtype /Type0 /BaseFont /X /Encoding /Identity-H /ToUnicode 9 0 R /DescendantFonts [10 0 R] >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Y /Encoding << /BaseEncoding /WinAnsiEncoding /Differences [192 /afii10017 /afii10066 /afii10067] >> >>",
            stream(cmap.toByteArray(Charsets.ISO_8859_1), flate = true),
            "<< /Type /Font /Subtype /CIDFontType2 /DW 500 /W [1 [600]] >>",
        )
        // Page 2 of the tree (object 3) shows "К" then, 300 units on, "а" (a word gap); page 1 shows "Абв" and "Аб".
        assertEquals("Абв\nАб\nК а", PdfText.text(file))
    }

    @Test(timeout = 30_000) fun pdfPagesSharingAHugeStreamStopAtTheDecodeBudgetKeepingTheTextReadSoFar() {
        // 2 000 different pages draw the same stream, which inflates to 1 MiB. Each stream alone is within its
        // limit, so only the document-wide budget (8 MiB here) keeps the reader from inflating it 2 000 times.
        val content = ("BT /F1 12 Tf 72 700 Td (Hi) Tj ET\n" + " ".repeat(1024 * 1024)).toByteArray(Charsets.ISO_8859_1)
        val pages = 2000
        val file = pdf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [${(5 until 5 + pages).joinToString(" ") { "$it 0 R" }}] /Count $pages >>",
            stream(content, flate = true),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
            *Array(pages) { "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> >> /Contents 3 0 R >>" },
        )
        val read = PdfText.text(file, budget = 8L * 1024 * 1024).lines().filter { it == "Hi" }.size
        assertTrue("pages read: $read", read in 1..8)
        // Pages sharing a small stream are all read within the default budget.
        assertEquals(List(3) { "Hi" }.joinToString("\n"), PdfText.text(pdf(
            "<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [5 0 R 6 0 R 7 0 R] /Count 3 >>",
            stream("BT /F1 12 Tf 72 700 Td (Hi) Tj ET".toByteArray(Charsets.ISO_8859_1), flate = true),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
            *Array(3) { "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> >> /Contents 3 0 R >>" })))
    }

    @Test fun pdfFiltersDecodeTheSpecificationExamples() {
        // The LZW example of the PDF specification: "-----A---B".
        val lzw = byteArrayOf(0x80.toByte(), 0x0B, 0x60, 0x50, 0x22, 0x0C, 0x0C, 0x85.toByte(), 0x01)
        assertEquals("-----A---B", String(Filters.lzw(lzw, 1), Charsets.ISO_8859_1))
        assertEquals("Man ", String(Filters.ascii85("9jqo^~>".toByteArray()), Charsets.ISO_8859_1))
        assertEquals("Hi", String(Filters.asciiHex("48 69>".toByteArray()), Charsets.ISO_8859_1))
        // PNG "Up" predictor over two rows of two bytes.
        assertArrayEquals(byteArrayOf(1, 2, 4, 6), Filters.png(byteArrayOf(2, 1, 2, 2, 3, 4), columns = 2, colors = 1, bits = 8))
        assertTrue(Encodings.glyph("afii10071") == "ё" && Encodings.glyph("uni0416") == "Ж" && Encodings.glyph("f_i") == "fi" && Encodings.glyph("a.sc") == "a")
    }
}
