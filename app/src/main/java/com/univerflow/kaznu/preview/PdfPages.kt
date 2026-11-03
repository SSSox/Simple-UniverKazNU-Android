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
    private val renderer = PdfRenderer(descriptor)
    private val lock = ReentrantLock(true)
    private var closed = false
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

    override fun close() = lock.withLock {
        if (!closed) { closed = true; renderer.close() }
    }

    companion object {
        const val PAGE_LIMIT = 2000
        private const val MAX_WIDTH = 2000
        private const val MAX_HEIGHT = 6000
    }
}
