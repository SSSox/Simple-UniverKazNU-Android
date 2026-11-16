package com.univerflow.kaznu.preview

import com.univerflow.kaznu.preview.WordFlow.Item

/**
 * Word 97–2003 .doc ([MS-DOC]) in the same reading view as .docx: text through the piece table, formatting from the
 * FKP pages. Encrypted files and Word 6/95 are refused.
 */
internal object WordBinary {
    private const val CHAR_LIMIT = 4_000_000
    private const val PICTURE_LIMIT = 32 * 1024 * 1024
    /** Paragraphs and their items kept for one document: as objects, marks cost far more than as characters. */
    private const val FLOW_LIMIT = 500_000
    /** Fields nested deeper than this are passed over: only their marks are counted. */
    private const val FIELD_DEPTH = 64

    /** The body of the reading view, drawn by [WordHtml] like .docx. */
    fun html(bytes: ByteArray, checkActive: () -> Unit = {}, media: PreviewMedia = PreviewMedia.inline()): String =
        html(CompoundFile(bytes), checkActive, media)

    /** The same from a compound file read in place: pictures come from the Data stream by position, not a copy of it. */
    fun html(file: CompoundFile, checkActive: () -> Unit = {}, media: PreviewMedia = PreviewMedia.inline()): String {
        val document = Document(file, checkActive, media)
        return WordFlow.html(document.paragraphs(document.mainLength))
    }

    /** Plain text for the search: the body, then footnotes, headers, comments, endnotes and text boxes. */
    fun text(bytes: ByteArray, checkActive: () -> Unit = {}): String {
        val document = Document(CompoundFile(bytes), checkActive, null)
        return WordFlow.text(document.paragraphs(document.allLength, forText = true))
    }

    private data class CharProps(
        val style: RunStyle = RunStyle(), val hidden: Boolean = false, val deleted: Boolean = false,
        val special: Boolean = false, val picture: Int = -1, val data: Boolean = false,
    )

    private class Range<T>(val start: Int, val end: Int, val value: T)

    private class Field(val instruction: StringBuilder = StringBuilder(), var result: Boolean = false, var link: Boolean = false)

    private class Document(file: CompoundFile, private val checkActive: () -> Unit, private val media: PreviewMedia?) {
        private val word = file.stream("WordDocument") ?: throw UnsupportedDocumentException("No WordDocument stream")
        private val fib = LittleEndian(word)
        private val table: LittleEndian
        private val data = if (media != null) file.reader("Data") else null
        private val rgLw: Int
        private val rgFcLcb: Int
        private val fcLcbCount: Int
        private val text: CharArray
        private val positions: IntArray
        private val headings: Map<Int, Int>
        private val characters: List<Range<CharProps>>
        private val paragraphProps: List<Range<WordFlow.Props>>
        val mainLength: Int
        val allLength: Int

        init {
            if (fib.u16(0) != 0xA5EC) throw UnsupportedDocumentException("Not a Word document")
            if (fib.u16(2) < 0xC0) throw UnsupportedDocumentException("Word 6/95 document")
            val flags = fib.u16(0x0A)
            if (flags and 0x0100 != 0 || flags and 0x8000 != 0) throw UnsupportedDocumentException("Encrypted document")
            table = LittleEndian(file.stream(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: throw UnsupportedDocumentException("No table stream"))
            // FIB: FibBase, then counted arrays of 16-bit values, 32-bit values (lengths) and (fc, lcb) pairs.
            val csw = fib.u16(32)
            rgLw = 34 + csw * 2 + 2
            val cslw = fib.u16(rgLw - 2)
            fcLcbCount = fib.u16(rgLw + cslw * 4)
            rgFcLcb = rgLw + cslw * 4 + 2
            // ccpText, ccpFtn, ccpHdd, ccpAtn, ccpEdn, ccpTxbx, ccpHdrTxbx (index 6 is reserved).
            val counts = (3..10).filter { it != 6 }.map { fib.i32(rgLw + it * 4).coerceAtLeast(0) }
            mainLength = counts[0]
            val subdocuments = counts.drop(1).sum()
            allLength = mainLength + subdocuments + if (subdocuments > 0) 1 else 0
            if (allLength > CHAR_LIMIT) throw PreviewLimitException()
            text = CharArray(allLength)
            positions = IntArray(allLength) { -1 }
            readPieces()
            headings = readStyles()
            characters = readCharacters()
            paragraphProps = readParagraphs()
        }

        private fun fcLcb(index: Int): Pair<Int, Int>? {
            if (index >= fcLcbCount) return null
            val fc = fib.i32(rgFcLcb + index * 8)
            val lcb = fib.i32(rgFcLcb + index * 8 + 4)
            return if (lcb > 0 && table.has(fc, lcb)) fc to lcb else null
        }

        /** The piece table (Clx) maps character positions to bytes: 8-bit Windows-1252 or UTF-16 pieces. */
        private fun readPieces() {
            val (fc, lcb) = fcLcb(CLX) ?: throw UnsupportedDocumentException("No piece table")
            var position = fc
            while (position < fc + lcb) {
                when (table.u8(position)) {
                    1 -> position += 3 + table.i16(position + 1).coerceAtLeast(0)
                    2 -> {
                        val size = table.i32(position + 1)
                        val start = position + 5
                        val count = (size - 4) / 12
                        if (count <= 0 || !table.has(start, size)) throw UnsupportedDocumentException("Bad piece table")
                        // Pieces follow one another. One that starts before the end of those already read adds only
                        // what lies past them, so overlapping pieces copy each character once.
                        var covered = 0
                        for (index in 0 until count) {
                            val cpStart = table.i32(start + index * 4).coerceAtLeast(0)
                            val cpEnd = table.i32(start + (index + 1) * 4).coerceAtMost(allLength)
                            val raw = table.u32(start + (count + 1) * 4 + index * 8 + 2)
                            val compressed = raw and 0x40000000L != 0L
                            val offset = (raw and 0x3FFFFFFFL).let { if (compressed) it / 2 else it }.toInt()
                            for (cp in maxOf(cpStart, covered) until cpEnd) {
                                val at = if (compressed) offset + (cp - cpStart) else offset + (cp - cpStart) * 2
                                if (!fib.has(at, if (compressed) 1 else 2)) break
                                text[cp] = if (compressed) WINDOWS_1252[fib.u8(at)] else fib.u16(at).toChar()
                                positions[cp] = at
                            }
                            covered = maxOf(covered, cpEnd)
                            if (index % 256 == 0) checkActive()
                        }
                        return
                    }
                    else -> throw UnsupportedDocumentException("Bad piece table")
                }
            }
            throw UnsupportedDocumentException("No pieces")
        }

        /** Formatted-disk pages: a Plc of page numbers, each 512-byte page listing byte ranges and their properties. */
        private fun <T> readPages(index: Int, parse: (page: Int, entry: Int, runs: Int) -> T): List<Range<T>> {
            val (fc, lcb) = fcLcb(index) ?: return emptyList()
            val count = (lcb - 4) / 8
            val result = mutableListOf<Range<T>>()
            val read = HashSet<Int>()
            for (slot in 0 until count) {
                val page = (table.i32(fc + (count + 1) * 4 + slot * 4) and 0x3FFFFF) * 512
                // A page holds each of its ranges once, however many slots of a damaged file name it.
                if (!fib.has(page, 512) || !read.add(page)) continue
                val runs = fib.u8(page + 511)
                for (entry in 0 until runs) {
                    val value = runCatching { parse(page, entry, runs) }.getOrNull() ?: continue
                    result += Range(fib.i32(page + entry * 4), fib.i32(page + (entry + 1) * 4), value)
                }
                if (slot % 64 == 0) checkActive()
            }
            return result.sortedBy { it.start }
        }

        private fun readCharacters() = readPages(PLCF_BTE_CHPX) { page, entry, runs ->
            val offset = fib.u8(page + (runs + 1) * 4 + entry) * 2
            var props = CharProps()
            if (offset != 0) sprms(fib, page + offset + 1, fib.u8(page + offset)) { sprm, operand ->
                val toggle = { fib.u8(operand).let { it == 1 || it == 0x81 } }
                props = when (sprm) {
                    0x0835 -> props.copy(style = props.style.copy(bold = toggle()))
                    0x0836 -> props.copy(style = props.style.copy(italic = toggle()))
                    0x0837, 0x2A53 -> props.copy(style = props.style.copy(strike = toggle()))
                    0x2A3E -> props.copy(style = props.style.copy(underline = fib.u8(operand) != 0))
                    0x2A48 -> props.copy(style = props.style.copy(vertical = when (fib.u8(operand)) {
                        1 -> RunStyle.Vertical.SUPER; 2 -> RunStyle.Vertical.SUB; else -> RunStyle.Vertical.NONE }))
                    0x083C, 0x0802 -> props.copy(hidden = toggle())
                    0x0800 -> props.copy(deleted = toggle())
                    0x0855 -> props.copy(special = toggle())
                    0x0806 -> props.copy(data = toggle())
                    0x6A03 -> props.copy(picture = fib.i32(operand))
                    else -> props
                }
            }
            props
        }

        private fun readParagraphs() = readPages(PLCF_BTE_PAPX) { page, entry, runs ->
            val offset = fib.u8(page + (runs + 1) * 4 + entry * 13) * 2
            if (offset == 0) return@readPages WordFlow.Props()
            // PapxInFkp: a byte count (or a zero and then the count), the style index and the sprms.
            var at = page + offset
            var size = fib.u8(at) * 2 - 1
            at += 1
            if (size < 0) { size = fib.u8(at) * 2; at += 1 }
            if (size < 2) return@readPages WordFlow.Props()
            var props = WordFlow.Props(heading = headings[fib.u16(at)] ?: 0)
            sprms(fib, at + 2, size - 2) { sprm, operand ->
                props = when (sprm) {
                    0x2416 -> props.copy(inTable = fib.u8(operand) != 0)
                    0x2417 -> props.copy(rowEnd = fib.u8(operand) != 0)
                    0x6649 -> props.copy(depth = fib.i32(operand).coerceIn(0, 64))
                    0x244C -> props.copy(innerRowEnd = fib.u8(operand) != 0)
                    0x2403, 0x2461 -> props.copy(align = when (fib.u8(operand)) { 1 -> Align.CENTER; 2 -> Align.END; 3, 4 -> Align.JUSTIFY; else -> null })
                    0x460B -> props.copy(listItem = fib.i16(operand) != 0)
                    0xD608 -> props.copy(row = rowLayout(operand))
                    else -> props
                }
            }
            props
        }

        /** sprmTDefTable: cell boundaries in twips and each cell's merge flags (TC80). */
        private fun rowLayout(operand: Int): WordFlow.RowLayout? {
            val size = fib.u16(operand) - 1
            val cells = fib.u8(operand + 2)
            if (cells !in 1..63 || size < 1 + (cells + 1) * 2) return null
            val bounds = IntArray(cells + 1) { fib.i16(operand + 3 + it * 2) }
            val flags = (0 until cells).map { cell ->
                val at = operand + 3 + (cells + 1) * 2 + cell * 20
                if (at + 2 <= operand + 2 + size) fib.u16(at) else 0
            }
            return WordFlow.RowLayout(bounds,
                merges = flags.map { when ((it shr 5) and 3) { 3 -> Merge.RESTART; 1 -> Merge.CONTINUE; else -> Merge.NONE } },
                covered = flags.map { it and 3 >= 2 })
        }

        /** Heading levels by style index, from each style's built-in identifier (sti 1–9 heading, 62 title). */
        private fun readStyles(): Map<Int, Int> = runCatching {
            val (fc, _) = fcLcb(STSHF) ?: return@runCatching null
            val header = table.u16(fc)
            val count = table.u16(fc + 2)
            var at = fc + 2 + header
            val levels = mutableMapOf<Int, Int>()
            for (istd in 0 until count.coerceAtMost(4096)) {
                val size = table.u16(at)
                if (size >= 2) {
                    val sti = table.u16(at + 2) and 0x0FFF
                    if (sti in 1..9) levels[istd] = sti.coerceAtMost(6) else if (sti == 62) levels[istd] = 1
                }
                at += 2 + size
            }
            levels
        }.getOrNull() ?: (1..9).associateWith { it.coerceAtMost(6) }

        private fun <T> find(ranges: List<Range<T>>, position: Int): T? {
            if (position < 0) return null
            var low = 0
            var high = ranges.size - 1
            while (low <= high) {
                val middle = (low + high) ushr 1
                val range = ranges[middle]
                when {
                    position < range.start -> high = middle - 1
                    position >= range.end -> low = middle + 1
                    else -> return range.value
                }
            }
            return null
        }

        /**
         * Splits the first [length] characters into paragraphs; field codes are dropped, their results kept. Past
         * [FLOW_LIMIT] paragraphs and items the view is refused, while the search ([forText]) keeps what it has; the
         * search also stops at [TextExtract.LIMIT] characters that are not blank, since it keeps no more than that.
         */
        fun paragraphs(length: Int, forText: Boolean = false): List<WordFlow.Paragraph> {
            val result = mutableListOf<WordFlow.Paragraph>()
            var items = mutableListOf<Item>()
            val run = StringBuilder()
            var runStyle = RunStyle()
            val fields = ArrayDeque<Field>()
            // The innermost field still in its instruction, and the fields nested past FIELD_DEPTH.
            var instruction: Field? = null
            var ignoredFields = 0
            // Paragraphs and items in result, and the characters added that are not blank.
            var kept = 0
            var visible = 0
            val textLimit = if (forText) TextExtract.LIMIT else Int.MAX_VALUE
            fun flush() { if (run.isNotEmpty()) { items += Item.Text(run.toString(), runStyle); run.setLength(0) } }
            fun add(value: String, style: RunStyle) {
                if (run.isNotEmpty() && style != runStyle) flush()
                if (run.isEmpty()) runStyle = style
                run.append(value)
                visible += value.count { !it.isWhitespace() }
            }
            fun end(position: Int, cellEnd: Boolean) {
                flush()
                // A link left open by a damaged field still closes with its paragraph.
                fields.filter { it.link }.forEach { items += Item.LinkEnd; it.link = false }
                result += WordFlow.Paragraph(items, find(paragraphProps, position) ?: DEFAULT_PROPS, cellEnd)
                kept += items.size + 1
                items = mutableListOf()
            }
            for (cp in 0 until minOf(length, text.size)) {
                if (cp % 4096 == 0) checkActive()
                if (kept + items.size >= FLOW_LIMIT || visible >= textLimit) { if (forText) break; throw PreviewLimitException() }
                val char = text[cp]
                val position = positions[cp]
                if (position < 0) continue
                val props = find(characters, position) ?: CharProps()
                when (char.code) {
                    // Fields: begin, separator (the instruction ends, the shown result starts), end.
                    0x13 -> {
                        flush()
                        if (fields.size >= FIELD_DEPTH) ignoredFields++ else { val field = Field(); fields.addLast(field); instruction = field }
                        continue
                    }
                    0x14 -> {
                        if (ignoredFields > 0) continue
                        val field = fields.lastOrNull() ?: continue
                        field.result = true
                        instruction = fields.lastOrNull { !it.result }
                        val target = HYPERLINK.find(field.instruction)?.groupValues?.get(1)
                        if (target != null && fields.all { it.result }) { flush(); items += Item.LinkStart(target); field.link = true }
                        continue
                    }
                    0x15 -> {
                        if (ignoredFields > 0) { ignoredFields--; continue }
                        val field = fields.removeLastOrNull() ?: continue
                        instruction = fields.lastOrNull { !it.result }
                        if (field.link) { flush(); items += Item.LinkEnd }
                        continue
                    }
                }
                val current = instruction
                if (current != null) { if (current.instruction.length < 2048) current.instruction.append(char); continue }
                when (char.code) {
                    0x0D, 0x0C -> end(position, cellEnd = false)
                    0x07 -> end(position, cellEnd = true)
                    0x09 -> { flush(); items += Item.Tab }
                    0x0B, 0x0A -> { flush(); items += Item.Break }
                    0x01 -> if (props.special && !props.data && props.picture >= 0) picture(props.picture)?.let { flush(); items += it }
                    0x1E -> if (!props.hidden && !props.deleted) add("-", props.style)
                    else -> if (char.code >= 0x20 && !props.hidden && !props.deleted) add(char.toString(), props.style)
                }
            }
            if (run.isNotEmpty() || items.isNotEmpty()) end(-1, cellEnd = false)
            return result
        }

        /** The picture looked up last: a run of picture characters points to one record, which is read once. */
        private var lastLocation = -1
        private var lastPicture: Item.Picture? = null
        /** Bytes of picture records read so far. */
        private var pictureBytes = 0L

        private fun picture(location: Int): Item.Picture? {
            val stream = data ?: return null
            if (location != lastLocation) {
                checkActive()
                lastPicture = runCatching { readPicture(stream, location) }.getOrNull()
                lastLocation = location
            }
            return lastPicture
        }

        /** An inline picture: PICF in the Data stream, then OfficeArt records holding the BLIP. */
        private fun readPicture(stream: StreamReader, location: Int): Item.Picture? {
            if (location < 0 || location + 8L > stream.size) return null
            val total = LittleEndian(stream.read(location.toLong(), 8)).i32(0)
            if (total <= 8 || location.toLong() + total > stream.size || total > PICTURE_LIMIT + 4096) return null
            // Pictures that do not overlap add up to the stream's size at most. Overlapping records stop at that size
            // and one picture more, instead of reading the stream again and again.
            if (pictureBytes + total > stream.size + PICTURE_LIMIT) return null
            pictureBytes += total
            // Only this picture's record is read from the stream.
            val bytes = LittleEndian(stream.read(location.toLong(), total))
            val header = bytes.u16(4)
            if (total <= header) return null
            var start = header
            if (bytes.i16(6) == 0x66) start += 1 + bytes.u8(start) // MM_SHAPEFILE has a picture name.
            val blip = Blips.find(bytes, start, total) ?: return null
            return media?.add(blip.type, blip.bytes)?.let { Item.Picture(it) }
        }
    }

    /** Calls [action] for each sprm in a grpprl with its operand's offset; stops at anything malformed. */
    private inline fun sprms(bytes: LittleEndian, start: Int, size: Int, action: (sprm: Int, operand: Int) -> Unit) {
        var at = start
        val end = start + size
        while (at + 2 <= end) {
            val sprm = bytes.u16(at)
            val operand = at + 2
            val length = when (sprm ushr 13) {
                0, 1 -> 1
                2, 4, 5 -> 2
                3 -> 4
                7 -> 3
                else -> when (sprm) {
                    0xD608, 0xD606 -> bytes.u16(operand) + 1 // sprmTDefTable: a 2-byte count, plus one.
                    0xC615 -> bytes.u8(operand).takeIf { it != 255 }?.plus(1) ?: return
                    else -> bytes.u8(operand) + 1
                }
            }
            if (operand + length > end) return
            action(sprm, operand)
            at = operand + length
        }
    }

    // FibRgFcLcb97 indexes.
    private const val STSHF = 1
    private const val PLCF_BTE_CHPX = 12
    private const val PLCF_BTE_PAPX = 13
    private const val CLX = 33

    private val HYPERLINK = Regex("^\\s*HYPERLINK\\s+(?:\\\\[a-z]\\s+)*\"?([^\"\\s]+)", RegexOption.IGNORE_CASE)

    /** The properties of a paragraph without its own, shared by all such paragraphs (they never change). */
    private val DEFAULT_PROPS = WordFlow.Props()

    /** Characters 0x80–0x9F of Windows-1252 differ from ISO-8859-1; the rest map one to one. */
    private val WINDOWS_1252 = CharArray(256) { it.toChar() }.also { table ->
        "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F\u0090‘’“”•–—˜™š›œ\u009DžŸ".forEachIndexed { index, char -> table[0x80 + index] = char }
    }
}

/** OfficeArt BLIPs (pictures in .doc and .ppt): PNG and JPEG as they are, device-independent bitmaps as BMP. */
internal object Blips {
    private const val CONTAINER = 0xF
    private const val FBSE = 0xF007

    /** A picture's type and bytes, as a viewer shows them. */
    class Blip(val type: String, val bytes: ByteArray)

    fun find(bytes: LittleEndian, start: Int, end: Int, depth: Int = 0): Blip? {
        var at = start
        while (at + 8 <= end && depth < 8) {
            val info = bytes.u16(at)
            val type = bytes.u16(at + 2)
            val length = bytes.i32(at + 4)
            if (length < 0 || at.toLong() + 8 + length > end) return null
            val body = at + 8
            picture(bytes, type, info ushr 4, body, length)?.let { return it }
            if (type == FBSE && length > 36) {
                val nameLength = bytes.u8(body + 33)
                find(bytes, body + 36 + nameLength, body + length, depth + 1)?.let { return it }
            } else if (info and 0xF == CONTAINER) {
                find(bytes, body, body + length, depth + 1)?.let { return it }
            }
            at = body + length
        }
        return null
    }

    private fun picture(bytes: LittleEndian, type: Int, instance: Int, body: Int, length: Int): Blip? {
        val (mime, secondUid) = when (type) {
            0xF01D -> "image/jpeg" to (instance == 0x46B)
            0xF02A -> "image/jpeg" to (instance == 0x6E3)
            0xF01E -> "image/png" to (instance == 0x6E1)
            0xF01F -> "image/bmp" to (instance == 0x7A9)
            else -> return null
        }
        val header = 16 + (if (secondUid) 16 else 0) + 1
        if (length <= header) return null
        val data = bytes.slice(body + header, length - header)
        return Blip(mime, if (type == 0xF01F) bitmapFile(data) ?: return null else data)
    }

    /** A DIB lacks the 14-byte file header that a .bmp file starts with. */
    fun bitmapFile(dib: ByteArray): ByteArray? {
        val info = LittleEndian(dib)
        if (!info.has(0, 40)) return null
        val headerSize = info.i32(0)
        val bits = info.u16(14)
        val compression = info.i32(16)
        val colors = info.i32(32).takeIf { it > 0 } ?: if (bits <= 8) 1 shl bits else 0
        val masks = if (headerSize == 40 && compression == 3) 12 else 0
        val offset = 14 + headerSize + masks + colors * 4
        if (headerSize < 12 || offset > 14 + dib.size) return null
        val size = 14 + dib.size
        val header = byteArrayOf('B'.code.toByte(), 'M'.code.toByte(),
            size.toByte(), (size shr 8).toByte(), (size shr 16).toByte(), (size shr 24).toByte(), 0, 0, 0, 0,
            offset.toByte(), (offset shr 8).toByte(), (offset shr 16).toByte(), (offset shr 24).toByte())
        return header + dib
    }
}
