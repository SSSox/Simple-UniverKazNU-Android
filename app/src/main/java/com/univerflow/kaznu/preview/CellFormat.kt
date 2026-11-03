package com.univerflow.kaznu.preview

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DateFormatSymbols
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Excel number formats for .xls and .xlsx cells (sections, decimals, grouping, percent, scientific, text, dates).
 * Fractions and conditions fall back to the general format; separators and month names follow [Locale].
 */
internal object CellFormat {
    private val BUILT_IN = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00", 9 to "0%", 10 to "0.00%", 11 to "0.00E+00",
        12 to "# ?/?", 13 to "# ??/??", 14 to "dd.mm.yyyy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy", 18 to "h:mm AM/PM",
        19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss", 22 to "dd.mm.yyyy h:mm", 37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;(#,##0)",
        39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;(#,##0.00)", 45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mm:ss.0", 48 to "##0.0E+0", 49 to "@",
    )

    /** Excel's built-in formats are not stored in the file; 14 is the short date of the reader's locale. */
    fun builtIn(id: Int) = BUILT_IN[id] ?: "General"

    fun format(value: CellValue, code: String, date1904: Boolean, locale: Locale): String = when (value) {
        is CellValue.Text -> value.value
        is CellValue.Bool -> if (value.value) "TRUE" else "FALSE"
        is CellValue.Error -> value.code
        is CellValue.Number -> runCatching { number(value.value, code, date1904, locale) }.getOrElse { general(value.value, locale) }
    }

    private fun number(value: Double, code: String, date1904: Boolean, locale: Locale): String {
        if (value.isNaN() || value.isInfinite()) return "#NUM!"
        val sections = split(code)
        val own = when {
            value < 0 && sections.size >= 2 -> sections[1]
            value == 0.0 && sections.size >= 3 -> sections[2]
            else -> sections[0]
        }
        // A negative section draws the sign itself, with brackets or a minus written in the format.
        val signed = value < 0 && sections.size < 2
        val magnitude = if (value < 0 && sections.size >= 2) -value else value
        val section = clean(own)
        val text = when {
            section.isBlank() && own.isNotBlank() -> return literal(own)
            section.isBlank() || section.equals("General", ignoreCase = true) || section == "@" -> return general(value, locale)
            isDate(section) -> return if (value < 0) general(value, locale) else date(value, section, date1904, locale)
            else -> numeric(abs(magnitude), section, locale) ?: return general(value, locale)
        }
        return if (signed && text.any(Char::isDigit) && text.any { it in '1'..'9' }) "-$text" else text
    }

    /** "General": integers as they are, other numbers with up to ten significant digits. */
    fun general(value: Double, locale: Locale): String {
        if (value.isNaN() || value.isInfinite()) return "#NUM!"
        if (value == floor(value) && abs(value) < 1e11) return value.toLong().toString()
        val decimal = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val magnitude = abs(value)
        val plain = if (magnitude >= 1e-9 && magnitude < 1e11)
            BigDecimal(value).round(MathContext(10, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()
        else String.format(Locale.ROOT, "%.5E", value).replace(Regex("\\.?0+E"), "E")
        return plain.replace('.', decimal)
    }

    /** Splits on ";" outside quotes, escapes and brackets. */
    private fun split(code: String): List<String> {
        val sections = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < code.length) {
            val char = code[index]
            when {
                char == '"' -> { val end = code.indexOf('"', index + 1).let { if (it < 0) code.length - 1 else it }; current.append(code, index, end + 1); index = end }
                char == '\\' && index + 1 < code.length -> { current.append(code, index, index + 2); index++ }
                char == '[' -> { val end = code.indexOf(']', index).let { if (it < 0) code.length - 1 else it }; current.append(code, index, end + 1); index = end }
                char == ';' -> { sections += current.toString(); current.setLength(0) }
                else -> current.append(char)
            }
            index++
        }
        sections += current.toString()
        return sections.take(4)
    }

    /** Drops colours, conditions and locale tags; a currency tag "[$₸-43F]" leaves its symbol. Elapsed-time brackets stay. */
    private fun clean(section: String) = Regex("\\[([^\\]]*)]").replace(section) { match ->
        val inner = match.groupValues[1]
        when {
            inner.matches(Regex("(?i)h+|m+|s+")) -> match.value
            inner.startsWith("$") -> inner.drop(1).substringBefore('-').let { if (it.isEmpty()) "" else "\"$it\"" }
            else -> ""
        }
    }.trim()

    /** A section with no placeholders is text written as is, such as "-" for zero. */
    private fun literal(section: String) = tokens(clean(section)).joinToString("") { (it as? Token.Literal)?.text.orEmpty() }

    private fun isDate(section: String) = tokens(section).any { it is Token.Code && it.text[0].lowercaseChar() in "ymdhs" }

    private sealed interface Token {
        class Literal(val text: String) : Token
        class Code(val text: String) : Token
    }

    /** Literal text (quoted, escaped, padding) and code characters, so letters in quotes never act as codes. */
    private fun tokens(section: String): List<Token> {
        val result = mutableListOf<Token>()
        var index = 0
        while (index < section.length) {
            val char = section[index]
            when {
                char == '"' -> {
                    val end = section.indexOf('"', index + 1).let { if (it < 0) section.length else it }
                    result += Token.Literal(section.substring(index + 1, end)); index = end + 1
                }
                char == '\\' && index + 1 < section.length -> { result += Token.Literal(section[index + 1].toString()); index += 2 }
                char == '_' && index + 1 < section.length -> { result += Token.Literal(" "); index += 2 }
                char == '*' && index + 1 < section.length -> index += 2
                char == '[' -> { val end = section.indexOf(']', index).let { if (it < 0) section.length - 1 else it }; result += Token.Code(section.substring(index, end + 1)); index = end + 1 }
                char.lowercaseChar() in "ymdhs" || char in "0#?.,%Ee+-/@:" -> { result += Token.Code(char.toString()); index++ }
                section.regionMatches(index, "AM/PM", 0, 5, ignoreCase = true) -> { result += Token.Code("AM/PM"); index += 5 }
                section.regionMatches(index, "A/P", 0, 3, ignoreCase = true) -> { result += Token.Code("A/P"); index += 3 }
                else -> { result += Token.Literal(char.toString()); index++ }
            }
        }
        return result
    }

    private fun numeric(value: Double, section: String, locale: Locale): String? {
        val tokens = tokens(section)
        fun code(token: Token?, vararg texts: String) = token is Token.Code && token.text in texts
        if (tokens.any { code(it, "/") }) return null
        val exponent = tokens.indexOfFirst { code(it, "E", "e") }
        val mantissa = if (exponent >= 0) tokens.subList(0, exponent) else tokens
        val digits = mantissa.indices.filter { code(mantissa[it], "0", "#", "?") }
        if (digits.isEmpty()) return literal(section).ifEmpty { null }
        val first = digits.first()
        val last = digits.last()
        val inner = mantissa.subList(first, last + 1)
        val point = inner.indexOfFirst { code(it, ".") }
        val integerCodes = (if (point >= 0) inner.subList(0, point) else inner).filterIsInstance<Token.Code>().map { it.text }
        val fractionCodes = if (point >= 0) inner.subList(point + 1, inner.size).filter { code(it, "0", "#", "?") } else emptyList()
        val minInteger = integerCodes.count { it == "0" }
        val decimals = fractionCodes.size
        val minDecimals = fractionCodes.count { code(it, "0") }
        // A comma right after the last digit placeholder scales by a thousand.
        val scale = mantissa.drop(last + 1).takeWhile { code(it, ",") }.size
        val percent = tokens.count { code(it, "%") }
        val number = value * Math.pow(100.0, percent.toDouble()) / Math.pow(1000.0, scale.toDouble())
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val end: Int
        val body = if (exponent >= 0) {
            val powerDigits = tokens.drop(exponent + 1).takeWhile { code(it, "0", "#", "?", "+", "-") }
            end = exponent + 1 + powerDigits.size
            var power = if (number == 0.0) 0 else floor(Math.log10(number)).toInt()
            var scaled = number / Math.pow(10.0, power.toDouble())
            if (BigDecimal(scaled).setScale(decimals, RoundingMode.HALF_UP).toDouble() >= 10) { power++; scaled /= 10 }
            val width = powerDigits.count { code(it, "0") }.coerceAtLeast(1)
            val sign = if (power < 0) "-" else if (powerDigits.any { code(it, "+") }) "+" else ""
            digitsOf(scaled, minInteger.coerceAtLeast(1), decimals, minDecimals, false, symbols) + "E" + sign + abs(power).toString().padStart(width, '0')
        } else {
            end = last + 1
            digitsOf(number, minInteger, decimals, minDecimals, integerCodes.contains(","), symbols)
        }
        fun text(part: List<Token>) = part.joinToString("") { token ->
            if (token is Token.Literal) token.text else (token as Token.Code).text.takeIf { it == "%" || it == "-" || it == "+" || it == ":" }.orEmpty()
        }
        return text(tokens.subList(0, first)) + body + text(tokens.subList(end.coerceAtMost(tokens.size), tokens.size))
    }

    private fun digitsOf(value: Double, minInteger: Int, decimals: Int, minDecimals: Int, grouping: Boolean, symbols: DecimalFormatSymbols): String {
        val plain = BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).toPlainString()
        var integer = plain.substringBefore('.').trimStart('0')
        var fraction = if (decimals > 0) plain.substringAfter('.', "") else ""
        while (fraction.length > minDecimals && fraction.endsWith('0')) fraction = fraction.dropLast(1)
        integer = integer.padStart(minInteger, '0')
        if (grouping && integer.length > 3) {
            val separator = if (symbols.groupingSeparator == ' ' || symbols.groupingSeparator == ' ') ' ' else symbols.groupingSeparator
            integer = integer.reversed().chunked(3).joinToString(separator.toString()).reversed()
        }
        return if (fraction.isEmpty()) integer.ifEmpty { if (decimals == 0) "0" else "" } else integer + symbols.decimalSeparator + fraction
    }

    private fun date(value: Double, section: String, date1904: Boolean, locale: Locale): String {
        val tokens = tokens(section)
        val fractionDigits = tokens.indices.firstOrNull { index ->
            (tokens[index] as? Token.Code)?.text == "." && tokens.getOrNull(index - 1).let { it is Token.Code && it.text[0].lowercaseChar() == 's' }
        }?.let { index -> tokens.drop(index + 1).takeWhile { it is Token.Code && it.text == "0" }.size } ?: 0
        val unit = Math.pow(10.0, fractionDigits.toDouble())
        val total = Math.round(value * 86_400 * unit)
        val days = Math.floorDiv(total, (86_400 * unit).toLong())
        val partial = total - days * (86_400 * unit).toLong()
        val seconds = (partial / unit.toLong())
        val fraction = partial % unit.toLong()
        val (year, month, day) = civil(days, date1904)
        val hour = (seconds / 3600).toInt()
        val minute = (seconds / 60 % 60).toInt()
        val second = (seconds % 60).toInt()
        val twelveHour = tokens.any { it is Token.Code && (it.text == "AM/PM" || it.text == "A/P") }
        val symbols = DateFormatSymbols.getInstance(locale)
        // Runs of one letter form a code; "m" after hours or before seconds is minutes.
        val runs = mutableListOf<Token>()
        tokens.forEach { token ->
            val previous = runs.lastOrNull()
            if (token is Token.Code && previous is Token.Code && token.text.length == 1 && token.text[0].isLetter() &&
                previous.text[0].equals(token.text[0], ignoreCase = true)) runs[runs.size - 1] = Token.Code(previous.text + token.text)
            else runs += token
        }
        val out = StringBuilder()
        runs.forEachIndexed { index, token ->
            if (token is Token.Literal) { out.append(token.text); return@forEachIndexed }
            val code = (token as Token.Code).text
            val lower = code.lowercase(Locale.ROOT)
            fun minutes(): Boolean {
                val before = runs.subList(0, index).lastOrNull { it is Token.Code && it.text[0].lowercaseChar() in "ymdhs" } as? Token.Code
                val after = runs.subList(index + 1, runs.size).firstOrNull { it is Token.Code && it.text[0].lowercaseChar() in "ymdhs" } as? Token.Code
                return before?.text?.get(0)?.lowercaseChar() == 'h' || after?.text?.get(0)?.lowercaseChar() == 's'
            }
            when {
                lower.startsWith("[h") -> out.append((days * 24 + hour).toString().padStart(lower.length - 2, '0'))
                lower.startsWith("[m") -> out.append(((days * 24 + hour) * 60 + minute).toString().padStart(lower.length - 2, '0'))
                lower.startsWith("[s") -> out.append((((days * 24 + hour) * 60 + minute) * 60 + second).toString().padStart(lower.length - 2, '0'))
                lower[0] == 'y' -> out.append(if (lower.length <= 2) (year % 100).toString().padStart(2, '0') else year.toString())
                lower[0] == 'd' -> out.append(when (lower.length) {
                    1 -> day.toString(); 2 -> day.toString().padStart(2, '0')
                    3 -> symbols.shortWeekdays[weekday(days, date1904)]; else -> symbols.weekdays[weekday(days, date1904)]
                })
                lower[0] == 'm' && lower.length <= 2 && minutes() -> out.append(if (lower.length == 2) minute.toString().padStart(2, '0') else minute.toString())
                lower[0] == 'm' -> out.append(when (lower.length) {
                    1 -> month.toString(); 2 -> month.toString().padStart(2, '0')
                    3 -> symbols.shortMonths[month - 1].trimEnd('.'); 5 -> symbols.months[month - 1].take(1); else -> symbols.months[month - 1]
                })
                lower[0] == 'h' -> {
                    val shown = if (twelveHour) (hour % 12).let { if (it == 0) 12 else it } else hour
                    out.append(if (lower.length >= 2) shown.toString().padStart(2, '0') else shown.toString())
                }
                lower[0] == 's' -> out.append(if (lower.length >= 2) second.toString().padStart(2, '0') else second.toString())
                lower == "am/pm" -> out.append(if (hour < 12) "AM" else "PM")
                lower == "a/p" -> out.append(if (hour < 12) "A" else "P")
                code == "." && fractionDigits > 0 -> out.append(DecimalFormatSymbols.getInstance(locale).decimalSeparator)
                    .append(fraction.toString().padStart(fractionDigits, '0'))
                code == "0" && fractionDigits > 0 -> Unit
                else -> out.append(code)
            }
        }
        return out.toString()
    }

    /** Year, month and day of an Excel serial day: 1900 counts its false 29 February; 1904 starts at day 0. */
    private fun civil(serial: Long, date1904: Boolean): Triple<Int, Int, Int> {
        if (!date1904 && serial == 60L) return Triple(1900, 2, 29)
        if (!date1904 && serial == 0L) return Triple(1900, 1, 0)
        // Days from 1970-01-01: 1899-12-31 is -25568 for serials before the false leap day, 1899-12-30 after it.
        val epochDay = when {
            date1904 -> serial - 24107
            serial < 60 -> serial - 25568
            else -> serial - 25569
        }
        // Howard Hinnant's civil_from_days.
        val z = epochDay + 719468
        val era = Math.floorDiv(z, 146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
        return Triple(year, month, day)
    }

    /** Calendar.SUNDAY (1) to SATURDAY (7), as DateFormatSymbols indexes its weekdays. */
    private fun weekday(serial: Long, date1904: Boolean): Int {
        val epochDay = if (date1904) serial - 24107 else if (serial < 60) serial - 25568 else serial - 25569
        return (Math.floorMod(epochDay + 4, 7L) + 1).toInt()
    }
}
