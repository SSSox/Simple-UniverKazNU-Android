package com.univerflow.kaznu.preview

import java.nio.charset.Charset

/** Simple-font encodings of PDF and the Adobe glyph names that fonts' Differences arrays use. */
internal object Encodings {
    /** WinAnsiEncoding: Windows-1252 (printable codes only). */
    val WIN_ANSI: Map<Int, String> = decodeTable("windows-1252")

    private val MAC_ROMAN: Map<Int, String> by lazy { runCatching { decodeTable("x-MacRoman") }.getOrDefault(WIN_ANSI) }

    /** StandardEncoding differs from ASCII mostly in its quotes. */
    private val STANDARD: Map<Int, String> by lazy {
        (0x20..0x7E).associateWith { it.toChar().toString() } + mapOf(0x27 to "’", 0x60 to "‘")
    }

    fun named(name: String): Map<Int, String> = when (name) {
        "MacRomanEncoding" -> MAC_ROMAN
        "StandardEncoding" -> STANDARD
        else -> WIN_ANSI
    }

    private fun decodeTable(charset: String): Map<Int, String> {
        val decoder = Charset.forName(charset)
        return (0x20..0xFF).mapNotNull { code ->
            val text = String(byteArrayOf(code.toByte()), decoder)
            if (text.isEmpty() || text[0] == '�' || text[0].code in 0x7F..0x9F) null else code to text
        }.toMap()
    }

    /** A glyph name as text: uniXXXX, uXXXX, Cyrillic afii names, the common Latin and punctuation names, ligatures. */
    fun glyph(name: String): String? {
        val base = name.substringBefore('.')
        if (base.isEmpty()) return null
        if (base.length == 1 && base[0].isLetterOrDigit()) return base
        NAMES[base]?.let { return it }
        if (base.startsWith("uni") && base.length >= 7 && (base.length - 3) % 4 == 0) {
            val units = base.substring(3).chunked(4).map { it.toIntOrNull(16) ?: return null }
            return String(CharArray(units.size) { units[it].toChar() })
        }
        if (base.startsWith("u") && base.length in 5..7) base.substring(1).toIntOrNull(16)?.let { return String(Character.toChars(it)) }
        if (base.startsWith("afii")) base.substring(4).toIntOrNull()?.let { afii(it) }?.let { return it }
        if ('_' in base) {
            val parts = base.split('_').map { glyph(it) }
            return if (parts.all { it != null }) parts.joinToString("") else null
        }
        return null
    }

    /** The Cyrillic afii names: А–Я 10017–10049 (Ё 10023), а–я 10065–10097 (ё 10071), and a few others. */
    private fun afii(number: Int): String? = when (number) {
        in 10017..10022 -> (0x0410 + number - 10017).toChar().toString()
        10023 -> "Ё"
        in 10024..10049 -> (0x0416 + number - 10024).toChar().toString()
        in 10065..10070 -> (0x0430 + number - 10065).toChar().toString()
        10071 -> "ё"
        in 10072..10097 -> (0x0436 + number - 10072).toChar().toString()
        10051 -> "Ђ"; 10052 -> "Ѓ"; 10053 -> "Є"; 10054 -> "Ѕ"; 10055 -> "І"; 10056 -> "Ї"; 10057 -> "Ј"; 10058 -> "Љ"; 10059 -> "Њ"
        10060 -> "Ћ"; 10061 -> "Ќ"; 10062 -> "Ў"; 10145 -> "Џ"; 10099 -> "ђ"; 10100 -> "ѓ"; 10101 -> "є"; 10102 -> "ѕ"; 10103 -> "і"
        10104 -> "ї"; 10105 -> "ј"; 10106 -> "љ"; 10107 -> "њ"; 10108 -> "ћ"; 10109 -> "ќ"; 10110 -> "ў"; 10193 -> "џ"
        61352 -> "№"
        else -> null
    }

    private val NAMES: Map<String, String> = mapOf(
        "space" to " ", "nbspace" to " ", "nonbreakingspace" to " ", "exclam" to "!", "quotedbl" to "\"", "numbersign" to "#",
        "dollar" to "$", "percent" to "%", "ampersand" to "&", "quotesingle" to "'", "quoteright" to "’", "quoteleft" to "‘",
        "parenleft" to "(", "parenright" to ")", "asterisk" to "*", "plus" to "+", "comma" to ",", "hyphen" to "-", "minus" to "−",
        "period" to ".", "slash" to "/", "colon" to ":", "semicolon" to ";", "less" to "<", "equal" to "=", "greater" to ">",
        "question" to "?", "at" to "@", "bracketleft" to "[", "backslash" to "\\", "bracketright" to "]", "asciicircum" to "^",
        "underscore" to "_", "grave" to "`", "braceleft" to "{", "bar" to "|", "braceright" to "}", "asciitilde" to "~",
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
        "eight" to "8", "nine" to "9", "bullet" to "•", "endash" to "–", "emdash" to "—", "ellipsis" to "…",
        "quotedblleft" to "“", "quotedblright" to "”", "quotesinglbase" to "‚", "quotedblbase" to "„", "guillemotleft" to "«",
        "guillemotright" to "»", "guilsinglleft" to "‹", "guilsinglright" to "›", "degree" to "°", "copyright" to "©",
        "registered" to "®", "trademark" to "™", "section" to "§", "paragraph" to "¶", "Euro" to "€", "multiply" to "×",
        "divide" to "÷", "plusminus" to "±", "periodcentered" to "·", "dagger" to "†", "daggerdbl" to "‡", "perthousand" to "‰",
        "fi" to "fi", "fl" to "fl", "ff" to "ff", "ffi" to "ffi", "ffl" to "ffl", "germandbls" to "ß", "onehalf" to "½",
        "onequarter" to "¼", "threequarters" to "¾", "mu" to "µ", "sterling" to "£", "yen" to "¥", "cent" to "¢",
        "exclamdown" to "¡", "questiondown" to "¿", "macron" to "¯", "acute" to "´", "dieresis" to "¨", "cedilla" to "¸",
    )
}
