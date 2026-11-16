package com.univerflow.kaznu.preview

import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/** A file (or a part of it) is past what the preview and the text search read. */
class PreviewLimitException : Exception()

/** The file's format is known but this one cannot be read (encrypted, too old, damaged). */
class UnsupportedDocumentException(message: String) : Exception(message)

/** Reading limits shared by every preview and text reader: a hostile or broken file must not exhaust memory. */
internal object Bounded {
    /** Reads the whole stream, failing past [limit] bytes (this also stops decompression bombs). */
    fun read(input: InputStream, limit: Int, checkActive: () -> Unit = {}): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw PreviewLimitException()
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

/**
 * The parts of one zip package that are not read (other documents' parts, video, pictures past their limit). They
 * still have to be inflated to reach the next part, so that work is counted and can be cancelled.
 */
internal class PassedOver(private val checkActive: () -> Unit) {
    private var left = LIMIT
    private val buffer = ByteArray(64 * 1024)

    /** Reads [entry], the current entry of a zip stream, to its end. */
    fun rest(entry: InputStream) {
        while (true) {
            checkActive()
            val count = entry.read(buffer)
            if (count < 0) return
            left -= count
            if (left < 0) throw PreviewLimitException()
        }
    }

    private companion object {
        // Far above what a file within the readers' size limits holds unless it is a decompression bomb.
        const val LIMIT = 1024L * 1024 * 1024
    }
}

/** XML parts of Office and OpenDocument files: namespace-aware SAX without DTDs or external entities. */
internal object SafeXml {
    fun parse(bytes: ByteArray, handler: DefaultHandler) {
        // Android's parser does not know the DOCTYPE ban below and expands internal entities in native code, where
        // cancelling cannot stop it, so a document type declaration is refused before the part reaches the parser.
        if (declaresDocumentType(bytes)) throw UnsupportedDocumentException("Document type declarations are not supported")
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }
        // These parts never have a DTD; external entities are refused where the platform parser supports it.
        listOf("http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false).forEach { (feature, value) ->
            runCatching { factory.setFeature(feature, value) }
        }
        val reader = factory.newSAXParser().xmlReader
        reader.setEntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
        reader.contentHandler = handler
        reader.parse(InputSource(ByteArrayInputStream(bytes)))
    }

    /**
     * Whether the prolog (what precedes the root element: the XML declaration, comments, processing instructions and
     * spaces) holds `<!DOCTYPE`. The encoding is told as the parser tells it: a byte order mark or a zero byte in the
     * first two selects UTF-16, otherwise ASCII characters are single bytes (UTF-8, ISO-8859-1, US-ASCII).
     */
    private fun declaresDocumentType(bytes: ByteArray): Boolean {
        if (bytes.size < 2) return false
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        val utf16Mark = first == 0xFE && second == 0xFF || first == 0xFF && second == 0xFE
        val width = if (utf16Mark || first == 0 || second == 0) 2 else 1
        val bigEndian = first == 0 || first == 0xFE && second == 0xFF
        var at = when {
            utf16Mark -> 2
            first == 0xEF && second == 0xBB && bytes.size > 2 && bytes[2] == 0xBF.toByte() -> 3
            else -> 0
        }
        fun char(index: Int): Int {
            if (width == 1) return bytes[index].toInt() and 0xFF
            val high = bytes[if (bigEndian) index else index + 1].toInt() and 0xFF
            val low = bytes[if (bigEndian) index + 1 else index].toInt() and 0xFF
            return (high shl 8) or low
        }
        fun has(index: Int, text: String) = index + text.length * width <= bytes.size && text.indices.all { char(index + it * width) == text[it].code }
        /** Where the [text] ending a comment or an instruction ends, or -1 when the part ends first. */
        fun after(from: Int, text: String): Int {
            var index = from
            while (index + text.length * width <= bytes.size) {
                if (has(index, text)) return index + text.length * width
                index += width
            }
            return -1
        }
        while (at + width <= bytes.size) {
            val code = char(at)
            at = when {
                code == 0x20 || code == 0x09 || code == 0x0A || code == 0x0D -> at + width
                has(at, "<?") -> after(at + 2 * width, "?>")
                has(at, "<!--") -> after(at + 4 * width, "-->")
                // The root element, or something the parser refuses anyway.
                else -> return has(at, "<!DOCTYPE")
            }
            if (at < 0) return false
        }
        return false
    }
}

/** Little-endian reads with bounds checks; a damaged file fails with [UnsupportedDocumentException]. */
internal class LittleEndian(val bytes: ByteArray, private val start: Int = 0, val size: Int = bytes.size - start) {
    private fun at(offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset.toLong() + length > size) throw UnsupportedDocumentException("Damaged file")
        return start + offset
    }
    fun u8(offset: Int) = bytes[at(offset, 1)].toInt() and 0xFF
    fun u16(offset: Int) = at(offset, 2).let { (bytes[it].toInt() and 0xFF) or ((bytes[it + 1].toInt() and 0xFF) shl 8) }
    fun i16(offset: Int) = u16(offset).toShort().toInt()
    fun i32(offset: Int) = at(offset, 4).let {
        (bytes[it].toInt() and 0xFF) or ((bytes[it + 1].toInt() and 0xFF) shl 8) or
            ((bytes[it + 2].toInt() and 0xFF) shl 16) or ((bytes[it + 3].toInt() and 0xFF) shl 24)
    }
    fun u32(offset: Int) = i32(offset).toLong() and 0xFFFFFFFFL
    fun has(offset: Int, length: Int) = offset >= 0 && length >= 0 && offset.toLong() + length <= size
    fun slice(offset: Int, length: Int) = bytes.copyOfRange(at(offset, length), start + offset + length)
    fun utf16(offset: Int, chars: Int) = String(bytes, at(offset, chars * 2), chars * 2, Charsets.UTF_16LE)
}
