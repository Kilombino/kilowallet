package com.kilombino.pyblockwatch.data

import android.content.Context
import com.kilombino.pyblockwatch.ark.Ark
import org.json.JSONObject

/**
 * Ark alerts for the background watcher: payments that arrive, moves into Ark that become
 * spendable, withdrawals that complete, on-chain deposits waiting to be moved in, and coins
 * close to expiry. It compares the engine's movements (id → status) and deposit balance with
 * what it saw last time; the first run only records a baseline, so old history never alerts.
 */
object ArkWatch {
    private const val PREFS = "kilombino_ark_watch"
    /** Under about a week left, ask for a renewal; at most once a day. */
    private const val EXPIRY_WARN_BLOCKS = 1008
    private const val EXPIRY_REPEAT_MS = 24 * 3600 * 1000L

    fun evaluate(ctx: Context, notifier: Notifier) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val movements = Ark.history()
        val balance = runCatching { Ark.balance() }.getOrNull() ?: return
        val deposit = balance.onchainConfirmed + balance.onchainPending

        val seen = prefs.getString("seen", null)?.let { JSONObject(it) }
        val now = JSONObject()
        movements.forEach { now.put(it.id, it.status) }

        if (seen != null) {
            for (m in movements) {
                val before = if (seen.has(m.id)) seen.getString(m.id) else null
                val done = m.status == "successful"
                if (!done || before == "successful") continue
                when (m.kind) {
                    "Ark received", "Lightning received" -> if (m.amount > 0) notifier.arkReceived(m.kind, m.amount, m.id)
                    "move into Ark" -> notifier.arkBoarded(m.amount, m.id)
                    "withdrawal" -> notifier.arkWithdrawn(m.amount, m.id)
                }
            }
            val lastDeposit = prefs.getLong("deposit", 0)
            if (deposit > lastDeposit) notifier.arkDeposit(deposit - lastDeposit)
        }

        Ark.blocksToNearestExpiry()?.let { blocks ->
            val last = prefs.getLong("expiry_warned", 0)
            if (blocks in 0 until EXPIRY_WARN_BLOCKS && System.currentTimeMillis() - last > EXPIRY_REPEAT_MS) {
                notifier.arkExpiring(blocks)
                prefs.edit().putLong("expiry_warned", System.currentTimeMillis()).apply()
            }
        }

        prefs.edit().putString("seen", now.toString()).putLong("deposit", deposit).apply()
    }
}
