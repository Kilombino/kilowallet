package com.kilombino.pyblockwatch.data

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.TxParse

/**
 * What the JoinMarket wallet's own transactions say beyond the balances: the coins (UTXOs) each
 * jar holds, and what the wallet earned as a maker. Worked out from the transactions a look
 * already fetched ([lookup]: by txid, null when not at hand), so it needs no Jam API and no extra
 * round trip. Read-only, and pure: the tests feed it small made-up transactions.
 */
object JamLedger {

    /** One unspent output of the wallet, with the address that holds it. */
    data class Coin(
        val txid: String, val vout: Int, val value: Long,
        val address: String, val path: String, val jar: Int?, val chainIndex: Int,
        val confirmations: Int, val pending: Boolean,
    ) {
        /** Sitting at a fidelity bond's timelocked address. */
        val bond: Boolean get() = chainIndex == JoinMarket.BOND_BRANCH
    }

    /**
     * Every unspent output of [rows]: each output of a wallet transaction ([txs]) paid to one of
     * the wallet's scripts that no wallet transaction spends. Whatever spends a coin of ours is in
     * that address's history, so it is among [txs] too: the list is complete once every one of
     * them is at hand. [complete] is false when one is not (the list may then miss or keep coins).
     */
    data class Coins(val coins: List<Coin>, val complete: Boolean)

    fun coins(rows: List<AddressRow>, txs: List<TxConf>, lookup: (String) -> TxParse.Tx?): Coins {
        val byScript = rows.associateBy { it.scriptHash }
        val parsed = txs.mapNotNull { t -> lookup(t.txid)?.let { t to it } }
        val spent = parsed.flatMap { (_, tx) -> tx.inputs.map { it.txid to it.vout } }.toHashSet()
        val coins = parsed.flatMap { (t, tx) ->
            tx.outputs.mapIndexedNotNull { vout, o ->
                val row = byScript[Address.electrumScriptHash(o.scriptPubKey)] ?: return@mapIndexedNotNull null
                if ((t.txid to vout) in spent) return@mapIndexedNotNull null
                Coin(t.txid, vout, o.value, row.address, row.path, row.jar, row.chainIndex, t.confirmations, t.pending)
            }
        }.sortedByDescending { it.value }
        return Coins(coins, parsed.size == txs.size)
    }

    /** One CoinJoin this wallet took part in as a maker, and what it earned there. */
    data class MakerJoin(
        val txid: String,
        /** Our outputs minus our inputs: the maker's fee less its share of the mining fee. */
        val earned: Long,
        /** The jar the coins came from (JoinMarket spends a CoinJoin's coins from a single jar). */
        val jar: Int?,
        /** The CoinJoin's amount: the value of its equal outputs, and how many there were. */
        val amount: Long, val equalOutputs: Int,
        val confirmations: Int, val pending: Boolean,
    )

    data class Earnings(val joins: List<MakerJoin>) {
        val total: Long get() = joins.sumOf { it.earned }
        val count: Int get() = joins.size
        fun forJar(jar: Int) = Earnings(joins.filter { it.jar == jar })
    }

    /**
     * What the wallet earned as a JoinMarket maker. A transaction counts when ALL of these hold:
     *
     *  1. it is a CoinJoin: at least [MIN_EQUAL] outputs of the same value, one of them ours (the
     *     maker's CoinJoin output is exactly the taker's amount);
     *  2. it spends coins of ours: the maker puts in inputs, so a plain receive (even one that came
     *     out of somebody else's CoinJoin) never counts;
     *  3. what came back to us (our outputs, CoinJoin output and change) is MORE than what we put
     *     in: the difference is the maker's fee less its share of the mining fee. A CoinJoin we made
     *     as the taker pays the makers and the mining fee, so its net is negative and it is left out.
     *
     * Every input is checked: one whose previous transaction is not at hand and is in our own
     * history might be ours, so that transaction is left out rather than guessed at (a coin of
     * ours was always paid by a transaction in our history; one outside it cannot be ours). A
     * mined reward is never a CoinJoin. The figure is a floor: a CoinJoin whose net came to zero
     * or less (fee smaller than the mining-fee share) earned nothing and is not counted.
     */
    fun makerEarnings(rows: List<AddressRow>, txs: List<TxConf>, lookup: (String) -> TxParse.Tx?): Earnings {
        val byScript = rows.associateBy { it.scriptHash }
        val history = txs.map { it.txid }.toHashSet()
        val joins = txs.mapNotNull { t ->
            val tx = lookup(t.txid) ?: return@mapNotNull null
            if (Scanner.isCoinbase(tx)) return@mapNotNull null
            fun ours(o: TxParse.Out) = byScript[Address.electrumScriptHash(o.scriptPubKey)]
            // 1. equal outputs, one of them ours
            val equal = tx.outputs.groupBy { it.value }.filter { (_, os) -> os.size >= MIN_EQUAL && os.any { ours(it) != null } }
            val amount = equal.keys.maxOrNull() ?: return@mapNotNull null
            // 2. our inputs (every input accounted for)
            var ourIn = 0L
            val inJars = HashSet<Int?>()
            for (i in tx.inputs) {
                val prev = lookup(i.txid)
                if (prev == null) { if (i.txid in history) return@mapNotNull null else continue }
                val o = prev.outputs.getOrNull(i.vout) ?: continue
                val row = ours(o) ?: continue
                ourIn += o.value; inJars += row.jar
            }
            if (ourIn <= 0) return@mapNotNull null
            // 3. more back than in
            val earned = tx.outputs.sumOf { o -> if (ours(o) != null) o.value else 0L } - ourIn
            if (earned <= 0) return@mapNotNull null
            MakerJoin(t.txid, earned, inJars.singleOrNull(), amount, equal.getValue(amount).size, t.confirmations, t.pending)
        }
        return Earnings(joins)
    }

    /** A CoinJoin with a single maker has two equal outputs (the taker's and the maker's). */
    const val MIN_EQUAL = 2
}
