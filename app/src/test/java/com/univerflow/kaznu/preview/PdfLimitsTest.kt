package com.univerflow.kaznu.preview

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Hostile PDFs read for the search in "Downloads": a small file must not decode, map or scan without end. The fixtures are
 * synthetic and small; without their limits each would take gigabytes of memory or hours of work.
 */
class PdfLimitsTest {
    private fun pdf(vararg objects: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray(Charsets.ISO_8859_1))
        objects.forEachIndexed { index, body -> out.write("${index + 1} 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1)) }
        out.write("trailer\n<< /Root 1 0 R >>\n%%EOF".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private fun deflate(content: ByteArray): ByteArray =
        ByteArrayOutputStream().also { DeflaterOutputStream(it, Deflater()).use { zip -> zip.write(content) } }.toByteArray()

    /** A stream object holding [data] as it is, with its [filter] (a name or an array of names). */
    private fun stream(data: ByteArray, filter: String? = null) =
        "<< /Length ${data.size}${filter?.let { " /Filter $it" }.orEmpty()} >>\nstream\n${String(data, Charsets.ISO_8859_1)}\nendstream"

    private fun content(operators: String) = stream(deflate(operators.toByteArray(Charsets.ISO_8859_1)), "/FlateDecode")

    private fun page(contents: Int, fonts: String = "") = "<< /Type /Page /Resources << /Font << $fonts >> >> /Contents $contents 0 R >>"

    /** LZW codes that each add the previous string and its first byte (the KwKwK case), then repeat the longest one. */
    private fun lzwBomb(repeats: Int): ByteArray {
        val codes = listOf(65) + (258 until 4096) + List(repeats) { 4095 }
        val out = ByteArrayOutputStream()
        var buffer = 0L
        var buffered = 0
        var table = 258
        var bits = 9
        codes.forEachIndexed { index, code ->
            buffer = (buffer shl bits) or code.toLong()
            buffered += bits
            while (buffered >= 8) { out.write((buffer shr (buffered - 8)).toInt() and 0xFF); buffered -= 8 }
            // The decoder's table grows from the second code on, and the code width follows it (EarlyChange 1).
            if (index > 0 && table < 4096) table++
            bits = when { table + 1 >= 2048 -> 12; table + 1 >= 1024 -> 11; table + 1 >= 512 -> 10; else -> 9 }
        }
        if (buffered > 0) out.write((buffer shl (8 - buffered)).toInt() and 0xFF)
        return out.toByteArray()
    }

    @Test(timeout = 30_000) fun expandingFiltersStopAtTheirLimit() {
        // Exactly the limit is decoded; one byte over it is refused.
        val run = byteArrayOf(0x81.toByte(), 'A'.code.toByte(), 0x80.toByte())
        assertEquals("A".repeat(128), String(Filters.runLength(run, limit = 128), Charsets.ISO_8859_1))
        assertThrows(PreviewLimitException::class.java) { Filters.runLength(run, limit = 127) }
        val lzw = byteArrayOf(0x80.toByte(), 0x0B, 0x60, 0x50, 0x22, 0x0C, 0x0C, 0x85.toByte(), 0x01)
        assertEquals("-----A---B", String(Filters.lzw(lzw, 1, limit = 10), Charsets.ISO_8859_1))
        assertThrows(PreviewLimitException::class.java) { Filters.lzw(lzw, 1, limit = 9) }
        assertArrayEquals(ByteArray(16), Filters.ascii85("zzzz~>".toByteArray(), limit = 16))
        assertThrows(PreviewLimitException::class.java) { Filters.ascii85("zzzz~>".toByteArray(), limit = 15) }
        // 4 MB of run-length pairs stand for 256 MB, and 75 KB of LZW for about 200 MB: both stop at the limit.
        val runs = ByteArray(4 * 1024 * 1024) { if (it % 2 == 0) 0x81.toByte() else 0x20 }
        assertThrows(PreviewLimitException::class.java) { Filters.runLength(runs, limit = 1024 * 1024) }
        assertThrows(PreviewLimitException::class.java) { Filters.lzw(lzwBomb(50_000), 1, limit = 1024 * 1024) }
        // A stream within the limit decodes as before.
        assertEquals(128 * 1000, Filters.runLength(runs.copyOf(2000), limit = 1024 * 1024).size)
    }

    @Test(timeout = 60_000) fun pagesSharingAStreamPastItsLimitSpendTheBudgetInsteadOfDecodingItAgain() {
        // 600 KB of run-length pairs, about a kilobyte once deflated, stand for 38 MB: past the 32 MB one stream may give.
        // Each page that tries it counts that much against the budget (64 MB here), so the third one is not decoded,
        // and neither is the page after it.
        val runs = ByteArray(600_000) { if (it % 2 == 0) 0x81.toByte() else 0x20 }
        val bombs = 3
        val file = pdf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [${(0 until bombs + 2).joinToString(" ") { "${6 + it} 0 R" }}] /Count ${bombs + 2} >>",
            content("BT /F1 12 Tf 72 700 Td (Hi) Tj ET"),
            stream(deflate(runs), "[/FlateDecode /RunLengthDecode]"),
            content("BT /F1 12 Tf 72 700 Td (Bye) Tj ET"),
            page(3), *Array(bombs) { page(4) }, page(5),
        )
        assertEquals("Hi", PdfText.text(file, budget = 64L * 1024 * 1024))
    }

    @Test(timeout = 60_000) fun aPageListingOneLargeStreamManyTimesHoldsAtMostOneStreamLimit() {
        // About 20 KB of deflated spaces stand for 20 MB. The page lists that stream three times between two texts: its
        // program stops before passing the 32 MB one page may hold, so the text after the filler is left out.
        val filler = deflate(ByteArray(20 * 1024 * 1024) { 0x20 })
        val file = pdf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Resources << /Font << >> >> /Contents [4 0 R 5 0 R 5 0 R 5 0 R 6 0 R] >>",
            content("BT /F1 12 Tf 72 700 Td (Hi) Tj ET"),
            stream(filler, "/FlateDecode"),
            content("BT /F1 12 Tf 72 700 Td (Bye) Tj ET"),
        )
        assertEquals("Hi", PdfText.text(file))
    }

    @Test fun aClearCodeForgetsTheLearnedEntriesOnly() {
        // A, B, then 258 (the learned "AB"); after the clear code 258 is unknown again, so the stream ends there.
        val codes = listOf(65, 66, 258, 256, 258, 257)
        val out = ByteArrayOutputStream()
        var buffer = 0L
        var buffered = 0
        codes.forEach { code ->
            buffer = (buffer shl 9) or code.toLong(); buffered += 9
            while (buffered >= 8) { out.write((buffer shr (buffered - 8)).toInt() and 0xFF); buffered -= 8 }
        }
        if (buffered > 0) out.write((buffer shl (8 - buffered)).toInt() and 0xFF)
        assertEquals("ABAB", String(Filters.lzw(out.toByteArray(), 1), Charsets.ISO_8859_1))
    }

    private fun type0(cmap: String, widths: String) = arrayOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        page(4, "/F1 5 0 R"),
        content("BT /F1 10 Tf 72 700 Td <00410042> Tj ET"),
        "<< /Type /Font /Subtype /Type0 /BaseFont /X /Encoding /Identity-H /ToUnicode 6 0 R /DescendantFonts [7 0 R] >>",
        stream(deflate(cmap.toByteArray(Charsets.ISO_8859_1)), "/FlateDecode"),
        "<< /Type /Font /Subtype /CIDFontType2 /DW 1000 /W [$widths] >>",
    )

    @Test(timeout = 30_000) fun aToUnicodeMapRepeatingTheWholeRangeIsReadWithinItsLimit() {
        // 100 000 copies of the whole two-byte range in one block, a few kilobytes deflated: 6.5 billion entries to write,
        // all to the same 65 536 codes, so the map never reaches its own size limit.
        val cmap = "1 begincodespacerange <0000> <FFFF> endcodespacerange 100000 beginbfrange " +
            "<0000> <FFFF> <0000> ".repeat(100_000) + "endbfrange"
        assertEquals("AB", PdfText.text(pdf(*type0(cmap, "65 [600 600]"))))
    }

    @Test(timeout = 30_000) fun aWidthArrayRepeatingOneRangeIsReadWithinItsLimit() {
        // "0 65534 500" again and again: 65 535 widths per range, 6.5 billion in all, while the map stays below 65 536 codes.
        val cmap = "1 begincodespacerange <0000> <FFFF> endcodespacerange 1 beginbfrange <0041> <0042> <0041> endbfrange"
        assertEquals("AB", PdfText.text(pdf(*type0(cmap, "0 65534 500 ".repeat(100_000)))))
    }

    @Test(timeout = 30_000) fun aLongRunOfShownSpacesIsCleanedUpInOnePass() {
        // Half a million spaces shown before a letter: the old clean-up regex tried the run again from each of its spaces.
        // Blanks before a line break still go.
        val spaces = " ".repeat(500_000)
        val file = pdf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            page(4),
            content("BT /F1 12 Tf 72 700 Td (Hi${spaces}x) Tj 0 -20 Td (Next   ) Tj 0 -20 Td (Last) Tj ET"),
        )
        assertEquals("Hi${spaces}x\nNext\nLast", PdfText.text(file))
    }

    @Test(timeout = 30_000) fun aStringThatNeverClosesIsScannedOnce() {
        val objects = arrayOf(
            "<< /Type /Catalog /Pages 3 0 R >>",
            "<< /Type /Pages /Kids [4 0 R] /Count 1 >>",
            page(5),
            content("BT /F1 12 Tf 72 700 Td (Hi) Tj ET"),
        )
        // A damaged object's string ends at its "endobj": the objects after it are still found.
        assertEquals("Hi", PdfText.text(pdf("<< /Title (never closed >>", *objects)))
        // 2 MB of "99 0 obj (" that never close: the scan read to the end of the file again from every "obj" in them.
        val hostile = pdf("<< /Producer (x) >>", *objects) + "99 0 obj (".repeat(200_000).toByteArray(Charsets.ISO_8859_1)
        assertEquals("Hi", PdfText.text(hostile))
    }
}
