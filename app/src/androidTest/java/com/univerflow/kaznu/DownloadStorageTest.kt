package com.univerflow.kaznu

import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.download.PublicDownloadStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DownloadStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

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
}
