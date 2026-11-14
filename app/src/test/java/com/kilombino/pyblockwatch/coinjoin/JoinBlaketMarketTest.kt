package com.kilombino.pyblockwatch.coinjoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoinBlaketMarketTest {
    private val json = """{"updated":"x","height":981795,"network":"xbt","makers":2,"min_amount":100000,"max_amount":12312375,
        "offers":[{"type":"rel","minsize":100000,"maxsize":12312375,"cjfee":"0.00002","txfee":0,"bond_value":27738},
                  {"type":"abs","minsize":500000,"maxsize":2000000,"cjfee":"300","txfee":50,"bond_value":100}]}"""

    @Test fun parses() {
        val m = JoinBlaketMarket.parse(json)
        assertEquals(2, m.makers)
        assertEquals(100000L, m.minAmount)
        assertEquals(12312375L, m.maxAmount)
        assertEquals(2, m.offers.size)
        assertEquals(true, m.offers[0].rel)
        assertEquals(300.0, m.offers[1].cjfee, 0.0)
    }

    @Test fun emptyMarket() {
        val m = JoinBlaketMarket.parse("""{"makers":0,"min_amount":null,"max_amount":null,"offers":[]}""")
        assertEquals(0, m.makers)
        assertNull(m.minAmount)
        assertNull(JoinBlaketMarket.estimate(m, 200000, 1.0))
    }

    @Test fun estimateOneMaker() {
        val m = JoinBlaketMarket.parse(json)
        // 200 000 sats: only the rel offer takes it (the abs one starts at 500 000).
        val e = JoinBlaketMarket.estimate(m, 200_000, 2.0)!!
        assertEquals(1, e.makers)
        assertEquals(4L, e.makerFees) // 200 000 * 0.00002
        // 11 + 68*2 + 31*4 = 271 vB * 2 sat/vB
        assertEquals(542L, e.miningFee)
        assertEquals(546L, e.total)
    }

    @Test fun estimateTwoMakersAndOutOfRange() {
        val m = JoinBlaketMarket.parse(json)
        val e = JoinBlaketMarket.estimate(m, 1_000_000, 1.0)!!
        assertEquals(2, e.makers)
        assertEquals(20L + 300L, e.makerFees)
        // 11 + 68*3 + 31*6 = 401 vB, minus the abs maker's 50 sats contribution
        assertEquals(351L, e.miningFee)
        assertNull(JoinBlaketMarket.estimate(m, 50_000, 1.0))
        assertNull(JoinBlaketMarket.estimate(m, 20_000_000, 1.0))
    }
}
