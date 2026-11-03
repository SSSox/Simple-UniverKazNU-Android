package com.univerflow.kaznu.preview

import java.io.InputStream

/**
 * A page written while WebView reads it: [head], then [count] parts a batch at a time, then [tail], so the first parts
 * show before the last are made. A null part ends the page early (the document was closed).
 */
internal class PartStream(head: String, private val count: Int, private val part: (Int) -> String?, private val tail: String) : InputStream() {
    private var buffer = head.toByteArray(Charsets.UTF_8)
    private var position = 0
    private var next = 0
    private var finished = false

    private fun fill(): Boolean {
        while (position >= buffer.size) {
            if (finished) return false
            val out = StringBuilder()
            while (next < count && out.length < BATCH) {
                // WebView reads on its own thread: a failure ends the page instead of reaching it.
                val html = try { part(next) } catch (_: Exception) { null }
                if (html == null) { next = count; break }
                out.append(html)
                next++
            }
            if (next >= count) { out.append(tail); finished = true }
            buffer = out.toString().toByteArray(Charsets.UTF_8)
            position = 0
        }
        return true
    }

    override fun read(): Int = if (fill()) buffer[position++].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val count = minOf(len, buffer.size - position)
        System.arraycopy(buffer, position, b, off, count)
        position += count
        return count
    }

    private companion object {
        /** About ten PDF pages: small enough for the first ones to show at once. */
        const val BATCH = 4096
    }
}

/** Bytes made only when WebView starts reading them, so a request it holds back costs no memory; empty if [make] gives none. */
internal class DeferredStream(private val make: () -> ByteArray?) : InputStream() {
    private var bytes: ByteArray? = null
    private var position = 0

    private fun data() = bytes ?: ((try { make() } catch (_: Exception) { null }) ?: ByteArray(0)).also { bytes = it }

    override fun read(): Int = data().let { if (position < it.size) it[position++].toInt() and 0xFF else -1 }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val data = data()
        if (position >= data.size) return -1
        val count = minOf(len, data.size - position)
        System.arraycopy(data, position, b, off, count)
        position += count
        return count
    }

    override fun close() { bytes = ByteArray(0); position = 0 } // Never made after closing.
}
