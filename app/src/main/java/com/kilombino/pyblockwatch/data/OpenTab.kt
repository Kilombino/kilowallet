package com.kilombino.pyblockwatch.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Notifications open the tab they are about, already refreshed: "btc", "spamcoin", "ark",
 * "coinjoin". The intent goes to the running MainActivity (singleTop) so it is delivered
 * even when the app is only in the background.
 */
object OpenTab {
    const val EXTRA = "open_tab"
    const val BTC = "btc"
    const val SPAMCOIN = "spamcoin"
    const val ARK = "ark"
    const val COINJOIN = "coinjoin"
    const val LIGHTNING = "lightning"
    /** BTC of one wallet: a payment's notification opens the wallet it is about. */
    const val BTC_HOT = "btc:hot"
    const val BTC_WATCH = "btc:watch"
    /** The coinjoin tab on one list: POOLS (a new pool), IN PROGRESS (a round of ours)… */
    const val COINJOIN_POOLS = "coinjoin:pools"
    const val COINJOIN_ROUNDS = "coinjoin:rounds"
    /** …or HISTORY (a round confirmed or cancelled). */
    const val COINJOIN_HISTORY = "coinjoin:history"
    /** Not a tab: a newer release was found in the background; the app offers it on opening. */
    const val UPDATE = "update"
    /** Set by settings' CHECK NOW: say so when there is nothing newer. */
    @Volatile var checkNow = false

    /** The tab a notification asked for, until the wallet screen has acted on it. */
    val flow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** The coinjoin list asked for (0 POOLS, 1 IN PROGRESS, 2 HISTORY), until the coinjoin screen shows it. */
    val coinjoinList = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)

    private val TABS = listOf(BTC, SPAMCOIN, ARK, COINJOIN, LIGHTNING, UPDATE, BTC_HOT, BTC_WATCH,
        COINJOIN_POOLS, COINJOIN_ROUNDS, COINJOIN_HISTORY)

    fun pending(ctx: Context, tab: String): PendingIntent {
        val i = Intent(ctx, com.kilombino.pyblockwatch.ui.MainActivity::class.java)
            .putExtra(EXTRA, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // One request code per tab, or a later notification's extras would overwrite another's (two
        // tabs' hashes could meet modulo 100: each has its own number now).
        val code = TABS.indexOf(tab).let { if (it >= 0) 100 + it else 200 + Math.floorMod(tab.hashCode(), 100) }
        return PendingIntent.getActivity(ctx, code, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun from(intent: Intent?) { intent?.getStringExtra(EXTRA)?.let { flow.value = it } }
}
