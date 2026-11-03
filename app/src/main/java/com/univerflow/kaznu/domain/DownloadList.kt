package com.univerflow.kaznu.domain

/** The Downloads list as shown: type and course filters, the search (by name and course, and inside files), the order. */
object DownloadList {
    enum class Order { DATE, NAME, SIZE }

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

        fun clear() { entries.clear(); characters = 0 }

        /** The texts as [view] expects them, without the bookkeeping. */
        fun snapshot(): Map<String, FileText> = entries
    }

    const val MAX_FILES = 64
    /** Roughly a tenth of a typical 192 MB heap once both strings exist; documents are re-read instead. */
    const val MAX_CHARACTERS = 4_000_000

    /** The records to show, and for files found by their text the fragment with the found word. */
    class View(val records: List<DownloadRecord>, val snippets: Map<String, TextSearch.Snippet>)

    /**
     * [texts] are the files read so far when the search also looks inside files (null when it does not). A file
     * matches by name or course, or by its text; a file found by its text gets a snippet even if its name matches too.
     */
    fun view(records: List<DownloadRecord>, query: String, kind: FileKind?, subject: String?, texts: Map<String, FileText>?, order: Order): View {
        val inside = texts != null && query.isNotBlank()
        val snippets = mutableMapOf<String, TextSearch.Snippet>()
        val matching = records.filter { record ->
            if (!FileKinds.matchesFilter(record, kind) || !FileKinds.matchesSubject(record, subject)) return@filter false
            val text = if (inside) texts?.get(record.id) else null
            if (text != null && TextSearch.matches(text.folded, query)) TextSearch.snippet(text.text, text.folded, query)?.let { snippets[record.id] = it }
            FileKinds.matchesQuery(record, query) || record.id in snippets
        }
        val sorted = when (order) {
            Order.NAME -> matching.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName })
            Order.SIZE -> matching.sortedByDescending { it.total.takeIf { size -> size >= 0 } ?: it.received }
            Order.DATE -> matching.sortedByDescending { it.started }
        }
        return View(sorted, snippets)
    }
}
