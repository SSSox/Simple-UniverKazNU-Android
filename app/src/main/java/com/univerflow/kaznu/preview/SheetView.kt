package com.univerflow.kaznu.preview

import java.io.InputStream
import java.util.Locale
import java.util.TreeMap
import kotlin.math.roundToInt

/** A cell's value as the file stores it, before its number format. */
internal sealed interface CellValue {
    class Text(val value: String) : CellValue
    class Number(val value: Double) : CellValue
    class Bool(val value: Boolean) : CellValue
    class Error(val code: String) : CellValue
}

internal enum class VerticalAlign { TOP, CENTER, BOTTOM }

/** How a cell looks, whatever spreadsheet format it came from: its number format, font and alignment. */
internal data class CellStyle(
    val format: String = "General",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val align: Align? = null,
    val vertical: VerticalAlign = VerticalAlign.BOTTOM,
    /** "Wrap text": the cell grows in height instead of running over empty neighbours. */
    val wrap: Boolean = false,
)

internal class SheetCell(val value: CellValue, val style: CellStyle)

internal data class CellRange(val firstRow: Int, val firstColumn: Int, val lastRow: Int, val lastColumn: Int)

/** Columns [first]..[last]: [width] in characters of the default font, as Excel stores it, or null for the default. */
internal class ColumnSpec(val first: Int, val last: Int, val width: Double?, val hidden: Boolean)

/** One worksheet: cells by row and column (from 0), merged ranges, column widths and hidden rows. */
internal class Sheet(val name: String, private val budget: CellBudget = CellBudget()) {
    val rows = TreeMap<Int, TreeMap<Int, SheetCell>>()
    val merges = mutableListOf<CellRange>()
    val columns = mutableListOf<ColumnSpec>()
    val hiddenRows = HashSet<Int>()
    var defaultWidth: Double? = null
    /** The first row whose cells were left out past [CELL_LIMIT] or the workbook's budget: the sheet is shown up to the row before it. */
    var truncatedAt: Int? = null
        private set
    private var count = 0
    private val specs = HashMap<Int, ColumnSpec?>()
    private var specsOf = 0

    fun put(row: Int, column: Int, cell: SheetCell) {
        if (row !in 0 until MAX_ROWS || column !in 0 until MAX_COLUMNS) return
        if (cell.value is CellValue.Text && cell.value.value.isEmpty()) return
        if (count >= CELL_LIMIT || !budget.take()) { truncatedAt = minOf(truncatedAt ?: row, row); return }
        count++
        rows.getOrPut(row) { TreeMap() }[column] = cell
    }

    fun merge(range: CellRange) {
        if (range.firstRow < 0 || range.firstColumn < 0 || range.lastRow < range.firstRow || range.lastColumn < range.firstColumn ||
            range.lastRow >= MAX_ROWS || range.lastColumn >= MAX_COLUMNS) return
        if (range.firstRow == range.lastRow && range.firstColumn == range.lastColumn) return
        if (merges.size < MERGE_LIMIT) merges += range
    }

    fun hideRow(row: Int) { if (row in 0 until MAX_ROWS && hiddenRows.size < MAX_ROWS) hiddenRows += row }

    fun column(first: Int, last: Int, width: Double?, hidden: Boolean) {
        if (first < 0 || last < first || first >= MAX_COLUMNS || columns.size >= MAX_COLUMNS) return
        columns += ColumnSpec(first, minOf(last, MAX_COLUMNS - 1), width?.takeIf { it.isFinite() && it >= 0 }, hidden)
    }

    fun cell(row: Int, column: Int) = rows[row]?.get(column)

    /** The last column's spec wins, as in Excel's own reading of overlapping ranges. */
    fun spec(column: Int): ColumnSpec? {
        if (specsOf != columns.size) { specs.clear(); specsOf = columns.size }
        if (column !in specs) specs[column] = columns.lastOrNull { column in it.first..it.last }
        return specs[column]
    }

    companion object {
        const val MAX_ROWS = 1_048_576
        const val MAX_COLUMNS = 16_384
        const val CELL_LIMIT = 500_000
        const val WORKBOOK_LIMIT = 1_000_000
        const val MERGE_LIMIT = 20_000
    }
}

/** The cells all sheets of one workbook may still hold: a file of a thousand full sheets must not fill memory. */
internal class CellBudget(private var left: Int = Sheet.WORKBOOK_LIMIT) {
    fun take(): Boolean = left > 0 && run { left--; true }
}

/** The visible sheets of a workbook in tab order; [date1904] is the workbook's date system. */
internal class Workbook(val sheets: List<Sheet>, val date1904: Boolean = false)

/** Texts of the spreadsheet and presentation views, in the app's language. */
class PreviewLabels(
    /** The language of the numbers' separators and month names (ru, kk or en). */
    val locale: Locale,
    val sheets: String = "",
    val emptySheet: String = "",
    val emptyDeck: String = "",
    val notes: String = "",
    /** "Slide 2 of 9". */
    val slide: (Int, Int) -> String = { number, total -> "$number / $total" },
    /** "The first 1 200 rows are shown": a sheet too large for the view. */
    val sheetTruncated: (Int) -> String = { rows -> "1–$rows" },
)

/**
 * The table view of any spreadsheet, whatever its name says: Excel .xlsx/.xlsm and Excel 97–2003 .xls are read into one
 * model and drawn by [SheetHtml], so the same workbook looks the same in both formats.
 */
object SheetView {
    fun html(bytes: ByteArray, labels: PreviewLabels, checkActive: () -> Unit = {}): String =
        SheetHtml(labels).render(read(bytes, checkActive))

    /** The viewer's way in: an .xlsx is streamed and an .xls read in place; a sheet too large is shown in part. */
    internal fun html(file: FileAccess, labels: PreviewLabels, checkActive: () -> Unit): String =
        SheetHtml(labels).render(read(file, checkActive))

    internal fun read(bytes: ByteArray, checkActive: () -> Unit = {}): Workbook = read(FileAccess.of(bytes), checkActive)

    internal fun read(file: FileAccess, checkActive: () -> Unit = {}): Workbook = when {
        file.zip -> file.stream().use { XlsxReader.read(it, checkActive) }
        file.compound -> XlsReader.read(CompoundFile(file.source()), checkActive)
        else -> throw UnsupportedDocumentException("Not a spreadsheet")
    }

    /** The search text: each visible sheet's name, then its rows, cells separated by tabs, as the table view shows them. */
    internal fun text(book: Workbook): String {
        val out = StringBuilder()
        for (sheet in book.sheets) {
            if (out.isNotEmpty()) out.append('\n')
            out.append(sheet.name).append('\n')
            for ((row, cells) in sheet.rows) {
                if (row in sheet.hiddenRows) continue
                val line = cells.filterKeys { sheet.spec(it)?.hidden != true }.values
                    .map { CellFormat.format(it.value, it.style.format, book.date1904, Locale.ROOT).replace('\n', ' ') }
                    .filter { it.isNotBlank() }
                if (line.isNotEmpty()) out.append(line.joinToString("\t")).append('\n')
                if (out.length > TextExtract.LIMIT) return out.toString().take(TextExtract.LIMIT).trim()
            }
        }
        return out.toString().trim()
    }
}

/**
 * HTML of a workbook: a bar of sheet links, then each sheet as a grid with Excel's column letters and row numbers, merged
 * cells, column widths and hidden rows and columns left out. Wide grids scroll sideways on their own. Text is escaped.
 */
internal class SheetHtml(private val labels: PreviewLabels) {
    private val out = StringBuilder()

    fun render(book: Workbook): String {
        if (book.sheets.size > 1) {
            out.append("<nav class=\"sheets\" aria-label=\"").append(PreviewHtml.escape(labels.sheets)).append("\">")
            book.sheets.forEachIndexed { index, sheet ->
                out.append("<a href=\"#sheet-").append(index + 1).append("\">").append(PreviewHtml.escape(sheet.name.ifBlank { "${index + 1}" })).append("</a>")
            }
            out.append("</nav>")
        }
        book.sheets.forEachIndexed { index, sheet -> sheet(index, sheet, book.date1904) }
        if (book.sheets.isEmpty()) out.append("<p class=\"empty\">").append(PreviewHtml.escape(labels.emptySheet)).append("</p>")
        return out.toString()
    }

    private fun sheet(index: Int, sheet: Sheet, date1904: Boolean) {
        val name = PreviewHtml.escape(sheet.name.ifBlank { "${index + 1}" })
        out.append("<section class=\"sheet\" id=\"sheet-").append(index + 1).append("\"><h2>").append(name).append("</h2>")
        // The grid ends at the last cell with a value (or a merge starting at one); formatting alone adds no rows.
        var lastRow = -1
        var lastColumn = -1
        sheet.rows.forEach { (row, cells) ->
            if (row in sheet.hiddenRows) return@forEach
            val last = cells.descendingKeySet().firstOrNull { sheet.spec(it)?.hidden != true } ?: return@forEach
            lastRow = maxOf(lastRow, row); lastColumn = maxOf(lastColumn, last)
        }
        sheet.merges.forEach { range -> if (sheet.cell(range.firstRow, range.firstColumn) != null) { lastRow = maxOf(lastRow, range.lastRow); lastColumn = maxOf(lastColumn, range.lastColumn) } }
        // The last text of a row runs on into empty columns, past the used range too (up to a few), as Excel draws it.
        sheet.rows.forEach { (row, cells) ->
            if (row > lastRow || row in sheet.hiddenRows) return@forEach
            val (column, cell) = cells.descendingMap().entries.firstOrNull { sheet.spec(it.key)?.hidden != true } ?: return@forEach
            if (!spills(cell)) return@forEach
            val needed = textWidth(cell, date1904)
            var end = column
            var width = px(sheet, column)
            while (width < needed && end < column + SPILL_COLUMNS && end + 1 < Sheet.MAX_COLUMNS) {
                end++
                if (sheet.spec(end)?.hidden != true) width += px(sheet, end)
            }
            lastColumn = maxOf(lastColumn, end)
        }
        // A sheet too large for the page is shown from its start: whole rows, as many as fit, and a notice says so.
        var truncated = sheet.truncatedAt != null || lastColumn >= COLUMN_LIMIT
        val columns = (0..minOf(lastColumn, COLUMN_LIMIT - 1)).filter { sheet.spec(it)?.hidden != true }
        val lastShown = minOf(lastRow, (sheet.truncatedAt ?: Int.MAX_VALUE) - 1)
        val allowed = (GRID_LIMIT / columns.size.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        val rows = ArrayList<Int>()
        var next = 0
        while (next <= lastShown && rows.size < allowed) { if (next !in sheet.hiddenRows) rows += next; next++ }
        if (next <= lastShown && (next..lastShown).any { it !in sheet.hiddenRows && it in sheet.rows }) truncated = true
        if (rows.isEmpty() || columns.isEmpty()) {
            out.append("<p class=\"empty\">").append(PreviewHtml.escape(labels.emptySheet)).append("</p></section>")
            return
        }
        // A merge is drawn at its first visible cell and spans only visible rows and columns.
        val anchors = HashMap<Long, Pair<CellRange, IntArray>>()
        val covered = HashSet<Long>()
        // Merged ranges of a real sheet do not overlap, so together they fit the grid; what a file adds past that is not drawn.
        var room = rows.size.toLong() * columns.size
        for (range in sheet.merges) {
            val visibleRows = rows.subList(firstAtLeast(rows, range.firstRow), firstAtLeast(rows, range.lastRow + 1))
            val visibleColumns = columns.subList(firstAtLeast(columns, range.firstColumn), firstAtLeast(columns, range.lastColumn + 1))
            if (visibleRows.isEmpty() || visibleColumns.isEmpty()) continue
            val key = key(visibleRows.first(), visibleColumns.first())
            if (key in covered || key in anchors) continue
            val cells = visibleRows.size.toLong() * visibleColumns.size
            if (cells > room) continue
            room -= cells
            anchors[key] = range to intArrayOf(visibleRows.size, visibleColumns.size)
            for (row in visibleRows) for (column in visibleColumns) if (row != visibleRows.first() || column != visibleColumns.first()) covered += key(row, column)
        }
        val width = columns.sumOf { px(sheet, it) } + HEADER_WIDTH
        val notice = out.length
        out.append("<div class=\"grid\" role=\"region\" tabindex=\"0\" aria-label=\"").append(name).append("\"><table style=\"width:").append(width)
            .append("px\"><colgroup><col style=\"width:").append(HEADER_WIDTH).append("px\">")
        columns.forEach { out.append("<col style=\"width:").append(px(sheet, it)).append("px\">") }
        out.append("</colgroup><thead><tr><th class=\"corner\"></th>")
        columns.forEach { out.append("<th scope=\"col\">").append(columnName(it)).append("</th>") }
        out.append("</tr></thead><tbody>")
        var shown = 0
        for (row in rows) {
            if (out.length > WordHtml.OUTPUT_LIMIT) { truncated = true; break }
            shown = row + 1
            out.append("<tr><th scope=\"row\">").append(row + 1).append("</th>")
            var index = 0
            while (index < columns.size) {
                val column = columns[index++]
                val key = key(row, column)
                if (key in covered) continue
                val merge = anchors[key]
                val cell = merge?.let { sheet.cell(it.first.firstRow, it.first.firstColumn) } ?: sheet.cell(row, column)
                out.append("<td")
                merge?.second?.let { (rowSpan, columnSpan) ->
                    if (columnSpan > 1) out.append(" colspan=\"").append(columnSpan).append('"')
                    if (rowSpan > 1) out.append(" rowspan=\"").append(rowSpan).append('"')
                }
                // Unwrapped text runs over the empty cells to its right until it fits, as in Excel.
                if (merge == null && cell != null && spills(cell)) {
                    val needed = textWidth(cell, date1904)
                    var width = px(sheet, column)
                    var span = 1
                    while (width < needed && index < columns.size) {
                        val next = columns[index]
                        if (next != column + span || sheet.cell(row, next) != null || key(row, next) in covered || key(row, next) in anchors) break
                        width += px(sheet, next); span++; index++
                    }
                    if (span > 1) out.append(" colspan=\"").append(span).append('"')
                }
                if (cell != null) {
                    classes(cell).takeIf { it.isNotEmpty() }?.let { out.append(" class=\"").append(it).append('"') }
                    out.append('>').append(PreviewHtml.escape(CellFormat.format(cell.value, cell.style.format, date1904, labels.locale)))
                } else out.append('>')
                out.append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</tbody></table></div></section>")
        if (truncated) out.insert(notice, "<p class=\"notice\">" + PreviewHtml.escape(labels.sheetTruncated(shown)) + "</p>")
    }

    private fun spills(cell: SheetCell) = cell.value is CellValue.Text && !cell.style.wrap && (cell.style.align == null || cell.style.align == Align.START)

    /** A rough width of the cell's longest line in the grid's 14 px font, with the cell padding. */
    private fun textWidth(cell: SheetCell, date1904: Boolean): Int {
        val line = CellFormat.format(cell.value, cell.style.format, date1904, labels.locale).lines().maxOfOrNull { it.length } ?: 0
        return (line * (if (cell.style.bold) 8.0 else 7.2)).roundToInt() + 12
    }

    private fun classes(cell: SheetCell): String {
        val style = cell.style
        val names = ArrayList<String>(4)
        when (style.align) {
            Align.START -> names += "al"
            Align.CENTER -> names += "ac"
            Align.END -> names += "ar"
            Align.JUSTIFY -> names += "aj"
            // Excel's "General" alignment: numbers right, logical values and errors centred, text left.
            null -> when (cell.value) {
                is CellValue.Number -> names += "ar"
                is CellValue.Bool, is CellValue.Error -> names += "ac"
                is CellValue.Text -> Unit
            }
        }
        if (style.vertical == VerticalAlign.TOP) names += "vt"
        if (style.vertical == VerticalAlign.CENTER) names += "vm"
        if (style.wrap) names += "w"
        if (style.bold) names += "b"
        if (style.italic) names += "i"
        if (style.underline) names += "u"
        if (style.strike) names += "s"
        return names.joinToString(" ")
    }

    private fun px(sheet: Sheet, column: Int): Int {
        val width = sheet.spec(column)?.width ?: sheet.defaultWidth ?: return DEFAULT_WIDTH
        return (width * 7).roundToInt().coerceIn(24, 560)
    }

    companion object {
        private const val DEFAULT_WIDTH = 64
        private const val HEADER_WIDTH = 44
        private const val GRID_LIMIT = 600_000L
        private const val COLUMN_LIMIT = 2_000
        private const val SPILL_COLUMNS = 10

        private fun key(row: Int, column: Int) = (row.toLong() shl 20) or column.toLong()

        /** Index of the first element of the sorted [list] that is at least [value]. */
        private fun firstAtLeast(list: List<Int>, value: Int): Int {
            var low = 0
            var high = list.size
            while (low < high) { val middle = (low + high) ushr 1; if (list[middle] < value) low = middle + 1 else high = middle }
            return low
        }

        /** Excel's column letters: 0 is A, 25 is Z, 26 is AA. */
        fun columnName(column: Int): String {
            val name = StringBuilder()
            var rest = column + 1
            while (rest > 0) { rest--; name.append('A' + rest % 26); rest /= 26 }
            return name.reverse().toString()
        }

        /** "B12" to (11, 1); null for anything else. */
        fun parseReference(reference: String): Pair<Int, Int>? {
            val match = REFERENCE.matchEntire(reference.uppercase(Locale.ROOT).replace("$", "")) ?: return null
            var column = 0
            match.groupValues[1].forEach { column = column * 26 + (it - 'A' + 1) }
            val row = match.groupValues[2].toIntOrNull() ?: return null
            return (row - 1) to (column - 1)
        }

        private val REFERENCE = Regex("([A-Z]{1,3})([0-9]{1,7})")
    }
}
