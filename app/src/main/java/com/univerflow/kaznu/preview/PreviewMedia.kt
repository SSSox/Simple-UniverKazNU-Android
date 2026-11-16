package com.univerflow.kaznu.preview

import java.io.File
import java.io.InputStream

/**
 * Where a reader puts a document's pictures; the page refers to each by the address [add] returns. The viewer keeps
 * them as files served from its own origin ([FileMedia]), so a presentation full of photos stays a small page.
 */
interface PreviewMedia {
    /** Keeps a picture of [type] (one of [TYPES]) and returns its address, or null when it is not kept. */
    fun add(type: String, bytes: ByteArray): String?

    companion object {
        val TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/bmp", "image/webp")

        /** Pictures as `data:` addresses inside the page, within [total] bytes (the search and the unit tests). */
        fun inline(total: Int = 24 * 1024 * 1024): PreviewMedia = object : PreviewMedia {
            private var used = 0
            override fun add(type: String, bytes: ByteArray): String? {
                if (type !in TYPES || bytes.isEmpty() || used + bytes.size > total) return null
                used += bytes.size
                return "data:$type;base64," + WordHtml.base64(bytes)
            }
        }
    }
}

/**
 * Pictures as files in a private folder, named by SHA-256 so each is kept once; [limit] bounds the disk they take.
 * The address may end in its pixel size ("#800x600") for the page's placeholder; the fragment is never requested.
 */
class FileMedia(private val directory: File, private val limit: Long = 768L * 1024 * 1024) : PreviewMedia {
    private val types = HashMap<String, String>()
    private val addresses = HashMap<String, String>()
    private var used = 0L

    @Synchronized override fun add(type: String, bytes: ByteArray): String? {
        if (type !in PreviewMedia.TYPES || bytes.isEmpty()) return null
        val name = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        addresses[name]?.let { return it }
        if (used + bytes.size > limit || types.size >= 50_000) return null
        if (!directory.isDirectory && !directory.mkdirs()) return null
        runCatching { File(directory, name).writeBytes(bytes) }.getOrElse { return null }
        types[name] = type
        used += bytes.size
        val address = PreviewHtml.mediaUrl(name) + (ImageSize.of(bytes)?.let { (width, height) -> "#${width}x$height" } ?: "")
        addresses[name] = address
        return address
    }

    /** The picture named [name] and its type, or null for a name the page was never given. */
    @Synchronized fun open(name: String): Pair<InputStream, String>? {
        val type = types[name] ?: return null
        return runCatching { File(directory, name).inputStream() to type }.getOrNull()
    }

    @Synchronized fun clear() {
        types.clear()
        addresses.clear()
        used = 0
        directory.deleteRecursively()
    }
}

/** How a reader reaches a file: its first bytes, a fresh stream from the start, and positional reads for compound files. */
internal class FileAccess(val head: ByteArray, val stream: () -> InputStream, val source: () -> ByteSource) {
    val zip get() = head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2].toInt() == 3 && head[3].toInt() == 4
    val compound get() = CompoundFile.startsLikeCompound(head)

    companion object {
        fun of(bytes: ByteArray) = FileAccess(bytes.copyOf(minOf(bytes.size, HEAD)), { bytes.inputStream() }, { ArraySource(bytes) })
        const val HEAD = 4096
    }
}
