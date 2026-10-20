package com.kilombino.pyblockwatch.coinjoin

import android.content.Context
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.crypto.Address
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
    ) {
        val key get() = "exact:$txid"
        fun toJson(): JSONObject = JSONObject().put("txid", txid).put("vout", vout).put("value", value).put("type", type.name)
            .put("path", path).put("script", script.toHex()).put("mix", mixScripts.first().toHex())
            .put("mixes", JSONArray(mixScripts.map { it.toHex() })).put("change", changeScript.toHex())
            .put("pool", poolId ?: "").put("poolPassword", poolPassword ?: "").put("create", create ?: JSONObject.NULL).put("since", since)
            .put("terms", terms ?: JSONObject.NULL)

        companion object {
            private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            fun parse(o: JSONObject) = Waiting(o.getString("txid"), o.getInt("vout"), o.getLong("value"), ScriptType.valueOf(o.getString("type")),
                o.getString("path"), hex(o.getString("script")),
                o.optJSONArray("mixes")?.let { a -> (0 until a.length()).map { hex(a.getString(it)) } } ?: listOf(hex(o.getString("mix"))),
                hex(o.getString("change")),
                o.optString("pool").ifEmpty { null }, o.optString("poolPassword").ifEmpty { null }, o.optJSONObject("create"), o.optLong("since"),
                o.optJSONObject("terms"))
        }
    }

    /** What the user agreed to when choosing [t]: the parts of the pool's terms that decide what is signed. */
    fun agreed(t: Protocol.Terms): JSONObject = JSONObject().put("pub", t.poolPub).put("amount", t.amount).put("feeRate", t.feeRate)
        .put("script", t.script.name).put("version", t.version).put("maxInputs", t.maxInputs).put("maxPeers", t.maxPeers)
        .put("outputs", t.outputs)

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

    /** The payment to ourselves went out: remember it, its key and the seat's addresses. */
    fun expect(ctx: Context, w: Waiting, coinKey: java.math.BigInteger) {
        AutoKeyVault.save(ctx, w.key, listOf(coinKey))
        save(ctx, load(ctx).filter { it.txid != w.txid } + w)
        CoinjoinService.start(ctx)
    }

    fun cancel(ctx: Context, txid: String) {
        AutoKeyVault.remove(ctx, "exact:$txid")
        save(ctx, load(ctx).filter { it.txid != txid })
    }

    fun hasWaiting(ctx: Context) = load(ctx).isNotEmpty()

    /**
     * Looks at each coin waiting; the confirmed ones join (or open) their pool on their own.
     * Called by the coinjoin service every minute.
     */
    fun check(ctx: Context) {
        val app = ctx.applicationContext
        for (w in load(app)) {
            val confirmed = runCatching {
                val store = Store(app); val e = store.endpoint(Chain.BLAKE2B)
                val c = ElectrumClient(e, store.pinnedFingerprint(e))
                try {
                    c.connect()
                    val h = c.history(Address.electrumScriptHash(w.script)).firstOrNull { it.txid == w.txid }
                    h != null && h.height > 0
                } finally { c.close() }
            }.getOrDefault(false)
            if (!confirmed) continue
            val k = AutoKeyVault.load(app, w.key)?.firstOrNull()
            cancel(app, w.txid)
            if (k == null) { CoinjoinHub.notifyPublic(app, "Perfect coin ready", "${w.value} sats confirmed. Join a pool with it in COINJOIN."); continue }
            runCatching {
                val node = Bip32Priv.ExtendedPrivKey(k, ByteArray(32), 5)
                val pick = CoinjoinHub.Pick(listOf(CoinjoinHub.PickCoin(w.txid, w.vout, w.value, node, w.path)), w.type, w.mixScripts, w.changeScript)
                val c = w.create
                if (c != null) CoinjoinHub.create(app, pick, c.getLong("amount"), c.getDouble("feeRate"), c.getInt("maxPeers"), c.getInt("hours"),
                    c.getInt("minPeers"), c.optString("password").ifEmpty { null }, c.optInt("maxInputs", 1), auto = true,
                    outputs = c.optInt("outputs", 1))
                else {
                    val t = CoinjoinHub.fetchPools().firstOrNull { it.id == w.poolId } ?: error("that pool is no longer open")
                    changed(w.terms, t)?.let { error("the pool is not the one you chose ($it)") }
                    CoinjoinHub.join(app, t, pick, w.poolPassword, auto = true)
                }
            }.onSuccess { CoinjoinHub.notifyPublic(app, "Perfect coin in", "Confirmed: " + (if (w.create != null) "your pool is open" else "asked to join the pool") + ", on its own.") }
             .onFailure { CoinjoinHub.notifyPublic(app, "Perfect coin ready", "Confirmed, but ${it.message}. Join a pool with it in COINJOIN.") }
        }
    }
}
