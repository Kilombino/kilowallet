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
    // Paperclip funded recovery profile 2: reserve per recovery transaction, and the
    // smallest output (330 dust + 1,000 claim allowance). See FUNDED-EXITS.md.
    const val RESERVE_SAT = 2_000L
    const val MIN_OUTPUT_SAT = 1_330L

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

    /** The engine words amounts as "0.00009000 BTC"; the wallet speaks in sats. */
    private fun inSats(msg: String): String {
        val withSats = Regex("(\\d+\\.\\d{8}) BTC").replace(msg) { m ->
            val sats = m.groupValues[1].replace(".", "").trimStart('0').ifEmpty { "0" }.toLong()
            "%,d sats".format(java.util.Locale.US, sats).replace(',', ' ')
        }
        return withSats.trimEnd().let { if (it.endsWith(".")) it else "$it." }
    }

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
                throw ArkError(code, inSats(msg).ifBlank { "HTTP $code" })
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

    /**
     * A digest of what a backup must capture: the wallet's coins and every movement with
     * its status. A board that confirms, a renewal or a payment all change it.
     */
    fun stateFingerprint(): String? = runCatching {
        val vtxos = JSONArray(call("GET", "/wallet/vtxos"))
        val ids = (0 until vtxos.length()).map { vtxos.getJSONObject(it).optString("id") }.sorted()
        val moves = history().map { "${it.id}:${it.status}" }.sorted()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.digest((ids + "|" + moves).joinToString(",").toByteArray())
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** [stateFingerprint] when the last backup file was saved; null if never. */
    fun backupFingerprint(ctx: Context): String? = prefs(ctx).getString("backup_state", null)

    /**
     * Reads a consistent copy of the wallet: the engine stops so SQLite is closed cleanly,
     * the files are copied, and the engine starts again.
     */
    @Synchronized
    fun snapshot(ctx: Context): ArkBackup.Snapshot {
        val words = ArkSeed(ctx).load() ?: error("There is no Ark wallet to back up.")
        val movements = runCatching { history().size }.getOrDefault(0)
        lastSnapshotFingerprint = stateFingerprint()
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

    /** The [stateFingerprint] captured by the latest [snapshot]. */
    @Volatile var lastSnapshotFingerprint: String? = null
        private set

    fun markBackedUp(ctx: Context, fingerprint: String?) {
        prefs(ctx).edit().putString("backup_state", fingerprint).apply()
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
        ensureStarted(ctx)
        // The restored wallet is exactly what the file holds.
        markBackedUp(ctx, stateFingerprint())
    }

    data class Balance(
        val spendable: Long,
        val pendingBoard: Long,
        val pendingRound: Long,
        val needsRefresh: Long,
        val pendingExit: Long,
        val onchainConfirmed: Long,
        val onchainPending: Long,
        /** Lightning payments received but not yet settled into Ark coins, and sends in flight. */
        val lightningPending: Long = 0,
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
            lightningPending = a.optLong("claimable_lightning_receive_sat") + a.optLong("pending_lightning_send_sat"),
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
    /** [maxTotal] caps the debit at what the user approved (Ark addresses only). */
    fun send(destination: String, sats: Long?, maxTotal: Long? = null): String {
        val req = JSONObject().put("destination", destination.trim())
        if (sats != null) req.put("amount_sat", sats)
        if (maxTotal != null && destination.trim().startsWith("ark1", ignoreCase = true)) req.put("max_total_sat", maxTotal)
        val d = destination.trim()
        if (!d.startsWith("ark1", ignoreCase = true) && !d.startsWith("ln", ignoreCase = true)) {
            // An XBT address leaves Ark on-chain: an amount, or everything when there is none.
            return if (sats == null) {
                call("POST", "/wallet/offboard/all", JSONObject().put("address", d), 180_000)
                "Withdrawal requested: everything leaves Ark in the next round."
            } else {
                call("POST", "/wallet/send-onchain", JSONObject().put("destination", d).put("amount_sat", sats), 180_000)
                "Withdrawal requested: it leaves Ark in the next round."
            }
        }
        return JSONObject(call("POST", "/wallet/send", req, 180_000)).optString("message")
    }

    /**
     * What an operation will really cost, shown BEFORE the user confirms. Paperclip runs a
     * "funded recovery profile": each Ark transfer reserves sats to pre-pay its on-chain
     * emergency exit (about 6,000 with change, 4,000 without), and they are not refunded.
     * In fiat it is cents; in sats it can be most of a small payment, so it must be shown.
     */
    data class Estimate(
        val amount: Long, val fee: Long, val total: Long, val exact: Boolean, val note: String,
        /** Set when the engine would refuse this payment as it stands; the UI then offers no send. */
        val problem: String? = null,
    )

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
            d.startsWith("lno", ignoreCase = true) && sats == null ->
                Estimate(0, 0, 0, false, "", "This is a reusable BOLT12 offer: enter the amount to pay.")
            d.startsWith("ln", ignoreCase = true) -> {
                val amt = sats ?: invoiceSats(d) ?: return null
                feeQuery("/fees/lightning/pay?amount_sat=$amt")?.copy(
                    note = "Lightning: the server's fee plus the recovery reserve of the transfer.")
            }
            d.startsWith("ark1", ignoreCase = true) -> {
                val amt = sats ?: return null
                // The engine's own planner (Paperclip 0.7.7): the coins it will use and their
                // recovery reserves. Refused payments come back as an error, never a zero quote.
                try {
                    val r = JSONObject(call("POST", "/fees/ark/send",
                        JSONObject().put("destination", d).put("amount_sat", amt)))
                    val coins = r.optInt("input_count", 1)
                    Estimate(r.optLong("recipient_amount_sat"), r.optLong("recovery_reserve_sat") + r.optLong("service_fee_sat"),
                        r.optLong("total_debit_sat"), true,
                        "Recovery reserve pre-paid for the emergency exit of " +
                            (if (coins == 1) "the coin used" else "each of the $coins coins used") + "; not refunded." +
                            (if (coins > 1) " RENEW first joins your coins into one and makes payments cheaper." else ""))
                } catch (e: ArkError) {
                    Estimate(amt, 0, amt, false, "", (e.message ?: "The engine refused this payment.") + arkPaymentHint())
                }
            }
            else -> {
                val addr = java.net.URLEncoder.encode(d, "UTF-8")
                if (sats == null) {
                    // Empty amount to an XBT address: everything in Ark leaves to it.
                    feeQuery("/fees/offboard-all?address=$addr")?.let {
                        Estimate(it.amount, it.fee, it.total, true,
                            "Everything in Ark leaves to this address in the next round, minus the on-chain fee.")
                    }
                } else feeQuery("/fees/send-onchain?amount_sat=$sats&address=$addr")
                    ?.copy(note = "Leaves Ark through the next round and pays the on-chain fee.")
            }
        }
    }

    /**
     * What to try when an Ark payment is refused: an Ark payment must cover 3 recovery
     * reserves per coin and leave change of at least [MIN_OUTPUT_SAT].
     */
    private fun arkPaymentHint(): String = runCatching {
        val arr = JSONArray(call("GET", "/wallet/vtxos"))
        val biggest = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optJSONObject("state")?.optString("type") == "spendable" }
            .maxOfOrNull { it.optLong("amount_sat") } ?: 0L
        val maxOne = biggest - 3 * RESERVE_SAT - MIN_OUTPUT_SAT
        if (maxOne >= MIN_OUTPUT_SAT) " The most you can pay from one coin is $maxOne sats."
        else " To empty Ark, send to an XBT address (on-chain) instead."
    }.getOrDefault("")

    // ---------------------------------------------------------------- deposit (on-chain)

    /** A transaction of the on-chain deposit wallet; [height] is null while in the mempool. */
    data class DepositTx(val txid: String, val change: Long, val fee: Long?, val height: Int?)

    fun depositTxs(): List<DepositTx> = runCatching {
        val arr = JSONArray(call("GET", "/onchain/transactions"))
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { t ->
            DepositTx(t.getString("txid"), t.optLong("balance_change_sat"),
                t.optLong("onchain_fee_sat", -1).takeIf { it >= 0 },
                t.optJSONObject("confirmation")?.optInt("height"))
        }.reversed()
    }.getOrDefault(emptyList())

    /**
     * Sending straight from the deposit: plain on-chain XBT, no Ark reserves. The fee is an
     * estimate from the current rate and the size of a transaction spending every deposit
     * coin, which is what the wallet's coin selection does at worst.
     */
    fun estimateDepositSend(dest: String, sats: Long?): Estimate? = runCatching {
        val d = dest.trim()
        if (d.startsWith("ark1", ignoreCase = true) || d.startsWith("ln", ignoreCase = true)) {
            return@runCatching Estimate(0, 0, 0, false, "",
                "From the deposit you can only send to an XBT address. Choose ARK to pay an Ark address or Lightning.")
        }
        val b = balance()
        val available = b.onchainConfirmed
        val rate = JSONObject(call("GET", "/fees/onchain")).let { r ->
            listOf("regular_sat_per_vb", "slow_sat_per_vb", "fast_sat_per_vb").firstNotNullOfOrNull { k -> r.optDouble(k).takeIf { !it.isNaN() && it > 0 } } ?: 1.0
        }.coerceAtLeast(1.0)
        val inputs = runCatching { JSONArray(call("GET", "/onchain/utxos")).length() }.getOrDefault(1).coerceAtLeast(1)
        val outputs = if (sats == null) 1 else 2
        val fee = kotlin.math.ceil(rate * (11 + 58 * inputs + 43 * outputs)).toLong()
        val amount = sats ?: (available - fee)
        val problem = when {
            amount < 330 -> "Not enough confirmed in the deposit to send."
            sats != null && sats + fee > available ->
                "The deposit has $available sats confirmed; this needs ${sats + fee} with the fee."
            else -> null
        }
        Estimate(amount, fee, amount + fee, false,
            "Plain on-chain XBT from your deposit: only the network fee (about " +
                String.format(java.util.Locale.US, "%.1f", rate) + " sat/vB), no Ark reserves.", problem)
    }.getOrNull()

    fun sendFromDeposit(dest: String, sats: Long?): String {
        val d = dest.trim()
        val r = if (sats == null) JSONObject(call("POST", "/onchain/drain", JSONObject().put("destination", d), 120_000))
        else JSONObject(call("POST", "/onchain/send", JSONObject().put("destination", d).put("amount_sat", sats), 120_000))
        return "Sent from the deposit: " + r.optString("txid").take(16) + "…"
    }

    /**
     * Ark movements and the deposit's on-chain transactions in one list, newest first. A
     * deposit transaction that funded a move into Ark is left out: the move already shows.
     * On-chain entries have no clock time, only a block, so their time is estimated from it.
     */
    fun activity(): List<Movement> {
        val moves = history()
        val anchored = moves.mapNotNull { it.onchainTxid }.toSet()
        val tip = runCatching { JSONObject(call("GET", "/bitcoin/tip")).optInt("tip_height", -1) }.getOrDefault(-1)
        val now = System.currentTimeMillis()
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val deposits = depositTxs().filter { it.txid !in anchored && it.change != 0L }.map { t ->
            val ms = if (t.height == null || tip < 0) now else now - (tip - t.height).coerceAtLeast(0) * 600_000L
            Movement(
                id = "tx:" + t.txid,
                status = if (t.height == null) "unconfirmed" else "confirmed",
                kind = if (t.change > 0) "deposit received" else "sent from deposit",
                amount = t.change, time = iso.format(java.util.Date(ms)),
                fee = t.fee ?: 0, onchainTxid = t.txid,
            )
        }
        return (moves + deposits).sortedByDescending { it.time }
    }

    // ---------------------------------------------------------------- emergency exit

    data class ExitState(val vtxo: String, val type: String, val claimableHeight: Int?, val tip: Int?)

    fun exits(): List<ExitState> = runCatching {
        val arr = JSONArray(call("GET", "/exits/status/all"))
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { e ->
            val st = e.optJSONObject("state") ?: JSONObject()
            ExitState(e.optString("vtxo_id"), st.optString("type"),
                st.optInt("claimable_height").takeIf { it > 0 }, st.optInt("tip_height").takeIf { it > 0 })
        }
    }.getOrDefault(emptyList())

    private fun sats(o: JSONObject, k: String): Long =
        o.opt(k).let { v -> if (v is Number && v.toString().contains('.')) Math.round(v.toDouble() * 1e8) else (v as? Number)?.toLong() ?: 0L }

    /** What leaving Ark without the server costs: broadcasting the exit chain, then the claim. */
    fun estimateExit(): Estimate? = runCatching {
        val b = balance()
        if (b.spendable == 0L) return@runCatching Estimate(0, 0, 0, false, "", "You have no Ark coins to exit.")
        val r = JSONObject(call("GET", "/exits/fee"))
        val total = sats(r, "total_fee")
        val broadcast = sats(r, "exit_broadcast_fee")
        val deposit = b.onchainConfirmed
        Estimate(b.spendable - total, total, b.spendable, false,
            "broadcast $broadcast sats for " + r.optInt("txs_to_broadcast") + " transactions + claim " +
                sats(r, "claim_fee") + " sats",
            if (broadcast > deposit) "Broadcasting needs $broadcast sats confirmed in your on-chain deposit and " +
                "it has $deposit. Send that much to your DEPOSIT address first." else null)
    }.getOrElse { e -> if (e is ArkError) Estimate(0, 0, 0, false, "", e.message) else null }

    fun startExitAll(): String = JSONObject(call("POST", "/exits/start/all", JSONObject(), 120_000)).optString("message", "Exit started.")

    fun claimExits(dest: String): String {
        call("POST", "/exits/claim/all", JSONObject().put("destination", dest.trim()), 120_000)
        return "Claimed: the recovered XBT is on its way to the address."
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

    /**
     * This wallet's reusable BOLT12 offer (lno1…), creating one if there is none. Payers
     * ask the wallet for a fresh invoice through the Ark server, so it is only paid while
     * the engine is running. [sats] fixes the amount; null lets each payer choose.
     */
    fun reusableOffer(description: String, sats: Long?): String {
        runCatching { JSONObject(call("GET", "/lightning/offers")) }.getOrNull()
            ?.takeIf { it.optBoolean("active") && it.optString("offer").isNotEmpty() }
            ?.let { if (sats == null || it.optLong("amount_sat") == sats) return it.getString("offer") }
        val req = JSONObject().put("description", description)
        if (sats != null) req.put("amount_sat", sats)
        return JSONObject(call("POST", "/lightning/offers", req, 60_000)).getString("offer")
    }

    fun disableOffer() { call("DELETE", "/lightning/offers") }

    /** A Lightning invoice paid into this Ark wallet. */
    fun lightningInvoice(sats: Long, description: String?): String {
        val req = JSONObject().put("amount_sat", sats)
        if (!description.isNullOrBlank()) req.put("description", description)
        val r = JSONObject(call("POST", "/lightning/receives/invoice", req, 120_000))
        return r.optString("invoice", r.toString())
    }

    /** What renewing every coin costs now: the round fee, and what the new coin holds. */
    fun estimateRenew(): Estimate? = runCatching {
        val r = JSONObject(call("GET", "/fees/refresh-all"))
        val coins = r.optJSONArray("vtxos_spent")?.length() ?: 0
        Estimate(r.optLong("net_amount_sat"), r.optLong("fee_sat"), r.optLong("gross_amount_sat"), true,
            "$coins coin" + (if (coins == 1) "" else "s") + " renewed into one")
    }.getOrElse { e ->
        if (e !is ArkError) null
        else Estimate(0, 0, 0, false, "",
            if (e.message.orEmpty().contains("No VTXOs")) "You have no Ark coins to renew." else e.message)
    }

    /** Ask the engine to look at the chain now instead of at its next minute or block. */
    fun syncOnchain() { runCatching { call("POST", "/onchain/sync", JSONObject(), 60_000) } }

    /** Renews every coin close to expiry. The engine also does this by itself while running. */
    fun refreshAll() { call("POST", "/wallet/refresh/all", JSONObject(), 180_000) }

    data class Movement(
        val id: String, val status: String, val kind: String, val amount: Long, val time: String,
        val fee: Long = 0,
        val destinations: List<String> = emptyList(),
        /** The on-chain transaction behind this movement, when there is one. */
        val onchainTxid: String? = null,
        /** Ark coins (virtual outputs) this movement created, or spent when it created none. */
        val vtxos: List<String> = emptyList(),
        val paymentHash: String? = null,
        val preimage: String? = null,
    )

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
                kind = kindLabel(m.optJSONObject("subsystem")?.optString("name").orEmpty()).let { k ->
                    if (k == "Ark payment" && m.optLong("effective_balance_sat") > 0) "Ark received" else k
                },
                amount = m.optLong("effective_balance_sat", m.optLong("intended_balance_sat")),
                time = m.optJSONObject("time")?.optString("created_at") ?: "",
                fee = m.optLong("offchain_fee_sat") + (m.optJSONObject("metadata")?.optLong("onchain_fee_sat") ?: 0),
                destinations = m.optJSONArray("sent_to")?.let { a ->
                    (0 until a.length()).mapNotNull { a.getJSONObject(it).optJSONObject("destination")?.optString("value") }
                }.orEmpty(),
                onchainTxid = onchainTxid(m),
                vtxos = (strings(m.optJSONArray("output_vtxos")).ifEmpty { strings(m.optJSONArray("input_vtxos")) }),
                paymentHash = m.optJSONObject("metadata")?.optString("payment_hash")?.takeIf { it.isNotEmpty() },
                preimage = m.optJSONObject("metadata")?.optString("payment_preimage")?.takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }

    /**
     * The blockchain transaction behind a movement: the deposit moved into Ark, the round of
     * a renewal or withdrawal, or the coin's own transaction for an emergency exit. Ark and
     * Lightning payments have none: they stay off-chain until the coin is renewed or withdrawn.
     */
    private fun onchainTxid(m: JSONObject): String? {
        val meta = m.optJSONObject("metadata")
        meta?.optString("chain_anchor")?.takeIf { it.isNotEmpty() }?.let { return it.substringBefore(':') }
        for (k in listOf("funding_txid", "offboard_txid", "txid")) {
            meta?.optString(k)?.takeIf { it.length == 64 }?.let { return it }
        }
        if (m.optJSONObject("subsystem")?.optString("name") == "bark.exit") {
            return strings(m.optJSONArray("input_vtxos")).firstOrNull()?.substringBefore(':')
        }
        return null
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
