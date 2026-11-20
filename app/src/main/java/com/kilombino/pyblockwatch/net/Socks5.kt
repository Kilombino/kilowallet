package com.kilombino.pyblockwatch.net

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Connections the wallet opens itself (no networking library, for the reproducible build):
 * through a SOCKS5 proxy (the app's Tor) or directly, with TLS on top when asked.
 */
object Socks5 {
    /**
     * A SOCKS5 connection to [host]:[port] through [proxy]. With [isolation], username/password
     * authentication (RFC 1929) carries it: Arti, like Tor's IsolateSOCKSAuth, never shares a
     * circuit between streams that give different usernames. Java's own SOCKS client only sends a
     * username from a process-wide Authenticator, so this one is written out. The host name is
     * resolved by Tor (a .onion has no other way, and DNS would leak the name).
     */
    fun connect(proxy: InetSocketAddress, host: String, port: Int, isolation: String?, timeoutMs: Int = 60_000): Socket {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(proxy.hostString, proxy.port), 15_000)
            raw.soTimeout = timeoutMs
            val o = raw.getOutputStream(); val i = raw.getInputStream()
            if (isolation != null) {
                o.write(byteArrayOf(5, 1, 2)); o.flush()
                val m = readFully(i, 2)
                check(m[0].toInt() == 5 && m[1].toInt() == 2) { "the SOCKS proxy refused the isolation" }
                val u = isolation.toByteArray().let { it.copyOf(minOf(it.size, 255)) }; val pw = "x".toByteArray()
                o.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(pw.size.toByte()) + pw); o.flush()
                check(readFully(i, 2)[1].toInt() == 0) { "the SOCKS proxy refused the isolation" }
            } else {
                o.write(byteArrayOf(5, 1, 0)); o.flush()
                val m = readFully(i, 2)
                check(m[0].toInt() == 5 && m[1].toInt() == 0) { "the SOCKS proxy wants authentication" }
            }
            val h = host.toByteArray()
            require(h.size in 1..255) { "bad host name" }
            o.write(byteArrayOf(5, 1, 0, 3, h.size.toByte()) + h + byteArrayOf((port shr 8).toByte(), port.toByte())); o.flush()
            val r = readFully(i, 4)
            check(r[1].toInt() == 0) { "the SOCKS proxy could not connect (${r[1].toInt() and 0xff})" }
            when (r[3].toInt()) {
                1 -> readFully(i, 4 + 2)
                4 -> readFully(i, 16 + 2)
                3 -> readFully(i, (readFully(i, 1)[0].toInt() and 0xff) + 2)
                else -> error("the SOCKS proxy answered oddly")
            }
            return raw
        } catch (e: Exception) { runCatching { raw.close() }; throw e }
    }

    /** Straight to [host]:[port], no proxy. */
    fun direct(host: String, port: Int, timeoutMs: Int = 60_000): Socket =
        Socket().also { it.connect(InetSocketAddress(host, port), 15_000); it.soTimeout = timeoutMs }

    /**
     * TLS over [raw] to [host]: the certificate must be valid and for this name (endpoint
     * identification, and on Android the platform's verifier as HttpsURLConnection would).
     */
    fun tls(raw: Socket, host: String, port: Int): SSLSocket {
        val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
        s.sslParameters = s.sslParameters.apply {
            serverNames = listOf(SNIHostName(host)); endpointIdentificationAlgorithm = "HTTPS"
        }
        s.startHandshake()
        if (System.getProperty("java.vendor").orEmpty().contains("Android", true))
            check(HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session)) { "certificate is not for $host" }
        return s
    }

    internal fun readFully(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(n); var off = 0
        while (off < n) { val r = inp.read(b, off, n - off); if (r < 0) error("connection closed"); off += r }
        return b
    }
}
