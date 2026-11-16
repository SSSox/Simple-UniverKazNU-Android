package com.univerflow.kaznu.web

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

data class DownloadIdentity(val name: String, val mime: String)

/** File signatures lead over server labels. Package inspection reads names only, never extracts or executes entries. */
object DownloadTypes {
    private const val INSPECT_LIMIT = 8 * 1024 * 1024
    private val types = mapOf(
        "pdf" to "application/pdf", "doc" to "application/msword", "dot" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "xls" to "application/vnd.ms-excel", "xlt" to "application/vnd.ms-excel", "ppt" to "application/vnd.ms-powerpoint", "pps" to "application/vnd.ms-powerpoint",
        "odt" to "application/vnd.oasis.opendocument.text", "ods" to "application/vnd.oasis.opendocument.spreadsheet", "odp" to "application/vnd.oasis.opendocument.presentation",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "bmp" to "image/bmp", "webp" to "image/webp",
        "rtf" to "application/rtf", "txt" to "text/plain", "csv" to "text/csv", "zip" to "application/zip", "rar" to "application/vnd.rar", "7z" to "application/x-7z-compressed",
        "docm" to "application/vnd.ms-word.document.macroenabled.12", "xlsm" to "application/vnd.ms-excel.sheet.macroenabled.12", "pptm" to "application/vnd.ms-powerpoint.presentation.macroenabled.12",
        "dotx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.template", "dotm" to "application/vnd.ms-word.template.macroenabled.12",
        "xltx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.template", "xltm" to "application/vnd.ms-excel.template.macroenabled.12",
        "pot" to "application/vnd.ms-powerpoint", "potx" to "application/vnd.openxmlformats-officedocument.presentationml.template", "potm" to "application/vnd.ms-powerpoint.template.macroenabled.12",
        "ppsx" to "application/vnd.openxmlformats-officedocument.presentationml.slideshow", "ppsm" to "application/vnd.ms-powerpoint.slideshow.macroenabled.12",
        "mp4" to "video/mp4", "m4v" to "video/x-m4v", "webm" to "video/webm", "mkv" to "video/x-matroska", "avi" to "video/x-msvideo", "mov" to "video/quicktime",
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "m4a" to "audio/mp4", "flac" to "audio/flac", "gz" to "application/gzip", "tar" to "application/x-tar", "epub" to "application/epub+zip"
    )
    private val wordPackages = setOf("docx", "docm", "dotx", "dotm")
    private val sheetPackages = setOf("xlsx", "xlsm", "xltx", "xltm")
    private val slidePackages = setOf("pptx", "pptm", "ppsx", "ppsm", "potx", "potm")
    private val zipTypes = wordPackages + sheetPackages + slidePackages + setOf("odt", "ods", "odp", "zip", "epub")
    private val oleTypes = setOf("doc", "dot", "xls", "xlt", "ppt", "pps", "pot")

    private fun starts(bytes: ByteArray, vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 255) == prefix[it] }
    private fun magic(bytes: ByteArray): String? = when {
        starts(bytes, 0x25, 0x50, 0x44, 0x46) -> "pdf"
        starts(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "png"
        starts(bytes, 0xff, 0xd8, 0xff) -> "jpg"
        starts(bytes, 0x47, 0x49, 0x46, 0x38) -> "gif"
        starts(bytes, 0x42, 0x4d) -> "bmp"
        starts(bytes, 0x52, 0x49, 0x46, 0x46) && bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        starts(bytes, 0x7b, 0x5c, 0x72, 0x74, 0x66) -> "rtf"
        starts(bytes, 0x52, 0x61, 0x72, 0x21, 0x1a, 7) -> "rar"
        starts(bytes, 0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c) -> "7z"
        starts(bytes, 0x50, 0x4b, 3, 4) || starts(bytes, 0x50, 0x4b, 5, 6) -> "zip"
        starts(bytes, 0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1) -> "ole"
        starts(bytes, 0x4d, 0x5a) || starts(bytes, 0x7f, 0x45, 0x4c, 0x46) -> "bin"
        else -> null
    }

    fun resolve(name: String, mime: String?, prefix: ByteArray, inspected: String? = null): DownloadIdentity {
        val current = DownloadFileNames.extension(name)
        val fromMime = types.entries.firstOrNull { it.value.equals(mime?.substringBefore(';')?.trim(), true) }?.key
        val signature = magic(prefix)
        val packageExtension = when {
            inspected == "docx" && current in wordPackages -> current
            inspected == "xlsx" && current in sheetPackages -> current
            inspected == "pptx" && current in slidePackages -> current
            inspected == "doc" && current == "dot" -> current
            inspected == "xls" && current == "xlt" -> current
            inspected == "ppt" && current in setOf("pps", "pot") -> current
            else -> inspected
        }
        val extension = when (signature) {
            "zip" -> packageExtension ?: current.takeIf { it in zipTypes } ?: fromMime?.takeIf { it in zipTypes } ?: "zip"
            "ole" -> packageExtension ?: current.takeIf { it in oleTypes } ?: fromMime?.takeIf { it in oleTypes } ?: "bin"
            null -> current.takeIf { it in types } ?: fromMime ?: "bin"
            else -> signature
        }
        val stem = if (current.isEmpty()) name else name.dropLast(current.length + 1)
        return DownloadIdentity(DownloadFileNames.safe("$stem.$extension"), types[extension] ?: "application/octet-stream")
    }

    fun inspect(prefix: ByteArray, open: () -> InputStream, checkActive: () -> Unit = {}): String? = when (magic(prefix)) {
        "zip" -> inspectZip(open, checkActive)
        "ole" -> inspectOle(open, checkActive)
        else -> null
    }

    private fun inspectZip(open: () -> InputStream, checkActive: () -> Unit): String? {
        return try {
        ZipInputStream(open().buffered()).use { zip ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            repeat(256) {
                checkActive()
                val entry = zip.nextEntry ?: return null
                when (entry.name.lowercase(Locale.ROOT)) {
                    "word/document.xml" -> return "docx"
                    "xl/workbook.xml" -> return "xlsx"
                    "ppt/presentation.xml" -> return "pptx"
                }
                val mimetype = if (entry.name == "mimetype") ByteArrayOutputStream() else null
                while (total < INSPECT_LIMIT) {
                    checkActive()
                    val count = zip.read(buffer, 0, minOf(buffer.size, INSPECT_LIMIT - total))
                    if (count < 0) break
                    total += count
                    if (mimetype != null && mimetype.size() < 256) mimetype.write(buffer, 0, minOf(count, 256 - mimetype.size()))
                }
                if (mimetype != null) types.entries.firstOrNull { it.key in setOf("odt", "ods", "odp") && it.value == mimetype.toString("UTF-8").trim() }?.let { return it.key }
                if (total >= INSPECT_LIMIT) return null
            }
            null
        }
        } catch (_: IOException) { null }
    }

    private fun inspectOle(open: () -> InputStream, checkActive: () -> Unit): String? = try {
        val bytes = ByteArrayOutputStream()
        open().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (bytes.size() < INSPECT_LIMIT) {
                checkActive()
                val count = input.read(buffer, 0, minOf(buffer.size, INSPECT_LIMIT - bytes.size()))
                if (count < 0) break
                bytes.write(buffer, 0, count)
            }
        }
        val directory = bytes.toString("UTF-16LE")
        when {
            "WordDocument" in directory -> "doc"
            "Workbook" in directory || "Book\u0000" in directory -> "xls"
            "PowerPoint Document" in directory -> "ppt"
            else -> null
        }
    } catch (_: IOException) { null }
}
