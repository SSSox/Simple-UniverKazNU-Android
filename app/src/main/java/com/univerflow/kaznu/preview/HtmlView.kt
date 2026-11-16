package com.univerflow.kaznu.preview

import java.io.InputStream
import java.util.Locale

/**
 * A web page rebuilt from an allowlist of text, list, table, link and picture elements; scripts, styles, frames, forms
 * and handlers are dropped. Links keep only web and mail addresses, pictures only inline `data:` images.
 */
object HtmlView {
    /** Inline pictures move out of the page into [PreviewMedia], so a page full of them still opens. */
    const val FILE_LIMIT = 64 * 1024 * 1024

    fun html(input: InputStream, checkActive: () -> Unit = {}, media: PreviewMedia = PreviewMedia.inline()): String =
        html(Bounded.read(input, FILE_LIMIT, checkActive), checkActive, media)

    fun html(bytes: ByteArray, checkActive: () -> Unit = {}, media: PreviewMedia = PreviewMedia.inline()): String =
        Sanitizer(HtmlText.decode(bytes), checkActive, media).run()

    private class Sanitizer(private val source: String, private val checkActive: () -> Unit, private val media: PreviewMedia) {
        private val out = StringBuilder()
        /** Open elements by their output name. */
        private val open = ArrayList<String>()
        private var steps = 0

        fun run(): String {
            var at = 0
            while (at < source.length) {
                if (++steps % 2048 == 0) checkActive()
                val tag = source.indexOf('<', at)
                if (tag < 0) { text(source.substring(at)); break }
                if (tag > at) text(source.substring(at, tag))
                at = markup(tag)
                if (out.length > WordHtml.OUTPUT_LIMIT) throw PreviewLimitException()
            }
            while (open.isNotEmpty()) close()
            return out.toString()
        }

        private fun text(value: String) {
            if (value.isEmpty()) return
            out.append(PreviewHtml.escape(HtmlText.entities(value)))
        }

        /** Reads the markup at [at] and returns where the text continues. */
        private fun markup(at: Int): Int {
            if (source.startsWith("<!--", at)) return source.indexOf("-->", at + 4).let { if (it < 0) source.length else it + 3 }
            val next = source.getOrNull(at + 1) ?: return source.length.also { text("<") }
            if (next == '!' || next == '?') return source.indexOf('>', at).let { if (it < 0) source.length else it + 1 }
            val closing = next == '/'
            val nameStart = if (closing) at + 2 else at + 1
            if (source.getOrNull(nameStart)?.isLetter() != true) { text("<"); return at + 1 }
            var nameEnd = nameStart
            while (nameEnd < source.length && (source[nameEnd].isLetterOrDigit() || source[nameEnd] == '-' || source[nameEnd] == ':')) nameEnd++
            val name = source.substring(nameStart, nameEnd).lowercase(Locale.ROOT)
            val attributes = HashMap<String, String>()
            val end = attributes(nameEnd, attributes)
            if (closing) { endTag(name); return end }
            if (name in RAW_TEXT) {
                // Script-like content is not markup: it ends only at its own end tag.
                val close = Regex("</$name[\\s/>]", RegexOption.IGNORE_CASE).find(source, end)
                return close?.let { source.indexOf('>', it.range.first).let { gt -> if (gt < 0) source.length else gt + 1 } } ?: source.length
            }
            // A head ends at its end tag or where the body starts, even when the page forgets to close it.
            if (name == "head") return Regex("</head\\s*>|<body[\\s>]", RegexOption.IGNORE_CASE).find(source, end)
                ?.let { if (it.value.startsWith("</")) it.range.last + 1 else it.range.first } ?: source.length
            val selfClosing = source.getOrNull(end - 2) == '/'
            // <embed> never has an end tag and a self-closing <svg/> holds nothing: only the tag itself is dropped.
            if (name in DROPPED) return if (name == "embed" || selfClosing) end else skip(name, end)
            startTag(name, attributes, selfClosing)
            return end
        }

        /** Attributes up to the tag's '>' (quotes may hold '>'); returns the index after it. */
        private fun attributes(from: Int, into: MutableMap<String, String>): Int {
            var at = from
            while (at < source.length) {
                val char = source[at]
                when {
                    char == '>' -> return at + 1
                    char.isWhitespace() || char == '/' -> at++
                    else -> {
                        val start = at
                        while (at < source.length && !source[at].isWhitespace() && source[at] != '=' && source[at] != '>' && source[at] != '/') at++
                        val name = source.substring(start, at).lowercase(Locale.ROOT)
                        while (at < source.length && source[at].isWhitespace()) at++
                        var value = ""
                        if (at < source.length && source[at] == '=') {
                            at++
                            while (at < source.length && source[at].isWhitespace()) at++
                            val quote = source.getOrNull(at)
                            if (quote == '"' || quote == '\'') {
                                val close = source.indexOf(quote, at + 1).let { if (it < 0) source.length else it }
                                value = source.substring(at + 1, close); at = close + 1
                            } else {
                                val valueStart = at
                                while (at < source.length && !source[at].isWhitespace() && source[at] != '>') at++
                                value = source.substring(valueStart, at)
                            }
                        }
                        if (into.size < 64 && name.isNotEmpty()) into.putIfAbsent(name, HtmlText.entities(value))
                    }
                }
            }
            return source.length
        }

        /** Skips a dropped element with its content, counting nested elements of the same name. */
        private fun skip(name: String, from: Int): Int {
            var depth = 1
            val pattern = Regex("<(/?)$name(?=[\\s/>])", RegexOption.IGNORE_CASE)
            var at = from
            while (depth > 0) {
                if (++steps % 2048 == 0) checkActive()
                val match = pattern.find(source, at) ?: return source.length
                if (match.groupValues[1].isEmpty()) {
                    // A self-closing start (<svg/>) opens nothing.
                    val gt = source.indexOf('>', match.range.last).let { if (it < 0) source.length else it }
                    if (source.getOrNull(gt - 1) != '/') depth++
                } else depth--
                at = source.indexOf('>', match.range.last).let { if (it < 0) return source.length else it + 1 }
            }
            return at
        }

        private fun startTag(name: String, attributes: Map<String, String>, selfClosing: Boolean) {
            val target = ELEMENTS[name] ?: return
            when (target) {
                "br" -> { out.append("<br>"); return }
                "hr" -> { closeParagraph(); out.append("<hr>"); return }
                "img" -> { picture(attributes); return }
            }
            if (open.size >= DEPTH_LIMIT) return
            if (target in BLOCKS) closeParagraph()
            when (target) {
                "li" -> closeWithin(setOf("li"), setOf("ul", "ol"))
                "dt", "dd" -> closeWithin(setOf("dt", "dd"), setOf("dl"))
                "tr" -> { if ("table" !in open) return; closeWithin(setOf("tr", "td", "th"), setOf("table", "thead", "tbody", "tfoot")) }
                "td", "th" -> { if ("tr" !in open) return; closeWithin(setOf("td", "th"), setOf("tr")) }
                "thead", "tbody", "tfoot" -> { if ("table" !in open) return; closeWithin(setOf("thead", "tbody", "tfoot", "tr", "td", "th"), setOf("table")) }
                "caption" -> if (open.lastOrNull() != "table") return
            }
            if (target == "table") out.append("<div class=\"table\" role=\"region\" tabindex=\"0\">")
            out.append('<').append(target)
            when (target) {
                "a" -> WordHtml.safeLink(attributes["href"])?.let { out.append(" href=\"").append(PreviewHtml.escape(it)).append('"') }
                "td", "th" -> {
                    attributes["colspan"]?.trim()?.toIntOrNull()?.takeIf { it > 1 }?.let { out.append(" colspan=\"").append(it.coerceAtMost(512)).append('"') }
                    attributes["rowspan"]?.trim()?.toIntOrNull()?.takeIf { it > 1 }?.let { out.append(" rowspan=\"").append(it.coerceAtMost(4096)).append('"') }
                }
                "ol" -> attributes["start"]?.trim()?.toIntOrNull()?.let { out.append(" start=\"").append(it).append('"') }
            }
            ALIGN[attributes["align"]?.trim()?.lowercase(Locale.ROOT)]?.takeIf { target in ALIGNED }?.let { out.append(" style=\"text-align:").append(it).append('"') }
            if (name == "center") out.append(" style=\"text-align:center\"")
            out.append('>')
            if (selfClosing && target !in setOf("td", "th", "li")) out.append("</").append(target).append('>') else open += target
        }

        private fun picture(attributes: Map<String, String>) {
            val source = attributes["src"]?.trim().orEmpty()
            val match = DATA_IMAGE.matchEntire(source)
            val src = match?.let {
                val bytes = WordHtml.unbase64(it.groupValues[2]) ?: return@let null
                media.add("image/" + it.groupValues[1].lowercase(Locale.ROOT).replace("jpg", "jpeg"), bytes)
            }
            if (src != null) out.append(PreviewHtml.picture(src, attributes["alt"].orEmpty()))
            // A picture from a file or the web cannot be shown; its description can.
            else attributes["alt"]?.takeIf { it.isNotBlank() }?.let { out.append("<span class=\"alt\">").append(PreviewHtml.escape(it)).append("</span>") }
        }

        private fun endTag(name: String) {
            val target = ELEMENTS[name] ?: return
            if (target in VOID) return
            val index = open.lastIndexOf(target)
            if (index < 0) return
            // Table parts never close beyond their own table.
            if (target != "table" && open.subList(index, open.size).contains("table")) return
            while (open.size > index) close()
        }

        private fun close() {
            val name = open.removeAt(open.size - 1)
            out.append("</").append(name).append('>')
            if (name == "table") out.append("</div>")
        }

        /** A block closes an open paragraph, as browsers do, unless a table cell or list item lies between. */
        private fun closeParagraph() = closeWithin(setOf("p"), setOf("td", "th", "li", "dd", "blockquote", "div", "table"))

        /** Closes the nearest open element of [names], unless one of [boundaries] is open above it. */
        private fun closeWithin(names: Set<String>, boundaries: Set<String>) {
            for (index in open.indices.reversed()) {
                val name = open[index]
                if (name in names) { while (open.size > index) close(); return }
                if (name in boundaries) return
            }
        }

        companion object {
            private const val DEPTH_LIMIT = 256
            private val RAW_TEXT = setOf("script", "style", "title", "textarea", "xmp", "iframe", "noembed", "noframes", "plaintext", "template")
            private val DROPPED = setOf("svg", "math", "object", "embed", "applet", "select", "canvas", "audio", "video", "button", "frameset", "map", "xml")
            private val ELEMENTS = mapOf(
                "p" to "p", "div" to "div", "h1" to "h1", "h2" to "h2", "h3" to "h3", "h4" to "h4", "h5" to "h5", "h6" to "h6",
                "ul" to "ul", "ol" to "ol", "li" to "li", "dl" to "dl", "dt" to "dt", "dd" to "dd", "table" to "table", "caption" to "caption",
                "thead" to "thead", "tbody" to "tbody", "tfoot" to "tfoot", "tr" to "tr", "td" to "td", "th" to "th",
                "b" to "b", "strong" to "strong", "i" to "i", "em" to "em", "u" to "u", "s" to "s", "strike" to "s", "del" to "del", "ins" to "ins",
                "sub" to "sub", "sup" to "sup", "small" to "small", "big" to "span", "mark" to "mark", "code" to "code", "tt" to "code", "kbd" to "kbd",
                "samp" to "samp", "pre" to "pre", "blockquote" to "blockquote", "q" to "q", "cite" to "cite", "abbr" to "abbr", "br" to "br", "hr" to "hr",
                "a" to "a", "img" to "img", "span" to "span", "font" to "span", "label" to "span", "center" to "div", "section" to "div", "article" to "div",
                "header" to "div", "footer" to "div", "main" to "div", "nav" to "div", "aside" to "div", "address" to "div", "fieldset" to "div",
                "figure" to "div", "figcaption" to "p", "legend" to "p", "details" to "details", "summary" to "summary",
            )
            private val VOID = setOf("br", "hr", "img")
            private val BLOCKS = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "dl", "table", "pre", "blockquote", "details")
            private val ALIGNED = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "td", "th", "caption")
            private val ALIGN = mapOf("left" to "start", "right" to "end", "center" to "center", "middle" to "center", "justify" to "justify")
            private val DATA_IMAGE = Regex("^data:image/(png|jpe?g|gif|webp|bmp);base64,([A-Za-z0-9+/=\\s]+)$", RegexOption.IGNORE_CASE)
        }
    }
}
