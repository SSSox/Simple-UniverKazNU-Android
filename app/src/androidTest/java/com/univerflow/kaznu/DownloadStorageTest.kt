package com.univerflow.kaznu

import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.download.DownloadService
import com.univerflow.kaznu.download.PublicDownloadStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DownloadStorageTest {
    private fun grantLegacyStorage() {
        // Android's storage dialog grants the group. pm grant writes only the named permission; on API 24 a
        // WRITE-only shell grant leaves the process without the READ mount needed to traverse Downloads.
        listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE).forEach { permission ->
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission")
                .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { output -> output.readBytes() } }
            org.junit.Assume.assumeTrue(context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
    }
    @Test fun detectedFileTypesAndEncodedServerNamesWorkOnTheMinimumAndroidRuntime() {
        assertEquals("Курс.pdf", com.univerflow.kaznu.web.DownloadFileNames.fromDisposition("attachment; filename=\"=?UTF-8?B?0JrRg9GA0YEucGRm?=\""))
        val actual = com.univerflow.kaznu.web.DownloadTypes.resolve("material.exe", "application/octet-stream", "%PDF-fixture".toByteArray())
        assertEquals("material.pdf", actual.name)
        assertEquals("application/pdf", actual.mime)
    }
    @Test fun completedContentCanBeInspectedAndPublishedWithItsDetectedNameAndMime() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 29)
        val stem = "Univer-QA-${UUID.randomUUID()}"
        val uri = PublicDownloadStore(context).create("$stem.bin", "application/octet-stream").use { target ->
            target.output.write("%PDF-fixture".toByteArray())
            assertEquals("%PDF-fixture", target.openInput().use { it.readBytes().toString(Charsets.UTF_8) })
            target.publish("$stem.pdf", "application/pdf")
        }
        try {
            context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.MIME_TYPE), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals("$stem.pdf", it.getString(0)); assertEquals("application/pdf", it.getString(1))
            }
        } finally { context.contentResolver.delete(uri, null, null) }
    }
    @Test fun courseMaterialsUseTheValidatedCourseAndSectionFolder() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 29)
        val relative = com.univerflow.kaznu.domain.StudyFolders.relative("Алгоритмы", "Силлабус")
        val name = "Univer-QA-${UUID.randomUUID()}.pdf"
        val uri = PublicDownloadStore(context).create(name, "application/pdf", relative).use { target ->
            target.output.write("%PDF-course".toByteArray()); target.publish()
        }
        try {
            context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.RELATIVE_PATH), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals("Download/$relative/", it.getString(0))
            }
        } finally { context.contentResolver.delete(uri, null, null) }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun aDownloadRefusedByTheServiceDoesNotEndTheApp() {
        // The session ended between the tap and the start: the epoch no longer matches.
        androidx.core.content.ContextCompat.startForegroundService(context, android.content.Intent(context, DownloadService::class.java)
            .putExtra("url", "https://univer.kaznu.kz/student/umkd/get/1/1/2026/1").putExtra("epoch", -1))
        Thread.sleep(1_500) // A violation would crash this process here and fail the run.
    }

    @Test fun completedDownloadsKeepServerNamesAndDoNotOverwriteDuplicateFiles() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 29)
        val store = PublicDownloadStore(context)
        val name = "Univer-QA-${UUID.randomUUID()}-Қазақша.pdf"
        val first = store.create(name, "application/pdf").use { file -> file.output.write("%PDF-first".toByteArray()); file.publish() }
        val second = store.create(name, "application/pdf").use { file -> file.output.write("%PDF-second".toByteArray()); file.publish() }
        try {
            assertNotEquals(first, second)
            assertEquals("%PDF-first", context.contentResolver.openInputStream(first)!!.use { it.readBytes().toString(Charsets.UTF_8) })
            assertEquals("%PDF-second", context.contentResolver.openInputStream(second)!!.use { it.readBytes().toString(Charsets.UTF_8) })
            context.contentResolver.query(first, arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(name, it.getString(0)); assertEquals(0, it.getInt(1))
            }
        } finally { context.contentResolver.delete(first, null, null); context.contentResolver.delete(second, null, null) }
    }
    @Test fun failedOrCancelledDownloadLeavesNoVisibleOrPendingRecord() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 29)
        val name = "Univer-QA-${UUID.randomUUID()}.pdf"
        PublicDownloadStore(context).create(name, "application/pdf").use { file -> file.output.write("partial".toByteArray()) }
        context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(name), null)!!.use { assertEquals(0, it.count) }
    }
    @Suppress("DEPRECATION") // The public Downloads folder path and MediaColumns.DATA are the API 24-28 storage model.
    @Test fun legacyDownloadIsIndexedSoOtherAppsAndUsbSeeIt() {
        org.junit.Assume.assumeTrue("API 29+ saves through MediaStore.Downloads", Build.VERSION.SDK_INT < 29)
        grantLegacyStorage()
        val name = "Univer-QA-${UUID.randomUUID()}.pdf"
        val file = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), name)
        val files = MediaStore.Files.getContentUri("external")
        val byPath = "${MediaStore.MediaColumns.DATA}=?"
        try {
            PublicDownloadStore(context).create(name, "application/pdf").use { target ->
                target.output.write("%PDF-legacy".toByteArray())
                target.publish()
            }
            assertTrue(file.isFile)
            // The scan is asynchronous: wait a bounded time for the MediaStore row that MTP and other apps read.
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            var indexed = false
            while (!indexed && android.os.SystemClock.uptimeMillis() < deadline) {
                indexed = context.contentResolver.query(files, arrayOf(android.provider.BaseColumns._ID), byPath, arrayOf(file.absolutePath), null)
                    ?.use { it.count > 0 } == true
                if (!indexed) Thread.sleep(200)
            }
            assertTrue("The saved file is in MediaStore", indexed)
        } finally {
            runCatching { context.contentResolver.delete(files, byPath, arrayOf(file.absolutePath)) }
            file.delete()
        }
    }

    @Suppress("DEPRECATION") // The public Downloads folder is the API 24-28 storage model.
    @Test fun aPartialFileLeftByAKilledProcessIsRemovedByALaterDownload() {
        org.junit.Assume.assumeTrue("API 29+ keeps partial files as pending MediaStore rows", Build.VERSION.SDK_INT < 29)
        grantLegacyStorage()
        val folder = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
        // Nothing deletes this one: the process that wrote it is gone.
        val left = java.io.File.createTempFile(".univer-", ".part", folder).apply { writeText("partial") }
        val unrelated = java.io.File(folder, ".univer-notes.part").apply { writeText("keep") }
        val name = "Univer-QA-${UUID.randomUUID()}.pdf"
        try {
            // A download running in another process the same day keeps its file.
            PublicDownloadStore(context).create(name, "application/pdf").use { it.output.write(1) }
            assertTrue(left.isFile)
            val twoDaysOn = { System.currentTimeMillis() + 2 * 86_400_000L }
            PublicDownloadStore(context, twoDaysOn).create(name, "application/pdf").use { it.output.write(1) }
            assertFalse("A partial file older than a day is removed", left.exists())
            assertEquals("A similarly named user file is not app-owned", "keep", unrelated.readText())
        } finally {
            left.delete()
            unrelated.delete()
        }
    }
}
