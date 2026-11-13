package com.kilombino.pyblockwatch.joinblaket

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.SecureRandom
import kotlin.math.ceil
import kotlin.math.min

/**
 * Fee arithmetic of the taker (`jmcore/bitcoin.py`, `jmcore/fee_quantization.py`,
 * `taker/orderbook.py`, `taker/coinjoin_session.py`). Maker fees are exact Decimal maths with
 * round-half-even, the mining fee is a float rate times an integer size, rounded up.
 */
object Fees {
    /** Relative-fee grid (fraction of the CoinJoin amount). */
    val QUANT_REL: List<BigDecimal> = listOf(
        "0.00002", "0.00005", "0.0001", "0.0002", "0.0005", "0.001",
        "0.002", "0.005", "0.01", "0.02", "0.05", "0.1",
    ).map(::BigDecimal)

    /** Absolute-fee grid (sats). */
    val QUANT_ABS: List<Long> = listOf(0, 100, 200, 500, 1000, 2000, 5000, 10000)

    /** Taker change at or below this goes to the miner. */
    const val TAKER_DUST = 2730L
    /** A maker whose change would fall below this is dropped (`DUST_THRESHOLD`). */
    const val MAKER_DUST = 27300L

    /** `MaxCjFee` defaults: 500 sats for absolute offers, 0.1 % for relative ones. */
    data class MaxCjFee(val absFee: Long = 500, val relFee: String = "0.001")

    /**
     * `calculate_relative_fee`: `int((Decimal(cjfee) * Decimal(amount)).quantize(Decimal(1)))`,
     * round half to even. Accepts scientific notation ("1E-5") and integer strings.
     */
    fun relativeFee(amount: Long, cjfee: String): Long {
        require(amount >= 0) { "amount cannot be negative" }
        return BigDecimal(cjfee).multiply(BigDecimal.valueOf(amount)).setScale(0, RoundingMode.HALF_EVEN).longValueExact()
    }

    /** Smallest grid value >= [fee], or null above the grid. */
    fun quantizeRelUp(fee: String): BigDecimal? = BigDecimal(fee).let { d -> QUANT_REL.firstOrNull { it >= d } }
    fun quantizeAbsUp(fee: Long): Long? = QUANT_ABS.firstOrNull { it >= fee }

    /** Whether the advertised fee is exactly on its grid (`require_quantized_cj_fees`). */
    fun isQuantized(offer: Offer): Boolean =
        if (offer.type.absolute) offer.cjfee.toLong() in QUANT_ABS
        else BigDecimal(offer.cjfee).let { d -> QUANT_REL.any { it.compareTo(d) == 0 } }

    /** The fee policy actually paid: advertised, or raised to the grid with [roundUp]. */
    private fun paidPolicy(offer: Offer, roundUp: Boolean): String? =
        if (offer.type.absolute) {
            val a = offer.cjfee.toLong()
            (if (roundUp) quantizeAbsUp(a) else a)?.toString()
        } else {
            if (roundUp) quantizeRelUp(offer.cjfee)?.toPlainString() else offer.cjfee
        }

    /** `calculate_cj_fee`: what the taker pays [offer] for [cjAmount]. */
    fun offerFee(offer: Offer, cjAmount: Long, roundUp: Boolean = false): Long {
        val p = paidPolicy(offer, roundUp) ?: throw IllegalArgumentException("offer from ${offer.counterparty} has no upper fee quantum")
        return if (offer.type.absolute) p.toLong() else relativeFee(cjAmount, p)
    }

    /** `is_fee_within_limits`: each offer type against its own limit only. */
    fun withinLimits(offer: Offer, max: MaxCjFee = MaxCjFee(), roundUp: Boolean = false): Boolean {
        val p = paidPolicy(offer, roundUp) ?: return false
        return if (offer.type.absolute) p.toLong() <= max.absFee else BigDecimal(p) <= BigDecimal(max.relFee)
    }

    /**
     * The per-offer part of `filter_offers`: a usable `sw0` type, on the grid when
     * [requireQuantized] (default true), [cjAmount] within min/max size, fee within [max].
     */
    fun acceptable(offer: Offer, cjAmount: Long, max: MaxCjFee = MaxCjFee(), requireQuantized: Boolean = true, roundUp: Boolean = false): Boolean =
        offer.type.usable && (!requireQuantized || isQuantized(offer)) &&
            cjAmount in offer.minsize..offer.maxsize && withinLimits(offer, max, roundUp)

    /**
     * `calculate_cj_fee_plan`: nick to paid fee, in offer order; with [equalize] every maker is
     * paid the largest fee of the set.
     */
    fun feePlan(offers: List<Offer>, cjAmount: Long, roundUp: Boolean = false, equalize: Boolean = false): LinkedHashMap<String, Long> {
        val fees = LinkedHashMap<String, Long>()
        for (o in offers) fees[o.counterparty] = offerFee(o, cjAmount, roundUp)
        if (equalize && fees.isNotEmpty()) { val top = fees.values.max(); fees.keys.forEach { fees[it] = top } }
        return fees
    }

    /** `estimate_p2wpkh_vsize`: 68 per input, 31 per output, 11 overhead. */
    fun vsizeEstimate(inputs: Int, outputs: Int): Long = inputs * 68L + outputs * 31L + 11

    /** `_estimate_tx_fee`: `ceil(vsize_estimate * rate)`. */
    fun txFee(inputs: Int, outputs: Int, rate: Double): Long = ceil(vsizeEstimate(inputs, outputs) * rate).toLong()

    /** Outputs of a normal (non-sweep) CoinJoin: our cj + change and one cj + change per maker. */
    fun outputCount(makers: Int): Int = 1 + makers + 1 + makers

    /**
     * The once-per-round randomized rate: uniform in `[rate, min(rate * (1 + factor), max)]`
     * (`_apply_fee_randomization`, `tx_fee_factor` 0.2, `max_fee_rate_sat_vb` 100).
     */
    fun randomizedRate(rate: Double, factor: Double = 0.2, maxRate: Double = 100.0, rng: java.util.Random = SecureRandom()): Double {
        val hi = min(rate * (1 + factor), maxRate)
        return if (hi <= rate) rate else rate + rng.nextDouble() * (hi - rate)
    }

    /** Taker change: `inputs - cj_amount - sum(maker fees) - tx_fee` (no output if <= 2730). */
    fun takerChange(takerInputs: Long, cjAmount: Long, makerFees: Collection<Long>, txFee: Long): Long =
        takerInputs - cjAmount - makerFees.sum() - txFee

    /** Maker change: `inputs - cj_amount - offer.txfee + cjfee`. */
    fun makerChange(makerInputs: Long, cjAmount: Long, offerTxfee: Long, cjfee: Long): Long =
        makerInputs - cjAmount - offerTxfee + cjfee

    /**
     * `calculate_sweep_amount`: the largest cj amount with `amount + sum(relative fees) <=
     * available`, exact fractions then a downward correction for half-even rounding.
     */
    fun sweepAmount(available: Long, relativeFees: List<String>): Long {
        require(available >= 0) { "amount cannot be negative" }
        if (relativeFees.isEmpty()) return available
        val sum = relativeFees.fold(BigDecimal.ZERO) { a, f -> a + BigDecimal(f) }
        val scale = maxOf(0, sum.scale())
        val denominator = BigDecimal.ONE.movePointRight(scale).toBigIntegerExact()
        val numerator = sum.movePointRight(scale).toBigIntegerExact()
        var amount = java.math.BigInteger.valueOf(available).multiply(denominator).divide(denominator + numerator).toLong()
        while (amount > 0 && amount + relativeFees.sumOf { relativeFee(amount, it) } > available) amount--
        return amount
    }

    /**
     * The miner-fee check before `!tx` (`_verify_unsigned_miner_fee`): fee = all input values
     * minus all output values, non-negative, at most `maxRate * vsize_estimate`, at least
     * `minRate * vsize_estimate`. Returns null if fine, else the reason.
     */
    fun checkMinerFee(totalIn: Long, totalOut: Long, inputs: Int, outputs: Int, maxRate: Double = 100.0, minRate: Double? = 1.0): String? {
        val fee = totalIn - totalOut
        val vsize = vsizeEstimate(inputs, outputs)
        if (fee < 0) return "CoinJoin has a negative miner fee"
        if (fee > maxRate * vsize) return "miner fee rate above the safety cap"
        if (minRate != null && fee < minRate * vsize) return "miner fee rate below the minimum"
        return null
    }
}
