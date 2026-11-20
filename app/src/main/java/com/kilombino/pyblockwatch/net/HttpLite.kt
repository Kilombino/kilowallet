package com.kilombino.pyblockwatch.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.URI

/**
 * A small HTTP/1.1 client over a socket the caller opens ([Connector]): through Tor with a
 * circuit of its own per robot, or directly. HttpURLConnection can't give a SOCKS username per
 * request, which the per-robot isolation needs. One request per connection (Connection: close),
 * bodies by Content-Length, chunked, or up to the close.
 */
object HttpLite {
    /** Opens the TCP connection for [host]:[port] (the proxy and TLS are the caller's choice). */
    fun interface Connector { fun open(host: String, port: Int, tls: Boolean): Socket }

    class Response(val code: Int, val headers: Map<String, String>, val body: ByteArray) {
        fun text(): String = String(body, Charsets.UTF_8)
    }

    const val MAX_BODY = 16_000_000

    fun request(
        method: String, url: String, headers: Map<String, String>, body: ByteArray?,
        connector: Connector, readTimeoutMs: Int = 120_000,
    ): Response {
        val u = URI(url)
        val scheme = u.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "only http(s)" }
        val host = u.host ?: error("no host in the address")
        val tls = scheme == "https"
        val port = if (u.port > 0) u.port else if (tls) 443 else 80
        val path = (u.rawPath ?: "").ifEmpty { "/" } + (u.rawQuery?.let { "?$it" } ?: "")
        // Header names and values come from the page: no line breaks may get through (checked
        // before anything is opened).
        for ((k, v) in headers)
            require(k.isNotEmpty() && k.none { it == '\r' || it == '\n' || it == ':' } && v.none { it == '\r' || it == '\n' }) { "bad header" }
        val s = connector.open(host, port, tls)
        try {
            s.soTimeout = readTimeoutMs
            val req = StringBuilder()
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            req.append("Host: ").append(if ((tls && port == 443) || (!tls && port == 80)) host else "$host:$port").append("\r\n")
            val sent = HashSet<String>()
            for ((k, v) in headers) {
                val lk = k.lowercase()
                if (lk == "host" || lk == "content-length" || lk == "connection" || lk == "transfer-encoding" || lk == "accept-encoding") continue
                req.append(k).append(": ").append(v).append("\r\n"); sent.add(lk)
            }
            if ("user-agent" !in sent) req.append("User-Agent: Kilowallet\r\n")
            req.append("Accept-Encoding: identity\r\nConnection: close\r\n")
            if (body != null) req.append("Content-Length: ").append(body.size).append("\r\n")
            req.append("\r\n")
            val o = s.getOutputStream()
            o.write(req.toString().toByteArray(Charsets.UTF_8))
            if (body != null) o.write(body)
            o.flush()
            return read(BufferedInputStream(s.getInputStream()), method == "HEAD")
        } finally { runCatching { s.close() } }
    }

    internal fun read(inp: InputStream, head: Boolean = false): Response {
        var status = line(inp)
        var code = parseCode(status)
        var hs = headers(inp)
        // 1xx (100 Continue) comes before the real answer.
        while (code in 100..199) { status = line(inp); code = parseCode(status); hs = headers(inp) }
        if (head || code == 204 || code == 304) return Response(code, hs, ByteArray(0))
        val body = when {
            hs["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> chunked(inp)
            hs["content-length"] != null -> {
                val n = hs.getValue("content-length").trim().toLong()
                require(n in 0..MAX_BODY) { "answer too large" }
                Socks5.readFully(inp, n.toInt())
            }
            else -> untilClose(inp)
        }
        return Response(code, hs, body)
    }

    private fun parseCode(status: String): Int {
        val parts = status.split(' ')
        require(parts.size >= 2 && parts[0].startsWith("HTTP/")) { "not an HTTP answer" }
        return parts[1].toInt()
    }

    private fun headers(inp: InputStream): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        while (true) {
            val l = line(inp)
            if (l.isEmpty()) return m
            val i = l.indexOf(':'); if (i <= 0) continue
            val k = l.substring(0, i).trim().lowercase(); val v = l.substring(i + 1).trim()
            m[k] = m[k]?.let { "$it, $v" } ?: v
        }
    }

    private fun chunked(inp: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = line(inp).substringBefore(';').trim().toInt(16)
            if (size == 0) { while (line(inp).isNotEmpty()) { }; return out.toByteArray() }
            require(out.size() + size <= MAX_BODY) { "answer too large" }
            out.write(Socks5.readFully(inp, size))
            line(inp)
        }
    }

    private fun untilClose(inp: InputStream): ByteArray {
        val out = ByteArrayOutputStream(); val b = ByteArray(16384)
        while (true) {
            val r = inp.read(b); if (r < 0) return out.toByteArray()
            out.write(b, 0, r); require(out.size() <= MAX_BODY) { "answer too large" }
        }
    }

    internal fun line(inp: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = inp.read(); if (c < 0) { if (b.size() == 0) error("connection closed"); break }
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
            require(b.size() < 65536) { "line too long" }
        }
        return b.toString("UTF-8")
    }
}
