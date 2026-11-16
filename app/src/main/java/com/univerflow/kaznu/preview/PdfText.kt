package com.univerflow.kaznu.preview

import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * A PDF's text for the search where PdfRenderer gives none (before Android 15). Objects are found by scanning the file,
 * so damaged cross-reference tables do not matter; encrypted files give no text.
 */
internal object PdfText {
    const val FILE_LIMIT = 48 * 1024 * 1024
    private const val STREAM_LIMIT = 32 * 1024 * 1024
    private const val PAGE_LIMIT = 2000
    private const val FORM_DEPTH = 8
    private const val FORM_RUNS = 256
    private const val CMAP_LIMIT = 200_000
    private const val WIDTH_LIMIT = 65536
    // Bytes all stream decoding of one document may produce: pages can share one huge stream, and each
    // stream alone stays under STREAM_LIMIT, so only a document-wide total bounds the work.
    const val DECODE_BUDGET = 256L * 1024 * 1024

    fun isPdf(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        return head.contains("%PDF-")
    }

    fun text(bytes: ByteArray, checkActive: () -> Unit = {}, budget: Long = DECODE_BUDGET): String =
        Document(bytes, checkActive, budget).text()

    // Objects.

    private sealed interface Value
    private class Number(val value: Double) : Value
    private class Name(val value: String) : Value
    private class Bytes(val value: ByteArray) : Value
    private class Array(val items: List<Value>) : Value
    private class Dict(val entries: Map<String, Value>) : Value { operator fun get(key: String) = entries[key] }
    private class Ref(val number: Int, val generation: Int) : Value
    private class Keyword(val value: String) : Value
    private class Stream(val dict: Dict, val start: Int, val end: Int, val source: ByteArray) : Value
    private object Null : Value

    /** Tokens and objects of the PDF syntax over [bytes] from [position]. */
    private class Lexer(val bytes: ByteArray, var position: Int = 0, private val end: Int = bytes.size) {
        val atEnd get() = skipSpace().let { position >= end }

        private fun byte(at: Int) = bytes[at].toInt() and 0xFF

        private fun isSpace(b: Int) = b == 0x20 || b == 0x0A || b == 0x0D || b == 0x09 || b == 0x0C || b == 0x00

        private fun isDelimiter(b: Int) = b == '('.code || b == ')'.code || b == '<'.code || b == '>'.code || b == '['.code ||
            b == ']'.code || b == '{'.code || b == '}'.code || b == '/'.code || b == '%'.code

        fun skipSpace() {
            while (position < end) {
                val b = byte(position)
                if (isSpace(b)) position++
                else if (b == '%'.code) { while (position < end && byte(position) != 0x0A && byte(position) != 0x0D) position++ }
                else return
            }
        }

        /** The next object; arrays and dictionaries nest up to a depth; numbers followed by "G R" become references. */
        fun next(depth: Int = 0): Value? {
            skipSpace()
            if (position >= end) return null
            if (depth > 64) throw UnsupportedDocumentException("PDF nesting")
            val b = byte(position)
            return when {
                b == '/'.code -> name()
                b == '('.code -> literal()
                b == '<'.code && position + 1 < end && byte(position + 1) == '<'.code -> dictionary(depth)
                b == '<'.code -> hexString()
                b == '['.code -> { position++; array(depth) }
                b == ']'.code || b == '>'.code || b == ')'.code || b == '{'.code || b == '}'.code -> { position++; Keyword(b.toChar().toString()) }
                b == '+'.code || b == '-'.code || b == '.'.code || b in '0'.code..'9'.code -> numberOrRef()
                else -> keyword()
            }
        }

        private fun name(): Name {
            position++
            val out = ByteArrayOutputStream()
            while (position < end) {
                val b = byte(position)
                if (isSpace(b) || isDelimiter(b)) break
                if (b == '#'.code && position + 2 < end) {
                    val hex = String(bytes, position + 1, 2, Charsets.ISO_8859_1).toIntOrNull(16)
                    if (hex != null) { out.write(hex); position += 3; continue }
                }
                out.write(b); position++
            }
            return Name(String(out.toByteArray(), Charsets.ISO_8859_1))
        }

        private fun literal(): Bytes {
            position++
            val out = ByteArrayOutputStream()
            var nesting = 1
            while (position < end) {
                val b = byte(position++)
                when (b) {
                    '('.code -> { nesting++; out.write(b) }
                    ')'.code -> { if (--nesting == 0) break; out.write(b) }
                    '\\'.code -> {
                        if (position >= end) break
                        val e = byte(position++)
                        when (e) {
                            'n'.code -> out.write(0x0A); 'r'.code -> out.write(0x0D); 't'.code -> out.write(0x09)
                            'b'.code -> out.write(0x08); 'f'.code -> out.write(0x0C)
                            0x0D -> { if (position < end && byte(position) == 0x0A) position++ }
                            0x0A -> Unit
                            in '0'.code..'7'.code -> {
                                var value = e - '0'.code
                                repeat(2) { if (position < end && byte(position) in '0'.code..'7'.code) value = value * 8 + (byte(position++) - '0'.code) }
                                out.write(value and 0xFF)
                            }
                            else -> out.write(e)
                        }
                    }
                    else -> out.write(b)
                }
            }
            return Bytes(out.toByteArray())
        }

        private fun hexString(): Bytes {
            position++
            val out = ByteArrayOutputStream()
            var high = -1
            while (position < end) {
                val b = byte(position++)
                if (b == '>'.code) break
                val digit = Character.digit(b, 16)
                if (digit < 0) continue
                if (high < 0) high = digit else { out.write(high * 16 + digit); high = -1 }
            }
            if (high >= 0) out.write(high * 16)
            return Bytes(out.toByteArray())
        }

        private fun array(depth: Int): Array {
            val items = mutableListOf<Value>()
            while (true) {
                val item = next(depth + 1) ?: break
                if (item is Keyword && item.value == "]") break
                items += item
                if (items.size > 1_000_000) throw PreviewLimitException()
            }
            return Array(items)
        }

        private fun dictionary(depth: Int): Dict {
            position += 2
            val entries = mutableMapOf<String, Value>()
            while (true) {
                skipSpace()
                if (position + 1 < end && byte(position) == '>'.code && byte(position + 1) == '>'.code) { position += 2; break }
                val key = next(depth + 1) ?: break
                if (key !is Name) { if (key is Keyword && key.value == ">") continue; continue }
                entries[key.value] = next(depth + 1) ?: break
            }
            return Dict(entries)
        }

        private fun numberOrRef(): Value {
            val number = readNumber()
            // "12 0 R" is a reference; look ahead without consuming anything else.
            if (number % 1.0 == 0.0 && number >= 0) {
                val saved = position
                skipSpace()
                val start = position
                while (position < end && byte(position) in '0'.code..'9'.code) position++
                if (position > start) {
                    val generation = String(bytes, start, position - start, Charsets.ISO_8859_1).toIntOrNull()
                    skipSpace()
                    if (generation != null && position < end && byte(position) == 'R'.code &&
                        (position + 1 >= end || isSpace(byte(position + 1)) || isDelimiter(byte(position + 1)))) {
                        position++
                        return Ref(number.toInt(), generation)
                    }
                }
                position = saved
            }
            return Number(number)
        }

        private fun readNumber(): Double {
            val start = position
            position++
            while (position < end && byte(position).let { it in '0'.code..'9'.code || it == '.'.code }) position++
            return String(bytes, start, position - start, Charsets.ISO_8859_1).toDoubleOrNull() ?: 0.0
        }

        private fun keyword(): Keyword {
            val start = position
            while (position < end && !isSpace(byte(position)) && !isDelimiter(byte(position))) position++
            if (position == start) position++
            return Keyword(String(bytes, start, position - start, Charsets.ISO_8859_1))
        }
    }

    // Document.

    private class Document(private val bytes: ByteArray, private val checkActive: () -> Unit, private val budget: Long) {
        /** Bytes decoded so far; past [budget] the remaining pages are not read and the text found so far is kept. */
        private var decoded = 0L
        private var exhausted = false
        private val objects = HashMap<Int, Value>()
        private val output = StringBuilder()
        private val decoders = HashMap<Any, FontDecoder>()

        init {
            scan()
            unpackObjectStreams()
        }

        fun text(): String {
            if (encrypted()) return ""
            val pages = pages()
            pages.forEachIndexed { index, page ->
                if (index % 4 == 0) checkActive()
                if (output.length >= TextExtract.LIMIT || exhausted) return@forEachIndexed
                // A damaged page is skipped; cancellation still ends the whole reading.
                try { page(page) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
            }
            // Blanks before line breaks are cut line by line: a regex would backtrack over a long run of spaces at every position.
            return output.toString().take(TextExtract.LIMIT).split('\n').joinToString("\n") { it.trimEnd(' ', '\t') }
                .replace(Regex("\n{3,}"), "\n\n").trim()
        }

        /**
         * Every "N G obj" in the file; a later definition (an incremental update) replaces an earlier one. An object is read
         * up to the next "endobj" at most, and the search goes on after all it read: a string that never closes is read
         * once, not again from every "obj" inside it.
         */
        private fun scan() {
            var at = 0
            // The first "endobj" after the object being read, found once for all objects before it; the end without one.
            var endobj = -1
            while (true) {
                at = indexOf(OBJ, at)
                if (at < 0) break
                val header = Regex("(\\d+)\\s+(\\d+)\\s+$").find(String(bytes, maxOf(0, at - 24), minOf(24, at), Charsets.ISO_8859_1))
                val after = at + OBJ.size
                val boundary = after >= bytes.size || (bytes[after].toInt() and 0xFF).let { it <= 0x20 || it == '<'.code || it == '['.code || it == '/'.code }
                at = after
                if (header != null && boundary) {
                    val number = header.groupValues[1].toIntOrNull()
                    if (number != null) {
                        if (endobj < after) endobj = indexOf(ENDOBJ, after).takeIf { it >= 0 } ?: bytes.size
                        val lexer = Lexer(bytes, after, endobj)
                        val value = runCatching { parseObject(lexer) }.getOrNull()
                        at = maxOf(at, lexer.position)
                        if (value != null) {
                            objects[number] = value
                            // Stream data is not searched for objects: compressed bytes can look like "1 0 obj".
                            if (value is Stream) at = maxOf(at, value.end)
                        }
                    }
                }
                if (++scanned % 1024 == 0) checkActive()
            }
        }

        private var scanned = 0

        /** The object at the [lexer]'s position; the lexer is left after all it read, the token after a dictionary included. */
        private fun parseObject(lexer: Lexer): Value {
            val value = lexer.next() ?: return Null
            if (value !is Dict) return value
            val keyword = lexer.next()
            if (keyword !is Keyword || keyword.value != "stream") return value
            var data = lexer.position
            if (data < bytes.size && bytes[data] == 0x0D.toByte()) data++
            if (data < bytes.size && bytes[data] == 0x0A.toByte()) data++
            val declared = (value["Length"] as? Number)?.value?.toInt()
            val end = declared?.takeIf { it >= 0 && data + it <= bytes.size && endsStream(data + it) }?.let { data + it }
                ?: indexOf(ENDSTREAM, data).takeIf { it >= 0 }?.let { trimEol(data, it) }
                ?: bytes.size
            return Stream(value, data, end, bytes)
        }

        private fun endsStream(at: Int): Boolean {
            var position = at
            while (position < bytes.size && (bytes[position].toInt() and 0xFF) <= 0x20) position++
            return startsWith(ENDSTREAM, position)
        }

        private fun trimEol(start: Int, end: Int): Int {
            var position = end
            if (position > start && bytes[position - 1] == 0x0A.toByte()) position--
            if (position > start && bytes[position - 1] == 0x0D.toByte()) position--
            return position
        }

        private fun startsWith(pattern: ByteArray, at: Int) = at + pattern.size <= bytes.size && pattern.indices.all { bytes[at + it] == pattern[it] }

        private fun indexOf(pattern: ByteArray, from: Int): Int {
            var at = from
            val last = bytes.size - pattern.size
            while (at <= last) {
                if (bytes[at] == pattern[0] && startsWith(pattern, at)) return at
                at++
            }
            return -1
        }

        /** Objects packed in object streams (PDF 1.5+), unless the file defines them directly. */
        private fun unpackObjectStreams() {
            objects.values.filterIsInstance<Stream>().filter { (it.dict["Type"] as? Name)?.value == "ObjStm" }.forEach { stream ->
                runCatching {
                    val data = decode(stream) ?: return@runCatching
                    val count = (resolve(stream.dict["N"]) as? Number)?.value?.toInt() ?: return@runCatching
                    val first = (resolve(stream.dict["First"]) as? Number)?.value?.toInt() ?: return@runCatching
                    val header = Lexer(data, 0, minOf(first, data.size))
                    repeat(count.coerceAtMost(100_000)) {
                        val number = (header.next() as? Number)?.value?.toInt() ?: return@runCatching
                        val offset = (header.next() as? Number)?.value?.toInt() ?: return@runCatching
                        if (number !in objects && first + offset < data.size) {
                            Lexer(data, first + offset).next()?.let { objects[number] = it }
                        }
                    }
                }
                checkActive()
            }
        }

        fun resolve(value: Value?, depth: Int = 0): Value? = if (value is Ref && depth < 32) resolve(objects[value.number], depth + 1) else value

        private fun dict(value: Value?): Dict? = when (val resolved = resolve(value)) { is Dict -> resolved; is Stream -> resolved.dict; else -> null }

        private fun encrypted() = objects.values.any { value ->
            val dict = (value as? Dict) ?: (value as? Stream)?.dict
            dict != null && dict["Encrypt"] != null && (dict["Root"] != null || (dict["Type"] as? Name)?.value == "XRef")
        } || indexOf("/Encrypt".toByteArray(Charsets.ISO_8859_1), maxOf(0, bytes.size - 4096)) >= 0

        /** Page dictionaries in page-tree order, each with its inherited resources. */
        private fun pages(): List<Pair<Dict, Dict?>> {
            val result = mutableListOf<Pair<Dict, Dict?>>()
            // By object number: a damaged or hostile tree that lists a node twice (or its own ancestor) is walked once.
            val seen = HashSet<Int>()
            fun walk(node: Value?, resources: Dict?, depth: Int) {
                if (depth > 64 || result.size >= PAGE_LIMIT) return
                if (node is Ref && !seen.add(node.number)) return
                val dict = dict(node) ?: return
                val own = dict(dict["Resources"]) ?: resources
                val kids = resolve(dict["Kids"]) as? Array
                if (kids != null) kids.items.forEach { walk(it, own, depth + 1) }
                else if ((dict["Type"] as? Name)?.value != "Pages") result += dict to own
            }
            val root = objects.values.mapNotNull { (it as? Dict) }.lastOrNull { (it["Type"] as? Name)?.value == "Catalog" }
            walk(root?.get("Pages"), null, 0)
            if (result.isNotEmpty()) return result
            // No usable page tree: every page object in file order.
            return objects.entries.sortedBy { it.key }.mapNotNull { (_, value) ->
                (value as? Dict)?.takeIf { (it["Type"] as? Name)?.value == "Page" }?.let { it to dict(it["Resources"]) }
            }.take(PAGE_LIMIT)
        }

        private fun page(page: Pair<Dict, Dict?>) {
            val contents = when (val value = resolve(page.first["Contents"])) {
                is Stream -> listOf(value)
                is Array -> value.items.mapNotNull { resolve(it) as? Stream }
                else -> emptyList()
            }
            formRuns = 0
            Content(this, page.second, 0).run(program(contents))
        }

        /**
         * The page's content streams as one program. Together they stay under STREAM_LIMIT: a stream that would pass it
         * ends the page, so a small file listing one large stream many times cannot fill the heap with a single page.
         */
        private fun program(contents: List<Stream>): ByteArray {
            val joined = ByteArrayOutputStream()
            for (stream in contents) {
                val data = decode(stream) ?: continue
                if (joined.size() + data.size + 1 > STREAM_LIMIT) break
                joined.write(data); joined.write(0x0A)
            }
            return joined.toByteArray()
        }

        fun font(resources: Dict?, name: String): FontDecoder? {
            val fonts = dict(resources?.get("Font")) ?: return null
            val reference = fonts[name] ?: return null
            val key: Any = (reference as? Ref)?.number ?: reference
            return decoders.getOrPut(key) { FontDecoder.of(this, dict(reference)) }
        }

        /** Form XObjects run on the current page: a form drawn again and again (or inside itself) has a budget. */
        var formRuns = 0

        /** Decoded programs of the forms now running, one inside another: together they stay under STREAM_LIMIT. */
        private var heldFormBytes = 0L
        fun holdForm(size: Int): Boolean {
            if (heldFormBytes + size > STREAM_LIMIT) return false
            heldFormBytes += size
            return true
        }
        fun releaseForm(size: Int) { heldFormBytes -= size }

        fun form(resources: Dict?, name: String): Stream? {
            if (++formRuns > FORM_RUNS) return null
            val forms = dict(resources?.get("XObject")) ?: return null
            return (resolve(forms[name]) as? Stream)?.takeIf { (it.dict["Subtype"] as? Name)?.value == "Form" }
        }

        fun formResources(form: Stream, fallback: Dict?) = dict(form.dict["Resources"]) ?: fallback

        fun append(text: String) { if (output.length < TextExtract.LIMIT) output.append(text) }

        fun appendSeparator(separator: Char) {
            if (output.isNotEmpty() && output.last() != '\n' && output.last() != separator) output.append(separator)
        }

        /** Applies the stream's filters; null when one is not supported (images and the like). */
        fun decode(stream: Stream): ByteArray? {
            var data = spend(stream.source.copyOfRange(stream.start, stream.end.coerceAtMost(stream.source.size).coerceAtLeast(stream.start)))
            val filters = when (val filter = resolve(stream.dict["Filter"])) {
                is Name -> listOf(filter.value)
                is Array -> filter.items.mapNotNull { (resolve(it) as? Name)?.value }
                else -> emptyList()
            }
            val parameters = when (val value = resolve(stream.dict["DecodeParms"])) {
                is Dict -> listOf(value)
                is Array -> value.items.map { dict(it) }
                else -> emptyList()
            }
            filters.forEachIndexed { index, filter ->
                val parms = parameters.getOrNull(index)
                data = try {
                    when (filter) {
                        "FlateDecode", "Fl" -> predict(inflate(data), parms)
                        "LZWDecode", "LZW" -> predict(Filters.lzw(data, (parms?.get("EarlyChange") as? Number)?.value?.toInt() ?: 1, STREAM_LIMIT), parms)
                        "ASCIIHexDecode", "AHx" -> Filters.asciiHex(data)
                        "ASCII85Decode", "A85" -> Filters.ascii85(data, STREAM_LIMIT)
                        "RunLengthDecode", "RL" -> Filters.runLength(data, STREAM_LIMIT)
                        else -> return null
                    }.also { if (it.size > STREAM_LIMIT) throw PreviewLimitException() }
                } catch (exceeded: PreviewLimitException) {
                    // A limit's worth was decoded before the filter stopped: it counts against the budget, so pages
                    // sharing such a stream do not decode it again and again.
                    decoded += STREAM_LIMIT
                    if (decoded > budget) exhausted = true
                    throw exceeded
                }
                spend(data)
            }
            return data
        }

        private fun spend(data: ByteArray): ByteArray {
            decoded += data.size
            if (decoded > budget) { exhausted = true; throw PreviewLimitException() }
            return data
        }

        private fun inflate(data: ByteArray): ByteArray {
            val inflater = Inflater()
            try {
                inflater.setInput(data)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (!inflater.finished()) {
                    val count = try { inflater.inflate(buffer) } catch (_: DataFormatException) { break } // Keep what was readable.
                    if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buffer, 0, count)
                    if (out.size() > STREAM_LIMIT) throw PreviewLimitException()
                }
                return out.toByteArray()
            } finally { inflater.end() }
        }

        private fun predict(data: ByteArray, parms: Dict?): ByteArray {
            val predictor = (parms?.get("Predictor") as? Number)?.value?.toInt() ?: 1
            if (predictor < 10) return data
            val columns = ((parms?.get("Columns") as? Number)?.value?.toInt() ?: 1).coerceIn(1, 1 shl 16)
            val colors = ((parms?.get("Colors") as? Number)?.value?.toInt() ?: 1).coerceIn(1, 32)
            val bits = ((parms?.get("BitsPerComponent") as? Number)?.value?.toInt() ?: 8).coerceIn(1, 16)
            return Filters.png(data, columns, colors, bits)
        }
    }

    // Fonts.

    /**
     * Turns shown strings into text: codes looked up in [map] (with [unicodeCodes], an unmapped code is UTF-16).
     * [widths] are glyph widths in thousandths of an em, used to tell word gaps from kerning.
     */
    private class FontDecoder(
        private val map: Map<Int, String>, private val lengths: List<Int>, private val unicodeCodes: Boolean = false,
        private val widths: Map<Int, Double> = emptyMap(), private val defaultWidth: Double = 500.0,
    ) {
        class Shown(val text: String, val width: Double)

        fun show(bytes: ByteArray): Shown {
            val out = StringBuilder()
            var width = 0.0
            var at = 0
            while (at < bytes.size) {
                var consumed = 0
                for (length in lengths) {
                    if (at + length > bytes.size) break
                    var code = 0
                    for (index in 0 until length) code = (code shl 8) or (bytes[at + index].toInt() and 0xFF)
                    val text = map[code]
                    if (text != null || length == lengths.last()) {
                        if (text != null) out.append(text) else if (unicodeCodes) out.append(code.toChar())
                        width += widths[code] ?: defaultWidth
                        consumed = length
                        break
                    }
                }
                at += consumed.coerceAtLeast(1)
            }
            return Shown(out.toString(), width)
        }

        companion object {
            val LATIN = FontDecoder(Encodings.WIN_ANSI, listOf(1))

            fun of(document: Document, font: Dict?): FontDecoder {
                if (font == null) return LATIN
                val composite = (font["Subtype"] as? Name)?.value == "Type0"
                val cmap = (document.resolve(font["ToUnicode"]) as? Stream)
                    ?.let { stream -> runCatching { document.decode(stream)?.let(CMap::parse) }.getOrNull() }
                    ?.takeIf { it.map.isNotEmpty() }
                val (widths, defaultWidth) = runCatching { if (composite) compositeWidths(document, font) else simpleWidths(document, font) }
                    .getOrDefault(emptyMap<Int, Double>() to 500.0)
                return when {
                    cmap != null && composite -> FontDecoder(cmap.map, cmap.lengths.ifEmpty { setOf(2) }.sorted(), widths = widths, defaultWidth = defaultWidth)
                    // A simple font's ToUnicode may omit codes its encoding covers.
                    cmap != null -> FontDecoder(simpleEncoding(document, font) + cmap.map, listOf(1), widths = widths, defaultWidth = defaultWidth)
                    // Without ToUnicode a composite font is readable only through a Unicode CMap.
                    composite -> FontDecoder(emptyMap(), listOf(2), unicodeCodes = (font["Encoding"] as? Name)?.value.orEmpty().let { "UCS2" in it || "UTF16" in it },
                        widths = widths, defaultWidth = defaultWidth)
                    else -> FontDecoder(simpleEncoding(document, font), listOf(1), widths = widths, defaultWidth = defaultWidth)
                }
            }

            private fun number(document: Document, value: Value?) = (document.resolve(value) as? Number)?.value

            /** FirstChar and Widths, or the descriptor's MissingWidth. */
            private fun simpleWidths(document: Document, font: Dict): Pair<Map<Int, Double>, Double> {
                val first = number(document, font["FirstChar"])?.toInt() ?: 0
                val list = (document.resolve(font["Widths"]) as? Array)?.items.orEmpty()
                val missing = (document.resolve(font["FontDescriptor"]) as? Dict)?.let { number(document, it["MissingWidth"]) }
                return list.withIndex().associate { (index, value) -> first + index to (number(document, value) ?: 0.0) } to (missing?.takeIf { it > 0 } ?: 500.0)
            }

            /** A CID font's W array ("c [w …]" or "first last w") and its DW default. */
            private fun compositeWidths(document: Document, font: Dict): Pair<Map<Int, Double>, Double> {
                val descendant = (document.resolve(font["DescendantFonts"]) as? Array)?.items?.firstOrNull()?.let { document.resolve(it) as? Dict }
                    ?: return emptyMap<Int, Double>() to 1000.0
                val result = HashMap<Int, Double>()
                val items = (document.resolve(descendant["W"]) as? Array)?.items.orEmpty().map { document.resolve(it) }
                var index = 0
                // Codes written, repeats included: "0 65534 500" over and over keeps the map small, not the work.
                var writes = 0
                while (index + 1 < items.size && writes < WIDTH_LIMIT) {
                    val start = (items[index] as? Number)?.value?.toInt() ?: break
                    when (val next = items[index + 1]) {
                        is Array -> {
                            val count = minOf(next.items.size, WIDTH_LIMIT - writes)
                            for (offset in 0 until count) number(document, next.items[offset])?.let { result[start + offset] = it }
                            writes += count
                            index += 2
                        }
                        is Number -> {
                            val width = (items.getOrNull(index + 2) as? Number)?.value ?: break
                            val last = minOf(next.value.toInt(), start + 65535, start + (WIDTH_LIMIT - 1 - writes))
                            for (code in start..last) result[code] = width
                            if (last >= start) writes += last - start + 1
                            index += 3
                        }
                        else -> break
                    }
                }
                return result to (number(document, descendant["DW"]) ?: 1000.0)
            }

            private fun simpleEncoding(document: Document, font: Dict): Map<Int, String> {
                val base = mutableMapOf<Int, String>()
                when (val encoding = document.resolve(font["Encoding"])) {
                    is Name -> base.putAll(Encodings.named(encoding.value))
                    is Dict -> {
                        base.putAll(Encodings.named((encoding["BaseEncoding"] as? Name)?.value ?: "WinAnsiEncoding"))
                        (document.resolve(encoding["Differences"]) as? Array)?.let { differences ->
                            var code = 0
                            differences.items.forEach { item ->
                                when (item) {
                                    is Number -> code = item.value.toInt()
                                    is Name -> { Encodings.glyph(item.value)?.let { base[code] = it }; code++ }
                                    else -> Unit
                                }
                            }
                        }
                    }
                    else -> base.putAll(Encodings.WIN_ANSI)
                }
                return base
            }
        }
    }

    /** ToUnicode CMaps: code space lengths, bfchar and bfrange entries. */
    private object CMap {
        class Result(val map: Map<Int, String>, val lengths: Set<Int>)

        fun parse(data: ByteArray): Result {
            val lexer = Lexer(data)
            val map = HashMap<Int, String>()
            val lengths = sortedSetOf<Int>()
            val operands = mutableListOf<Value>()
            // Entries written, repeats included: the same range written again and again keeps the map small, not the work.
            var writes = 0
            while (!lexer.atEnd && writes < CMAP_LIMIT) {
                val token = lexer.next() ?: break
                if (token !is Keyword) { operands += token; if (operands.size > 300_000) operands.clear(); continue }
                when (token.value) {
                    "endcodespacerange" -> operands.chunked(2).forEach { pair -> (pair.firstOrNull() as? Bytes)?.let { lengths += it.value.size.coerceIn(1, 4) } }
                    "endbfchar" -> operands.chunked(2).forEach { pair ->
                        if (++writes > CMAP_LIMIT) return Result(map, lengths)
                        val source = pair.getOrNull(0) as? Bytes
                        val target = pair.getOrNull(1)
                        if (source != null && source.value.size in 1..4) unicode(target)?.let { map[code(source.value)] = it }
                    }
                    "endbfrange" -> operands.chunked(3).forEach { triple ->
                        val low = (triple.getOrNull(0) as? Bytes)?.value ?: return@forEach
                        val high = (triple.getOrNull(1) as? Bytes)?.value ?: return@forEach
                        if (low.size !in 1..4) return@forEach
                        val from = code(low)
                        val to = code(high)
                        if (to < from || to - from > 65535) return@forEach
                        when (val target = triple.getOrNull(2)) {
                            is Bytes -> {
                                val start = target.value
                                for (offset in 0..(to - from)) {
                                    if (++writes > CMAP_LIMIT) return Result(map, lengths)
                                    val shifted = start.copyOf()
                                    if (shifted.isNotEmpty()) {
                                        val last = shifted.size - 1
                                        val value = (shifted[last].toInt() and 0xFF) + offset
                                        shifted[last] = value.toByte()
                                        if (last > 0) shifted[last - 1] = ((shifted[last - 1].toInt() and 0xFF) + (value shr 8)).toByte()
                                    }
                                    map[from + offset] = String(shifted, Charsets.UTF_16BE)
                                }
                            }
                            is Array -> target.items.forEachIndexed { offset, item ->
                                if (++writes > CMAP_LIMIT) return Result(map, lengths)
                                unicode(item)?.let { map[from + offset] = it }
                            }
                            else -> Unit
                        }
                    }
                }
                operands.clear()
            }
            return Result(map, lengths)
        }

        private fun code(bytes: ByteArray) = bytes.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

        private fun unicode(value: Value?): String? = when (value) {
            is Bytes -> if (value.value.size % 2 == 0) String(value.value, Charsets.UTF_16BE) else String(value.value, Charsets.ISO_8859_1)
            is Name -> Encodings.glyph(value.value)
            else -> null
        }
    }

    // Content streams.

    /**
     * Runs a content stream's text operators following the pen: another baseline starts a new line, and a gap wider
     * than a fraction of the font size is a space, so kerning and mid-word splits stay together.
     */
    private class Content(private val document: Document, private val resources: Dict?, private val depth: Int) {
        private var font = FontDecoder.LATIN
        private var size = 12.0
        private var scale = 1.0
        private var leading = 0.0
        private var lineX = 0.0
        private var lineY = 0.0
        private var x = 0.0
        private var y = 0.0
        private var endX = Double.NaN
        private var endY = Double.NaN

        fun run(data: ByteArray) {
            val lexer = Lexer(data)
            val operands = mutableListOf<Value>()
            while (true) {
                val token = runCatching { lexer.next() }.getOrNull() ?: break
                if (token !is Keyword || token.value in setOf("]", ">", ")", "{", "}")) {
                    operands += token
                    if (operands.size > 4096) operands.clear()
                    continue
                }
                operator(token.value, operands, lexer, data)
                operands.clear()
            }
        }

        private fun number(operands: List<Value>, index: Int) = (operands.getOrNull(index) as? Number)?.value ?: 0.0

        private fun operator(name: String, operands: List<Value>, lexer: Lexer, data: ByteArray) {
            when (name) {
                // The text matrix starts as the identity in every text object.
                "BT" -> { scale = 1.0; lineX = 0.0; lineY = 0.0; x = 0.0; y = 0.0 }
                "Tf" -> {
                    font = (operands.getOrNull(0) as? Name)?.let { document.font(resources, it.value) } ?: FontDecoder.LATIN
                    size = kotlin.math.abs(number(operands, 1)).takeIf { it > 0 } ?: 12.0
                }
                "TL" -> leading = number(operands, 0)
                "Tm" -> {
                    scale = kotlin.math.hypot(number(operands, 0), number(operands, 1)).takeIf { it > 0 } ?: 1.0
                    lineX = number(operands, 4); lineY = number(operands, 5); x = lineX; y = lineY
                }
                "Td" -> moveLine(number(operands, 0), number(operands, 1))
                "TD" -> { leading = -number(operands, 1); moveLine(number(operands, 0), number(operands, 1)) }
                "T*" -> moveLine(0.0, -leading)
                "Tj" -> show(operands.lastOrNull())
                "'", "\"" -> { moveLine(0.0, -leading); show(operands.lastOrNull()) }
                "TJ" -> (operands.lastOrNull() as? Array)?.items?.forEach { item ->
                    when (item) {
                        is Bytes -> show(item)
                        is Number -> x -= item.value / 1000 * size * scale
                        else -> Unit
                    }
                }
                "Do" -> (operands.getOrNull(0) as? Name)?.let { form(it.value) }
                "BI" -> skipInlineImage(lexer, data)
            }
        }

        private fun moveLine(tx: Double, ty: Double) {
            lineX += tx * scale; lineY += ty * scale
            x = lineX; y = lineY
        }

        private fun show(value: Value?) {
            val bytes = (value as? Bytes)?.value ?: return
            val shown = font.show(bytes)
            val em = size * scale
            if (!endY.isNaN()) {
                val gap = x - endX
                when {
                    kotlin.math.abs(y - endY) > maxOf(em * 0.5, 1.0) -> document.appendSeparator('\n')
                    gap > em * 0.15 || gap < -em * 2 -> document.appendSeparator(' ')
                }
            }
            document.append(shown.text.filter { it >= ' ' || it == '\t' })
            x += shown.width / 1000 * em
            endX = x; endY = y
        }

        private fun form(name: String) {
            if (depth >= FORM_DEPTH) return
            val stream = document.form(resources, name) ?: return
            val data = document.decode(stream) ?: return
            if (!document.holdForm(data.size)) return
            try {
                document.appendSeparator(' ')
                Content(document, document.formResources(stream, resources), depth + 1).run(data)
            } finally { document.releaseForm(data.size) }
        }

        /** BI … ID <binary> EI: the image data is skipped so it is not read as operators. */
        private fun skipInlineImage(lexer: Lexer, data: ByteArray) {
            var at = lexer.position
            while (at + 1 < data.size && !(data[at] == 'I'.code.toByte() && data[at + 1] == 'D'.code.toByte())) at++
            at += 2
            while (at + 2 < data.size) {
                if (data[at] == 'E'.code.toByte() && data[at + 1] == 'I'.code.toByte() &&
                    (data[at - 1].toInt() and 0xFF) <= 0x20 && (at + 2 >= data.size || (data[at + 2].toInt() and 0xFF) <= 0x20)) { at += 2; break }
                at++
            }
            lexer.position = at.coerceAtMost(data.size)
        }
    }

    private val OBJ = "obj".toByteArray(Charsets.ISO_8859_1)
    private val ENDSTREAM = "endstream".toByteArray(Charsets.ISO_8859_1)
    private val ENDOBJ = "endobj".toByteArray(Charsets.ISO_8859_1)
}

/**
 * Stream filters of PDF besides Flate: LZW, ASCII hex and base-85, run-length, and PNG predictors. The expanding ones stop
 * at their `limit` of output bytes: a few kilobytes of run-length or LZW data can stand for gigabytes.
 */
internal object Filters {
    /** Throws before [count] more bytes take [out] past [limit]. */
    private fun room(out: ByteArrayOutputStream, count: Int, limit: Int) {
        if (count > limit - out.size()) throw PreviewLimitException()
    }

    fun asciiHex(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var high = -1
        for (byte in data) {
            if (byte == '>'.code.toByte()) break
            val digit = Character.digit(byte.toInt(), 16)
            if (digit < 0) continue
            if (high < 0) high = digit else { out.write(high * 16 + digit); high = -1 }
        }
        if (high >= 0) out.write(high * 16)
        return out.toByteArray()
    }

    fun ascii85(data: ByteArray, limit: Int = Int.MAX_VALUE): ByteArray {
        val out = ByteArrayOutputStream()
        var tuple = 0L
        var count = 0
        for (byte in data) {
            val c = byte.toInt() and 0xFF
            when {
                c == '~'.code -> break
                c == 'z'.code && count == 0 -> { room(out, 4, limit); repeat(4) { out.write(0) } }
                c in '!'.code..'u'.code -> {
                    tuple = tuple * 85 + (c - '!'.code)
                    if (++count == 5) {
                        room(out, 4, limit)
                        for (shift in 24 downTo 0 step 8) out.write(((tuple shr shift) and 0xFF).toInt())
                        tuple = 0; count = 0
                    }
                }
            }
        }
        if (count > 1) {
            room(out, count - 1, limit)
            repeat(5 - count) { tuple = tuple * 85 + 84 }
            for (index in 0 until count - 1) out.write(((tuple shr (24 - index * 8)) and 0xFF).toInt())
        }
        return out.toByteArray()
    }

    fun runLength(data: ByteArray, limit: Int = Int.MAX_VALUE): ByteArray {
        val out = ByteArrayOutputStream()
        var at = 0
        while (at < data.size) {
            val length = data[at++].toInt() and 0xFF
            when {
                length == 128 -> break
                length < 128 -> { val count = minOf(length + 1, data.size - at); room(out, count, limit); out.write(data, at, count); at += count }
                at < data.size -> { room(out, 257 - length, limit); repeat(257 - length) { out.write(data[at].toInt()) }; at++ }
            }
        }
        return out.toByteArray()
    }

    fun lzw(data: ByteArray, earlyChange: Int, limit: Int = Int.MAX_VALUE): ByteArray {
        val out = ByteArrayOutputStream()
        val table = ArrayList<ByteArray>(4096)
        for (index in 0 until 256) table += byteArrayOf(index.toByte())
        table += ByteArray(0); table += ByteArray(0)
        // The 258 first entries never change: a clear code only drops the learned ones, so a stream of clear codes costs nothing.
        fun reset() { if (table.size > 258) table.subList(258, table.size).clear() }
        var bits = 9
        var buffer = 0L
        var buffered = 0
        var previous: ByteArray? = null
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toLong() and 0xFF)
            buffered += 8
            while (buffered >= bits) {
                val code = ((buffer shr (buffered - bits)) and ((1L shl bits) - 1)).toInt()
                buffered -= bits
                when {
                    code == 256 -> { reset(); bits = 9; previous = null }
                    code == 257 -> return out.toByteArray()
                    else -> {
                        val entry = when {
                            code < table.size -> table[code]
                            previous != null -> previous + previous[0]
                            else -> return out.toByteArray()
                        }
                        room(out, entry.size, limit)
                        out.write(entry)
                        if (previous != null && table.size < 4096) table += previous + entry[0]
                        previous = entry
                        bits = when { table.size + earlyChange >= 2048 -> 12; table.size + earlyChange >= 1024 -> 11; table.size + earlyChange >= 512 -> 10; else -> 9 }
                    }
                }
            }
        }
        return out.toByteArray()
    }

    /** Undoes PNG row predictors (Predictor ≥ 10): each row starts with its filter type. */
    fun png(data: ByteArray, columns: Int, colors: Int, bits: Int): ByteArray {
        val pixel = maxOf(1, colors * bits / 8)
        val row = (columns * colors * bits + 7) / 8
        val out = ByteArrayOutputStream()
        var previous = ByteArray(row)
        var at = 0
        while (at < data.size) {
            val type = data[at++].toInt() and 0xFF
            val current = ByteArray(row)
            val count = minOf(row, data.size - at)
            System.arraycopy(data, at, current, 0, count)
            at += row
            for (index in 0 until row) {
                val left = if (index >= pixel) current[index - pixel].toInt() and 0xFF else 0
                val up = previous[index].toInt() and 0xFF
                val upLeft = if (index >= pixel) previous[index - pixel].toInt() and 0xFF else 0
                val raw = current[index].toInt() and 0xFF
                current[index] = when (type) {
                    1 -> raw + left
                    2 -> raw + up
                    3 -> raw + (left + up) / 2
                    4 -> {
                        val p = left + up - upLeft
                        val pa = kotlin.math.abs(p - left); val pb = kotlin.math.abs(p - up); val pc = kotlin.math.abs(p - upLeft)
                        raw + if (pa <= pb && pa <= pc) left else if (pb <= pc) up else upLeft
                    }
                    else -> raw
                }.toByte()
            }
            out.write(current)
            previous = current
        }
        return out.toByteArray()
    }
}
