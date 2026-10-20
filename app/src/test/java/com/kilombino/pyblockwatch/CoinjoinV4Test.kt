package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.coinjoin.CoinjoinTx
import com.kilombino.pyblockwatch.coinjoin.NostrEvent
import com.kilombino.pyblockwatch.coinjoin.PoolSession
import com.kilombino.pyblockwatch.coinjoin.Protocol
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import com.kilombino.pyblockwatch.crypto.TxBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger

/** Kilojoin v4: several mixed outputs per person, its terms, fees and the transaction checks. */
class CoinjoinV4Test {
    private fun key(n: Long) = BigInteger.valueOf(n)
    private fun pub(k: BigInteger) = Secp256k1.compress(Secp256k1.multiply(k, Secp256k1.G))
    private fun coin(n: Long, value: Long) = CoinjoinTx.Coin("%064x".format(n), 0, value, pub(key(n)))
    private fun script(n: Long) = Address.scriptPubKey(pub(key(n)), ScriptType.P2WPKH)

    private fun announce(np: PoolSession.Companion.NewPool, edit: (JSONObject) -> Unit = {}): Protocol.Terms? {
        val o = np.terms.toJson().also(edit)
        return Protocol.Terms.parse(NostrEvent.sign(BigInteger(np.secret, 16), Protocol.KIND_POOL,
            listOf(listOf("d", np.terms.id), listOf("t", Protocol.TAG)), o.toString()))
    }

    @Test fun oneOutputStaysV3SeveralAreV4() {
        val one = PoolSession.newPool(1_000_000, 1.0, 5, 1, v3 = true)
        assertEquals(Protocol.V3, one.terms.version)
        assertEquals(1, one.terms.outputs)
        // A v3 announcement has no "outputs" field at all: older v3 wallets read it as always.
        assertTrue(!one.terms.toJson().has("outputs"))
        val four = PoolSession.newPool(1_000_000, 1.0, 5, 1, v3 = true, outputs = 4)
        assertEquals(Protocol.V4, four.terms.version)
        val back = announce(four)!!
        assertEquals(4, back.outputs)
        assertEquals(4_000_000, back.mixTotal)
        assertTrue(back.v3) // everything of v3 holds in v4
        // v4 with one output, or more than five, or none at all, is not listed.
        assertNull(announce(four) { it.put("outputs", 1) })
        assertNull(announce(four) { it.put("outputs", 6) })
        assertNull(announce(four) { it.remove("outputs") })
        try { PoolSession.newPool(1_000_000, 1.0, 5, 1, v3 = false, outputs = 2); fail() } catch (_: IllegalArgumentException) {}
        try { PoolSession.newPool(1_000_000, 1.0, 5, 1, v3 = true, outputs = 6); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun perfectCoinIsOutputsTimesAmountPlusFee() {
        // 4 × 1 000 000 at 1 sat/vB, native SegWit: 68 in + 4 × 31 out + 5.25 = 197.25 → 198.
        assertEquals(198, CoinjoinTx.feeShare(1.0, false, 1, ScriptType.P2WPKH, 4))
        assertEquals(4_000_198, CoinjoinTx.minimumCoin(1_000_000, 1.0, ScriptType.P2WPKH, 4))
        assertEquals(0L, CoinjoinTx.change(4_000_198, 1_000_000, 1.0, 1, ScriptType.P2WPKH, 4))
        assertNull(CoinjoinTx.change(4_000_197, 1_000_000, 1.0, 1, ScriptType.P2WPKH, 4))
        // With change: one more output to pay for.
        assertEquals(5_000_000 - 4_000_000 - 229L, CoinjoinTx.change(5_000_000, 1_000_000, 1.0, 1, ScriptType.P2WPKH, 4))
        // One output is exactly v3.
        assertEquals(CoinjoinTx.minimumCoin(1_000_000, 1.0), CoinjoinTx.minimumCoin(1_000_000, 1.0, ScriptType.P2WPKH, 1))
    }

    @Test fun seatNeedsOneAddressPerOutput() {
        val np = PoolSession.newPool(100_000, 2.0, 5, 1, v3 = true, outputs = 3)
        val c = coin(11, 400_000)
        try {
            PoolSession.newState(np.terms, true, np.secret, listOf(c), listOf(key(11)), listOf("a"), listOf(script(41)), script(31),
                creatorSecret = np.creatorSecret, blindPriv = np.blindPriv); fail()
        } catch (_: IllegalArgumentException) {}
        try {
            PoolSession.newState(np.terms, true, np.secret, listOf(c), listOf(key(11)), listOf("a"), listOf(script(41), script(41), script(42)),
                script(31), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv); fail()
        } catch (_: IllegalArgumentException) {}
        val st = PoolSession.newState(np.terms, true, np.secret, listOf(c), listOf(key(11)), listOf("a"),
            listOf(script(41), script(42), script(43)), script(31), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv)
        assertEquals(400_000 - 300_000 - CoinjoinTx.feeShare(2.0, true, 1, ScriptType.P2WPKH, 3), st.seat.changeValue)
        val back = PoolSession.State.parse(JSONObject(st.toJson().toString()))
        assertEquals(3, back.mixScripts.size)
        assertEquals(3, back.terms.outputs)
        assertTrue(back.mixScripts.zip(st.mixScripts).all { (a, b) -> a.contentEquals(b) })
    }

    @Test fun oldSavedRoundStillLoads() {
        // A v3 round saved by 1.1.x: single blinding fields, one mix script.
        val np = PoolSession.newPool(100_000, 2.0, 5, 1, v3 = true)
        val st = PoolSession.newState(np.terms, false, null, coin(11, 150_000), key(11), "a", script(41), script(31))
        val o = JSONObject(st.toJson().toString())
        listOf("mix_scripts", "blind_prefixes", "blind_invs", "blinded_msgs", "out_sigs", "handed_in").forEach { o.remove(it) }
        o.put("blind_prefix", "ab").put("blind_inv", "cd").put("blinded_msg", "ef").put("out_sig", "")
        val back = PoolSession.State.parse(o)
        assertEquals(1, back.mixScripts.size)
        assertEquals(listOf("ab"), back.blindPrefixes)
        assertEquals(listOf("ef"), back.blindedMsgs)
        assertTrue(back.outSigs.isEmpty())
    }

    @Test fun planAndChecksWithFourOutputsEach() {
        val rate = 2.0
        val type = ScriptType.P2WPKH
        val a = coin(1, CoinjoinTx.minimumCoin(100_000, rate, type, 4))          // perfect coin: no change
        val b = coin(2, 1_000_000)
        val c1 = coin(3, 250_000); val c2 = coin(4, 200_000)                       // two coins, one seat
        val mixA = (10L..13L).map { script(it) }; val mixB = (20L..23L).map { script(it) }; val mixC = (30L..33L).map { script(it) }
        val chB = TxBuilder.Output(script(29), CoinjoinTx.change(b.value, 100_000, rate, 1, type, 4)!!)
        val chC = TxBuilder.Output(script(39), CoinjoinTx.change(c1.value + c2.value, 100_000, rate, 2, type, 4)!!)
        val plan = CoinjoinTx.plan(listOf(a, b, c1, c2), (mixA + mixB + mixC).shuffled(), listOf(chB, chC), 100_000, 4)
        assertEquals(14, plan.outputs.size)
        assertEquals(12, plan.outputs.count { it.value == 100_000L })
        val seats = listOf(1, 1, 2)
        assertNull(CoinjoinTx.checkOurs(plan, mixA, null, 100_000, rate, seats, 4))
        assertNull(CoinjoinTx.checkOurs(plan, mixB, chB, 100_000, rate, seats, 4))
        assertNull(CoinjoinTx.checkOurs(plan, mixC, chC, 100_000, rate, seats, 4))
        // One of ours missing (someone else's in its place) is refused.
        assertNotNull(CoinjoinTx.checkOurs(plan, mixA.take(3) + script(99), null, 100_000, rate, seats, 4))
        // So is a round with one output short for somebody.
        try { CoinjoinTx.plan(listOf(a, b, c1, c2), mixA + mixB + mixC.take(3), listOf(chB, chC), 100_000, 4); fail() }
        catch (_: IllegalArgumentException) {}
        // The fee covers every output at the pool's rate.
        val vb = 10.5 + 68.0 * 4 + 31.0 * 14
        assertTrue(plan.fee >= kotlin.math.floor(rate * vb * 0.95))
    }
}
