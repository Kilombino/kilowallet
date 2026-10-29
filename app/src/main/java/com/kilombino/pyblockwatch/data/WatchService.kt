package com.kilombino.pyblockwatch.data

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bip32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Balance-change notifications, without a push server.
 *
 * The upstream PyBLØCK app routes notifications through UnifiedPush, which is a
 * good degoogled answer but still means a server knows your device and your npub.
 * A watch-only wallet does not need that: the phone can simply ask the Electrum
 * server itself. Nothing about this wallet leaves the device except the scripthash
 * queries the wallet already makes.
 *
 * The cost is honesty about battery: this is a visible foreground service on a loop of
 * [Store.refreshBackgroundMinutes] (15 by default), not a free push wake-up. The user can
 * set it, or turn it off and with it the persistent notification. While the app is open,
 * the foreground refresh ([Store.refreshOpenSeconds], [WalletViewModel]) drives the same
 * [BalanceWatch] engine.
 */
class WatchService : Service() {

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        com.kilombino.pyblockwatch.chain.Tor.install(this) // a .onion node is reachable from the background too
        // specialUse from Android 14: dataSync is cut after ~6 hours a day on 15, and the
        // notifications stopped with it.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(ONGOING_ID, ongoingNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            startForeground(ONGOING_ID, ongoingNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ONGOING_ID, ongoingNotification())
        if (job?.isActive != true) job = scope.launch { loop() }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel(); scope.cancel()
        super.onDestroy()
    }

    private suspend fun loop() {
        val store = Store(this)
        Scanner.useDisk(java.io.File(noBackupFilesDir, "scan-cache"))
        val scanner = Scanner()
        while (scope.isActive) {
            if (!store.notificationsEnabled || store.refreshBackgroundMinutes <= 0) { stopSelf(); return }

            // Both wallets are watched: the hot one and, if set up, the separate watch-only one.
            // The watch-only one's notifications say so in their title.
            for (wallet in listOf(Store.HOT, Store.WATCH)) {
                if (Store(this, wallet).xpub == null) continue
                for (chain in Chain.entries.filter { it != Chain.SHA256 || store.spamchainAccepted })
                    runCatching { look(this, scanner, wallet, chain) }
            }
            // JoinMarket (read-only, from Jam's export): its jars and bonds like a watch-only
            // wallet, plus the bond alerts (unlocks in 7 days, unlocked, no longer counts).
            runCatching {
                val js = Store(this, Store.JM)
                val e = JamWatch.export(js) ?: return@runCatching
                val chain = Chain.BLAKE2B
                val endpoint = js.endpoint(chain)
                val pin = store.pinnedFingerprint(endpoint)
                val quick = !js.fullLookDue(chain)
                val (updated, txs, _) = scanner.refreshDetails(JamWatch.knownRows(e, js, store.gapLimit), endpoint, pin, quick)
                if (!quick) js.setLastFullLook(chain)
                val nf = Notifier(this, JAM_PREFIX)
                BalanceWatch.evaluate(js, nf, chain, updated.sumOf { it.confirmed }, updated.sumOf { it.unconfirmed }, txs,
                    runCatching { com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.ownTxids(this) }.getOrDefault(emptySet()))
                JamWatch.alerts(js, nf, JamWatch.bondStates(e, updated, endpoint, pin, js))
            }
            val notifier = Notifier(this)
            // Ark: while this watcher runs, keep the Ark engine up. Its own daemon renews coins
            // before they expire (4,320 blocks); without it the user would have to remember
            // to open the app at least once a month.
            if (com.kilombino.pyblockwatch.ark.Ark.available &&
                java.io.File(filesDir, "ark/db.sqlite").exists() &&
                !com.kilombino.pyblockwatch.ark.Ark.paused(this@WatchService)) {
                runCatching {
                    com.kilombino.pyblockwatch.ark.Ark.ensureStarted(this@WatchService)
                    ArkWatch.evaluate(this@WatchService, notifier)
                }
            }
            // Lightning: payments arriving at the node's FUND addresses, from the explorer (no
            // need for the node to run).
            if (com.kilombino.pyblockwatch.ln.Lightning.enabled(this@WatchService))
                runCatching { com.kilombino.pyblockwatch.ln.Lightning.checkIncomingNow(this@WatchService) }
            // A coinjoin round still running whose service died: start it again.
            runCatching {
                if (com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.hasActive(this@WatchService))
                    com.kilombino.pyblockwatch.coinjoin.CoinjoinService.start(this@WatchService)
            }
            // Telegram alerts still waiting (the round may be over and its service gone): once more.
            runCatching { if (com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.waiting(this@WatchService) > 0)
                com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts.flush(this@WatchService) }
            // Coinjoin: tell about pools other people opened since the last look (BLAKE2b
            // spending wallets only, the ones that could join).
            if (com.kilombino.pyblockwatch.data.SeedVault(this@WatchService).hasSeed() && com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.supported(store)) {
                runCatching { com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.checkNewPools(this@WatchService) }
            }
            // A newer release: notified once per version (the app installs it from inside).
            runCatching {
                val prefs = getSharedPreferences("pyblockwatch", MODE_PRIVATE)
                if (prefs.getBoolean("check_updates", true)) {
                    val current = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
                    UpdateCheck.check(current)?.let { r ->
                        if (prefs.getString("update_notified", null) != r.version) {
                            notifier.updateAvailable(r.version)
                            prefs.edit().putString("update_notified", r.version).apply()
                        }
                    }
                }
            }
            // Home-screen widget: piggy-backs on this loop instead of pulling in WorkManager.
            // MarketFeed only calls the server when its own schedule allows it.
            if (com.kilombino.pyblockwatch.widget.XbtWidget.hasWidgets(this@WatchService)) {
                runCatching {
                    MarketFeed.refresh(this@WatchService)
                    com.kilombino.pyblockwatch.widget.XbtWidget.renderAll(this@WatchService)
                }
            }
            // Waited a minute at a time, so a changed interval counts from this cycle: shorter,
            // and the next one comes sooner; off, and the watcher stops (at the loop's top).
            val since = System.currentTimeMillis()
            while (scope.isActive) {
                delay(60_000L)
                val every = store.refreshBackgroundMinutes
                if (every <= 0 || !store.notificationsEnabled) break
                if (System.currentTimeMillis() - since >= every * 60_000L) break
            }
        }
    }

    private fun ongoingNotification(): Notification {
        Notifier(this).ensureChannels()
        return Notification.Builder(this, Notifier.CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(com.kilombino.pyblockwatch.ui.i18n.I18n.tr("Watching your balances"))
            .setContentText(com.kilombino.pyblockwatch.ui.i18n.I18n.tr("Checks every ${Store(this).refreshBackgroundMinutes} min · no push server"))
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()
    }

    private fun openApp(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        return PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val ONGOING_ID = 1
        /** The JoinMarket wallet's notifications say so in their title. */
        const val JAM_PREFIX = "JoinMarket · "
        /** The watch-only wallet's notifications say so in their title. */
        const val WATCH_PREFIX = "Watch-only · "
        /** And the hot wallet's, so the two are never mistaken. */
        const val HOT_PREFIX = "Hot wallet · "

        /**
         * One look at [wallet] (the hot one or the watch-only one) on [chain]: the addresses it
         * knows asked about, and what changed since the last notification notified once
         * ([BalanceWatch] keeps the baseline, so a second look at the same thing says nothing).
         */
        suspend fun look(ctx: Context, scanner: Scanner, wallet: String, chain: Chain) {
            val store = Store(ctx)
            val ws = Store(ctx, wallet)
            val xpub = ws.xpub ?: return
            val notifier = Notifier(ctx, if (wallet == Store.WATCH) WATCH_PREFIX else HOT_PREFIX)
            // A hot wallet's Taproot account is watched too: its coins count in the balance.
            val taproot = ws.taprootXpub.takeIf { wallet == Store.HOT }
            val rows = deriveKnownAddresses(xpub, chain, ws) +
                (taproot?.let { deriveKnownAddresses(it, chain, ws, com.kilombino.pyblockwatch.crypto.ScriptType.P2TR) } ?: emptyList())
            if (rows.isEmpty()) return
            val endpoint = store.endpoint(chain)
            val quick = !ws.fullLookDue(chain)
            val (updated, txs, _) = scanner.refreshDetails(rows, endpoint, store.pinnedFingerprint(endpoint), quick)
            if (!quick) ws.setLastFullLook(chain)
            // A payment to an address past the count moves it on: the next cycle looks further.
            for (r in updated.filter { it.txCount > 0 })
                ws.noteUsedTop(if (r.type == com.kilombino.pyblockwatch.crypto.ScriptType.P2TR) taproot ?: xpub else xpub,
                    r.chainIndex, r.index + 1, r.type ?: ws.scriptType)
            BalanceWatch.evaluate(
                ws, notifier, chain,
                updated.sumOf { it.confirmed }, updated.sumOf { it.unconfirmed }, txs,
                runCatching { com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.ownTxids(ctx) }.getOrDefault(emptySet()),
            )
        }

        /**
         * A coinjoin round whose mixed outputs go to the watch-only wallet was sent or confirmed:
         * look at that wallet now. Otherwise only this watcher looks at it, every
         * [Store.refreshBackgroundMinutes] (never, with background checks off), while the app on
         * screen refreshes just the wallet shown and the round's own notifications speak for the
         * pool, not for the wallet the coins reached. What the look finds is notified once, as
         * any payment to that wallet; the watcher's next look then finds nothing new.
         */
        fun lookAtWatchSoon(ctx: Context, delayMs: Long = 0) {
            val app = ctx.applicationContext
            val store = Store(app)
            if (!store.notificationsEnabled || Store(app, Store.WATCH).xpub == null) return
            CoroutineScope(Dispatchers.IO).launch {
                if (delayMs > 0) delay(delayMs)
                runCatching {
                    com.kilombino.pyblockwatch.chain.Tor.install(app) // a .onion server, as the watcher reaches it
                    Scanner.useDisk(java.io.File(app.noBackupFilesDir, "scan-cache"))
                    look(app, Scanner(), Store.WATCH, Chain.BLAKE2B)
                }
            }
        }

        /**
         * Re-derive the addresses a scan already found. We keep only the count in
         * storage, not the address list, so this walks the same deterministic path the
         * scanner did — cheap, and it keeps a single source of truth for derivation.
         */
        private fun deriveKnownAddresses(
            xpub: String, chain: Chain, store: Store, tag: com.kilombino.pyblockwatch.crypto.ScriptType? = null,
        ): List<AddressRow> {
            val account = runCatching { Bip32.parseExtendedPubKey(xpub) }.getOrNull() ?: return emptyList()
            val type = tag ?: store.scriptType
            val out = mutableListOf<AddressRow>()
            for (chainIndex in 0..1) {
                // Every address used so far plus the gap, not just the first [gapLimit]: a wallet
                // past its 20th address would otherwise miss payments to its newer ones.
                // Worked out once and remembered with the scanner's (the same addresses every cycle).
                val depth = store.usedTopToWatch(xpub, chainIndex, type) + store.gapLimit
                Scanner.derivedRange(account, type, chainIndex, depth).forEachIndexed { i, (address, sh) ->
                    out += AddressRow(
                        chainIndex = chainIndex, index = i, address = address,
                        path = "m/$chainIndex/$i", scriptHash = sh, type = tag,
                    )
                }
            }
            return out
        }
    }
}
