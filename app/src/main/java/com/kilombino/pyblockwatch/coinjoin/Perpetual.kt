package com.kilombino.pyblockwatch.coinjoin

import android.content.Context
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bip32
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.TxParse
import com.kilombino.pyblockwatch.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.SecureRandom

/**
 * A perpetual coinjoin: a coin that, each time its round confirms, mixes again on its own. Its
 * mixed output joins another open pool that fits (one output per person, of its address type, at
 * most the fee cap, best with no change), or opens one with the whole coin (no change). Every round
 * costs it its fee share, so it shrinks a little each time. It goes on until the coin is spent some
 * other way, the user turns it off, it falls below a pool's minimum, or the fees rise above the cap.
 *
 * Only for the hot wallet's own outputs and pools of one output each: several outputs of one person
 * remixing at the same moment would be tied together by their timing. A change, when a pool needs
 * one, is never remixed.
 */
class Remix(
    val id: String,
    val type: ScriptType,
    /** The coin that mixes next (WAITING): its outpoint, value and receive index; null before its first round ends. */
    var txid: String? = null, var vout: Int = 0, var value: Long = 0, var index: Int = -1,
    /** The most it pays per vB; by default the first pool's rate. */
    var cap: Double,
    /** The first pool's rate: what a pool opened for it asks, unless the chain needs more (up to [cap]). */
    val rate: Double,
    val minPeers: Int, val maxPeers: Int, val hours: Int,
    var state: State = State.SEATED,
    /** The seat it is in now ([PoolSession.State.key]), and where that seat's mixed output goes. */
    var seat: String? = null, var seatIndex: Int = -1, var seatScript: ByteArray? = null,
    /** Rounds it went through (confirmed), and when the next look for a pool is due. */
    var rounds: Int = 0, var nextAt: Long = 0,
    /** Why it stopped, or what went wrong on the last try. */
    var reason: String = "",
    /** Pools that refused it: not tried again. */
    val tried: MutableSet<String> = linkedSetOf(),
    /** What the coin was worth when it began, to show what the rounds cost. */
    var startValue: Long = 0,
    val since: Long = System.currentTimeMillis() / 1000,
) {
    enum class State { SEATED, WAITING, STOPPED }

    val outpoint: String? get() = txid?.let { "$it:$vout" }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type.name).put("txid", txid ?: "").put("vout", vout)
        .put("value", value).put("index", index).put("cap", cap).put("rate", rate).put("minPeers", minPeers)
        .put("maxPeers", maxPeers).put("hours", hours).put("state", state.name).put("seat", seat ?: "")
        .put("seatIndex", seatIndex).put("seatScript", seatScript?.toHex() ?: "").put("rounds", rounds)
        .put("nextAt", nextAt).put("reason", reason).put("tried", JSONArray(tried.toList())).put("startValue", startValue)
        .put("since", since).put("coinScript", coinScript?.toHex() ?: "")

    /** The coin's own script (its address), kept to ask the server about it. */
    var coinScript: ByteArray? = null

    companion object {
        fun parse(o: JSONObject): Remix = Remix(
            o.getString("id"), ScriptType.valueOf(o.getString("type")), o.optString("txid").ifEmpty { null },
            o.optInt("vout"), o.optLong("value"), o.optInt("index", -1), o.getDouble("cap"), o.getDouble("rate"),
            o.getInt("minPeers"), o.getInt("maxPeers"), o.getInt("hours"), State.valueOf(o.getString("state")),
            o.optString("seat").ifEmpty { null }, o.optInt("seatIndex", -1),
            o.optString("seatScript").ifEmpty { null }?.let { Hashes.hexToBytes(it) }, o.optInt("rounds"), o.optLong("nextAt"),
            o.optString("reason"),
            o.optJSONArray("tried")?.let { a -> (0 until a.length()).map { a.getString(it) } }?.toMutableSet() ?: linkedSetOf(),
            o.optLong("startValue"), o.optLong("since"),
        ).also { r -> r.coinScript = o.optString("coinScript").ifEmpty { null }?.let { Hashes.hexToBytes(it) } }

        fun listJson(list: List<Remix>): String = JSONArray(list.map { it.toJson() }).toString()
        fun parseList(s: String?): List<Remix> = runCatching {
            val a = JSONArray(s ?: "[]"); (0 until a.length()).mapNotNull { runCatching { parse(a.getJSONObject(it)) }.getOrNull() }
        }.getOrDefault(emptyList())

        /** Reasons it stops on its own, as the card says them. */
        const val MOVED = "the coin was spent or moved"
        const val BELOW_MINIMUM = "the coin fell below a pool's minimum"
        const val OFF = "turned off"
        const val FIRST_CANCELLED = "its first round was cancelled: the coin did not move"
        const val NO_KEYS = "its keys are not on this phone any more: turn it on again"
        fun overCap(cap: Double) = "the fees rose above your cap (${fmt(cap)} sat/vB)"
        fun fmt(r: Double): String = if (r == Math.floor(r)) "%.0f".format(java.util.Locale.ROOT, r) else "%.1f".format(java.util.Locale.ROOT, r)
    }
}

/**
 * What a perpetual coin does next, step by step: the decisions alone, with the wallet, the relay and
 * the chain behind [Env], so the same code runs in the app and against a regtest node in the tests.
 */
class Remixer(private val env: Env) {
    interface Env {
        fun now(): Long = System.currentTimeMillis() / 1000
        /** The random wait before each join or new pool, in seconds. */
        fun delay(): Long = 20L + SecureRandom().nextInt(71)
        /** A seat's state, or null when it is gone (removed from the list). */
        fun seat(key: String): PoolSession.State?
        /** Index and value of the output paying [script] in [txid]; null when the server can't say. */
        fun outputOf(txid: String, script: ByteArray): Pair<Int, Long>?
        /** Whether the coin is still unspent; null when the server can't be asked. */
        fun unspent(r: Remix): Boolean?
        /** The chain's fee rate now (sat/vB), or null when it has no estimate. */
        fun feeEstimate(): Double?
        fun openPools(): List<Protocol.Terms>
        /** Pools a seat of this phone is in, not over: never one more seat there. */
        fun busyPools(): Set<String>
        /** Joins [t] with the coin; the new seat's key, and its mixed output's receive index and script. */
        fun join(r: Remix, t: Protocol.Terms): NewSeat
        /** Opens a pool of [amount] at [rate] with the whole coin (no change). */
        fun create(r: Remix, amount: Long, rate: Double): NewSeat
        fun log(msg: String) {}
    }

    class NewSeat(val key: String, val mixIndex: Int, val mixScript: ByteArray)

    /** Moves [r] on if it is time; true when anything about it changed. */
    fun step(r: Remix): Boolean {
        val now = env.now()
        when (r.state) {
            Remix.State.STOPPED -> {
                // Turned off mid-round: the round still ends as any other; its output becomes the coin
                // it would go on with if turned on again.
                val key = r.seat ?: return false
                val st = env.seat(key)
                return when {
                    st == null || st.phase == PoolSession.Phase.ABORTED || st.phase == PoolSession.Phase.REJECTED -> { r.seat = null; true }
                    st.phase == PoolSession.Phase.CONFIRMED -> advance(r, st) && run { r.seat = null; true }
                    else -> false
                }
            }
            Remix.State.SEATED -> {
                val st = r.seat?.let { env.seat(it) }
                when (st?.phase) {
                    PoolSession.Phase.CONFIRMED -> {
                        if (!advance(r, st)) return false
                        r.seat = null; r.state = Remix.State.WAITING; r.nextAt = now + env.delay(); r.reason = ""
                        env.log("remix ${r.id}: round ${r.rounds} confirmed, ${r.value} sats, next look in ${r.nextAt - now} s")
                        return true
                    }
                    null, PoolSession.Phase.ABORTED, PoolSession.Phase.REJECTED -> {
                        // Refused, or never let in (its creator gone): that pool is not tried again.
                        if (st != null && (st.phase == PoolSession.Phase.REJECTED || st.token == null)) r.tried += st.poolId
                        r.seat = null
                        // The coin did not move. Before its first round ends there is none of ours to go on with.
                        if (r.txid == null) { r.state = Remix.State.STOPPED; r.reason = Remix.FIRST_CANCELLED; return true }
                        r.state = Remix.State.WAITING; r.nextAt = now + env.delay()
                        r.reason = st?.reason ?: ""
                        env.log("remix ${r.id}: its pool ended (${st?.reason}); next look in ${r.nextAt - now} s")
                        return true
                    }
                    else -> return false
                }
            }
            Remix.State.WAITING -> {
                if (now < r.nextAt) return false
                return look(r, now)
            }
        }
    }

    /** The round [st] confirmed: its mixed output is the coin now. False when the server can't say yet. */
    private fun advance(r: Remix, st: PoolSession.State): Boolean {
        val txid = st.txid ?: return false
        val script = r.seatScript ?: st.mixScript
        val (vout, value) = env.outputOf(txid, script) ?: return false
        if (r.startValue == 0L) r.startValue = st.seat.inputValue
        r.txid = txid; r.vout = vout; r.value = value; r.index = r.seatIndex; r.coinScript = script
        r.rounds++
        return true
    }

    private fun look(r: Remix, now: Long): Boolean {
        fun stop(why: String): Boolean { r.state = Remix.State.STOPPED; r.reason = why; env.log("remix ${r.id} stops: $why"); return true }
        fun later(why: String, s: Long = 120): Boolean { r.nextAt = now + s; r.reason = why; env.log("remix ${r.id}: $why"); return true }
        if (r.txid == null) return stop(Remix.FIRST_CANCELLED)
        when (env.unspent(r)) {
            false -> return stop(Remix.MOVED)
            null -> return later("the server could not be asked about the coin", 60)
            true -> {}
        }
        val rate = createRate(r, env.feeEstimate()) ?: return stop(Remix.overCap(r.cap))
        val busy = env.busyPools()
        val pick = runCatching { choose(r, env.openPools(), now, busy) }.getOrElse { return later("could not reach the relay: ${it.message}") }
        try {
            val seat = if (pick != null) env.join(r, pick) else {
                val amount = createAmount(r.value, rate, r.type) ?: return stop(Remix.BELOW_MINIMUM)
                env.create(r, amount, rate)
            }
            r.state = Remix.State.SEATED; r.seat = seat.key; r.seatIndex = seat.mixIndex; r.seatScript = seat.mixScript; r.reason = ""
            env.log("remix ${r.id}: " + (if (pick != null) "joined ${pick.id}" else "opened ${seat.key}"))
            return true
        } catch (e: Exception) {
            return later(e.message ?: "could not join a pool")
        }
    }

    companion object {
        /** Least time a pool joined must still have before it expires. */
        const val MIN_LEFT = 20 * 60L

        /**
         * The rate of a pool opened for [r]: always the fee the user chose ([Remix.cap], set from the
         * first pool and changeable on its card), whatever the chain asks now. Pools of others are
         * joined only at exactly that fee, so it always pays what was chosen and never stops for it.
         */
        @Suppress("UNUSED_PARAMETER")
        fun createRate(r: Remix, estimate: Double?): Double? = r.cap

        /** The pool amount the whole coin makes with no change, or null below the minimum. */
        fun createAmount(value: Long, rate: Double, type: ScriptType): Long? =
            CoinjoinTx.noChangeAmount(value, 1, rate, type, 1)?.takeIf { it >= Protocol.MIN_AMOUNT && it <= Protocol.MAX_AMOUNT }

        /** Whether [t] can take [r]'s coin: one output each, its type, open, not ours, at most the cap, and the coin covers it. */
        fun fits(r: Remix, t: Protocol.Terms, now: Long, busy: Set<String>): Boolean =
            t.state == "open" && t.script == r.type && t.outputs == 1 && t.v3 && !t.private && t.peers < t.maxPeers &&
                t.expiresAt - now >= MIN_LEFT && kotlin.math.abs(t.feeRate - r.cap) < 1e-9 && !Protocol.isTest(t) && t.id !in busy &&
                t.id !in r.tried && CoinjoinTx.changeOf(r.value, t.mixTotal, t.feeRate, 1, r.type, 1) != null

        /** The pool to join: one with no change first, then the one leaving the least change; null for none. */
        fun choose(r: Remix, pools: List<Protocol.Terms>, now: Long, busy: Set<String>): Protocol.Terms? {
            val ok = pools.filter { fits(r, it, now, busy) }
            fun change(t: Protocol.Terms) = CoinjoinTx.changeOf(r.value, t.mixTotal, t.feeRate, 1, r.type, 1) ?: Long.MAX_VALUE
            return ok.minWithOrNull(compareBy({ if (change(it) == 0L) 0 else 1 }, { change(it) }, { -it.peers }))
        }
    }
}

/**
 * The app's perpetual coins: kept on the phone, moved on every minute by [CoinjoinService] (and at
 * each round's end), and their keys. To join a pool on its own, again and again, each new mixed
 * output's key is needed without the fingerprint: the key of the hot wallet's receive branch of
 * that address type is kept, encrypted with [AutoKeyVault] like a round on its own, while any
 * perpetual coin of that type is on, and deleted when none is.
 */
object Perpetual {
    private const val PREFS = "cj_perpetual"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun branchKey(type: ScriptType) = "perpbranch:" + type.name

    private val _list = MutableStateFlow<List<Remix>>(emptyList())
    val list: StateFlow<List<Remix>> = _list.asStateFlow()

    @Synchronized fun load(ctx: Context): List<Remix> = Remix.parseList(prefs(ctx).getString("list", null)).also { _list.value = it }

    @Synchronized private fun save(ctx: Context, l: List<Remix>) {
        prefs(ctx).edit().putString("list", Remix.listJson(l)).apply()
        _list.value = l
        // A branch key stays only while a perpetual coin of its type may still need it.
        val need = l.filter { it.state != Remix.State.STOPPED || it.seat != null }.map { it.type }.toSet()
        ScriptType.entries.filter { it !in need }.forEach { AutoKeyVault.remove(ctx, branchKey(it)) }
        if (l.size > 50) prefs(ctx).edit().putString("list", Remix.listJson(l.takeLast(50))).apply()
    }

    @Synchronized private fun edit(ctx: Context, id: String, f: (Remix) -> Unit) {
        val l = load(ctx); l.firstOrNull { it.id == id }?.let(f); save(ctx, l)
    }

    /** Coins of perpetual remixes waiting for their next pool: not offered to a pool by hand. */
    fun waitingOutpoints(ctx: Context): Set<String> =
        load(ctx).filter { it.state == Remix.State.WAITING }.mapNotNull { it.outpoint }.toSet()

    /** The perpetual coin of each outpoint it holds now (waiting, or stopped with its coin). */
    fun byOutpoint(ctx: Context): Map<String, Remix> = load(ctx).filter { it.outpoint != null && it.seat == null }.associateBy { it.outpoint!! }

    fun ofSeat(key: String): Remix? = _list.value.firstOrNull { it.seat == key }

    fun hasLive(ctx: Context): Boolean = load(ctx).any { it.state != Remix.State.STOPPED }

    /**
     * Starts a perpetual coin with the round just joined or opened ([seatKey]), whose single mixed
     * output is receive address [mixIndex] of the hot wallet's [type] account; [branch] is that
     * account's receive branch key (m/purpose'/0'/0'/0), kept for the rounds to come.
     */
    fun start(ctx: Context, seatKey: String, type: ScriptType, branch: Bip32Priv.ExtendedPrivKey, mixIndex: Int, mixScript: ByteArray,
              terms: Protocol.Terms, cap: Double, perpId: String, hours: Int) {
        AutoKeyVault.save(ctx, branchKey(type), listOf(branch.key, BigInteger(1, branch.chainCode)))
        val r = Remix(perpId, type, cap = cap, rate = terms.feeRate, minPeers = terms.minPeers, maxPeers = terms.maxPeers,
            hours = hours.coerceIn(1, 72),
            seat = seatKey, seatIndex = mixIndex, seatScript = mixScript)
        synchronized(this) { save(ctx, load(ctx).filter { it.id != perpId } + r) }
    }

    /** A new id for a perpetual coin. */
    fun newId(): String = ByteArray(8).also { SecureRandom().nextBytes(it) }.toHex()

    fun turnOff(ctx: Context, id: String) = edit(ctx, id) { it.state = Remix.State.STOPPED; it.reason = Remix.OFF }

    /** On again, with the branch key from one fingerprint ([branch]); the coin must still be there. */
    fun turnOn(ctx: Context, id: String, branch: Bip32Priv.ExtendedPrivKey) {
        val r = load(ctx).firstOrNull { it.id == id } ?: return
        AutoKeyVault.save(ctx, branchKey(r.type), listOf(branch.key, BigInteger(1, branch.chainCode)))
        edit(ctx, id) {
            if (it.seat != null) { it.state = Remix.State.SEATED; it.reason = "" }
            else if (it.txid != null) { it.state = Remix.State.WAITING; it.nextAt = System.currentTimeMillis() / 1000 + 20 + SecureRandom().nextInt(71); it.reason = "" }
        }
        CoinjoinService.start(ctx)
    }

    fun setCap(ctx: Context, id: String, cap: Double) = edit(ctx, id) { it.cap = cap }

    fun forget(ctx: Context, id: String) = synchronized(this) { save(ctx, load(ctx).filter { it.id != id }) }

    private fun branch(ctx: Context, type: ScriptType): Bip32Priv.ExtendedPrivKey? =
        AutoKeyVault.load(ctx, branchKey(type))?.takeIf { it.size == 2 }?.let { (k, c) ->
            Bip32Priv.ExtendedPrivKey(k, c.toByteArray().let { b -> if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b }, 4)
        }

    @Volatile private var checking = false

    /** Moves every perpetual coin on; called by the coinjoin service every minute and when a round ends. */
    fun check(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(this) { if (checking) return; checking = true }
        try {
            CoinjoinHub.load(app)
            val remixer = Remixer(AndroidEnv(app))
            for (r in load(app)) {
                // Each one read again just before it moves: the user may have turned it off meanwhile.
                val now = load(app).firstOrNull { it.id == r.id } ?: continue
                if (now.state != Remix.State.STOPPED && branch(app, now.type) == null && now.state == Remix.State.WAITING) {
                    now.state = Remix.State.STOPPED; now.reason = Remix.NO_KEYS
                    synchronized(this) { save(app, load(app).map { if (it.id == now.id) now else it }) }
                    continue
                }
                val changed = runCatching { remixer.step(now) }.getOrElse { false }
                if (changed) synchronized(this) {
                    // Turned off while it was looking: that wins, whatever the step did.
                    val cur = load(app).firstOrNull { it.id == now.id }
                    if (cur?.state == Remix.State.STOPPED && cur.reason == Remix.OFF && now.state != Remix.State.STOPPED) {
                        now.state = Remix.State.STOPPED; now.reason = Remix.OFF
                    }
                    save(app, load(app).map { if (it.id == now.id) now else it })
                }
            }
        } finally { checking = false }
    }

    private class AndroidEnv(val ctx: Context) : Remixer.Env {
        private fun <T> electrum(f: (ElectrumClient) -> T): T {
            val store = Store(ctx); val e = store.endpoint(Chain.BLAKE2B)
            val c = ElectrumClient(e, store.pinnedFingerprint(e))
            return try { c.connect(); f(c) } finally { c.close() }
        }
        override fun seat(key: String) = CoinjoinHub.session(key)?.state
        override fun outputOf(txid: String, script: ByteArray): Pair<Int, Long>? = runCatching {
            electrum { c -> TxParse.parse(c.transaction(txid)).outputs.withIndex()
                .firstOrNull { it.value.scriptPubKey.contentEquals(script) }?.let { it.index to it.value.value } }
        }.getOrNull()
        override fun unspent(r: Remix): Boolean? = runCatching {
            val sp = r.coinScript ?: return null
            electrum { c -> c.isUnspent(Address.electrumScriptHash(sp), r.txid!!, r.vout, r.value, sp) }
        }.getOrNull()
        override fun feeEstimate(): Double? = runCatching {
            electrum { c -> maxOf(c.estimateFeePerKb(3), c.relayFeePerKb()).takeIf { it > 0 }?.let { it * 100_000.0 } }
        }.getOrNull()
        override fun openPools() = CoinjoinHub.fetchPools()
        override fun busyPools() = CoinjoinHub.busyPools(ctx)
        override fun log(msg: String) { android.util.Log.i("Kilowallet", msg) }

        /** The hot wallet's [type] account key, public. */
        private fun account(type: ScriptType): Pair<String, Bip32.ExtendedPubKey> {
            val h = Store(ctx, Store.HOT)
            val x = (if (type == ScriptType.P2TR) h.taprootXpub else h.xpub) ?: error("there is no hot wallet")
            return x to Bip32.parseExtendedPubKey(x)
        }

        /** The next unused address of [chainIndex] no seat holds, asked to the server; its index. */
        private fun fresh(type: ScriptType, chainIndex: Int, skip: Set<Int> = emptySet()): Int {
            val (x, acc) = account(type)
            val held = Destination.reserved(ctx)
            val derive = { i: Int -> Address.scriptPubKey(Bip32.derivePath(acc, chainIndex, i).pubkey(), type) }
            return electrum { c ->
                Destination.pickUnusedIndexes(1 + skip.size, Store(ctx, Store.HOT).usedTop(x, chainIndex, type), held, derive,
                    hasHistory = { sp -> c.history(Address.electrumScriptHash(sp)).isNotEmpty() })
            }.first { it !in skip }
        }

        private fun pick(r: Remix, mixTotal: Long, rate: Double): Pair<CoinjoinHub.Pick, Int> {
            val b = branch(ctx, r.type) ?: error(Remix.NO_KEYS)
            val coinKey = Bip32Priv.deriveChild(b, r.index)
            check(Address.scriptPubKey(coinKey.publicKey(), r.type).contentEquals(r.coinScript)) { "the coin's key does not match it" }
            val (_, acc) = account(r.type)
            val mixIndex = fresh(r.type, 0)
            val mix = Address.scriptPubKey(Bip32.derivePath(acc, 0, mixIndex).pubkey(), r.type)
            val change = CoinjoinTx.changeOf(r.value, mixTotal, rate, 1, r.type, 1) ?: error("the coin does not cover the pool")
            val changeScript = if (change > 0) Address.scriptPubKey(Bip32.derivePath(acc, 1, fresh(r.type, 1)).pubkey(), r.type)
                else Address.scriptPubKey(Bip32.derivePath(acc, 1, 0).pubkey(), r.type) // unused: no change
            val path = "m/${com.kilombino.pyblockwatch.data.Scanner.purposeFor(r.type)}'/0'/0'/0/${r.index}"
            return CoinjoinHub.Pick(listOf(CoinjoinHub.PickCoin(r.txid!!, r.vout, r.value, coinKey, path)), r.type, listOf(mix), changeScript) to mixIndex
        }

        override fun join(r: Remix, t: Protocol.Terms): Remixer.NewSeat {
            val (p, idx) = pick(r, t.mixTotal, t.feeRate)
            val st = CoinjoinHub.join(ctx, t, p, null, auto = true, perpId = r.id)
            return Remixer.NewSeat(st.key, idx, p.mixScripts.first())
        }

        override fun create(r: Remix, amount: Long, rate: Double): Remixer.NewSeat {
            val (p, idx) = pick(r, amount, rate)
            val st = CoinjoinHub.create(ctx, p, amount, rate, r.maxPeers, r.hours, r.minPeers, null, 1, auto = true, outputs = 1, perpId = r.id)
            return Remixer.NewSeat(st.key, idx, p.mixScripts.first())
        }
    }
}
