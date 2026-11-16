package com.univerflow.kaznu.preview

import com.univerflow.kaznu.preview.WordFlow.Item
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Locale

/**
 * Rich Text Format (many ".doc" files are RTF inside), read into the same [WordFlow] as .doc. Headers, footers,
 * notes, shapes and objects are left out.
 */
internal object Rtf {
    private const val PICTURE_LIMIT = 32 * 1024 * 1024
    private const val DEPTH_LIMIT = 256
    /** Paragraphs and their items kept for one document: past it the view is refused and the search keeps what it read. */
    private const val FLOW_LIMIT = 500_000
    /** Characters of text the search reads at most, blanks included, when few of them are anything else. */
    private const val TEXT_CHAR_LIMIT = 4 * TextExtract.LIMIT

    fun isRtf(bytes: ByteArray) = bytes.size >= 5 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "{\\rtf"

    fun html(bytes: ByteArray, checkActive: () -> Unit = {}, media: PreviewMedia = PreviewMedia.inline()): String =
        WordFlow.html(Reader(bytes, checkActive, media).read())

    fun text(bytes: ByteArray, checkActive: () -> Unit = {}): String = WordFlow.text(Reader(bytes, checkActive, null).read())

    private enum class Destination { TEXT, SKIP, FONTS, STYLES, INSTRUCTION, PICTURE }

    private class Field(val instruction: StringBuilder = StringBuilder())

    /** Group-scoped state; a new group starts as a copy of its parent. */
    private data class State(
        val destination: Destination = Destination.TEXT,
        val style: RunStyle = RunStyle(),
        val hidden: Boolean = false,
        val font: Int = -1,
        val unicodeSkip: Int = 1,
        val paragraph: WordFlow.Props = WordFlow.Props(),
        val field: Field? = null,
        val linkOpened: Boolean = false,
        val picture: String? = null,
    )

    /** [media] keeps the pictures; without it (the search) they are skipped. */
    private class Reader(private val bytes: ByteArray, private val checkActive: () -> Unit, private val media: PreviewMedia?) {
        private val pictures = media != null
        private val result = mutableListOf<WordFlow.Paragraph>()
        private var items = mutableListOf<Item>()
        private val run = StringBuilder()
        private var runStyle = RunStyle()
        private var state = State()
        private val stack = ArrayDeque<State>()
        private var position = 0
        /** Fallback characters still to drop after \uN. */
        private var pendingSkip = 0
        private var starred = false
        private var defaultCodepage = 1252
        private val fontCodepages = mutableMapOf<Int, Int>()
        private var fontNumber = -1
        private val headingStyles = mutableMapOf<Int, Int>()
        private val styleName = StringBuilder()
        private var styleNumber = 0
        // The row being defined: its left edge and cell right edges in twips, and the next cell's pending merge flags.
        private val bounds = mutableListOf(0)
        private val merges = mutableListOf<Merge>()
        private val covered = mutableListOf<Boolean>()
        private var nextMerge = Merge.NONE
        private var nextCovered = false
        private val pictureData = ByteArrayOutputStream()
        private var pictureNibble = -1
        private val decoders = mutableMapOf<Int, Charset>()
        /** The lead byte of a double-byte character waiting for its second byte, or -1. */
        private var leadByte = -1
        /** Without [media] the reader serves the search, which needs only the first TextExtract.LIMIT characters. */
        private val forText = media == null
        /** Paragraphs and items in [result]. */
        private var kept = 0
        /** Characters of text read, and those of them that are not blank (only those always reach the search). */
        private var chars = 0
        private var visible = 0

        fun read(): List<WordFlow.Paragraph> {
            while (position < bytes.size) {
                if (position % 65536 == 0) checkActive()
                if (kept + items.size >= FLOW_LIMIT || forText && (visible >= TextExtract.LIMIT || chars >= TEXT_CHAR_LIMIT)) {
                    if (forText) break
                    throw PreviewLimitException()
                }
                val byte = byteAt(position)
                position++
                when (byte) {
                    '{'.code -> push()
                    '}'.code -> pop()
                    '\\'.code -> control()
                    '\r'.code, '\n'.code -> Unit
                    // A byte below 0x80 can complete a double-byte character too.
                    else -> if (byte >= 0x80 || leadByte >= 0) codepageByte(byte) else character(byte)
                }
            }
            flush()
            if (items.isNotEmpty()) result += WordFlow.Paragraph(items, state.paragraph)
            return result
        }

        private fun byteAt(at: Int) = bytes[at].toInt() and 0xFF
        private fun isLetter(at: Int) = at < bytes.size && byteAt(at).let { it in 'a'.code..'z'.code || it in 'A'.code..'Z'.code }
        private fun isDigit(at: Int) = at < bytes.size && byteAt(at) in '0'.code..'9'.code

        private fun push() {
            if (stack.size >= DEPTH_LIMIT) throw PreviewLimitException()
            stack.addLast(state)
            state = state.copy(linkOpened = false, picture = null)
            pendingSkip = 0
            leadByte = -1
        }

        private fun pop() {
            val closing = state
            state = stack.removeLastOrNull() ?: State()
            if (closing.destination == Destination.PICTURE && state.destination != Destination.PICTURE) picture(closing.picture)
            if (closing.destination == Destination.STYLES && styleName.isNotBlank()) style()
            if (closing.linkOpened) { flush(); items += Item.LinkEnd }
            pendingSkip = 0
            leadByte = -1
        }

        /** A control symbol (\' \~ \* …) or a control word with an optional numeric parameter. */
        private fun control() {
            if (position >= bytes.size) return
            if (!isLetter(position)) {
                val symbol = byteAt(position).toChar()
                position++
                if (symbol != '\'') leadByte = -1
                when (symbol) {
                    '\'' -> {
                        val value = if (position + 2 <= bytes.size) String(bytes, position, 2, Charsets.ISO_8859_1).toIntOrNull(16) else null
                        position += 2
                        if (value != null) codepageByte(value)
                    }
                    '*' -> starred = true
                    '~' -> character(0xA0)
                    '_' -> character('-'.code)
                    '{', '}', '\\' -> character(symbol.code)
                    '\r', '\n' -> paragraphEnd(cellEnd = false)
                    else -> Unit // \- (soft hyphen), \| and the like draw nothing.
                }
                return
            }
            leadByte = -1
            val start = position
            while (isLetter(position) && position - start < 32) position++
            val word = String(bytes, start, position - start, Charsets.ISO_8859_1)
            val negative = position < bytes.size && byteAt(position) == '-'.code && isDigit(position + 1)
            if (negative) position++
            val digits = position
            while (isDigit(position) && position - digits < 10) position++
            val parameter = if (position > digits) String(bytes, digits, position - digits, Charsets.ISO_8859_1).toIntOrNull()?.let { if (negative) -it else it } else null
            if (position < bytes.size && byteAt(position) == ' '.code) position++ // The delimiting space belongs to the word.
            val wasStarred = starred
            starred = false
            word(word, parameter, wasStarred)
        }

        private fun word(word: String, parameter: Int?, starred: Boolean) {
            if (state.destination == Destination.SKIP) { if (word == "bin") skip(parameter); return }
            // A group marked \* is skipped unless its destination is one read here.
            if (starred && word !in STARRED_READ) { state = state.copy(destination = Destination.SKIP); if (word == "bin") skip(parameter); return }
            when (word) {
                "fonttbl" -> { state = state.copy(destination = Destination.FONTS); return }
                "stylesheet" -> { state = state.copy(destination = Destination.STYLES); return }
                "fldinst" -> { state = state.copy(destination = if (state.field != null) Destination.INSTRUCTION else Destination.SKIP); return }
                "fldrslt" -> { state = state.copy(destination = Destination.TEXT); openLink(); return }
                "shppict" -> return
                "pict" -> {
                    pictureData.reset(); pictureNibble = -1
                    state = state.copy(destination = if (pictures) Destination.PICTURE else Destination.SKIP); return
                }
                "field" -> { state = state.copy(field = Field()); return }
                in SKIPPED -> { state = state.copy(destination = Destination.SKIP); return }
            }
            when (state.destination) {
                Destination.PICTURE -> picturePart(word, parameter)
                Destination.FONTS -> when (word) {
                    "f" -> fontNumber = parameter ?: -1
                    "fcharset" -> fontCodepages[fontNumber] = CHARSETS[parameter] ?: defaultCodepage
                    "cpg" -> parameter?.let { fontCodepages[fontNumber] = it }
                }
                Destination.STYLES -> if (word == "s") styleNumber = parameter ?: 0
                Destination.INSTRUCTION -> if (word == "u") parameter?.let(::unicode)
                Destination.TEXT -> formatting(word, parameter)
                Destination.SKIP -> Unit
            }
        }

        private fun formatting(word: String, parameter: Int?) {
            val on = parameter != 0
            val style = state.style
            val paragraph = state.paragraph
            when (word) {
                "ansicpg" -> parameter?.let { defaultCodepage = it }
                "f" -> state = state.copy(font = parameter ?: -1)
                "uc" -> state = state.copy(unicodeSkip = (parameter ?: 1).coerceIn(0, 8))
                "u" -> parameter?.let(::unicode)
                "par", "sect" -> paragraphEnd(cellEnd = false)
                "line" -> { flush(); items += Item.Break }
                "tab" -> { flush(); items += Item.Tab }
                "cell" -> paragraphEnd(cellEnd = true)
                "nestcell" -> paragraphEnd(cellEnd = false)
                "row" -> rowEnd()
                "emdash" -> character('—'.code); "endash" -> character('–'.code); "bullet" -> character('•'.code)
                "lquote" -> character('‘'.code); "rquote" -> character('’'.code)
                "ldblquote" -> character('“'.code); "rdblquote" -> character('”'.code)
                "plain" -> state = state.copy(style = RunStyle(), hidden = false)
                "b" -> state = state.copy(style = style.copy(bold = on))
                "i" -> state = state.copy(style = style.copy(italic = on))
                "ul", "uld", "uldb", "uldash", "uldashd", "uldashdd", "ulth", "ulw", "ulwave", "uldb2" -> state = state.copy(style = style.copy(underline = on))
                "ulnone" -> state = state.copy(style = style.copy(underline = false))
                "strike", "striked" -> state = state.copy(style = style.copy(strike = on))
                "super" -> state = state.copy(style = style.copy(vertical = RunStyle.Vertical.SUPER))
                "sub" -> state = state.copy(style = style.copy(vertical = RunStyle.Vertical.SUB))
                "nosupersub" -> state = state.copy(style = style.copy(vertical = RunStyle.Vertical.NONE))
                "v", "deleted" -> state = state.copy(hidden = on)
                "pard" -> state = state.copy(paragraph = WordFlow.Props())
                "s" -> state = state.copy(paragraph = paragraph.copy(heading = headingStyles[parameter ?: 0] ?: 0))
                "outlinelevel" -> if (parameter in 0..5) state = state.copy(paragraph = paragraph.copy(heading = parameter!! + 1))
                "qc" -> state = state.copy(paragraph = paragraph.copy(align = Align.CENTER))
                "qr" -> state = state.copy(paragraph = paragraph.copy(align = Align.END))
                "qj", "qd" -> state = state.copy(paragraph = paragraph.copy(align = Align.JUSTIFY))
                "ql" -> state = state.copy(paragraph = paragraph.copy(align = null))
                "ls" -> state = state.copy(paragraph = paragraph.copy(listItem = (parameter ?: 0) > 0))
                "intbl" -> state = state.copy(paragraph = paragraph.copy(inTable = true))
                "itap" -> state = state.copy(paragraph = paragraph.copy(depth = (parameter ?: 0).coerceIn(0, 64), inTable = (parameter ?: 0) > 0))
                "trowd" -> { bounds.clear(); bounds += 0; merges.clear(); covered.clear(); nextMerge = Merge.NONE; nextCovered = false }
                "trleft" -> if (bounds.size == 1) bounds[0] = parameter ?: 0
                "clvmgf" -> nextMerge = Merge.RESTART
                "clvmrg" -> nextMerge = Merge.CONTINUE
                "clmrg" -> nextCovered = true
                "cellx" -> if (bounds.size < 64) {
                    bounds += parameter ?: (bounds.last() + 1440)
                    merges += nextMerge; covered += nextCovered
                    nextMerge = Merge.NONE; nextCovered = false
                }
            }
        }

        private fun picturePart(word: String, parameter: Int?) {
            when (word) {
                "pngblip" -> state = state.copy(picture = "image/png")
                "jpegblip" -> state = state.copy(picture = "image/jpeg")
                "bin" -> {
                    val count = (parameter ?: 0).coerceIn(0, bytes.size - position)
                    if (pictureData.size() + count <= PICTURE_LIMIT) pictureData.write(bytes, position, count)
                    position += count
                }
            }
        }

        private fun skip(count: Int?) { position += (count ?: 0).coerceIn(0, bytes.size - position) }

        private fun character(code: Int) {
            if (pendingSkip > 0 && state.destination != Destination.PICTURE) { pendingSkip--; return }
            when (state.destination) {
                Destination.TEXT -> {
                    if (state.hidden || code < 0x20) return
                    if (run.isNotEmpty() && state.style != runStyle) flush()
                    if (run.isEmpty()) runStyle = state.style
                    run.appendCodePoint(code)
                    chars++
                    if (!Character.isWhitespace(code) && !Character.isSpaceChar(code)) visible++
                }
                Destination.INSTRUCTION -> state.field?.instruction?.takeIf { it.length < 2048 }?.appendCodePoint(code)
                Destination.STYLES -> if (code == ';'.code) style() else if (styleName.length < 256) styleName.appendCodePoint(code)
                Destination.PICTURE -> pictureHex(code)
                Destination.FONTS, Destination.SKIP -> Unit
            }
        }

        /**
         * A byte above ASCII (escaped as \'hh or raw): a character in the code page of the current font or document.
         * In the double-byte code pages (Chinese, Japanese, Korean) a lead byte waits for the byte that completes it.
         */
        private fun codepageByte(value: Int) {
            if (state.destination == Destination.PICTURE) return
            // The fallback after \uN is counted in bytes, both bytes of a double-byte character included.
            if (pendingSkip > 0) { pendingSkip--; leadByte = -1; return }
            val codepage = fontCodepages[state.font] ?: defaultCodepage
            val lead = leadByte
            leadByte = -1
            when {
                lead >= 0 -> String(byteArrayOf(lead.toByte(), value.toByte()), decoder(codepage)).forEach { character(it.code) }
                isLeadByte(codepage, value) -> leadByte = value
                value < 0x80 -> character(value)
                else -> character(String(byteArrayOf(value.toByte()), decoder(codepage)).firstOrNull()?.code ?: return)
            }
        }

        private fun unicode(value: Int) {
            pendingSkip = 0
            // A number that is no character at all is left out; what stands in for it is still skipped.
            (if (value < 0) value + 65536 else value).takeIf { it in 0..Character.MAX_CODE_POINT }?.let(::character)
            pendingSkip = state.unicodeSkip
        }

        private fun decoder(codepage: Int) = decoders.getOrPut(codepage) {
            runCatching { Charset.forName("windows-$codepage") }.recoverCatching { Charset.forName("cp$codepage") }.getOrDefault(Charsets.ISO_8859_1)
        }

        private fun pictureHex(code: Int) {
            val digit = Character.digit(code, 16)
            if (digit < 0) return
            if (pictureNibble < 0) { pictureNibble = digit; return }
            if (pictureData.size() < PICTURE_LIMIT) pictureData.write(pictureNibble * 16 + digit)
            pictureNibble = -1
        }

        private fun picture(type: String?) {
            val data = pictureData.toByteArray()
            pictureData.reset()
            if (type == null || data.isEmpty() || data.size >= PICTURE_LIMIT) return
            val src = media?.add(type, data) ?: return
            flush()
            items += Item.Picture(src)
        }

        private fun style() {
            val name = styleName.toString().trim().lowercase(Locale.ROOT)
            val level = Regex("^heading ([1-9])$").find(name)?.groupValues?.get(1)?.toInt()?.coerceAtMost(6) ?: if (name == "title") 1 else null
            if (level != null) headingStyles[styleNumber] = level
            styleName.setLength(0)
        }

        private fun openLink() {
            val target = HYPERLINK.find(state.field?.instruction ?: return)?.groupValues?.get(1) ?: return
            flush()
            items += Item.LinkStart(target)
            state = state.copy(linkOpened = true)
        }

        private fun flush() {
            if (run.isNotEmpty()) { items += Item.Text(run.toString(), runStyle); run.setLength(0) }
        }

        private fun paragraphEnd(cellEnd: Boolean) {
            if (state.destination != Destination.TEXT) return
            flush()
            keep(WordFlow.Paragraph(items, if (cellEnd) state.paragraph.copy(inTable = true) else state.paragraph, cellEnd))
            items = mutableListOf()
        }

        private fun keep(paragraph: WordFlow.Paragraph) {
            result += paragraph
            kept += paragraph.items.size + 1
        }

        /** \row: the row-end mark carries the row's layout, like a .doc row-end paragraph. */
        private fun rowEnd() {
            flush()
            if (items.isNotEmpty()) paragraphEnd(cellEnd = true)
            val layout = WordFlow.RowLayout(bounds.toIntArray(), merges.toList(), covered.toList()).takeIf { bounds.size > 1 }
            keep(WordFlow.Paragraph(emptyList(), WordFlow.Props(inTable = true, depth = 1, rowEnd = true, row = layout), cellEnd = true))
        }
    }

    private val HYPERLINK = Regex("^\\s*HYPERLINK\\s+(?:\\\\[a-z]\\s+)*\"?([^\"\\s]+)", RegexOption.IGNORE_CASE)

    /** Destinations marked \* that hold something the reader uses. */
    private val STARRED_READ = setOf("fldinst", "shppict")

    /** Groups that hold no document text, or text drawn elsewhere (headers, notes, list numbers, shapes). */
    private val SKIPPED = setOf("colortbl", "info", "header", "headerl", "headerr", "headerf", "footer", "footerl", "footerr", "footerf",
        "footnote", "annotation", "pntext", "pntxta", "pntxtb", "listtext", "listtable", "listoverridetable", "rsidtbl", "shp",
        "nonshppict", "nonesttables", "revtbl", "filetbl", "xe", "tc", "txe", "objdata", "objclass", "themedata", "colorschememapping",
        "datastore", "latentstyles", "generator", "xmlnstbl", "mmathPr", "pgdsctbl", "bkmkstart", "bkmkend", "template")

    /** Lead bytes of the double-byte code pages: Shift-JIS, GBK, Korean (Unified Hangul) and Big5. */
    private fun isLeadByte(codepage: Int, value: Int) = when (codepage) {
        932 -> value in 0x81..0x9F || value in 0xE0..0xFC
        936, 949, 950 -> value in 0x81..0xFE
        else -> false
    }

    /** \fcharset values to Windows code pages. */
    private val CHARSETS = mapOf(0 to 1252, 161 to 1253, 162 to 1254, 163 to 1258, 177 to 1255, 178 to 1256, 186 to 1257,
        204 to 1251, 222 to 874, 238 to 1250, 128 to 932, 134 to 936, 129 to 949, 136 to 950)
}
