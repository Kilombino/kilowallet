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
    private const val KEY_POOLS_NOTIFIED = "pools_notified"
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
        // Keys kept for rounds on their own: only while the round is live and still on its own.
        AutoKeyVault.keepOnly(ctx, sessions.values.filter { !it.done && it.autoKeys != null }.map { it.state.poolId }.toSet())
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
            val s = PoolSession(env(ctx, st), st)
            // A round on its own carries on after the app was closed: its keys come back from the vault.
            if (!finished) AutoKeyVault.load(ctx, st.poolId)?.let { s.autoKeys = it }
            sessions[st.poolId] = s
        }
        appCtx = ctx.applicationContext
        _states.value = sessions.values.map { it.state }.sortedByDescending { it.created }
        _version.value++
        // Rounds are dropped after a week: their mixed outputs are kept for good first.
        runCatching { MixedCoins.keep(ctx) }
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

    /** A round past the open stage (voting, closing, signing): not the moment for interruptions. */
    fun inRound(): Boolean = synchronized(this) {
        sessions.values.any { it.state.phase in setOf(PoolSession.Phase.VOTING, PoolSession.Phase.CLOSING, PoolSession.Phase.SIGNING) }
    }

    /** True while some pool still needs this phone online. */
    fun hasActive(ctx: Context): Boolean {
        load(ctx)
        // A perfect coin waiting for its confirmation keeps the service up too: it joins on its own.
        return synchronized(this) { sessions.values.any { !it.done || it.state.phase == PoolSession.Phase.BROADCAST } } ||
            PerfectCoin.hasWaiting(ctx)
    }

    /** Coins already promised to a pool that is not over: a normal send must leave them alone. */
    fun lockedOutpoints(ctx: Context): Set<String> {
        load(ctx)
        // Only a round being built (outputs posted, signatures asked) holds its coins: spending
        // one then would spoil it for everyone. A pool still waiting for people, or voting,
        // lets its coins be spent ([waitingOutpoints]); the spend leaves that pool.
        return synchronized(this) {
            sessions.values.filter { !it.done && it.state.phase in setOf(PoolSession.Phase.CLOSING, PoolSession.Phase.SIGNING) }
                .flatMap { st -> st.state.seat.coins.map { it.outpoint } }.toSet()
        }
    }

    /** Coins seated in a pool that has not started its round, outpoint → pool id: spendable, at the cost of the seat. */
    fun waitingOutpoints(ctx: Context): Map<String, String> {
        load(ctx)
        return synchronized(this) {
            sessions.values.filter { !it.done && it.state.phase in setOf(PoolSession.Phase.JOINING, PoolSession.Phase.OPEN, PoolSession.Phase.VOTING) }
                .flatMap { st -> st.state.seat.coins.map { it.outpoint to st.state.poolId } }.toMap()
        }
    }

    /** Give up the seats of [poolIds] (their coin was just spent): the others are told, off the main thread. */
    fun leavePools(ctx: Context, poolIds: Collection<String>) {
        if (poolIds.isEmpty()) return
        load(ctx)
        val ss = synchronized(this) { poolIds.mapNotNull { sessions[it] } }
        Thread { ss.forEach { runCatching { it.leave() } } }.start()
    }

    /** Start every live session; called by the service. */
    fun startAll(ctx: Context) {
        load(ctx)
        val live = synchronized(this) { sessions.values.filter { !it.done } }
        live.forEach { s -> Thread { runCatching { s.start() } }.start() }
    }

    fun stopAll() = synchronized(this) { sessions.values.forEach { runCatching { it.stop() } } }

    // ------------------------------------------------------------------------------- joining

    /** One of our coins for a seat, with the key that spends it. */
    class PickCoin(val txid: String, val vout: Int, val value: Long, val coinKey: Bip32Priv.ExtendedPrivKey, val coinPath: String)

    /** Our coins (all of [type]), with fresh addresses of that type for the round: one per mixed output, and the change. */
    class Pick(
        val coins: List<PickCoin>, val type: ScriptType,
        val mixScripts: List<ByteArray>, val changeScript: ByteArray,
    ) {
        fun txCoins(): List<CoinjoinTx.Coin> = coins.map { CoinjoinTx.Coin(it.txid, it.vout, it.value, it.coinKey.publicKey(), type) }
    }

    // ------------------------------------------------------------------------------- on its own

    /**
     * Pools that run on their own: the keys of OUR coins in them, unlocked once at join and kept
     * in memory only (never written anywhere) until the round ends. With them the wallet accepts
     * a request to close, asks to close itself when the pool is about to expire with enough
     * people, and signs, always after [PoolSession] has checked that the transaction pays our
     * exact output and our change at our fee share (it refuses to sign otherwise). If the app is
     * killed the keys are gone and the round asks for the fingerprint as before.
     */
    fun isAuto(poolId: String): Boolean = session(poolId)?.autoKeys != null

    /** Whether new pools run on their own; on unless the user turned it off. */
    fun autoDefault(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("cj_auto", true)

    fun setAutoDefault(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("cj_auto", on).apply()

    @Volatile private var appCtx: Context? = null

    /** Run a round already joined on its own from now: [keys] of its coins, one unlock. */
    fun startAuto(poolId: String, keys: List<java.math.BigInteger>) {
        val s = session(poolId) ?: return
        s.autoKeys = keys
        appCtx?.let { AutoKeyVault.save(it, poolId, keys) }
        // Already asked to close, or waiting for a signature: answer now, through the same checks.
        if (s.state.phase == PoolSession.Phase.VOTING && s.state.votedOn != s.state.voteId) runCatching { s.vote(true) }
        if (s.state.phase == PoolSession.Phase.SIGNING && !s.state.postedSig) runCatching { s.sign(keys) }
        _version.value++
    }

    /** Stop running [poolId] on its own: its keys are dropped, and the next steps ask again. */
    fun stopAuto(poolId: String) {
        session(poolId)?.autoKeys = null
        appCtx?.let { AutoKeyVault.remove(it, poolId) }
        _version.value++
    }

    private fun add(ctx: Context, st: PoolSession.State, keys: List<java.math.BigInteger>? = null): PoolSession {
        val s = PoolSession(env(ctx, st), st)
        s.autoKeys = keys
        appCtx = ctx.applicationContext
        if (keys != null) AutoKeyVault.save(ctx, st.poolId, keys)
        synchronized(this) { sessions[st.poolId] = s }
        persist(ctx)
        CoinjoinService.start(ctx)
        return s
    }

    /** Open a new pool with our coin as the first seat. */
    fun create(
        ctx: Context, pick: Pick, amount: Long, feeRate: Double, maxPeers: Int, hours: Int,
        minPeers: Int = Protocol.MIN_PEERS, password: String? = null, maxInputs: Int = 1, auto: Boolean = false,
        outputs: Int = 1,
    ): PoolSession.State {
        // New pools are v3 (outputs registered blindly), v4 with several outputs each; older
        // wallets keep joining v1 and v2 ones.
        val np = PoolSession.newPool(amount, feeRate, maxPeers, hours, minPeers, !password.isNullOrEmpty(),
            pick.type, maxInputs, v3 = true, outputs = outputs)
        val st = PoolSession.newState(np.terms, true, np.secret, pick.txCoins(), pick.coins.map { it.coinKey.key }, pick.coins.map { it.coinPath },
            pick.mixScripts, pick.changeScript, password?.ifEmpty { null }, np.creatorSecret, np.blindPriv)
        add(ctx, st, if (auto) pick.coins.map { it.coinKey.key } else null)
        CoinjoinAlerts.follow(ctx, st)
        return st
    }

    fun join(ctx: Context, terms: Protocol.Terms, pick: Pick, password: String? = null, auto: Boolean = false): PoolSession.State {
        require(pick.type == terms.script) { "these coins are not of the pool's address type" }
        require(pick.mixScripts.size == terms.outputs) { "this pool gives ${terms.outputs} mixed outputs each" }
        val st = PoolSession.newState(terms, false, null, pick.txCoins(), pick.coins.map { it.coinKey.key }, pick.coins.map { it.coinPath },
            pick.mixScripts, pick.changeScript, password?.ifEmpty { null })
        add(ctx, st, if (auto) pick.coins.map { it.coinKey.key } else null)
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
        open.firstOrNull { firstNotice(ctx, it.id) }?.let { t -> notify(ctx, t.id.hashCode(), "Coinjoin pool open now",
            "${amountOf(t)} sats${kind(t)} · ${t.peers}/${t.maxPeers} people · ${t.feeRate} sat/vB") }
    }

    /**
     * True the first time a pool is to be notified, and remembered: its creator publishes it again
     * on every change (someone joins, a vote), with a newer time, and each of those read as a new
     * pool. Kept three days, longer than any pool stays open.
     */
    private fun firstNotice(ctx: Context, poolId: String): Boolean {
        val p = prefs(ctx)
        val now = System.currentTimeMillis() / 1000
        val kept = (p.getStringSet(KEY_POOLS_NOTIFIED, null) ?: emptySet())
            .filter { (it.substringAfterLast(':').toLongOrNull() ?: 0) > now - 3 * 86400 }
        if (kept.any { it.substringBeforeLast(':') == poolId }) return false
        p.edit().putStringSet(KEY_POOLS_NOTIFIED, (kept + "$poolId:$now").toSet()).apply()
        return true
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
        for (t in pools.asSequence().filter { firstNotice(ctx, it.id) }.take(3)) notify(ctx, t.id.hashCode(), "New coinjoin pool" + if (t.amount < Protocol.MIN_AMOUNT) " (test)" else "",
            "${amountOf(t)} sats${kind(t)} · ${t.peers}/${t.maxPeers} people · ${t.feeRate} sat/vB")
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
                c.isUnspent(Address.electrumScriptHash(coin.script), coin.txid, coin.vout, coin.value, coin.script)
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
                    if (sessions.containsKey(state.poolId)) CoinjoinAlerts.event(app, state.poolId, "left")
                    val gone = synchronized(this@CoinjoinHub) { sessions.remove(state.poolId) }
                    Thread { runCatching { gone?.stop() } }.start()
                }
                persist(app)
            }
            override fun event(e: PoolSession.Event) = onEvent(app, st, e)
        }
    }

    private fun sats(v: Long): String = "%,d".format(v).replace(',', ' ')
    /** " · Taproot" for a Taproot pool (bc1p coins only); nothing for the usual bc1q ones. */
    private fun kind(t: Protocol.Terms): String = if (t.script == ScriptType.P2TR) " · Taproot" else ""
    /** "4 × 1 000 000" for a v4 pool, the plain amount otherwise. */
    private fun amountOf(t: Protocol.Terms): String = if (t.outputs > 1) "${t.outputs} × ${sats(t.amount)}" else sats(t.amount)

    private fun onEvent(ctx: Context, st: PoolSession.State, e: PoolSession.Event) {
        alert(ctx, st, e)
        if (e is PoolSession.Event.Broadcast || e is PoolSession.Event.Confirmed) runCatching { MixedCoins.keep(ctx) }
        val id = ("cj" + st.poolId).hashCode()
        val pool = "${amountOf(st.terms)} sats pool"
        when (e) {
            is PoolSession.Event.Changed -> return
            is PoolSession.Event.Welcomed -> notify(ctx, id, "Coinjoin: you are in", "$pool · waiting for more people")
            is PoolSession.Event.Rejected -> notify(ctx, id, "Coinjoin: join refused", e.reason)
            is PoolSession.Event.Joined -> notify(ctx, id, "Coinjoin: someone joined", "$pool · ${e.peers}/${st.terms.maxPeers} people")
            is PoolSession.Event.CloseRequested -> if (!e.byMe && !isAuto(st.poolId))
                notify(ctx, id, "Coinjoin: close now?", "$pool · ${e.peers} people. Open the app to accept or refuse.")
            is PoolSession.Event.CloseRefused -> notify(ctx, id, "Coinjoin: stays open", "$pool · someone said no")
            is PoolSession.Event.Closing -> notify(ctx, id, "Coinjoin: closing", "$pool · ${e.peers} people, collecting outputs")
            is PoolSession.Event.DidAlone -> notify(ctx, id, when (e.what) {
                PoolSession.Alone.ACCEPTED -> "Coinjoin: closing"
                PoolSession.Alone.ASKED_TO_CLOSE -> "Coinjoin: asking to close"
                PoolSession.Alone.SIGNED -> "Coinjoin: signed on its own"
            }, when (e.what) {
                PoolSession.Alone.ACCEPTED -> "$pool · accepted on its own"
                PoolSession.Alone.ASKED_TO_CLOSE -> "$pool · enough people and under an hour left: asked on its own"
                PoolSession.Alone.SIGNED -> "$pool · your output and change checked out"
            })
            is PoolSession.Event.SignNeeded -> notify(ctx, id, "Coinjoin: sign now",
                "$pool · the transaction is ready. Open the app and sign within ${Protocol.SIGN_SECONDS / 60} min.")
            is PoolSession.Event.Broadcast -> notify(ctx, id, "Coinjoin sent", "$pool · ${e.txid.take(16)}…")
            is PoolSession.Event.Confirmed -> notify(ctx, id, "Coinjoin confirmed ✅", "$pool · ${e.txid.take(16)}…")
            is PoolSession.Event.Aborted -> notify(ctx, id, "Coinjoin cancelled", "$pool · ${e.reason}. Your coin did not move.")
            is PoolSession.Event.ExpiringSoon -> notify(ctx, id, "Coinjoin: under an hour left",
                "$pool · ${e.peers}/${st.terms.maxPeers} people, enough to mix. It expires in ${e.minutes} min: open the app and tap ASK TO CLOSE NOW.")
        }
        CoinjoinService.refresh(ctx)
    }

    /** Telegram alerts (optional): the steps only this app can see, for the user's own pools. */
    private fun alert(ctx: Context, st: PoolSession.State, e: PoolSession.Event) {
        if (!CoinjoinAlerts.enabled(ctx)) return
        val vote = st.voteId ?: ""
        when (e) {
            is PoolSession.Event.Welcomed -> CoinjoinAlerts.follow(ctx, st)
            is PoolSession.Event.CloseRequested -> CoinjoinAlerts.event(ctx, st.poolId, if (e.byMe) "close_asked" else "close_requested", vote)
            is PoolSession.Event.CloseRefused -> CoinjoinAlerts.event(ctx, st.poolId, "close_refused", vote)
            is PoolSession.Event.SignNeeded -> CoinjoinAlerts.event(ctx, st.poolId, "sign_needed")
            is PoolSession.Event.Confirmed -> CoinjoinAlerts.event(ctx, st.poolId, "confirmed")
            // No free text: an abort reason can be another member's own words.
            is PoolSession.Event.Aborted -> CoinjoinAlerts.event(ctx, st.poolId,
                if (e.reason == PoolSession.LEFT_BY_CHOICE) "left" else "aborted")
            else -> {}
        }
    }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Coinjoin", NotificationManager.IMPORTANCE_HIGH))
    }

    fun openApp(ctx: Context): PendingIntent =
        com.kilombino.pyblockwatch.data.OpenTab.pending(ctx, com.kilombino.pyblockwatch.data.OpenTab.COINJOIN)

    /** An alert from outside a round (the perfect coin). */
    fun notifyPublic(ctx: Context, title: String, text: String) = notify(ctx, 4300 + (title.hashCode() and 0xff), title, text)

    private fun notify(ctx: Context, id: Int, title: String, text: String) {
        ensureChannel(ctx)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(com.kilombino.pyblockwatch.ui.i18n.I18n.tr(title)).setContentText(com.kilombino.pyblockwatch.ui.i18n.I18n.tr(text))
            .setStyle(Notification.BigTextStyle().bigText(com.kilombino.pyblockwatch.ui.i18n.I18n.tr(text)))
            .setAutoCancel(true).setContentIntent(openApp(ctx)).build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
    }

    /** A hot wallet's own account is native SegWit; its Taproot one (if on) can do Taproot pools. */
    fun supported(store: Store): Boolean = store.scriptType == ScriptType.P2WPKH
}
