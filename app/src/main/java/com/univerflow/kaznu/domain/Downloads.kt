package com.univerflow.kaznu.domain

import java.util.Locale

enum class DownloadState { RUNNING, COMPLETED, FAILED, CANCELED }

enum class FileKind { PDF, WORD, EXCEL, POWERPOINT, IMAGE, TEXT, ARCHIVE, OTHER }

/** Why a transfer did not complete. Only these codes are stored, never server text or URLs. */
enum class DownloadFailure { LOGIN, ACCESS_DENIED, NETWORK, INTERRUPTED }

/**
 * A file saved through the app: its local name and content URI only, never the URL, cookies or referer.
 * [key] is a one-way digest of the address ([DownloadKeys]) that recognises the same file downloaded again.
 */
data class DownloadRecord(
    val id: String,
    val fileName: String = "",
    val uri: String? = null,
    val mime: String? = null,
    val state: DownloadState = DownloadState.RUNNING,
    val received: Long = 0,
    val total: Long = -1,
    val started: Long,
    val finished: Long? = null,
    val source: String = "",
    val failure: DownloadFailure? = null,
    val key: String = "",
) {
    val kind get() = FileKinds.of(fileName, mime)
    val progress: Int? get() = if (total > 0) (received * 100 / total).coerceIn(0, 100).toInt() else null
}

object FileKinds {
    fun of(name: String?, mime: String? = null): FileKind {
        val fromName = when (name.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "pdf" -> FileKind.PDF
            "doc", "docx", "docm", "dot", "dotx", "dotm", "rtf", "odt" -> FileKind.WORD
            "xls", "xlt", "xlsx", "xlsm", "csv", "ods" -> FileKind.EXCEL
            "ppt", "pot", "pptx", "pptm", "pps", "ppsx", "odp" -> FileKind.POWERPOINT
            "png", "jpg", "jpeg", "gif", "bmp", "webp" -> FileKind.IMAGE
            "txt" -> FileKind.TEXT
            "zip", "rar", "7z" -> FileKind.ARCHIVE
            else -> null
        }
        if (fromName != null) return fromName
        val type = mime.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        return when {
            type == "application/pdf" -> FileKind.PDF
            type.startsWith("image/") -> FileKind.IMAGE
            type == "text/plain" -> FileKind.TEXT
            else -> FileKind.OTHER
        }
    }

    fun badge(kind: FileKind) = when (kind) {
        FileKind.PDF -> "PDF"
        FileKind.WORD -> "DOC"
        FileKind.EXCEL -> "XLS"
        FileKind.POWERPOINT -> "PPT"
        FileKind.IMAGE -> "IMG"
        FileKind.TEXT -> "TXT"
        FileKind.ARCHIVE -> "ZIP"
        FileKind.OTHER -> "FILE"
    }

    /** "Other" also covers text and archives. */
    fun matchesFilter(record: DownloadRecord, filter: FileKind?): Boolean {
        if (filter == null) return true
        val actual = record.kind
        return if (filter == FileKind.OTHER) actual in setOf(FileKind.OTHER, FileKind.TEXT, FileKind.ARCHIVE) else actual == filter
    }

    /** Every word must occur in the name or source; case is ignored and "ё" equals "е". */
    fun matchesQuery(record: DownloadRecord, query: String): Boolean {
        val words = fold(query).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val text = fold(record.fileName + " " + record.source)
        return words.all { text.contains(it) }
    }

    fun matches(record: DownloadRecord, query: String, filter: FileKind?) =
        matchesFilter(record, filter) && matchesQuery(record, query)

    /**
     * The course a file came from, read from the page heading saved with it: "Психология" stays as is,
     * "Социология (Системы … -6618): СРС 1. | СДО КазНУ …" (a Moodle page) becomes "Социология".
     */
    fun subject(record: DownloadRecord): String = record.source.substringBefore(" | ").substringBefore(" (").substringBefore(":")
        .replace(Regex("\\s+"), " ").trim().take(80)

    fun matchesSubject(record: DownloadRecord, subject: String?) = subject == null || subject(record) == subject

    /** Courses present in the list, in alphabetical order; files without a course have no chip. */
    fun subjects(records: List<DownloadRecord>): List<String> =
        records.map(::subject).filter { it.isNotEmpty() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** Units follow the app language: "КБ/МБ" for Russian and Kazakh, "KB/MB" for English. */
    fun size(bytes: Long, language: AppLanguage): String {
        if (bytes < 0) return ""
        val units = if (language == AppLanguage.ENGLISH) listOf("B", "KB", "MB", "GB") else listOf("Б", "КБ", "МБ", "ГБ")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
        val locale = if (language == AppLanguage.ENGLISH) Locale.US else Locale.forLanguageTag(language.localeCode)
        val number = when {
            unit == 0 -> String.format(locale, "%.0f", value)
            value < 10 -> String.format(locale, "%.1f", value)
            else -> String.format(locale, "%.0f", value)
        }
        return "$number ${units[unit]}"
    }

    private fun fold(value: String) = value.lowercase(Locale.ROOT).replace('ё', 'е')
}

/** Newest first and bounded. A transfer cannot outlive the process that ran it. */
object DownloadHistoryRules {
    const val LIMIT = 1000

    fun restore(saved: List<DownloadRecord>, now: Long): List<DownloadRecord> = saved
        .distinctBy { it.id }
        .map { if (it.state == DownloadState.RUNNING) it.copy(state = DownloadState.FAILED, failure = DownloadFailure.INTERRUPTED, finished = now) else it }
        .sortedByDescending { it.started }
        .take(LIMIT)

    fun upsert(records: List<DownloadRecord>, record: DownloadRecord): List<DownloadRecord> {
        val index = records.indexOfFirst { it.id == record.id }
        val next = if (index >= 0) records.toMutableList().also { it[index] = record } else listOf(record) + records
        return next.take(LIMIT)
    }

    /** The newest finished download of the same file, if any (whether it still exists is checked by the caller). */
    fun previous(records: List<DownloadRecord>, key: String): DownloadRecord? = if (key.isEmpty()) null else
        records.filter { it.key == key && it.state == DownloadState.COMPLETED && it.uri != null }.maxByOrNull { it.finished ?: it.started }
}

/**
 * A one-way digest of a file's address (scheme, host, path, query without Moodle's "forcedownload"): the history
 * recognises a repeated download without storing where it came from.
 */
object DownloadKeys {
    fun of(url: String): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return ""
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return ""
        val host = uri.host?.lowercase(Locale.ROOT) ?: return ""
        val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() && !it.startsWith("forcedownload=") }.sorted().joinToString("&")
        val normal = scheme + "://" + host + uri.rawPath.orEmpty().trimEnd('/') + if (query.isEmpty()) "" else "?$query"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(normal.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Search inside a file's text: any of the words may occur, so a document is found when the student remembers only
 * part of what it says. The snippet shows the first word that actually occurs, in context.
 */
object TextSearch {
    data class Snippet(val text: String, val start: Int, val end: Int)

    /** Lower case and "ё" → "е" without changing the length, so positions map back to the original text. */
    fun fold(value: String): String = buildString(value.length) { value.forEach { append(if (it == 'ё' || it == 'Ё') 'е' else it.lowercaseChar()) } }

    private fun words(query: String) = fold(query).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun matches(folded: String, query: String) = words(query).let { list -> list.isNotEmpty() && list.any { folded.contains(it) } }

    fun snippet(text: String, folded: String, query: String, radius: Int = 60): Snippet? {
        // Only a word that is really in the text can be highlighted: the others of the query may be absent.
        val first = words(query).firstOrNull { folded.contains(it) } ?: return null
        val at = folded.indexOf(first)
        var from = (at - radius).coerceAtLeast(0)
        var to = (at + first.length + radius).coerceAtMost(text.length)
        // Start and end on word boundaries where possible.
        if (from > 0) text.indexOf(' ', from).takeIf { it in from until at }?.let { from = it + 1 }
        if (to < text.length) text.lastIndexOf(' ', to).takeIf { it > at + first.length }?.let { to = it }
        val prefix = if (from > 0) "…" else ""
        val body = text.substring(from, to).replace(Regex("\\s+"), " ")
        val start = prefix.length + text.substring(from, at).replace(Regex("\\s+"), " ").length
        return Snippet(prefix + body + if (to < text.length) "…" else "", start, start + first.length)
    }
}
