package com.univerflow.kaznu.preview

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale

/** Bytes read by position: a file in memory, or an open file read in place (a large download is never copied whole). */
internal interface ByteSource {
    val size: Long
    /** Fills [buffer] from [position]; fewer bytes only at the end of the source. Returns the count read. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

internal class ArraySource(private val bytes: ByteArray) : ByteSource {
    override val size get() = bytes.size.toLong()
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= bytes.size) return 0
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, count)
        return count
    }
}

internal class ChannelSource(private val channel: FileChannel) : ByteSource {
    override val size: Long = channel.size()
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val target = ByteBuffer.wrap(buffer, offset, length)
        var at = position
        while (target.hasRemaining()) {
            val count = channel.read(target, at)
            if (count <= 0) break
            at += count
        }
        return target.position() - offset
    }
}

/**
 * Microsoft Compound File Binary ([MS-CFB]), the container of .doc, .xls and .ppt. Every sector number, chain and size
 * is checked: a damaged or hostile file fails with [UnsupportedDocumentException] instead of looping or overreading.
 */
internal class CompoundFile(private val source: ByteSource) {
    constructor(data: ByteArray) : this(ArraySource(data))

    private class Entry(val name: String, val type: Int, val left: Int, val right: Int, val child: Int, val start: Int, val size: Long)

    private val sectorSize: Int
    private val miniCutoff: Long
    private val fat: IntArray
    private val miniFat: IntArray
    private val nodes: List<Entry>
    private val miniStream: ByteArray
    /** Streams directly under the root storage, by upper-case name (embedded objects keep their own). */
    private val streams: Map<String, Entry>

    init {
        val head = ByteArray(512)
        if (source.read(0, head, 0, 512) < 512 || !isCompound(head)) throw UnsupportedDocumentException("Not a compound file")
        val file = LittleEndian(head)
        val shift = file.u16(0x1E)
        if (shift != 9 && shift != 12 || file.u16(0x20) != 6) throw UnsupportedDocumentException("Unknown sector size")
        sectorSize = 1 shl shift
        miniCutoff = file.u32(0x38)
        val sectorCount = ((source.size - sectorSize).coerceAtLeast(0) + sectorSize - 1) / sectorSize
        if (sectorCount > Int.MAX_VALUE / 2) throw PreviewLimitException()
        val perSector = sectorSize / 4
        // The FAT sectors are listed by the 109 header entries, then by the DIFAT sector chain.
        val fatSectors = mutableListOf<Int>()
        val fatCount = file.i32(0x2C)
        if (fatCount < 0 || fatCount > sectorCount) throw UnsupportedDocumentException("Bad FAT size")
        for (index in 0 until 109) { if (fatSectors.size < fatCount) fatSectors += file.i32(0x4C + index * 4) }
        var difat = file.i32(0x44)
        var difatSeen = 0
        while (fatSectors.size < fatCount && difat >= 0) {
            if (++difatSeen > sectorCount) throw UnsupportedDocumentException("DIFAT loop")
            val sector = LittleEndian(sector(difat))
            for (index in 0 until perSector - 1) { if (fatSectors.size < fatCount) fatSectors += sector.i32(index * 4) }
            difat = sector.i32((perSector - 1) * 4)
        }
        fat = IntArray(fatSectors.size * perSector)
        fatSectors.forEachIndexed { index, number ->
            val sector = LittleEndian(sector(number))
            for (slot in 0 until perSector) fat[index * perSector + slot] = sector.i32(slot * 4)
        }
        val directory = chain(file.i32(0x30), Long.MAX_VALUE)
        val table = LittleEndian(directory)
        nodes = (0 until directory.size / 128).map { index ->
            val base = index * 128
            val nameBytes = table.u16(base + 0x40).coerceIn(0, 64)
            Entry(
                name = if (nameBytes >= 2) table.utf16(base, nameBytes / 2 - 1) else "",
                type = table.u8(base + 0x42),
                left = table.i32(base + 0x44), right = table.i32(base + 0x48), child = table.i32(base + 0x4C),
                start = table.i32(base + 0x74), size = table.u32(base + 0x78),
            )
        }
        val root = nodes.firstOrNull()?.takeIf { it.type == ROOT } ?: throw UnsupportedDocumentException("No root")
        // Small streams live in the mini stream; it holds only streams under the cutoff, so it stays small.
        miniStream = if (root.size > 0) chain(root.start, minOf(root.size, MINI_STREAM_LIMIT)) else ByteArray(0)
        val miniFatBytes = if (file.i32(0x40) > 0) chain(file.i32(0x3C), Long.MAX_VALUE) else ByteArray(0)
        miniFat = LittleEndian(miniFatBytes).let { bytes -> IntArray(miniFatBytes.size / 4) { bytes.i32(it * 4) } }
        streams = buildMap {
            val pending = ArrayDeque<Int>().apply { add(root.child) }
            val seen = mutableSetOf<Int>()
            while (pending.isNotEmpty()) {
                val index = pending.removeLast()
                if (index !in nodes.indices || !seen.add(index)) continue
                val entry = nodes[index]
                if (entry.type == STREAM) putIfAbsent(entry.name.uppercase(Locale.ROOT), entry)
                pending.add(entry.left); pending.add(entry.right)
            }
        }
    }

    fun has(name: String) = name.uppercase(Locale.ROOT) in streams

    /** The named root-level stream in memory, or null when the file has none; past [limit] bytes it is refused. */
    fun stream(name: String, limit: Long = STREAM_LIMIT): ByteArray? {
        val entry = streams[name.uppercase(Locale.ROOT)] ?: return null
        if (entry.size == 0L) return ByteArray(0)
        if (entry.size > limit) throw PreviewLimitException()
        return if (entry.size < miniCutoff) miniChain(entry.start, entry.size) else chain(entry.start, entry.size)
    }

    /** A large stream read in place, by position (the pictures of a presentation or a document). */
    fun reader(name: String): StreamReader? {
        val entry = streams[name.uppercase(Locale.ROOT)] ?: return null
        if (entry.size < miniCutoff) return StreamReader.of(stream(name) ?: return null)
        val sectors = ArrayList<Int>()
        var sector = entry.start
        while (sector != END_OF_CHAIN && sectors.size.toLong() * sectorSize < entry.size) {
            if (sector !in fat.indices || sectors.size > fat.size) throw UnsupportedDocumentException("Bad chain")
            sectors += sector
            sector = fat[sector]
        }
        if (sectors.size.toLong() * sectorSize < entry.size) throw UnsupportedDocumentException("Short stream")
        return object : StreamReader {
            override val size = entry.size
            override fun read(position: Long, length: Int): ByteArray {
                if (position < 0 || length < 0 || position + length > size) throw UnsupportedDocumentException("Damaged file")
                val out = ByteArray(length)
                var done = 0
                while (done < length) {
                    val at = position + done
                    val index = (at / sectorSize).toInt()
                    val within = (at % sectorSize).toInt()
                    val count = minOf(sectorSize - within, length - done)
                    if (source.read(offset(sectors[index]) + within, out, done, count) < count) throw UnsupportedDocumentException("Short stream")
                    done += count
                }
                return out
            }
        }
    }

    private fun offset(sector: Int): Long {
        val base = (sector.toLong() + 1) * sectorSize
        if (sector < 0 || base >= source.size) throw UnsupportedDocumentException("Bad sector")
        return base
    }

    /** One sector; the last sector of a file may be cut short. */
    private fun sector(number: Int): ByteArray {
        val bytes = ByteArray(sectorSize)
        source.read(offset(number), bytes, 0, sectorSize)
        return bytes
    }

    /** Joins a FAT chain; [size] trims the result (Long.MAX_VALUE: the whole chain, as for the directory). */
    private fun chain(start: Int, size: Long): ByteArray {
        if (size != Long.MAX_VALUE && size > source.size) throw UnsupportedDocumentException("Stream too long")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(sectorSize)
        var sector = start
        var steps = 0
        while (sector != END_OF_CHAIN && output.size() < size) {
            if (sector !in fat.indices || ++steps > fat.size) throw UnsupportedDocumentException("Bad chain")
            if (size == Long.MAX_VALUE && output.size() > STREAM_LIMIT) throw PreviewLimitException()
            val count = source.read(offset(sector), buffer, 0, sectorSize)
            output.write(buffer, 0, count)
            sector = fat[sector]
        }
        val bytes = output.toByteArray()
        if (size != Long.MAX_VALUE && bytes.size < size) throw UnsupportedDocumentException("Short stream")
        return if (size == Long.MAX_VALUE) bytes else bytes.copyOf(size.toInt())
    }

    private fun miniChain(start: Int, size: Long): ByteArray {
        if (size > miniStream.size) throw UnsupportedDocumentException("Stream too long")
        val output = ByteArrayOutputStream()
        var sector = start
        var steps = 0
        while (sector != END_OF_CHAIN && output.size() < size) {
            val base = sector.toLong() * MINI_SECTOR
            if (sector !in miniFat.indices || base + MINI_SECTOR > miniStream.size || ++steps > miniFat.size) throw UnsupportedDocumentException("Bad chain")
            output.write(miniStream, base.toInt(), MINI_SECTOR)
            sector = miniFat[sector]
        }
        val bytes = output.toByteArray()
        if (bytes.size < size) throw UnsupportedDocumentException("Short stream")
        return bytes.copyOf(size.toInt())
    }

    companion object {
        private const val END_OF_CHAIN = -2
        private const val MINI_SECTOR = 64
        private const val STREAM = 2
        private const val ROOT = 5
        /** Streams kept in memory (text, tables, workbook records); pictures are read in place through [reader]. */
        const val STREAM_LIMIT = 192L * 1024 * 1024
        private const val MINI_STREAM_LIMIT = 64L * 1024 * 1024
        private val SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

        fun isCompound(bytes: ByteArray) = bytes.size >= 512 && SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }

        /** Whether [head] (the first bytes of a file, at least 8) starts like a compound file. */
        fun startsLikeCompound(head: ByteArray) = head.size >= 8 && SIGNATURE.indices.all { head[it] == SIGNATURE[it] }
    }
}

/** A stream of a compound file read by position. */
internal interface StreamReader {
    val size: Long
    fun read(position: Long, length: Int): ByteArray

    companion object {
        fun of(bytes: ByteArray) = object : StreamReader {
            override val size = bytes.size.toLong()
            override fun read(position: Long, length: Int): ByteArray {
                if (position < 0 || length < 0 || position + length > bytes.size) throw UnsupportedDocumentException("Damaged file")
                return bytes.copyOfRange(position.toInt(), position.toInt() + length)
            }
        }
    }
}
