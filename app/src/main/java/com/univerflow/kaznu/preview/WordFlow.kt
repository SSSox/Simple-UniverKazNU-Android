package com.univerflow.kaznu.preview

/**
 * The flow of a binary Word-like document (.doc, .rtf): paragraphs in order, cells and rows marked by their end marks
 * as Word stores them. [html] lays it out with [WordHtml]; [text] is the plain text for the search.
 */
internal object WordFlow {
    sealed interface Item {
        class Text(val value: String, val style: RunStyle) : Item
        object Tab : Item
        object Break : Item
        /** A picture already kept by [PreviewMedia], at [src]. */
        class Picture(val src: String) : Item
        class LinkStart(val target: String?) : Item
        object LinkEnd : Item
    }

    /** A row's cells: right boundaries in twips (the first is the row's left edge), vertical and old-style horizontal merges. */
    class RowLayout(val bounds: IntArray, val merges: List<Merge>, val covered: List<Boolean>)

    data class Props(
        val heading: Int = 0, val align: Align? = null, val listItem: Boolean = false,
        val inTable: Boolean = false, val depth: Int = 0, val rowEnd: Boolean = false, val innerRowEnd: Boolean = false,
        val row: RowLayout? = null,
    ) {
        /** Table depth: 0 outside tables; nested tables are drawn flat inside the outer cell. */
        val level get() = if (depth > 0) depth else if (inTable) 1 else 0
    }

    class Paragraph(val items: List<Item>, val props: Props, val cellEnd: Boolean = false)

    fun html(paragraphs: List<Paragraph>): String {
        val html = WordHtml()
        Layout(html).write(paragraphs)
        return html.result()
    }

    fun text(paragraphs: List<Paragraph>): String = paragraphs.joinToString("\n") { paragraph ->
        paragraph.items.joinToString("") { item -> when (item) { is Item.Text -> item.value; Item.Tab -> "\t"; Item.Break -> "\n"; else -> "" } }
    }.replace(Regex("\n{3,}"), "\n\n").trim()

    private class Layout(private val html: WordHtml) {
        fun write(paragraphs: List<Paragraph>) {
            var index = 0
            while (index < paragraphs.size) {
                if (paragraphs[index].props.level == 0) { paragraph(paragraphs[index]); index++; continue }
                val start = index
                while (index < paragraphs.size && paragraphs[index].props.level > 0) index++
                table(paragraphs.subList(start, index))
            }
        }

        private fun paragraph(paragraph: Paragraph) {
            html.startParagraph()
            paragraph.items.forEach { item ->
                when (item) {
                    is Item.Text -> html.text(item.value, item.style)
                    Item.Tab -> html.tab()
                    Item.Break -> html.lineBreak()
                    is Item.Picture -> html.picture(item.src)
                    is Item.LinkStart -> html.startLink(item.target)
                    Item.LinkEnd -> html.endLink()
                }
            }
            val props = paragraph.props
            html.endParagraph(ParagraphStyle(props.heading, props.listItem, props.align))
        }

        private class Row(val cells: List<List<Paragraph>>, val layout: RowLayout?)

        private fun table(paragraphs: List<Paragraph>) {
            val rows = mutableListOf<Row>()
            var cells = mutableListOf<List<Paragraph>>()
            var cell = mutableListOf<Paragraph>()
            paragraphs.forEach { paragraph ->
                val props = paragraph.props
                val outer = props.level <= 1
                when {
                    outer && props.rowEnd -> {
                        if (cell.isNotEmpty()) { cells += cell; cell = mutableListOf() }
                        rows += Row(cells, props.row); cells = mutableListOf()
                    }
                    !outer && props.innerRowEnd -> Unit // The end mark of a nested row draws nothing.
                    outer && paragraph.cellEnd -> { cell += paragraph; cells += cell; cell = mutableListOf() }
                    else -> cell += paragraph
                }
            }
            if (cell.isNotEmpty()) cells += cell
            if (cells.isNotEmpty()) rows += Row(cells, null)
            // The grid: every boundary of every row; a cell spans the grid columns between its own boundaries.
            val grid = rows.flatMap { row -> row.layout?.bounds?.toList().orEmpty() }.distinct().sorted()
            html.startTable()
            grid.zipWithNext { left, right -> html.column(right - left) }
            rows.forEach { row ->
                html.startRow()
                val layout = row.layout
                row.cells.forEachIndexed { index, content ->
                    if (layout?.covered?.getOrNull(index) == true) return@forEachIndexed
                    var span = 1
                    if (layout != null && index + 1 < layout.bounds.size && grid.isNotEmpty()) {
                        var last = index + 1
                        while (last + 1 < layout.bounds.size && layout.covered.getOrNull(last) == true) last++
                        span = grid.count { it >= layout.bounds[index] && it < layout.bounds[last] }.coerceAtLeast(1)
                    }
                    html.startCell(span, layout?.merges?.getOrNull(index) ?: Merge.NONE)
                    content.forEach(::paragraph)
                    html.endCell()
                }
            }
            html.endTable()
        }
    }
}
