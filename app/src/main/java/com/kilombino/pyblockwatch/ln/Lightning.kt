package com.kilombino.pyblockwatch.ln

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The LN tab's side of the node: whether Lightning is on, bringing [Lnd] up (start, create or
 * unlock its wallet, add the watchtower, reach Kilombino's node), and a status the screen
 * draws, refreshed while it is shown.
 */
object Lightning {
    /** Kilombino's node: offered for the first channel, never required. */
    const val KILOMBINO_NODE = "0222d36d7b634ead665c982145f6e4507ad65c4f369902ab3ca11e379d2139dc60"
    /**
     * Its clearnet address, a name of ours kept on AirVPN's current exit IPs. AirDNS's own name is
     * the fallback: on 25 Oct 2026 it kept the old IPs for over an hour after AirVPN changed them.
     */
    const val KILOMBINO_HOST = "ln.kilombino.com:50002"
    val KILOMBINO_HOSTS = listOf(KILOMBINO_HOST, "nodoblake2b.airdns.org:50002")
    const val KILOMBINO_ONION = "eu2pntt4zzsvm2fkcn5uaqxac2mj5t2fk5ecwyhhejp3deu7env2h2ad.onion:9735"
    /** Kilombino's watchtower (onion: reached when Tor is on). */
    const val TOWER = "033f366c8e43b040dbc27648d68e123650ec1f23176de092f39b9e0a7fb4f2a95d"
    const val TOWER_HOST = "b44c2u3dar4fdhb4m4vsp3v6kzohh2cqrggk2io74v4olr4nn5nytyad.onion:9911"

    private const val PREFS = "lightning"
    /** 2026-09-01 00:00 UTC, around the BLAKE2b activation (block 961640): no node of these words is older. */
    private const val ACTIVATION_TIME = 1788220800L
    /** A channel backup from a restored backup file, handed to the node when its wallet is made. */
    private const val RESTORE_SCB = "lnd-restore.backup"

    /**
     * What the backup file keeps of Lightning: the wallet's birthday and the static channel
     * backup (LND keeps it current in channel.backup, encrypted with the node's keys). With it
     * and the words, a new phone can ask each peer to close the channels and get the money back.
     */
    fun exportBackup(ctx: Context): org.json.JSONObject? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val birthday = prefs.getLong("birthday", 0L)
        if (birthday == 0L) return null
        val o = org.json.JSONObject().put("birthday", birthday)
        channelBackupFile(ctx).takeIf { it.exists() }?.let {
            o.put("scb", android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP))
        }
        return o
    }

    /** LND's own channel backup file, kept current by the node (encrypted with its keys). */
    fun channelBackupFile(ctx: Context) = File(Lnd.dir(ctx), "data/chain/bitcoin/mainnet/channel.backup")

    /** A channel backup file from the old phone, handed to the node when its wallet is made here. */
    fun stageScb(ctx: Context, bytes: ByteArray) = File(ctx.noBackupFilesDir, RESTORE_SCB).writeBytes(bytes)
    fun hasStagedScb(ctx: Context) = File(ctx.noBackupFilesDir, RESTORE_SCB).exists()

    /** Keeps what [exportBackup] saved for the next time Lightning is turned on here. */
    fun stageRestore(ctx: Context, o: org.json.JSONObject) {
        o.optLong("birthday").takeIf { it > 0 }?.let {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("birthday", it).apply()
        }
        o.optString("scb").takeIf { it.isNotEmpty() }?.let {
            File(ctx.noBackupFilesDir, RESTORE_SCB).writeBytes(android.util.Base64.decode(it, android.util.Base64.NO_WRAP))
        }
    }

    sealed class Status {
        object Off : Status()
        /** [height] is how far the chain sync has got, when known (shown against the tip). */
        data class Starting(val step: String, val height: Int? = null) : Status()
        data class Running(
            val info: Lnd.Info, val balances: Lnd.Balances?, val channels: List<Lnd.Channel>,
            val pending: List<Lnd.Pending>, val tip: Int,
            val closing: List<Lnd.Closing> = emptyList(),
            val invoices: List<Lnd.Invoice> = emptyList(),
            val payments: List<Lnd.Payment> = emptyList(),
        ) : Status()
        data class Failed(val message: String) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var poller: Job? = null
    /** The chain tip the wallet's own server reports, to show how far the node has synced. */
    @Volatile var chainTip: Int = 0

    /** This phone's node was moved to another phone ([LnMove]). */
    fun movedAway(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("moved_away", false)

    fun markOff() { _status.value = Status.Off }

    // ---------------------------------------------------------------- incoming on-chain (FUND)
    // The light node only learns of a payment once it is in a block (neutrino sees no mempool),
    // so for its FUND addresses the explorer is asked as well: an incoming payment shows (and
    // notifies) at 0 confirmations, and again when it confirms.

    fun rememberFundAddress(ctx: Context, a: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = (p.getString("fund_addrs", "") ?: "").lines().filter { it.isNotBlank() && it != a } + a
        p.edit().putString("fund_addrs", list.takeLast(30).joinToString("\n")).apply()
    }

    /** A payment this app is waiting on: the PAY dialog may be gone, the app in the background. */
    data class Paying(val key: String, val sats: Long)
    private val _paying = MutableStateFlow<List<Paying>>(emptyList())
    val paying: StateFlow<List<Paying>> = _paying.asStateFlow()
    /** How the last payment started here ended, for the PAY card: (text, paid). */
    private val _payResult = MutableStateFlow<Triple<String, Long, Boolean>?>(null)
    val payResult: StateFlow<Triple<String, Long, Boolean>?> = _payResult.asStateFlow()
    fun clearPayResult() { _payResult.value = null }

    /**
     * Runs a payment outside the screen, so closing the dialog or leaving the app doesn't lose
     * it: the outcome comes back in [payResult] and as a notification. [key] is the payment hash
     * (or the offer): one payment per key at a time.
     */
    fun startPayment(ctx: Context, key: String, sats: Long, pay: suspend () -> Unit) {
        val app = ctx.applicationContext
        synchronized(_paying) {
            if (_paying.value.any { it.key == key }) return
            _paying.value = _paying.value + Paying(key, sats)
        }
        scope.launch {
            val r = runCatching { pay() }
            synchronized(_paying) { _paying.value = _paying.value.filterNot { it.key == key } }
            val n = com.kilombino.pyblockwatch.data.Notifier(app)
            r.onSuccess { _payResult.value = Triple("", sats, true); n.lightningPaid(sats, key) }
                .onFailure { e ->
                    val why = Lnd.payError(e)
                    _payResult.value = Triple(why, sats, false)
                    n.lightningNotPaid(sats, why, key)
                }
            runCatching { refreshHere() }
        }
    }

    /**
     * Payments still on their way from an earlier run (LND keeps them across restarts): followed
     * to their end here, so the result is shown and notified like a payment started now.
     */
    private fun followInFlight(app: Context, payments: List<Lnd.Payment>) {
        // A BOLT 12 payment is keyed by its offer until it ends; don't follow it twice.
        if (_paying.value.isNotEmpty()) return
        for (p in payments.filter { it.status == 1 && it.hash.isNotBlank() })
            startPayment(app, p.hash, p.sats) { Lnd.track(p.hash) }
    }

    @Volatile private var invoiceWatch = false

    /** Notifies each invoice paid to this node while it runs (and those paid since the last one notified). */
    private fun watchInvoices(app: Context) {
        if (invoiceWatch) return
        invoiceWatch = true
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Lnd.subscribeSettled(prefs.getLong("settle_index", 0L), onSettled = { memo, sats, index, hash ->
            if (index > prefs.getLong("settle_index", 0L)) {
                prefs.edit().putLong("settle_index", index).apply()
                com.kilombino.pyblockwatch.data.Notifier(app).lightningReceived(sats, memo, hash)
                scope.launch { runCatching { refreshHere() } }
            }
        }, onEnd = { invoiceWatch = false })
    }

    /** Payments to the FUND addresses the explorer sees and the node does not yet. */
    @Volatile var incoming: List<Lnd.ChainTx> = emptyList()
        private set
    @Volatile private var lastIncoming = 0L

    /** The same look from the background watcher, whatever the last one was. */
    fun checkIncomingNow(ctx: Context) { lastIncoming = 0L; checkIncoming(ctx.applicationContext) }

    private fun checkIncoming(app: Context) {
        val now = System.currentTimeMillis()
        if (now - lastIncoming < 60_000) return
        lastIncoming = now
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val addrs = (p.getString("fund_addrs", "") ?: "").lines().filter { it.isNotBlank() }
        if (addrs.isEmpty()) return
        val seen = org.json.JSONObject(p.getString("in_seen", "{}") ?: "{}")
        // The first look only learns what is already there: no alerts for old payments.
        val first = !p.getBoolean("in_init", false)
        val pending = ArrayList<Lnd.ChainTx>()
        val notifier = com.kilombino.pyblockwatch.data.Notifier(app)
        for (a in addrs) {
            val arr = runCatching {
                // Through Tor when it is already on: the explorer would otherwise see this phone's
                // address next to the node's on-chain addresses.
                val u = java.net.URL("https://mempool.kilombino.com/api/address/$a/txs")
                val c = ((com.kilombino.pyblockwatch.chain.Tor.proxyIfRunning()?.let { u.openConnection(it) } ?: u.openConnection()) as java.net.HttpURLConnection)
                    .apply { connectTimeout = 10_000; readTimeout = 10_000 }
                try { org.json.JSONArray(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
            }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val txid = t.getString("txid")
                val vout = t.getJSONArray("vout")
                var amount = 0L
                for (j in 0 until vout.length()) vout.getJSONObject(j).let { o -> if (o.optString("scriptpubkey_address") == a) amount += o.optLong("value") }
                if (amount <= 0) continue
                val confirmed = t.optJSONObject("status")?.optBoolean("confirmed") == true
                val was = seen.optString(txid, "")
                if (!confirmed) {
                    pending += Lnd.ChainTx(txid, amount, 0, now / 1000, 0, "incoming")
                    if (was.isEmpty()) { if (!first) notifier.lightningIn(amount, txid, false); seen.put(txid, "m") }
                } else if (was != "c") {
                    if (!first) notifier.lightningIn(amount, txid, true)
                    seen.put(txid, "c")
                }
            }
        }
        incoming = pending
        p.edit().putString("in_seen", seen.toString()).putBoolean("in_init", true).apply()
    }

    /**
     * The node's name, which peers see. Chosen when turning Lightning on; until then a name with
     * a random tag, so not every phone is called the same. Takes effect on the next start.
     */
    fun alias(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString("alias", null)?.let { return it }
        val a = "Kilowallet-" + java.security.SecureRandom().nextInt(0x10000).toString(16).padStart(4, '0')
        p.edit().putString("alias", a).apply()
        return a
    }
    fun setAlias(ctx: Context, a: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("alias", a.trim().take(32).ifBlank { alias(ctx) }).apply()

    /** The routing fee a payment may cost at most: [feePercent] of it, never under [feeMinSats]. */
    fun feeLimit(ctx: Context, sats: Long): Long {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pct = p.getFloat("fee_pct", 1f); val min = p.getLong("fee_min", 10L).coerceIn(0, MAX_FEE_MIN)
        return maxOf(min, (sats * pct / 100.0).toLong())
    }
    fun feePercent(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("fee_pct", 1f)
    fun feeMinSats(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("fee_min", 10L)
    /** The highest "at least" a user can set: a larger minimum would take the cap away for small payments. */
    const val MAX_FEE_MIN = 1_000L
    fun setFeeLimit(ctx: Context, pct: Float, min: Long) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("fee_pct", pct).putLong("fee_min", min.coerceIn(0, MAX_FEE_MIN)).apply()

    /** Stops and deletes an empty node (the screen checks it holds nothing). */
    suspend fun deleteNode(ctx: Context) {
        val app = ctx.applicationContext
        watch(false)
        Lnd.stop()
        kotlinx.coroutines.delay(6_000)
        Lnd.dir(app).deleteRecursively()
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
        markOff()
    }

    /**
     * Removes the node of a wallet that was forgotten or replaced, with all it remembered (name,
     * birthday, password). The caller made sure no channel is open or closing.
     */
    suspend fun wipe(ctx: Context) {
        val app = ctx.applicationContext
        if (Lnd.running || Lnd.dir(app).exists()) deleteNode(app)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        File(app.noBackupFilesDir, RESTORE_SCB).delete()
    }

    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun peers(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("peers", null)
            ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: Lnd.DEFAULT_PEERS

    fun setPeers(ctx: Context, peers: List<String>) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("peers", peers.joinToString("\n")).apply()

    /**
     * Turns Lightning on for the first time: the node's wallet is made from [bip39Seed] (the
     * recovery words, unlocked once by the caller) and the words never reach it otherwise.
     */
    suspend fun enable(ctx: Context, bip39Seed: ByteArray) = lock.withLock {
        val app = ctx.applicationContext
        try {
            bringUp(app) { state ->
                if (state == Lnd.WalletState.NON_EXISTING) {
                    _status.value = Status.Starting("creating the Lightning wallet")
                    val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    // The same words may have run a node before (another phone, a reinstall):
                    // without a birthday from a backup file, the wallet looks from the chain's
                    // activation, so no coin of that node is missed.
                    val birthday = prefs.getLong("birthday", 0L).takeIf { it > 0 } ?: ACTIVATION_TIME
                    val scb = File(app.noBackupFilesDir, RESTORE_SCB).takeIf { it.exists() }?.readBytes()
                    Lnd.initWallet(LnSeed.password(app), LnSeed.masterXprv(bip39Seed), birthday, scb)
                    prefs.edit().putLong("birthday", birthday).apply()
                    File(app.noBackupFilesDir, RESTORE_SCB).delete()
                }
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", true).putBoolean("moving", false).apply()
        } catch (e: Exception) {
            _status.value = Status.Failed(e.message ?: "Lightning did not start"); throw e
        } finally { bip39Seed.fill(0) }
    }

    /** Brings an enabled node up (once per process); a no-op when it already runs. */
    fun resume(ctx: Context) {
        val app = ctx.applicationContext
        if (!enabled(app)) { _status.value = Status.Off; return }
        // A move to another phone was under way when the app last stopped: don't bring this node
        // up by itself (it may already run there). Turning Lightning on by hand clears this.
        if (app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("moving", false)) { _status.value = Status.Off; return }
        scope.launch {
            lock.withLock {
                if (Lnd.running && _status.value is Status.Running) return@withLock
                runCatching { bringUp(app) {} }.onFailure { _status.value = Status.Failed(it.message ?: "Lightning did not start") }
            }
        }
    }

    @Volatile private var appCtx: Context? = null

    private suspend fun bringUp(app: Context, onState: suspend (Lnd.WalletState) -> Unit) {
        appCtx = app
        _status.value = Status.Starting("starting the node")
        val tor = if (com.kilombino.pyblockwatch.chain.Tor.available) {
            com.kilombino.pyblockwatch.chain.Tor.start(app)
            (com.kilombino.pyblockwatch.chain.Tor.proxy(60_000)?.address() as? java.net.InetSocketAddress)?.port
        } else null
        if (!Lnd.running) {
            Lnd.writeConfig(app, peers(app), tor, alias(app))
            Lnd.start(app)
        }
        // The wallet RPC comes first; the main RPC once the wallet is open.
        var state = Lnd.state()
        repeat(90) { if (state == Lnd.WalletState.UNKNOWN || state == Lnd.WalletState.WAITING_TO_START) { delay(1000); state = Lnd.state() } }
        if (state == Lnd.WalletState.UNKNOWN) {
            Lnd.markStopped()
            throw LndError("The node stopped: " + (Lnd.lastError(app) ?: "no answer from it"))
        }
        onState(state)
        if (state == Lnd.WalletState.LOCKED) {
            _status.value = Status.Starting("opening the Lightning wallet")
            Lnd.unlock(LnSeed.password(app))
        }
        torOn = tor != null; linked = false; towerAdded = false
        _status.value = Status.Starting("catching up with the chain")
        refresh()
    }

    @Volatile private var torOn = false
    @Volatile private var linked = false
    @Volatile private var towerAdded = false

    /**
     * Once the node answers (its RPC only does after the chain backend has caught up), best
     * effort: the watchtower (onion, needs Tor) and a link to Kilombino's node. Retried on
     * each refresh until they take.
     */
    private suspend fun link(peers: Int) {
        // The watchtower is added once, and only once the node is fully up: AddTower holds the
        // tower client's lock while it reaches the tower over Tor, and LND's start waits on that
        // same lock, so adding it during start (it was retried on every refresh) kept the node
        // "still in the process of starting" for minutes, with no peer.
        if (torOn && !towerAdded && (peers > 0 || linked)) addTowerOnce()
        // With no peer at all (a fresh or moved node, or one that lost its connection), reach
        // Kilombino's node again every half minute. The name is resolved here, not by LND: with
        // Tor on, LND resolves names through Tor, which fails until Tor is up. Its onion is tried too.
        if (peers > 0) { linked = true; return }
        val now = System.currentTimeMillis()
        if (now - lastLink < 30_000) return
        lastLink = now
        connectKilombino()
    }

    /** Connects to Kilombino's node (its name resolved here, then the onion); true when connected. */
    suspend fun connectKilombino(): Boolean {
        val targets = Lnd.resolved(KILOMBINO_HOSTS) + (if (torOn) listOf(KILOMBINO_ONION) else emptyList())
        val errors = ArrayList<String>()
        for (t in targets) {
            val r = runCatching { Lnd.connect(KILOMBINO_NODE, t) }
            if (r.isSuccess) { linked = true; linkError = null; return true }
            errors += "$t: ${r.exceptionOrNull()?.message}"
        }
        linkError = errors.joinToString("\n")
        return false
    }

    private suspend fun addTowerOnce() {
        val prefs = appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        if (prefs.getBoolean("tower_added", false)) { towerAdded = true; return }
        runCatching { Lnd.addTower(TOWER, TOWER_HOST) }.onSuccess {
            towerAdded = true; prefs.edit().putBoolean("tower_added", true).apply()
        }
    }

    /** Why the last attempt to reach Kilombino's node failed, to show under the channel. */
    @Volatile var linkError: String? = null
        private set
    @Volatile private var lastLink = 0L

    /** Re-reads everything the screen shows. */
    suspend fun refresh(): Unit = kotlinx.coroutines.withContext(Dispatchers.IO) { refreshHere() }

    private suspend fun refreshHere() {
        if (!Lnd.running) return
        appCtx?.let { runCatching { checkIncoming(it) } }
        runCatching {
            val info = Lnd.info()
            link(info.peers)
            val b = runCatching { Lnd.balances() }.getOrNull()
            val ch = runCatching { Lnd.channels() }.getOrDefault(emptyList())
            val p = runCatching { Lnd.pendingOpen() }.getOrDefault(emptyList())
            val cl = runCatching { Lnd.pendingClose() }.getOrDefault(emptyList())
            val inv = runCatching { Lnd.invoices() }.getOrDefault(emptyList())
            val pay = runCatching { Lnd.payments() }.getOrDefault(emptyList())
            _status.value = Status.Running(info, b, ch, p, chainTip, cl, inv, pay)
            appCtx?.let { app -> watchInvoices(app); followInFlight(app, pay) }
        }.onFailure {
            // The wallet RPC can answer before LND has looked at its database, so the state read
            // at start may not be the real one: a locked wallet found here is opened here.
            appCtx?.let { app ->
                if (Lnd.state() == Lnd.WalletState.LOCKED) runCatching { Lnd.unlock(LnSeed.password(app)) }
            }
            if (_status.value !is Status.Running)
                _status.value = Status.Starting("catching up with the chain", appCtx?.let { Lnd.syncHeight(it) })
        }
    }

    /** Refreshes every few seconds while the LN tab is on screen. */
    fun watch(on: Boolean) {
        poller?.cancel(); poller = null
        if (!on) return
        poller = scope.launch {
            // One failed look must not end the loop (the screen would wait for good). With the
            // app in the background nobody sees the figures: no calls to the node until it's back.
            while (isActive) {
                if (com.kilombino.pyblockwatch.ui.AppVisible.value) runCatching { refresh() }
                delay(5_000)
            }
        }
    }
}
