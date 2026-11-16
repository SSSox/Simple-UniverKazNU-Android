package com.univerflow.kaznu

import com.univerflow.kaznu.download.Transfer
import com.univerflow.kaznu.web.DownloadProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** A loopback server that reads the request and never answers stands in for a Univer file response that stalled. */
class DownloadCancelTest {
    private val url = "https://univer.kaznu.kz/student/umkd/get/1/1/2026/1"

    @Test fun cancellingATransferStalledBeforeTheResponseEndsItWithoutWaitingForTheReadTimeout(): Unit = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val requested = CountDownLatch(1)
            val accepted = AtomicReference<Socket?>()
            thread(isDaemon = true) {
                runCatching {
                    server.accept().also { accepted.set(it) }.getInputStream().bufferedReader().readLine()
                    requested.countDown()
                }
            }
            val connection = AtomicReference<HttpURLConnection?>()
            val probe = DownloadProbe { URL("http://127.0.0.1:${server.localPort}/file").openConnection() as HttpURLConnection }
            val job = launch(Dispatchers.IO) {
                try { probe.open(url, "fixture-agent", null, connectionChanged = { connection.set(it) }).close() }
                catch (stopped: Exception) { if (stopped is CancellationException) throw stopped }
            }
            try {
                assertTrue("The request reached the server", requested.await(10, TimeUnit.SECONDS))
                Transfer("fixture-record", job, connection).cancel { it.disconnect() }
                // The probe reads with a 30 s timeout: only closing the connection ends the blocked call this soon.
                assertNotNull("The stalled transfer ended after the cancel", withTimeoutOrNull(5_000) { job.join() })
            } finally { accepted.get()?.close() }
        }
    }
}
