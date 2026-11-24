package com.univerflow.kaznu

import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.net.toUri
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.data.DownloadHistoryCodec
import com.univerflow.kaznu.data.DownloadRepository
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.domain.DownloadRecord
import com.univerflow.kaznu.domain.DownloadState
import com.univerflow.kaznu.domain.FilePresence
import com.univerflow.kaznu.domain.StudyFolders
import com.univerflow.kaznu.download.PublicDownloadStore
import com.univerflow.kaznu.ui.DownloadsActivity
import com.univerflow.kaznu.ui.FileActions
import com.univerflow.kaznu.web.DownloadBatch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * «Скачать все» and the Downloads list on real shared storage: synthetic files in a QA folder of Downloads, moved, renamed
 * and deleted the way a file manager does it (through the file system, which MediaStore follows). Everything is deleted.
 */
@RunWith(AndroidJUnit4::class)
class SectionDownloadQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repository get() = DownloadRepository.get(context)
    private val folder = StudyFolders.relative("QA курс", "Лекции")
    private val leftovers = mutableListOf<File>()
    private val uris = mutableListOf<String>()
    private val records = mutableListOf<String>()

    @After fun cleanUp() {
        records.forEach { repository.remove(it) }
        uris.forEach { runCatching { context.contentResolver.delete(it.toUri(), null, null) } }
        leftovers.forEach { it.delete() }
    }

    @Suppress("DEPRECATION")
    private val downloads get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    private fun saved(): DownloadRecord {
        val name = "Univer-QA-${UUID.randomUUID()}.pdf"
        val uri = PublicDownloadStore(context).create(name, "application/pdf", folder).use { it.output.write("%PDF-qa".toByteArray()); it.publish() }
        uris += uri.toString()
        return DownloadRecord("qa-" + UUID.randomUUID(), name, uri.toString(), "application/pdf", DownloadState.COMPLETED, 7, 7,
            System.currentTimeMillis(), System.currentTimeMillis(), "QA курс", place = FileActions.place(context, uri))
    }

    private fun waitFor(message: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(200) }
        throw AssertionError(message)
    }

    @Test fun aFileMovedRenamedOrDeletedElsewhereIsGoneAndAnUnclearOneIsUnknown() {
        assumeTrue("Apps reach their own shared files by path from Android 11", Build.VERSION.SDK_INT >= 30)
        val moved = saved(); val renamed = saved(); val deleted = saved()
        assertEquals("Download/$folder/${moved.fileName}", moved.place)
        listOf(moved, renamed, deleted).forEach { assertEquals(it.fileName, FilePresence.PRESENT, FileActions.locate(context, it)) }

        val base = File(downloads, folder)
        val movedTo = File(downloads, moved.fileName).also { leftovers += it }
        val renamedTo = File(base, "renamed-" + renamed.fileName).also { leftovers += it }
        assertTrue("moved", File(base, moved.fileName).renameTo(movedTo))
        assertTrue("renamed", File(base, renamed.fileName).renameTo(renamedTo))
        assertTrue("deleted", File(base, deleted.fileName).delete())

        waitFor("MediaStore notices the changes") { listOf(moved, renamed, deleted).all { FileActions.locate(context, it) == FilePresence.GONE } }
        // What MediaStore does with a moved file decides whether the content URI alone could tell: logged for the report.
        Log.i("SectionDownloadQa", "moved: row ${FileActions.place(context, moved.uri!!.toUri()) ?: "gone"}; renamed: row ${FileActions.place(context, renamed.uri!!.toUri()) ?: "gone"}")
        assertEquals(FilePresence.UNKNOWN, FileActions.locate(context, moved.copy(uri = null)))
        assertEquals(FilePresence.GONE, FileActions.locate(context, moved.copy(uri = "content://media/external/downloads/2147483000")))
    }

    @Test fun theDownloadsScreenDropsFilesThatLeftTheirPlaceAndKeepsTheRest() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        runBlocking { repository.awaitLoaded() }
        val kept = saved(); val gone = saved()
        // Saved before places were kept: the screen takes the place it has now.
        val old = saved().copy(place = null)
        val unclear = DownloadRecord("qa-" + UUID.randomUUID(), "Без адреса QA.pdf", null, "application/pdf", DownloadState.COMPLETED, 7, 7, System.currentTimeMillis())
        listOf(kept, gone, old, unclear).forEach { records += it.id; repository.upsert(it, persistNow = false) }
        assertTrue(File(File(downloads, folder), gone.fileName).delete())
        ActivityScenario.launch<DownloadsActivity>(DownloadsActivity.intent(context, AppLanguage.RUSSIAN)).use { scenario ->
            waitFor("the deleted file leaves the list after the second check") { repository.find(gone.id) == null }
            assertEquals(kept.place, repository.find(kept.id)?.place)
            assertEquals("Download/$folder/${old.fileName}", repository.find(old.id)?.place)
            assertTrue("a file with no answer stays", repository.find(unclear.id) != null)
            var shown = 0
            scenario.onActivity { shown = it.findViewById<RecyclerView>(R.id.downloads_list).adapter!!.itemCount }
            assertTrue("the list still shows the kept files: $shown", shown >= 3)
        }
    }

    @Test fun theHistoryKeepsThePlaceAndTheSectionMessageIsReadWithTheRealParser() {
        val record = DownloadRecord("qa-1", "a.pdf", "content://media/external/downloads/5", "application/pdf", DownloadState.COMPLETED, started = 1, place = "Download/a.pdf")
        assertEquals("Download/a.pdf", DownloadHistoryCodec.decode(DownloadHistoryCodec.encode(listOf(record))).single().place)
        assertNull(DownloadHistoryCodec.decode(DownloadHistoryCodec.encode(listOf(record.copy(place = null)))).single().place)
        val url = "https://univer.kaznu.kz/student/umkd/get/9002/321/2026/1"
        val batch = DownloadBatch.parse(DownloadBatch.MESSAGE_PREFIX + JSONObject().put("section", " Лекции ").put("head", 2).put("count", 3).put("mark", 7).put("urls", JSONArray(listOf(url, url))))!!
        assertEquals("Лекции", batch.section)
        assertEquals(listOf(url), batch.urls)
        assertEquals(DownloadBatch.Tap(2, 3, 7), batch.tap)
        assertNull("a message without the heading count or the channel's mark", DownloadBatch.parse(DownloadBatch.MESSAGE_PREFIX + JSONObject().put("section", "Лекции").put("head", 2).put("urls", JSONArray(listOf(url)))))
        assertNull("a message without its heading number", DownloadBatch.parse(DownloadBatch.MESSAGE_PREFIX + JSONObject().put("section", "Лекции").put("urls", JSONArray(listOf(url)))))
        assertNull(DownloadBatch.parse(DownloadBatch.MESSAGE_PREFIX + "{\"section\":\"Лекции\",\"urls\":\"$url\"}"))
        assertNull(DownloadBatch.parse(DownloadBatch.MESSAGE_PREFIX + JSONObject().put("section", "Лекции").put("urls", JSONArray(listOf(url, "https://univer.kaznu.kz/user/logout")))))
        assertNull(DownloadBatch.parse(DownloadBatch.MESSAGE_PREFIX + "not json"))
        assertNull(DownloadBatch.parse(DownloadBatch.MESSAGE_PREFIX + "x".repeat(70_000)))
    }
}
