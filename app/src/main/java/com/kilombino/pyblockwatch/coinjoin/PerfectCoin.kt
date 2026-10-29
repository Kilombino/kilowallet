package com.kilombino.pyblockwatch.coinjoin

import android.content.Context
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.TxParse
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The perfect coin of a pool, sent and waiting for its first confirmation. The fingerprint that
 * signed the payment to ourselves also gave the coin's key, kept encrypted with [AutoKeyVault]
 * like a round on its own, and the mixed-output and change addresses of the seat, reserved then.
 * Once the coin confirms, the pool is joined (or opened) by itself and runs on its own: no
 * screen has to stay open and nothing more is asked.
 */
object PerfectCoin {
    private const val PREFS = "cj_perfect_coin"

    class Waiting(
        val txid: String, val vout: Int, val value: Long, val type: ScriptType, val path: String,
        val script: ByteArray, val mixScripts: List<ByteArray>, val changeScript: ByteArray,
        /** Join this pool… */
        val poolId: String?, val poolPassword: String?,
        /** …or open one with these terms. */
        val create: JSONObject?,
        val since: Long = System.currentTimeMillis() / 1000,
        /** The joined pool's terms as the user saw and accepted them ([agreed]). */
        val terms: JSONObject? = null,
        /** Another seat in a pool this phone is already in: joins as one (its own key, connection, random wait). */
        val extraSeat: Boolean = false,
        /** A creator's own private pool: the pool's key from its password (hex). */
        val passwordKey: String? = null,
        /** A perpetual coinjoin from the round it joins: its fee cap, and the hot wallet's receive index of its mixed output. */
        val perpCap: Double? = null, val mixIndex: Int = -1,
        /** Where its round's mixed outputs go when not to the hot wallet ([Destination.note]), for the round's card. */
        val dest: String? = null,
    ) {
        val key get() = "exact:$txid"
        /** The same coin, now at [txid]:[vout] (its payment was replaced to speed it up). */
        fun movedTo(txid: String, vout: Int) = Waiting(txid, vout, value, type, path, script, mixScripts, changeScript,
            poolId, poolPassword, create, since, terms, extraSeat, passwordKey, perpCap, mixIndex, dest)
        fun toJson(): JSONObject = JSONObject().put("txid", txid).put("vout", vout).put("value", value).put("type", type.name)
            .put("path", path).put("script", script.toHex()).put("mix", mixScripts.first().toHex())
            .put("mixes", JSONArray(mixScripts.map { it.toHex() })).put("change", changeScript.toHex())
            .put("pool", poolId ?: "").put("poolPassword", poolPassword ?: "").put("create", create ?: JSONObject.NULL).put("since", since)
            .put("terms", terms ?: JSONObject.NULL)
            .put("extraSeat", extraSeat).put("passwordKey", passwordKey ?: "").put("perpCap", perpCap ?: JSONObject.NULL).put("mixIndex", mixIndex)
            .put("dest", dest ?: "")

        companion object {
            private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            fun parse(o: JSONObject) = Waiting(o.getString("txid"), o.getInt("vout"), o.getLong("value"), ScriptType.valueOf(o.getString("type")),
                o.getString("path"), hex(o.getString("script")),
                o.optJSONArray("mixes")?.let { a -> (0 until a.length()).map { hex(a.getString(it)) } } ?: listOf(hex(o.getString("mix"))),
                hex(o.getString("change")),
                o.optString("pool").ifEmpty { null }, o.optString("poolPassword").ifEmpty { null }, o.optJSONObject("create"), o.optLong("since"),
                o.optJSONObject("terms"), o.optBoolean("extraSeat"), o.optString("passwordKey").ifEmpty { null },
                if (o.isNull("perpCap") || !o.has("perpCap")) null else o.getDouble("perpCap"), o.optInt("mixIndex", -1),
                o.optString("dest").ifEmpty { null })
        }
    }

    /** What the user agreed to when choosing [t]: the parts of the pool's terms that decide what is signed. */
    fun agreed(t: Protocol.Terms): JSONObject = JSONObject().put("pub", t.poolPub).put("amount", t.amount).put("feeRate", t.feeRate)
        .put("script", t.script.name).put("version", t.version).put("maxInputs", t.maxInputs).put("maxPeers", t.maxPeers)
        .put("outputs", t.outputs).put("payments", t.payments.joinToString(","))

    /**
     * Why the pool as it reads now is not the one the user agreed to, or null. Joining on its own
     * must not sign anything the user did not see: another key, amount, fee, type or size is a
     * different pool, however it is named. A coin waiting from before this check (no terms kept)
     * is not joined on its own either.
     */
    fun changed(agreed: JSONObject?, now: Protocol.Terms): String? {
        if (agreed == null) return "its terms were not kept"
        val a = agreed
        return when {
            a.optString("pub") != now.poolPub -> "the pool's key changed"
            a.optLong("amount") != now.amount -> "its amount changed"
            a.optDouble("feeRate") != now.feeRate -> "its fee changed"
            a.optString("script") != now.script.name || a.optInt("version") != now.version -> "its kind changed"
            a.optInt("maxInputs") != now.maxInputs || a.optInt("maxPeers") != now.maxPeers -> "its size changed"
            a.optInt("outputs", 1) != now.outputs -> "its outputs changed"
            a.optString("payments") != now.payments.joinToString(",") -> "its payments changed"
            now.state != "open" -> "it is no longer open"
            else -> null
        }
    }

    private val _waiting = MutableStateFlow<List<Waiting>>(emptyList())
    val waiting: StateFlow<List<Waiting>> = _waiting.asStateFlow()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(ctx: Context): List<Waiting> {
        val a = runCatching { JSONArray(prefs(ctx).getString("list", "[]")) }.getOrDefault(JSONArray())
        val week = System.currentTimeMillis() / 1000 - 7 * 86400
        val all = (0 until a.length()).mapNotNull { runCatching { Waiting.parse(a.getJSONObject(it)) }.getOrNull() }
        val list = all.filter { it.since > week }
        // A coin that waited more than a week is dropped: its key goes with it, not left on disk.
        all.filter { it.since <= week }.forEach { AutoKeyVault.remove(ctx, it.key) }
        if (list.size != all.size) prefs(ctx).edit().putString("list", JSONArray(list.map { it.toJson() }).toString()).apply()
        _waiting.value = list
        return list
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Waiting>) {
        prefs(ctx).edit().putString("list", JSONArray(list.map { it.toJson() }).toString()).apply()
        _waiting.value = list
    }

    /**
     * The payment to ourselves went out: remember it, its key and the seat's addresses; with a perpetual
     * coinjoin, also the receive branch key ([branch]) its later rounds sign with, kept with the coin's.
     */
    fun expect(ctx: Context, w: Waiting, coinKey: java.math.BigInteger, branch: Bip32Priv.ExtendedPrivKey? = null) {
        AutoKeyVault.save(ctx, w.key, listOfNotNull(coinKey, branch?.key, branch?.let { java.math.BigInteger(1, it.chainCode) }))
        save(ctx, load(ctx).filter { it.txid != w.txid } + w)
        CoinjoinService.start(ctx)
    }

    fun cancel(ctx: Context, txid: String) {
        AutoKeyVault.remove(ctx, "exact:$txid")
        save(ctx, load(ctx).filter { it.txid != txid })
    }

    fun hasWaiting(ctx: Context) = load(ctx).isNotEmpty()

    /** [w]'s payment was replaced (RBF): follow the coin to its new transaction, key and all. */
    @Synchronized
    private fun follow(ctx: Context, w: Waiting, txid: String, vout: Int) {
        val moved = w.movedTo(txid, vout)
        AutoKeyVault.load(ctx, w.key)?.let { AutoKeyVault.save(ctx, moved.key, it) }
        AutoKeyVault.remove(ctx, w.key)
        save(ctx, load(ctx).map { if (it.txid == w.txid) moved else it })
    }

    /**
     * Looks at each coin waiting; the confirmed ones join (or open) their pool on their own.
     * Called by the coinjoin service every minute.
     */
    fun check(ctx: Context) {
        val app = ctx.applicationContext
        for (w in load(app)) {
            // Its payment, or the one that replaced it: the coin's address is fresh, so whatever
            // pays it is that payment (sped up with RBF, the transaction and its id change).
            var replaced: Pair<String, TxParse.Out?>? = null
            val confirmed = runCatching {
                val store = Store(app); val e = store.endpoint(Chain.BLAKE2B)
                val c = ElectrumClient(e, store.pinnedFingerprint(e))
                try {
                    c.connect()
                    val hist = c.history(Address.electrumScriptHash(w.script))
                    val h = hist.firstOrNull { it.txid == w.txid }
                    if (h == null) for (other in hist) {
                        val outs = TxParse.parse(c.transaction(other.txid)).outputs
                        val i = outs.indexOfFirst { it.scriptPubKey.contentEquals(w.script) }
                        if (i >= 0) { replaced = "${other.txid}:$i" to outs[i]; break }
                    }
                    h != null && h.height > 0
                } finally { c.close() }
            }.getOrDefault(false)
            replaced?.let { (at, out) ->
                if (out != null && out.value == w.value) follow(app, w, at.substringBefore(':'), at.substringAfter(':').toInt())
                else {
                    // The speed-up took its fee from the coin itself: it is no longer the exact amount.
                    cancel(app, w.txid)
                    CoinjoinHub.notifyPublic(app, "Perfect coin changed", "Speeding it up changed its amount, so it no longer " +
                        "fits the pool. It stays in your wallet: join a pool with it by hand in COINJOIN.")
                }
            }
            if (!confirmed) continue
            val keys = AutoKeyVault.load(app, w.key)
            val k = keys?.firstOrNull()
            val branch = keys?.takeIf { it.size == 3 && w.perpCap != null && w.mixIndex >= 0 }?.let { (_, b, c) ->
                Bip32Priv.ExtendedPrivKey(b, c.toByteArray().let { x -> if (x.size > 32) x.copyOfRange(x.size - 32, x.size) else ByteArray(32 - x.size) + x }, 4)
            }
            cancel(app, w.txid)
            if (k == null) { CoinjoinHub.notifyPublic(app, "Perfect coin ready", "${w.value} sats confirmed. Join a pool with it in COINJOIN."); continue }
            runCatching {
                val node = Bip32Priv.ExtendedPrivKey(k, ByteArray(32), 5)
                val pick = CoinjoinHub.Pick(listOf(CoinjoinHub.PickCoin(w.txid, w.vout, w.value, node, w.path)), w.type, w.mixScripts, w.changeScript)
                val c = w.create
                val perpId = if (branch != null) Perpetual.newId() else null
                val st = if (c != null) CoinjoinHub.create(app, pick, c.getLong("amount"), c.getDouble("feeRate"), c.getInt("maxPeers"), c.getInt("hours"),
                    c.getInt("minPeers"), c.optString("password").ifEmpty { null }, c.optInt("maxInputs", 1), auto = true,
                    outputs = c.optInt("outputs", 1), perpId = perpId)
                else {
                    val t = CoinjoinHub.fetchPools().firstOrNull { it.id == w.poolId } ?: error("that pool is no longer open")
                    changed(w.terms, t)?.let { error("the pool is not the one you chose ($it)") }
                    // Another seat of ours: the hub gives it a seat of its own (own key, connection and wait),
                    // as long as our first seat is still in the pool; else it is just our seat there.
                    CoinjoinHub.join(app, t, pick, w.poolPassword, auto = true, perpId = perpId,
                        passwordKey = w.passwordKey?.let { com.kilombino.pyblockwatch.crypto.Hashes.hexToBytes(it) })
                }
                // Its round's card and notifications say where the mixed outputs go, as when joining by hand.
                // (A coin prepared before 1.4.1 has no note: its addresses are looked for in the other wallets.)
                (w.dest ?: Destination.guess(app, w.type, w.mixScripts)?.let { Destination.note(app, it) })
                    ?.let { Destination.rememberNote(app, st.key, it) }
                // The perpetual coinjoin chosen with the coin goes on from this round.
                if (branch != null && perpId != null && st.terms.outputs == 1) Perpetual.start(app, st.key, w.type, branch, w.mixIndex,
                    w.mixScripts.first(), st.terms, w.perpCap!!, perpId, c?.optInt("hours", 6) ?: 6,
                    FirstPool.of(st, c?.optInt("hours", 6) ?: 6))
            }.onSuccess { CoinjoinHub.notifyPublic(app, "Perfect coin in", "Confirmed: " + (if (w.create != null) "your pool is open" else "asked to join the pool") + ", on its own.") }
             .onFailure { CoinjoinHub.notifyPublic(app, "Perfect coin ready", "Confirmed, but ${it.message}. Join a pool with it in COINJOIN.") }
        }
    }
}
