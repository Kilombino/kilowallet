package com.kilombino.pyblockwatch.ln

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kilombino's inbound liquidity service (lsp.kilombino.com): it opens a private channel from
 * Kilombino's node to this one, so the wallet can receive. The request is signed with the
 * node key; the fee is paid with a hold invoice that Kilombino's node only settles once the
 * channel's funding transaction is out (otherwise it is cancelled and the payment comes back).
 */
object Inbound {
    const val API = "https://lsp.kilombino.com/v1"

    data class Info(val pubkey: String, val minSats: Long, val maxSats: Long, val feePerMillion: Long,
                    val availableSats: Long, val minDays: Int)
    data class Quote(val id: String, val invoice: String, val fee: Long, val sats: Long, val expires: Long)
    data class Order(val status: String, val fundingTxid: String?, val reason: String?)

    private fun http(method: String, path: String, body: JSONObject? = null): JSONObject {
        // Through Tor when it is already on, so the service doesn't see this phone's address.
        val proxy = com.kilombino.pyblockwatch.chain.Tor.proxyIfRunning()
        val c = ((if (proxy != null) URL(API + path).openConnection(proxy) else URL(API + path).openConnection()) as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Accept", "application/json"); setRequestProperty("User-Agent", "Kilowallet")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        try {
            body?.let { b -> c.outputStream.use { it.write(b.toString().toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val o = JSONObject(text)
            if (code !in 200..299) throw IllegalStateException(o.optString("error", "HTTP $code"))
            return o
        } finally { c.disconnect() }
    }

    fun info(): Info = http("GET", "/info").let {
        Info(it.getString("pubkey"), it.getLong("minSats"), it.getLong("maxSats"), it.getLong("feePerMillion"),
            it.getLong("availableSats"), it.getInt("minDays"))
    }

    /** Asks for a channel of [sats]; the node signs the request. */
    suspend fun quote(ourPubkey: String, sats: Long): Quote {
        // The service opens the channel to a node connected to it: reach Kilombino's node
        // first (a wallet whose channels are with other nodes may not be connected to it).
        if (!Lightning.connectKilombino()) throw IllegalStateException("Cannot reach Kilombino's node: " + (Lightning.linkError ?: "no answer"))
        val ts = System.currentTimeMillis() / 1000
        val sig = Lnd.signMessage("kilowallet-lsp:v1:$ourPubkey:$sats:$ts")
        val o = http("POST", "/quote", JSONObject().put("pubkey", ourPubkey).put("sats", sats).put("ts", ts).put("signature", sig))
        return Quote(o.getString("id"), o.getString("invoice"), o.getLong("fee"), o.getLong("sats"), o.getLong("expires"))
    }

    /**
     * Why the quote's invoice must not be paid, or null when it is the one the user agreed to:
     * decoded on this phone's own node, it must pay Kilombino's node on this chain, ask for
     * exactly the fee shown (the service's rate for the size asked), for the size asked, and
     * still have time left.
     */
    suspend fun mismatch(q: Quote, askedSats: Long, feePerMillion: Long): String? {
        val r = Lnd.decode(q.invoice)
        val fee = feePerMillion * (askedSats / 1_000_000)
        val now = System.currentTimeMillis() / 1000
        return when {
            r.destination != Lightning.KILOMBINO_NODE -> "the invoice pays another node"
            !r.blake2b -> "the invoice is not a BTC invoice"
            q.sats != askedSats -> "the offer is for another channel size"
            q.fee != fee || r.msat != fee * 1000 -> "the invoice asks for another fee (${(r.msat + 999) / 1000} sats, not $fee)"
            r.timestamp + r.expiry - now < 30 -> "the offer has expired: ask again"
            // The fee is held until the channel is out: never for longer than two weeks of blocks.
            r.cltv > Bridge.MAX_HOLD_CLTV -> "the invoice could hold the fee too long (${r.cltv} blocks)"
            else -> null
        }
    }

    fun order(id: String): Order = http("GET", "/order/$id").let {
        Order(it.getString("status"), it.optString("fundingTxid").takeIf { t -> t.length == 64 }, if (it.isNull("reason")) null else it.optString("reason").ifBlank { null })
    }
}
