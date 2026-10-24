package com.kilombino.pyblockwatch.coinjoin

import android.content.Context
import com.kilombino.pyblockwatch.chain.Tor
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Optional Telegram alerts for the user's own pools, through @Coinjoinpoolbot.
 *
 * The user links once (a random token, opened as t.me/Coinjoinpoolbot?start=<token>); from then
 * on every pool they create or join is followed by the bot until it ends. The bot sees the
 * public steps itself (joins, leaves, closing, broadcast) in the pool announcements; the private
 * ones (close requested, refused, time to sign, confirmed) only this app knows, so it reports
 * them. Privacy cost, shown to the user before enabling: the bot's server learns that this
 * Telegram account is in that pool (not which mixed output is theirs). It forgets the pool when
 * it ends. Requests go over Tor to the bot's onion when Tor is already running, and otherwise over
 * https: Telegram itself already sees the phone's address, and a Tor that doesn't come up on some
 * phones lost every alert. [torOnly] keeps them to Tor (waiting for it to start) for who wants that.
 *
 * Delivery: every request waits in a small queue kept on the phone (at most [QUEUE_MAX], each
 * dropped after a day) until the bot has it, so an alert that found Tor still starting or the
 * onion out of reach goes again later, with a growing wait, and at each refresh of the coinjoin
 * service. How the last one went is kept for the alerts panel ([status]).
 */
object CoinjoinAlerts {
    const val BOT = "Coinjoinpoolbot"
    private const val ONION = "http://cjbotnhewqw7kdkxpczay4u4fiidhdwnboazbhi5hkrjeymr7ozgbkad.onion"
    private const val CLEARNET = "https://cjbot.kilombino.com"
    private const val PREFS = "coinjoin_alerts"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)

    /** This phone's link token, created on first use. */
    fun token(ctx: Context): String {
        prefs(ctx).getString("token", null)?.let { return it }
        val b = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val t = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        prefs(ctx).edit().putString("token", t).apply()
        return t
    }

    /** The deep link that opens the bot and links this phone. */
    fun linkUrl(ctx: Context): String = "https://t.me/$BOT?start=${token(ctx)}"

    fun enable(ctx: Context) { prefs(ctx).edit().putBoolean("enabled", true).apply() }

    /** Alerts only over Tor, never https: they wait for Tor to start, and if it doesn't they wait in the queue. */
    fun torOnly(ctx: Context): Boolean = prefs(ctx).getBoolean("tor_only", false)
    fun setTorOnly(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("tor_only", on).apply() }

    fun disable(ctx: Context) {
        val t = prefs(ctx).getString("token", null)
        // What was waiting for the old link has nowhere to go now; only the unlink itself still does.
        synchronized(this) { prefs(ctx).edit().putBoolean("enabled", false).remove("token").remove("queue").apply() }
        if (t != null) send(ctx, "/unlink", JSONObject().put("token", t), force = true)
    }

    /** Whether the bot has this phone linked (the user pressed Start). Network; call off the main thread. */
    fun linked(ctx: Context): Boolean = check(ctx) == true

    /**
     * Asks the bot whether this phone is linked, and notes how the request went ([status]): true or
     * false from the bot, null when it could not be reached. The bot has no test message to send, so
     * this is the test: it goes the same way as every alert. Network; call off the main thread.
     */
    fun check(ctx: Context): Boolean? = runCatching {
        JSONObject(post(ctx, "/linked", JSONObject().put("token", token(ctx)).toString()).second).optBoolean("linked")
    }.onSuccess { note(ctx, null) }.onFailure { note(ctx, reason(it)) }.getOrNull()

    /** Start following a pool we created or were welcomed into. */
    fun follow(ctx: Context, st: PoolSession.State) {
        val t = st.terms
        send(ctx, "/follow", JSONObject().put("pool", st.poolId).put("amount", t.amount).put("outputs", t.outputs).put("fee", t.feeRate)
            .put("max", t.maxPeers).put("min", t.minPeers).put("expires", t.expiresAt).put("peers", st.seats.size))
    }

    /** A private step of the round (what the bot can't see in the public announcements). */
    fun event(ctx: Context, poolId: String, type: String, detail: String = "") {
        send(ctx, "/event", JSONObject().put("pool", poolId).put("type", type).put("detail", detail))
    }

    private fun send(ctx: Context, path: String, body: JSONObject, force: Boolean = false) {
        if (!force && !enabled(ctx)) return
        if (!body.has("token")) body.put("token", token(ctx))
        enqueue(ctx, path, body, force)
        val app = ctx.applicationContext
        Thread { flush(app) }.apply { isDaemon = true; name = "cj-alerts" }.start()
    }

    // ------------------------------------------------------------------------------- delivery

    /** Requests kept at most, the oldest dropped first; and how long one is worth sending (a day). */
    internal const val QUEUE_MAX = 20
    internal const val MAX_AGE_MS = 24 * 3600_000L

    /** The wait before try number [tries] + 1: 30 s, 1, 2, 4… minutes, at most half an hour. */
    internal fun backoffMs(tries: Int): Long = minOf(30_000L shl minOf(tries, 10), 30 * 60_000L)

    /**
     * [queue] (JSON array of {path, body, at, tries, next, force}) with [item] added: anything older
     * than [MAX_AGE_MS] dropped, and the oldest beyond [QUEUE_MAX].
     */
    internal fun added(queue: JSONArray, item: JSONObject, now: Long): JSONArray {
        val keep = (0 until queue.length()).map { queue.getJSONObject(it) }.filter { now - it.optLong("at") < MAX_AGE_MS } + item
        return JSONArray(keep.takeLast(QUEUE_MAX))
    }

    private fun queue(ctx: Context): JSONArray =
        runCatching { JSONArray(prefs(ctx).getString("queue", "[]")) }.getOrDefault(JSONArray())

    @Synchronized private fun enqueue(ctx: Context, path: String, body: JSONObject, force: Boolean) {
        val now = System.currentTimeMillis()
        val item = JSONObject().put("path", path).put("body", body.toString()).put("at", now).put("tries", 0).put("next", 0).put("force", force)
        prefs(ctx).edit().putString("queue", added(queue(ctx), item, now).toString()).apply()
    }

    /** Requests still waiting for the bot. */
    fun waiting(ctx: Context): Int = queue(ctx).length()

    private val flushing = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Sends what is waiting and due, in order; called after each new alert and at every refresh of
     * [CoinjoinService]. One that fails on the way (Tor, the network, the bot's server) waits
     * longer each time and stops this pass, since the next would fail the same way; one the bot
     * refuses (a 4xx: not linked, a pool it doesn't know) is dropped, as sending it again would
     * change nothing. Network; off the main thread.
     */
    fun flush(ctx: Context) {
        if (!flushing.compareAndSet(false, true)) return
        try {
            while (true) {
                val now = System.currentTimeMillis()
                val item = synchronized(this) {
                    val q = queue(ctx)
                    val live = (0 until q.length()).map { q.getJSONObject(it) }.filter { now - it.optLong("at") < MAX_AGE_MS }
                        // Alerts for a link turned off since have nowhere to go.
                        .filter { it.optBoolean("force") || enabled(ctx) }
                    if (live.size != q.length()) prefs(ctx).edit().putString("queue", JSONArray(live).toString()).apply()
                    live.firstOrNull()
                } ?: return
                if (item.optLong("next") > now) return
                val r = runCatching { post(ctx, item.getString("path"), item.getString("body")) }
                val code = r.getOrNull()?.first ?: 0
                val done = r.isSuccess && code in 200..499
                synchronized(this) {
                    val q = queue(ctx)
                    val rest = (0 until q.length()).map { q.getJSONObject(it) }
                        .filter { !(it.optLong("at") == item.optLong("at") && it.optString("body") == item.optString("body")) }
                    val kept = if (done) rest else listOf(item.put("tries", item.optInt("tries") + 1)
                        .put("next", now + backoffMs(item.optInt("tries")))) + rest
                    prefs(ctx).edit().putString("queue", JSONArray(kept).toString()).apply()
                }
                when {
                    r.isFailure -> { note(ctx, reason(r.exceptionOrNull()!!)); return }
                    code >= 500 -> { note(ctx, "the bot's server answered $code"); return }
                    code >= 400 -> note(ctx, runCatching { JSONObject(r.getOrNull()!!.second).optString("error") }.getOrNull()
                        ?.ifEmpty { null } ?: "the bot refused it ($code)")
                    else -> note(ctx, null)
                }
            }
        } finally { flushing.set(false) }
    }

    /** How the last request to the bot went: null [error] for delivered. */
    class Status(val at: Long, val error: String?)

    fun status(ctx: Context): Status? = prefs(ctx).getLong("last_at", 0L).takeIf { it > 0 }?.let { Status(it, prefs(ctx).getString("last_error", null)) }

    private fun note(ctx: Context, error: String?) {
        prefs(ctx).edit().putLong("last_at", System.currentTimeMillis()).apply {
            if (error == null) remove("last_error") else putString("last_error", error)
        }.apply()
    }

    private fun reason(e: Throwable): String = when (e) {
        is TorDown -> e.message!!
        is java.net.SocketTimeoutException -> "no answer in time"
        is java.io.IOException -> "could not reach the bot: ${e.message ?: e.javaClass.simpleName}"
        else -> e.message ?: e.javaClass.simpleName
    }

    private class TorDown(msg: String) : Exception(msg)

    /**
     * POST to the bot, giving back the HTTP status and the body: over Tor to the onion when Tor is
     * already running (or always, with [torOnly], waiting for it to start), else over https.
     */
    private fun post(ctx: Context, path: String, body: String): Pair<Int, String> {
        val proxy = when {
            !Tor.available -> null
            Tor.running -> Tor.proxy(0)
            torOnly(ctx) -> {
                // The first start downloads Tor's directory, which on a phone can take a couple of minutes.
                Tor.start(ctx)
                Tor.proxy(180_000) ?: throw TorDown(
                    (if (Tor.alive) "Tor still connecting after 3 minutes" else "Tor stopped") +
                        (Tor.lastLine.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ": not sent over the clear net")
            }
            else -> null
        }
        val base = if (proxy != null) ONION else CLEARNET
        val u = java.net.URL(base + path)
        if (proxy == null) {
            val c = u.openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 20_000; c.readTimeout = 30_000
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            return code to ((if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "")
        }
        // Plain HTTP over the SOCKS proxy, the .onion resolved by Tor.
        java.net.Socket(proxy).use { s ->
            s.connect(java.net.InetSocketAddress.createUnresolved(u.host, if (u.port > 0) u.port else 80), 60_000)
            s.soTimeout = 60_000
            val b = body.toByteArray()
            s.getOutputStream().apply {
                write(("POST ${u.path} HTTP/1.1\r\nHost: ${u.host}\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray()); write(b); flush()
            }
            val resp = s.getInputStream().bufferedReader().readText()
            val head = resp.substringBefore("\r\n\r\n")
            val body = resp.substringAfter("\r\n\r\n", "")
            // "HTTP/1.1 200 OK": nothing back at all is a failure to deliver, not an answer.
            val code = head.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull()
                ?: throw java.io.IOException("no answer from the onion")
            // A chunked reply ("10\r\n{...}\r\n0\r\n\r\n") would not parse as JSON: join its chunks.
            return code to if (head.contains("transfer-encoding: chunked", ignoreCase = true)) unchunk(body) else body
        }
    }

    private fun unchunk(body: String): String {
        val out = StringBuilder(); var rest = body
        while (true) {
            val size = rest.substringBefore("\r\n").trim().substringBefore(';').toIntOrNull(16) ?: break
            if (size == 0) break
            rest = rest.substringAfter("\r\n")
            out.append(rest.take(size)); rest = rest.drop(size).removePrefix("\r\n")
        }
        return out.toString()
    }
}
