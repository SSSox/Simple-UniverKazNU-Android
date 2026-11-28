package com.univerflow.kaznu.domain

/** The Downloads list as shown: type and course filters, the search (by name and course, and inside files), the order. */
object DownloadList {
    enum class Order { DATE, NAME, SIZE }

    /** When a file arrived, as the list groups it. */
    enum class Period { TODAY, YESTERDAY, WEEK, MONTH, EARLIER }

    /** The group of a file that arrived at [at]; whole calendar days in [zone], as a person counts them. */
    fun period(at: Long, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Period {
        val days = day(now, zone) - day(at, zone)
        return when {
            days <= 0 -> Period.TODAY
            days == 1L -> Period.YESTERDAY
            days < 7 -> Period.WEEK
            days < 31 -> Period.MONTH
            else -> Period.EARLIER
        }
    }

    /** The local calendar day of [time] in [zone], counted from the epoch: the day the date groups are drawn for. */
    fun day(time: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long =
        Math.floorDiv(time + zone.getOffset(time), 86_400_000L)

    /**
     * Everything the drawn list depends on. The date groups (today, yesterday, ...) follow the local [day], so the same
     * records and filters drawn on another day are drawn again.
     */
    internal data class RenderKey(
        val records: List<DownloadRecord>,
        val query: String,
        val filter: FileKind?,
        val subject: String?,
        val textSearch: Boolean,
        val sort: Int,
        val textsVersion: Long,
        val day: Long,
    )

    /**
     * How many files each type chip would show, with the course and the search applied: the same matching as [view],
     * files found by their text included, so a chip never says 0 over a card it would show.
     */
    fun kindCounts(records: List<DownloadRecord>, query: String, subject: String?, texts: Map<String, FileText>? = null,
        scan: TextScan? = null): Map<FileKind, Int> =
        select(records, query, null, subject, texts, scan).first.groupingBy { FileKinds.filterKind(it.kind) }.eachCount()

    /**
     * Text read from a file. The folded copy used for matching is built on the first comparison and then kept:
     * most files of a large library are never looked inside, so they cost one string, not two.
     */
    class FileText(val text: String) {
        /** Lightweight identity token so [TextScan] can memoize evaluations without retaining evicted texts. */
        internal val token: Long = nextToken.incrementAndGet()
        val folded: String by lazy(LazyThreadSafetyMode.PUBLICATION) { TextSearch.fold(text) }

        companion object {
            private val nextToken = java.util.concurrent.atomic.AtomicLong()
        }
    }

    /**
     * The texts read for the search, bounded by count and total characters; the least recently used leaves first
     * and is read again from the cache folder when needed.
     */
    class TextCache(private val maxFiles: Int = MAX_FILES, private val maxCharacters: Int = MAX_CHARACTERS) {
        // Access-ordered, so iteration starts at the least recently used entry.
        private val entries = LinkedHashMap<String, FileText>(16, 0.75f, true)
        private var characters = 0

        val size get() = entries.size

        operator fun contains(id: String) = entries.containsKey(id)

        /** The text of [id], or null when it was read and has left the cache since. */
        operator fun get(id: String): FileText? = entries[id]

        /** Stores [value] and returns it; the least recently used texts are dropped to stay within both limits. */
        fun put(id: String, value: FileText): FileText {
            entries.put(id, value)?.let { characters -= it.text.length }
            characters += value.text.length
            while (entries.size > maxFiles || characters > maxCharacters) {
                val eldest = entries.entries.iterator()
                if (!eldest.hasNext()) break
                characters -= eldest.next().value.text.length
                eldest.remove()
            }
            return value
        }

        fun remove(id: String) { entries.remove(id)?.let { characters -= it.text.length } }

        /** Makes room for matches: a text that found nothing leaves first once the cache is full. */
        fun yieldTo(id: String) { if (entries.size >= maxFiles) remove(id) }

        fun clear() { entries.clear(); characters = 0 }

        /** The texts as [view] expects them, without the bookkeeping. */
        fun snapshot(): Map<String, FileText> = entries

        /** An independent copy of the cached texts, safe to read on a background dispatcher. */
        internal fun copy(): Map<String, FileText> = HashMap(entries)
    }

    /** Immutable snapshot of a [TextScan]'s matches and evaluated text tokens, safe to read off the main thread. */
    internal class ScanSnapshot(
        val found: Map<String, TextSearch.Snippet>,
        val checked: Map<String, Long>,
    ) {
        companion object { val EMPTY = ScanSnapshot(emptyMap(), emptyMap()) }
    }

    /**
     * Which files one query still has to read. The bounded cache forgets texts, so "not cached" cannot mean "not read":
     * a library larger than the cache would otherwise be read over and over for as long as the query stands.
     */
    class TextScan(val query: String) {
        private val matches = HashMap<String, TextSearch.Snippet>()
        /** Lightweight [FileText.token] of every text evaluated for [query]; retains no [FileText] references on eviction. */
        private val checked = HashMap<String, Long>()

        /**
         * What this query found inside files, kept apart from the bounded cache: a matching text that the cache drops
         * to make room is not read again for the same query, and its file must not leave the results.
         */
        fun found(query: String): Map<String, TextSearch.Snippet> = if (query == this.query) matches else emptyMap()

        internal fun snapshot(query: String): ScanSnapshot =
            if (query == this.query) ScanSnapshot(HashMap(matches), HashMap(checked)) else ScanSnapshot.EMPTY

        fun pending(candidates: List<DownloadRecord>, cached: TextCache): List<DownloadRecord> =
            candidates.filter { it.id !in checked && it.id !in cached }

        /** Records a completed read and returns whether the text matched this query. */
        fun read(id: String, text: FileText): Boolean =
            record(id, text, TextSearch.snippet(text.text, text.folded, query))

        /** Records a snippet already computed off the main thread for [text]. */
        internal fun record(id: String, text: FileText, snippet: TextSearch.Snippet?): Boolean {
            checked[id] = text.token
            if (snippet == null) { matches.remove(id); return false }
            matches[id] = snippet
            return true
        }

        internal fun remember(entries: Map<String, Pair<Long, TextSearch.Snippet?>>) {
            for ((id, evaluation) in entries) {
                val (token, snippet) = evaluation
                if ((checked[id] ?: -1L) > token) continue
                checked[id] = token
                if (snippet != null) matches[id] = snippet else matches.remove(id)
            }
        }

        internal fun lookup(id: String, text: FileText, query: String, words: List<String>): TextSearch.Snippet? {
            if (query != this.query) return TextSearch.snippet(text.text, text.folded, words)
            if (checked[id] == text.token) return matches[id]
            val snippet = TextSearch.snippet(text.text, text.folded, words)
            checked[id] = text.token
            if (snippet != null) matches[id] = snippet else matches.remove(id)
            return snippet
        }

        /** The file left the list. */
        fun forget(id: String) { matches.remove(id); checked.remove(id) }
    }

    const val MAX_FILES = 64
    /** Roughly a tenth of a typical 192 MB heap once both strings exist; documents are re-read instead. */
    const val MAX_CHARACTERS = 4_000_000

    /** The records to show, and for files found by their text the fragment with the found word. */
    class View(val records: List<DownloadRecord>, val snippets: Map<String, TextSearch.Snippet>)

    /** Both the sorted list view and the chip counts computed in one pass, plus newly checked text tokens. */
    internal class Presentation(
        val view: View,
        val counts: Map<FileKind, Int>,
        val checked: Map<String, Pair<Long, TextSearch.Snippet?>>,
    )

    /**
     * [texts] are the files read so far when the search also looks inside files (null when it does not), [scan] retains
     * matches for its query whose text has since left the cache. A file matches by name or course, or by its
     * text; a file found by its text gets a snippet even if its name matches too.
     */
    fun view(records: List<DownloadRecord>, query: String, kind: FileKind?, subject: String?, texts: Map<String, FileText>?, order: Order,
        scan: TextScan? = null): View {
        val (matching, snippets) = select(records, query, kind, subject, texts, scan)
        return View(sort(matching, order), snippets)
    }

    /** Computes [View] and [kindCounts] together in a single pass from immutable inputs (safe off the main thread). */
    internal fun present(
        records: List<DownloadRecord>,
        query: String,
        kind: FileKind?,
        subject: String?,
        texts: Map<String, FileText>?,
        order: Order,
        scan: ScanSnapshot,
    ): Presentation {
        val inside = texts != null && query.isNotBlank()
        val queryWords = FileKinds.queryWords(query)
        val textWords = if (inside) TextSearch.words(query) else emptyList()
        val snippets = mutableMapOf<String, TextSearch.Snippet>()
        val newlyChecked = mutableMapOf<String, Pair<Long, TextSearch.Snippet?>>()
        val counts = mutableMapOf<FileKind, Int>()
        val matching = ArrayList<DownloadRecord>(records.size)
        for (record in records) {
            if (!FileKinds.matchesSubject(record, subject)) continue
            var snippet: TextSearch.Snippet? = null
            if (inside) {
                val text = texts[record.id]
                snippet = if (text != null) {
                    if (scan.checked[record.id] == text.token) scan.found[record.id]
                    else TextSearch.snippet(text.text, text.folded, textWords).also { newlyChecked[record.id] = text.token to it }
                } else scan.found[record.id]
            }
            if (snippet == null && !FileKinds.matchesQuery(record, queryWords)) continue
            val filterKind = FileKinds.filterKind(record.kind)
            counts[filterKind] = (counts[filterKind] ?: 0) + 1
            if (kind == null || filterKind == kind) {
                matching += record
                if (snippet != null) snippets[record.id] = snippet
            }
        }
        return Presentation(View(sort(matching, order), snippets), counts, newlyChecked)
    }

    private fun sort(matching: List<DownloadRecord>, order: Order): List<DownloadRecord> = when (order) {
        Order.NAME -> matching.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName })
        Order.SIZE -> matching.sortedByDescending { it.total.takeIf { size -> size >= 0 } ?: it.received }
        Order.DATE -> matching.sortedByDescending { it.displayTime }
    }

    /** The files that match, in their stored order, with the snippets of those found by their text. */
    private fun select(records: List<DownloadRecord>, query: String, kind: FileKind?, subject: String?, texts: Map<String, FileText>?,
        scan: TextScan?): Pair<List<DownloadRecord>, Map<String, TextSearch.Snippet>> {
        val inside = texts != null && query.isNotBlank()
        val found = scan?.found(query).orEmpty()
        val queryWords = FileKinds.queryWords(query)
        val textWords = if (inside) TextSearch.words(query) else emptyList()
        val snippets = mutableMapOf<String, TextSearch.Snippet>()
        val matching = records.filter { record ->
            if (!FileKinds.matchesFilter(record, kind) || !FileKinds.matchesSubject(record, subject)) return@filter false
            if (inside) {
                val text = texts[record.id]
                val snippet = if (text != null) {
                    if (scan != null) scan.lookup(record.id, text, query, textWords)
                    else TextSearch.snippet(text.text, text.folded, textWords)
                } else found[record.id]
                if (snippet != null) snippets[record.id] = snippet
            }
            FileKinds.matchesQuery(record, queryWords) || record.id in snippets
        }
        return matching to snippets
    }
}
