package com.univerflow.kaznu.preview

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One open PDF. PdfRenderer allows a single page at a time, so pages are opened under one lock, a fair one: a page the
 * reader is waiting for is drawn between the size reads of later pages instead of after all of them.
 */
class PdfPages(descriptor: ParcelFileDescriptor) : Closeable {
    // A PDF that asks for a password: the file is there, but this viewer cannot open it.
    private val renderer = try { PdfRenderer(descriptor) } catch (_: SecurityException) { throw UnsupportedDocumentException("Password-protected PDF") }
    private val lock = ReentrantLock(true)
    /** Set without the lock, so pages waiting for it after [close] return at once instead of being drawn. */
    @Volatile private var closed = false
    /** Whether [renderer] is closed; read and set under the lock. */
    private var released = false
    val count: Int = renderer.pageCount
    /** Pages shown: a longer file is shown from its start. */
    val shown = minOf(count, PAGE_LIMIT)
    private val widths = IntArray(shown)
    private val heights = IntArray(shown)

    /** The size of a page in PDF points, read when first asked; null after [close]. */
    fun size(index: Int): Pair<Int, Int>? = lock.withLock {
        if (closed || index !in 0 until shown) return null
        if (widths[index] == 0) renderer.openPage(index).use { widths[index] = it.width.coerceAtLeast(1); heights[index] = it.height.coerceAtLeast(1) }
        widths[index] to heights[index]
    }

    /** Returns a PNG [width] pixels wide, or null after [close] or without memory for it. */
    fun render(index: Int, width: Int): ByteArray? = lock.withLock {
        if (closed || index !in 0 until count) return null
        try {
            renderer.openPage(index).use { page ->
                val target = width.coerceIn(320, MAX_WIDTH)
                val height = (target.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, MAX_HEIGHT)
                val bitmap = createBitmap(target, height)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
                } finally { bitmap.recycle() }
            }
        } catch (_: OutOfMemoryError) { null } // The page stays blank; the viewer keeps running.
    }

    /**
     * Ends drawing without waiting for it on the calling (main) thread: a page being drawn finishes and the renderer is
     * then closed on another thread; pages waiting for the lock draw nothing.
     */
    override fun close() {
        if (closed) return
        closed = true
        if (lock.tryLock()) {
            try { release() } finally { lock.unlock() }
        } else {
            Thread { lock.withLock { release() } }.start()
        }
    }

    private fun release() {
        if (!released) { released = true; renderer.close() }
    }

    companion object {
        const val PAGE_LIMIT = 2000
        private const val MAX_WIDTH = 2000
        private const val MAX_HEIGHT = 6000
    }
}
