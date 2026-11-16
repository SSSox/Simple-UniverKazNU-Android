package com.univerflow.kaznu.preview

/**
 * The tables of a document page, each with a link to open it on its own screen: every workbook sheet and every
 * top-level table of at least two rows and four cells. The body is the viewer's escaped markup, so its tags balance.
 */
class DocumentTables private constructor(val body: String, private val ranges: List<IntRange>) {
    val count get() = ranges.size

    fun markup(index: Int): String? = ranges.getOrNull(index)?.let { body.substring(it.first, it.last + 1) }

    companion object {
        private val START = Regex("<div class=\"(table|grid)\" role=\"region\"")
        private val DIV = Regex("<div[\\s>]|</div>")

        /** Finds the tables of [body] and links each to its own screen with the text [label]. */
        fun find(body: String, label: String): DocumentTables {
            var out: StringBuilder? = null
            var copied = 0
            val ranges = ArrayList<IntRange>()
            var at = 0
            while (true) {
                val match = START.find(body, at) ?: break
                val start = match.range.first
                val end = end(body, start) ?: break
                if (match.groupValues[1] == "grid" || large(body, start, end)) {
                    // The body is copied only once a link is added.
                    val page = out ?: StringBuilder(body.length + 4096).also { out = it }
                    page.append(body, copied, start).append("<p class=\"open-table\"><a href=\"").append(PreviewHtml.tableUrl(ranges.size))
                        .append("\">").append(PreviewHtml.escape(label)).append("</a></p>")
                    val from = page.length
                    page.append(body, start, end)
                    ranges += from until page.length
                    copied = end
                }
                at = end
            }
            val page = out ?: return DocumentTables(body, emptyList())
            return DocumentTables(page.append(body, copied, body.length).toString(), ranges)
        }

        /** The end of the element that starts at [start] (after its closing tag), or null if it never closes. */
        private fun end(body: String, start: Int): Int? {
            var depth = 0
            var at = start
            while (true) {
                val match = DIV.find(body, at) ?: return null
                if (match.value[1] == '/') { if (--depth == 0) return match.range.last + 1 } else depth++
                at = match.range.last + 1
            }
        }

        /** At least two rows and four cells: a layout table of one row (a heading, a signature) gets no link. */
        private fun large(body: String, start: Int, end: Int): Boolean {
            // Searched on its own: a tag the table lacks is not looked for through the rest of the document.
            val table = body.substring(start, end)
            fun count(tag: String, enough: Int): Int {
                var found = 0
                var at = 0
                while (found < enough) {
                    at = table.indexOf(tag, at).takeIf { it >= 0 } ?: break
                    at += tag.length
                    if (table.getOrNull(at) == '>' || table.getOrNull(at) == ' ') found++ // Not <thead>.
                }
                return found
            }
            return count("<tr", 2) >= 2 && count("<td", 4) + count("<th", 4) >= 4
        }
    }
}
