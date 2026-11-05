package vn.vnpt.ontconfig.core.driver

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object UnsafeSslClient {

    private val ALLOWED_HOSTS = setOf("192.168.1.1", "localhost", "127.0.0.1")

    fun sha256Fingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Throws(SSLPeerUnverifiedException::class)
    fun verifyPinnedCertificateChain(
        chain: Array<out Certificate>?,
        pinnedCertSha256: String?,
        onPeerCertSha256: ((String) -> Unit)? = null
    ) {
        val leaf = chain?.firstOrNull() as? X509Certificate
        val actualSha = leaf?.let(::sha256Fingerprint)
        if (actualSha != null) {
            onPeerCertSha256?.invoke(actualSha)
        }
        val expectedSha = pinnedCertSha256?.trim()?.lowercase().orEmpty()
        if (expectedSha.isNotEmpty()) {
            if (actualSha == null || actualSha != expectedSha) {
                throw SSLPeerUnverifiedException(
                    "Chứng chỉ TLS của ONT (${actualSha ?: "missing"}) không khớp với chứng chỉ đã ghim ở Lần kết nối 1 ($expectedSha)."
                )
            }
        }
    }

    private fun buildTrustManager(
        onPeerCertSha256: ((String) -> Unit)?
    ): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: return
            onPeerCertSha256?.invoke(sha256Fingerprint(leaf))
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val defaultTrustManager: X509TrustManager = buildTrustManager(null)

    private val sharedSslSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(defaultTrustManager), SecureRandom())
    }.socketFactory

    private val sharedBaseClient: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(sharedSslSocketFactory, defaultTrustManager)
        .retryOnConnectionFailure(false)
        .build()

    fun create(
        socketFactory: SocketFactory? = null,
        timeoutMs: Long = 5000L,
        allowedHostOverride: String? = null,
        pinnedCertSha256: String? = null,
        onPeerCertSha256: ((String) -> Unit)? = null
    ): OkHttpClient {
        val cleanPin = pinnedCertSha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val verifier = HostnameVerifier { hostname, session ->
            val hostOk = hostname in ALLOWED_HOSTS ||
                (allowedHostOverride != null && hostname == allowedHostOverride)
            if (!hostOk) return@HostnameVerifier false
            try {
                verifyPinnedCertificateChain(
                    chain = session.peerCertificates,
                    pinnedCertSha256 = cleanPin,
                    onPeerCertSha256 = onPeerCertSha256
                )
                true
            } catch (_: SSLPeerUnverifiedException) {
                false
            }
        }

        val builder = sharedBaseClient.newBuilder()
            .hostnameVerifier(verifier)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)

        if (onPeerCertSha256 != null) {
            val customTm = buildTrustManager(onPeerCertSha256)
            val customSslFactory = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(customTm), SecureRandom())
            }.socketFactory
            builder.sslSocketFactory(customSslFactory, customTm)
        }

        if (socketFactory != null) {
            builder.socketFactory(socketFactory)
        } else {
            builder.socketFactory(SocketFactory.getDefault())
        }

        return builder.build()
    }
}
