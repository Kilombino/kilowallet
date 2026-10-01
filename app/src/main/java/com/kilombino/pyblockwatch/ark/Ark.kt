package com.kilombino.pyblockwatch.ark

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * Ark for Kilombino wallet, through the Paperclip Ark server, with chain data from the
 * Esplora API of mempool.kilombino.com (a phone cannot run a Knots node).
 */
object Ark {
    const val SERVER = "https://ark.paperclippool.xyz"
    const val ESPLORA = "https://mempool.kilombino.com/api"

    // Limits advertised by the Paperclip Ark server (GetArkInfo, Oct 2026). Read live from
    // /wallet/ark-info when the engine is up; these are only for text shown before that.
    const val VTXO_LIFETIME_BLOCKS = 4320
    const val MIN_BOARD_SAT = 20_000L
    const val MAX_VTXO_SAT = 1_000_000L
    const val MAX_LIGHTNING_SAT = 250_000L

    @Volatile private var port = 0
    @Volatile private var token: String? = null

    val available: Boolean get() = ArkNative.available

    private fun datadir(ctx: Context) = File(ctx.filesDir, "ark")

    /** Starts the engine once per process. Blocking: call off the main thread. */
    @Synchronized
    fun ensureStarted(ctx: Context) {
        if (token != null || !available) return
        val dir = datadir(ctx).absolutePath
        var lastErr = "no port"
        repeat(5) {
            // A random high port; the token, not the port, is what keeps other apps out.
            val p = Random.nextInt(20_000, 60_000)
            val r = ArkNative.start(dir, p)
            if (!r.startsWith("ERR:")) { port = p; token = r; return }
            lastErr = r.removePrefix("ERR:")
            if (!lastErr.contains("bind", ignoreCase = true)) throw IllegalStateException(lastErr)
        }
        throw IllegalStateException(lastErr)
    }

    @Synchronized
    fun stop() {
        if (token == null) return
        ArkNative.stop()
        token = null
    }

    class ArkError(val status: Int, message: String) : Exception(message)

    private fun call(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 60_000): String {
        val t = token ?: throw IllegalStateException("Ark engine not started")
        val conn = (URL("http://127.0.0.1:$port/api/v1$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = timeoutMs
            setRequestProperty("Authorization", "Bearer $t")
            setRequestProperty("Accept", "application/json")
            if (body != null || method == "POST") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (conn.doOutput) conn.outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("message", text) }.getOrDefault(text)
                throw ArkError(code, msg.ifBlank { "HTTP $code" })
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- wallet

    fun hasWallet(): Boolean = runCatching { call("GET", "/wallet/balance"); true }
        .getOrElse { e -> if (e is ArkError && e.status == 422) false else throw e }

    /** Creates the Ark wallet (its own seed, kept by the engine in the app's private storage). */
    fun createWallet() {
        val req = JSONObject()
            .put("ark_server", SERVER)
            .put("chain_source", JSONObject().put("esplora", JSONObject().put("url", ESPLORA)))
            .put("network", "mainnet")
        call("POST", "/wallet/create", req, timeoutMs = 120_000)
    }

    data class Balance(
        val spendable: Long,
        val pendingBoard: Long,
        val pendingRound: Long,
        val needsRefresh: Long,
        val pendingExit: Long,
        val onchainConfirmed: Long,
        val onchainPending: Long,
    ) {
        val arkTotal: Long get() = spendable + pendingBoard + pendingRound
    }

    fun balance(): Balance {
        val a = JSONObject(call("GET", "/wallet/balance"))
        val o = runCatching { JSONObject(call("GET", "/onchain/balance")) }.getOrNull()
        return Balance(
            spendable = a.optLong("spendable_sat"),
            pendingBoard = a.optLong("pending_board_sat"),
            pendingRound = a.optLong("pending_in_round_sat"),
            needsRefresh = a.optLong("needs_refresh_sat"),
            pendingExit = a.optLong("pending_exit_sat"),
            onchainConfirmed = o?.optLong("confirmed_sat") ?: 0,
            onchainPending = (o?.optLong("trusted_pending_sat") ?: 0) + (o?.optLong("untrusted_pending_sat") ?: 0),
        )
    }

    fun arkAddress(): String = JSONObject(call("POST", "/wallet/addresses/next")).getString("address")

    /** On-chain XBT address to fund the wallet before moving the funds into Ark. */
    fun onchainAddress(): String = JSONObject(call("POST", "/onchain/addresses/next")).getString("address")

    /** Moves [sats] of on-chain XBT into Ark (needs 3 confirmations to become spendable). */
    fun board(sats: Long) { call("POST", "/boards/board-amount", JSONObject().put("amount_sat", sats), 180_000) }

    /** Moves all the on-chain XBT into Ark. */
    fun boardAll() { call("POST", "/boards/board-all", JSONObject(), 180_000) }

    /**
     * Pays an Ark address, a Lightning invoice / address / offer, a Bitcoin address or a
     * BIP-321 URI. [sats] may be null when the destination carries the amount.
     */
    fun send(destination: String, sats: Long?): String {
        val req = JSONObject().put("destination", destination.trim())
        if (sats != null) req.put("amount_sat", sats)
        return JSONObject(call("POST", "/wallet/send", req, 180_000)).optString("message")
    }

    /** A Lightning invoice paid into this Ark wallet. */
    fun lightningInvoice(sats: Long, description: String?): String {
        val req = JSONObject().put("amount_sat", sats)
        if (!description.isNullOrBlank()) req.put("description", description)
        val r = JSONObject(call("POST", "/lightning/receives/invoice", req, 120_000))
        return r.optString("invoice", r.toString())
    }

    /** Renews every coin close to expiry. The engine also does this by itself while running. */
    fun refreshAll() { call("POST", "/wallet/refresh/all", JSONObject(), 180_000) }

    data class Movement(val id: String, val status: String, val kind: String, val amount: Long, val time: String)

    fun history(): List<Movement> {
        val arr = runCatching { JSONArray(call("GET", "/history")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            Movement(
                id = m.optString("id"),
                status = m.optString("status"),
                kind = m.optJSONObject("subsystem")?.let { "${it.optString("name")} ${it.optString("kind")}".trim() } ?: "",
                amount = m.optLong("effective_balance_sat", m.optLong("intended_balance_sat")),
                time = m.optJSONObject("time")?.optString("created_at") ?: "",
            )
        }
    }

    /** Blocks until the next Ark coin expires, from the server's tip; null if unknown. */
    fun blocksToNearestExpiry(): Int? = runCatching {
        val vtxos = JSONArray(call("GET", "/wallet/vtxos"))
        val tip = JSONObject(call("GET", "/bitcoin/tip")).optInt("tip_height", -1)
        if (tip < 0 || vtxos.length() == 0) return@runCatching null
        (0 until vtxos.length()).mapNotNull { i -> vtxos.getJSONObject(i).optInt("expiry_height", -1).takeIf { it > 0 } }
            .minOrNull()?.let { it - tip }
    }.getOrNull()
}
