package com.kilombino.pyblockwatch.coinjoin

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A nostr relay in the test's own process, on the loopback (ws://127.0.0.1:<port>): NIP-01 EVENT/REQ/CLOSE
 * with the filters Kilojoin uses (ids, kinds, authors, #p, #d, #t, since, until, limit), parameterised
 * replaceable events (30000–39999), OK and EOSE. Nothing leaves this machine, so no test pool can ever be
 * seen by a real wallet.
 *
 * And the faults a real relay and network can have, each switched on by the test:
 *  - [lose]: an event is answered OK but neither stored nor delivered (a lost message, a lost acknowledgment);
 *  - [noOk]: an event is stored and delivered but its OK never comes back (the sender can't tell);
 *  - [duplicate]: every event is delivered twice to every live subscription;
 *  - [shuffle]: stored events are replayed in random order (not newest first);
 *  - [forget]: stored events matching a predicate are dropped from history (lost or pruned history);
 *  - [hold]: an event is answered OK but stored and delivered only at [release] (a message that comes late).
 */
class TestRelay : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val url = "ws://127.0.0.1:${server.localPort}"

    /** Stored events, in arrival order. */
    val stored = CopyOnWriteArrayList<JSONObject>()
    /** Every event ever published, lost ones included (what each wallet sent). */
    val published = CopyOnWriteArrayList<JSONObject>()
    private val conns = CopyOnWriteArrayList<Conn>()

    @Volatile var lose: (JSONObject) -> Boolean = { false }
    @Volatile var noOk: (JSONObject) -> Boolean = { false }
    @Volatile var duplicate = false
    @Volatile var shuffle = false
    @Volatile var hold: (JSONObject) -> Boolean = { false }
    private val held = CopyOnWriteArrayList<JSONObject>()

    /** Stores and delivers the events [hold] kept back, in the order they came; returns how many. */
    fun release(): Int {
        val l = held.toList(); held.removeAll(l.toSet())
        for (ev in l) { store(ev); for (c in conns) c.deliver(ev) }
        return l.size
    }

    /** Drops stored events matching [pred] from history; returns how many. */
    fun forget(pred: (JSONObject) -> Boolean): Int { val l = stored.filter(pred); stored.removeAll(l.toSet()); return l.size }

    /** Closes every client connection (as a network drop would): the clients reconnect and replay. */
    fun dropConnections() { conns.forEach { runCatching { it.socket.close() } } }

    init {
        Thread({
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                Thread({ runCatching { Conn(s).run() } }, "test-relay-conn").apply { isDaemon = true }.start()
            }
        }, "test-relay").apply { isDaemon = true }.start()
    }

    override fun close() { runCatching { server.close() }; conns.forEach { runCatching { it.socket.close() } } }

    private inner class Conn(val socket: Socket) {
        private val out: OutputStream = socket.getOutputStream()
        val subs = ConcurrentHashMap<String, List<JSONObject>>()

        fun run() {
            val inp = socket.getInputStream()
            // The handshake (RFC 6455 §4.2).
            var key = ""
            while (true) {
                val line = readLine(inp)
                if (line.isEmpty()) break
                if (line.startsWith("Sec-WebSocket-Key:", true)) key = line.substringAfter(':').trim()
            }
            val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            synchronized(out) {
                out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                out.flush()
            }
            conns.add(this)
            try {
                val msg = ByteArrayOutputStream()
                while (true) {
                    val h = readFully(inp, 2)
                    val fin = (h[0].toInt() and 0x80) != 0
                    val op = h[0].toInt() and 0x0F
                    var len = (h[1].toInt() and 0x7F).toLong()
                    if (len == 126L) { val e = readFully(inp, 2); len = (((e[0].toInt() and 0xFF) shl 8) or (e[1].toInt() and 0xFF)).toLong() }
                    else if (len == 127L) { val e = readFully(inp, 8); len = 0; for (x in e) len = (len shl 8) or (x.toLong() and 0xFF) }
                    val masked = (h[1].toInt() and 0x80) != 0
                    val mask = if (masked) readFully(inp, 4) else ByteArray(4)
                    val p = readFully(inp, len.toInt())
                    if (masked) for (i in p.indices) p[i] = (p[i].toInt() xor mask[i % 4].toInt()).toByte()
                    when (op) {
                        0x0, 0x1 -> { msg.write(p); if (fin) { handle(msg.toString("UTF-8")); msg.reset() } }
                        0x8 -> break
                        0x9 -> frame(0xA, p)
                        else -> {}
                    }
                }
            } catch (_: Exception) {
            } finally { conns.remove(this); runCatching { socket.close() } }
        }

        private fun handle(text: String) {
            val a = JSONArray(text)
            when (a.getString(0)) {
                "EVENT" -> {
                    val ev = a.getJSONObject(1)
                    published.add(ev)
                    val parsed = runCatching { NostrEvent.parse(ev) }.getOrNull()
                    if (parsed == null || !parsed.verify()) { send(JSONArray().put("OK").put(ev.optString("id")).put(false).put("invalid: bad signature")); return }
                    val lost = lose(ev)
                    val late = !lost && hold(ev)
                    if (late) held.add(ev)
                    if (!lost && !late) store(ev)
                    if (!noOk(ev)) send(JSONArray().put("OK").put(ev.getString("id")).put(true).put(""))
                    if (!lost && !late) for (c in conns) c.deliver(ev)
                }
                "REQ" -> {
                    val id = a.getString(1)
                    val filters = (2 until a.length()).map { a.getJSONObject(it) }
                    subs[id] = filters
                    val matched = filters.flatMap { f ->
                        val m = stored.filter { matches(f, it) }.sortedByDescending { it.getLong("created_at") }
                        if (f.has("limit")) m.take(f.getInt("limit")) else m
                    }.distinctBy { it.getString("id") }
                    val order = if (shuffle) matched.shuffled() else matched
                    for (ev in order) send(JSONArray().put("EVENT").put(id).put(ev))
                    send(JSONArray().put("EOSE").put(id))
                }
                "CLOSE" -> subs.remove(a.getString(1))
            }
        }

        fun deliver(ev: JSONObject) {
            for ((id, filters) in subs) if (filters.any { matches(it, ev) }) {
                send(JSONArray().put("EVENT").put(id).put(ev))
                if (duplicate) send(JSONArray().put("EVENT").put(id).put(ev))
            }
        }

        private fun send(a: JSONArray) = runCatching { frame(0x1, a.toString().toByteArray(Charsets.UTF_8)) }

        private fun frame(op: Int, payload: ByteArray) = synchronized(out) {
            val f = ByteArrayOutputStream()
            f.write(0x80 or op)
            when {
                payload.size < 126 -> f.write(payload.size)
                payload.size < 65536 -> { f.write(126); f.write(payload.size ushr 8); f.write(payload.size and 0xFF) }
                else -> { f.write(127); for (i in 7 downTo 0) f.write(((payload.size.toLong() ushr (8 * i)) and 0xFF).toInt()) }
            }
            f.write(payload)
            out.write(f.toByteArray()); out.flush()
        }
    }

    private fun store(ev: JSONObject) {
        val kind = ev.getInt("kind")
        if (kind in 30000..39999) {
            val d = dTag(ev)
            val older = stored.filter { it.getInt("kind") == kind && it.getString("pubkey") == ev.getString("pubkey") && dTag(it) == d }
            if (older.any { it.getLong("created_at") > ev.getLong("created_at") }) return
            stored.removeAll(older.toSet())
        }
        if (stored.none { it.getString("id") == ev.getString("id") }) stored.add(ev)
    }

    companion object {
        private fun tags(ev: JSONObject, name: String): List<String> {
            val t = ev.optJSONArray("tags") ?: return emptyList()
            return (0 until t.length()).map { t.getJSONArray(it) }.filter { it.length() >= 2 && it.getString(0) == name }.map { it.getString(1) }
        }
        private fun dTag(ev: JSONObject) = tags(ev, "d").firstOrNull() ?: ""
        private fun list(f: JSONObject, k: String): List<String>? = f.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.get(it).toString() } }

        fun matches(f: JSONObject, ev: JSONObject): Boolean {
            list(f, "ids")?.let { if (ev.getString("id") !in it) return false }
            list(f, "kinds")?.let { if (ev.getInt("kind").toString() !in it) return false }
            list(f, "authors")?.let { if (ev.getString("pubkey") !in it) return false }
            for (k in f.keys()) if (k.startsWith("#")) {
                val want = list(f, k) ?: continue
                if (tags(ev, k.substring(1)).none { it in want }) return false
            }
            if (f.has("since") && ev.getLong("created_at") < f.getLong("since")) return false
            if (f.has("until") && ev.getLong("created_at") > f.getLong("until")) return false
            return true
        }

        private fun readLine(inp: InputStream): String {
            val b = ByteArrayOutputStream()
            while (true) {
                val c = inp.read(); if (c < 0) error("closed")
                if (c == '\n'.code) break
                if (c != '\r'.code) b.write(c)
            }
            return b.toString("UTF-8")
        }

        private fun readFully(inp: InputStream, n: Int): ByteArray {
            val b = ByteArray(n); var off = 0
            while (off < n) { val r = inp.read(b, off, n - off); if (r < 0) error("closed"); off += r }
            return b
        }
    }
}
