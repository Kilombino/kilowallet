package android.net.http

import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Desktop stand-in for Android's hostname-aware trust check. The shared ElectrumClient only calls
 * it on Android (onAndroid); here it delegates to the plain check so the shared code compiles.
 */
class X509TrustManagerExtensions(private val tm: X509TrustManager) {
    fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String): List<X509Certificate> {
        tm.checkServerTrusted(chain, authType)
        return chain.toList()
    }
}
