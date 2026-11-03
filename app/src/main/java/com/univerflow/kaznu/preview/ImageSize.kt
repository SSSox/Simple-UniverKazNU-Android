package com.univerflow.kaznu.preview

/**
 * A picture's pixel size from its PNG, JPEG, GIF, BMP or WebP header, without decoding (null otherwise). A JPEG
 * rotated by EXIF reports its turned size, as WebView shows it.
 */
object ImageSize {
    private const val MAX = 65_535

    fun of(bytes: ByteArray): Pair<Int, Int>? = runCatching {
        when {
            bytes.startsWith(0x89, 'P', 'N', 'G') -> size(be32(bytes, 16), be32(bytes, 20))
            bytes.startsWith('G', 'I', 'F', '8') -> size(le16(bytes, 6), le16(bytes, 8))
            bytes.startsWith('B', 'M') -> if (le32(bytes, 14) == 12) size(le16(bytes, 18), le16(bytes, 20))
                else size(le32(bytes, 18), kotlin.math.abs(le32(bytes, 22)))
            bytes.startsWith(0xFF, 0xD8) -> jpeg(bytes)
            bytes.startsWith('R', 'I', 'F', 'F') && text(bytes, 8, 4) == "WEBP" -> webp(bytes)
            else -> null
        }
    }.getOrNull()

    private fun size(width: Int, height: Int) = if (width in 1..MAX && height in 1..MAX) width to height else null

    private fun jpeg(bytes: ByteArray): Pair<Int, Int>? {
        var turned = false
        var at = 2
        while (at + 4 <= bytes.size) {
            if (u8(bytes, at) != 0xFF) return null
            val marker = u8(bytes, at + 1)
            if (marker == 0xFF) { at++; continue } // Fill byte.
            if (marker == 0x01 || marker in 0xD0..0xD7) { at += 2; continue } // No length.
            val length = be16(bytes, at + 2)
            if (length < 2) return null
            when (marker) {
                // EXIF orientations 5 to 8 turn the picture a quarter.
                0xE1 -> if (text(bytes, at + 4, 4) == "Exif") turned = (orientation(bytes, at + 10, at + 2 + length) ?: 1) in 5..8
                0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF -> {
                    val height = be16(bytes, at + 5)
                    val width = be16(bytes, at + 7)
                    return if (turned) size(height, width) else size(width, height)
                }
                0xDA, 0xD9 -> return null // Image data before any frame header.
            }
            at += 2 + length
        }
        return null
    }

    /** The orientation tag of the TIFF block at [start] (an EXIF segment ending at [end]), if any. */
    private fun orientation(bytes: ByteArray, start: Int, end: Int): Int? {
        val little = when (text(bytes, start, 2)) { "II" -> true; "MM" -> false; else -> return null }
        fun u16(at: Int) = if (little) le16(bytes, at) else be16(bytes, at)
        fun u32(at: Int) = if (little) le32(bytes, at) else be32(bytes, at)
        val directory = start + u32(start + 4)
        if (directory !in start until end - 2) return null
        val entries = u16(directory)
        for (index in 0 until minOf(entries, 512)) {
            val entry = directory + 2 + index * 12
            if (entry + 12 > end) return null
            if (u16(entry) == 0x0112) return u16(entry + 8)
        }
        return null
    }

    private fun webp(bytes: ByteArray): Pair<Int, Int>? = when (text(bytes, 12, 4)) {
        "VP8 " -> size(le16(bytes, 26) and 0x3FFF, le16(bytes, 28) and 0x3FFF)
        "VP8L" -> {
            val bits = le32(bytes, 21)
            size((bits and 0x3FFF) + 1, ((bits ushr 14) and 0x3FFF) + 1)
        }
        "VP8X" -> size(le24(bytes, 24) + 1, le24(bytes, 27) + 1)
        else -> null
    }

    private fun ByteArray.startsWith(vararg values: Any) = size >= values.size && values.indices.all { index ->
        val expected = when (val value = values[index]) { is Char -> value.code; else -> value as Int }
        u8(this, index) == expected
    }

    private fun u8(bytes: ByteArray, at: Int) = bytes[at].toInt() and 0xFF
    private fun be16(bytes: ByteArray, at: Int) = u8(bytes, at) shl 8 or u8(bytes, at + 1)
    private fun le16(bytes: ByteArray, at: Int) = u8(bytes, at) or (u8(bytes, at + 1) shl 8)
    private fun le24(bytes: ByteArray, at: Int) = le16(bytes, at) or (u8(bytes, at + 2) shl 16)
    private fun be32(bytes: ByteArray, at: Int) = be16(bytes, at) shl 16 or be16(bytes, at + 2)
    private fun le32(bytes: ByteArray, at: Int) = le16(bytes, at) or (le16(bytes, at + 2) shl 16)
    private fun text(bytes: ByteArray, at: Int, length: Int) = if (at >= 0 && at + length <= bytes.size) String(bytes, at, length, Charsets.ISO_8859_1) else ""
}
