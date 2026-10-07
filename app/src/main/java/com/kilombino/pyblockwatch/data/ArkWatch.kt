package com.kilombino.pyblockwatch.data

import android.content.Context
import com.kilombino.pyblockwatch.ark.Ark
import org.json.JSONObject

/**
 * Ark alerts, for the background watcher and for the Ark tab while it is open: payments that
 * arrive or go out (Ark and Lightning), moves into Ark when they start and when they become
 * spendable, renewals, withdrawals, and the deposit's own on-chain transactions in the mempool
 * and at their first confirmation, plus coins close to expiry. It compares every movement's
 * status with what it saw last time; the first run only records a baseline, so old history
 * never alerts.
 */
object ArkWatch {
    private const val PREFS = "kilombino_ark_watch"
    /** Under about a week left, ask for a renewal; at most once a day. */
    private const val EXPIRY_WARN_BLOCKS = 1008
    private const val EXPIRY_REPEAT_MS = 24 * 3600 * 1000L

    @Synchronized
    fun evaluate(ctx: Context, notifier: Notifier) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching { Ark.balance() }.getOrNull() ?: return
        val movements = Ark.activity()

        // "seen2": since 0.15 the list also holds the deposit's transactions, so the old
        // baseline would announce every past deposit. A fresh key starts a fresh baseline.
        val seen = prefs.getString("seen2", null)?.let { JSONObject(it) }
        val now = JSONObject()
        movements.forEach { now.put(it.id, it.status) }

        if (seen != null) {
            for (m in movements) {
                val before = if (seen.has(m.id)) seen.getString(m.id) else null
                if (before == m.status) continue
                val done = m.status == "successful"
                val failed = m.status == "failed" || m.status == "canceled" || m.status == "cancelled"
                when (m.kind) {
                    "Ark received", "Lightning received" -> if (done && m.amount > 0) notifier.arkReceived(m.kind, m.amount, m.id)
                    "move into Ark" -> when {
                        done -> notifier.arkBoarded(m.amount, m.id)
                        before == null && !failed -> notifier.arkBoarding(m.amount, m.id)
                    }
                    "withdrawal" -> if (done) notifier.arkWithdrawn(m.amount, m.id)
                    "Ark payment", "Lightning payment" -> when {
                        done -> notifier.arkSent(m.kind, m.amount, m.id)
                        failed -> notifier.arkFailed(m.kind, m.amount, m.id)
                    }
                    "renewal" -> if (done) notifier.arkRenewed(maxOf(m.fee, -m.amount), m.id)
                    "deposit received" -> notifier.arkDeposit(m.amount, m.id, m.status == "confirmed")
                    "sent from deposit" -> notifier.arkDepositSent(m.amount, m.id, m.status == "confirmed")
                }
            }
        }

        Ark.blocksToNearestExpiry()?.let { blocks ->
            val last = prefs.getLong("expiry_warned", 0)
            // Under 3 days, every 6 hours: an expired coin is the server's to sweep.
            val every = if (blocks < 432) 6 * 3600 * 1000L else EXPIRY_REPEAT_MS
            if (blocks in 0 until EXPIRY_WARN_BLOCKS && System.currentTimeMillis() - last > every) {
                notifier.arkExpiring(blocks)
                prefs.edit().putLong("expiry_warned", System.currentTimeMillis()).apply()
            }
        }

        prefs.edit().putString("seen2", now.toString()).apply()
    }
}
