package com.kilombino.pyblockwatch.joinblaket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [TakerBudget]: SEND's JoinBlaket MAX is the largest amount the round's own coin check
 * ([TakerBudget.need], as [TakerSession] runs it) passes in the worst case, and one sat more
 * fails it.
 */
class TakerBudgetTest {
    private val config = TakerConfig()

    @Test fun maxIsTheWorstCaseBoundary() {
        val rng = Random(7)
        repeat(300) {
            val coins = 30_000L + (rng.nextDouble() * 2_000_000_000).toLong()
            val inputs = 1 + rng.nextInt(12)
            val makers = 1 + rng.nextInt(9)
            val rate = listOf(0.1, 1.0, 2.0, 7.5, 40.0, 150.0)[rng.nextInt(6)]
            val max = TakerBudget.maxAmount(coins, inputs, makers, rate, config)
            if (max == 0L) {
                assertTrue(TakerBudget.worstNeed(1, inputs, makers, rate, config) > coins)
            } else {
                assertTrue("max fits", TakerBudget.worstNeed(max, inputs, makers, rate, config) <= coins)
                assertTrue("max + 1 doesn't", TakerBudget.worstNeed(max + 1, inputs, makers, rate, config) > coins)
            }
        }
    }

    /** Whatever rate the round draws and whatever fees makers within the limits ask, MAX passes the round's check. */
    @Test fun maxPassesTheRoundsCheckForAnyDrawAndFees() {
        val rng = Random(11)
        repeat(2000) {
            val coins = 100_000L + (rng.nextDouble() * 500_000_000).toLong()
            val inputs = 1 + rng.nextInt(8)
            val makers = 1 + rng.nextInt(9)
            val rate = 0.5 + rng.nextDouble() * 30
            val max = TakerBudget.maxAmount(coins, inputs, makers, rate, config)
            assertTrue(max > 0)
            val drawn = Fees.randomizedRate(rate.coerceIn(config.minFeeRate, config.maxFeeRate), config.txFeeFactor, config.maxFeeRate, rng)
            val chosen = (1..makers).random(kotlin.random.Random(rng.nextLong()))
            val fees = (1..chosen).sumOf { (rng.nextDouble() * TakerBudget.makerFeeCap(max, config)).toLong() }
            assertTrue(TakerBudget.need(max, fees, inputs, chosen, drawn, config) <= coins)
        }
    }

    @Test fun capsFollowTheTakersLimits() {
        assertEquals(1.0 * 1.2, TakerBudget.rateCap(0.1, config), 1e-9)   // clamped up to 1 sat/vB, then +20 %
        assertEquals(100.0, TakerBudget.rateCap(150.0, config), 1e-9)     // never above the cap
        assertEquals(500L, TakerBudget.makerFeeCap(100_000, config))       // 0.1 % of 100k is below the absolute limit
        assertEquals(1_118L, TakerBudget.makerFeeCap(1_118_405, config))  // 0.1 %
        assertEquals(0L, TakerBudget.maxAmount(1_000, 1, 1, 1.0, config))
    }

    /** The screenshot's case: coins of 1 118 405 sats can't pay 1 117 943 through a CoinJoin; MAX says what they can. */
    @Test fun theReportedCase() {
        val coins = 1_118_405L
        assertTrue(TakerBudget.worstNeed(1_117_943, 3, 3, 1.0, config) > coins)
        val max = TakerBudget.maxAmount(coins, 3, 3, 1.0, config)
        assertTrue(max in 1_100_000 until 1_117_943)
    }
}
