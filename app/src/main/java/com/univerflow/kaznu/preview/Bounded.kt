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

/** XML parts of Office and OpenDocument files: namespace-aware SAX without DTDs or external entities. */
internal object SafeXml {
    fun parse(bytes: ByteArray, handler: DefaultHandler) {
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
