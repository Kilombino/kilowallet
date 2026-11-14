package com.kilombino.pyblockwatch.data

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.TxParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JoinMarket wallet's coins and maker earnings, on small made-up transactions. */
class JamLedgerTest {
    // Scripts: ours are "a0", "a1", "c0"… (any bytes do: only their Electrum script hash matters).
    private fun spk(name: String) = name.toByteArray()
    private fun row(name: String, jar: Int, chainIndex: Int = 0, index: Int = 0) =
        AddressRow(chainIndex, index, "bc1q$name", "m/84'/0'/$jar'/$chainIndex/$index", Address.electrumScriptHash(spk(name)), jar = jar)

    private fun input(txid: String, vout: Int) = TxParse.In(txid, vout, ByteArray(0), 0xffffffffL, emptyList())
    private fun out(value: Long, name: String) = TxParse.Out(value, spk(name))
    private fun tx(ins: List<TxParse.In>, outs: List<TxParse.Out>) = TxParse.Tx(2, ins, outs, 0)

    private val rows = listOf(row("a0", 0), row("c0", 0, 1), row("a1", 1), row("c1", 1, 1), row("a2", 2),
        row("bond", 0, JoinMarket.BOND_BRANCH, 82))

    // A deposit into jar 0 (from a stranger's coin), then a CoinJoin where we are the maker:
    // 100 000 sats from jar 0 → 50 000 CoinJoin output to jar 1 + 50 250 change to jar 0 (earned 250).
    private val deposit = tx(listOf(input("ff".repeat(32), 0)), listOf(out(100_000, "a0"), out(7_000, "stranger")))
    private val makerJoin = tx(listOf(input("dep", 0), input("ee".repeat(32), 1), input("dd".repeat(32), 3)),
        listOf(out(50_000, "a1"), out(50_000, "taker"), out(50_000, "maker2"), out(50_250, "c0"), out(12_000, "x"), out(9_000, "y")))
    private val all = mapOf("dep" to deposit, "mj" to makerJoin)
    private val txs = listOf(TxConf("mj", 3, false), TxConf("dep", 10, false))

    @Test fun coinsAreTheUnspentOutputsOfOurs() {
        val c = JamLedger.coins(rows, txs, all::get)
        assertTrue(c.complete)
        // The deposit's output was spent by the CoinJoin; the two outputs of ours from it remain.
        assertEquals(listOf("mj" to 3, "mj" to 0), c.coins.map { it.txid to it.vout })
        val change = c.coins.first()
        assertEquals(50_250, change.value); assertEquals(0, change.jar); assertEquals("bc1qc0", change.address)
        assertEquals("m/84'/0'/0'/1/0", change.path); assertEquals(3, change.confirmations); assertFalse(change.bond)
        assertEquals(1, c.coins[1].jar)
    }

    @Test fun aMissingTransactionMakesTheListIncomplete() {
        val c = JamLedger.coins(rows, txs, mapOf("dep" to deposit)::get)
        assertFalse(c.complete)
        assertEquals(listOf("dep" to 0), c.coins.map { it.txid to it.vout })
    }

    @Test fun aBondCoinIsMarked() {
        val b = tx(listOf(input("ab".repeat(32), 0)), listOf(out(1_000_000, "bond")))
        val c = JamLedger.coins(rows, listOf(TxConf("b", 0, true)), mapOf("b" to b)::get)
        assertTrue(c.coins.single().bond); assertTrue(c.coins.single().pending)
    }

    @Test fun makerCoinJoinEarnsOursOutMinusOursIn() {
        val e = JamLedger.makerEarnings(rows, txs, all::get)
        assertEquals(1, e.count)
        val j = e.joins.single()
        assertEquals("mj", j.txid); assertEquals(250, j.earned); assertEquals(0, j.jar)
        assertEquals(50_000, j.amount); assertEquals(3, j.equalOutputs)
        assertEquals(250, e.total)
        assertEquals(250, e.forJar(0).total); assertEquals(0, e.forJar(1).count)
    }

    @Test fun aPlainReceiveIsNotAnEarning() {
        // Paid to us out of somebody else's CoinJoin: equal outputs, one ours, but no input of ours.
        val r = tx(listOf(input("aa".repeat(32), 0), input("bb".repeat(32), 0)),
            listOf(out(30_000, "a2"), out(30_000, "other"), out(4_000, "z")))
        val e = JamLedger.makerEarnings(rows, listOf(TxConf("r", 1, false)), mapOf("r" to r)::get)
        assertEquals(0, e.count); assertEquals(0, e.total)
        // And the deposit alone (no equal outputs, no input of ours) neither.
        assertEquals(0, JamLedger.makerEarnings(rows, listOf(TxConf("dep", 1, false)), all::get).count)
    }

    @Test fun ourTakerCoinJoinIsNotAnEarning() {
        // We are the taker: 100 000 in, 50 000 CoinJoin output + 49 500 change back (paid 500).
        val t = tx(listOf(input("dep", 0), input("cc".repeat(32), 0)),
            listOf(out(50_000, "a1"), out(50_000, "maker1"), out(49_500, "c0"), out(20_100, "maker1change")))
        val e = JamLedger.makerEarnings(rows, listOf(TxConf("t", 2, false), TxConf("dep", 9, false)),
            mapOf("t" to t, "dep" to deposit)::get)
        assertEquals(0, e.count)
    }

    @Test fun aSendOfOursWithTwoEqualOutputsIsNotAnEarning() {
        val s = tx(listOf(input("dep", 0)), listOf(out(40_000, "somebody"), out(40_000, "c0"), out(19_000, "c0")))
        assertEquals(0, JamLedger.makerEarnings(rows, listOf(TxConf("s", 1, false), TxConf("dep", 2, false)),
            mapOf("s" to s, "dep" to deposit)::get).count)
    }

    @Test fun aCoinJoinWithoutAnEqualOutputOfOursIsNotAnEarning() {
        // Our input, more back than in, equal outputs, but none of the equal ones is ours.
        val x = tx(listOf(input("dep", 0)), listOf(out(60_000, "p"), out(60_000, "q"), out(100_100, "c0")))
        assertEquals(0, JamLedger.makerEarnings(rows, listOf(TxConf("x", 1, false), TxConf("dep", 2, false)),
            mapOf("x" to x, "dep" to deposit)::get).count)
    }

    @Test fun anInputFromOurHistoryNotAtHandIsNotGuessed() {
        // The deposit is in our history but was not fetched: we can't tell what we put in.
        val e = JamLedger.makerEarnings(rows, txs, mapOf("mj" to makerJoin)::get)
        assertEquals(0, e.count)
    }

    @Test fun nothingEarnedMeansEmpty() {
        val e = JamLedger.makerEarnings(rows, emptyList()) { null }
        assertEquals(0, e.count); assertEquals(0, e.total); assertNull(e.joins.maxByOrNull { it.earned })
    }

    @Test fun severalMakerCoinJoinsAddUp() {
        // A second one from jar 1: 50 000 in → 20 000 CoinJoin output to jar 2 + 30 040 change (earned 40).
        val mj2 = tx(listOf(input("mj", 0), input("99".repeat(32), 0)),
            listOf(out(20_000, "a2"), out(20_000, "taker2"), out(30_040, "c1"), out(5_000, "taker2change")))
        val e = JamLedger.makerEarnings(rows, listOf(TxConf("mj2", 1, false)) + txs, (all + ("mj2" to mj2))::get)
        assertEquals(2, e.count); assertEquals(290, e.total)
        assertEquals(40, e.forJar(1).total)
    }
}
