package com.univerflow.kaznu.domain

/** The Downloads list as shown: type and course filters, the search (by name and course, and inside files), the order. */
object DownloadList {
    enum class Order { DATE, NAME, SIZE }

    /** When a file arrived, as the list groups it. */
    enum class Period { TODAY, YESTERDAY, WEEK, MONTH, EARLIER }

    /** The group of a file that arrived at [at]; whole calendar days in [zone], as a person counts them. */
    fun period(at: Long, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Period {
        fun day(time: Long) = Math.floorDiv(time + zone.getOffset(time), 86_400_000L)
        val days = day(now) - day(at)
        return when {
            days <= 0 -> Period.TODAY
            days == 1L -> Period.YESTERDAY
            days < 7 -> Period.WEEK
            days < 31 -> Period.MONTH
            else -> Period.EARLIER
        }
    }

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
        val folded: String by lazy(LazyThreadSafetyMode.NONE) { TextSearch.fold(text) }
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
    }

    /**
     * Which files one query still has to read. The bounded cache forgets texts, so "not cached" cannot mean "not read":
     * a library larger than the cache would otherwise be read over and over for as long as the query stands.
     */
    class TextScan(val query: String) {
        private val done = HashSet<String>()
        private val matches = HashMap<String, TextSearch.Snippet>()

        /**
         * What this query found inside files, kept apart from the bounded cache: a matching text that the cache drops
         * to make room is not read again for the same query, and its file must not leave the results.
         */
        fun found(query: String): Map<String, TextSearch.Snippet> = if (query == this.query) matches else emptyMap()

        fun pending(candidates: List<DownloadRecord>, cached: TextCache): List<DownloadRecord> =
            candidates.filter { it.id !in done && it.id !in cached }

        /** Records a completed read and returns whether the text matched this query. */
        fun read(id: String, text: FileText): Boolean {
            done += id
            val snippet = TextSearch.snippet(text.text, text.folded, query) ?: return false
            matches[id] = snippet
            return true
        }

        /** The file left the list. */
        fun forget(id: String) { done -= id; matches.remove(id) }
    }

    const val MAX_FILES = 64
    /** Roughly a tenth of a typical 192 MB heap once both strings exist; documents are re-read instead. */
    const val MAX_CHARACTERS = 4_000_000

    /** The records to show, and for files found by their text the fragment with the found word. */
    class View(val records: List<DownloadRecord>, val snippets: Map<String, TextSearch.Snippet>)

    /**
     * [texts] are the files read so far when the search also looks inside files (null when it does not), [scan] retains
     * matches for its query whose text has since left the cache. A file matches by name or course, or by its
     * text; a file found by its text gets a snippet even if its name matches too.
     */
    fun view(records: List<DownloadRecord>, query: String, kind: FileKind?, subject: String?, texts: Map<String, FileText>?, order: Order,
        scan: TextScan? = null): View {
        val (matching, snippets) = select(records, query, kind, subject, texts, scan)
        val sorted = when (order) {
            Order.NAME -> matching.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName })
            Order.SIZE -> matching.sortedByDescending { it.total.takeIf { size -> size >= 0 } ?: it.received }
            Order.DATE -> matching.sortedByDescending { it.displayTime }
        }
        return View(sorted, snippets)
    }

    /** The files that match, in their stored order, with the snippets of those found by their text. */
    private fun select(records: List<DownloadRecord>, query: String, kind: FileKind?, subject: String?, texts: Map<String, FileText>?,
        scan: TextScan?): Pair<List<DownloadRecord>, Map<String, TextSearch.Snippet>> {
        val inside = texts != null && query.isNotBlank()
        val found = scan?.found(query).orEmpty()
        val snippets = mutableMapOf<String, TextSearch.Snippet>()
        val matching = records.filter { record ->
            if (!FileKinds.matchesFilter(record, kind) || !FileKinds.matchesSubject(record, subject)) return@filter false
            if (inside) {
                val text = texts[record.id]
                val snippet = if (text != null) TextSearch.snippet(text.text, text.folded, query) else found[record.id]
                if (snippet != null) snippets[record.id] = snippet
            }
            FileKinds.matchesQuery(record, query) || record.id in snippets
        }
        return matching to snippets
    }
}
