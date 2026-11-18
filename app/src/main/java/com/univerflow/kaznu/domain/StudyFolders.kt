package com.univerflow.kaznu.domain

/** Course and section names are page text, reduced to at most two safe path segments under our Downloads folder. */
object StudyFolders {
    const val ROOT = "Univer УМКД"
    private val INVISIBLE = Regex("[\\p{Cc}\\p{Cf}\\s]+")
    private val SEPARATOR = Regex("\\s*[/\\\\|]\\s*")
    private val COLON = Regex("\\s*:\\s*")
    private val RESERVED = Regex("[*?\"<>]")

    fun name(value: String?): String {
        var text = value.orEmpty().replace(INVISIBLE, " ").replace(SEPARATOR, ", ").replace(COLON, " - ")
            .replace(RESERVED, "").trim(' ', '.', ',', '-')
        if (text.length > 64) {
            val space = text.lastIndexOf(' ', 64)
            text = text.take(if (space >= 32) space else 64)
        }
        if (text.lastOrNull()?.isHighSurrogate() == true) text = text.dropLast(1)
        return text.trimEnd(' ', '.', ',', '-')
    }

    fun relative(course: String?, section: String?): String {
        val first = name(course)
        if (first.isEmpty()) return ROOT
        val second = name(section)
        return "$ROOT/$first" + if (second.isEmpty()) "" else "/$second"
    }

    /** Validates an already built relative path again at the storage boundary. */
    fun checked(value: String?): String? {
        val parts = value?.split('/') ?: return null
        if (parts.size !in 1..3 || parts[0] != ROOT) return null
        if (parts.drop(1).any { it.isEmpty() || name(it) != it || it == "." || it == ".." }) return null
        return parts.joinToString("/")
    }
}
