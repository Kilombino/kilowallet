package com.kilombino.pyblockwatch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketFeedParseTest {

    private val old = """{"pollMinutes":5,"updated":1792234412330,"stale":false,"height":977694,""" +
        """"thsBtcDay":0.0123,"xbtUsd":630.42,"changePct":-1.98,"networkHashps":41480701119463210}"""

    @Test fun olderServerWithoutDifficulty() {
        val m = MarketFeed.parse(old, 1L)
        assertEquals(977694L, m.height)
        assertNull(m.difficulty)
        assertNull(m.difficultyPrev)
        assertNull(m.difficultyNext)
        assertNull(m.difficultyChangePct)
        assertNull(m.difficultyRemainingBlocks)
        assertNull(m.difficultyRetargetMs)
    }

    @Test fun difficultyFields() {
        val json = old.dropLast(1) + ""","difficulty":5086468190.224727,"difficultyPrev":4625110531.247428,""" +
            """"difficultyPrevChangePct":9.975,"difficultyNext":5930443510.5,"difficultyChangePct":16.5924,""" +
            """"difficultyRemainingBlocks":66,"difficultyRetargetDate":1792268415816,"difficultyRetargetHeight":977760}"""
        val m = MarketFeed.parse(json, 1L)
        assertEquals(5086468190.224727, m.difficulty!!, 1e-3)
        assertEquals(4625110531.247428, m.difficultyPrev!!, 1e-3)
        assertEquals(5930443510.5, m.difficultyNext!!, 1e-3)
        assertEquals(16.5924, m.difficultyChangePct!!, 1e-9)
        assertEquals(9.975, m.difficultyPrevChangePct!!, 1e-9)
        assertEquals(66L, m.difficultyRemainingBlocks)
        assertEquals(1792268415816L, m.difficultyRetargetMs)
    }

    @Test fun nullsAndZeroMeanUnknown() {
        val json = old.dropLast(1) + ""","difficulty":0,"difficultyPrev":null,"difficultyNext":null,"difficultyChangePct":null}"""
        val m = MarketFeed.parse(json, 1L)
        assertNull(m.difficulty)
        assertNull(m.difficultyPrev)
        assertNull(m.difficultyNext)
        assertNull(m.difficultyChangePct)
    }

    @Test fun exchangeRates() {
        val json = old.dropLast(1) + ""","xbtEur":561.2,"fiatPerUsd":{"EUR":0.8902,"JPY":157.88,"RON":4.7503,"BAD":-1},"fiatDate":"2026-10-21"}"""
        val m = MarketFeed.parse(json, 1L)
        assertEquals(3, m.fiatPerUsd!!.size) // a rate that is not positive is dropped
        assertEquals(630.42, m.xbtIn("USD")!!, 1e-9)
        assertEquals(561.2, m.xbtIn("EUR")!!, 1e-9) // Kraken's own XBT/EUR first
        assertEquals(630.42 * 157.88, m.xbtIn("JPY")!!, 1e-6)
        assertEquals(630.42 * 4.7503 * 0.5, m.fiatValue(50_000_000, "RON")!!, 1e-6)
        assertNull(m.xbtIn("ARS")) // no rate, no price
        assertEquals(561.2 / 630.42, m.perUsd("EUR")!!, 1e-12)
        assertEquals(1.0, m.perUsd("USD")!!, 0.0)
        // An older server: only USD and EUR.
        assertNull(MarketFeed.parse(old, 1L).fiatPerUsd)
        assertNull(MarketFeed.parse(old, 1L).xbtIn("JPY"))
    }
}
