package com.univerflow.kaznu.preview

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.nio.charset.Charset

/** Text of OpenDocument files (.odt, .ods, .odp): paragraphs and headings of content.xml. */
internal object OpenDocumentText {
    private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"

    fun text(content: ByteArray, checkActive: () -> Unit = {}): String {
        val out = StringBuilder()
        var inBody = false
        var nodes = 0
        SafeXml.parse(content, object : DefaultHandler() {
            override fun startElement(uri: String, local: String, qName: String, attributes: Attributes) {
                if (++nodes % 1024 == 0) checkActive()
                if (uri == OFFICE && local == "body") inBody = true
                if (!inBody || uri != TEXT) return
                when (local) {
                    "tab" -> out.append('\t')
                    "line-break" -> out.append('\n')
                    "s" -> repeat((attributes.getValue(TEXT, "c")?.toIntOrNull() ?: 1).coerceIn(1, 64)) { out.append(' ') }
                }
            }

            override fun endElement(uri: String, local: String, qName: String) {
                if (uri == OFFICE && local == "body") inBody = false
                if (inBody && uri == TEXT && (local == "p" || local == "h") && out.isNotEmpty() && out.last() != '\n') out.append('\n')
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inBody && out.length < TextExtract.LIMIT) out.appendRange(ch, start, start + length)
            }
        })
        return out.toString().take(TextExtract.LIMIT).trim()
    }
}

/** Text of an HTML page (also Word's "web page" saved as .doc): blocks become lines, cells tabs, markup is dropped. */
internal object HtmlText {
    fun isHtml(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1).trimStart('\uFEFF', '\u00EF', '\u00BB', '\u00BF', ' ', '\r', '\n', '\t').lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html") || (head.startsWith("<") && "<html" in head)
    }

    /** The page's characters in its declared charset, or as [TextPreview] detects them. */
    fun decode(bytes: ByteArray): String {
        val declared = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
            .find(String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1)
        return declared?.let { name -> runCatching { String(bytes, Charset.forName(name)) }.getOrNull() } ?: TextPreview.decode(bytes, bytes.size)
    }

    private val BLOCK = Regex("<(script|style|head|xml)\\b", RegexOption.IGNORE_CASE)

    /**
     * [change] applied up to the last [closing] of the text. What these patterns match ends with [closing], so the
     * result is the same, and a page of markup that never closes is not searched to its end from every opening.
     */
    private fun String.through(closing: String, change: (String) -> String): String {
        val end = lastIndexOf(closing).takeIf { it >= 0 } ?: return this
        return change(substring(0, end + closing.length)) + substring(end + closing.length)
    }

    /** Scripts, styles and the head, each from its opening to its first closing tag; one never closed stays as it is. */
    private fun withoutBlocks(text: String): String {
        val out = StringBuilder(text.length)
        val unclosed = HashSet<String>()
        var at = 0
        while (true) {
            val open = BLOCK.find(text, at) ?: break
            val name = open.groupValues[1].lowercase()
            val close = if (name in unclosed) null else Regex("</$name\\s*>", RegexOption.IGNORE_CASE).find(text, open.range.last + 1)
            if (close == null) {
                unclosed += name // Nor does any later one close.
                out.append(text, at, open.range.last + 1)
                at = open.range.last + 1
            } else {
                out.append(text, at, open.range.first).append(' ')
                at = close.range.last + 1
            }
        }
        return out.append(text, at, text.length).toString()
    }

    fun text(bytes: ByteArray): String {
        return decode(bytes)
            .through("-->") { it.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ") }
            .let(::withoutBlocks)
            // As in a browser, line breaks in the source are spaces; only blocks start lines.
            .replace(Regex("\\s+"), " ")
            .through(">") { it.replace(Regex("</?(p|div|br|h[1-6]|li|tr|table|ul|ol|blockquote|pre|section|article|header|footer)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n") }
            .replace(Regex("</t[dh]\\s*>", RegexOption.IGNORE_CASE), "\t")
            .through(">") { it.replace(Regex("<[^>]*>"), "") }
            .let(::entities)
            .lines().joinToString("\n") { it.replace(Regex("[ \\u00A0]{2,}"), " ").trim() }
            .replace(Regex("\n{3,}"), "\n\n").take(TextExtract.LIMIT).trim()
    }

    fun entities(text: String) = Regex("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});").replace(text) { match ->
        val name = match.groupValues[1]
        when {
            name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) }
            name.startsWith("#") -> name.substring(1).toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) }
            else -> ENTITIES[name]
        } ?: match.value
    }

    private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "laquo" to "«", "raquo" to "»", "mdash" to "—", "ndash" to "–", "hellip" to "…", "copy" to "©", "reg" to "®",
        "bull" to "•", "middot" to "·", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "bdquo" to "„", "deg" to "°", "times" to "×")
}
