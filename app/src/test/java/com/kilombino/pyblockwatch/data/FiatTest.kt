package com.kilombino.pyblockwatch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FiatTest {
    @Test fun formats() {
        assertEquals("$1,234.56", Fiat.format(1234.561, "USD"))
        assertEquals("€0.47", Fiat.format(0.4712, "EUR"))
        assertEquals("¥157,880", Fiat.format(157880.4, "JPY"))
        assertEquals("1,234.56 RON", Fiat.format(1234.56, "RON"))
        assertEquals("3,174 COP", Fiat.format(3173.5, "COP"))
        assertEquals("R$5.02", Fiat.format(5.0233, "BRL"))
        assertEquals("$ –", Fiat.missing("USD"))
        assertEquals("– RON", Fiat.missing("RON"))
    }

    @Test fun typedAmounts() {
        assertEquals(12.5, Fiat.parse("12.5")!!, 0.0)
        assertEquals(12.5, Fiat.parse("12,5")!!, 0.0)
        assertEquals(1234.56, Fiat.parse("1,234.56")!!, 1e-9)
        assertEquals(1234.56, Fiat.parse("1.234,56")!!, 1e-9)
        assertEquals(1234567.0, Fiat.parse("1.234.567")!!, 0.0)
        assertEquals(1234567.0, Fiat.parse("1,234,567")!!, 0.0)
        assertNull(Fiat.parse(""))
        assertNull(Fiat.parse("abc"))
    }

    @Test fun satsFromFiat() {
        // 1 XBT = 500 RON: 10 RON is 2,000,000 sats.
        assertEquals(2_000_000L, Fiat.toSats("10", 500.0))
        assertEquals(2_000_000L, Fiat.toSats("10,00", 500.0))
        assertNull(Fiat.toSats("10", null))
        assertNull(Fiat.toSats("10", 0.0))
        assertNull(Fiat.toSats("-1", 500.0))
        // And back, as the fiat box shows it under typed sats.
        assertEquals("10.00", Fiat.plain(500.0 * 2_000_000 / 1e8, "RON"))
        assertEquals("1579", Fiat.plain(1578.8, "JPY"))
    }

    @Test fun availableNeedsARate() {
        val m = MarketFeed.parse("""{"xbtUsd":600,"fiatPerUsd":{"JPY":150,"RON":4.7,"XYZ":2}}""", 1L)
        val codes = Fiat.available(m).map { it.code }
        assertEquals(listOf("USD", "EUR", "JPY", "RON"), codes) // unknown codes are left out, the list keeps its order
        assertEquals(listOf("USD", "EUR"), Fiat.available(null).map { it.code })
    }
}
