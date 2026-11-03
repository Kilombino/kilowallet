package com.kilombino.pyblockwatch.coinjoin

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** Kilojoin v6 (asynchronous rounds): the announcement, its bounds, what a join binds, and what a round keeps on disk. */
class AsyncRoundsTest {
    private fun pub(n: Long) = Secp256k1.compress(Secp256k1.multiply(BigInteger.valueOf(n), Secp256k1.G))
    private fun script(n: Long) = Address.scriptPubKey(pub(n), ScriptType.P2WPKH)

    private fun announce(np: PoolSession.Companion.NewPool, edit: (JSONObject) -> Unit = {}): Protocol.Terms? {
        val o = np.terms.toJson().also(edit)
        val ev = NostrEvent.sign(BigInteger(np.secret, 16), Protocol.KIND_POOL, listOf(listOf("d", np.terms.id), listOf("t", Protocol.TAG)), o.toString())
        return Protocol.Terms.parse(ev)
    }

    @Test fun anAsyncPoolIsV6WithItsWindows() {
        val np = PoolSession.newPool(10_000, 1.0, 5, 2, v3 = true, async = true)
        val t = announce(np)!!
        assertEquals(Protocol.V6, t.version); assertTrue(t.v6); assertFalse("no payments: not a payment pool", t.v5)
        assertEquals(np.terms.async, t.async)
        assertEquals(3600L, t.voteSeconds); assertEquals(1800L, t.outputsSeconds); assertEquals(7200L, t.signSeconds)
        assertTrue(t.async!!.final > t.expiresAt)
        // Shorter windows, as the creator chooses them.
        val short = PoolSession.newPool(10_000, 1.0, 5, 2, v3 = true, async = true, asyncVote = 900, asyncOutputs = 600, asyncSign = 1800)
        assertEquals(900L, announce(short)!!.voteSeconds)
        // v6 with several outputs each, and with payments (as v4 and v5).
        assertEquals(3, announce(PoolSession.newPool(10_000, 1.0, 5, 2, v3 = true, outputs = 3, async = true))!!.outputs)
        val pay = announce(PoolSession.newPool(10_000, 1.0, 5, 2, v3 = true, outputs = 2, payments = listOf(7_000L, 13_000L), async = true))!!
        assertTrue(pay.v5 && pay.v6); assertEquals(listOf(7_000L, 13_000L), pay.payments)
    }

    @Test fun windowsOutOfBoundsAreNotListed() {
        val np = PoolSession.newPool(10_000, 1.0, 5, 2, v3 = true, async = true)
        fun with(k: String, v: Long) = announce(np) { it.getJSONObject("async").put(k, v) }
        assertNull(with("vote", 3601)); assertNull(with("vote", 599))
        assertNull(with("outputs", 1801)); assertNull(with("outputs", 299))
        assertNull(with("sign", 7201)); assertNull(with("sign", 1199))
        assertNull(with("final", np.terms.expiresAt)); assertNull(with("final", np.terms.expiresAt + Protocol.ASYNC_TAIL_MAX + 1))
        assertNotNull(with("final", np.terms.expiresAt + Protocol.ASYNC_TAIL_MAX))
        assertNull("a v6 pool without its timing", announce(np) { it.remove("async") })
        assertNull("unbalanced payments", announce(np) { it.put("payments", org.json.JSONArray(listOf(5_000L))) })
        // And newPool refuses to make one.
        assertTrue(runCatching { PoolSession.newPool(10_000, 1.0, 5, 2, v3 = true, async = true, asyncSign = 60) }.isFailure)
        assertTrue(runCatching { PoolSession.newPool(10_000, 1.0, 5, 2, async = true) }.isFailure)
    }

    /** What older wallets see: `v` 6, a version none of them accepts (they take 1 to 5 only), so they don't list it. */
    @Test fun olderWalletsSeeAnUnknownVersion() {
        val np = PoolSession.newPool(10_000, 1.0, 5, 2, v3 = true, async = true)
        assertEquals(6, np.terms.toJson().getInt("v"))
        // A v5 pool is announced exactly as before: no new field.
        val v5 = PoolSession.newPool(10_000, 1.0, 5, 2, v3 = true, outputs = 2, payments = listOf(7_000L, 13_000L)).terms.toJson()
        assertEquals(5, v5.getInt("v")); assertFalse(v5.has("async"))
        assertEquals(setOf("v", "app", "network", "id", "public_key", "denomination", "fee_rate", "min_peers", "max_peers", "private",
            "timeout", "state", "peers", "script", "max_inputs", "creator", "blind_key", "outputs", "payments"), v5.keys().asSequence().toSet())
        assertEquals(3, PoolSession.newPool(10_000, 1.0, 5, 2, v3 = true).terms.version)
    }

    @Test fun aJoinBindsEveryTerm() {
        val np = PoolSession.newPool(10_000, 1.0, 5, 2, v3 = true, async = true)
        val t = np.terms
        val h = Protocol.termsHash(t)
        assertEquals(64, h.length); assertEquals(h, Protocol.termsHash(announce(np)!!))
        assertNotEquals(h, Protocol.termsHash(t.copy(async = t.async!!.copy(sign = 3600))))
        assertNotEquals(h, Protocol.termsHash(t.copy(async = t.async!!.copy(final = t.async!!.final + 1))))
        assertNotEquals(h, Protocol.termsHash(t.copy(feeRate = 1.001)))
        // State and peer counts change while the pool lives: they are not terms.
        assertEquals(h, Protocol.termsHash(t.copy(state = "closing", peers = 4)))
    }

    @Test fun aRoundKeepsItsV6StateOnDisk() {
        val np = PoolSession.newPool(10_000, 1.0, 5, 2, v3 = true, async = true)
        val st = PoolSession.newState(np.terms, true, np.secret, listOf(CoinjoinTx.Coin("ab".repeat(32), 1, 30_000, pub(5))),
            listOf(BigInteger.valueOf(5)), listOf("m/0"), listOf(script(6)), script(7), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv)
        st.voteSeq = 3; st.voteN = 2; st.voteEndedN = 1; st.voteAcked = "aa"; st.votesSent = 2; st.usedVotes += listOf("aa", "bb")
        st.revotes = 1; st.lastMsgs["roster"] = JSONObject().put("at", 5).put("msg", JSONObject().put("type", "roster")).toString()
        st.stateN = 7; st.signedAt = 1234
        val back = PoolSession.State.parse(JSONObject(st.toJson().toString()))
        assertEquals(np.terms.async, back.terms.async); assertEquals(Protocol.V6, back.terms.version)
        assertEquals(listOf(3, 2, 1, 2, 1, 7), listOf(back.voteSeq, back.voteN, back.voteEndedN, back.votesSent, back.revotes, back.stateN))
        assertEquals("aa", back.voteAcked); assertEquals(setOf("aa", "bb"), back.usedVotes); assertEquals(1234L, back.signedAt)
        assertEquals(st.lastMsgs, back.lastMsgs)
        assertEquals(st.toJson().toString(), back.toJson().toString())
        // A v5 round's saved form has none of it.
        val v5 = PoolSession.newPool(10_000, 1.0, 5, 2, v3 = true)
        val s5 = PoolSession.newState(v5.terms, true, v5.secret, listOf(CoinjoinTx.Coin("ab".repeat(32), 1, 30_000, pub(5))),
            listOf(BigInteger.valueOf(5)), listOf("m/0"), listOf(script(6)), script(7), creatorSecret = v5.creatorSecret, blindPriv = v5.blindPriv)
        assertFalse(s5.toJson().has("vote_n"))
    }
}
