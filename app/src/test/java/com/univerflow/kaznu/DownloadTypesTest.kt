package com.univerflow.kaznu

import com.univerflow.kaznu.web.DownloadFileNames
import com.univerflow.kaznu.web.DownloadTypes
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.encoding.Base64

class DownloadTypesTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { archive -> entries.forEach { (name, bytes) -> archive.putNextEntry(ZipEntry(name)); archive.write(bytes); archive.closeEntry() } }
    }.toByteArray()

    @Test fun signaturesFixMissingAndWrongExtensionsWhileDottedTitlesStayWhole() {
        val pdf = "%PDF-1.7 fixture".toByteArray()
        assertEquals("Лекция 1. Введение.pdf", DownloadTypes.resolve("Лекция 1. Введение", null, pdf).name)
        val identity = DownloadTypes.resolve("incorrect.xlsx", "application/vnd.ms-excel", pdf)
        assertEquals("incorrect.pdf", identity.name)
        assertEquals("application/pdf", identity.mime)
        assertEquals("program.bin", DownloadTypes.resolve("program.exe", "application/octet-stream", byteArrayOf(77, 90, 0, 0)).name)
        assertEquals("unknown.bin", DownloadTypes.resolve("unknown.cmd", null, byteArrayOf(1, 2, 3)).name)
    }

    @Test fun officePackagesAndOpenDocumentAreIdentifiedByTheirActualEntries() {
        listOf("word/document.xml" to "docx", "xl/workbook.xml" to "xlsx", "ppt/presentation.xml" to "pptx").forEach { (entry, extension) ->
            val bytes = zip(entry to "<fixture/>".toByteArray())
            val detected = DownloadTypes.inspect(bytes.take(4096).toByteArray(), { ByteArrayInputStream(bytes) })
            assertEquals(extension, detected)
            assertEquals("material.$extension", DownloadTypes.resolve("material", null, bytes, detected).name)
        }
        val odt = zip("mimetype" to "application/vnd.oasis.opendocument.text".toByteArray())
        assertEquals("odt", DownloadTypes.inspect(odt, { ByteArrayInputStream(odt) }))
        val macro = zip("word/document.xml" to byteArrayOf())
        assertEquals("template.docm", DownloadTypes.resolve("template.docm", null, macro, "docx").name)
        assertEquals("template.dotx", DownloadTypes.resolve("template.dotx", null, macro, "docx").name)
        assertEquals("lecture.mp4", DownloadTypes.resolve("lecture.mp4", "video/mp4", byteArrayOf(0, 0, 0, 24)).name)
        val apk = zip("AndroidManifest.xml" to byteArrayOf())
        assertEquals("download.zip", DownloadTypes.resolve("download.apk", null, apk).name)
    }

    @Test fun packageInspectionHasAnInflationLimitAndNeverSwallowsCancellation() {
        val bytes = zip("padding" to ByteArray(9 * 1024 * 1024), "word/document.xml" to byteArrayOf())
        assertNull(DownloadTypes.inspect(bytes.take(4096).toByteArray(), { ByteArrayInputStream(bytes) }))
        var checks = 0
        assertThrows(CancellationException::class.java) {
            DownloadTypes.inspect(bytes.take(4096).toByteArray(), { ByteArrayInputStream(bytes) }) { if (++checks == 3) throw CancellationException() }
        }
        assertEquals(3, checks)
    }

    @Test fun legacyCompoundOfficeReadsStreamNamesWithoutTrustingTheHeader() {
        val bytes = byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte()) +
            ByteArray(5000) + "Workbook\u0000".toByteArray(Charsets.UTF_16LE)
        val kind = DownloadTypes.inspect(bytes.take(4096).toByteArray(), { ByteArrayInputStream(bytes) })
        assertEquals("xls", kind)
        assertEquals("sheet.xls", DownloadTypes.resolve("sheet.doc", null, bytes, kind).name)
    }

    @Test fun serverNamesDecodeCharsetsMimeWordsAndLegacyUtf8Headers() {
        assertEquals("_CON.pdf", DownloadFileNames.safe("CON.pdf"))
        assertEquals("café.pdf", DownloadFileNames.fromDisposition("attachment; filename*=ISO-8859-1''caf%E9.pdf"))
        assertEquals("Курс.pdf", DownloadFileNames.fromDisposition("attachment; filename=\"=?UTF-8?B?${Base64.encode("Курс.pdf".toByteArray())}?=\""))
        assertEquals("Курс.pdf", DownloadFileNames.fromDisposition("attachment; filename=\"=?UTF-8?Q?=D0=9A=D1=83=D1=80=D1=81.pdf?=\""))
        val legacy = String("Курс.pdf".toByteArray(), Charsets.ISO_8859_1)
        assertEquals("Курс.pdf", DownloadFileNames.fromDisposition("attachment; filename=\"$legacy\""))
        assertNull(DownloadFileNames.fromDisposition("attachment; filename=\"\""))
        assertEquals("Univer-document", DownloadFileNames.fromUrl("https://univer.kaznu.kz/student/umkd/get/1/2/2026/1"))
        assertEquals("course+1.pdf", DownloadFileNames.fromUrl("https://univer.kaznu.kz/files/course+1.pdf"))
    }
}
