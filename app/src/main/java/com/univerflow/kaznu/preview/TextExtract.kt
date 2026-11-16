package com.univerflow.kaznu.preview

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Plain text of downloaded files for the search in "Downloads". The format comes from the file's first bytes, not
 * its name: a ".doc" is often RTF, HTML or .docx inside.
 */
object TextExtract {
    /** Characters kept per file; enough for any course document, small enough to keep in memory. */
    const val LIMIT = 1_000_000
    /** Files read whole for the search; larger ones are left out (PDF on Android 15+ is read page by page instead). */
    const val FILE_LIMIT = PdfText.FILE_LIMIT
    private const val PART_LIMIT = 16 * 1024 * 1024
    private const val ENTRY_LIMIT = 4_000

    private val EXTENSIONS = setOf("pdf", "doc", "dot", "docx", "docm", "dotx", "dotm", "rtf", "odt", "ott", "ppt", "pps", "pot", "pptx", "ppsx",
        "pptm", "ppsm", "potx", "potm", "odp", "xls", "xlt", "xlsx", "xlsm", "xltx", "xltm", "ods", "csv", "txt", "text", "md", "htm", "html", "xml", "json", "log")

    fun extension(fileName: String) = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** Whether the search reads files of this type. */
    fun searchable(fileName: String) = extension(fileName) in EXTENSIONS

    fun text(input: InputStream, fileName: String, checkActive: () -> Unit = {}): String =
        text(Bounded.read(input, FILE_LIMIT, checkActive), fileName, checkActive)

    fun text(bytes: ByteArray, fileName: String, checkActive: () -> Unit = {}): String = when {
        bytes.isEmpty() -> ""
        PdfText.isPdf(bytes) -> PdfText.text(bytes, checkActive)
        CompoundFile.isCompound(bytes) -> compound(CompoundFile(bytes), bytes, checkActive)
        isZip(bytes) -> zip(bytes, checkActive)
        Rtf.isRtf(bytes) -> Rtf.text(bytes, checkActive)
        HtmlText.isHtml(bytes) || extension(fileName) in setOf("htm", "html") -> HtmlText.text(bytes)
        extension(fileName) in setOf("pdf", "doc", "dot", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp") -> ""
        else -> TextPreview.decode(bytes, minOf(bytes.size, TextPreview.LIMIT), truncated = bytes.size > TextPreview.LIMIT)
    }.take(LIMIT)

    internal fun isZip(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
        bytes[2].toInt() == 3 && bytes[3].toInt() == 4

    private fun compound(file: CompoundFile, bytes: ByteArray, checkActive: () -> Unit) = when {
        file.has("WordDocument") -> WordBinary.text(bytes, checkActive)
        file.has("PowerPoint Document") -> LegacyOffice.pptText(file, checkActive)
        file.has("Workbook") || file.has("Book") -> LegacyOffice.xlsText(file, checkActive)
        else -> ""
    }

    /** Office Open XML (.docx, .pptx with notes, .xlsx cells) and OpenDocument (content.xml) packages. */
    private fun zip(bytes: ByteArray, checkActive: () -> Unit): String {
        // Each part becomes text as soon as it is read, and only the text that can reach the first LIMIT characters
        // is kept: a package of many huge parts (a zip bomb) holds one part's bytes at a time, never all of them.
        val byOrder = compareBy<Pair<Int, Int>>({ it.first }, { it.second })
        val parts = sortedMapOf<Pair<Int, Int>, Part>(byOrder)
        var kept = 0
        var openDocument: ByteArray? = null
        var workbook = false
        var entries = 0
        val passed = PassedOver(checkActive)
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                checkActive()
                val entry = zip.nextEntry ?: break
                if (++entries > ENTRY_LIMIT) throw PreviewLimitException()
                val name = entry.name.replace('\\', '/')
                val order = order(name)
                when {
                    name == "content.xml" -> openDocument = Bounded.read(zip, PART_LIMIT, checkActive)
                    name == "xl/workbook.xml" -> workbook = true
                    // A workbook (Excel writes xl/workbook.xml first) is read by XlsxReader below; its shared strings
                    // are not parsed here as well. A part placed after text that already fills the limit would add
                    // nothing: it is not read.
                    order != null -> if (!workbook && (kept < LIMIT || byOrder.compare(order, parts.lastKey()) <= 0)) {
                        val part = readPart(Bounded.read(zip, PART_LIMIT, checkActive))
                        parts.put(order, part)?.let { kept -= it.length }
                        kept += part.length
                        // The last parts drop out once the ones before them reach the limit on their own.
                        while (parts.size > 1) {
                            val last = parts.getValue(parts.lastKey())
                            if (kept - last.length < LIMIT) break
                            parts.remove(parts.lastKey())
                            kept -= last.length
                        }
                    }
                }
                passed.rest(zip)
            }
        }
        openDocument?.let { return OpenDocumentText.text(it, checkActive) }
        // Workbooks give their cells row by row, numbers included, as the table view shows them; a package without
        // its workbook part still gives its shared strings.
        if (workbook) return SheetView.text(XlsxReader.read(bytes, checkActive))
        val text = StringBuilder()
        for (part in parts.values) {
            if (text.length >= LIMIT) break
            part.error?.let { throw it }
            text.append(part.text)
            if (text.isNotEmpty() && text.last() != '\n') text.append('\n')
        }
        return text.toString().take(LIMIT).trim()
    }

    /** A package part's text, or the error its XML gave: raised only if the text reaches the part, as in order. */
    private class Part(val text: String, val error: Exception? = null) {
        /** Characters it adds to the text: its own and the line end after them. */
        val length = text.length + if (text.isNotEmpty() && text.last() != '\n') 1 else 0
    }

    private fun readPart(xml: ByteArray): Part {
        val text = StringBuilder()
        return try {
            officeXml(xml, text)
            Part(text.toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Part("", error)
        }
    }

    /** Where a package part's text goes: the document, slides then their notes in slide order, shared strings. */
    private fun order(name: String): Pair<Int, Int>? = when {
        name == "word/document.xml" -> 0 to 0
        name == "xl/sharedStrings.xml" -> 0 to 0
        else -> SLIDE.matchEntire(name)?.let { 1 to it.groupValues[1].toInt() * 2 }
            ?: NOTES.matchEntire(name)?.let { 1 to it.groupValues[1].toInt() * 2 + 1 }
    }

    private val SLIDE = Regex("^ppt/slides/slide(\\d{1,5})\\.xml$")
    private val NOTES = Regex("^ppt/notesSlides/notesSlide(\\d{1,5})\\.xml$")

    /** Text of WordprocessingML, DrawingML and SpreadsheetML parts: `t` elements, paragraphs and shared strings end lines. */
    private fun officeXml(bytes: ByteArray, out: StringBuilder) {
        SafeXml.parse(bytes, object : DefaultHandler() {
            private var inText = false
            override fun startElement(uri: String?, localName: String, qName: String?, attributes: Attributes?) {
                when (localName) {
                    "t" -> inText = true
                    "tab" -> if (out.length < LIMIT) out.append(' ')
                    "br" -> if (out.length < LIMIT) out.append('\n')
                }
            }
            override fun endElement(uri: String?, localName: String, qName: String?) {
                when (localName) {
                    "t" -> inText = false
                    "p", "si" -> if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText && out.length < LIMIT) out.appendRange(ch, start, start + length)
            }
        })
    }
}

/** The reading view of any Word-like file, whatever its name says: .docx, .doc, RTF or a web page saved as .doc. */
object WordView {
    /** RTF and web pages saved as .doc are read whole; .docx is streamed and .doc read in place, whatever their size. */
    const val FILE_LIMIT = 96 * 1024 * 1024

    fun html(input: InputStream, checkActive: () -> Unit = {}): String = html(Bounded.read(input, FILE_LIMIT, checkActive), checkActive)

    fun html(bytes: ByteArray, checkActive: () -> Unit = {}, media: PreviewMedia = PreviewMedia.inline()): String = when {
        TextExtract.isZip(bytes) -> DocxHtml.convert(ByteArrayInputStream(bytes), checkActive, media)
        CompoundFile.isCompound(bytes) -> WordBinary.html(bytes, checkActive, media)
        Rtf.isRtf(bytes) -> Rtf.html(bytes, checkActive, media)
        HtmlText.isHtml(bytes) -> WordFlow.html(HtmlText.text(bytes).split('\n').filter { it.isNotBlank() }.map { line ->
            WordFlow.Paragraph(listOf(WordFlow.Item.Text(line, RunStyle())), WordFlow.Props())
        })
        else -> throw UnsupportedDocumentException("Not a Word document")
    }

    /** The viewer's way in: a .docx streamed, a .doc read in place from the open file, anything else read whole. */
    internal fun html(file: FileAccess, checkActive: () -> Unit, media: PreviewMedia): String = when {
        file.zip -> file.stream().use { DocxHtml.convert(it, checkActive, media) }
        file.compound -> WordBinary.html(CompoundFile(file.source()), checkActive, media)
        else -> file.stream().use { html(Bounded.read(it, FILE_LIMIT, checkActive), checkActive, media) }
    }
}
