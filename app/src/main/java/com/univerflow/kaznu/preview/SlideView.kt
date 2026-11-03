package com.univerflow.kaznu.preview

import java.io.InputStream

/** A paragraph of slide text: its outline [level] (0 is the first) and whether it is a bulleted point. */
internal class SlideParagraph(val text: String, val level: Int = 0, val bullet: Boolean = false)

/** What a slide shows, in reading order: titles first, then text and pictures as PowerPoint orders them. */
internal sealed interface SlideBlock {
    class Title(val text: String) : SlideBlock
    class Text(val paragraphs: List<SlideParagraph>) : SlideBlock
    /** A picture already kept by [PreviewMedia], at [src]. */
    class Picture(val src: String) : SlideBlock
}

internal class Slide(val blocks: List<SlideBlock>, val notes: List<SlideParagraph>)

internal class Deck(val slides: List<Slide>)

/** Where a text placeholder's paragraphs come from, which decides their default bullets. */
internal enum class TextKind { TITLE, SUBTITLE, BODY, OTHER, NOTES, SKIPPED }

/**
 * The slide view of any presentation, whatever its name says: PowerPoint .pptx/.ppsx/.pptm and PowerPoint 97–2003 .ppt
 * are read into one model and drawn by [SlideHtml] as cards, so the same presentation looks the same in both formats.
 */
object SlideView {
    /** A picture larger than this is left out; pictures are files of the viewer, not part of the page ([PreviewMedia]). */
    internal const val IMAGE_LIMIT = 48 * 1024 * 1024

    fun html(bytes: ByteArray, labels: PreviewLabels, checkActive: () -> Unit = {}, media: PreviewMedia = PreviewMedia.inline()): String =
        SlideHtml.render(read(FileAccess.of(bytes), checkActive, media), labels)

    /** The viewer's way in: a .pptx is streamed and a .ppt read in place, so the file's size is not a limit. */
    internal fun html(file: FileAccess, labels: PreviewLabels, checkActive: () -> Unit, media: PreviewMedia): String =
        SlideHtml.render(read(file, checkActive, media), labels)

    internal fun read(file: FileAccess, checkActive: () -> Unit = {}, media: PreviewMedia = PreviewMedia.inline()): Deck = when {
        file.zip -> file.stream().use { PptxReader.read(it, checkActive, media) }
        file.compound -> PptReader.read(CompoundFile(file.source()), checkActive, media)
        else -> throw UnsupportedDocumentException("Not a presentation")
    }

    /** Titles before everything else; other blocks keep the file's drawing order, which is PowerPoint's reading order. */
    internal fun ordered(blocks: List<SlideBlock>) = blocks.filterIsInstance<SlideBlock.Title>() + blocks.filter { it !is SlideBlock.Title }

    /** Body placeholders draw bullets unless a paragraph turns them off; other text has none unless it turns them on. */
    internal fun bullet(kind: TextKind, explicit: Boolean?) = explicit ?: (kind == TextKind.BODY)
}

/** Slide cards: a "Slide N of M" label, titles, bulleted outline levels, pictures and folded speaker notes. */
internal object SlideHtml {
    fun render(deck: Deck, labels: PreviewLabels): String {
        val out = StringBuilder()
        val total = deck.slides.size
        deck.slides.forEachIndexed { index, slide ->
            val label = PreviewHtml.escape(labels.slide(index + 1, total))
            out.append("<section class=\"slide\" id=\"slide-").append(index + 1).append("\" aria-label=\"").append(label)
                .append("\"><p class=\"number\">").append(label).append("</p>")
            slide.blocks.forEach { block -> block(out, block) }
            if (slide.notes.isNotEmpty()) {
                out.append("<details class=\"notes\"><summary>").append(PreviewHtml.escape(labels.notes)).append("</summary>")
                paragraphs(out, slide.notes)
                out.append("</details>")
            }
            out.append("</section>")
            if (out.length > WordHtml.OUTPUT_LIMIT) throw PreviewLimitException()
        }
        if (deck.slides.isEmpty()) out.append("<p class=\"empty\">").append(PreviewHtml.escape(labels.emptyDeck)).append("</p>")
        return out.toString()
    }

    private fun block(out: StringBuilder, block: SlideBlock) {
        when (block) {
            is SlideBlock.Title -> out.append("<h2>").append(text(block.text)).append("</h2>")
            is SlideBlock.Text -> paragraphs(out, block.paragraphs)
            is SlideBlock.Picture -> out.append("<figure>").append(PreviewHtml.picture(block.src)).append("</figure>")
        }
    }

    /** Bulleted runs become lists; each level indents one step. */
    private fun paragraphs(out: StringBuilder, paragraphs: List<SlideParagraph>) {
        var inList = false
        paragraphs.filter { it.text.isNotBlank() }.forEach { paragraph ->
            if (paragraph.bullet != inList) { out.append(if (paragraph.bullet) "<ul>" else "</ul>"); inList = paragraph.bullet }
            val level = paragraph.level.coerceIn(0, 8)
            val tag = if (paragraph.bullet) "li" else "p"
            out.append('<').append(tag)
            if (level > 0) out.append(" class=\"l").append(level).append('"')
            out.append('>').append(text(paragraph.text)).append("</").append(tag).append('>')
        }
        if (inList) out.append("</ul>")
    }

    /** Line breaks inside a paragraph stay line breaks. */
    private fun text(value: String) = PreviewHtml.escape(value.trim()).replace("\n", "<br>")
}

/**
 * PowerPoint .pptx ([ECMA-376] PresentationML) in presentation order. Dates, footers, slide numbers and anything
 * fetched from outside the file are left out.
 */
internal object PptxReader {
    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"
    private const val PART_LIMIT = 16 * 1024 * 1024
    private const val PARTS_TOTAL = 256L * 1024 * 1024
    private const val ENTRY_LIMIT = 20_000
    private const val SLIDE_LIMIT = 5_000
    private const val NODE_LIMIT = 400_000

    private class Relation(val target: String, val type: String, val external: Boolean)

    /**
     * One pass over the package as a stream: slide and notes XML stay in memory, pictures go to [store] as they come
     * (by part name), and video, audio and embedded files are skipped without being kept.
     */
    fun read(input: InputStream, checkActive: () -> Unit = {}, store: PreviewMedia = PreviewMedia.inline()): Deck {
        val parts = HashMap<String, ByteArray>()
        val media = HashMap<String, String>()
        var partsTotal = 0L
        var entries = 0
        java.util.zip.ZipInputStream(input).use { zip ->
            while (true) {
                checkActive()
                val entry = zip.nextEntry ?: break
                if (++entries > ENTRY_LIMIT) throw PreviewLimitException()
                val name = entry.name.replace('\\', '/').removePrefix("/")
                when {
                    name == "ppt/presentation.xml" || name == "ppt/_rels/presentation.xml.rels" ||
                        (name.startsWith("ppt/slides/") || name.startsWith("ppt/notesSlides/")) && (name.endsWith(".xml") || name.endsWith(".rels")) -> {
                        val part = Bounded.read(zip, PART_LIMIT, checkActive)
                        partsTotal += part.size
                        if (partsTotal > PARTS_TOTAL) throw PreviewLimitException()
                        parts[name] = part
                    }
                    name.startsWith("ppt/media/") && WordHtml.imageType(name) != null -> {
                        val image = try { Bounded.read(zip, SlideView.IMAGE_LIMIT, checkActive) } catch (_: PreviewLimitException) { null }
                        image?.let { store.add(WordHtml.imageType(name)!!, it) }?.let { media[name] = it }
                    }
                }
            }
        }
        val presentation = parts["ppt/presentation.xml"] ?: throw UnsupportedDocumentException("Not a PowerPoint presentation")
        val relations = parts["ppt/_rels/presentation.xml.rels"]?.let { relations(it, "ppt/presentation.xml") } ?: emptyMap()
        val order = mutableListOf<String>()
        SafeXml.parse(presentation, object : DefaultHandlerAdapter() {
            override fun start(uri: String, local: String, attributes: org.xml.sax.Attributes) {
                if (uri == P && local == "sldId" && order.size < SLIDE_LIMIT) attributes.getValue(R, "id")?.let(order::add)
            }
        })
        val slides = order.mapNotNull { id ->
            checkActive()
            val path = relations[id]?.takeIf { !it.external }?.target ?: return@mapNotNull null
            val xml = parts[path] ?: return@mapNotNull null
            val slideRelations = parts[relationsPath(path)]?.let { relations(it, path) } ?: emptyMap()
            val blocks = Shapes(slideRelations, media, notes = false, checkActive).also { SafeXml.parse(xml, it) }.blocks
            val notesPath = slideRelations.values.firstOrNull { it.type.endsWith("/notesSlide") && !it.external }?.target
            val notes = notesPath?.let(parts::get)?.let { notesXml ->
                Shapes(emptyMap(), emptyMap(), notes = true, checkActive).also { SafeXml.parse(notesXml, it) }.blocks
                    .filterIsInstance<SlideBlock.Text>().flatMap { it.paragraphs }
            } ?: emptyList()
            Slide(SlideView.ordered(blocks), notes)
        }
        return Deck(slides)
    }

    private fun relationsPath(part: String) = part.substringBeforeLast('/') + "/_rels/" + part.substringAfterLast('/') + ".rels"

    /** Relationship targets resolved against the folder of [source]. */
    private fun relations(bytes: ByteArray, source: String): Map<String, Relation> {
        val result = HashMap<String, Relation>()
        SafeXml.parse(bytes, object : DefaultHandlerAdapter() {
            override fun start(uri: String, local: String, attributes: org.xml.sax.Attributes) {
                if (local != "Relationship") return
                val id = attributes.getValue("", "Id") ?: return
                val target = attributes.getValue("", "Target") ?: return
                val external = attributes.getValue("", "TargetMode").equals("External", true)
                result[id] = Relation(if (external) target else resolve(source, target), attributes.getValue("", "Type").orEmpty(), external)
            }
        })
        return result
    }

    private fun resolve(source: String, target: String): String {
        val path = if (target.startsWith("/")) target.removePrefix("/") else source.substringBeforeLast('/', "") + "/" + target
        val parts = ArrayDeque<String>()
        path.split('/').forEach { segment -> when (segment) { "", "." -> Unit; ".." -> parts.removeLastOrNull(); else -> parts.addLast(segment) } }
        return parts.joinToString("/")
    }

    /** Shapes of one slide or notes page, in drawing order; `mc:Fallback` copies of the same content are skipped. */
    private class Shapes(
        val relations: Map<String, Relation>,
        val media: Map<String, String>,
        val notes: Boolean,
        val checkActive: () -> Unit,
    ) : DefaultHandlerAdapter() {
        val blocks = mutableListOf<SlideBlock>()
        private var nodes = 0
        private var fallback = 0
        private var kind = TextKind.OTHER
        private var inShape = false
        private var paragraphs = mutableListOf<SlideParagraph>()
        private var level = 0
        private var explicit: Boolean? = null
        private val text = StringBuilder()
        private var inText = false
        private var inTable = false
        private var inPicture = false

        override fun start(uri: String, local: String, attributes: org.xml.sax.Attributes) {
            if (++nodes > NODE_LIMIT) throw PreviewLimitException()
            if (nodes % 1024 == 0) checkActive()
            if (uri == MC && local == "Fallback") fallback++
            if (fallback > 0) return
            when {
                uri == P && local == "sp" -> { inShape = true; kind = if (notes) TextKind.SKIPPED else TextKind.OTHER; paragraphs = mutableListOf() }
                uri == P && local == "ph" && inShape -> kind = placeholder(attributes.getValue("", "type"))
                uri == P && local == "graphicFrame" -> paragraphs = mutableListOf()
                uri == P && local == "pic" -> inPicture = true
                uri == A && local == "tbl" -> inTable = true
                uri == A && local == "p" -> { level = 0; explicit = null; text.setLength(0) }
                uri == A && local == "pPr" -> level = attributes.getValue("", "lvl")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
                uri == A && local == "buNone" -> explicit = false
                uri == A && (local == "buChar" || local == "buAutoNum" || local == "buBlip") -> explicit = true
                uri == A && local == "t" -> inText = true
                uri == A && local == "br" -> text.append('\n')
                // Picture frames only, as in .ppt; shape and background fills are decoration.
                uri == A && local == "blip" && inPicture && !notes -> picture(attributes.getValue(R, "embed"))
            }
        }

        override fun end(uri: String, local: String) {
            if (uri == MC && local == "Fallback") { fallback--; return }
            if (fallback > 0) return
            when {
                uri == A && local == "t" -> inText = false
                uri == A && local == "p" -> {
                    val textKind = if (inTable) TextKind.OTHER else kind
                    paragraphs += SlideParagraph(text.toString(), if (inTable) 0 else level, !inTable && SlideView.bullet(textKind, explicit))
                }
                uri == A && local == "tbl" -> inTable = false
                uri == P && local == "pic" -> inPicture = false
                uri == P && local == "sp" -> { shape(); inShape = false }
                uri == P && local == "graphicFrame" -> { if (!notes && paragraphs.any { it.text.isNotBlank() }) blocks += SlideBlock.Text(paragraphs); paragraphs = mutableListOf() }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText && fallback == 0 && text.length < 65_536) text.appendRange(ch, start, start + length)
        }

        private fun shape() {
            val visible = paragraphs.filter { it.text.isNotBlank() }
            if (visible.isEmpty() || kind == TextKind.SKIPPED) return
            blocks += if (kind == TextKind.TITLE) SlideBlock.Title(visible.joinToString("\n") { it.text.trim() }) else SlideBlock.Text(visible)
        }

        /** On notes pages only the notes body counts; slide images, numbers, dates and footers never do. */
        private fun placeholder(type: String?): TextKind = when (type) {
            "title", "ctrTitle" -> if (notes) TextKind.SKIPPED else TextKind.TITLE
            "subTitle" -> if (notes) TextKind.SKIPPED else TextKind.SUBTITLE
            "dt", "ftr", "hdr", "sldNum", "sldImg" -> TextKind.SKIPPED
            "body" -> if (notes) TextKind.NOTES else TextKind.BODY
            else -> if (notes) TextKind.SKIPPED else TextKind.BODY
        }

        private fun picture(id: String?) {
            val relation = id?.let(relations::get)?.takeIf { !it.external } ?: return
            blocks += SlideBlock.Picture(media[relation.target] ?: return)
        }
    }
}

/** A SAX handler without the qualified-name noise; text and structure callbacks only. */
internal abstract class DefaultHandlerAdapter : org.xml.sax.helpers.DefaultHandler() {
    open fun start(uri: String, local: String, attributes: org.xml.sax.Attributes) {}
    open fun end(uri: String, local: String) {}
    final override fun startElement(uri: String, localName: String, qName: String?, attributes: org.xml.sax.Attributes) = start(uri, localName, attributes)
    final override fun endElement(uri: String, localName: String, qName: String?) = end(uri, localName)
}
