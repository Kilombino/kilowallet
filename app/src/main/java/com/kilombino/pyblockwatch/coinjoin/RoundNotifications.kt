package com.kilombino.pyblockwatch.coinjoin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import com.kilombino.pyblockwatch.R

/**
 * Each coinjoin round as a card on the lock screen, as on the COINJOIN tab: the amount, the
 * step it is on, the bar of its five steps, where your coin is and, while it waits for
 * people, the countdown to its close. A round going stays (it cannot be swiped away); one that
 * ends leaves a last card ("Done · Confirmed", or cancelled) that can be.
 *
 * Silent: the alerts that ask for something (vote, sign) still come from [CoinjoinHub].
 */
object RoundNotifications {
    const val CHANNEL = "coinjoin_rounds"
    private const val PREFS = "cj_round_cards"
    private const val STEPS = 5

    private val final = setOf(PoolSession.Phase.CONFIRMED, PoolSession.Phase.ABORTED, PoolSession.Phase.REJECTED)

    fun idFor(poolId: String) = 5000 + (poolId.hashCode() and 0x7fff)

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(CHANNEL, "Coinjoin rounds", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Each round going, with its steps, on the lock screen"
            setSound(null, null); enableVibration(false); setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun stepOf(st: PoolSession.State): Int = when (st.phase) {
        PoolSession.Phase.JOINING, PoolSession.Phase.OPEN -> 1
        PoolSession.Phase.VOTING -> 2
        PoolSession.Phase.CLOSING, PoolSession.Phase.SIGNING -> 3
        PoolSession.Phase.BROADCAST -> 4
        PoolSession.Phase.CONFIRMED -> 5
        else -> 0
    }

    private fun sats(v: Long, masked: Boolean) = if (masked) "•••" else "%,d".format(v).replace(',', ' ')

    /** The card of [st]; with the amounts hidden when the app keeps them hidden (street mode). */
    fun card(ctx: Context, st: PoolSession.State): Notification {
        ensureChannel(ctx)
        // Hidden when street mode is on by default and the app does not show amounts right now
        // (unlocked with the eye). A process without the app on screen keeps them hidden.
        val masked = ctx.getSharedPreferences("pyblockwatch", Context.MODE_PRIVATE).getBoolean("street_mode_default", true) &&
            com.kilombino.pyblockwatch.ui.StreetMode.hidden
        val step = stepOf(st)
        val people = if (st.round.isNotEmpty()) st.round.size else st.seats.size
        // The people first: "Step 1 of 5" read as one person of five when the line was cut short.
        // The bar of dots already says the step.
        val status = when (st.phase) {
            PoolSession.Phase.JOINING -> "Asking to join"
            PoolSession.Phase.OPEN -> "$people/${st.terms.maxPeers} people · waiting for more"
            PoolSession.Phase.VOTING -> "$people people · closing vote"
            PoolSession.Phase.CLOSING -> "$people people · collecting outputs"
            PoolSession.Phase.SIGNING -> if (st.postedSig) "$people people · signed, waiting for the others" else "$people people · signing"
            PoolSession.Phase.BROADCAST -> "Sent · waiting for a block"
            PoolSession.Phase.CONFIRMED -> "Done · Confirmed"
            PoolSession.Phase.ABORTED -> "Cancelled"
            PoolSession.Phase.REJECTED -> "Refused"
        }
        val where = when (st.phase) {
            PoolSession.Phase.BROADCAST -> "Your coin left in the group transaction. Waiting for a block."
            PoolSession.Phase.CONFIRMED -> "Done: ${sats(st.terms.amount, masked)} sats mixed in your wallet" +
                (if (st.seat.changeValue > 0) ", plus ${sats(st.seat.changeValue, masked)} sats change." else ".")
            PoolSession.Phase.ABORTED, PoolSession.Phase.REJECTED -> "${st.reason.replaceFirstChar { it.uppercase() }}. Your coin did not move."
            else -> "Your coin is still in your wallet — it hasn't left."
        }
        val color = when {
            step == 0 -> 0xFFFF5D5D.toInt()
            step == STEPS -> 0xFF34D399.toInt()
            else -> 0xFF278BF5.toInt()
        }
        val title = "${sats(st.terms.amount, masked)} sats each" + if (st.terms.script == com.kilombino.pyblockwatch.crypto.ScriptType.P2TR) " · Taproot" else ""
        val counting = st.phase == PoolSession.Phase.OPEN && st.terms.expiresAt > System.currentTimeMillis() / 1000

        fun views(layout: Int, big: Boolean) = RemoteViews(ctx.packageName, layout).apply {
            // Folded there is room for one line: the amount and the people (the dots say the step).
            setTextViewText(R.id.cj_title, com.kilombino.pyblockwatch.ui.i18n.I18n.tr(if (big) title else "${sats(st.terms.amount, masked)} sats · " + when (st.phase) {
                PoolSession.Phase.JOINING -> "joining"
                PoolSession.Phase.OPEN -> "$people/${st.terms.maxPeers} people"
                PoolSession.Phase.VOTING -> "$people people · vote"
                PoolSession.Phase.CLOSING, PoolSession.Phase.SIGNING -> "$people people · signing"
                PoolSession.Phase.BROADCAST -> "$people people · sent"
                PoolSession.Phase.CONFIRMED -> "confirmed"
                else -> "cancelled"
            }))
            setTextViewText(R.id.cj_status, com.kilombino.pyblockwatch.ui.i18n.I18n.tr(status))
            setTextColor(R.id.cj_status, color)
            if (big) setTextViewText(R.id.cj_where, com.kilombino.pyblockwatch.ui.i18n.I18n.tr(where))
            val dots = intArrayOf(R.id.cj_dot1, R.id.cj_dot2, R.id.cj_dot3, R.id.cj_dot4, R.id.cj_dot5)
            val bars = intArrayOf(R.id.cj_bar1, R.id.cj_bar2, R.id.cj_bar3, R.id.cj_bar4)
            for (i in 1..STEPS) {
                setImageViewResource(dots[i - 1], when {
                    step == 0 -> R.drawable.cj_dot_empty
                    i < step || step == STEPS -> R.drawable.cj_dot_done
                    i == step -> R.drawable.cj_dot_now
                    else -> R.drawable.cj_dot_empty
                })
                if (i < STEPS) setInt(bars[i - 1], "setBackgroundColor", if (step > 0 && i < step) 0xFF34D399.toInt() else 0xFF3A2E4E.toInt())
            }
            if (counting) {
                val left = (st.terms.expiresAt - System.currentTimeMillis() / 1000) * 1000
                setChronometer(R.id.cj_timer, SystemClock.elapsedRealtime() + left, null, true)
                setChronometerCountDown(R.id.cj_timer, true)
                setViewVisibility(R.id.cj_timer_box, android.view.View.VISIBLE)
            } else setViewVisibility(R.id.cj_timer_box, android.view.View.GONE)
        }

        val live = st.phase !in final
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(com.kilombino.pyblockwatch.ui.i18n.I18n.tr(title)).setContentText(com.kilombino.pyblockwatch.ui.i18n.I18n.tr(status))
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(views(R.layout.cj_round_small, false))
            .setCustomBigContentView(views(R.layout.cj_round_big, true))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(live).setAutoCancel(!live).setOnlyAlertOnce(true)
            .setGroup("coinjoin_rounds").setSortKey(st.created.toString())
            .setContentIntent(CoinjoinHub.openApp(ctx))
            .build()
    }

    /**
     * Brings the cards in line with the rounds: one per round going (the first one is the
     * coinjoin service's own, [skip]), a last one when a round ends, nothing for older ones.
     */
    @Synchronized
    fun update(ctx: Context, skip: String? = null) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shownFinal = prefs.getStringSet("final", emptySet())!!.toMutableSet()
        val states = CoinjoinHub.states.value
        for (st in states) {
            val id = idFor(st.poolId)
            when {
                st.phase in final -> if (st.poolId !in shownFinal) {
                    // Only a round seen going here gets its last card (not old ones loaded at start).
                    if (prefs.getBoolean("live:" + st.poolId, false)) runCatching { nm.notify(id, card(ctx, st)) }
                    shownFinal += st.poolId
                }
                st.poolId == skip -> { nm.cancel(id); prefs.edit().putBoolean("live:" + st.poolId, true).apply() }
                else -> { runCatching { nm.notify(id, card(ctx, st)) }; prefs.edit().putBoolean("live:" + st.poolId, true).apply() }
            }
        }
        // Rounds removed from the list: their cards and marks go too.
        val ids = states.map { it.poolId }.toSet()
        shownFinal.retainAll(ids)
        prefs.edit().putStringSet("final", shownFinal).apply {
            prefs.all.keys.filter { it.startsWith("live:") && it.removePrefix("live:") !in ids }
                .forEach { k -> nm.cancel(idFor(k.removePrefix("live:"))); remove(k) }
        }.apply()
    }
}
