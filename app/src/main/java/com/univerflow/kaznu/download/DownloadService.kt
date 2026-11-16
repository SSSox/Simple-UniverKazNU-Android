package com.univerflow.kaznu.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.univerflow.kaznu.MainActivity
import com.univerflow.kaznu.R
import com.univerflow.kaznu.domain.AppLanguage
import com.univerflow.kaznu.data.DownloadRepository
import com.univerflow.kaznu.ui.LanguageResources
import com.univerflow.kaznu.domain.DownloadFailure
import com.univerflow.kaznu.domain.DownloadKeys
import com.univerflow.kaznu.domain.DownloadRecord
import com.univerflow.kaznu.domain.DownloadState
import com.univerflow.kaznu.ui.FileActions
import com.univerflow.kaznu.ui.FileViewerActivity
import com.univerflow.kaznu.web.DownloadAccessDenied
import com.univerflow.kaznu.web.DownloadFileNames
import com.univerflow.kaznu.web.DownloadIsLink
import com.univerflow.kaznu.web.DownloadNeedsLogin
import com.univerflow.kaznu.web.DownloadTooLarge
import com.univerflow.kaznu.web.DownloadTypes
import com.univerflow.kaznu.web.DownloadProbe
import com.univerflow.kaznu.web.SingleSignOnPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

/**
 * [loginUrl] is set only when an ended session refused the file, so it can be fetched again after signing in; [batch] then
 * names its «Скачать все» run. [link] is a course material that turned out to be a teacher's link: the screen opens it
 * once instead of showing a message.
 */
data class DownloadEvent(val id: String, val message: Int, val uri: Uri? = null, val mime: String? = null, val recordId: String? = null,
    val loginUrl: String? = null, val link: String? = null, val batch: String? = null)

/**
 * One running transfer of record [recordId]. Cancelling [job] does not interrupt a connect or read blocked on the network,
 * so [cancel] also hands the current connection to `disconnect`, which closes it off the main thread.
 */
internal class Transfer(val recordId: String, val job: Job, private val connection: AtomicReference<HttpURLConnection?>) {
    fun cancel(disconnect: (HttpURLConnection) -> Unit) {
        // Cancel first: a connection set after this point fails the transfer's own active() check instead.
        job.cancel()
        connection.getAndSet(null)?.let(disconnect)
    }
}

/** Foreground, bounded streaming. Requests/credentials are never persisted, logged or redelivered. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val parallel = Semaphore(2)
    private val transfers = mutableMapOf<String, Transfer>()
    /** «Скачать все» batches whose files the server asked a sign-in for: the rest of each is not requested. */
    private val refusedBatches = mutableSetOf<String>()
    private val connections = mutableSetOf<AtomicReference<HttpURLConnection?>>()
    private lateinit var notifications: NotificationManager
    private lateinit var history: DownloadRepository
    private var foreground = false
    private var closing = false
    private var startSequence = 0
    private var language = AppLanguage.RUSSIAN
    private val labels = LanguageResources(this)

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        history = DownloadRepository.get(this)
        if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.downloads), NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startSequence = startId
        if (intent?.action == ACTION_CANCEL) { cancel(intent.getStringExtra("id"), startId); return START_NOT_STICKY }
        // Read first: the first foreground notification already uses the app's language.
        intent?.getStringExtra("language")?.let { language = AppLanguage.fromCode(it) }
        // Android ends the app if a service started for the foreground stops without entering it, even to refuse the work.
        enterForeground()
        val url = intent?.getStringExtra("url")
        if (!SingleSignOnPolicy.downloadAllowed(url) || intent?.getIntExtra("epoch", -1) != epoch) {
            if (transfers.isEmpty()) leaveForeground(startId)
            return START_NOT_STICKY
        }
        if (transfers.containsKey(url)) return START_NOT_STICKY
        val requestEpoch = epoch
        val recordId = intent.getStringExtra("id") ?: UUID.randomUUID().toString()
        history.upsert(DownloadRecord(recordId, started = System.currentTimeMillis(),
            source = intent.getStringExtra("source").orEmpty().take(200), key = DownloadKeys.of(url!!)))
        start(Request(url, intent.getStringExtra("agent").orEmpty(), intent.getStringExtra("referer"),
            intent.getStringExtra("disposition"), intent.getStringExtra("mime"), requestEpoch, intent.getStringExtra("folder"),
            intent.getStringExtra("batch")?.takeIf { it.length <= 64 }), recordId)
        return START_NOT_STICKY
    }

    /** Cancels the transfer of record [id]; a record left running by an ended transfer is closed without a network request. */
    private fun cancel(id: String?, startId: Int) {
        val transfer = transfers.values.firstOrNull { it.recordId == id }
        // Not the service scope: a disconnect queued just before onDestroy must still run.
        if (transfer != null) transfer.cancel { open -> CoroutineScope(Dispatchers.IO).launch { open.disconnect() } }
        else if (id != null) history.update(id) { if (it.state == DownloadState.RUNNING) it.copy(state = DownloadState.CANCELED,
            finished = System.currentTimeMillis()) else it }
        if (transfers.isEmpty()) stopSelfResult(startId)
    }

    private fun enterForeground() {
        if (foreground) return
        ServiceCompat.startForeground(this, ACTIVE_NOTIFICATION, notification(R.string.download_preparing).build(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        foreground = true
    }

    private fun leaveForeground(startId: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelfResult(startId)
    }

    /** Runs [request] within the parallel limit; the service leaves the foreground with its last transfer. */
    private fun start(request: Request, recordId: String) {
        val url = request.url
        val connection = AtomicReference<HttpURLConnection?>()
        connections.add(connection)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                parallel.withPermit {
                    if (request.batch != null && request.batch in refusedBatches) history.remove(recordId)
                    else withContext(Dispatchers.IO) { transfer(request, recordId, connection) }
                }
            } catch (cancelled: CancellationException) {
                history.update(recordId) { it.copy(state = DownloadState.CANCELED, finished = System.currentTimeMillis()) }
                throw cancelled
            } catch (failure: Exception) {
                // A cancel that closed a stalled connection ends the call with an I/O error, not a CancellationException.
                if (!isActive && !closing) history.update(recordId) { it.copy(state = DownloadState.CANCELED,
                    finished = System.currentTimeMillis()) }
                else failed(request, recordId, failure)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { connection.getAndSet(null)?.disconnect() }
                connections.remove(connection)
                transfers.remove(url)
                if (transfers.isEmpty() && !closing) leaveForeground(startSequence)
            }
        }
        transfers[url] = Transfer(recordId, job, connection)
        job.start()
    }

    /** What a start intent asks for. It lives only in memory for the transfer: never saved, logged or redelivered. */
    private class Request(val url: String, val agent: String, val referer: String?, val disposition: String?, val mime: String?, val epoch: Int, val folder: String?,
        val batch: String? = null)

    /**
     * One validated response streamed into public Downloads with throttled progress (the record every 250 ms,
     * the notification every 750 ms); the record completes on the main thread unless the session ended meanwhile.
     */
    private suspend fun transfer(request: Request, recordId: String, connection: AtomicReference<HttpURLConnection?>) {
        val coroutine = currentCoroutineContext()
        val cookieManager = CookieManager.getInstance()
        fun active() { coroutine.ensureActive(); check(request.epoch == epoch) }
        active()
        DownloadProbe().open(request.url, request.agent, request.referer, cookieManager::getCookie, { origin, value ->
            // Serialize cookie writes with logout's removeAllCookies on the UI thread, without blocking this coroutine.
            withContext(Dispatchers.Main.immediate) { active(); cookieManager.setCookie(origin, value) }
        }, { connection.set(it); active() }).use { response ->
            val metadata = response.metadata
            val originalName = DownloadFileNames.fromDisposition(metadata.disposition ?: request.disposition) ?: DownloadFileNames.fromUrl(metadata.url)
            val identity = DownloadTypes.resolve(originalName, metadata.mime ?: request.mime, response.prefix)
            val filename = identity.name
            val mime = identity.mime
            history.update(recordId) { it.copy(fileName = filename, mime = mime, total = metadata.length) }
            var lastNotice = 0L
            var lastRecord = 0L
            var written = 0L
            PublicDownloadStore(this@DownloadService).create(filename, mime, request.folder).use { target ->
                response.saveTo(target.output, ::active) { bytes, length ->
                    written = bytes
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastRecord >= 250) {
                        lastRecord = now
                        history.update(recordId, persistNow = false) { it.copy(received = bytes, total = length) }
                    }
                    if (now - lastNotice >= 750) {
                        lastNotice = now
                        notify(ACTIVE_NOTIFICATION, notification(R.string.download_started).setContentText(filename).setProgress(100,
                            if (length > 0) ((bytes.toDouble() / length) * 100).toInt().coerceIn(0, 100) else 0, length <= 0).build())
                    }
                }
                active()
                val inspected = DownloadTypes.inspect(response.prefix, target::openInput, ::active)
                val final = DownloadTypes.resolve(originalName, metadata.mime ?: request.mime, response.prefix, inspected)
                active()
                val uri = target.publish(final.name, final.mime)
                val place = FileActions.place(this@DownloadService, uri)
                withContext(Dispatchers.Main) {
                    if (request.epoch == epoch) {
                        history.update(recordId) { it.copy(state = DownloadState.COMPLETED, uri = uri.toString(), fileName = final.name, mime = final.mime, place = place,
                            received = written, total = written, finished = System.currentTimeMillis()) }
                        publishEvent(R.string.download_complete, uri, final.mime, recordId)
                        completed(final.name, recordId)
                    }
                }
            }
        }
        cookieManager.flush()
    }

    /** A failed transfer: its reason is recorded and shown; after a logout the failure is part of the cancellation. */
    private fun failed(request: Request, recordId: String, failure: Exception) {
        // A teacher's link among the materials is neither a file nor a failure: no record stays, and the page opens it.
        if (failure is DownloadIsLink && request.epoch == epoch) {
            history.remove(recordId)
            // «Скачать все» leaves a teacher's link for its own tap instead of opening pages one after another.
            if (request.batch == null) publishEvent(0, link = failure.target)
            return
        }
        val reason = when (failure) {
            is DownloadNeedsLogin -> DownloadFailure.LOGIN
            is DownloadAccessDenied -> DownloadFailure.ACCESS_DENIED
            is DownloadTooLarge -> DownloadFailure.TOO_LARGE
            else -> DownloadFailure.NETWORK
        }
        val canceled = request.epoch != epoch
        // One sign-in for a whole «Скачать все»: the batch's later refusals leave no record or message.
        if (!canceled && reason == DownloadFailure.LOGIN && request.batch != null && !refusedBatches.add(request.batch)) { history.remove(recordId); return }
        history.update(recordId) { it.copy(state = if (canceled) DownloadState.CANCELED else DownloadState.FAILED,
            failure = if (canceled) null else reason, finished = System.currentTimeMillis()) }
        if (canceled) return
        val message = failureMessage(reason)
        publishEvent(message, recordId = recordId, loginUrl = request.url.takeIf { reason == DownloadFailure.LOGIN }, batch = request.batch)
        notify(++notificationId, notification(message).setOngoing(false).setAutoCancel(true).build())
    }

    private fun notification(message: Int): NotificationCompat.Builder = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_download).setContentTitle(labels.of(language).getString(R.string.app_name))
        .setContentText(labels.of(language).getString(message)).setOnlyAlertOnce(true).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

    /** The finished file opens in the app's own viewer. */
    private fun completed(filename: String, recordId: String) {
        val open = FileViewerActivity.intent(this, recordId, language).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(this, ++notificationId, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notify(++notificationId, notification(R.string.download_complete).setContentTitle(filename)
            .setOngoing(false).setAutoCancel(true).setContentIntent(pending).build())
    }
    override fun onTimeout(startId: Int, fgsType: Int) { cancelTransfers(); stopSelf() }
    private fun cancelTransfers() {
        closing = true
        scope.cancel()
        val active = connections.mapNotNull { it.getAndSet(null) }
        CoroutineScope(Dispatchers.IO).launch { active.forEach { it.disconnect() } }
    }
    @android.annotation.SuppressLint("MissingPermission") // Notification permission is optional; FGS still runs without it.
    private fun notify(id: Int, notification: android.app.Notification) {
        if (notifications.areNotificationsEnabled()) notifications.notify(id, notification)
    }
    override fun onDestroy() { cancelTransfers(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "univer.downloads"
        private const val ACTIVE_NOTIFICATION = 400
        private const val ACTION_CANCEL = "com.univerflow.kaznu.download.CANCEL"
        private var notificationId = (System.currentTimeMillis() % 1_000_000_000L).toInt() + 500
        @Volatile private var epoch = 0
        private val mutableEvent = MutableStateFlow<DownloadEvent?>(null)
        val events = mutableEvent.asStateFlow()
        fun failureMessage(reason: DownloadFailure?) = when (reason) {
            DownloadFailure.LOGIN -> R.string.download_login
            DownloadFailure.ACCESS_DENIED -> R.string.download_access_denied
            DownloadFailure.INTERRUPTED -> R.string.download_interrupted
            DownloadFailure.TOO_LARGE -> R.string.download_too_large
            else -> R.string.download_failed
        }
        private fun publishEvent(message: Int, uri: Uri? = null, mime: String? = null, recordId: String? = null, loginUrl: String? = null,
            link: String? = null, batch: String? = null) {
            mutableEvent.value = DownloadEvent(UUID.randomUUID().toString(), message, uri, mime, recordId, loginUrl, link, batch)
        }
        /** A handled one-shot event is cleared, so a later screen does not act on it again. */
        fun consume(id: String) { mutableEvent.value?.takeIf { it.id == id }?.let { mutableEvent.compareAndSet(it, null) } }
        fun start(context: Context, url: String, agent: String, referer: String?, disposition: String?, mime: String?,
            language: AppLanguage, source: String = "", folder: String? = null, batch: String? = null) {
            require(SingleSignOnPolicy.downloadAllowed(url))
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)
                .putExtra("url", url).putExtra("agent", agent).putExtra("referer", referer)
                .putExtra("disposition", disposition).putExtra("mime", mime).putExtra("language", language.siteCode)
                .putExtra("source", source).putExtra("folder", folder).putExtra("batch", batch).putExtra("epoch", epoch))
        }
        /** Cancels one transfer from the Downloads screen; a stopped service has nothing to cancel. */
        fun cancel(context: Context, id: String) {
            try { context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra("id", id)) }
            catch (_: IllegalStateException) { /* Background start is refused only when no transfer keeps the service alive. */ }
        }
        fun cancelAll(context: Context) { epoch++; mutableEvent.value = null; context.stopService(Intent(context, DownloadService::class.java)) }
    }
}
