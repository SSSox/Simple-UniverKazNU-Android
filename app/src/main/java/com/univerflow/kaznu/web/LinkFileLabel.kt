package com.univerflow.kaznu.web

/**
 * Text of the confirmation before a file from the link window opens in another app.
 * The site comes first. A long site is cut from the left on a dot, so the real domain stays
 * and a trusted-looking prefix cannot fill the three-line bar.
 * The name is shortened in the middle: the start and the extension both stay.
 * Characters a page can use to break the line, reorder the site or hide text are left out, by code point.
 */
object LinkFileLabel {
    /**
     * Trailing labels of the site, with a leading … when the rest was cut.
     * Together with [NAME_LIMIT] the longest confirmation fits in three lines at a large font:
     * 14sp at scale 1.15 beside «Открыть» on a 360dp phone is about 27 letters a line, 81 in all.
     * The longest sentence (Russian) adds 32 letters around these two, saying the file opens in another app.
     */
    const val HOST_LIMIT = 34

    /** Middle of the file name. See [HOST_LIMIT] for the three-line budget. */
    const val NAME_LIMIT = 15
    private const val ELLIPSIS = "…"

    fun visibleHost(raw: String): String {
        val host = clean(raw).trim()
        val count = host.codePointCount(0, host.length)
        if (count <= HOST_LIMIT) return host
        val room = HOST_LIMIT - ELLIPSIS.codePointCount(0, ELLIPSIS.length)
        val boundary = labelTail(host, room)
        if (boundary >= 0) return ELLIPSIS + host.substring(boundary)
        val start = host.offsetByCodePoints(0, count - room)
        return ELLIPSIS + host.substring(start)
    }

    /** The file's name as it may be shown, or null when nothing safe is left of it. */
    fun visibleName(raw: String?): String? {
        val cleaned = clean(raw ?: return null).trim()
        if (cleaned.isEmpty()) return null
        return middle(cleaned, NAME_LIMIT)
    }

    /** By code point, so a supplementary format mark (the tag characters) or a lone surrogate never stays. */
    private fun clean(raw: String): String = buildString(raw.length) {
        var index = 0
        while (index < raw.length) {
            val point = raw.codePointAt(index)
            index += Character.charCount(point)
            if (!hidden(point)) appendCodePoint(point)
        }
    }

    private fun hidden(point: Int): Boolean = when (Character.getType(point).toByte()) {
        Character.FORMAT, Character.CONTROL, Character.SURROGATE, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> point in HIDDEN || point in 0xFE00..0xFE0F || point in 0xE0000..0xE007F || point in 0xE0100..0xE01EF
    }

    /** Start of the longest whole-label suffix that fits in [room], or -1 when no dot leaves one. */
    private fun labelTail(host: String, room: Int): Int {
        var dot = host.indexOf('.')
        while (dot >= 0) {
            val suffix = dot + 1
            if (suffix < host.length && host.codePointCount(suffix, host.length) <= room) return suffix
            dot = host.indexOf('.', suffix)
        }
        return -1
    }

    private fun middle(name: String, limit: Int): String {
        val count = name.codePointCount(0, name.length)
        if (count <= limit) return name
        if (limit <= 1) return ELLIPSIS
        val keep = limit - 1
        val head = keep / 2
        val tail = keep - head
        val headEnd = name.offsetByCodePoints(0, head)
        val tailStart = name.offsetByCodePoints(0, count - tail)
        return name.substring(0, headEnd) + ELLIPSIS + name.substring(tailStart)
    }

    /**
     * Invisible marks some Unicode versions do not class as format: the soft hyphen, the Mongolian vowel separator,
     * the bidi marks and isolates, the Hangul fillers and the interlinear annotation characters. The variation
     * selectors (ranges in [hidden]) are invisible too.
     */
    private val HIDDEN = setOf(
        0x00AD, 0x034F, 0x061C, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180B, 0x180C, 0x180D, 0x180E, 0x180F,
        0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
        0x2060, 0x2061, 0x2062, 0x2063, 0x2064, 0x2066, 0x2067, 0x2068, 0x2069,
        0x206A, 0x206B, 0x206C, 0x206D, 0x206E, 0x206F, 0x3164, 0xFEFF, 0xFFA0, 0xFFF9, 0xFFFA, 0xFFFB,
    )
}
