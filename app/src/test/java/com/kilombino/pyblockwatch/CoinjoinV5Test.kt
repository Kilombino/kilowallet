package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.coinjoin.CoinjoinPayment
import com.kilombino.pyblockwatch.coinjoin.CoinjoinTx
import com.kilombino.pyblockwatch.coinjoin.NostrEvent
import com.kilombino.pyblockwatch.coinjoin.PerfectCoin
import com.kilombino.pyblockwatch.coinjoin.PoolSession
import com.kilombino.pyblockwatch.coinjoin.Protocol
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import com.kilombino.pyblockwatch.crypto.TxBuilder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger

/** Kilojoin v5: the creator pays recipients of different amounts; joiners get equal outputs of the same sum. */
class CoinjoinV5Test {
    private fun key(n: Long) = BigInteger.valueOf(n)
    private fun pub(k: BigInteger) = Secp256k1.compress(Secp256k1.multiply(k, Secp256k1.G))
    private fun coin(n: Long, value: Long, type: ScriptType = ScriptType.P2WPKH) = CoinjoinTx.Coin("%064x".format(n), 0, value, pub(key(n)), type)
    private fun script(n: Long, type: ScriptType = ScriptType.P2WPKH) = Address.scriptPubKey(pub(key(n)), type)

    private fun announce(np: PoolSession.Companion.NewPool, edit: (JSONObject) -> Unit = {}): Protocol.Terms? {
        val o = np.terms.toJson().also(edit)
        return Protocol.Terms.parse(NostrEvent.sign(BigInteger(np.secret, 16), Protocol.KIND_POOL,
            listOf(listOf("d", np.terms.id), listOf("t", Protocol.TAG)), o.toString()))
    }

    private val pays = listOf(30_000L, 45_000L, 125_000L)    // S = 200 000

    @Test fun termsRoundTripAndValidation() {
        val np = PoolSession.newPool(100_000, 2.0, 5, 1, v3 = true, outputs = 2, payments = pays)
        assertEquals(Protocol.V5, np.terms.version)
        val back = announce(np)!!
        assertEquals(pays, back.payments)
        assertEquals(2, back.outputs); assertEquals(100_000, back.amount); assertEquals(200_000, back.mixTotal)
        assertTrue(back.v5 && back.v3)
        // One output per joiner is fine in v5 (unlike v4).
        val one = PoolSession.newPool(200_000, 2.0, 5, 1, v3 = true, outputs = 1, payments = pays)
        assertEquals(1, announce(one)!!.outputs)
        // Sums that don't match, dust, too many, none, or no payments field: not listed.
        assertNull(announce(np) { it.put("payments", JSONArray(listOf(30_000L, 45_000L, 125_001L))) })
        assertNull(announce(np) { it.put("payments", JSONArray(listOf(200L, 199_800L))) })
        assertNull(announce(np) { it.put("payments", JSONArray(List(6) { 1_000L }).also { _ -> it.put("denomination", 3_000).put("outputs", 2) }) })
        assertNull(announce(np) { it.put("payments", JSONArray()) })
        assertNull(announce(np) { it.remove("payments") })
        assertNull(announce(np) { it.put("outputs", 6) })
        // A wallet before v5 would refuse "v": 5 outright: only 1–4 parse there (checked by the version gate).
        try { PoolSession.newPool(100_000, 2.0, 5, 1, v3 = true, outputs = 2, payments = listOf(1L, 199_999L)); fail() } catch (_: IllegalArgumentException) {}
        try { PoolSession.newPool(100_000, 2.0, 5, 1, v3 = true, outputs = 2, payments = listOf(100_000L)); fail() } catch (_: IllegalArgumentException) {}
        try { PoolSession.newPool(200_000, 2.0, 5, 1, v3 = false, payments = pays); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun theMinimumIsPerPersonNotPerOutput() {
        fun t(amount: Long, outputs: Int) = PoolSession.newPool(amount, 1.0, 5, 1, outputs = outputs, v3 = true).terms
        assertTrue(Protocol.isTest(t(9_000, 1)))
        assertFalse(Protocol.isTest(t(10_000, 1)))
        assertFalse(Protocol.isTest(t(5_000, 2)))       // 2 × 5 000: a real pool
        assertTrue(Protocol.isTest(t(3_000, 3)))        // 9 000 each: still a test
        assertNull(CoinjoinPayment.check(listOf(Address.encode(Secp256k1.compress(Secp256k1.multiply(BigInteger.valueOf(7), Secp256k1.G)), ScriptType.P2WPKH) to 5_000L, Address.encode(Secp256k1.compress(Secp256k1.multiply(BigInteger.valueOf(8), Secp256k1.G)), ScriptType.P2WPKH) to 5_000L), false).reason)
        assertEquals(5_000L, CoinjoinPayment.check(listOf(Address.encode(Secp256k1.compress(Secp256k1.multiply(BigInteger.valueOf(7), Secp256k1.G)), ScriptType.P2WPKH) to 5_000L, Address.encode(Secp256k1.compress(Secp256k1.multiply(BigInteger.valueOf(8), Secp256k1.G)), ScriptType.P2WPKH) to 5_000L), false).amount)
    }

    @Test fun splitPrefersOneOutputAndNeedsAnEvenSplit() {
        assertEquals(200_000L to 1, Protocol.v5Split(200_000))
        assertEquals(100_000L to 2, Protocol.v5Split(200_000, 2))
        assertEquals(200_001L to 1, Protocol.v5Split(200_001, 2))         // not divisible
        assertEquals(7_500L to 2, Protocol.v5Split(15_000, 2))            // the minimum is per person, not per output
        assertEquals(1_500L to 1, Protocol.v5Split(1_500, 2))             // 750 each is under the smallest output
        assertEquals(40_000L to 5, Protocol.v5Split(200_000, 5))
        assertEquals(200_000L to 1, Protocol.v5Split(200_000, 6))
    }

    @Test fun creatorSeatHasOneAddressPerPaymentAndPaysItsOwnFee() {
        val rate = 2.0
        val np = PoolSession.newPool(100_000, rate, 5, 1, v3 = true, outputs = 2, payments = pays)
        // Two addresses for three payments: refused.
        try {
            PoolSession.newState(np.terms, true, np.secret, listOf(coin(1, 500_000)), listOf(key(1)), listOf("a"),
                listOf(script(41), script(42)), script(31), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv); fail()
        } catch (_: IllegalArgumentException) {}
        val st = PoolSession.newState(np.terms, true, np.secret, listOf(coin(1, 500_000)), listOf(key(1)), listOf("a"),
            listOf(script(41), script(42), script(43)), script(31), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv)
        // The creator's share counts its three payment outputs (a joiner's, its two).
        assertEquals(500_000 - 200_000 - CoinjoinTx.feeShare(rate, true, 1, ScriptType.P2WPKH, 3), st.seat.changeValue)
        val joiner = PoolSession.newState(announce(np)!!, false, null, listOf(coin(2, 500_000)), listOf(key(2)), listOf("b"),
            listOf(script(51), script(52)), script(32))
        assertEquals(500_000 - 200_000 - CoinjoinTx.feeShare(rate, true, 1, ScriptType.P2WPKH, 2), joiner.seat.changeValue)
        val back = PoolSession.State.parse(JSONObject(st.toJson().toString()))
        assertEquals(pays, back.terms.payments)
        assertEquals(Protocol.V5, back.terms.version)
        // The creator's perfect coin: the payments plus its own share; a joiner's: outputs × amount plus its share.
        val perfectCreator = pays.sum() + CoinjoinTx.feeShare(rate, false, 1, ScriptType.P2WPKH, 3)
        assertEquals(0L, CoinjoinTx.changeOf(perfectCreator, 200_000, rate, 1, ScriptType.P2WPKH, 3))
        assertEquals(0L, CoinjoinTx.change(CoinjoinTx.minimumCoin(100_000, rate, ScriptType.P2WPKH, 2), 100_000, rate, 1, ScriptType.P2WPKH, 2))
        // Agreed terms for a joiner's perfect coin carry the payments: other payments are another pool.
        val agreed = PerfectCoin.agreed(np.terms)
        assertNull(PerfectCoin.changed(agreed, np.terms))
        assertNotNull(PerfectCoin.changed(agreed, np.terms.copy(payments = listOf(100_000L, 100_000L))))
    }

    /** A round: creator A pays [pays]; joiners B (perfect coin) and C (two coins, change) get [k] outputs each. */
    private inner class Round(val k: Int, val type: ScriptType = ScriptType.P2WPKH) {
        val rate = 3.0
        val d = pays.sum() / k
        val a = coin(1, 400_000, type)
        val b = coin(2, CoinjoinTx.minimumCoin(d, rate, type, k), type)
        val c1 = coin(3, 150_000, type); val c2 = coin(4, 120_000, type)
        val payOuts = pays.mapIndexed { i, v -> TxBuilder.Output(script(60L + i, type), v) }
        val chA = TxBuilder.Output(script(19, type), CoinjoinTx.changeOf(a.value, pays.sum(), rate, 1, type, pays.size)!!)
        val chC = TxBuilder.Output(script(39, type), CoinjoinTx.change(c1.value + c2.value, d, rate, 2, type, k)!!)
        val mixB = (20L until 20L + k).map { script(it, type) }
        val mixC = (30L until 30L + k).map { script(it, type) }
        val seatInputs = listOf(1, 1, 2)
        val seatOutputs = listOf(pays.size, k, k)
        fun plan(payments: List<TxBuilder.Output> = payOuts, mix: List<ByteArray> = mixB + mixC, changes: List<TxBuilder.Output> = listOf(chA, chC)) =
            CoinjoinTx.plan(listOf(a, b, c1, c2), mix, changes, d, k, payments)
        fun check(plan: CoinjoinTx.Plan, mine: List<TxBuilder.Output>, change: TxBuilder.Output?) =
            CoinjoinTx.checkPayment(plan, mine, change, d, rate, seatInputs, seatOutputs, k, pays, 2)
        val mineA get() = payOuts
        val mineB get() = mixB.map { TxBuilder.Output(it, d) }
        val mineC get() = mixC.map { TxBuilder.Output(it, d) }
    }

    @Test fun everyMemberAcceptsAnHonestRound() {
        for (k in listOf(1, 2, 4)) for (type in listOf(ScriptType.P2WPKH, ScriptType.P2TR)) {
            val r = Round(k, type)
            val plan = r.plan()
            assertEquals(2 * k + pays.size + 2, plan.outputs.size)
            assertNull(r.check(plan, r.mineA, r.chA))
            assertNull(r.check(plan, r.mineB, null))
            assertNull(r.check(plan, r.mineC, r.chC))
            // Fee shares balance: what the transaction pays is each seat's share, rounding aside.
            val shares = r.seatInputs.indices.sumOf { i ->
                CoinjoinTx.feeShare(r.rate, i != 1, r.seatInputs[i], type, r.seatOutputs[i])
            }
            assertTrue("fee ${plan.fee} vs shares $shares", plan.fee in shares - 2..shares + CoinjoinTx.DUST)
        }
    }

    @Test fun membersRefuseACreatorThatCheats() {
        val r = Round(2)
        // Swaps a payment's amount for another (same sum: 30 000 + 45 000 → 25 000 + 50 000).
        val swapped = listOf(TxBuilder.Output(r.payOuts[0].scriptPubKey, 25_000), TxBuilder.Output(r.payOuts[1].scriptPubKey, 50_000), r.payOuts[2])
        val p1 = r.plan(swapped)
        assertNotNull(r.check(p1, r.mineB, null)); assertNotNull(r.check(p1, r.mineC, r.chC))
        // Adds a payment (to itself) on top of the announced ones.
        val added = r.payOuts + TxBuilder.Output(script(70), 5_000)
        val p2 = r.plan(added, changes = listOf(TxBuilder.Output(r.chA.scriptPubKey, r.chA.value - 5_000), r.chC))
        assertNotNull(r.check(p2, r.mineB, null))
        // Pays a recipient more by taking it from the change: the amounts are not the announced ones.
        val more = listOf(r.payOuts[0], r.payOuts[1], TxBuilder.Output(r.payOuts[2].scriptPubKey, 135_000))
        assertNotNull(r.check(r.plan(more, changes = listOf(TxBuilder.Output(r.chA.scriptPubKey, r.chA.value - 10_000), r.chC)), r.mineB, null))
        // Underpays a joiner: one of B's outputs replaced by the creator's address.
        val underpaid = r.plan(mix = listOf(r.mixB[0], script(71)) + r.mixC)
        assertNotNull(r.check(underpaid, r.mineB, null))
        // Keeps more change than its share leaves: the fee falls short for everyone.
        val greedy = r.plan(changes = listOf(TxBuilder.Output(r.chA.scriptPubKey, r.chA.value + 600), r.chC))
        assertEquals("the fee is too low", r.check(greedy, r.mineB, null))
        // A payment of another address type can't even make a plan.
        try { r.plan(listOf(r.payOuts[0], r.payOuts[1], TxBuilder.Output(script(72, ScriptType.P2TR), 125_000))); fail() }
        catch (_: IllegalArgumentException) {}
        // Nor a dust payment.
        try { r.plan(listOf(TxBuilder.Output(script(73), 100)) + r.payOuts); fail() } catch (_: IllegalArgumentException) {}
        // The creator itself refuses a plan where one of its payments is missing.
        val missing = r.plan(listOf(r.payOuts[0], r.payOuts[1], TxBuilder.Output(script(74), 125_000)))
        assertNotNull(r.check(missing, r.mineA, r.chA))
    }
}
