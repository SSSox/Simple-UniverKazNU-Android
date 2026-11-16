package com.univerflow.kaznu.preview

import java.util.Locale

/** How a run of text looks, whatever the Word-like format it came from. */
internal data class RunStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val vertical: Vertical = Vertical.NONE,
) {
    enum class Vertical { NONE, SUPER, SUB }
}

internal enum class Align(val css: String) { START("start"), CENTER("center"), END("end"), JUSTIFY("justify") }

/** What a paragraph is, known at its end: a heading level (1–6), a list item, its alignment. */
internal data class ParagraphStyle(val heading: Int = 0, val listItem: Boolean = false, val align: Align? = null)

/** A table cell's place in a vertical merge: the first cell of the merged column, or one covered by it. */
internal enum class Merge { NONE, RESTART, CONTINUE }

/**
 * The reading view's HTML for every Word-like document (.docx, .doc, .rtf). Text is always escaped, links are kept
 * only for web and mail addresses, and the output is bounded.
 */
internal class WordHtml {
    private class Cell(var span: Int, var merge: Merge, val html: StringBuilder = StringBuilder())
    private class Table(val rows: MutableList<MutableList<Cell>> = mutableListOf(), val widths: MutableList<Int> = mutableListOf())

    /** Markup that only the .docx equations use, opened and closed in pairs. */
    enum class Markup(val open: String, val close: String) {
        EQUATION("<span class=\"word-equation\">", "</span>"),
        FRACTION("<span class=\"word-fraction\">", "</span>"),
        GROUP("<span>", "</span>"),
        SUPERSCRIPT("<sup>", "</sup>"),
        SUBSCRIPT("<sub>", "</sub>"),
    }

    private val body = StringBuilder()
    private val outputs = ArrayDeque<StringBuilder>().apply { addLast(body) }
    private val tables = ArrayDeque<Table>()
    private val links = ArrayDeque<String?>()
    private var paragraphs = 0

    /** Whether a paragraph is open (Word draws tabs only inside paragraphs). */
    val inParagraph get() = paragraphs > 0

    fun result(): String = body.toString()

    private fun out() = outputs.last()

    private fun guard() {
        if (out().length > OUTPUT_LIMIT || body.length > OUTPUT_LIMIT) throw PreviewLimitException()
    }

    fun startParagraph() { outputs.addLast(StringBuilder()); paragraphs++ }

    fun endParagraph(style: ParagraphStyle = ParagraphStyle()) {
        if (paragraphs == 0) return
        paragraphs--
        val content = outputs.removeLast().toString()
        val css = style.align?.let { " style=\"text-align:${it.css}\"" } ?: ""
        val heading = style.heading.coerceIn(0, 6)
        when {
            heading > 0 && content.isNotBlank() -> out().append("<h$heading$css>").append(content).append("</h$heading>")
            style.listItem && content.isNotBlank() -> out().append("<p class=\"li\"$css>• ").append(content).append("</p>")
            content.isBlank() -> out().append("<p class=\"empty\"></p>")
            else -> out().append("<p$css>").append(content).append("</p>")
        }
        guard()
    }

    fun text(value: String, style: RunStyle = RunStyle()) {
        if (value.isEmpty()) return
        var html = PreviewHtml.escape(value)
        if (style.bold) html = "<b>$html</b>"
        if (style.italic) html = "<i>$html</i>"
        if (style.underline) html = "<u>$html</u>"
        if (style.strike) html = "<s>$html</s>"
        when (style.vertical) {
            RunStyle.Vertical.SUPER -> html = "<sup>$html</sup>"
            RunStyle.Vertical.SUB -> html = "<sub>$html</sub>"
            RunStyle.Vertical.NONE -> Unit
        }
        out().append(html)
        guard()
    }

    fun tab() { if (inParagraph) out().append("<span class=\"tab\"></span>") }

    fun lineBreak() { out().append("<br>") }

    fun open(markup: Markup) { out().append(markup.open) }

    fun close(markup: Markup) { out().append(markup.close) }

    /** Plain text inside equations, without run formatting. */
    fun plain(value: String) { out().append(PreviewHtml.escape(value)); guard() }

    /** A link to [target]; anything but a web or mail address keeps its text without a link. */
    fun startLink(target: String?) {
        links.addLast(safeLink(target))
        outputs.addLast(StringBuilder())
    }

    fun endLink() {
        if (links.isEmpty()) return
        val content = outputs.removeLast()
        val href = links.removeLast()
        if (href != null) out().append("<a href=\"").append(PreviewHtml.escape(href)).append("\">").append(content).append("</a>")
        else out().append(content)
        guard()
    }

    /** An embedded picture at [src], an address given by [PreviewMedia]; it loads when it scrolls into view. */
    fun picture(src: String) {
        out().append(PreviewHtml.picture(src))
        guard()
    }

    fun startTable() { tables.addLast(Table()) }

    /** A grid column of the open table, [twips] wide (1/1440 inch); proportions are kept within readable bounds. */
    fun column(twips: Int?) {
        val table = tables.lastOrNull() ?: return
        if (table.widths.size >= COLUMN_LIMIT) throw PreviewLimitException()
        table.widths.add(((twips ?: 1800) / 15).coerceIn(96, 400))
    }

    fun startRow() { tables.lastOrNull()?.rows?.add(mutableListOf()) }

    fun startCell(span: Int = 1, merge: Merge = Merge.NONE) {
        val cell = Cell(span.coerceIn(1, COLUMN_LIMIT), merge)
        tables.lastOrNull()?.rows?.lastOrNull()?.add(cell)
        outputs.addLast(cell.html)
    }

    fun cellSpan(span: Int) { currentCell()?.span = span.coerceIn(1, COLUMN_LIMIT) }

    fun cellMerge(merge: Merge) { currentCell()?.merge = merge }

    fun endCell() { if (outputs.size > 1) outputs.removeLast() }

    fun endTable() {
        val table = tables.removeLastOrNull() ?: return
        out().append(render(table))
        guard()
    }

    private fun currentCell() = tables.lastOrNull()?.rows?.lastOrNull()?.lastOrNull()

    /** Rebuilds row spans from vertical-merge chains on the logical column grid. */
    private fun render(table: Table): String {
        val starts = table.rows.map { row ->
            var column = 0
            row.map { cell -> column.also { column += cell.span } }
        }
        val columns = table.rows.maxOfOrNull { row -> row.sumOf { it.span } } ?: 0
        if (columns > COLUMN_LIMIT) throw PreviewLimitException()
        val widths = List(columns) { table.widths.getOrNull(it) ?: 120 }
        val width = widths.sum()
        val html = StringBuilder("<div class=\"table\" role=\"region\" tabindex=\"0\"><table style=\"min-width:${width}px;width:${width}px\"><colgroup>")
        widths.forEach { html.append("<col style=\"width:${it}px\">") }
        html.append("</colgroup><tbody>")
        table.rows.forEachIndexed { r, row ->
            html.append("<tr>")
            row.forEachIndexed { c, cell ->
                val column = starts[r][c]
                val covered = cell.merge == Merge.CONTINUE && r > 0 && table.rows[r - 1].indices.any {
                    starts[r - 1][it] == column && table.rows[r - 1][it].merge != Merge.NONE }
                if (covered) return@forEachIndexed
                var span = 1
                if (cell.merge != Merge.NONE) {
                    var next = r + 1
                    while (next < table.rows.size && table.rows[next].indices.any {
                            starts[next][it] == column && table.rows[next][it].merge == Merge.CONTINUE }) { span++; next++ }
                }
                html.append("<td")
                if (cell.span > 1) html.append(" colspan=\"").append(cell.span).append('"')
                if (span > 1) html.append(" rowspan=\"").append(span).append('"')
                html.append('>').append(cell.html).append("</td>")
            }
            html.append("</tr>")
        }
        return html.append("</tbody></table></div>").toString()
    }

    companion object {
        /** Pictures are not part of the page ([PreviewMedia]), so this bounds text and tables only. */
        const val OUTPUT_LIMIT = 24 * 1024 * 1024
        private const val COLUMN_LIMIT = 512
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        /** Web and mail addresses only, without control characters. */
        fun safeLink(target: String?): String? = target?.trim()?.takeIf {
            Regex("^(https?|mailto):", RegexOption.IGNORE_CASE).containsMatchIn(it) && it.none { c -> c.code < 0x20 }
        }

        fun imageType(name: String) = when (name.substringAfterLast('.').lowercase(Locale.ROOT)) {
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "bmp" -> "image/bmp"; "webp" -> "image/webp"
            else -> null
        }

        /** Standard base64 back to bytes, skipping whitespace; null for anything else. */
        fun unbase64(text: CharSequence): ByteArray? {
            val out = java.io.ByteArrayOutputStream(text.length * 3 / 4)
            var buffer = 0
            var bits = 0
            for (char in text) {
                if (char == '=') break
                if (char.isWhitespace()) continue
                val value = ALPHABET.indexOf(char).takeIf { it >= 0 } ?: return null
                buffer = (buffer shl 6) or value
                bits += 6
                if (bits >= 8) { bits -= 8; out.write((buffer shr bits) and 0xFF) }
            }
            return out.toByteArray()
        }

        /** java.util.Base64 needs API 26; this app supports API 24. */
        fun base64(bytes: ByteArray): String {
            val out = StringBuilder((bytes.size + 2) / 3 * 4)
            var index = 0
            while (index < bytes.size) {
                val b0 = bytes[index].toInt() and 0xFF
                val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else 0
                val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else 0
                out.append(ALPHABET[b0 shr 2]).append(ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
                out.append(if (index + 1 < bytes.size) ALPHABET[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
                out.append(if (index + 2 < bytes.size) ALPHABET[b2 and 63] else '=')
                index += 3
            }
            return out.toString()
        }
    }
}
