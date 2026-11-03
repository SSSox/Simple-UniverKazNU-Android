package com.univerflow.kaznu.preview

import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Locale
import javax.imageio.ImageIO

/**
 * Document pictures are fetched only near the screen: the store gives each address its pixel size and the page reserves
 * a box of those proportions. Pictures are made with ImageIO; WebP and EXIF headers are written by hand.
 */
class LazyPicturesTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("documents/$name")!!.use { it.readBytes() }

    private fun image(format: String, width: Int, height: Int): ByteArray {
        val picture = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { assertTrue(ImageIO.write(picture, format, it)) }.toByteArray()
    }

    /** A JPEG header: SOI, an EXIF block with [orientation] (big-endian TIFF), a frame header of [width] x [height]. */
    private fun jpeg(width: Int, height: Int, orientation: Int?): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(value: Int) { out.write(value ushr 8); out.write(value and 0xFF) }
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        if (orientation != null) {
            val tiff = byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, 0, 1,
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
            out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte())); u16(2 + 6 + tiff.size)
            out.write("Exif".toByteArray()); out.write(byteArrayOf(0, 0)); out.write(tiff)
        }
        out.write(byteArrayOf(0xFF.toByte(), 0xC0.toByte())); u16(17); out.write(8); u16(height); u16(width); out.write(3)
        repeat(9) { out.write(0) }
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    private fun riff(chunk: String, data: ByteArray) = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + chunk.toByteArray() + ByteArray(4) + data

    @Test fun pictureSizesAreReadFromTheirHeaders() {
        assertEquals(640 to 480, ImageSize.of(image("png", 640, 480)))
        assertEquals(33 to 17, ImageSize.of(image("gif", 33, 17)))
        assertEquals(120 to 45, ImageSize.of(image("bmp", 120, 45)))
        assertEquals(300 to 200, ImageSize.of(image("jpg", 300, 200)))
        // A photo turned by its EXIF orientation is shown turned, so its size is too.
        assertEquals(4000 to 3000, ImageSize.of(jpeg(4000, 3000, orientation = 1)))
        assertEquals(3000 to 4000, ImageSize.of(jpeg(4000, 3000, orientation = 6)))
        assertEquals(4000 to 3000, ImageSize.of(jpeg(4000, 3000, orientation = null)))
        // WebP: lossy, lossless and extended headers.
        assertEquals(500 to 260, ImageSize.of(riff("VP8 ", byteArrayOf(0, 0, 0, 0x9D.toByte(), 1, 0x2A, (500 and 0xFF).toByte(), (500 shr 8).toByte(), (260 and 0xFF).toByte(), (260 shr 8).toByte()))))
        val bits = (99 - 1) or ((77 - 1) shl 14)
        assertEquals(99 to 77, ImageSize.of(riff("VP8L", byteArrayOf(0x2F, bits.toByte(), (bits shr 8).toByte(), (bits shr 16).toByte(), (bits shr 24).toByte()))))
        assertEquals(1920 to 1080, ImageSize.of(riff("VP8X", byteArrayOf(0, 0, 0, 0, (1919 and 0xFF).toByte(), (1919 shr 8).toByte(), 0, (1079 and 0xFF).toByte(), (1079 shr 8).toByte(), 0))))
        // Anything else, or a header cut short, has no size.
        assertNull(ImageSize.of("GIF8".toByteArray()))
        assertNull(ImageSize.of(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte(), 0, 2)))
        assertNull(ImageSize.of("<svg/>".toByteArray()))
        assertNull(ImageSize.of(ByteArray(0)))
    }

    @Test fun picturesWithASizeAreBoxesFetchedNearTheScreen() {
        val directory = Files.createTempDirectory("media").toFile()
        try {
            val media = FileMedia(directory)
            val png = image("png", 800, 600)
            val address = media.add("image/png", png)!!
            assertTrue(address, address.matches(Regex("${Regex.escape(PreviewHtml.ORIGIN)}/media/[0-9a-f]{64}#800x600")))
            // The same picture keeps its address; the fragment never reaches a request, so the file is found by its name.
            assertEquals(address, media.add("image/png", png))
            assertNotNull(media.open(address.substringAfter("/media/").substringBefore('#')))
            val box = PreviewHtml.picture(address, "Схема \"сети\"")
            assertEquals("<span class=\"pic\" style=\"width:800px\"><span style=\"padding-top:75.000%\"><span role=\"img\" aria-label=\"Схема &quot;сети&quot;\" " +
                "style=\"background-image:url(${address.substringBefore('#')})\"></span></span></span>", box)
            assertFalse(PreviewHtml.picture(address).contains("role="))
            // Without a size (a picture whose header is unknown, or the search's data: addresses) it stays an <img>.
            assertEquals("<img alt=\"\" loading=\"lazy\" src=\"data:image/png;base64,AA\">", PreviewHtml.picture("data:image/png;base64,AA"))
            // Every reader uses it: Word, the slide view and web pages.
            val word = WordView.html(resource("word.docx"), media = media)
            val slides = SlideView.html(resource("deck.pptx"), labels, media = media)
            val page = HtmlView.html(resource("page.html"), media = media)
            for (html in listOf(word, slides, page)) {
                assertTrue(html.contains("<span class=\"pic\" style=\"width:"))
                assertFalse(html, html.contains("<img"))
            }
            assertTrue(page.contains("role=\"img\" aria-label=\"точка\""))
            assertEquals(slides, SlideView.html(resource("deck.ppt"), labels, media = media))
        } finally { directory.deleteRecursively() }
    }

    @Test fun thePrintedCopyFetchesEveryPictureWithThePage() {
        val body = PreviewHtml.picture("${PreviewHtml.mediaUrl("a".repeat(64))}#10x10")
        val reading = PreviewHtml.docx("a.docx", "ru", null, body)
        val printed = PreviewHtml.docx("a.docx", "ru", null, body, PreviewHtml.Style(zoom = 50, print = true))
        assertTrue(reading.contains(".pic>span{display:block;position:relative;height:0;content-visibility:auto}"))
        assertFalse(reading.contains("content-visibility:visible"))
        assertTrue(printed.contains(".pic>span,.page .leaf{content-visibility:visible}"))
        assertTrue(printed.contains("html{zoom:0.5}"))
        // Pictures print as backgrounds, and on paper tables lose their scroll frame and their "Open table" link.
        assertTrue(reading.contains("print-color-adjust:exact"))
        assertTrue(reading.contains("@media print{.open-table{display:none}html .paper .table,html .grid{overflow:visible;max-height:none}}"))
    }

    private val labels = PreviewLabels(Locale.forLanguageTag("ru"), sheets = "Листы", emptySheet = "Лист пуст", emptyDeck = "Нет слайдов",
        notes = "Заметки", slide = { number, total -> "Слайд $number из $total" }, sheetTruncated = { rows -> "Первые $rows" })

}
