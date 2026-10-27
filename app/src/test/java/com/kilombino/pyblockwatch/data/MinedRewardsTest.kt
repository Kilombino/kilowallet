package com.kilombino.pyblockwatch.data

import com.kilombino.pyblockwatch.crypto.TxParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mined rewards (coinbase outputs): which ones are still locked, until which block, and about when. */
class MinedRewardsTest {
    /** A raw transaction with one input spending [prevTxid]:[prevVout] and one output of 50 000 sats. */
    private fun tx(prevTxid: String, prevVout: Long): String {
        val prev = prevTxid.chunked(2).reversed().joinToString("")
        val vout = (0 until 4).joinToString("") { "%02x".format((prevVout shr (8 * it)) and 0xff) }
        return "01000000" + "01" + prev + vout + "03" + "01e803" + "ffffffff" +
            "01" + "50c3000000000000" + "16" + "0014" + "11".repeat(20) + "00000000"
    }

    @Test fun aCoinbaseIsOneInputSpendingNothing() {
        val coinbase = TxParse.parse(tx("00".repeat(32), 0xffffffffL))
        assertTrue(Scanner.isCoinbase(coinbase))
        // An ordinary spend, even of output 0 of an all-zero-looking id but a real index, is not.
        assertFalse(Scanner.isCoinbase(TxParse.parse(tx("ab".repeat(32), 0))))
        assertFalse(Scanner.isCoinbase(TxParse.parse(tx("00".repeat(32), 0))))
    }

    @Test fun lockedUntilItsMaturity() {
        // The flag day's long maturity (blocks 973 440 to 979 919): 6 480 confirmations.
        val h = 975_000
        assertEquals(6_480, Scanner.maturityFor(h))
        assertEquals(h + 6_479, Scanner.maturesAt(h))
        assertTrue(Scanner.immature(true, h, h + 6_478))
        assertFalse(Scanner.immature(true, h, h + 6_479)) // spendable once the tip is its maturity block
        assertFalse(Scanner.immature(false, h, h + 10)) // not a reward: never locked
        assertFalse(Scanner.immature(true, 0, 100)) // unconfirmed: nothing to say
        // Outside the flag day: the usual 100.
        assertEquals(100, Scanner.maturityFor(979_920)); assertEquals(100, Scanner.maturityFor(973_439))
        assertEquals(979_920 + 99, Scanner.maturesAt(979_920))
        assertTrue(Scanner.immature(true, 979_920, 980_018)); assertFalse(Scanner.immature(true, 979_920, 980_019))
        // Only coins younger than the longest maturity can be locked rewards: older ones are not looked at.
        assertTrue(Scanner.mayBeImmature(h, h + 6_478)); assertFalse(Scanner.mayBeImmature(h, h + 6_479))
        assertFalse(Scanner.mayBeImmature(0, h))
    }

    @Test fun totalsAndDates() {
        fun coin(v: Long, height: Int) = Scanner.ImmatureCoin(Scanner.SpendableUtxo("aa", 0, v, 0, 0, height), Scanner.maturesAt(height))
        val im = Scanner.Immature(976_000, listOf(coin(1_000, 975_000), coin(2_000, 975_500)), 600.0)
        assertEquals(3_000L, im.total)
        // 6 479 − 1 000 blocks to go at ten minutes each.
        assertEquals(1_000_000L + (975_000 + 6_479 - 976_000) * 600L, im.eta(Scanner.maturesAt(975_000), now = 1_000_000))
        assertEquals(1_000_000L, im.eta(970_000, now = 1_000_000)) // already past: now
        assertEquals(1_000L, im.of(Scanner.SpendableUtxo("aa", 0, 0, 0, 0, 0))?.coin?.value)
    }
}
