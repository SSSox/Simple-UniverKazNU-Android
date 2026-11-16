package com.univerflow.kaznu.preview

import java.nio.charset.Charset

/**
 * Excel 97–2003 (.xls, BIFF8; BIFF5 labels) into the shared [Workbook] model, like [XlsxReader]; saved formula
 * results are shown. Encrypted workbooks are refused.
 */
internal object XlsReader {
    fun read(file: CompoundFile, checkActive: () -> Unit = {}): Workbook {
        val stream = file.stream("Workbook") ?: file.stream("Book") ?: throw UnsupportedDocumentException("No workbook stream")
        return Biff(LittleEndian(stream), checkActive).read()
    }

    private class BoundSheet(val position: Int, val name: String, val visible: Boolean, val worksheet: Boolean)

    private class Biff(private val bytes: LittleEndian, private val checkActive: () -> Unit) {
        private val strings = ArrayList<String>()
        private val fonts = ArrayList<CellStyle>()
        private val formats = HashMap<Int, String>()
        private val styles = ArrayList<CellStyle>()
        private val sheets = ArrayList<BoundSheet>()
        private var codepage: Charset = Charset.forName("windows-1252")
        private var date1904 = false
        private var records = 0

        fun read(): Workbook {
            var at = 0
            // The globals substream ends at its EOF; the worksheets follow at the offsets their BOUNDSHEET records give.
            while (at + 4 <= bytes.size) {
                val type = bytes.u16(at)
                val length = bytes.u16(at + 2)
                val body = at + 4
                if (body + length > bytes.size) break
                tick()
                when (type) {
                    FILEPASS -> throw UnsupportedDocumentException("Encrypted workbook")
                    CODEPAGE -> codepage(bytes.u16(body))
                    DATEMODE -> date1904 = bytes.u16(body) == 1
                    FONT -> font(body, length)
                    FORMAT -> runCatching { format(body, length) }
                    XF -> xf(body)
                    BOUNDSHEET -> boundSheet(body, length)
                    SST -> runCatching { readStrings(body, length) }
                    EOF -> break
                }
                at = body + length
            }
            val budget = CellBudget()
            // One substream is one sheet, whatever number of names a damaged workbook gives it.
            val result = sheets.filter { it.visible && it.worksheet }.distinctBy { it.position }.mapNotNull { bound ->
                // A damaged worksheet is left out; the others still open.
                try { Sheet(bound.name, budget).also { sheet(bound.position, it) } } catch (_: UnsupportedDocumentException) { null }
            }
            return Workbook(result, date1904)
        }

        private fun tick() { if (++records % 4096 == 0) checkActive() }

        private fun style(index: Int) = styles.getOrNull(index) ?: CellStyle()

        private fun sheet(position: Int, sheet: Sheet) {
            if (position < 0 || position + 4 > bytes.size || bytes.u16(position) != BOF) return
            var at = position
            var pendingFormula: Triple<Int, Int, CellStyle>? = null
            while (at + 4 <= bytes.size) {
                val type = bytes.u16(at)
                val length = bytes.u16(at + 2)
                val body = at + 4
                if (body + length > bytes.size) break
                tick()
                fun put(value: CellValue?) { if (value != null) sheet.put(bytes.u16(body), bytes.u16(body + 2), SheetCell(value, style(bytes.u16(body + 4)))) }
                when (type) {
                    LABEL_SST -> put(strings.getOrNull(bytes.i32(body + 6))?.let { CellValue.Text(it) })
                    LABEL, RSTRING -> put(label(body + 6, length - 6)?.let { CellValue.Text(it) })
                    NUMBER -> put(CellValue.Number(java.lang.Double.longBitsToDouble(long(body + 6))))
                    RK -> put(CellValue.Number(rk(bytes.i32(body + 6))))
                    MULRK -> {
                        val row = bytes.u16(body)
                        val first = bytes.u16(body + 2)
                        for (index in 0 until (length - 6) / 6) {
                            val item = body + 4 + index * 6
                            sheet.put(row, first + index, SheetCell(CellValue.Number(rk(bytes.i32(item + 2))), style(bytes.u16(item))))
                        }
                    }
                    BOOLERR -> put(if (bytes.u8(body + 7) == 0) CellValue.Bool(bytes.u8(body + 6) != 0) else CellValue.Error(ERRORS[bytes.u8(body + 6)] ?: "#N/A"))
                    FORMULA -> {
                        // The saved result: a number, or a marker whose string follows in a STRING record.
                        if (bytes.u16(body + 12) != 0xFFFF) put(CellValue.Number(java.lang.Double.longBitsToDouble(long(body + 6))))
                        else when (bytes.u8(body + 6)) {
                            0 -> pendingFormula = Triple(bytes.u16(body), bytes.u16(body + 2), style(bytes.u16(body + 4)))
                            1 -> put(CellValue.Bool(bytes.u8(body + 8) != 0))
                            2 -> put(CellValue.Error(ERRORS[bytes.u8(body + 8)] ?: "#N/A"))
                        }
                    }
                    STRING -> pendingFormula?.let { (row, column, style) ->
                        pendingFormula = null
                        val text = runCatching { continued(body, length) }.getOrNull() ?: return@let
                        sheet.put(row, column, SheetCell(CellValue.Text(text), style))
                    }
                    MERGEDCELLS -> {
                        val count = bytes.u16(body)
                        for (index in 0 until minOf(count, (length - 2) / 8)) {
                            val range = body + 2 + index * 8
                            sheet.merge(CellRange(bytes.u16(range), bytes.u16(range + 4), bytes.u16(range + 2), bytes.u16(range + 6)))
                        }
                    }
                    COLINFO -> sheet.column(bytes.u16(body), bytes.u16(body + 2), bytes.u16(body + 4) / 256.0, bytes.u16(body + 8) and 1 != 0)
                    STANDARDWIDTH -> sheet.defaultWidth = bytes.u16(body) / 256.0
                    ROW -> if (bytes.u16(body + 12) and 0x20 != 0) sheet.hideRow(bytes.u16(body))
                    EOF -> return
                }
                // A shared, array or table formula's own record sits between the first cell's FORMULA and its STRING.
                if (type != FORMULA && type != STRING && type != CONTINUE && type != SHRFMLA && type != ARRAY && type != TABLE) pendingFormula = null
                at = body + length
            }
        }

        private fun codepage(value: Int) {
            codepage = when (value) {
                1200 -> Charsets.UTF_16LE
                else -> runCatching { Charset.forName("windows-$value") }.getOrDefault(codepage)
            }
        }

        /** BIFF8 FONT: italic and strike-out flags, weight (700 is bold) and underline style. */
        private fun font(body: Int, length: Int) {
            if (length < 12) return
            val flags = bytes.u16(body + 2)
            fonts += CellStyle(bold = bytes.u16(body + 6) >= 700, italic = flags and 2 != 0, strike = flags and 8 != 0, underline = bytes.u8(body + 10) != 0)
            // Font index 4 is never stored: the fifth FONT record is index 5.
            if (fonts.size == 4) fonts += CellStyle()
        }

        private fun format(body: Int, length: Int) {
            val id = bytes.u16(body)
            formats[id] = if (length >= 5 && bytes.u8(body + 4) in 0..1 && length >= 5 + bytes.u16(body + 2) * (if (bytes.u8(body + 4) and 1 != 0) 2 else 1))
                unicode(body + 2, 2) ?: return
            else String(bytes.slice(body + 3, bytes.u8(body + 2)), codepage)
        }

        /** BIFF8 XF: font, number format and the alignment byte (horizontal in bits 0–2, wrap in bit 3, vertical in bits 4–6). */
        private fun xf(body: Int) {
            val font = bytes.u16(body)
            val format = bytes.u16(body + 2)
            val alignment = bytes.u8(body + 6)
            styles += (fonts.getOrNull(font) ?: CellStyle()).copy(
                format = formats[format] ?: CellFormat.builtIn(format),
                align = when (alignment and 7) { 1 -> Align.START; 2, 6 -> Align.CENTER; 3 -> Align.END; 5, 7 -> Align.JUSTIFY; else -> null },
                vertical = when ((alignment shr 4) and 7) { 0 -> VerticalAlign.TOP; 1, 3, 4 -> VerticalAlign.CENTER; else -> VerticalAlign.BOTTOM },
                wrap = alignment and 8 != 0,
            )
        }

        private fun boundSheet(body: Int, length: Int) {
            if (length < 8) return
            val name = runCatching {
                // BIFF8 names carry a flags byte; BIFF5 names are code-page bytes.
                if (length >= 8 + bytes.u8(body + 6) && bytes.u8(body + 7) in 0..1) unicode(body + 6, 1)
                else String(bytes.slice(body + 7, bytes.u8(body + 6)), codepage)
            }.getOrNull().orEmpty()
            sheets += BoundSheet(bytes.i32(body), name, visible = bytes.u8(body + 4) and 3 == 0, worksheet = bytes.u8(body + 5) == 0)
        }

        private fun long(at: Int) = bytes.u32(at) or (bytes.u32(at + 4) shl 32)

        private fun rk(value: Int): Double {
            val number = if (value and 2 != 0) (value shr 2).toDouble()
                else java.lang.Double.longBitsToDouble((value.toLong() and 0xFFFFFFFCL) shl 32)
            return if (value and 1 != 0) number / 100 else number
        }

        /** A string with an 8-bit or 16-bit length and a flags byte (BIFF8); rich-text and phonetic data are skipped. */
        private fun unicode(at: Int, lengthBytes: Int): String? = runCatching {
            val count = if (lengthBytes == 1) bytes.u8(at) else bytes.u16(at)
            val flags = bytes.u8(at + lengthBytes)
            var start = at + lengthBytes + 1
            if (flags and 8 != 0) start += 2
            if (flags and 4 != 0) start += 4
            if (flags and 1 != 0) bytes.utf16(start, count) else String(bytes.slice(start, count), Charsets.ISO_8859_1)
        }.getOrNull()

        /** LABEL: BIFF8 has a flags byte; BIFF5 stores code-page bytes right after the length. */
        private fun label(at: Int, length: Int): String? = runCatching {
            val count = bytes.u16(at)
            if (length >= 3 + count && bytes.u8(at + 2) in setOf(0, 1)) {
                val flags = bytes.u8(at + 2)
                if (flags and 1 != 0) bytes.utf16(at + 3, count) else String(bytes.slice(at + 3, count), Charsets.ISO_8859_1)
            } else String(bytes.slice(at + 2, count), codepage)
        }.getOrNull()

        /** A record's string that may continue in CONTINUE records (a formula's STRING result). */
        private fun continued(body: Int, length: Int): String {
            val reader = BiffSegments(bytes, segments(body, length))
            val count = reader.u16()
            val flags = reader.u8()
            return reader.characters(count, flags and 1 != 0)
        }

        private fun segments(body: Int, length: Int): List<Pair<Int, Int>> {
            val segments = mutableListOf(body to body + length)
            var next = body + length
            while (next + 4 <= bytes.size && bytes.u16(next) == CONTINUE) {
                val size = bytes.u16(next + 2)
                segments += (next + 4) to minOf(next + 4 + size, bytes.size)
                next += 4 + size
            }
            return segments
        }

        /** The shared string table, read across its CONTINUE records (each restarts the character width). */
        private fun readStrings(body: Int, length: Int) {
            val reader = BiffSegments(bytes, segments(body, length))
            reader.skip(4)
            val unique = reader.i32()
            repeat(unique.coerceIn(0, 2_000_000)) {
                if (!reader.has(3)) return
                if (strings.size % 4096 == 0) checkActive()
                val count = reader.u16()
                val flags = reader.u8()
                val runs = if (flags and 8 != 0) reader.u16() else 0
                val extra = if (flags and 4 != 0) reader.i32() else 0
                strings += reader.characters(count, flags and 1 != 0)
                reader.skip(runs * 4 + extra.coerceAtLeast(0))
            }
        }

        companion object {
            const val BOF = 0x0809
            const val EOF = 0x000A
            const val FILEPASS = 0x002F
            const val CODEPAGE = 0x0042
            const val DATEMODE = 0x0022
            const val FONT = 0x0031
            const val FORMAT = 0x041E
            const val XF = 0x00E0
            const val BOUNDSHEET = 0x0085
            const val SST = 0x00FC
            const val CONTINUE = 0x003C
            const val LABEL_SST = 0x00FD
            const val LABEL = 0x0204
            const val RSTRING = 0x00D6
            const val NUMBER = 0x0203
            const val RK = 0x027E
            const val MULRK = 0x00BD
            const val BOOLERR = 0x0205
            const val FORMULA = 0x0006
            const val STRING = 0x0207
            const val SHRFMLA = 0x04BC
            const val ARRAY = 0x0221
            const val TABLE = 0x0236
            const val MERGEDCELLS = 0x00E5
            const val COLINFO = 0x007D
            const val STANDARDWIDTH = 0x0099
            const val ROW = 0x0208
            val ERRORS = mapOf(0x00 to "#NULL!", 0x07 to "#DIV/0!", 0x0F to "#VALUE!", 0x17 to "#REF!", 0x1D to "#NAME?", 0x24 to "#NUM!", 0x2A to "#N/A")
        }
    }
}

/** Reads across BIFF record segments; characters continuing into the next segment restart with a width flag. */
internal class BiffSegments(private val bytes: LittleEndian, private val segments: List<Pair<Int, Int>>) {
    private var segment = 0
    private var at = segments.first().first

    private fun advance() {
        while (segment < segments.size && at >= segments[segment].second) {
            segment++
            if (segment < segments.size) at = segments[segment].first
        }
    }

    fun has(count: Int): Boolean { advance(); return segment < segments.size && at + count <= segments[segment].second || segment + 1 < segments.size }

    fun u8(): Int { advance(); return bytes.u8(at).also { at += 1 } }
    fun u16(): Int { advance(); return bytes.u16(at).also { at += 2 } }
    fun i32(): Int { advance(); return bytes.i32(at).also { at += 4 } }

    fun skip(count: Int) {
        var left = count
        while (left > 0) {
            advance()
            if (segment >= segments.size) return
            val step = minOf(left, segments[segment].second - at)
            at += step; left -= step
        }
    }

    fun characters(count: Int, wide: Boolean): String {
        val out = StringBuilder(count)
        var unicode = wide
        var left = count
        while (left > 0) {
            if (at >= segments.getOrNull(segment)?.second ?: return out.toString()) {
                segment++
                if (segment >= segments.size) return out.toString()
                at = segments[segment].first
                unicode = bytes.u8(at) and 1 != 0
                at += 1
            }
            val available = (segments[segment].second - at) / if (unicode) 2 else 1
            val take = minOf(left, available)
            if (take <= 0) { at = segments[segment].second; continue }
            if (unicode) out.append(bytes.utf16(at, take)) else out.append(String(bytes.slice(at, take), Charsets.ISO_8859_1))
            at += take * if (unicode) 2 else 1
            left -= take
        }
        return out.toString()
    }
}
