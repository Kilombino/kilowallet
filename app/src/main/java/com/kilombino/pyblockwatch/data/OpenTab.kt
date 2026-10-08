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
    /** Not a tab: a newer release was found in the background; the app offers it on opening. */
    const val UPDATE = "update"
    /** Set by settings' CHECK NOW: say so when there is nothing newer. */
    @Volatile var checkNow = false

    /** The tab a notification asked for, until the wallet screen has acted on it. */
    val flow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun pending(ctx: Context, tab: String): PendingIntent {
        val i = Intent(ctx, com.kilombino.pyblockwatch.ui.MainActivity::class.java)
            .putExtra(EXTRA, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // One request code per tab, or a later notification's extras would overwrite another's.
        return PendingIntent.getActivity(ctx, 100 + tab.hashCode() % 100, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun from(intent: Intent?) { intent?.getStringExtra(EXTRA)?.let { flow.value = it } }
}
