package com.univerflow.kaznu.preview

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Excel .xlsx/.xlsm ([ECMA-376] SpreadsheetML) into the shared [Workbook] model. Formulas are not calculated (the
 * value Excel saved is shown); parts are read with [SafeXml] within limits.
 */
internal object XlsxReader {
    private const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PART_LIMIT = 64 * 1024 * 1024
    private const val TOTAL_LIMIT = 160 * 1024 * 1024
    private const val SHARED_LIMIT = 2_000_000
    private const val ENTRY_LIMIT = 4_000
    private const val NODE_LIMIT = 4_000_000

    fun read(bytes: ByteArray, checkActive: () -> Unit = {}): Workbook = read(ByteArrayInputStream(bytes), checkActive)

    /** One pass over the package as a stream: the workbook's XML parts are kept (sheets may precede the shared strings). */
    fun read(input: InputStream, checkActive: () -> Unit = {}): Workbook {
        val parts = HashMap<String, ByteArray>()
        var entries = 0
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                checkActive()
                val entry = zip.nextEntry ?: break
                if (++entries > ENTRY_LIMIT) throw PreviewLimitException()
                val name = entry.name.replace('\\', '/').removePrefix("/")
                if (name in setOf("xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/sharedStrings.xml", "xl/styles.xml") ||
                    (name.startsWith("xl/") && name.endsWith(".xml") && "/worksheets/" in "/$name")) {
                    val part = Bounded.read(zip, PART_LIMIT, checkActive)
                    total += part.size
                    if (total > TOTAL_LIMIT) throw PreviewLimitException()
                    parts[name] = part
                }
            }
        }
        val workbook = parts["xl/workbook.xml"] ?: throw UnsupportedDocumentException("Not an Excel workbook")
        val targets = parts["xl/_rels/workbook.xml.rels"]?.let(::relations) ?: emptyMap()
        val strings = parts["xl/sharedStrings.xml"]?.let { sharedStrings(it, checkActive) } ?: emptyList()
        val styles = parts["xl/styles.xml"]?.let(::styles) ?: listOf(CellStyle())
        var date1904 = false
        val sheets = mutableListOf<Triple<String, String?, String?>>()
        SafeXml.parse(workbook, object : DefaultHandler() {
            override fun startElement(uri: String, local: String, qName: String, attributes: Attributes) {
                if (uri != S) return
                if (local == "workbookPr") date1904 = attributes.getValue("", "date1904").let { it == "1" || it == "true" }
                if (local == "sheet" && sheets.size < 1024) sheets += Triple(attributes.getValue("", "name").orEmpty(),
                    attributes.getValue(R, "id"), attributes.getValue("", "state"))
            }
        })
        val result = sheets.filter { it.third.isNullOrEmpty() || it.third == "visible" }.mapNotNull { (name, id, _) ->
            val target = id?.let(targets::get) ?: return@mapNotNull null
            val part = parts[resolve(target)] ?: return@mapNotNull null
            Sheet(name).also { sheet -> SafeXml.parse(part, SheetHandler(sheet, strings, styles, checkActive)) }
        }
        return Workbook(result, date1904)
    }

    /** "worksheets/sheet1.xml" and "/xl/worksheets/sheet1.xml" both name the part in xl/. */
    private fun resolve(target: String): String {
        val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
        val parts = ArrayDeque<String>()
        path.split('/').forEach { segment -> when (segment) { "", "." -> Unit; ".." -> parts.removeLastOrNull(); else -> parts.addLast(segment) } }
        return parts.joinToString("/")
    }

    private fun relations(bytes: ByteArray): Map<String, String> {
        val result = HashMap<String, String>()
        SafeXml.parse(bytes, object : DefaultHandler() {
            override fun startElement(uri: String, local: String, qName: String, attributes: Attributes) {
                if (local != "Relationship" || attributes.getValue("", "TargetMode").equals("External", true)) return
                val id = attributes.getValue("", "Id") ?: return
                result[id] = attributes.getValue("", "Target") ?: return
            }
        })
        return result
    }

    /** Each `si` is plain text or rich-text runs; phonetic guides (`rPh`) are not part of the text. */
    private fun sharedStrings(bytes: ByteArray, checkActive: () -> Unit): List<String> {
        val result = ArrayList<String>()
        val text = StringBuilder()
        var inText = false
        var phonetic = 0
        var nodes = 0
        SafeXml.parse(bytes, object : DefaultHandler() {
            override fun startElement(uri: String, local: String, qName: String, attributes: Attributes) {
                if (++nodes > NODE_LIMIT) throw PreviewLimitException()
                if (nodes % 4096 == 0) checkActive()
                if (uri != S) return
                when (local) {
                    "si" -> text.setLength(0)
                    "rPh" -> phonetic++
                    "t" -> inText = phonetic == 0
                }
            }
            override fun endElement(uri: String, local: String, qName: String) {
                if (uri != S) return
                when (local) {
                    // Past the limit, cells that use the remaining strings are left out with the rest of a truncated sheet.
                    "si" -> if (result.size < SHARED_LIMIT) result += text.toString()
                    "rPh" -> phonetic--
                    "t" -> inText = false
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText && text.length < 32_767) text.appendRange(ch, start, start + length)
            }
        })
        return result
    }

    /** Cell formats (`cellXfs`) with their number format codes and fonts resolved. */
    private fun styles(bytes: ByteArray): List<CellStyle> {
        val formats = HashMap<Int, String>()
        val fonts = mutableListOf<CellStyle>()
        val cells = mutableListOf<CellStyle>()
        var section = ""
        var font: CellStyle? = null
        SafeXml.parse(bytes, object : DefaultHandler() {
            fun on(attributes: Attributes) = attributes.getValue("", "val").let { it == null || it == "1" || it == "true" }
            override fun startElement(uri: String, local: String, qName: String, attributes: Attributes) {
                if (uri != S) return
                when (local) {
                    "numFmt" -> attributes.getValue("", "numFmtId")?.toIntOrNull()?.let { id -> formats[id] = attributes.getValue("", "formatCode").orEmpty() }
                    "fonts", "cellXfs", "cellStyleXfs", "dxfs" -> section = local
                    "font" -> if (section == "fonts") font = CellStyle()
                    "b" -> font = font?.copy(bold = on(attributes))
                    "i" -> font = font?.copy(italic = on(attributes))
                    "strike" -> font = font?.copy(strike = on(attributes))
                    "u" -> font = font?.copy(underline = attributes.getValue("", "val") != "none")
                    "xf" -> if (section == "cellXfs" && cells.size < 65_536) {
                        val id = attributes.getValue("", "numFmtId")?.toIntOrNull() ?: 0
                        val base = fonts.getOrNull(attributes.getValue("", "fontId")?.toIntOrNull() ?: 0) ?: CellStyle()
                        cells += base.copy(format = formats[id] ?: CellFormat.builtIn(id))
                    }
                    "alignment" -> if (section == "cellXfs" && cells.isNotEmpty()) cells[cells.size - 1] = cells.last().copy(
                        align = when (attributes.getValue("", "horizontal")) {
                            "left" -> Align.START; "center", "centerContinuous" -> Align.CENTER; "right" -> Align.END
                            "justify", "distributed" -> Align.JUSTIFY; else -> null
                        },
                        vertical = when (attributes.getValue("", "vertical")) {
                            "top" -> VerticalAlign.TOP; "center", "justify", "distributed" -> VerticalAlign.CENTER; else -> VerticalAlign.BOTTOM
                        },
                        wrap = attributes.getValue("", "wrapText").let { it == "1" || it == "true" },
                    )
                }
            }
            override fun endElement(uri: String, local: String, qName: String) {
                if (uri != S) return
                if (local == "font" && section == "fonts") { font?.let { if (fonts.size < 65_536) fonts += it }; font = null }
                if (local == section) section = ""
            }
        })
        return cells.ifEmpty { listOf(CellStyle()) }
    }

    /** Rows and cells may omit their references; positions then continue from the previous one, as Excel reads them. */
    private class SheetHandler(
        val sheet: Sheet,
        val strings: List<String>,
        val styles: List<CellStyle>,
        val checkActive: () -> Unit,
    ) : DefaultHandler() {
        private var nodes = 0
        private var row = -1
        private var column = -1
        private var type: String? = null
        private var style = 0
        private val value = StringBuilder()
        private var inValue = false
        private var phonetic = 0
        private var hasValue = false

        override fun startElement(uri: String, local: String, qName: String, attributes: Attributes) {
            if (++nodes > NODE_LIMIT) throw PreviewLimitException()
            if (nodes % 4096 == 0) checkActive()
            if (uri != S) return
            when (local) {
                "sheetFormatPr" -> sheet.defaultWidth = attributes.getValue("", "defaultColWidth")?.toDoubleOrNull()
                "col" -> {
                    val first = attributes.getValue("", "min")?.toIntOrNull() ?: return
                    val last = attributes.getValue("", "max")?.toIntOrNull() ?: first
                    val width = attributes.getValue("", "width")?.toDoubleOrNull()?.takeIf { attributes.getValue("", "customWidth") != null || it > 0 }
                    sheet.column(first - 1, last - 1, width, attributes.getValue("", "hidden").let { it == "1" || it == "true" })
                }
                "row" -> {
                    row = attributes.getValue("", "r")?.toIntOrNull()?.minus(1) ?: (row + 1)
                    column = -1
                    if (attributes.getValue("", "hidden").let { it == "1" || it == "true" }) sheet.hideRow(row)
                }
                "c" -> {
                    val position = attributes.getValue("", "r")?.let(SheetHtml::parseReference)
                    if (position != null) { row = position.first; column = position.second } else column++
                    type = attributes.getValue("", "t")
                    style = attributes.getValue("", "s")?.toIntOrNull() ?: 0
                    value.setLength(0)
                    hasValue = false
                }
                "v" -> { inValue = true; hasValue = true }
                "rPh" -> phonetic++
                "t" -> if (type == "inlineStr" && phonetic == 0) { inValue = true; hasValue = true }
                "mergeCell" -> attributes.getValue("", "ref")?.let { reference ->
                    val first = SheetHtml.parseReference(reference.substringBefore(':')) ?: return
                    val last = SheetHtml.parseReference(reference.substringAfter(':', reference)) ?: return
                    sheet.merge(CellRange(first.first, first.second, last.first, last.second))
                }
            }
        }

        override fun endElement(uri: String, local: String, qName: String) {
            if (uri != S) return
            when (local) {
                "v", "t" -> inValue = false
                "rPh" -> phonetic--
                "c" -> if (hasValue) cell()
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inValue && value.length < 32_767) value.appendRange(ch, start, start + length)
        }

        private fun cell() {
            val raw = value.toString()
            val content: CellValue = when (type) {
                "s" -> CellValue.Text(raw.trim().toIntOrNull()?.let(strings::getOrNull) ?: return)
                "str", "inlineStr", "d" -> CellValue.Text(raw)
                "b" -> CellValue.Bool(raw.trim() == "1" || raw.trim().lowercase(Locale.ROOT) == "true")
                "e" -> CellValue.Error(raw.trim())
                else -> CellValue.Number(raw.trim().toDoubleOrNull() ?: return)
            }
            sheet.put(row, column, SheetCell(content, styles.getOrNull(style) ?: styles[0]))
        }
    }
}
