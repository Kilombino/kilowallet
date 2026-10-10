package com.kilombino.pyblockwatch.coinjoin

import android.content.Context
import com.kilombino.pyblockwatch.chain.Tor
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
 * it ends. Requests go over Tor to the bot's onion when Tor is available.
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

    fun disable(ctx: Context) {
        val t = prefs(ctx).getString("token", null)
        prefs(ctx).edit().putBoolean("enabled", false).remove("token").apply()
        if (t != null) send(ctx, "/unlink", JSONObject().put("token", t), force = true)
    }

    /** Whether the bot has this phone linked (the user pressed Start). Network; call off the main thread. */
    fun linked(ctx: Context): Boolean = runCatching {
        JSONObject(post(ctx, "/linked", JSONObject().put("token", token(ctx)).toString())).optBoolean("linked")
    }.getOrDefault(false)

    /** Start following a pool we created or were welcomed into. */
    fun follow(ctx: Context, st: PoolSession.State) {
        val t = st.terms
        send(ctx, "/follow", JSONObject().put("pool", st.poolId).put("amount", t.amount).put("fee", t.feeRate)
            .put("max", t.maxPeers).put("min", t.minPeers).put("expires", t.expiresAt).put("peers", st.seats.size))
    }

    /** A private step of the round (what the bot can't see in the public announcements). */
    fun event(ctx: Context, poolId: String, type: String, detail: String = "") {
        send(ctx, "/event", JSONObject().put("pool", poolId).put("type", type).put("detail", detail))
    }

    private fun send(ctx: Context, path: String, body: JSONObject, force: Boolean = false) {
        if (!force && !enabled(ctx)) return
        if (!body.has("token")) body.put("token", token(ctx))
        val app = ctx.applicationContext
        Thread { runCatching { post(app, path, body.toString()) } }.start()
    }

    /** POST over Tor to the onion when Tor is there, else over https. */
    private fun post(ctx: Context, path: String, body: String): String {
        val proxy = if (Tor.available) { Tor.start(ctx); Tor.proxy(60_000) } else null
        val base = if (proxy != null) ONION else CLEARNET
        val u = java.net.URL(base + path)
        if (proxy == null) {
            val c = u.openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 20_000; c.readTimeout = 30_000
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            return (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
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
            // A chunked reply ("10\r\n{...}\r\n0\r\n\r\n") would not parse as JSON: join its chunks.
            return if (head.contains("transfer-encoding: chunked", ignoreCase = true)) unchunk(body) else body
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
