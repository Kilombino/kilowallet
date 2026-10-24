package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.coinjoin.CoinjoinAlerts
import com.kilombino.pyblockwatch.coinjoin.CoinjoinPayment
import com.kilombino.pyblockwatch.coinjoin.CoinjoinTx
import com.kilombino.pyblockwatch.coinjoin.Protocol
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import com.kilombino.pyblockwatch.crypto.TxBuilder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** 1.2.7: a pool opened from whole coins (no change), paying through a pool from SEND, and the alerts' queue. */
class CoinjoinPayTest {
    private fun pub(n: Long) = Secp256k1.compress(Secp256k1.multiply(BigInteger.valueOf(n), Secp256k1.G))
    private fun addr(n: Long, type: ScriptType) = Address.encode(pub(n), type)

    @Test fun amountFromCoinsLeavesNoChange() {
        for (type in listOf(ScriptType.P2WPKH, ScriptType.P2TR)) for (rate in listOf(1.0, 1.5, 7.0, 33.3)) for (n in 1..Protocol.MAX_INPUTS)
            for (outputs in 1..Protocol.MAX_OUTPUTS) for (total in listOf(123_457L, 1_000_000L, 5_432_109L)) {
                val amount = CoinjoinTx.noChangeAmount(total, n, rate, type, outputs)!!
                val fee = CoinjoinTx.feeShare(rate, false, n, type, outputs)
                assertEquals((total - fee) / outputs, amount)
                // What rounding leaves goes to the miners, and is less than one sat per output.
                val rounding = total - fee - amount * outputs
                assertTrue(rounding in 0 until outputs)
                // The round itself (and the creator's check of the seat) sees no change at all.
                assertEquals(0L, CoinjoinTx.change(total, amount, rate, n, type, outputs))
                // One sat more per output and the coins no longer cover it.
                assertNull(CoinjoinTx.change(total, amount + 1, rate, n, type, outputs))
            }
        assertNull(CoinjoinTx.noChangeAmount(100, 1, 5.0))
    }

    @Test fun roundingToTheMinersPassesTheFeeCheck() {
        val rate = 3.0; val outputs = 2; val type = ScriptType.P2WPKH
        // The creator's two coins make an amount with rounding; the other joins with a coin of exactly that.
        val mine = listOf(CoinjoinTx.Coin("%064x".format(1), 0, 700_002, pub(1)), CoinjoinTx.Coin("%064x".format(2), 1, 333_333, pub(2)))
        val total = mine.sumOf { it.value }
        val amount = CoinjoinTx.noChangeAmount(total, 2, rate, type, outputs)!!
        assertTrue(total - CoinjoinTx.feeShare(rate, false, 2, type, outputs) - amount * outputs > 0)
        val theirs = CoinjoinTx.Coin("%064x".format(3), 0, CoinjoinTx.minimumCoin(amount, rate, type, outputs), pub(3))
        val mineMix = listOf(Address.scriptPubKey(pub(11), type), Address.scriptPubKey(pub(12), type))
        val theirMix = listOf(Address.scriptPubKey(pub(13), type), Address.scriptPubKey(pub(14), type))
        val plan = CoinjoinTx.plan(mine + theirs, mineMix + theirMix, emptyList<TxBuilder.Output>(), amount, outputs)
        assertNull(CoinjoinTx.checkOurs(plan, mineMix, null, amount, rate, listOf(2, 1), outputs))
        assertNull(CoinjoinTx.checkOurs(plan, theirMix, null, amount, rate, listOf(2, 1), outputs))
    }

    @Test fun payingThroughAPoolNeedsEqualAmountsAndOneType() {
        val q1 = addr(21, ScriptType.P2WPKH); val q2 = addr(22, ScriptType.P2WPKH); val q3 = addr(23, ScriptType.P2WPKH)
        val p1 = addr(31, ScriptType.P2TR); val p2 = addr(32, ScriptType.P2TR)
        CoinjoinPayment.check(listOf(q1 to 50_000L, q2 to 50_000L, q3 to 50_000L), taprootOk = false).let {
            assertTrue(it.ok); assertEquals(ScriptType.P2WPKH, it.type); assertEquals(50_000L, it.amount); assertEquals(3, it.scripts.size)
            assertTrue(it.scripts[0].contentEquals(Address.decodeToScriptPubKey(q1)))
        }
        assertEquals(ScriptType.P2TR, CoinjoinPayment.check(listOf(p1 to 20_000L, p2 to 20_000L), taprootOk = true).type)
        assertEquals(ScriptType.P2WPKH, CoinjoinPayment.check(listOf(q1 to 10_000L), taprootOk = false).type)
        // Different amounts, or bc1q and bc1p together: not one pool.
        assertEquals(CoinjoinPayment.NEEDS, CoinjoinPayment.check(listOf(q1 to 50_000L, q2 to 50_001L), true).reason)
        assertEquals(CoinjoinPayment.NEEDS, CoinjoinPayment.check(listOf(q1 to 50_000L, p1 to 50_000L), true).reason)
        // Neither bc1q nor bc1p (a legacy address), a handle, a silent payment.
        val legacy = "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"
        assertEquals(CoinjoinPayment.NEEDS, CoinjoinPayment.check(listOf(legacy to 50_000L), true).reason)
        assertFalse(CoinjoinPayment.check(listOf("someone@kilombino.com" to 50_000L), true).ok)
        assertFalse(CoinjoinPayment.check(listOf("garbage" to 50_000L), true).ok)
        // Taproot needs Taproot pools on; no address twice; the pool's limits; at most five recipients.
        assertFalse(CoinjoinPayment.check(listOf(p1 to 20_000L), taprootOk = false).ok)
        assertFalse(CoinjoinPayment.check(listOf(q1 to 20_000L, q1 to 20_000L), true).ok)
        assertFalse(CoinjoinPayment.check(listOf(q1 to 9_999L), true).ok)
        assertTrue(CoinjoinPayment.check(listOf(q1 to 9_999L), true, minAmount = Protocol.TEST_MIN_AMOUNT).ok)
        assertFalse(CoinjoinPayment.check(listOf(q1 to Protocol.MAX_AMOUNT + 1), true).ok)
        val six = (40L..45L).map { addr(it, ScriptType.P2WPKH) to 30_000L }
        assertFalse(CoinjoinPayment.check(six, true).ok)
        assertTrue(CoinjoinPayment.check(six.take(5), true).ok)
        assertFalse(CoinjoinPayment.check(emptyList(), true).ok)
        // The recipients' addresses come back as typed.
        assertEquals(q1, CoinjoinPayment.address(Address.decodeToScriptPubKey(q1)))
        assertEquals(p1, CoinjoinPayment.address(Address.decodeToScriptPubKey(p1)))
    }

    @Test fun alertsQueueIsBoundedAndForgetsOldOnes() {
        val now = 10L * CoinjoinAlerts.MAX_AGE_MS
        var q = JSONArray()
        for (i in 0 until 30) q = CoinjoinAlerts.added(q, JSONObject().put("path", "/event").put("at", now - 1000 + i), now)
        assertEquals(CoinjoinAlerts.QUEUE_MAX, q.length())
        assertEquals(now - 1000 + 10, q.getJSONObject(0).getLong("at"))
        // A day old is dropped when the next one comes in.
        q = CoinjoinAlerts.added(JSONArray().put(JSONObject().put("at", now - CoinjoinAlerts.MAX_AGE_MS)), JSONObject().put("at", now), now)
        assertEquals(1, q.length())
        assertEquals(30_000L, CoinjoinAlerts.backoffMs(0))
        assertEquals(60_000L, CoinjoinAlerts.backoffMs(1))
        assertEquals(30 * 60_000L, CoinjoinAlerts.backoffMs(50))
    }
}
