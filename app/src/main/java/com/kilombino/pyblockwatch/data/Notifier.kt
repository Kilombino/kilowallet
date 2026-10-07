package com.kilombino.pyblockwatch.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import com.kilombino.pyblockwatch.chain.Chain

/**
 * Posts balance notifications, and nothing else. Pulled out of the background service so
 * the SAME wording fires whether the change was caught by the 5-minute background watcher
 * or by the 30-second refresh while the app is open — one voice, one set of channels.
 */
class Notifier(private val context: Context, private val titlePrefix: String = "") {

    private fun manager() =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val m = manager()
        m.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Watching", NotificationManager.IMPORTANCE_MIN)
        )
        m.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Balance changes", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun tabFor(chain: Chain) = if (chain == Chain.BLAKE2B) OpenTab.BTC else OpenTab.SPAMCOIN

    /**
     * Post one alert. [id] is stable per (chain, event, txid) so re-evaluating the same
     * state updates the existing notification instead of stacking duplicates, while a
     * mempool alert and its later confirmation keep DIFFERENT ids so the user sees both.
     */
    private fun post(id: Int, title: String, text: String, tab: String) {
        ensureChannels()
        val n = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(titlePrefix + title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(OpenTab.pending(context, tab))
            .build()
        manager().notify(id, n)
    }

    /** Group sats with thin spaces (12 345 678) so an amount reads as an exact count. */
    private fun sats(v: Long): String {
        val digits = kotlin.math.abs(v).toString()
        val sb = StringBuilder()
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(' ')
            sb.append(c)
        }
        return (if (v < 0) "-" else "") + sb
    }

    fun found(chain: Chain, confirmed: Long, unconfirmed: Long) {
        val total = confirmed + unconfirmed
        val text = if (unconfirmed != 0L)
            "${sats(total)} sats · ${sats(unconfirmed)} in the mempool (0 conf)"
        else "${sats(confirmed)} sats · confirmed"
        post(idFor(chain, "found", ""), "${chain.display}: balance detected", text, tabFor(chain))
    }

    fun mempoolIn(chain: Chain, amount: Long, txid: String) =
        post(idFor(chain, "mem", txid), "New incoming payment in the mempool",
             "${chain.display} · +${sats(amount)} sats (0 conf)", tabFor(chain))

    fun mempoolOut(chain: Chain, amount: Long, txid: String) =
        post(idFor(chain, "mem", txid), "New outgoing payment in the mempool",
             "${chain.display} · ${sats(amount)} sats (0 conf)", tabFor(chain))

    fun firstConfIn(chain: Chain, amount: Long, txid: String) =
        post(idFor(chain, "conf", txid), "First confirmation of the incoming payment",
             "${chain.display} · +${sats(amount)} sats (1 conf)", tabFor(chain))

    fun firstConfOut(chain: Chain, amount: Long, txid: String) =
        post(idFor(chain, "conf", txid), "First confirmation of the outgoing payment",
             "${chain.display} · ${sats(amount)} sats (1 conf)", tabFor(chain))

    // A change we only ever saw already-confirmed (received or spent between two checks,
    // never observed in the mempool). No txid to key on, so it collapses per chain.
    fun receivedConfirmed(chain: Chain, amount: Long) =
        post(idFor(chain, "recv", ""), "Received",
             "${chain.display} · +${sats(amount)} sats · confirmed", tabFor(chain))

    fun receivedPending(chain: Chain, amount: Long) =
        post(idFor(chain, "recv", ""), "Received",
             "${chain.display} · +${sats(amount)} sats · in the mempool (0 conf)", tabFor(chain))

    fun sentPending(chain: Chain, amount: Long) =
        post(idFor(chain, "sent", ""), "Sent",
             "${chain.display} · ${sats(amount)} sats · in the mempool (0 conf)", tabFor(chain))

    fun sentConfirmed(chain: Chain, amount: Long) =
        post(idFor(chain, "sent", ""), "Sent",
             "${chain.display} · ${sats(amount)} sats · confirmed", tabFor(chain))

    // ---------------------------------------------------------------- Ark

    private fun arkId(event: String, key: String): Int = ("ark:$event:$key").hashCode()

    fun arkReceived(kind: String, amount: Long, key: String) =
        post(arkId("in", key), "Ark: ${kind.lowercase()}", "+${sats(amount)} sats, spendable in Ark", OpenTab.ARK)

    fun arkBoarded(amount: Long, key: String) =
        post(arkId("board", key), "Ark: funds moved in", "+${sats(amount)} sats are now spendable in Ark", OpenTab.ARK)

    fun arkWithdrawn(amount: Long, key: String) =
        post(arkId("out", key), "Ark: withdrawal done", "${sats(kotlin.math.abs(amount))} sats left Ark on-chain", OpenTab.ARK)

    /** "payment" for an Ark payment, "Lightning payment" for Lightning: reads right after "Ark:". */
    private fun what(kind: String) = if (kind == "Ark payment") "payment" else kind

    fun arkBoarding(amount: Long, key: String) =
        post(arkId("boarding", key), "Ark: moving funds in",
             "${sats(kotlin.math.abs(amount))} sats on their way into Ark; spendable after 3 confirmations", OpenTab.ARK)

    fun arkSent(kind: String, amount: Long, key: String) =
        post(arkId("sent", key), "Ark: ${what(kind)} sent", "${sats(-kotlin.math.abs(amount))} sats", OpenTab.ARK)

    fun arkFailed(kind: String, amount: Long, key: String) =
        post(arkId("failed", key), "Ark: ${what(kind)} failed",
             "${sats(kotlin.math.abs(amount))} sats did not go out; the coins are still yours", OpenTab.ARK)

    fun arkRenewed(cost: Long, key: String) =
        post(arkId("renew", key), "Ark: coins renewed",
             "Good for about 30 more days" + (if (cost > 0) " · cost ${sats(cost)} sats" else ""), OpenTab.ARK)

    fun arkDeposit(amount: Long, key: String, confirmed: Boolean) =
        post(arkId(if (confirmed) "deposit-conf" else "deposit", key),
             if (confirmed) "Ark: deposit confirmed" else "Ark: deposit in the mempool",
             "+${sats(amount)} sats on-chain" + (if (confirmed) "" else " (0 conf)") +
                 ", not in Ark yet. Open the Ark tab and tap MOVE INTO ARK.", OpenTab.ARK)

    fun arkDepositSent(amount: Long, key: String, confirmed: Boolean) =
        post(arkId(if (confirmed) "dsent-conf" else "dsent", key),
             if (confirmed) "Ark: on-chain send confirmed" else "Ark: on-chain send in the mempool",
             "${sats(-kotlin.math.abs(amount))} sats from the deposit" + (if (confirmed) "" else " (0 conf)"), OpenTab.ARK)

    fun updateAvailable(version: String) =
        post(("update:$version").hashCode(), "Kilowallet $version is out",
             "Tap to update it from inside the app.", OpenTab.UPDATE)

    fun arkExpiring(blocks: Int) =
        post(arkId("expiry", ""), "Ark: a coin expires soon",
             "In about ${blocks * 10 / 1440} days ($blocks blocks). Open the Ark tab and tap RENEW, " +
                 "or the coin has to be withdrawn on-chain.", OpenTab.ARK)

    private fun idFor(chain: Chain, phase: String, txid: String): Int =
        (titlePrefix + chain.id + phase + txid).hashCode()

    companion object {
        const val CHANNEL_ONGOING = "watch_ongoing"
        const val CHANNEL_ALERTS = "watch_alerts_v2"
    }
}

/**
 * The balance-diff brain, shared by the background service and the foreground refresh.
 *
 * Given the fresh confirmed/unconfirmed totals and the current transaction list for a
 * chain, it works out what actually happened since the last notification and fires the
 * right alerts — arrival in the mempool, first confirmation, or a change only ever seen
 * confirmed — then advances the stored baseline so nothing fires twice.
 *
 * Amounts are attributed from the aggregate balance delta, which is exact for the common
 * one-movement-at-a-time case; when several land in the same window it degrades to a
 * single grouped figure rather than inventing per-tx splits.
 */
object BalanceWatch {

    fun evaluate(
        store: Store,
        notifier: Notifier,
        chain: Chain,
        confirmed: Long,
        unconfirmed: Long,
        txs: List<TxConf>,
    ) {
        val prevConf = store.lastNotifiedConf(chain)
        val prevUnconf = store.lastNotifiedUnconf(chain)
        val newTotal = confirmed + unconfirmed

        val currentPending = txs.filter { it.pending }.map { it.txid }.toSet()
        val currentConfirmed = txs.filter { !it.pending }.map { it.txid }.toSet()

        // First time we ever look at this chain: set the baseline, ping once if there are
        // funds, and record what is already pending so we don't later announce it as new.
        if (prevConf < 0) {
            if (newTotal > 0) notifier.found(chain, confirmed, unconfirmed)
            store.setPendingMap(chain, currentPending.associateWith { 0L })
            store.setLastNotified(chain, confirmed, unconfirmed)
            return
        }

        val prevMap = store.pendingMap(chain)
        val totalDelta = newTotal - (prevConf + prevUnconf)

        val newPending = (currentPending - prevMap.keys).toList()
        val nowConfirmed = prevMap.keys.filter { it in currentConfirmed }
        // Pending txids that vanished without confirming were replaced/evicted: drop quietly.

        val newAmounts = HashMap<String, Long>()

        // 1) New mempool transaction(s). The whole balance delta this cycle is theirs,
        //    since confirmations are balance-neutral.
        if (newPending.isNotEmpty()) {
            val share = if (newPending.size == 1) totalDelta else totalDelta / newPending.size
            newPending.forEach { newAmounts[it] = share }
            if (totalDelta >= 0) notifier.mempoolIn(chain, totalDelta, newPending.first())
            else notifier.mempoolOut(chain, -totalDelta, newPending.first())
        }

        // 2) Transactions that were pending and just got their first confirmation.
        nowConfirmed.forEach { txid ->
            val amount = prevMap[txid] ?: 0L
            if (amount >= 0) notifier.firstConfIn(chain, amount, txid)
            else notifier.firstConfOut(chain, -amount, txid)
        }

        // 3) A balance change we never saw in the mempool — received or spent already
        //    confirmed (common with the 5-minute background gap).
        //    It may still be unconfirmed (e.g. a payment to yourself, or an address the watcher
        //    only just started following): then say so instead of calling it confirmed.
        if (newPending.isEmpty() && totalDelta != 0L) {
            val stillPending = unconfirmed != prevUnconf
            if (totalDelta > 0) { if (stillPending) notifier.receivedPending(chain, totalDelta) else notifier.receivedConfirmed(chain, totalDelta) }
            else { if (stillPending) notifier.sentPending(chain, -totalDelta) else notifier.sentConfirmed(chain, -totalDelta) }
        }

        // Advance the baseline: pending set becomes the current one, carrying amounts
        // forward for those still pending and dropping the confirmed/vanished ones.
        val nextMap = currentPending.associateWith { prevMap[it] ?: newAmounts[it] ?: 0L }
        store.setPendingMap(chain, nextMap)
        store.setLastNotified(chain, confirmed, unconfirmed)
    }
}
