package com.univerflow.kaznu.preview

import org.junit.Assert.*
import org.junit.Test
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PreviewTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun docx(document: String, relations: String? = null, styles: String? = null, media: Map<String, ByteArray> = emptyMap()): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("[Content_Types].xml", "<Types/>".toByteArray())
            put("word/document.xml", document.toByteArray(Charsets.UTF_8))
            relations?.let { put("word/_rels/document.xml.rels", it.toByteArray()) }
            styles?.let { put("word/styles.xml", it.toByteArray(Charsets.UTF_8)) }
            media.forEach { (name, bytes) -> put("word/media/$name", bytes) }
        }
        return output.toByteArray()
    }

    private fun body(content: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><w:body>$content</w:body></w:document>"""

    @Test fun wordDocumentKeepsHeadingsFormattingListsLinksAndPictures() {
        val styles = """<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
            <w:style w:styleId="1"><w:name w:val="heading 1"/></w:style><w:style w:styleId="Body"><w:name w:val="Normal"/></w:style></w:styles>"""
        val relations = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rLink" Target="https://farabi.university/" TargetMode="External"/>
            <Relationship Id="rBad" Target="javascript:alert(1)" TargetMode="External"/>
            <Relationship Id="rImage" Target="media/image1.png"/></Relationships>"""
        val html = DocxHtml.convert(ByteArrayInputStream(docx(body("""
            <w:p><w:pPr><w:pStyle w:val="1"/><w:jc w:val="center"/></w:pPr><w:r><w:t>Силлабус</w:t></w:r></w:p>
            <w:p><w:pPr><w:tabs><w:tab w:val="left" w:pos="720"/></w:tabs></w:pPr><w:r><w:rPr><w:b/><w:i w:val="0"/></w:rPr><w:t>Жирный &lt;script&gt;</w:t></w:r><w:r><w:tab/><w:t xml:space="preserve"> обычный</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/></w:numPr></w:pPr><w:r><w:t>Пункт</w:t></w:r></w:p>
            <w:p><w:hyperlink r:id="rLink"><w:r><w:t>Сайт</w:t></w:r></w:hyperlink><w:hyperlink r:id="rBad"><w:r><w:t>Опасная</w:t></w:r></w:hyperlink></w:p>
            <w:p><w:r><w:drawing><a:blip r:embed="rImage"/></w:drawing></w:r></w:p><w:p/>"""), relations, styles, mapOf("image1.png" to png))))
        assertTrue(html, html.contains("<h1 style=\"text-align:center\">Силлабус</h1>"))
        assertTrue(html.contains("<b>Жирный &lt;script&gt;</b>"))
        assertFalse(html.contains("<i>"))
        assertEquals("Paragraph tab stops are not rendered as tabs", 1, Regex("class=\"tab\"").findAll(html).count())
        assertTrue(html.contains("<p class=\"li\">• Пункт</p>"))
        assertTrue(html.contains("<a href=\"https://farabi.university/\">Сайт</a>"))
        assertTrue("Unsafe link stays text", html.contains("Опасная") && !html.contains("javascript:"))
        assertTrue(html.contains("<img alt=\"\" loading=\"lazy\" src=\"data:image/png;base64,${WordHtml.base64(png)}\">"))
        assertTrue(html.contains("<p class=\"empty\"></p>"))
    }

    @Test fun tablesRebuildColumnAndRowSpansFromWordMerges() {
        fun cell(text: String, props: String = "") = "<w:tc><w:tcPr>$props</w:tcPr><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:tc>"
        val html = DocxHtml.convert(ByteArrayInputStream(docx(body("""<w:tbl>
            <w:tr>${cell("A", "<w:vMerge w:val=\"restart\"/>")}${cell("B", "<w:gridSpan w:val=\"2\"/>")}</w:tr>
            <w:tr>${cell("", "<w:vMerge/>")}${cell("C")}${cell("D")}</w:tr>
            <w:tr>${cell("", "<w:vMerge/>")}${cell("E")}${cell("F")}</w:tr></w:tbl>"""))))
        assertTrue(html, html.contains("<td rowspan=\"3\"><p>A</p></td><td colspan=\"2\"><p>B</p></td>"))
        assertTrue(html.contains("<tr><td><p>C</p></td><td><p>D</p></td></tr>"))
        assertEquals(6, Regex("<td").findAll(html).count())
    }

    @Test fun oversizedPartsAndMissingBodyAreRejected() {
        val huge = body("<w:p><w:r><w:t>" + "x".repeat(65 * 1024 * 1024) + "</w:t></w:r></w:p>")
        assertThrows(PreviewLimitException::class.java) { DocxHtml.convert(ByteArrayInputStream(docx(huge))) }
        val notWord = ByteArrayOutputStream().also { ZipOutputStream(it).use { zip -> zip.putNextEntry(ZipEntry("a.txt")); zip.write(1); zip.closeEntry() } }
        assertThrows(UnsupportedDocumentException::class.java) { DocxHtml.convert(ByteArrayInputStream(notWord.toByteArray())) }
    }

    @Test fun wideMergedAndNestedTablesKeepTheirLogicalWidthsAndFormulaText() {
        fun cell(text: String, props: String = "") = "<w:tc><w:tcPr>$props</w:tcPr><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:tc>"
        val nested = "<w:tbl><w:tblGrid><w:gridCol w:w=\"2250\"/></w:tblGrid><w:tr>${cell("Вложенная таблица")}</w:tr></w:tbl>"
        val formula = """<w:p><m:oMath xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math"><m:f><m:num><m:sSup><m:e><m:r><m:t>x</m:t></m:r></m:e><m:sup><m:r><m:t>2</m:t></m:r></m:sup></m:sSup></m:num><m:den><m:r><m:t>y</m:t></m:r></m:den></m:f></m:oMath></w:p>"""
        val html = DocxHtml.convert(ByteArrayInputStream(docx(body("""<w:tbl><w:tblGrid><w:gridCol w:w="1440"/><w:gridCol w:w="3600"/><w:gridCol w:w="3600"/></w:tblGrid>
            <w:tr>${cell("Объединённая", "<w:vMerge w:val=\"restart\"/>")}${cell("Заголовок", "<w:gridSpan w:val=\"2\"/>")}</w:tr>
            <w:tr>${cell("", "<w:vMerge/>")}<w:tc>$formula$nested</w:tc>${cell("Длинная дисциплина")}</w:tr></w:tbl>"""))))
        assertTrue(html, html.contains("min-width:576px;width:576px"))
        assertTrue(html.contains("min-width:150px;width:150px"))
        assertTrue(html.contains("rowspan=\"2\"") && html.contains("colspan=\"2\""))
        assertTrue(html.contains("<sup>2</sup>"))
        assertTrue(html.contains("word-fraction") && html.contains("x") && html.contains("y"))
        val reading = PreviewHtml.docx("a", "ru", null, html)
        assertTrue(reading.contains("overflow-wrap:break-word;word-break:normal"))
        // The reading page fills the screen and, zoomed out, stops widening at an A4 sheet unless that setting is off.
        assertTrue(reading.contains(".paper,.notice{max-width:794px") && !reading.contains("html{zoom") && !reading.contains("class=\"notice\""))
        val zoomedOut = PreviewHtml.docx("a", "ru", null, html, PreviewHtml.Style(zoom = 50, a4 = false))
        assertTrue(zoomedOut.contains("html{zoom:0.5}") && !zoomedOut.contains("max-width:794px"))
        assertTrue(PreviewHtml.document("a", "ru", "", style = PreviewHtml.Style(zoom = 10)).contains("html{zoom:0.25}"))
    }

    @Test fun documentTypeDeclarationsAreNotResolved() {
        val evil = """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/hosts">]>""" + body("<w:p><w:r><w:t>&x;</w:t></w:r></w:p>").substringAfter("?>")
        val result = runCatching { DocxHtml.convert(ByteArrayInputStream(docx(evil))) }
        assertFalse(result.getOrNull().orEmpty().contains("localhost"))
    }

    @Test fun nestedEntitiesAreRefusedBeforeAnyParserExpandsThemInEveryEncodingItReads() {
        // Android's parser ignores the DOCTYPE ban and expands internal entities, so the check cannot be left to it.
        val prolog = """<?xml version="1.0" encoding="UTF-8"?><!-- a <b> in a comment --><!DOCTYPE w:document [<!ENTITY a "aaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;">]>"""
        val bomb = prolog + body("<w:p><w:r><w:t>&b;</w:t></w:r></w:p>").substringAfter("?>")
        val utf16 = bomb.replace("UTF-8", "UTF-16")
        listOf(bomb.toByteArray(Charsets.UTF_8), byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bomb.toByteArray(Charsets.UTF_8),
            utf16.toByteArray(Charsets.UTF_16), byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16.toByteArray(Charsets.UTF_16LE),
            utf16.toByteArray(Charsets.UTF_16LE), utf16.toByteArray(Charsets.UTF_16BE), ("\n " + bomb.substringAfter("?>")).toByteArray()).forEach { bytes ->
            assertThrows(UnsupportedDocumentException::class.java) { SafeXml.parse(bytes, DefaultHandler()) }
        }
        assertThrows(UnsupportedDocumentException::class.java) { DocxHtml.convert(ByteArrayInputStream(docx(bomb))) }
        // A declaration mentioned in text, in a comment or in character data is not one.
        val mentioned = body("<w:p><w:r><w:t>&lt;!DOCTYPE html&gt; <![CDATA[<!DOCTYPE x>]]></w:t></w:r></w:p>").replaceFirst("?>", "?><!-- <!DOCTYPE c> -->")
        assertEquals("<p>&lt;!DOCTYPE html&gt; &lt;!DOCTYPE x&gt;</p>", DocxHtml.convert(ByteArrayInputStream(docx(mentioned))))
    }

    @Test fun textBoxesAndGroupsSavedTwiceShowOnceInsideTheParagraphThatAnchorsThem() {
        val chart = png + byteArrayOf(1)
        val styles = """<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:styleId="1"><w:name w:val="heading 1"/></w:style></w:styles>"""
        val relations = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rImage" Target="media/image1.png"/><Relationship Id="rChartPicture" Target="media/image2.png"/></Relationships>"""
        val namespaces = """xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" xmlns:v="urn:schemas-microsoft-com:vml"
            xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" xmlns:wpg="http://schemas.microsoft.com/office/word/2010/wordprocessingGroup"
            xmlns:cx="http://schemas.microsoft.com/office/drawing/2014/chartex""""
        // Word 2010 and later save a text box, shape or group for itself (Choice) and again in VML for older readers (Fallback).
        fun alternate(requires: String, choice: String, fallback: String) =
            "<mc:AlternateContent $namespaces><mc:Choice Requires=\"$requires\">$choice</mc:Choice><mc:Fallback>$fallback</mc:Fallback></mc:AlternateContent>"
        val box = "<w:txbxContent><w:p><w:r><w:t>Штамп</w:t></w:r></w:p><w:tbl><w:tblPr><w:jc w:val=\"center\"/></w:tblPr>" +
            "<w:tr><w:tc><w:p><w:r><w:t>Подпись</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:txbxContent>"
        val html = DocxHtml.convert(ByteArrayInputStream(docx(body("""
            <w:p><w:pPr><w:pStyle w:val="1"/></w:pPr><w:r><w:t>Титул</w:t></w:r><w:r><w:rPr><w:b/></w:rPr>${alternate("wps",
                "<w:drawing><wps:wsp><wps:txbx>$box</wps:txbx></wps:wsp></w:drawing>",
                "<w:pict><v:shape><v:textbox>$box</v:textbox></v:shape></w:pict>")}<w:t>после</w:t></w:r></w:p>
            <w:p><w:r>${alternate("wpg", "<w:drawing><wpg:wgp><a:blip r:embed=\"rImage\"/></wpg:wgp></w:drawing>",
                "<w:pict><v:group><v:shape><v:imagedata r:id=\"rImage\"/></v:shape></v:group></w:pict>")}</w:r></w:p>
            <w:p><w:r>${alternate("cx1", "<w:drawing><cx:chart r:id=\"rChart\"/></w:drawing>",
                "<w:drawing><a:blip r:embed=\"rChartPicture\"/></w:drawing>")}</w:r></w:p>"""), relations, styles,
            mapOf("image1.png" to png, "image2.png" to chart))))
        assertEquals(html, 1, Regex("Штамп").findAll(html).count())
        assertEquals(1, Regex("Подпись").findAll(html).count())
        // The heading keeps its level: neither the text box's paragraphs nor its table's alignment replace its style.
        assertTrue(html, html.startsWith("<h1>Титул<p>Штамп</p>"))
        assertTrue("The run holding the text box keeps its formatting after it", html.contains("<b>после</b></h1>"))
        assertEquals("A grouped picture appears once", 2, html.split("base64,${WordHtml.base64(png)}\"").size)
        // A chart type this view cannot draw shows the picture Word saved in its place.
        assertEquals(2, html.split("base64,${WordHtml.base64(chart)}\"").size)
    }

    @Test fun aVoidEmbedOrASelfClosingDroppedElementKeepsTheRestOfAWebPage() {
        assertEquals("<p>a</p><p>b</p>", HtmlView.html("<p>a</p><embed src=\"lecture.pdf\" type=\"application/pdf\"><p>b</p>".toByteArray()))
        assertEquals("<p>a</p><p>b</p>", HtmlView.html("<p>a</p><embed src=video.mp4 /><p>b</p>".toByteArray()))
        assertEquals("<p>a</p><p>b</p>", HtmlView.html("<p>a</p><svg width=\"10\"/><math/><p>b</p>".toByteArray()))
        // A dropped element that is closed still loses its content.
        assertEquals("<p>a</p><p>b</p>", HtmlView.html("<p>a</p><svg><text>x</text></svg><video src=v.mp4>Нет видео</video><p>b</p>".toByteArray()))
    }

    @Test fun textDecodingDetectsBomsUtf8AndWindows1251() {
        val utf8 = "Расписание и Қазақша".toByteArray(Charsets.UTF_8)
        assertEquals("Расписание и Қазақша", TextPreview.read(ByteArrayInputStream(utf8)).text)
        assertEquals("БОМ", TextPreview.read(ByteArrayInputStream(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "БОМ".toByteArray())).text)
        assertEquals("Юникод", TextPreview.read(ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Юникод".toByteArray(Charsets.UTF_16LE))).text)
        val legacy = "Оценки студентов".toByteArray(charset("windows-1251"))
        assertEquals("Оценки студентов", TextPreview.read(ByteArrayInputStream(legacy)).text)
    }

    @Test fun truncationKeepsWholeCharactersAndReportsIt() {
        val text = "аб".repeat(10) // Two bytes per letter in UTF-8.
        val content = TextPreview.read(ByteArrayInputStream(text.toByteArray()), limit = 7)
        assertTrue(content.truncated)
        assertEquals("аба", content.text)
        assertFalse(TextPreview.read(ByteArrayInputStream("ok".toByteArray()), limit = 7).truncated)
    }

    @Test fun previewPagesEscapeTextAndAllowOnlyTheirOwnImages() {
        assertEquals("&lt;b&gt; &amp; &quot;x&quot; &#39;y&#39;", PreviewHtml.escape("<b> & \"x\" 'y'"))
        val page = PreviewHtml.text("<title>", "ru", null, "<script>alert(1)</script>")
        assertTrue(page.contains("default-src 'none'; img-src ${PreviewHtml.ORIGIN} data:"))
        assertFalse(page.contains("<script>"))
        val pdf = PreviewHtml.pdf("a.pdf", "ru", listOf(595 to 842, 842 to 595)) { "Страница $it" }
        assertTrue(pdf.contains("style=\"padding-top:70.665%\"><div role=\"img\" aria-label=\"Страница 2\" style=\"background-image:url(${PreviewHtml.pageUrl(1)})\">"))
        assertTrue(pdf.contains("<figcaption>Страница 2</figcaption>"))
    }
}
