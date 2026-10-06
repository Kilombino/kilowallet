package com.kilombino.pyblockwatch.coinjoin

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.HttpsURLConnection

/**
 * A minimal nostr relay client: an RFC 6455 WebSocket written out by hand (the wallet keeps
 * no networking dependency for the reproducible build) and the NIP-01 REQ/EVENT/CLOSE verbs.
 *
 * One reader thread per connection; [onEvent] is called on it for every event of every open
 * subscription. Text frames only, client frames masked as the RFC requires, pings answered.
 */
class RelayClient(
    private val url: String,
    private val onEvent: (subId: String, event: NostrEvent) -> Unit,
    private val onClosed: (Throwable?) -> Unit = {},
) {
    private var socket: SSLSocket? = null
    private var out: OutputStream? = null
    private val rnd = SecureRandom()
    private val pendingOk = ConcurrentHashMap<String, CompletableFuture<Pair<Boolean, String>>>()
    private val pendingEose = ConcurrentHashMap<String, CompletableFuture<Unit>>()
    @Volatile var connected = false; private set

    fun connect() {
        val u = URI(url)
        require(u.scheme == "wss") { "only wss:// relays" }
        val host = u.host
        val port = if (u.port > 0) u.port else 443
        val s = (SSLSocketFactory.getDefault().createSocket() as SSLSocket)
        s.connect(InetSocketAddress(host, port), 15_000)
        // The certificate must be for this host name: the TLS layer checks it (endpoint
        // identification), and on Android the platform's verifier checks it again, as
        // HttpsURLConnection would. (Off Android that verifier refuses everything by design.)
        s.sslParameters = s.sslParameters.apply {
            serverNames = listOf(SNIHostName(host)); endpointIdentificationAlgorithm = "HTTPS"
        }
        s.startHandshake()
        if (System.getProperty("java.vendor").orEmpty().contains("Android", true))
            check(HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session)) { "certificate is not for $host" }
        s.soTimeout = 0
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also { rnd.nextBytes(it) })
        val path = (u.rawPath ?: "").ifEmpty { "/" }
        val o = s.outputStream
        o.write(("GET $path HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nUser-Agent: Kilowallet\r\n\r\n").toByteArray())
        o.flush()
        val inp = s.inputStream
        val status = readLine(inp)
        check(status.contains(" 101")) { "relay refused the WebSocket: $status" }
        while (readLine(inp).isNotEmpty()) { /* skip headers */ }
        socket = s; out = o; connected = true
        Thread({ readLoop(inp) }, "relay-$host").apply { isDaemon = true }.start()
    }

    private fun readLine(inp: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = inp.read(); if (c < 0) error("connection closed")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
        }
        return b.toString("UTF-8")
    }

    private fun readFully(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(n); var off = 0
        while (off < n) { val r = inp.read(b, off, n - off); if (r < 0) error("connection closed"); off += r }
        return b
    }

    private fun readLoop(inp: InputStream) {
        var err: Throwable? = null
        try {
            val msg = ByteArrayOutputStream()
            while (true) {
                val h = readFully(inp, 2)
                val fin = (h[0].toInt() and 0x80) != 0
                val op = h[0].toInt() and 0x0F
                var len = (h[1].toInt() and 0x7F).toLong()
                if (len == 126L) { val e = readFully(inp, 2); len = (((e[0].toInt() and 0xFF) shl 8) or (e[1].toInt() and 0xFF)).toLong() }
                else if (len == 127L) { val e = readFully(inp, 8); len = 0; for (x in e) len = (len shl 8) or (x.toLong() and 0xFF) }
                require(len < 8_000_000) { "frame too large" }
                val payload = readFully(inp, len.toInt())
                when (op) {
                    0x0, 0x1 -> { msg.write(payload); if (fin) { handle(msg.toString("UTF-8")); msg.reset() } }
                    0x8 -> break
                    0x9 -> frame(0xA, payload)
                    else -> {}
                }
            }
        } catch (e: Throwable) { err = e }
        connected = false
        pendingOk.values.forEach { it.complete(false to "disconnected") }
        pendingEose.values.forEach { it.complete(Unit) }
        runCatching { socket?.close() }
        onClosed(err)
    }

    private fun handle(text: String) {
        val a = runCatching { JSONArray(text) }.getOrNull() ?: return
        when (a.optString(0)) {
            "EVENT" -> {
                val ev = runCatching { NostrEvent.parse(a.getJSONObject(2)) }.getOrNull() ?: return
                if (ev.verify()) onEvent(a.getString(1), ev)
            }
            "EOSE" -> pendingEose.remove(a.optString(1))?.complete(Unit)
            "OK" -> pendingOk.remove(a.optString(1))?.complete(a.optBoolean(2) to a.optString(3))
            "CLOSED" -> pendingEose.remove(a.optString(1))?.complete(Unit)
        }
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

    private fun sendText(s: String) = frame(0x1, s.toByteArray(Charsets.UTF_8))

    /** Publishes [ev] and waits for the relay's OK. Returns (accepted, message). */
    fun publish(ev: NostrEvent, timeoutSec: Long = 15): Pair<Boolean, String> {
        val f = CompletableFuture<Pair<Boolean, String>>()
        pendingOk[ev.id] = f
        sendText(JSONArray().put("EVENT").put(ev.toJson()).toString())
        return runCatching { f.get(timeoutSec, TimeUnit.SECONDS) }.getOrElse { pendingOk.remove(ev.id); false to "timeout" }
    }

    /** Opens a subscription; with [waitEose] it returns once the stored events are delivered. */
    fun subscribe(subId: String, filters: List<JSONObject>, waitEose: Boolean = true, timeoutSec: Long = 15) {
        val f = CompletableFuture<Unit>()
        if (waitEose) pendingEose[subId] = f
        val a = JSONArray().put("REQ").put(subId); filters.forEach { a.put(it) }
        sendText(a.toString())
        if (waitEose) runCatching { f.get(timeoutSec, TimeUnit.SECONDS) }.onFailure { pendingEose.remove(subId) }
    }

    fun unsubscribe(subId: String) = runCatching { sendText(JSONArray().put("CLOSE").put(subId).toString()) }

    fun close() {
        runCatching { frame(0x8, ByteArray(0)) }
        runCatching { socket?.close() }
        connected = false
    }
}
