package com.univerflow.kaznu.data

import android.content.Context
import android.util.AtomicFile
import com.univerflow.kaznu.domain.DownloadFailure
import com.univerflow.kaznu.domain.DownloadHistoryRules
import com.univerflow.kaznu.domain.DownloadRecord
import com.univerflow.kaznu.domain.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** JSON codec for the local download list. Unknown or damaged entries are skipped, not trusted. */
object DownloadHistoryCodec {
    private val KEY = Regex("[0-9a-f]{32}")
    fun encode(records: List<DownloadRecord>): String = JSONArray().apply {
        records.forEach { record ->
            put(JSONObject().put("id", record.id).put("fileName", record.fileName).put("uri", record.uri ?: JSONObject.NULL)
                .put("mime", record.mime ?: JSONObject.NULL).put("state", record.state.name).put("received", record.received)
                .put("total", record.total).put("started", record.started).put("finished", record.finished ?: JSONObject.NULL)
                .put("source", record.source).put("failure", record.failure?.name ?: JSONObject.NULL).put("key", record.key)
                .put("place", record.place ?: JSONObject.NULL))
        }
    }.toString()

    fun decode(text: String): List<DownloadRecord> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                val uri = item.optString("uri").takeIf { !item.isNull("uri") && it.startsWith("content://") }
                DownloadRecord(
                    id = item.getString("id").also { require(it.isNotBlank() && it.length <= 64) },
                    fileName = item.optString("fileName").take(255),
                    uri = uri,
                    mime = item.optString("mime").takeIf { !item.isNull("mime") && it.length <= 128 },
                    state = DownloadState.valueOf(item.getString("state")),
                    received = item.optLong("received", 0),
                    total = item.optLong("total", -1),
                    started = item.getLong("started"),
                    finished = if (item.isNull("finished")) null else item.optLong("finished"),
                    source = item.optString("source").take(200),
                    failure = if (item.isNull("failure")) null else runCatching { DownloadFailure.valueOf(item.getString("failure")) }.getOrNull(),
                    key = item.optString("key").takeIf { it.matches(KEY) }.orEmpty(),
                    place = item.optString("place").takeIf { !item.isNull("place") && it.isNotEmpty() && it.length <= 1024 },
                )
            }.getOrNull()
        }
    }
}

/**
 * Process-wide list of downloads shared by the service and the screens. Progress lives in memory;
 * state changes are written atomically to no-backup storage in order.
 */
class DownloadRepository private constructor(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "downloads.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Mutex()
    private val mutable = MutableStateFlow<List<DownloadRecord>>(emptyList())
    private val loadedState = MutableStateFlow(false)
    val records: StateFlow<List<DownloadRecord>> = mutable.asStateFlow()
    val loaded: StateFlow<Boolean> = loadedState.asStateFlow()

    init {
        scope.launch {
            val saved = try { DownloadHistoryCodec.decode(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() } // A missing or damaged list starts empty; files stay in Downloads.
            val restored = DownloadHistoryRules.restore(saved, System.currentTimeMillis())
            // Transfers that began before the list finished loading stay in front of the restored history.
            mutable.update { current -> (current + restored.filter { old -> current.none { it.id == old.id } }).take(DownloadHistoryRules.LIMIT) }
            loadedState.value = true
            persist()
        }
    }

    suspend fun awaitLoaded() { loaded.first { it } }

    fun find(id: String?): DownloadRecord? = id?.let { wanted -> records.value.firstOrNull { it.id == wanted } }

    /** [persistNow] is false for progress-only updates, which would otherwise rewrite the file continuously. */
    fun upsert(record: DownloadRecord, persistNow: Boolean = true) {
        mutable.update { DownloadHistoryRules.upsert(it, record) }
        if (persistNow) persist()
    }

    fun update(id: String, persistNow: Boolean = true, change: (DownloadRecord) -> DownloadRecord) {
        var changed = false
        mutable.update { list -> list.map { if (it.id == id) { changed = true; change(it) } else it } }
        if (changed && persistNow) persist()
    }

    fun remove(id: String) {
        mutable.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    /** Drops the records of files that left their place, each only if it still points at the same file; one write. */
    fun removeGone(gone: Collection<DownloadRecord>) {
        if (gone.isEmpty()) return
        val wanted = gone.associate { it.id to it.uri }
        var changed = false
        mutable.update { list -> list.filterNot { it.id in wanted && it.state == DownloadState.COMPLETED && it.uri == wanted[it.id] }.also { changed = it.size != list.size } }
        if (changed) persist()
    }

    /** Records the current place of files saved before places were kept (id to place); one write. */
    fun keepPlaces(places: Map<String, String>) {
        if (places.isEmpty()) return
        var changed = false
        mutable.update { list -> list.map { record -> places[record.id]?.takeIf { record.place == null }?.let { changed = true; record.copy(place = it) } ?: record } }
        if (changed) persist()
    }

    private fun persist() {
        scope.launch {
            writes.withLock {
                if (!loadedState.value) return@withLock // The initial load persists the merged list itself.
                val text = DownloadHistoryCodec.encode(records.value)
                val output = file.startWrite()
                try { output.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
                catch (cancelled: CancellationException) { file.failWrite(output); throw cancelled }
                catch (_: Exception) { file.failWrite(output) } // The list is a convenience; the files are already saved.
            }
        }
    }

    companion object {
        @Volatile private var instance: DownloadRepository? = null
        fun get(context: Context): DownloadRepository = instance ?: synchronized(this) {
            instance ?: DownloadRepository(context.applicationContext).also { instance = it }
        }
    }
}
