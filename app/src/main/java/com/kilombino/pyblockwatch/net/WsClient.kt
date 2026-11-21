package com.kilombino.pyblockwatch.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * An RFC 6455 WebSocket over a socket the caller opens (Tor with a circuit per robot, or direct):
 * text and binary messages, client frames masked, pings answered, and a ping of its own every
 * [pingEveryMs] so a connection a phone lost silently is noticed ([onClosed] with an error after
 * [deadAfterMs] without hearing anything). The same frames as the coinjoin relay client, for any
 * message, not only nostr.
 */
class WsClient(
    private val url: String,
    private val connector: HttpLite.Connector,
    private val onText: (String) -> Unit,
    private val onBinary: (ByteArray) -> Unit = {},
    /** Once, when the connection ends: null error = closed normally (by either side). */
    private val onClosed: (Throwable?) -> Unit = {},
    private val pingEveryMs: Long = 30_000,
    private val deadAfterMs: Long = 90_000,
) {
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private val rnd = SecureRandom()
    @Volatile var open = false; private set
    @Volatile private var closing = false
    @Volatile private var lastHeard = 0L
    @Volatile private var ended = false

    /** Connects and completes the handshake (blocking); then messages arrive on a thread of its own. */
    fun connect(extraHeaders: Map<String, String> = emptyMap()) {
        val u = URI(url)
        val scheme = u.scheme?.lowercase()
        require(scheme == "ws" || scheme == "wss") { "only ws(s)" }
        val tls = scheme == "wss"
        val host = u.host ?: error("no host in the address")
        val port = if (u.port > 0) u.port else if (tls) 443 else 80
        val path = (u.rawPath ?: "").ifEmpty { "/" } + (u.rawQuery?.let { "?$it" } ?: "")
        val s = connector.open(host, port, tls)
        try {
            s.soTimeout = 60_000
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also { rnd.nextBytes(it) })
            val hostHeader = if ((tls && port == 443) || (!tls && port == 80)) host else "$host:$port"
            val req = StringBuilder("GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n")
            for ((k, v) in extraHeaders) {
                require(k.none { it == '\r' || it == '\n' || it == ':' } && v.none { it == '\r' || it == '\n' }) { "bad header" }
                req.append(k).append(": ").append(v).append("\r\n")
            }
            if (extraHeaders.keys.none { it.equals("User-Agent", true) }) req.append("User-Agent: Kilowallet\r\n")
            req.append("\r\n")
            val o = s.getOutputStream()
            o.write(req.toString().toByteArray()); o.flush()
            val inp = BufferedInputStream(s.getInputStream())
            val status = HttpLite.line(inp)
            check(status.contains(" 101")) { "the server refused the WebSocket: $status" }
            var accept: String? = null
            while (true) {
                val l = HttpLite.line(inp)
                if (l.isEmpty()) break
                if (l.startsWith("Sec-WebSocket-Accept:", true)) accept = l.substringAfter(':').trim()
            }
            val expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            check(accept == expected) { "the server's WebSocket answer is wrong" }
            s.soTimeout = 0
            socket = s; out = o; open = true; lastHeard = System.currentTimeMillis()
            Thread({ readLoop(inp) }, "ws-$host").apply { isDaemon = true }.start()
            if (pingEveryMs > 0) Thread({ pinger() }, "ws-ping-$host").apply { isDaemon = true }.start()
        } catch (e: Exception) { runCatching { s.close() }; throw e }
    }

    private fun pinger() {
        while (open) {
            runCatching { Thread.sleep(pingEveryMs) }
            if (!open) return
            if (System.currentTimeMillis() - lastHeard > deadAfterMs) { end(IllegalStateException("no answer for ${deadAfterMs / 1000} s")); return }
            runCatching { frame(0x9, ByteArray(0)) }.onFailure { end(it); return }
        }
    }

    private fun readLoop(inp: InputStream) {
        var err: Throwable? = null
        try {
            val msg = ByteArrayOutputStream(); var msgOp = 0
            while (true) {
                val h = Socks5.readFully(inp, 2)
                val fin = (h[0].toInt() and 0x80) != 0
                val op = h[0].toInt() and 0x0F
                var len = (h[1].toInt() and 0x7F).toLong()
                if (len == 126L) { val e = Socks5.readFully(inp, 2); len = (((e[0].toInt() and 0xFF) shl 8) or (e[1].toInt() and 0xFF)).toLong() }
                else if (len == 127L) { val e = Socks5.readFully(inp, 8); len = 0; for (x in e) len = (len shl 8) or (x.toLong() and 0xFF) }
                require(len < 16_000_000) { "frame too large" }
                val payload = Socks5.readFully(inp, len.toInt())
                lastHeard = System.currentTimeMillis()
                when (op) {
                    0x1, 0x2 -> { msgOp = op; msg.reset(); msg.write(payload); if (fin) { deliver(msgOp, msg.toByteArray()); msg.reset() } }
                    0x0 -> { msg.write(payload); require(msg.size() < 16_000_000) { "message too large" }; if (fin) { deliver(msgOp, msg.toByteArray()); msg.reset() } }
                    0x8 -> { runCatching { frame(0x8, payload.copyOf(minOf(payload.size, 2))) }; break }
                    0x9 -> frame(0xA, payload)
                    else -> {}
                }
            }
        } catch (e: Throwable) { if (!closing) err = e }
        end(err)
    }

    private fun deliver(op: Int, data: ByteArray) {
        runCatching { if (op == 0x1) onText(String(data, Charsets.UTF_8)) else onBinary(data) }
    }

    private fun end(err: Throwable?) {
        synchronized(this) { if (ended) return; ended = true }
        open = false
        runCatching { socket?.close() }
        onClosed(err)
    }

    @Synchronized
    private fun frame(op: Int, payload: ByteArray) {
        val o = out ?: error("not connected")
        val f = ByteArrayOutputStream()
        f.write(0x80 or op)
        when {
            payload.size < 126 -> f.write(0x80 or payload.size)
            payload.size < 65536 -> { f.write(0x80 or 126); f.write(payload.size ushr 8); f.write(payload.size and 0xFF) }
            else -> { f.write(0x80 or 127); for (i in 7 downTo 0) f.write(((payload.size.toLong() ushr (8 * i)) and 0xFF).toInt()) }
        }
        val mask = ByteArray(4).also { rnd.nextBytes(it) }
        f.write(mask)
        f.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
        o.write(f.toByteArray()); o.flush()
    }

    /** False when the connection is gone. */
    fun send(text: String): Boolean = open && runCatching { frame(0x1, text.toByteArray(Charsets.UTF_8)) }.isSuccess

    fun sendBinary(data: ByteArray): Boolean = open && runCatching { frame(0x2, data) }.isSuccess

    /** The network went away (the phone said so): ends now with an error, without waiting for the ping. */
    fun abort(why: String = "network lost") { closing = true; end(java.io.IOException(why)) }

    /** Closes from this side: [onClosed] gets no error. */
    fun close() {
        closing = true
        runCatching { frame(0x8, byteArrayOf(0x03, 0xE8.toByte())) }
        end(null)
    }
}
