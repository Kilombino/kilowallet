package com.kilombino.pyblockwatch.coinjoin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's side of coinjoin: keeps every pool this wallet is in (persisted, so a round
 * survives the app being killed), runs their sessions while [CoinjoinService] is up, lists
 * the open pools on the relay, and turns session events into notifications.
 */
object CoinjoinHub {
    private const val PREFS = "coinjoin"
    private const val KEY_SESSIONS = "sessions"
    private const val KEY_LAST_POOL_SEEN = "last_pool_seen"
    private const val KEY_TEST_POOLS = "test_pools"
    const val CHANNEL = "coinjoin"

    private val sessions = linkedMapOf<String, PoolSession>()
    private val _states = MutableStateFlow<List<PoolSession.State>>(emptyList())
    /** Our pools, newest first, refreshed on every change. */
    val states: StateFlow<List<PoolSession.State>> = _states.asStateFlow()
    /**
     * Bumped on every change. The states are mutated in place, so a new list of the same
     * objects compares equal and [states] alone would not tell the screen anything changed.
     */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun persist(ctx: Context) {
        val a = JSONArray(); sessions.values.forEach { a.put(it.state.toJson()) }
        prefs(ctx).edit().putString(KEY_SESSIONS, a.toString()).apply()
        _states.value = sessions.values.map { it.state }.sortedByDescending { it.created }
        _version.value++
    }

    /** Load saved pools (once per process). Finished ones older than a week are dropped. */
    @Synchronized
    fun load(ctx: Context) {
        if (sessions.isNotEmpty()) return
        val raw = prefs(ctx).getString(KEY_SESSIONS, null) ?: return
        val weekAgo = System.currentTimeMillis() / 1000 - 7 * 86400
        val a = runCatching { JSONArray(raw) }.getOrNull() ?: return
        for (i in 0 until a.length()) {
            val st = runCatching { PoolSession.State.parse(a.getJSONObject(i)) }.getOrNull() ?: continue
            val finished = st.phase in setOf(PoolSession.Phase.CONFIRMED, PoolSession.Phase.ABORTED, PoolSession.Phase.REJECTED)
            if (finished && st.created < weekAgo) continue
            sessions[st.poolId] = PoolSession(env(ctx, st), st)
        }
        _states.value = sessions.values.map { it.state }.sortedByDescending { it.created }
        _version.value++
    }

    /** Whether this user wants to see (and be told about) test pools under 10 000 sats. */
    fun testPools(@Suppress("UNUSED_PARAMETER") ctx: Context): Boolean = false // betas only
    fun setTestPools(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_TEST_POOLS, on).apply()

    /** Pull-to-refresh on the COINJOIN tab: the screen reloads the pool list when this moves. */
    private val _refreshRequests = MutableStateFlow(0)
    val refreshRequests: StateFlow<Int> = _refreshRequests.asStateFlow()
    fun requestRefresh() { _refreshRequests.value++; _version.value++ }

    /** Drop what is in memory and load again from storage (after a restore). */
    fun reload(ctx: Context) {
        stopAll()
        synchronized(this) { sessions.clear() }
        load(ctx)
        if (hasActive(ctx)) CoinjoinService.start(ctx)
    }

    fun session(poolId: String): PoolSession? = synchronized(this) { sessions[poolId] }

    /** True while some pool still needs this phone online. */
    fun hasActive(ctx: Context): Boolean { load(ctx); return synchronized(this) { sessions.values.any { !it.done || it.state.phase == PoolSession.Phase.BROADCAST } } }

    /** Coins already promised to a pool that is not over: a normal send must leave them alone. */
    fun lockedOutpoints(ctx: Context): Set<String> {
        load(ctx)
        return synchronized(this) {
            sessions.values.filter { !it.done && it.state.phase != PoolSession.Phase.BROADCAST }
                .map { it.state.seat.coin.outpoint }.toSet()
        }
    }

    /** Start every live session; called by the service. */
    fun startAll(ctx: Context) {
        load(ctx)
        val live = synchronized(this) { sessions.values.filter { !it.done } }
        live.forEach { s -> Thread { runCatching { s.start() } }.start() }
    }

    fun stopAll() = synchronized(this) { sessions.values.forEach { runCatching { it.stop() } } }

    // ------------------------------------------------------------------------------- joining

    /** A coin of ours, with the key that spends it and fresh addresses for the round. */
    class Pick(
        val txid: String, val vout: Int, val value: Long,
        val coinKey: Bip32Priv.ExtendedPrivKey, val coinPath: String,
        val mixScript: ByteArray, val changeScript: ByteArray,
    )

    private fun add(ctx: Context, st: PoolSession.State): PoolSession {
        val s = PoolSession(env(ctx, st), st)
        synchronized(this) { sessions[st.poolId] = s }
        persist(ctx)
        CoinjoinService.start(ctx)
        return s
    }

    /** Open a new pool with our coin as the first seat. */
    fun create(
        ctx: Context, pick: Pick, amount: Long, feeRate: Double, maxPeers: Int, hours: Int,
        minPeers: Int = Protocol.MIN_PEERS, password: String? = null,
    ): PoolSession.State {
        val (terms, secret) = PoolSession.newPool(amount, feeRate, maxPeers, hours, minPeers, !password.isNullOrEmpty())
        val coin = CoinjoinTx.Coin(pick.txid, pick.vout, pick.value, pick.coinKey.publicKey())
        val st = PoolSession.newState(terms, true, secret, coin, pick.coinKey.key, pick.coinPath, pick.mixScript, pick.changeScript,
            password?.ifEmpty { null })
        add(ctx, st)
        return st
    }

    fun join(ctx: Context, terms: Protocol.Terms, pick: Pick, password: String? = null): PoolSession.State {
        val coin = CoinjoinTx.Coin(pick.txid, pick.vout, pick.value, pick.coinKey.publicKey())
        val st = PoolSession.newState(terms, false, null, coin, pick.coinKey.key, pick.coinPath, pick.mixScript, pick.changeScript,
            password?.ifEmpty { null })
        add(ctx, st)
        return st
    }

    /** Forget a finished pool from the list. */
    fun remove(ctx: Context, poolId: String) {
        synchronized(this) { sessions.remove(poolId)?.stop() }
        persist(ctx)
    }

    // ------------------------------------------------------------------------------- the open pools

    /** Open pools on the relay right now (BLAKE2b, not expired), newest first. */
    fun fetchPools(sinceSeconds: Long = 3 * 86400): List<Protocol.Terms> {
        val found = java.util.concurrent.ConcurrentHashMap<String, Protocol.Terms>()
        val r = RelayClient(Protocol.RELAY, { _, ev ->
            Protocol.Terms.parse(ev)?.let { t ->
                val old = found[t.id]
                if (old == null || old.createdAt < t.createdAt) found[t.id] = t
            }
        })
        try {
            r.connect()
            r.subscribe("pools", listOf(JSONObject().put("kinds", JSONArray().put(Protocol.KIND_POOL))
                .put("#t", JSONArray().put(Protocol.TAG))
                .put("since", System.currentTimeMillis() / 1000 - sinceSeconds)))
        } finally { r.close() }
        val now = System.currentTimeMillis() / 1000
        return found.values.filter {
            it.state == "open" && it.expiresAt > now && it.peers < it.maxPeers && now - it.createdAt < Protocol.STALE_AFTER
        }
            .sortedByDescending { it.createdAt }
    }

    /**
     * Right after the user switches coinjoin notifications on: tell them about the newest pool
     * that is open now, if any, so they see what a notification looks like straight away.
     */
    fun notifyOpenNow(ctx: Context) {
        load(ctx)
        val ours = synchronized(this) { sessions.keys.toSet() }
        val open = fetchPools().filter { it.amount >= Protocol.MIN_AMOUNT && !it.private && it.id !in ours }
        open.maxOfOrNull { it.createdAt }?.let { prefs(ctx).edit().putLong(KEY_LAST_POOL_SEEN, it).apply() }
        open.firstOrNull()?.let { t -> notify(ctx, t.id.hashCode(), "Coinjoin pool open now",
            "${sats(t.amount)} sats · ${t.peers}/${t.maxPeers} people · ${t.feeRate} sat/vB") }
    }

    /** For the background watcher: notify pools opened since the last look, other than ours. */
    fun checkNewPools(ctx: Context) {
        load(ctx)
        if (!Store(ctx).coinjoinNotify) return
        val p = prefs(ctx)
        val last = p.getLong(KEY_LAST_POOL_SEEN, System.currentTimeMillis() / 1000 - 3600)
        val ours = synchronized(this) { sessions.keys.toSet() }
        val test = testPools(ctx)
        // Test pools only for whoever switched them on; private pools are announced by their creator.
        val pools = fetchPools(sinceSeconds = 86400).filter {
            it.createdAt > last && it.id !in ours && (test || it.amount >= Protocol.MIN_AMOUNT) && !it.private
        }
        pools.maxOfOrNull { it.createdAt }?.let { p.edit().putLong(KEY_LAST_POOL_SEEN, it).apply() }
        for (t in pools.take(3)) notify(ctx, t.id.hashCode(), "New coinjoin pool" + if (t.amount < Protocol.MIN_AMOUNT) " (test)" else "",
            "${sats(t.amount)} sats · ${t.peers}/${t.maxPeers} people · ${t.feeRate} sat/vB")
    }

    // ------------------------------------------------------------------------------- environment

    private fun env(ctx: Context, st: PoolSession.State): PoolSession.Env {
        val app = ctx.applicationContext
        return object : PoolSession.Env {
            private fun <T> electrum(f: (ElectrumClient) -> T): T {
                val store = Store(app)
                val e = store.endpoint(Chain.BLAKE2B)
                val c = ElectrumClient(e, store.pinnedFingerprint(e))
                return try { c.connect(); f(c) } finally { c.close() }
            }
            override fun coinUnspent(coin: CoinjoinTx.Coin): Boolean = electrum { c ->
                c.listUnspent(Address.electrumScriptHash(coin.script))
                    .any { it.txid == coin.txid && it.vout == coin.vout && it.value == coin.value }
            }
            override fun broadcast(rawHex: String): String = electrum { it.broadcast(rawHex) }
            override fun confirmations(txid: String): Int? = electrum { c ->
                // Our mixed output's address sees the transaction, confirmed or not.
                val h = c.history(Address.electrumScriptHash(st.mixScript)).firstOrNull { it.txid == txid } ?: return@electrum null
                if (h.height <= 0) 0 else c.blockHeight() - h.height + 1
            }
            override fun save(state: PoolSession.State) {
                // Left on purpose ("not yet", LEAVE): nothing to keep, and the pool goes back
                // to the open list so the user can join again straight away.
                if (state.phase == PoolSession.Phase.ABORTED && state.reason == PoolSession.LEFT_BY_CHOICE) {
                    val gone = synchronized(this@CoinjoinHub) { sessions.remove(state.poolId) }
                    Thread { runCatching { gone?.stop() } }.start()
                }
                persist(app)
            }
            override fun event(e: PoolSession.Event) = onEvent(app, st, e)
        }
    }

    private fun sats(v: Long): String = "%,d".format(v).replace(',', ' ')

    private fun onEvent(ctx: Context, st: PoolSession.State, e: PoolSession.Event) {
        val id = ("cj" + st.poolId).hashCode()
        val pool = "${sats(st.terms.amount)} sats pool"
        when (e) {
            is PoolSession.Event.Changed -> return
            is PoolSession.Event.Welcomed -> notify(ctx, id, "Coinjoin: you are in", "$pool · waiting for more people")
            is PoolSession.Event.Rejected -> notify(ctx, id, "Coinjoin: join refused", e.reason)
            is PoolSession.Event.Joined -> notify(ctx, id, "Coinjoin: someone joined", "$pool · ${e.peers}/${st.terms.maxPeers} people")
            is PoolSession.Event.CloseRequested -> if (!e.byMe)
                notify(ctx, id, "Coinjoin: close now?", "$pool · ${e.peers} people. Open the app to accept or refuse.")
            is PoolSession.Event.CloseRefused -> notify(ctx, id, "Coinjoin: stays open", "$pool · someone said no")
            is PoolSession.Event.Closing -> notify(ctx, id, "Coinjoin: closing", "$pool · ${e.peers} people, collecting outputs")
            is PoolSession.Event.SignNeeded -> notify(ctx, id, "Coinjoin: sign now",
                "$pool · the transaction is ready. Open the app and sign within ${Protocol.SIGN_SECONDS / 60} min.")
            is PoolSession.Event.Broadcast -> notify(ctx, id, "Coinjoin sent", "$pool · ${e.txid.take(16)}…")
            is PoolSession.Event.Confirmed -> notify(ctx, id, "Coinjoin confirmed ✅", "$pool · ${e.txid.take(16)}…")
            is PoolSession.Event.Aborted -> notify(ctx, id, "Coinjoin cancelled", "$pool · ${e.reason}. Your coin did not move.")
        }
        CoinjoinService.refresh(ctx)
    }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Coinjoin", NotificationManager.IMPORTANCE_HIGH))
    }

    fun openApp(ctx: Context): PendingIntent =
        com.kilombino.pyblockwatch.data.OpenTab.pending(ctx, com.kilombino.pyblockwatch.data.OpenTab.COINJOIN)

    private fun notify(ctx: Context, id: Int, title: String, text: String) {
        ensureChannel(ctx)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(openApp(ctx)).build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
    }

    /** Only P2WPKH wallets can join: every input and output must look the same. */
    fun supported(store: Store): Boolean = store.scriptType == ScriptType.P2WPKH
}
