package vn.vnpt.ontconfig.core.driver

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object UnsafeSslClient {

    private val ALLOWED_HOSTS = setOf("192.168.1.1", "localhost", "127.0.0.1")

    private val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sharedSslSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
    }.socketFactory

    private val sharedBaseClient: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(sharedSslSocketFactory, trustManager)
        .retryOnConnectionFailure(false)
        .build()

    fun create(
        socketFactory: SocketFactory? = null,
        timeoutMs: Long = 5000L,
        allowedHostOverride: String? = null
    ): OkHttpClient {
        val verifier = HostnameVerifier { hostname, _ ->
            hostname in ALLOWED_HOSTS || (allowedHostOverride != null && hostname == allowedHostOverride)
        }

        val builder = sharedBaseClient.newBuilder()
            .hostnameVerifier(verifier)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)

        if (socketFactory != null) {
            builder.socketFactory(socketFactory)
        } else {
            builder.socketFactory(SocketFactory.getDefault())
        }

        return builder.build()
    }
}
