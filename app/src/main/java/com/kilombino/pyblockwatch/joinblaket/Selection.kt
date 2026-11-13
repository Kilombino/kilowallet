package com.kilombino.pyblockwatch.joinblaket

import java.security.SecureRandom

/** A bonded offer with its bond's value on chain (0 when the bond did not verify, [problem] says why). */
data class ValuedOffer(val offer: Offer, val bond: BondProof, val value: Long, val problem: String? = null)

/**
 * `_update_offers_with_bond_values` (`taker/taker.py:817-961`) with the backend's `verify_bonds`
 * (`jmwallet/backends/base.py:596-683`).
 */
object BondValues {
    /**
     * Each distinct claim `(txid, vout, scriptPubKey)` is looked up once: skipped if the
     * certificate expired (`height > cert_expiry * 2016`); the output must be unspent, confirmed
     * and locked to exactly the proof's P2WSH; its confirmation time is the header time of block
     * `height - confirmations + 1`; the value is [Bond.value] at [nowSec]. Offers sharing a claim
     * share its value. Lookup failures give value 0 with the reason, never an exception, except
     * for the tip height: without it nothing is valued and that error propagates.
     */
    fun value(offers: List<BondedOffer>, chain: ChainLookup, nowSec: Long = System.currentTimeMillis() / 1000): List<ValuedOffer> {
        val height = chain.height()
        val results = HashMap<Triple<String, Long, String>, Pair<Long, String?>>()
        return offers.map { o ->
            val (value, problem) = results.getOrPut(o.claim) { valueOf(o.bond, height, chain, nowSec) }
            ValuedOffer(o.offer, o.bond, value, problem)
        }
    }

    private fun valueOf(bond: BondProof, height: Int, chain: ChainLookup, nowSec: Long): Pair<Long, String?> {
        if (bond.expiredAt(height.toLong())) return 0L to "certificate expired at block ${bond.certExpiryHeight}"
        val spk = bond.scriptPubKey
        return try {
            val u = chain.utxo(bond.txid, bond.vout, spk) ?: return 0L to "UTXO not found or spent"
            if (u.confirmations <= 0) return 0L to "UTXO unconfirmed"
            if (!u.scriptPubKey.contentEquals(spk)) return 0L to "ScriptPubKey mismatch"
            val time = chain.blockTime(height - u.confirmations + 1)
            val v = Bond.value(u.value, time, bond.locktime, nowSec)
            if (v > 0) v to null else 0L to "bond value is zero"
        } catch (e: Exception) {
            0L to "lookup failed: ${e.message}"
        }
    }
}

/**
 * Maker choice of a taker that only uses bonded makers (`bondless_makers_allowance = 0`):
 * `choose_orders` in `taker/orderbook.py:791-906` plus the XBT "go on with fewer" rule of
 * `taker/taker.py:1275-1294` and the final `minimum_makers` check (`:1412-1417`).
 */
object MakerSelection {
    sealed class Result {
        /** [offers] chosen (one per maker), [fees] what each is paid, by nick. */
        data class Chosen(val offers: List<ValuedOffer>, val fees: Map<String, Long>) : Result() {
            val totalFee: Long get() = fees.values.sum()
        }
        data class Failed(val reason: String) : Result()
    }

    /**
     * 1. Fewer offers in total than [n]: fail below `max(1, minimumMakers)`, else ask for that many.
     * 2. `filter_offers` ([Fees.acceptable], minus [ignored] nicks), then keep value > 0.
     * 3. Cheapest offer per maker, then cheapest per bond outpoint (sybil protection).
     * 4. `n = min(n, pool)`; pick without replacement, each pick weighted by bond value.
     * 5. Fewer chosen than [minimumMakers]: "Not enough makers with a fidelity bond".
     */
    fun choose(
        offers: List<ValuedOffer>, cjAmount: Long, n: Int, minimumMakers: Int = 1,
        maxFee: Fees.MaxCjFee = Fees.MaxCjFee(), ignored: Set<String> = emptySet(),
        requireQuantized: Boolean = true, roundUp: Boolean = false, equalize: Boolean = false,
        rng: java.util.Random = SecureRandom(),
    ): Result {
        var want = n
        if (offers.size < want) {
            val floor = maxOf(1, minimumMakers)
            if (offers.size < floor) return Result.Failed("Not enough offers: need $n (at least $floor), found ${offers.size}")
            want = offers.size
        }
        val eligible = offers.filter {
            it.offer.counterparty !in ignored && it.value > 0 &&
                Fees.acceptable(it.offer, cjAmount, maxFee, requireQuantized, roundUp)
        }
        fun fee(o: ValuedOffer) = Fees.offerFee(o.offer, cjAmount, roundUp)
        // sortedBy is stable, so equal fees keep the first offer like Python's sorted()[0].
        val perMaker = eligible.groupBy { it.offer.counterparty }.values.map { g -> g.sortedBy(::fee).first() }
        val pool = perMaker.groupBy { it.bond.txid to it.bond.vout }.values.map { g -> g.sortedBy(::fee).first() }
        val chosen = weightedChoose(pool, minOf(want, pool.size), rng)
        if (chosen.size < minimumMakers) {
            return Result.Failed("Not enough makers with a fidelity bond: found ${chosen.size}, need $minimumMakers " +
                "(makers without a fidelity bond are not used)")
        }
        return Result.Chosen(chosen, Fees.feePlan(chosen.map { it.offer }, cjAmount, roundUp, equalize))
    }

    /** `fidelity_bond_weighted_choose` with allowance 0: all of [pool] if it fits, else [n] weighted picks. */
    internal fun weightedChoose(pool: List<ValuedOffer>, n: Int, rng: java.util.Random): List<ValuedOffer> {
        if (pool.size <= n) return pool
        val remaining = pool.toMutableList()
        val selected = ArrayList<ValuedOffer>()
        repeat(n) {
            val total = remaining.sumOf { it.value.toDouble() }
            val r = rng.nextDouble() * total
            var cumulative = 0.0
            val picked = remaining.firstOrNull { cumulative += it.value; r <= cumulative } ?: remaining.last()
            selected.add(picked); remaining.remove(picked)
        }
        return selected
    }
}
