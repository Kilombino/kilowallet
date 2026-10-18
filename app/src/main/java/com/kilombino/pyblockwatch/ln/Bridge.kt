package com.kilombino.pyblockwatch.ln

import android.content.Context
import com.kilombino.pyblockwatch.chain.Tor
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Pays a Lightning invoice of the SHA-256 chain with BTC, through Kilombino's bridge
 * (bridge.kilombino.com, the same service as its web page and with the same limits).
 *
 * Nothing is entrusted to the bridge: it hands back a hold invoice on this chain with the
 * same payment hash as the SHA-256 invoice, so it can only collect by paying that invoice,
 * and the preimage this node gets back is the proof. Before paying, the hold invoice is
 * checked on this phone's own node as the bridge's payer API asks of a payer: the same
 * hash, Kilombino's node as destination, option_blake2b, no more than the user accepted,
 * and enough time left.
 */
object Bridge {
    private const val CLEARNET = "https://bridge.kilombino.com"
    private const val ONION = "http://li7nwmrffmt6iut2ygolrs6mgrzwdxe26mtatzpgxvff3fri3rnq4iid.onion"

    /** The bridge's node on this chain: every hold invoice must pay exactly this one. */
    const val NODE = Lightning.KILOMBINO_NODE

    /** How far above the price shown the quote may come before asking again. */
    const val TOLERANCE = 0.02

    class Refused(val code: String, message: String) : Exception(message)

    /** What paying [sat] sats SHA-256 costs now; nothing is reserved by asking. */
    class Estimate(val sat: Long, val paySat: Long, val rate: Double, val spread: Double, val waitSeconds: Int) {
        /** The most the user agrees to pay: the estimate plus [TOLERANCE]. */
        val maxPaySat: Long get() = Math.ceil(paySat * (1 + TOLERANCE)).toLong()
    }

    class Quote(val holdInvoice: String, val hash: String, val sat: Long, val paySat: Long, val expiresAt: Long)

    fun check(ctx: Context, invoice: String): Estimate {
        val o = call(ctx, "/api/check", JSONObject().put("invoice", invoice))
        return Estimate(o.getLong("sat"), o.getLong("estimate_sat"), o.getDouble("rate"), o.getDouble("spread"),
            o.optInt("wait_s", 0))
    }

    /**
     * The final price: a hold invoice the bridge reserves for a minute. Each one carries a small
     * proof of work for [hash] (the SHA-256 invoice's payment hash), so asking for quotes costs
     * a little: it is what keeps one person from taking every quote the bridge can hold.
     */
    fun quote(ctx: Context, invoice: String, hash: String): Quote {
        val body = JSONObject().put("invoice", invoice)
        runCatching { work(ctx, hash.lowercase()) }.getOrNull()?.let { body.put("pow", it) }
        val o = call(ctx, "/api/quote", body)
        return Quote(o.getString("hold_invoice"), o.getString("hash").lowercase(), o.getLong("sat"),
            o.getLong("pay_sat"), o.getLong("expires_at"))
    }

    /**
     * The bridge's challenge, and a nonce for it: SHA-256("<challenge>:<hash>:<nonce>") starting
     * with as many zero bits as it asks (18: a fraction of a second here). Null when the bridge
     * hands out none (one from before the proof of work); the quote is then asked without it.
     */
    private fun work(ctx: Context, hash: String): JSONObject? {
        val c = call(ctx, "/api/pow", null)
        val challenge = c.getString("challenge")
        val bits = c.getInt("bits").coerceIn(1, 32)
        val md = MessageDigest.getInstance("SHA-256")
        val prefix = "$challenge:$hash:".toByteArray()
        val deadline = System.currentTimeMillis() + 60_000
        var n = 0L
        while (System.currentTimeMillis() < deadline) {
            repeat(10_000) {
                val nonce = java.lang.Long.toString(n++, 36)
                md.reset(); md.update(prefix)
                val d = md.digest(nonce.toByteArray())
                val head = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or
                    ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
                if (head ushr (32 - bits) == 0L) return JSONObject().put("challenge", challenge).put("nonce", nonce)
            }
        }
        return null
    }

    /** The bridge's state for a swap ("settled", "failed", …), or null if it cannot say. */
    fun state(ctx: Context, hash: String): String? =
        runCatching { call(ctx, "/api/swap/$hash", null).optString("state").ifBlank { null } }.getOrNull()

    /**
     * Why the [hold] invoice must not be paid, or null when it is the one the user agreed to:
     * the payer API's checks, made on this phone's own node.
     */
    fun mismatch(hold: Lnd.PayReq, holdMsat: Long, sha: Lnd.PayReq, q: Quote, maxPaySat: Long, now: Long): String? = when {
        q.hash != sha.hash.lowercase() || hold.hash.lowercase() != sha.hash.lowercase() ->
            "the bridge's invoice is not for this payment (another payment hash)"
        hold.destination != NODE -> "the bridge's invoice pays another node"
        !hold.blake2b -> "the bridge's invoice is not a BTC invoice"
        holdMsat <= 0 -> "the bridge's invoice has no amount"
        // A hold invoice can keep the payment held until its timelock: refuse one longer than
        // two weeks of blocks (the bridge's own come in at about 1,400).
        hold.cltv > MAX_HOLD_CLTV -> "the bridge's invoice could hold your payment too long (${hold.cltv} blocks)"
        ceilSat(holdMsat) > maxPaySat -> "the price moved: it is now ${ceilSat(holdMsat)} sats, more than the $maxPaySat you accepted"
        q.expiresAt - now < 15 || hold.timestamp + hold.expiry - now < 15 -> "the bridge's price expired before it could be paid"
        else -> null
    }

    fun ceilSat(msat: Long) = (msat + 999) / 1000

    /** The longest timelock a bridge hold invoice may ask for: 2016 blocks, about two weeks. */
    const val MAX_HOLD_CLTV = 2016L

    /** True when [preimageHex] is the secret of [hashHex]: the proof the invoice was paid. */
    fun proves(preimageHex: String, hashHex: String): Boolean = runCatching {
        val p = preimageHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        MessageDigest.getInstance("SHA-256").digest(p).joinToString("") { "%02x".format(it) } == hashHex.lowercase()
    }.getOrDefault(false)

    /** A refusal in words for the person paying. */
    fun text(code: String, o: JSONObject? = null): String = when (code) {
        "checksum" -> "That invoice is damaged (checksum): copy it again, ideally from the wallet that made it."
        "invalid_invoice" -> "The bridge could not read that invoice."
        "wrong_network" -> "That invoice is not for mainnet."
        "is_xbt" -> "That is a BTC invoice: pay it directly."
        "no_amount" -> "The invoice has no amount: the bridge only pays invoices with one."
        "expires_soon" -> "The invoice expires too soon to be paid safely: ask for a new one."
        "too_small" -> "Too small for the bridge: at least ${o?.optLong("min_sat") ?: 1000} sats SHA-256."
        "too_large" -> "Too large for the bridge: at most ${o?.optLong("max_sat") ?: 100000} sats SHA-256."
        "daily_cap" -> "The bridge has reached its limit for today. Try again tomorrow."
        "no_liquidity" -> "The bridge has not enough liquidity for that amount right now: try a smaller one or later."
        "price_unavailable" -> "The bridge has no reliable price right now (the market is moving or can't be read): try again in a few minutes."
        "chain_unmeasured" -> "The bridge is warming up: try again in a few minutes."
        "self_payment", "route_budget", "no_direction" -> "The bridge can't pay that invoice."
        "in_progress" -> "That invoice is already being paid through the bridge: wait a moment."
        "already_paid" -> "That invoice has already been paid through the bridge."
        "needs_operator" -> "That invoice needs Kilombino to look at it."
        "limit" -> "The bridge is busy: wait a minute and try again."
        "pow_expired", "pow_used", "pow_invalid", "pow_missing" -> "The bridge asked for a fresh check: try again."
        "wait" -> "Wait ${o?.optInt("wait_s") ?: 60} s before asking the bridge for a new price for this invoice."
        "bad_hold" -> "The bridge gave an invoice that did not check out: nothing was paid. Try again."
        "network" -> "Could not reach the bridge. Check the connection and try again."
        else -> "The bridge is not available right now: try again later."
    }

    // ------------------------------------------------------------------------------- transport

    private const val PATIENCE_MS = 8_000L

    /** JSON from the bridge's web API (see [request] for the way). */
    private fun call(ctx: Context, path: String, body: JSONObject?): JSONObject {
        // A request that fails is tried again for a few seconds before telling the user. Not a
        // quote: one that got through but whose answer was lost would only come back as "in
        // progress".
        var raw: String? = null
        val until = System.currentTimeMillis() + if (path == "/api/quote") 0 else PATIENCE_MS
        while (true) {
            raw = runCatching { request(ctx, path, body?.toString()) }.getOrNull()
            if (raw != null || System.currentTimeMillis() >= until) break
            Thread.sleep(2_000)
        }
        if (raw == null) throw Refused("network", text("network"))
        val o = runCatching { JSONObject(raw) }.getOrElse { throw Refused("unavailable", text("unavailable")) }
        o.optString("error").takeIf { it.isNotBlank() }?.let { throw Refused(it, text(it, o)) }
        return o
    }

    /**
     * Over https, like the bridge's web page: at once. Over Tor to the onion only when Tor is
     * already running (for a node reached by .onion), and if the onion can't be reached right
     * then, over https after all: waiting for Tor to start made paying an invoice take a minute.
     */
    private fun request(ctx: Context, path: String, body: String?): String {
        val proxy = if (Tor.running) Tor.proxy(0) else null
        if (proxy != null) {
            val sock = java.net.Socket(proxy)
            val connected = runCatching {
                sock.connect(java.net.InetSocketAddress.createUnresolved(java.net.URL(ONION).host, 80), 20_000); true
            }.getOrDefault(false)
            if (connected) return sock.use { onion(it, path, body) }
            runCatching { sock.close() }
        }
        run {
            val c = java.net.URL(CLEARNET + path).openConnection() as javax.net.ssl.HttpsURLConnection
            c.connectTimeout = 20_000; c.readTimeout = 30_000; c.instanceFollowRedirects = false
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            return (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
        }
    }

    /** Plain HTTP over the SOCKS proxy, on a socket already connected: the onion address is what authenticates the server. */
    private fun onion(s: java.net.Socket, path: String, body: String?): String {
        val u = java.net.URL(ONION + path)
        run {
            s.soTimeout = 60_000
            val b = body?.toByteArray()
            s.getOutputStream().apply {
                val head = (if (b != null) "POST" else "GET") + " ${u.path} HTTP/1.1\r\nHost: ${u.host}\r\n" +
                    (if (b != null) "Content-Type: application/json\r\nContent-Length: ${b.size}\r\n" else "") +
                    "Connection: close\r\n\r\n"
                write(head.toByteArray()); b?.let { write(it) }; flush()
            }
            val resp = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            val head = resp.substringBefore("\r\n\r\n")
            val rest = resp.substringAfter("\r\n\r\n", "")
            return if (head.contains("transfer-encoding: chunked", ignoreCase = true)) unchunk(rest) else rest
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
