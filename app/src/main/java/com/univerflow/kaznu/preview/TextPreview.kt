package com.univerflow.kaznu.preview

import java.io.InputStream
import java.nio.charset.Charset

data class TextContent(val text: String, val truncated: Boolean)

/** Reads at most [limit] bytes; Univer text files are usually UTF-8 or Windows-1251. */
object TextPreview {
    const val LIMIT = 8 * 1024 * 1024

    fun read(input: InputStream, limit: Int = LIMIT, checkActive: () -> Unit = {}): TextContent {
        val buffer = ByteArray(limit + 1)
        var size = 0
        while (size < buffer.size) {
            checkActive()
            val count = input.read(buffer, size, buffer.size - size)
            if (count < 0) break
            size += count
        }
        val truncated = size > limit
        return TextContent(decode(buffer, minOf(size, limit), truncated), truncated)
    }

    fun decode(bytes: ByteArray, length: Int, truncated: Boolean = false): String {
        fun starts(vararg prefix: Int) = length >= prefix.size && prefix.indices.all { bytes[it].toInt() and 0xFF == prefix[it] }
        return when {
            starts(0xEF, 0xBB, 0xBF) -> String(bytes, 3, utf8Length(bytes, 3, length, truncated) - 3, Charsets.UTF_8)
            starts(0xFF, 0xFE) -> String(bytes, 2, (length - 2) and 1.inv(), Charsets.UTF_16LE)
            starts(0xFE, 0xFF) -> String(bytes, 2, (length - 2) and 1.inv(), Charsets.UTF_16BE)
            isUtf8(bytes, length, truncated) -> String(bytes, 0, utf8Length(bytes, 0, length, truncated), Charsets.UTF_8)
            else -> String(bytes, 0, length, Charset.forName("windows-1251"))
        }
    }

    /** Strict UTF-8 validation; a sequence cut by the size limit at the very end is not an error. */
    fun isUtf8(bytes: ByteArray, length: Int, truncated: Boolean): Boolean {
        var index = 0
        while (index < length) {
            val lead = bytes[index].toInt() and 0xFF
            val extra = when {
                lead < 0x80 -> 0
                lead in 0xC2..0xDF -> 1
                lead in 0xE0..0xEF -> 2
                lead in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (index + extra >= length) return truncated && index + extra >= length && (1..minOf(extra, length - index - 1)).all {
                bytes[index + it].toInt() and 0xC0 == 0x80 }
            for (offset in 1..extra) if (bytes[index + offset].toInt() and 0xC0 != 0x80) return false
            index += extra + 1
        }
        return true
    }

    private fun utf8Length(bytes: ByteArray, start: Int, length: Int, truncated: Boolean): Int {
        if (!truncated) return length
        // Drop an incomplete trailing sequence instead of decoding it as a replacement character.
        var end = length
        var back = 0
        while (end - back - 1 >= start && back < 3 && bytes[end - back - 1].toInt() and 0xC0 == 0x80) back++
        val leadIndex = end - back - 1
        if (leadIndex >= start) {
            val lead = bytes[leadIndex].toInt() and 0xFF
            val needed = when { lead >= 0xF0 -> 3; lead >= 0xE0 -> 2; lead >= 0xC0 -> 1; else -> 0 }
            if (needed > back) end = leadIndex
        }
        return end
    }
}
