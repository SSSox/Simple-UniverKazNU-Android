package com.univerflow.kaznu.preview

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Reading view of a Word .docx, drawn by [WordHtml] like .doc and .rtf. Nothing in the file is executed or fetched;
 * links become plain external anchors.
 */
object DocxHtml {
    private val HEADING_STYLE = Regex("^heading ([1-6])$")
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val M = "http://schemas.openxmlformats.org/officeDocument/2006/math"
    private const val MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"
    private const val DOCUMENT_LIMIT = 64 * 1024 * 1024
    private const val PART_LIMIT = 4 * 1024 * 1024
    private const val IMAGE_LIMIT = 32 * 1024 * 1024
    private const val NODE_LIMIT = 4_000_000
    private const val DEPTH_LIMIT = 128
    private const val ENTRY_LIMIT = 4_000

    /** [media] keeps the pictures, by address, as the package is streamed. */
    private class Package(val document: ByteArray, val relations: ByteArray?, val styles: ByteArray?, val media: Map<String, String>)

    fun convert(input: InputStream, checkActive: () -> Unit = {}, media: PreviewMedia = PreviewMedia.inline()): String {
        val parts = read(input, checkActive, media)
        val relations = parts.relations?.let { relations(it) } ?: emptyMap()
        val headings = parts.styles?.let { headingStyles(it) } ?: emptyMap()
        val handler = BodyHandler(relations, headings, parts.media, checkActive)
        SafeXml.parse(parts.document, handler)
        return handler.result()
    }

    private fun read(input: InputStream, checkActive: () -> Unit, store: PreviewMedia): Package {
        var document: ByteArray? = null
        var relations: ByteArray? = null
        var styles: ByteArray? = null
        val media = mutableMapOf<String, String>()
        var entries = 0
        val passed = PassedOver(checkActive)
        ZipInputStream(input).use { zip ->
            while (true) {
                checkActive()
                val entry = zip.nextEntry ?: break
                if (++entries > ENTRY_LIMIT) throw PreviewLimitException()
                val name = entry.name.replace('\\', '/')
                when {
                    name == "word/document.xml" -> document = Bounded.read(zip, DOCUMENT_LIMIT, checkActive)
                    name == "word/_rels/document.xml.rels" -> relations = Bounded.read(zip, PART_LIMIT, checkActive)
                    name == "word/styles.xml" -> styles = Bounded.read(zip, PART_LIMIT, checkActive)
                    name.startsWith("word/media/") && WordHtml.imageType(name) != null -> {
                        val bytes = try { Bounded.read(zip, IMAGE_LIMIT, checkActive) } catch (_: PreviewLimitException) { null }
                        bytes?.let { store.add(WordHtml.imageType(name)!!, it) }?.let { media[name.removePrefix("word/")] = it }
                    }
                }
                passed.rest(zip)
            }
        }
        return Package(document ?: throw UnsupportedDocumentException("Not a Word document"), relations, styles, media)
    }

    private fun relations(bytes: ByteArray): Map<String, Pair<String, Boolean>> {
        val result = mutableMapOf<String, Pair<String, Boolean>>()
        SafeXml.parse(bytes, object : DefaultHandler() {
            override fun startElement(uri: String, local: String, qName: String, attributes: Attributes) {
                if (local != "Relationship") return
                val id = attributes.getValue("", "Id") ?: return
                val target = attributes.getValue("", "Target") ?: return
                result[id] = target to attributes.getValue("", "TargetMode").equals("External", true)
            }
        })
        return result
    }

    /** Style ids are localized ("1", "Heading1", "Заголовок1"); the style name tells the level. */
    private fun headingStyles(bytes: ByteArray): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        var current: String? = null
        SafeXml.parse(bytes, object : DefaultHandler() {
            override fun startElement(uri: String, local: String, qName: String, attributes: Attributes) {
                if (uri != W) return
                if (local == "style") current = attributes.getValue(W, "styleId")
                if (local == "name") {
                    val name = attributes.getValue(W, "val")?.lowercase(Locale.ROOT).orEmpty()
                    val level = HEADING_STYLE.find(name)?.groupValues?.get(1)?.toInt() ?: if (name == "title") 1 else null
                    current?.let { id -> if (level != null) result[id] = level }
                }
            }
        })
        return result
    }

    /** Maps WordprocessingML elements onto [WordHtml]; run and paragraph properties precede their content. */
    private class BodyHandler(
        val relations: Map<String, Pair<String, Boolean>>,
        val headings: Map<String, Int>,
        val media: Map<String, String>,
        val checkActive: () -> Unit,
    ) : DefaultHandler() {
        /** An open paragraph: its style so far and the run style around it, restored when a text box's paragraph ends. */
        private class Open(var style: ParagraphStyle, val run: RunStyle)

        private val html = WordHtml()
        private var nodes = 0
        private var depth = 0
        private var inBody = false
        /** Open paragraphs, outermost first: a text box's paragraphs lie inside the paragraph that anchors it. */
        private val paragraphs = ArrayDeque<Open>()
        /** Depth inside paragraph properties; table and row alignment never changes a paragraph. */
        private var properties = 0
        private var run = RunStyle()
        private var inText = false
        private var tabStops = false
        private val text = StringBuilder()
        /** Elements shown so far (paragraphs, text, tables, pictures), to tell whether an alternative's choice showed anything. */
        private var shown = 0
        /** [shown] where each open mc:AlternateContent started. */
        private val alternates = ArrayDeque<Int>()
        /** Depth inside a skipped mc:Fallback, the second copy of what its mc:Choice already showed. */
        private var fallback = 0

        fun result(): String = html.result()

        private fun on(attributes: Attributes) = attributes.getValue(W, "val")?.lowercase(Locale.ROOT) !in setOf("0", "false", "none")

        private fun paragraph(change: (ParagraphStyle) -> ParagraphStyle) {
            if (properties > 0) paragraphs.lastOrNull()?.let { it.style = change(it.style) }
        }

        override fun startElement(uri: String, local: String, qName: String, attributes: Attributes) {
            if (++nodes > NODE_LIMIT || ++depth > DEPTH_LIMIT) throw PreviewLimitException()
            if (nodes % 512 == 0) checkActive()
            // Word saves text boxes, shapes and groups twice: for itself (mc:Choice) and for older readers (mc:Fallback).
            // The fallback is shown only when the choice showed nothing, as with a chart type this view cannot draw.
            if (uri == MC && local == "Fallback" && (fallback > 0 || shown > (alternates.lastOrNull() ?: -1))) { fallback++; return }
            if (fallback > 0) return
            if (uri == MC && local == "AlternateContent") { alternates.addLast(shown); return }
            if (uri == W && local == "body") { inBody = true; return }
            if (!inBody) return
            if (uri == M) { math(local, open = true); return }
            when (if (uri == W) local else "") {
                "p" -> { html.startParagraph(); paragraphs.addLast(Open(ParagraphStyle(), run)); shown++ }
                "pPr" -> properties++
                "pStyle" -> paragraph { it.copy(heading = headings[attributes.getValue(W, "val")] ?: 0) }
                "numPr" -> paragraph { it.copy(listItem = true) }
                "jc" -> paragraph { it.copy(align = when (attributes.getValue(W, "val")) {
                    "center" -> Align.CENTER; "right", "end" -> Align.END; "both" -> Align.JUSTIFY; else -> null }) }
                "r" -> run = RunStyle()
                "b" -> run = run.copy(bold = on(attributes))
                "i" -> run = run.copy(italic = on(attributes))
                "u" -> run = run.copy(underline = on(attributes))
                "strike", "dstrike" -> run = run.copy(strike = on(attributes))
                "vertAlign" -> run = run.copy(vertical = when (attributes.getValue(W, "val")) {
                    "superscript" -> RunStyle.Vertical.SUPER; "subscript" -> RunStyle.Vertical.SUB; else -> RunStyle.Vertical.NONE })
                "t" -> { inText = true; text.setLength(0); shown++ }
                "tabs" -> tabStops = true
                "tab" -> if (!tabStops) html.tab()
                "br", "cr" -> html.lineBreak()
                "hyperlink" -> html.startLink(attributes.getValue(R, "id")?.let(relations::get)?.takeIf { it.second }?.first)
                "tbl" -> { html.startTable(); shown++ }
                "gridCol" -> html.column(attributes.getValue(W, "w")?.toIntOrNull())
                "tr" -> html.startRow()
                "tc" -> html.startCell()
                "gridSpan" -> html.cellSpan(attributes.getValue(W, "val")?.toIntOrNull() ?: 1)
                "vMerge" -> html.cellMerge(if (attributes.getValue(W, "val") == "restart") Merge.RESTART else Merge.CONTINUE)
                else -> if (local == "blip" || local == "imagedata") picture(attributes)
            }
        }

        private fun math(local: String, open: Boolean) {
            val markup = when (local) {
                "oMath" -> WordHtml.Markup.EQUATION
                "f" -> WordHtml.Markup.FRACTION
                "num", "den", "e", "sSup", "sSub", "sSubSup" -> WordHtml.Markup.GROUP
                "sup" -> WordHtml.Markup.SUPERSCRIPT
                "sub" -> WordHtml.Markup.SUBSCRIPT
                "t" -> { if (open) { inText = true; text.setLength(0) } else { inText = false; html.plain(text.toString()) }; return }
                else -> return
            }
            if (open) html.open(markup) else html.close(markup)
        }

        private fun picture(attributes: Attributes) {
            val id = attributes.getValue(R, "embed") ?: attributes.getValue(R, "id") ?: return
            val target = relations[id]?.takeIf { !it.second }?.first ?: return
            val name = target.removePrefix("/word/").removePrefix("./")
            html.picture(media[name] ?: return)
            shown++
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (!inText || fallback > 0) return
            // A run longer than the whole reading view can hold fails now instead of after filling memory.
            if (text.length + length > WordHtml.OUTPUT_LIMIT) throw PreviewLimitException()
            text.append(ch, start, length)
        }

        override fun endElement(uri: String, local: String, qName: String) {
            depth--
            if (uri == MC && local == "Fallback" && fallback > 0) { fallback--; return }
            if (fallback > 0) return
            if (uri == MC && local == "AlternateContent") { alternates.removeLastOrNull(); return }
            if (uri == M && inBody) { math(local, open = false); return }
            if (uri != W || !inBody) return
            when (local) {
                "body" -> inBody = false
                "tabs" -> tabStops = false
                "pPr" -> properties--
                "t" -> { inText = false; html.text(text.toString(), run) }
                "hyperlink" -> html.endLink()
                "p" -> {
                    val open = paragraphs.removeLastOrNull()
                    html.endParagraph(open?.style ?: ParagraphStyle())
                    open?.let { run = it.run }
                }
                "tc" -> html.endCell()
                "tbl" -> html.endTable()
            }
        }
    }
}
