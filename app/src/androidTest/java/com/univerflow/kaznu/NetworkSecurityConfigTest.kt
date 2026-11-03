package com.univerflow.kaznu

import android.net.http.X509TrustManagerExtensions
import android.os.Build
import android.security.NetworkSecurityPolicy
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.net.ssl.HttpsURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.univerflow.kaznu.web.SitePolicy
import com.univerflow.kaznu.web.configureSecureSettings

@RunWith(AndroidJUnit4::class)
class NetworkSecurityConfigTest {
    @Test fun bundledAuthorityMatchesPublishedRootAndHasValidCaSignature() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val certificate = context.resources.openRawResource(R.raw.sectigo_public_server_authentication_root_r46).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02X".format(it.toInt() and 0xff) }
        assertEquals("7BB647A62AEEAC88BF257AA522D01FFEA395E0AB45C73F93F65654EC38F25A06", fingerprint)
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
        assertTrue(certificate.basicConstraints >= 0)
        assertTrue(certificate.keyUsage[5])
        certificate.verify(certificate.publicKey)
        certificate.checkValidity()
    }

    @Test fun runtimePolicyLimitsAdditionalTrustToExactUniverHost() {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val manager = X509TrustManagerExtensions(factory.trustManagers.filterIsInstance<X509TrustManager>().single())
        val unrelated = listOf("kaznu.kz", "docs.univer.kaznu.kz", "univer.kaznu.kz.evil.invalid", "example.com")
        for (host in unrelated) {
            assertFalse("Additional trust must not extend to $host", manager.isSameTrustConfiguration("univer.kaznu.kz", host))
            assertTrue("Other hosts must retain the system policy", manager.isSameTrustConfiguration("example.com", host))
        }
    }

    @Test fun cleartextRemainsBlockedAndAndroid17RetainsCertificateTransparency() {
        val policy = NetworkSecurityPolicy.getInstance()
        for (host in listOf("univer.kaznu.kz", "kaznu.kz", "docs.univer.kaznu.kz", "example.com")) {
            assertFalse("HTTP must remain blocked for $host", policy.isCleartextTrafficPermitted(host))
            if (Build.VERSION.SDK_INT >= 37) {
                assertTrue("CT must remain required for $host", policy.isCertificateTransparencyVerificationRequired(host))
            }
        }
    }

    @Test fun additionalAuthorityDoesNotAcceptAnUnknownCertificateEvenForUniver() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val certificate = instrumentation.context.assets.open("untrusted-univer-test-certificate.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val manager = X509TrustManagerExtensions(factory.trustManagers.filterIsInstance<X509TrustManager>().single())
        for (host in listOf("univer.kaznu.kz", "example.com")) {
            try {
                manager.checkServerTrusted(arrayOf(certificate), "RSA", host)
                fail("An unknown certificate must be rejected for $host")
            } catch (_: CertificateException) { /* Expected: no permissive fallback. */ }
        }
    }

    /** Explicit opt-in: the offline suite must not depend on Univer's availability. */
    @Test fun optionalLiveHttpsAndWebViewUseTheSamePolicy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("univer.liveTls") == "true")
        val connection = URL("https://univer.kaznu.kz/").openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            assertTrue("Public HTTPS endpoint must respond", connection.responseCode in 200..399)
            assertTrue(connection.serverCertificates.isNotEmpty())
        } finally { connection.disconnect() }

        val done = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        lateinit var web: WebView
        instrumentation.runOnMainSync {
            web = WebView(instrumentation.targetContext).apply {
                configureSecureSettings()
                webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        handler.cancel()
                        if (error.url == view.url) {
                            failure.set("WebView rejected the certificate: ${error.primaryError}")
                            done.countDown()
                        }
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) {
                            failure.set("WebView network error: ${error.errorCode}")
                            done.countDown()
                        }
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame && response.statusCode >= 400) {
                            failure.set("WebView HTTP error: ${response.statusCode}")
                            done.countDown()
                        }
                    }
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (url != view.url || !SitePolicy.trusted(url)) return
                        // New providers expose redirected HTTP errors here even if they omit the callback.
                        view.evaluateJavascript("(function(){var n=performance.getEntriesByType('navigation')[0];return n&&n.responseStatus||0;})()") { result ->
                            if (url != view.url) return@evaluateJavascript
                            val status = result.toIntOrNull() ?: 0
                            if (status >= 400) failure.set("WebView HTTP error: $status")
                            done.countDown()
                        }
                    }
                }
                loadUrl("https://univer.kaznu.kz/")
            }
        }
        try {
            assertTrue("Public page must finish loading", done.await(45, TimeUnit.SECONDS))
            assertNull(failure.get())
        } finally { instrumentation.runOnMainSync { web.destroyFixture() } }
    }
}
