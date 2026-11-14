package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.joinblaket.Vectors.objects
import com.kilombino.pyblockwatch.joinblaket.Vectors.strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Vector section `fees`. */
class FeesTest {
    private val v = Vectors.section("fees")

    private fun offer(nick: String, type: String, cjfee: String, txfee: Long) = Offer(
        nick, 0, OfferType.values().first { it.wire == type }, 27300, 10_000_000_000, txfee, cjfee,
    )

    @Test fun relativeFeeRounding() {
        for (c in v.getJSONArray("relative_fee_cases").objects()) {
            assertEquals(c.toString(), c.getLong("fee"), Fees.relativeFee(c.getLong("amount"), c.getString("cjfee")))
        }
    }

    @Test fun perOfferFeesGridAndLimits() {
        val amount = v.getLong("cj_amount")
        for (o in v.getJSONArray("offers").objects()) {
            val off = offer(o.getString("nick"), o.getString("ordertype"), o.getString("cjfee"), o.getLong("txfee"))
            assertEquals(o.getLong("fee"), Fees.offerFee(off, amount))
            assertEquals(o.getLong("fee_rounded_up"), Fees.offerFee(off, amount, roundUp = true))
            assertEquals(o.getBoolean("quantized"), Fees.isQuantized(off))
            assertEquals(o.getBoolean("within_default_limits"), Fees.withinLimits(off))
            // require_quantized_cj_fees (default) drops the off-grid offer.
            assertEquals(o.getBoolean("quantized") && o.getBoolean("within_default_limits"), Fees.acceptable(off, amount))
        }
        assertEquals(v.getJSONArray("quant_rel_grid").strings().map(::BigDecimal), Fees.QUANT_REL)
        val abs = v.getJSONArray("quant_abs_grid")
        assertEquals((0 until abs.length()).map { abs.getLong(it) }, Fees.QUANT_ABS)
        assertEquals(v.getLong("quantize_abs_up_150"), Fees.quantizeAbsUp(150))
        assertNull(Fees.quantizeRelUp("0.2"))
        val max = v.getJSONObject("default_max_cj_fee")
        assertEquals(max.getLong("abs_fee"), Fees.MaxCjFee().absFee)
        assertEquals(max.getString("rel_fee"), Fees.MaxCjFee().relFee)
    }

    @Test fun offGridAndOverLimitRejected() {
        val amount = 30_000_000L
        assertFalse(Fees.acceptable(offer("J5a", "sw0reloffer", "0.00025", 0), amount))
        assertFalse(Fees.acceptable(offer("J5a", "sw0absoffer", "150", 0), amount))
        assertTrue(Fees.acceptable(offer("J5a", "sw0reloffer", "0.00025", 0), amount, requireQuantized = false))
        assertFalse(Fees.acceptable(offer("J5a", "sw0reloffer", "0.002", 0), amount))   // on grid, above 0.1 %
        assertFalse(Fees.acceptable(offer("J5a", "sw0absoffer", "1000", 0), amount))    // on grid, above 500
        assertFalse(Fees.acceptable(offer("J5a", "swreloffer", "0.0002", 0), amount))   // legacy type
        assertFalse(Fees.acceptable(offer("J5a", "sw0reloffer", "0.0002", 0), 10_000))  // below minsize
        assertTrue(Fees.isQuantized(offer("J5a", "sw0reloffer", "0.00020", 0)))         // Decimal equality
    }

    @Test fun feePlans() {
        val amount = v.getLong("cj_amount")
        val offers = v.getJSONArray("offers").objects().take(2).map {
            offer(it.getString("nick"), it.getString("ordertype"), it.getString("cjfee"), it.getLong("txfee"))
        }
        val plan = v.getJSONObject("fee_plan"); val eq = v.getJSONObject("fee_plan_equalized")
        assertEquals(plan.keys().asSequence().associateWith { plan.getLong(it) }, Fees.feePlan(offers, amount))
        assertEquals(eq.keys().asSequence().associateWith { eq.getLong(it) }, Fees.feePlan(offers, amount, equalize = true))
    }

    @Test fun shapesAndSweep() {
        val base = v.getDouble("fee_rate_base"); val rnd = v.getDouble("fee_rate_randomized")
        for (s in v.getJSONArray("shapes").objects()) {
            val i = s.getInt("inputs"); val o = s.getInt("outputs")
            assertEquals(s.getLong("vsize_estimate"), Fees.vsizeEstimate(i, o))
            assertEquals(s.getLong("fee_at_randomized_rate"), Fees.txFee(i, o, rnd))
            assertEquals(s.getLong("fee_at_base_rate"), Fees.txFee(i, o, base))
        }
        val sw = v.getJSONObject("sweep")
        assertEquals(sw.getLong("cj_amount"), Fees.sweepAmount(sw.getLong("available"), sw.getJSONArray("relative_fees").strings()))
        assertEquals(4, Fees.outputCount(1))
        repeat(50) { val r = Fees.randomizedRate(2.0); assertTrue(r >= 2.0 && r <= 2.4) }
        assertEquals(100.0, Fees.randomizedRate(100.0), 0.0)
    }

    @Test fun minerFeePolicy() {
        assertNull(Fees.checkMinerFee(1000 + 271, 1000, 2, 4))
        assertEquals("CoinJoin has a negative miner fee", Fees.checkMinerFee(1000, 1001, 2, 4))
        assertTrue(Fees.checkMinerFee(1000 + 270, 1000, 2, 4)!!.contains("below"))
        assertTrue(Fees.checkMinerFee(1000 + 27101, 1000, 2, 4)!!.contains("cap"))
    }
}
