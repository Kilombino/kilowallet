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
 * The cost is honesty about battery: this is a visible foreground service on a
 * 5-minute loop, not a free push wake-up. The user opts in and can see it running.
 * While the app is open, the foreground refresh (every 30 s, [WalletViewModel]) drives
 * the same [BalanceWatch] engine, so movements are caught far faster than 5 minutes then.
 */
class WatchService : Service() {

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ONGOING_ID, ongoingNotification())
        if (job?.isActive != true) job = scope.launch { loop() }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel(); scope.cancel()
        super.onDestroy()
    }

    private suspend fun loop() {
        val store = Store(this)
        val scanner = Scanner()
        val notifier = Notifier(this)
        while (scope.isActive) {
            val xpub = store.xpub
            if (xpub == null || !store.notificationsEnabled) { delay(INTERVAL_MS); continue }

            for (chain in Chain.entries.filter { it != Chain.SHA256 || store.spamchainAccepted }) {
                runCatching {
                    val rows = deriveKnownAddresses(xpub, chain, store)
                    if (rows.isEmpty()) return@runCatching
                    val endpoint = store.endpoint(chain)
                    val (updated, txs, _) = scanner.refreshDetails(rows, endpoint, store.pinnedFingerprint(endpoint))
                    BalanceWatch.evaluate(
                        store, notifier, chain,
                        updated.sumOf { it.confirmed }, updated.sumOf { it.unconfirmed }, txs,
                    )
                }
            }
            // Ark: while this watcher runs, keep the Ark engine up. Its own daemon renews coins
            // before they expire (4,320 blocks); without it the user would have to remember
            // to open the app at least once a month.
            if (com.kilombino.pyblockwatch.ark.Ark.available &&
                java.io.File(filesDir, "ark/db.sqlite").exists()) {
                runCatching {
                    com.kilombino.pyblockwatch.ark.Ark.ensureStarted(this@WatchService)
                    ArkWatch.evaluate(this@WatchService, notifier)
                }
            }
            // A coinjoin round still running whose service died: start it again.
            runCatching {
                if (com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.hasActive(this@WatchService))
                    com.kilombino.pyblockwatch.coinjoin.CoinjoinService.start(this@WatchService)
            }
            // Coinjoin: tell about pools other people opened since the last look (BLAKE2b
            // spending wallets only, the ones that could join).
            if (com.kilombino.pyblockwatch.data.SeedVault(this@WatchService).hasSeed() && com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.supported(store)) {
                runCatching { com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.checkNewPools(this@WatchService) }
            }
            // Home-screen widget: piggy-backs on this loop instead of pulling in WorkManager.
            // MarketFeed only calls the server when its own schedule allows it.
            if (com.kilombino.pyblockwatch.widget.XbtWidget.hasWidgets(this@WatchService)) {
                runCatching {
                    MarketFeed.refresh(this@WatchService)
                    com.kilombino.pyblockwatch.widget.XbtWidget.renderAll(this@WatchService)
                }
            }
            delay(INTERVAL_MS)
        }
    }

    /**
     * Re-derive the addresses a scan already found. We keep only the count in
     * storage, not the address list, so this walks the same deterministic path the
     * scanner did — cheap, and it keeps a single source of truth for derivation.
     */
    private fun deriveKnownAddresses(xpub: String, chain: Chain, store: Store): List<AddressRow> {
        val account = runCatching { Bip32.parseExtendedPubKey(xpub) }.getOrNull() ?: return emptyList()
        val type = store.scriptType
        val out = mutableListOf<AddressRow>()
        for (chainIndex in 0..1) {
            // Every address used so far plus the gap, not just the first [gapLimit]: a wallet
            // past its 20th address would otherwise miss payments to its newer ones.
            val depth = store.usedTop(xpub, chainIndex) + store.gapLimit
            val branch = Bip32.deriveChild(account, chainIndex)
            for (i in 0 until depth) {
                val child = Bip32.deriveChild(branch, i)
                val sh = Address.scriptHashFor(child.pubkey(), type)
                out += AddressRow(
                    chainIndex = chainIndex, index = i,
                    address = Address.encode(child.pubkey(), type),
                    path = "m/$chainIndex/$i", scriptHash = sh,
                )
            }
        }
        return out
    }

    private fun ongoingNotification(): Notification {
        Notifier(this).ensureChannels()
        return Notification.Builder(this, Notifier.CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Watching your balances")
            .setContentText("Checks every 5 min · no push server")
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

    private companion object {
        const val ONGOING_ID = 1
        const val INTERVAL_MS = 5 * 60 * 1000L
    }
}
