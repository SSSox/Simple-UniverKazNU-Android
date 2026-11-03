package com.univerflow.kaznu.preview

/**
 * PowerPoint 97–2003 (.ppt) into the shared [Deck] model, like [PptxReader]: slides in presentation order from the
 * last save's persist directory, shapes in drawing order ([MS-PPT], [MS-ODRAW]). Encrypted files are refused.
 */
internal object PptReader {
    /** Pictures are read from the "Pictures" stream in place, one at a time, and handed to [media]. */
    fun read(file: CompoundFile, checkActive: () -> Unit = {}, media: PreviewMedia = PreviewMedia.inline()): Deck {
        val document = file.stream("PowerPoint Document") ?: throw UnsupportedDocumentException("No PowerPoint stream")
        val user = file.stream("Current User") ?: throw UnsupportedDocumentException("No current user")
        return Presentation(LittleEndian(document), LittleEndian(user), file.reader("Pictures"), media, checkActive).read()
    }

    private class Record(val instance: Int, val type: Int, val body: Int, val length: Int) {
        val end get() = body + length
    }

    private class TextEntry(val kind: TextKind, val text: String, val style: Record?)

    private class Presentation(
        private val bytes: LittleEndian,
        private val user: LittleEndian,
        private val pictures: StreamReader?,
        private val media: PreviewMedia,
        private val checkActive: () -> Unit,
    ) {
        private var records = 0
        private val blips = ArrayList<Record?>()
        /** One picture may sit on many slides; it is kept once. */
        private val kept = HashMap<Int, String?>()

        private fun record(at: Int, end: Int): Record? {
            if (at < 0 || at + 8 > end || at + 8 > bytes.size) return null
            val length = bytes.i32(at + 4)
            if (length < 0 || at.toLong() + 8 + length > minOf(end, bytes.size)) return null
            if (++records % 4096 == 0) checkActive()
            if (records > RECORD_LIMIT) throw PreviewLimitException()
            return Record(bytes.u16(at) ushr 4, bytes.u16(at + 2), at + 8, length)
        }

        private fun children(parent: Record): List<Record> {
            val result = ArrayList<Record>()
            var at = parent.body
            while (true) {
                val child = record(at, parent.end) ?: break
                result += child
                at = child.end
            }
            return result
        }

        private fun child(parent: Record, type: Int) = children(parent).firstOrNull { it.type == type }

        fun read(): Deck {
            // CurrentUserAtom: header token, then the offset of the last UserEditAtom.
            if (user.u32(12) == ENCRYPTED_TOKEN) throw UnsupportedDocumentException("Encrypted presentation")
            val directory = HashMap<Int, Int>()
            var edit = user.i32(16)
            var documentRef = -1
            val seen = HashSet<Int>()
            while (edit > 0 && seen.add(edit) && seen.size < 1024) {
                val atom = record(edit, bytes.size)?.takeIf { it.type == USER_EDIT && it.length >= 28 } ?: break
                if (atom.length > 28) throw UnsupportedDocumentException("Encrypted presentation")
                if (documentRef < 0) documentRef = bytes.i32(atom.body + 16)
                // Newer saves come first and win; each entry lists consecutive persist ids from a start id.
                record(bytes.i32(atom.body + 12), bytes.size)?.takeIf { it.type == PERSIST_DIRECTORY }?.let { list ->
                    var at = list.body
                    while (at + 4 <= list.end) {
                        val header = bytes.i32(at)
                        val first = header and 0xFFFFF
                        val count = header ushr 20
                        for (index in 0 until count) if (at + 4 + index * 4 + 4 <= list.end) directory.putIfAbsent(first + index, bytes.i32(at + 4 + index * 4))
                        at += 4 + count * 4
                    }
                }
                edit = bytes.i32(atom.body + 8)
            }
            val document = directory[documentRef]?.let { record(it, bytes.size) }?.takeIf { it.type == DOCUMENT }
                ?: throw UnsupportedDocumentException("No document container")
            val slides = ArrayList<Pair<Int, List<TextEntry>>>()
            val notes = HashMap<Int, Int>()
            for (item in children(document)) when {
                item.type == DRAWING_GROUP -> blipStore(item)
                item.type == SLIDE_LIST && item.instance == 0 -> slideList(item) { persist, _, texts -> if (slides.size < SLIDE_LIMIT) slides += persist to texts }
                item.type == SLIDE_LIST && item.instance == 2 -> slideList(item) { persist, id, _ -> notes[id] = persist }
            }
            return Deck(slides.mapNotNull { (persist, texts) ->
                val slide = directory[persist]?.let { record(it, bytes.size) }?.takeIf { it.type == SLIDE } ?: return@mapNotNull null
                val blocks = drawing(slide, texts, notesPage = false)
                val notesRef = child(slide, SLIDE_ATOM)?.takeIf { it.length >= 20 }?.let { bytes.i32(it.body + 16) } ?: 0
                val notesPage = notes[notesRef]?.let(directory::get)?.let { record(it, bytes.size) }?.takeIf { it.type == NOTES }
                val notesText = notesPage?.let { drawing(it, emptyList(), notesPage = true) }.orEmpty()
                    .filterIsInstance<SlideBlock.Text>().flatMap { it.paragraphs }
                Slide(SlideView.ordered(blocks), notesText)
            })
        }

        /** A SlideListWithText: each SlidePersistAtom is followed by the text of that slide's placeholders. */
        private fun slideList(list: Record, add: (persist: Int, id: Int, texts: List<TextEntry>) -> Unit) {
            var current: Triple<Int, Int, MutableList<TextEntry>>? = null
            var kind = TextKind.OTHER
            var text: String? = null
            fun flush() { text?.let { current?.third?.add(TextEntry(kind, it, null)) }; text = null }
            for (item in children(list)) when (item.type) {
                SLIDE_PERSIST -> {
                    flush()
                    current?.let { add(it.first, it.second, it.third) }
                    current = Triple(bytes.i32(item.body), if (item.length >= 16) bytes.i32(item.body + 12) else 0, mutableListOf())
                }
                TEXT_HEADER -> { flush(); kind = textKind(bytes.i32(item.body)) }
                TEXT_CHARS, TEXT_BYTES -> { flush(); text = chars(item) }
                STYLE_TEXT -> text?.let { value -> current?.third?.add(TextEntry(kind, value, item)); text = null }
            }
            flush()
            current?.let { add(it.first, it.second, it.third) }
        }

        private fun chars(item: Record) =
            if (item.type == TEXT_CHARS) bytes.utf16(item.body, item.length / 2) else String(bytes.slice(item.body, item.length), Charsets.ISO_8859_1)

        private fun textKind(type: Int) = when (type) {
            0, 6 -> TextKind.TITLE
            1, 7, 8 -> TextKind.BODY
            5 -> TextKind.SUBTITLE
            2 -> TextKind.NOTES
            else -> TextKind.OTHER
        }

        /** The shapes of a slide or notes page: PPDrawing, its drawing container and the top group, in drawing order. */
        private fun drawing(page: Record, texts: List<TextEntry>, notesPage: Boolean): List<SlideBlock> {
            val blocks = ArrayList<SlideBlock>()
            val container = child(page, PP_DRAWING)?.let { child(it, DG_CONTAINER) } ?: return blocks
            child(container, SPGR_CONTAINER)?.let { group(it, texts, notesPage, blocks, 0) }
            return blocks
        }

        private fun group(group: Record, texts: List<TextEntry>, notesPage: Boolean, blocks: MutableList<SlideBlock>, depth: Int) {
            if (depth > 16) return
            for (item in children(group)) when (item.type) {
                SPGR_CONTAINER -> group(item, texts, notesPage, blocks, depth + 1)
                SP_CONTAINER -> shape(item, texts, notesPage, blocks)
            }
            if (blocks.size > SHAPE_LIMIT) throw PreviewLimitException()
        }

        private fun shape(shape: Record, texts: List<TextEntry>, notesPage: Boolean, blocks: MutableList<SlideBlock>) {
            var picture = 0
            var placeholder = -1
            var entry: TextEntry? = null
            for (item in children(shape)) when (item.type) {
                // Group, patriarch, deleted and background shapes carry nothing to read.
                FSP -> if (item.length >= 8 && bytes.i32(item.body + 4) and (0x1 or 0x4 or 0x8 or 0x400) != 0) return
                FOPT -> for (index in 0 until item.instance) {
                    val at = item.body + index * 6
                    if (at + 6 > item.end) break
                    if (bytes.u16(at) and 0x3FFF == PIB) picture = bytes.i32(at + 2)
                }
                CLIENT_DATA -> child(item, PLACEHOLDER)?.takeIf { it.length >= 5 }?.let { placeholder = bytes.u8(it.body + 4) }
                CLIENT_TEXTBOX -> entry = textbox(item, texts)
            }
            if (placeholder in SKIPPED_PLACEHOLDERS) return
            if (notesPage) {
                val text = entry ?: return
                if (text.kind == TextKind.NOTES || placeholder == NOTES_BODY) blocks += SlideBlock.Text(paragraphs(text, TextKind.NOTES))
                return
            }
            if (picture > 0) blip(picture)?.let { blocks += it }
            val text = entry ?: return
            val kind = if (placeholder in TITLE_PLACEHOLDERS) TextKind.TITLE else text.kind
            val paragraphs = paragraphs(text, kind).filter { it.text.isNotBlank() }
            if (paragraphs.isEmpty()) return
            blocks += if (kind == TextKind.TITLE) SlideBlock.Title(paragraphs.joinToString("\n") { it.text.trim() }) else SlideBlock.Text(paragraphs)
        }

        /** Text kept in the shape, or a reference to the n-th text of the slide list. */
        private fun textbox(box: Record, texts: List<TextEntry>): TextEntry? {
            var kind = TextKind.OTHER
            var text: String? = null
            var style: Record? = null
            for (item in children(box)) when (item.type) {
                OUTLINE_TEXT_REF -> return texts.getOrNull(bytes.i32(item.body))
                TEXT_HEADER -> kind = textKind(bytes.i32(item.body))
                TEXT_CHARS, TEXT_BYTES -> text = chars(item)
                STYLE_TEXT -> if (text != null && style == null) style = item
            }
            return text?.let { TextEntry(kind, it, style) }
        }

        /** Paragraphs end with CR; a vertical tab is a line break. Levels and explicit bullets come from the style runs. */
        private fun paragraphs(entry: TextEntry, kind: TextKind): List<SlideParagraph> {
            val runs = entry.style?.let { paragraphRuns(it, entry.text.length) }.orEmpty()
            val result = ArrayList<SlideParagraph>()
            var offset = 0
            entry.text.split('\r').forEach { line ->
                var covered = 0
                val run = runs.firstOrNull { covered += it.first; offset < covered }
                result += SlideParagraph(line.replace('\u000B', '\n'), run?.second?.coerceIn(0, 8) ?: 0,
                    kind != TextKind.TITLE && SlideView.bullet(kind, run?.third))
                offset += line.length + 1
            }
            return result
        }

        /** StyleTextPropAtom paragraph runs: character count, indent level and the bullet flag when the run sets it. */
        private fun paragraphRuns(style: Record, length: Int): List<Triple<Int, Int, Boolean?>> {
            val runs = ArrayList<Triple<Int, Int, Boolean?>>()
            var at = style.body
            var covered = 0
            while (covered <= length && at + 10 <= style.end && runs.size < 4096) {
                val count = bytes.i32(at)
                val level = bytes.u16(at + 4)
                val masks = bytes.i32(at + 6)
                var field = at + 10
                var bullet: Boolean? = null
                if (masks and 0xF != 0) { val flags = bytes.u16(field); field += 2; if (masks and 1 != 0) bullet = flags and 1 != 0 }
                // Optional fields in [MS-PPT] TextPFException order: char, font, size, colour, alignment, spacing, margins, tabs.
                for ((bit, size) in PF_FIELDS) if (masks and (1 shl bit) != 0) field += size
                if (masks and (1 shl 20) != 0) field += 2 + bytes.u16(field) * 4
                if (masks and (1 shl 16) != 0) field += 2
                if (masks and (0x7 shl 17) != 0) field += 2
                if (masks and (1 shl 21) != 0) field += 2
                if (count <= 0) break
                runs += Triple(count, level, bullet)
                covered += count
                at = field
            }
            return runs
        }

        /** The drawing group's BLIP store: index n of a picture property is the n-th entry, from 1. */
        private fun blipStore(group: Record) {
            val store = child(group, DGG_CONTAINER)?.let { child(it, BSTORE) } ?: return
            children(store).forEach { if (blips.size < 4096) blips += it.takeIf { entry -> entry.type == FBSE && entry.length >= 36 } }
        }

        /** A picture's bytes from the store entry, embedded or at its offset in the "Pictures" stream; metafiles are skipped. */
        private fun blip(index: Int): SlideBlock.Picture? {
            if (index in kept) return kept[index]?.let { SlideBlock.Picture(it) }
            val entry = blips.getOrNull(index - 1) ?: return null
            val nameLength = bytes.u8(entry.body + 33)
            val src = if (entry.length > 36 + nameLength) image(StreamReader.of(bytes.slice(entry.body + 36 + nameLength, entry.length - 36 - nameLength)), 0L)
                else pictures?.let { stream -> image(stream, bytes.u32(entry.body + 28)) }
            kept[index] = src
            return src?.let { SlideBlock.Picture(it) }
        }

        private fun image(source: StreamReader, at: Long): String? = runCatching {
            if (at < 0 || at + 8 > source.size) return null
            val head = LittleEndian(source.read(at, 8))
            val instance = head.u16(0) ushr 4
            val type = head.u16(2)
            val length = head.i32(4)
            if (length < 0 || at + 8 + length > source.size || length > SlideView.IMAGE_LIMIT) return null
            val header = 16 * (if (instance and 1 == 1) 2 else 1) + 1
            if (length <= header) return null
            val mime = when (type) { 0xF01E -> "image/png"; 0xF01D, 0xF02A -> "image/jpeg"; 0xF01F -> "image/bmp"; else -> return null }
            val data = source.read(at + 8 + header, length - header)
            media.add(mime, if (type == 0xF01F) bitmap(data) ?: return null else data)
        }.getOrNull()

        /** A device-independent bitmap gets the 14-byte file header that makes it a .bmp. */
        private fun bitmap(dib: ByteArray): ByteArray? {
            if (dib.size < 40) return null
            val info = LittleEndian(dib)
            val headerSize = info.i32(0)
            val bits = info.u16(14)
            val used = info.i32(32)
            val palette = if (used in 1..256) used else if (bits in 1..8) 1 shl bits else 0
            val masks = if (headerSize == 40 && info.i32(16) == 3) 12 else 0
            val offset = 14 + headerSize + palette * 4 + masks
            if (headerSize < 12 || offset > dib.size + 14) return null
            val size = dib.size + 14
            val header = byteArrayOf('B'.code.toByte(), 'M'.code.toByte(), size.toByte(), (size shr 8).toByte(), (size shr 16).toByte(), (size shr 24).toByte(),
                0, 0, 0, 0, offset.toByte(), (offset shr 8).toByte(), (offset shr 16).toByte(), (offset shr 24).toByte())
            return header + dib
        }
    }

    private const val ENCRYPTED_TOKEN = 0xF3D1C4DFL
    private const val RECORD_LIMIT = 2_000_000
    private const val SLIDE_LIMIT = 2_000
    private const val SHAPE_LIMIT = 20_000
    private const val DOCUMENT = 0x03E8
    private const val SLIDE = 0x03EE
    private const val SLIDE_ATOM = 0x03EF
    private const val NOTES = 0x03F0
    private const val SLIDE_PERSIST = 0x03F3
    private const val DRAWING_GROUP = 0x040B
    private const val PP_DRAWING = 0x040C
    private const val PLACEHOLDER = 0x0BC3
    private const val OUTLINE_TEXT_REF = 0x0F9E
    private const val TEXT_HEADER = 0x0F9F
    private const val TEXT_CHARS = 0x0FA0
    private const val STYLE_TEXT = 0x0FA1
    private const val TEXT_BYTES = 0x0FA8
    private const val SLIDE_LIST = 0x0FF0
    private const val USER_EDIT = 0x0FF5
    private const val PERSIST_DIRECTORY = 0x1772
    private const val DGG_CONTAINER = 0xF000
    private const val BSTORE = 0xF001
    private const val DG_CONTAINER = 0xF002
    private const val SPGR_CONTAINER = 0xF003
    private const val SP_CONTAINER = 0xF004
    private const val FBSE = 0xF007
    private const val FSP = 0xF00A
    private const val FOPT = 0xF00B
    private const val CLIENT_TEXTBOX = 0xF00D
    private const val CLIENT_DATA = 0xF011
    private const val PIB = 0x0104
    private const val NOTES_BODY = 0x0C
    /** Title, centred title and vertical title placeholders. */
    private val TITLE_PLACEHOLDERS = setOf(0x0D, 0x0F, 0x11)
    /** Date, slide number, footer, header and the slide image of a notes page. */
    private val SKIPPED_PLACEHOLDERS = setOf(0x05, 0x07, 0x08, 0x09, 0x0A, 0x0B)
    /** (mask bit, size) of the fixed-size TextPFException fields before the tab stops. */
    private val PF_FIELDS = listOf(7 to 2, 4 to 2, 6 to 2, 5 to 4, 11 to 2, 12 to 2, 13 to 2, 14 to 2, 8 to 2, 10 to 2, 15 to 2)
}
