package com.univerflow.kaznu.preview

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val FREE = -1
private const val END = -2
private const val FAT_SECTOR = -3
/** Where the synthetic Word documents keep their characters in the WordDocument stream. */
private const val TEXT_AT = 1024
private val SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

/**
 * Small files built to expand into huge structures: the readers stop or refuse them within memory and time, and the
 * text they do give the search stays what it was.
 */
class HostileDocumentsTest {
    private fun ByteBuffer.short(at: Int, value: Int) { putShort(at, value.toShort()) }

    /** The compound file header: [fatSectors] as listed in the header, the directory's first sector and the mini FAT. */
    private fun header(file: ByteBuffer, shift: Int, fatSectors: List<Int>, directory: Int, miniCutoff: Int, miniFat: Int = END, miniFatCount: Int = 0) {
        SIGNATURE.forEachIndexed { index, byte -> file.put(index, byte) }
        file.short(0x18, 0x3E)
        file.short(0x1A, if (shift == 12) 4 else 3)
        file.short(0x1C, 0xFFFE)
        file.short(0x1E, shift)
        file.short(0x20, 6)
        file.putInt(0x2C, fatSectors.size)
        file.putInt(0x30, directory)
        file.putInt(0x38, miniCutoff)
        file.putInt(0x3C, miniFat)
        file.putInt(0x40, miniFatCount)
        file.putInt(0x44, END)
        for (index in 0 until 109) file.putInt(0x4C + index * 4, fatSectors.getOrElse(index) { FREE })
    }

    private fun entry(file: ByteBuffer, at: Int, name: String, type: Int, start: Int, size: Int, child: Int = FREE, right: Int = FREE) {
        name.forEachIndexed { index, char -> file.putChar(at + index * 2, char) }
        file.short(at + 0x40, (name.length + 1) * 2)
        file.put(at + 0x42, type.toByte())
        file.putInt(at + 0x44, FREE)
        file.putInt(at + 0x48, right)
        file.putInt(at + 0x4C, child)
        file.putInt(at + 0x74, start)
        file.putInt(at + 0x78, size)
    }

    /** A compound file holding [streams] in consecutive 512-byte sectors; nothing goes to the mini stream (cutoff 0). */
    private fun compound(streams: Map<String, ByteArray>): ByteArray {
        val size = 512
        fun sectors(bytes: Int) = (bytes + size - 1) / size
        val directorySectors = sectors((streams.size + 1) * 128)
        val dataSectors = streams.values.sumOf { sectors(it.size) }
        var fatSectors = 1
        while (fatSectors * size / 4 < fatSectors + directorySectors + dataSectors) fatSectors++
        require(fatSectors <= 109)
        val fat = IntArray(fatSectors * size / 4) { FREE }
        for (index in 0 until fatSectors) fat[index] = FAT_SECTOR
        fun chain(first: Int, count: Int): Int {
            for (index in 0 until count) fat[first + index] = if (index == count - 1) END else first + index + 1
            return if (count > 0) first else END
        }
        val directory = chain(fatSectors, directorySectors)
        var next = fatSectors + directorySectors
        val starts = streams.values.map { bytes -> chain(next, sectors(bytes.size)).also { next += sectors(bytes.size) } }
        val file = ByteBuffer.allocate(size * (1 + next)).order(ByteOrder.LITTLE_ENDIAN)
        header(file, shift = 9, fatSectors = List(fatSectors) { it }, directory = directory, miniCutoff = 0)
        fat.forEachIndexed { index, value -> file.putInt(size + index * 4, value) }
        val base = size * (1 + directory)
        entry(file, base, "Root Entry", type = 5, start = END, size = 0, child = 1)
        streams.entries.forEachIndexed { index, (name, bytes) ->
            entry(file, base + (index + 1) * 128, name, type = 2, start = starts[index], size = bytes.size,
                right = if (index + 1 < streams.size) index + 2 else FREE)
            if (bytes.isNotEmpty()) { file.position(size * (1 + starts[index])); file.put(bytes) }
        }
        return file.array()
    }

    /**
     * 4096-byte sectors and a FAT of 48 sectors that are all sector 0, where sector 1 points to itself: the directory
     * starts there, or ([miniFatLoops]) the mini FAT does.
     */
    private fun loopingCompound(miniFatLoops: Boolean): ByteArray {
        val size = 4096
        val file = ByteBuffer.allocate(size * 49).order(ByteOrder.LITTLE_ENDIAN)
        header(file, shift = 12, fatSectors = List(48) { 0 }, directory = if (miniFatLoops) 2 else 1, miniCutoff = 4096,
            miniFat = if (miniFatLoops) 1 else END, miniFatCount = if (miniFatLoops) 1 else 0)
        for (slot in 0 until size / 4) file.putInt(size + slot * 4, FREE)
        file.putInt(size, FAT_SECTOR)
        file.putInt(size + 4, 1)
        file.putInt(size + 8, END)
        entry(file, size * 3, "Root Entry", type = 5, start = END, size = 0)
        return file.array()
    }

    /** Counts the bytes a reader takes from the file. */
    private class CountingSource(private val bytes: ByteArray) : ByteSource {
        var taken = 0L
        override val size: Long get() = bytes.size.toLong()
        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
            ArraySource(bytes).read(position, buffer, offset, length).also { taken += it }
    }

    /**
     * A Word 97 document of [length] characters. Its WordDocument stream has [body] (8-bit characters) at [TEXT_AT];
     * the piece table maps the characters from each of [cps] to the next to the bytes at [offsets]. [chpx] is a page of
     * character properties for [body], [data] the Data stream.
     */
    private fun doc(body: ByteArray, cps: IntArray, offsets: IntArray, length: Int, chpx: ByteArray? = null, data: ByteArray? = null, slots: Int = 1): ByteArray {
        val pageAt = (TEXT_AT + body.size + 511) / 512 * 512
        val word = ByteBuffer.allocate(pageAt + (chpx?.size ?: 0)).order(ByteOrder.LITTLE_ENDIAN)
        word.short(0, 0xA5EC) // wIdent
        word.short(2, 0xC1) // nFib: Word 97
        word.short(32, 14) // csw: 14 16-bit values follow
        word.short(62, 22) // cslw: 22 32-bit values follow, from 64
        word.putInt(64 + 3 * 4, length) // ccpText
        word.short(152, 93) // cbRgFcLcb: (fc, lcb) pairs follow, from 154
        word.position(TEXT_AT)
        word.put(body)
        val count = offsets.size
        val clx = ByteBuffer.allocate(5 + (count + 1) * 4 + count * 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(2.toByte())
            putInt((count + 1) * 4 + count * 8)
            cps.forEach { putInt(it) }
            // Compressed (8-bit) pieces: the byte offset doubled, with bit 30 set.
            offsets.forEach { putShort(0.toShort()); putInt(0x40000000 or (it * 2)); putShort(0.toShort()) }
        }.array()
        word.putInt(154 + 33 * 8, 0) // fcClx
        word.putInt(158 + 33 * 8, clx.size)
        var table = clx
        if (chpx != null) {
            word.position(pageAt)
            word.put(chpx)
            // [slots] entries of the page table, every one naming the page of [chpx].
            val bte = ByteBuffer.allocate((slots + 1) * 4 + slots * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(slots) { putInt(TEXT_AT) }
                putInt(TEXT_AT + body.size)
                repeat(slots) { putInt(pageAt / 512) }
            }.array()
            word.putInt(154 + 12 * 8, clx.size) // fcPlcfBteChpx
            word.putInt(158 + 12 * 8, bte.size)
            table = clx + bte
        }
        val streams = linkedMapOf("WordDocument" to word.array(), "0Table" to table)
        if (data != null) streams["Data"] = data
        return compound(streams)
    }

    /** A page with one run of character properties over bytes [from] to [to]: special characters showing the picture at [location]. */
    private fun pictureRun(from: Int, to: Int, location: Int): ByteArray = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0, from)
        putInt(4, to)
        put(8, 0x80.toByte()) // The run's properties are at word 0x80 (byte 0x100).
        put(0x100, 9.toByte()) // sprmCFSpec (3 bytes) and sprmCPicLocation (6 bytes)
        short(0x101, 0x0855)
        put(0x103, 1.toByte())
        short(0x104, 0x6A03)
        putInt(0x106, location)
        put(511, 1.toByte()) // One run on the page.
    }.array()

    /** A Data stream holding one picture record at offset 0: its PICF header, then a PNG BLIP of [png]. */
    private fun pictureData(png: ByteArray): ByteArray {
        val total = 0x44 + 8 + 17 + png.size
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, total)
            short(4, 0x44) // cbHeader
            short(6, 0x64) // MM_SHAPE
            short(0x44, 0x6E0 shl 4) // A PNG BLIP with one UID
            short(0x46, 0xF01E)
            putInt(0x48, 17 + png.size)
            position(0x44 + 8 + 17)
            put(png)
        }.array()
    }

    @Test fun loopingChainsInACompoundFileFailWithoutReadingItOverAndOver() {
        // A 200 KB file: before, the loop was followed once for every FAT entry, 192 MB of reads.
        listOf(false, true).forEach { miniFatLoops ->
            val file = loopingCompound(miniFatLoops)
            val source = CountingSource(file)
            assertThrows(UnsupportedDocumentException::class.java) { CompoundFile(source) }
            assertTrue("${source.taken} bytes read", source.taken < 3L * file.size)
        }
        assertThrows(UnsupportedDocumentException::class.java) { TextExtract.text(loopingCompound(false), "loop.doc") }
        // A chain through sectors up to the end of the file still reads.
        val stream = ByteArray(5000) { (it % 251).toByte() }
        val compound = CompoundFile(compound(mapOf("Stream" to stream)))
        assertArrayEquals(stream, compound.stream("Stream"))
        assertArrayEquals(stream, compound.reader("Stream")!!.read(0, stream.size))
    }

    @Test(timeout = 30_000) fun deeplyNestedFieldsDoNotSlowTheDocReaderDown() {
        // 4 KB of field begin and separator marks, then 4 KB of text, each shown by 245 pieces: 500 000 open fields,
        // then a million characters of their result.
        val pieces = 245
        val body = ByteArray(8192) { if (it >= 4096) 'a'.code.toByte() else if (it % 2 == 0) 0x13 else 0x14 }
        val cps = IntArray(2 * pieces + 1) { it * 4096 }
        val file = doc(body, cps, IntArray(2 * pieces) { if (it < pieces) TEXT_AT else TEXT_AT + 4096 }, length = cps.last())
        assertEquals("a".repeat(TextExtract.LIMIT), TextExtract.text(file, "fields.doc"))
    }

    @Test fun millionsOfTinyParagraphsStopAtTheLimitInsteadOfFillingMemory() {
        // 4 KB of "a" and paragraph marks shown by 976 pieces: two million paragraphs from a 20 KB file.
        val pieces = 976
        val body = ByteArray(4096) { if (it % 2 == 0) 'a'.code.toByte() else 0x0D }
        val cps = IntArray(pieces + 1) { it * 4096 }
        val file = doc(body, cps, IntArray(pieces) { TEXT_AT }, length = cps.last())
        assertThrows(PreviewLimitException::class.java) { WordView.html(file) }
        // The search keeps the paragraphs read up to the limit.
        val text = TextExtract.text(file, "paragraphs.doc")
        assertTrue(text.length.toString(), text.length in 100_000 until TextExtract.LIMIT)
        assertTrue(text.split('\n').all { it == "a" })
    }

    @Test(timeout = 20_000) fun overlappingPiecesCopyTheirCharactersOnce() {
        // 10 000 pieces cover the same two million characters (every other piece is empty).
        val length = 2_000_000
        val cps = IntArray(20_001) { if (it % 2 == 0) 0 else length }
        val file = doc(ByteArray(length) { 'a'.code.toByte() }, cps, IntArray(20_000) { TEXT_AT }, length = length)
        assertEquals("a".repeat(TextExtract.LIMIT), TextExtract.text(file, "pieces.doc"))
    }

    @Test fun aRunOfPictureCharactersReadsItsPictureOnce() {
        val png = ByteArray(64 * 1024) { (it % 253).toByte() }
        val count = 100_000
        val file = doc(ByteArray(count) { 0x01 }, intArrayOf(0, count), intArrayOf(TEXT_AT), length = count,
            chpx = pictureRun(TEXT_AT, TEXT_AT + count, location = 0), data = pictureData(png))
        val stored = mutableListOf<ByteArray>()
        val media = object : PreviewMedia {
            override fun add(type: String, bytes: ByteArray): String { stored += bytes; return "media:0" }
        }
        val html = WordBinary.html(file, {}, media)
        assertArrayEquals(png, stored.single())
        assertEquals(count, Regex("src=\"media:0\"").findAll(html).count())
    }

    @Test fun anRtfOfMillionsOfParagraphMarksStopsAtTheLimit() {
        // "a", then a backslash and a line end (a paragraph mark): three bytes a paragraph, three million of them.
        val rtf = ("{\\rtf1 " + "a\\\n".repeat(3_000_000) + "}").toByteArray(Charsets.ISO_8859_1)
        assertThrows(PreviewLimitException::class.java) { WordView.html(rtf) }
        val text = TextExtract.text(rtf, "marks.rtf")
        assertTrue(text.length.toString(), text.length in 100_000 until TextExtract.LIMIT)
        assertTrue(text.split('\n').all { it == "a" })
    }

    @Test fun aLongRtfStillGivesTheSearchItsFirstMillionCharacters() {
        val lines = (1..300_000).map { "line $it" }
        val rtf = ("{\\rtf1\\ansi " + lines.joinToString("\\par\n") + "}").toByteArray(Charsets.ISO_8859_1)
        assertEquals(lines.joinToString("\n").take(TextExtract.LIMIT), TextExtract.text(rtf, "long.rtf"))
    }

    @Test fun rtfInChineseJapaneseAndKoreanCodePagesKeepsItsDoubleByteCharacters() {
        fun text(rtf: String) = TextExtract.text(rtf.toByteArray(Charsets.ISO_8859_1), "cjk.rtf")
        // WordPad on a Chinese Windows: GBK bytes as \'hh, with no Unicode values.
        assertEquals("中文 abc", text("{\\rtf1\\ansi\\ansicpg936{\\fonttbl{\\f0\\fnil\\fcharset134 SimSun;}}\\f0 \\'d6\\'d0\\'ce\\'c4 abc\\par}"))
        // Shift-JIS from the font's character set; the second byte of 本 is "{", escaped.
        assertEquals("日本", text("{\\rtf1\\ansi\\ansicpg1252{\\fonttbl{\\f1\\fnil\\fcharset128 MS Mincho;}}{\\f1 \\'93\\'fa\\'96\\'7b}}"))
        // Korean as raw bytes, and a GBK character whose second byte is a plain "@".
        assertEquals("한국", text("{\\rtf1\\ansi\\ansicpg949 \u00C7\u00D1\u00B1\u00B9}"))
        assertEquals("丂", text("{\\rtf1\\ansi\\ansicpg936 \\'81@}"))
        // The fallback after a Unicode value is counted in bytes: \uc2 drops both bytes of one character.
        assertEquals("中文", text("{\\rtf1\\ansi\\ansicpg936\\uc2\\u20013\\'d6\\'d0\\'ce\\'c4}"))
    }

    @Test(timeout = 60_000) fun aPageOfPropertiesNamedByEverySlotIsReadOnce() {
        // The most runs a 512-byte page holds, and 400 000 slots of the page table all naming that page.
        val page = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (run in 0..101) putInt(run * 4, TEXT_AT + run)
            put(511, 101.toByte())
        }.array()
        val file = doc(ByteArray(200) { 'x'.code.toByte() }, intArrayOf(0, 200), intArrayOf(TEXT_AT), length = 200, chpx = page, slots = 400_000)
        assertTrue(WordBinary.html(file, {}, PreviewMedia.inline()).contains("x".repeat(50)))
    }

    @Test(timeout = 10_000) fun editsNamingOneHugePersistDirectoryReadItOnce() {
        val edits = 1000
        val directorySize = 6 * 1024 * 1024
        val directoryAt = (edits + 1) * 36
        val stream = ByteBuffer.allocate(directoryAt + 8 + directorySize).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 1..edits) {
            val at = index * 36
            stream.short(at + 2, 0x0FF5) // UserEditAtom, 28 bytes
            stream.putInt(at + 4, 28)
            stream.putInt(at + 8 + 8, if (index < edits) at + 36 else 0) // the edit before this one
            stream.putInt(at + 8 + 12, directoryAt)
            stream.putInt(at + 8 + 16, 1) // the document's persist id
        }
        stream.short(directoryAt + 2, 0x1772) // PersistDirectoryAtom: entries of 4095 ids each
        stream.putInt(directoryAt + 4, directorySize)
        var at = directoryAt + 8
        var first = 1
        while (at + 4 + 4095 * 4 <= directoryAt + 8 + directorySize) {
            stream.putInt(at, (4095 shl 20) or (first and 0xFFFFF))
            at += 4 + 4095 * 4
            first += 4095
        }
        val user = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 36).array()
        val file = compound(linkedMapOf("PowerPoint Document" to stream.array(), "Current User" to user))
        // Every id leads to offset 0, which is no document: the file is refused, and at once.
        try { PptReader.read(CompoundFile(file)); fail("A presentation without a document was read") }
        catch (_: UnsupportedDocumentException) { }
    }

    private fun zip(vararg parts: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            parts.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
        }
    }.toByteArray()

    private fun slide(text: String) = "<sld><p><t>$text</t></p></sld>".toByteArray()

    @Test fun packagePartsGiveTheirTextInSlideOrderWhateverTheirOrderInTheFile() {
        val blank = "<sld>${" ".repeat(1_000_000)}</sld>".toByteArray()
        assertEquals("A\nnote\nB\nC\nJ", TextExtract.text(zip("ppt/slides/slide3.xml" to slide("C"), "ppt/slides/slide10.xml" to slide("J"),
            "ppt/notesSlides/notesSlide2.xml" to blank, "ppt/slides/slide2.xml" to slide("B"), "ppt/notesSlides/notesSlide1.xml" to slide("note"),
            "ppt/slides/slide1.xml" to slide("A")), "deck.pptx"))
        // A slide longer than the limit comes before slides read earlier, and after ones read later.
        val long = "x".repeat(TextExtract.LIMIT + 200_000)
        val file = zip("ppt/slides/slide3.xml" to slide("C"), "ppt/slides/slide2.xml" to slide(long), "ppt/slides/slide10.xml" to slide("J"),
            "ppt/notesSlides/notesSlide1.xml" to slide("note"), "ppt/slides/slide1.xml" to slide("A"))
        assertEquals("A\nnote\n$long".take(TextExtract.LIMIT), TextExtract.text(file, "deck.pptx"))
    }

    @Test(timeout = 60_000) fun aPackageOfHugePartsIsNotInflatedIntoMemory() {
        // The first slide fills the search's limit; 36 more slides of 16 MB of blanks (16 KB each in the file) would
        // hold 576 MB if every part were kept until the end.
        val blanks = "<sld>${" ".repeat(16_000_000)}</sld>".toByteArray()
        val parts = listOf("ppt/slides/slide1.xml" to slide("x".repeat(TextExtract.LIMIT))) + (2..37).map { "ppt/slides/slide$it.xml" to blanks }
        assertEquals("x".repeat(TextExtract.LIMIT), TextExtract.text(zip(*parts.toTypedArray()), "deck.pptx"))
    }
}
