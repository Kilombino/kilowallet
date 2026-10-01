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
        val datadir = datadir(ctx)
        val seed = ArkSeed(ctx)
        seed.migrateFrom(datadir)
        val words = seed.load()?.joinToString(" ")
        val dir = datadir.absolutePath
        var lastErr = "no port"
        repeat(5) {
            // A random high port; the token, not the port, is what keeps other apps out.
            val p = Random.nextInt(20_000, 60_000)
            val r = ArkNative.start(dir, p, words)
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

    /**
     * Creates the Ark wallet from [words]: new ones, or the same words as the XBT spending
     * wallet. Words that already held Ark coins get them back from the server's recovery
     * mailbox. The words go to the Keystore first and to the engine in memory only.
     */
    fun createWallet(ctx: Context, words: List<String>) {
        val seed = ArkSeed(ctx)
        seed.save(words)
        val req = JSONObject()
            .put("ark_server", SERVER)
            .put("chain_source", JSONObject().put("esplora", JSONObject().put("url", ESPLORA)))
            .put("network", "mainnet")
            .put("mnemonic", words.joinToString(" "))
        try {
            call("POST", "/wallet/create", req, timeoutMs = 300_000)
        } catch (e: Exception) {
            seed.clear()
            throw e
        }
    }

    /** The Ark wallet's words, or null when there is no Ark wallet. */
    fun words(ctx: Context): List<String>? = ArkSeed(ctx).load()

    fun hasWords(ctx: Context): Boolean = ArkSeed(ctx).has()

    // ---------------------------------------------------------------- backup file

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("kilombino_ark", Context.MODE_PRIVATE)

    /** Movements in the wallet when the last backup file was saved; -1 if never. */
    fun backupMovements(ctx: Context): Int = prefs(ctx).getInt("backup_movements", -1)

    /**
     * Reads a consistent copy of the wallet: the engine stops so SQLite is closed cleanly,
     * the files are copied, and the engine starts again.
     */
    @Synchronized
    fun snapshot(ctx: Context): ArkBackup.Snapshot {
        val words = ArkSeed(ctx).load() ?: error("There is no Ark wallet to back up.")
        val movements = runCatching { history().size }.getOrDefault(0)
        stop()
        try {
            val dir = datadir(ctx)
            return ArkBackup.Snapshot(
                words = words,
                config = File(dir, "config.toml").readText(),
                db = File(dir, "db.sqlite").readBytes(),
                dbWal = File(dir, "db.sqlite-wal").takeIf { it.exists() && it.length() > 0 }?.readBytes(),
                movements = movements,
                created = System.currentTimeMillis(),
            )
        } finally {
            ensureStarted(ctx)
        }
    }

    /** True when the Ark words are also the XBT spending wallet's words. */
    fun wordsShared(ctx: Context): Boolean = prefs(ctx).getBoolean("words_shared", false)

    fun setWordsShared(ctx: Context, shared: Boolean) {
        prefs(ctx).edit().putBoolean("words_shared", shared).apply()
    }

    fun markBackedUp(ctx: Context, movements: Int) {
        prefs(ctx).edit().putInt("backup_movements", movements).apply()
    }

    /** Replaces this phone's Ark wallet with the one in [s] and starts it. */
    @Synchronized
    fun restore(ctx: Context, s: ArkBackup.Snapshot) {
        stop()
        val dir = datadir(ctx)
        // Keep the engine's own lock files; everything else belongs to the old wallet.
        dir.listFiles()?.forEach { f ->
            if (f.name != "LOCK" && f.name != "barkd.lock") f.deleteRecursively()
        }
        dir.mkdirs()
        File(dir, "config.toml").writeText(s.config)
        File(dir, "db.sqlite").writeBytes(s.db)
        s.dbWal?.let { File(dir, "db.sqlite-wal").writeBytes(it) }
        ArkSeed(ctx).save(s.words)
        markBackedUp(ctx, s.movements)
        ensureStarted(ctx)
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

    /**
     * What an operation will really cost, shown BEFORE the user confirms. Paperclip runs a
     * "funded recovery profile": each Ark transfer reserves sats to pre-pay its on-chain
     * emergency exit (about 6,000 with change, 4,000 without), and they are not refunded.
     * In fiat it is cents; in sats it can be most of a small payment, so it must be shown.
     */
    data class Estimate(val amount: Long, val fee: Long, val total: Long, val exact: Boolean, val note: String)

    /** Sats encoded in a BOLT11 invoice's human-readable part (lnbc2500u…), or null. */
    fun invoiceSats(invoice: String): Long? {
        val m = Regex("^ln(?:bc|tb|bcrt)(\\d+)([munp]?)1", RegexOption.IGNORE_CASE).find(invoice.trim()) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        // BTC amount × multiplier, in sats: m = 1e-3, u = 1e-6, n = 1e-9, p = 1e-12 BTC.
        return when (m.groupValues[2].lowercase()) {
            "" -> n * 100_000_000
            "m" -> n * 100_000
            "u" -> n * 100
            "n" -> n / 10
            "p" -> n / 10_000
            else -> null
        }
    }

    private fun feeQuery(path: String): Estimate? = runCatching {
        val r = JSONObject(call("GET", path))
        Estimate(r.optLong("net_amount_sat"), r.optLong("fee_sat"), r.optLong("gross_amount_sat"), true, "")
    }.getOrNull()

    fun estimateSend(destination: String, sats: Long?): Estimate? {
        val d = destination.trim()
        return when {
            d.startsWith("ln", ignoreCase = true) -> {
                val amt = sats ?: invoiceSats(d) ?: return null
                feeQuery("/fees/lightning/pay?amount_sat=$amt")?.copy(
                    note = "Lightning: the server's fee plus the recovery reserve of the transfer.")
            }
            d.startsWith("ark1", ignoreCase = true) -> {
                val amt = sats ?: return null
                // No server estimate for Ark-to-Ark; Paperclip documents the reserves.
                val spendable = runCatching { balance().spendable }.getOrDefault(0L)
                val reserve = if (amt >= spendable - 4_000) 4_000L else 6_000L
                Estimate(amt, reserve, amt + reserve, false,
                    "Recovery reserve pre-paid for the emergency exit; not refunded. " +
                        "${if (reserve == 6_000L) "6,000 when there is change" else "4,000 without change"}, " +
                        "more if several coins are combined.")
            }
            else -> {
                val amt = sats ?: return null
                feeQuery("/fees/send-onchain?amount_sat=$amt&address=${java.net.URLEncoder.encode(d, "UTF-8")}")
                    ?.copy(note = "Leaves Ark through the next round and pays the on-chain fee.")
            }
        }
    }

    /** What arrives from a Lightning invoice of [sats] (receive fee + reserve deducted). */
    fun estimateLightningReceive(sats: Long): Estimate? =
        feeQuery("/fees/lightning/receive?amount_sat=$sats")?.copy(
            note = "The server's receive fee plus the recovery reserve are taken from what arrives.")

    /**
     * Moving [sats] into Ark. The server quote leaves out what Paperclip documents: boarding
     * costs the greater of the quote or 1,000 sats (the anchor) plus a separate 1,000-sat
     * miner fee. Measured: 40,000 in → 38,000 spendable.
     */
    fun estimateBoard(sats: Long): Estimate? = feeQuery("/fees/board?amount_sat=$sats")?.let {
        val fee = maxOf(it.fee, 1_000L) + 1_000L
        Estimate(sats - fee, fee, sats, false, "Anchor and miner fee of the recovery transaction.")
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

    /** Plain names for the engine's movement subsystems. */
    private fun kindLabel(subsystem: String): String = when (subsystem) {
        "bark.board" -> "move into Ark"
        "bark.arkoor" -> "Ark payment"
        "bark.round" -> "renewal"
        "bark.offboard" -> "withdrawal"
        "bark.lightning_send" -> "Lightning payment"
        "bark.lightning_receive" -> "Lightning received"
        "bark.exit" -> "emergency exit"
        else -> subsystem.removePrefix("bark.")
    }

    fun history(): List<Movement> {
        val arr = runCatching { JSONArray(call("GET", "/history")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            Movement(
                id = m.optString("id"),
                status = m.optString("status"),
                kind = kindLabel(m.optJSONObject("subsystem")?.optString("name").orEmpty()),
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
