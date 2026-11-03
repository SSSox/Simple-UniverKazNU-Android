package com.univerflow.kaznu.preview

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Large documents open: pictures leave the page for [PreviewMedia] (the viewer serves them as files), packages are
 * streamed, compound files are read in place from the open file, and a sheet too large is shown from its start.
 */
class LargeFilesTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("documents/$name")!!.use { it.readBytes() }
    private val labels = PreviewLabels(Locale.ROOT, slide = { number, total -> "$number/$total" })

    /** Keeps pictures as numbered addresses, as the viewer's [FileMedia] does, and counts their bytes. */
    private class Recorder : PreviewMedia {
        val pictures = mutableListOf<ByteArray>()
        override fun add(type: String, bytes: ByteArray): String { pictures += bytes; return "media:${pictures.size - 1}" }
    }

    /** A presentation with [slides] slides, each showing its own [pictureSize]-byte picture. */
    private fun presentation(slides: Int, pictureSize: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        val p = "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
        val random = Random(7)
        ZipOutputStream(bytes).use { zip ->
            fun put(name: String, data: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
            put("ppt/presentation.xml", "<p:presentation $p><p:sldIdLst>${(1..slides).joinToString("") { "<p:sldId id=\"${255 + it}\" r:id=\"s$it\"/>" }}</p:sldIdLst></p:presentation>".toByteArray())
            put("ppt/_rels/presentation.xml.rels", ("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                (1..slides).joinToString("") { "<Relationship Id=\"s$it\" Type=\"x/slide\" Target=\"slides/slide$it.xml\"/>" } + "</Relationships>").toByteArray())
            for (index in 1..slides) {
                put("ppt/slides/slide$index.xml", ("<p:sld $p><p:cSld><p:spTree><p:sp><p:nvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr>" +
                    "<p:txBody><a:p><a:r><a:t>Слайд $index</a:t></a:r></a:p></p:txBody></p:sp><p:pic><p:blipFill><a:blip r:embed=\"i\"/></p:blipFill></p:pic>" +
                    "</p:spTree></p:cSld></p:sld>").toByteArray())
                put("ppt/slides/_rels/slide$index.xml.rels", ("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"i\" Type=\"x/image\" Target=\"../media/image$index.png\"/></Relationships>").toByteArray())
                put("ppt/media/image$index.png", ByteArray(pictureSize).also(random::nextBytes))
            }
        }
    }.toByteArray()

    @Test fun aPresentationFullOfPicturesStaysASmallPage() {
        // 40 slides with 1.5 MB photos: 60 MB of pictures, far past one 8 MB page.
        val file = presentation(40, 1_500_000)
        val media = Recorder()
        // Streamed: the reader is never handed the whole file.
        val html = SlideView.html(FileAccess(file.copyOf(FileAccess.HEAD), { file.inputStream() }, { error("no positional reads for a zip") }), labels, {}, media)
        assertEquals(40, media.pictures.size)
        assertTrue(html.length.toString(), html.length < 20_000)
        assertTrue(html.contains("<p class=\"number\">40/40</p><h2>Слайд 40</h2><figure><img alt=\"\" loading=\"lazy\" src=\"media:39\"></figure>"))
    }

    @Test fun compoundFilesReadInPlaceMatchTheSameFileInMemory() {
        val deck = resource("deck.ppt")
        val temp = File.createTempFile("deck", ".ppt").apply { writeBytes(deck); deleteOnExit() }
        RandomAccessFile(temp, "r").use { random ->
            val inPlace = FileAccess(deck.copyOf(FileAccess.HEAD), { temp.inputStream() }, { ChannelSource(random.channel) })
            assertEquals(SlideView.html(deck, labels), SlideView.html(inPlace, labels, {}, PreviewMedia.inline()))
        }
        val word = resource("word.doc")
        val docTemp = File.createTempFile("word", ".doc").apply { writeBytes(word); deleteOnExit() }
        RandomAccessFile(docTemp, "r").use { random ->
            val inPlace = FileAccess(word.copyOf(FileAccess.HEAD), { docTemp.inputStream() }, { ChannelSource(random.channel) })
            assertEquals(WordView.html(word), WordView.html(inPlace, {}, PreviewMedia.inline()))
        }
        val sheet = resource("table.xls")
        assertEquals(SheetView.html(sheet, labels), SheetView.html(FileAccess(sheet.copyOf(FileAccess.HEAD), { sheet.inputStream() }, { ArraySource(sheet) }), labels, {}))
    }

    @Test fun picturesOfEveryFormatGoToTheMediaStore() {
        val docx = Recorder().also { WordView.html(resource("word.docx"), media = it) }
        val doc = Recorder().also { WordView.html(resource("word.doc"), media = it) }
        val rtf = Recorder().also { WordView.html(resource("word.rtf"), media = it) }
        assertEquals(1, docx.pictures.size)
        // The same picture, whatever the Word format.
        assertArrayEquals(docx.pictures[0], doc.pictures.single())
        assertArrayEquals(docx.pictures[0], rtf.pictures.single())
        val page = Recorder()
        val html = HtmlView.html(resource("page.html"), media = page)
        assertTrue(html.contains("<img alt=\"точка\" loading=\"lazy\" src=\"media:0\">") && page.pictures.single().size == 70)
        // Without room, a picture is left out rather than the document.
        val none = object : PreviewMedia { override fun add(type: String, bytes: ByteArray): String? = null }
        assertFalse(SlideView.html(resource("deck.pptx"), labels, media = none).contains("<img"))
    }

    @Test fun inlinePicturesStayWithinTheirBudget() {
        val media = PreviewMedia.inline(total = 100)
        assertNotNull(media.add("image/png", ByteArray(60)))
        assertNull(media.add("image/png", ByteArray(60)))
        assertNull(media.add("image/svg+xml", ByteArray(1)))
        assertEquals("AQID", WordHtml.base64(WordHtml.unbase64("AQ ID")!!))
    }
}
