package com.kilombino.pyblockwatch.coinjoin

import android.content.Context
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.TxParse

/**
 * Which coins of this wallet came out of a coinjoin. A round's saved state names its mixed
 * output, but rounds are dropped after a week or removed by hand, and a restored wallet has none:
 * so each mixed script is also kept here for good, and a coin nobody remembers is recognised from
 * the transaction that made it (the shape of a Kilojoin round, as mempool.kilombino.com reads it).
 */
object MixedCoins {
    private const val PREFS = "cj_mixed"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Scripts (hex) of every mixed output of this wallet: the kept ones and the rounds still listed. */
    fun scripts(ctx: Context): Set<String> {
        val kept = prefs(ctx).getStringSet("scripts", emptySet())!!
        val live = CoinjoinHub.states.value
            .filter { it.phase == PoolSession.Phase.BROADCAST || it.phase == PoolSession.Phase.CONFIRMED }
            // A payment's outputs are the recipients' coins, not ours.
            .filter { Destination.of(ctx, it.key) != Destination.Kind.PAY }
            .flatMap { st -> st.mixScripts.map { it.toHex() } }
        return kept + live
    }

    /** Keeps the mixed outputs of the rounds that went out (called on load and on every round event). */
    @Synchronized
    fun keep(ctx: Context) {
        val p = prefs(ctx)
        val kept = p.getStringSet("scripts", emptySet())!!
        val all = scripts(ctx)
        if (all.size != kept.size) p.edit().putStringSet("scripts", all).apply()
    }

    /** What the chain said about [outpoint] before: true, false, or null when never asked. */
    fun known(ctx: Context, outpoint: String): Boolean? =
        prefs(ctx).getString("tx:$outpoint", null)?.let { it == "1" }

    fun remember(ctx: Context, outpoint: String, mixed: Boolean) =
        prefs(ctx).edit().putString("tx:$outpoint", if (mixed) "1" else "0").apply()

    /**
     * True when output [vout] of [rawHex] is one of a Kilojoin round's mixed outputs: two or more
     * outputs of its exact (round) amount, two or more inputs, at most one change per input (v4
     * gives each person several mixed outputs, so they may outnumber the inputs), and every input
     * signed with the unified sighash 0x21 that Kilojoin always uses.
     */
    fun looksMixed(rawHex: String, vout: Int): Boolean = runCatching {
        val tx = TxParse.parse(rawHex)
        val mine = tx.outputs.getOrNull(vout) ?: return false
        val v = mine.value
        if (v < Protocol.TEST_MIN_AMOUNT || v % 1000 != 0L) return false
        val equal = tx.outputs.count { it.value == v }
        if (equal < 2 || tx.inputs.size < 2 || tx.outputs.size - equal > tx.inputs.size) return false
        if (tx.outputs.any { it.scriptPubKey.size != mine.scriptPubKey.size || it.scriptPubKey[0] != mine.scriptPubKey[0] }) return false
        tx.inputs.all { i -> i.witness.isNotEmpty() && i.witness[0].size >= 64 && (i.witness[0].last().toInt() and 0xFF) == 0x21 }
    }.getOrDefault(false)
}
