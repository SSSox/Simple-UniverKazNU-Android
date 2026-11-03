package com.univerflow.kaznu.update

import com.univerflow.kaznu.domain.Updates
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/** Reads GitHub without cookies or an account. Every redirect hop is checked, so a file can only come from GitHub. */
class UpdateClient(private val connect: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }) {
    /** The newest released version. The web redirect has no API rate limit, which a campus network would share. */
    fun latestVersion(): String? {
        val connection = open(Updates.LATEST, "HEAD")
        try {
            val status = connection.responseCode
            if (status !in REDIRECTS) throw IOException("Unexpected status $status")
            return Updates.version(target(Updates.LATEST, connection))
        } finally { connection.disconnect() }
    }

    fun checksum(version: String): String {
        val text = ByteArrayOutputStream()
        follow(Updates.checksumUrl(version)) { connection -> copy(connection.inputStream, text, MAX_CHECKSUM_BYTES, {}) }
        return Updates.checksum(text.toString("UTF-8")) ?: throw IOException("No checksum")
    }

    /** Writes the release file to [output] and returns its SHA-256. */
    fun download(version: String, output: OutputStream, active: () -> Unit, progress: (Int) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        follow(Updates.apkUrl(version)) { connection ->
            val length = connection.contentLengthLong
            if (length > Updates.MAX_APK_BYTES) throw IOException("Too large")
            var written = 0L
            copy(connection.inputStream, output, Updates.MAX_APK_BYTES, active) { chunk, count ->
                digest.update(chunk, 0, count)
                written += count
                if (length > 0) progress((written * 100 / length).toInt().coerceIn(0, 100))
            }
            if (written == 0L || (length >= 0 && written != length)) throw IOException("Incomplete download")
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun follow(url: String, read: (HttpURLConnection) -> Unit) {
        var next = url
        repeat(MAX_HOPS) {
            val connection = open(next, "GET")
            try {
                val status = connection.responseCode
                if (status in REDIRECTS) next = target(next, connection)
                else if (status == HttpURLConnection.HTTP_OK) { read(connection); return }
                else throw IOException("Unexpected status $status")
            } finally { connection.disconnect() }
        }
        throw IOException("Too many redirects")
    }

    private fun target(from: String, connection: HttpURLConnection) =
        URI(from).resolve(connection.getHeaderField("Location") ?: throw IOException("Missing redirect")).toString()

    private fun open(url: String, method: String): HttpURLConnection {
        if (!Updates.allowedHost(url)) throw IOException("Address not allowed")
        return connect(url).apply {
            instanceFollowRedirects = false
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Univer-Android")
        }
    }

    private fun copy(input: InputStream, output: OutputStream, limit: Long, active: () -> Unit, read: (ByteArray, Int) -> Unit = { _, _ -> }) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            active()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("Too large")
            output.write(buffer, 0, count)
            read(buffer, count)
        }
        output.flush()
    }

    private companion object {
        val REDIRECTS = listOf(301, 302, 303, 307, 308)
        const val MAX_HOPS = 6
        const val MAX_CHECKSUM_BYTES = 4096L
    }
}
