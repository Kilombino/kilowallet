package com.kilombino.pyblockwatch.coinjoin

import com.kilombino.pyblockwatch.crypto.ScriptType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The perfect coin joins on its own only the pool the user agreed to. */
class PerfectCoinTermsTest {
    private val seen = Protocol.Terms(id = "p", poolPub = "02aa", amount = 100_000, feeRate = 2.0, maxPeers = 5,
        expiresAt = 0, state = "open", peers = 1, createdAt = 0, version = Protocol.VERSION, script = ScriptType.P2TR, maxInputs = 3)

    @Test fun sameTermsJoin() = assertNull(PerfectCoin.changed(PerfectCoin.agreed(seen), seen.copy(peers = 4, createdAt = 9)))

    @Test fun changedTermsDoNot() {
        val a = PerfectCoin.agreed(seen)
        assertNotNull(PerfectCoin.changed(a, seen.copy(poolPub = "02bb")))
        assertNotNull(PerfectCoin.changed(a, seen.copy(amount = 99_000)))
        assertNotNull(PerfectCoin.changed(a, seen.copy(feeRate = 50.0)))
        assertNotNull(PerfectCoin.changed(a, seen.copy(script = ScriptType.P2WPKH)))
        assertNotNull(PerfectCoin.changed(a, seen.copy(maxInputs = 10)))
        assertNotNull(PerfectCoin.changed(a, seen.copy(state = "closing")))
        assertEquals("its terms were not kept", PerfectCoin.changed(null, seen))
    }

    /** A perfect coin waiting to join as another seat, perpetual: kept as it is, and one saved before 1.3.3-beta2 still reads. */
    @Test fun waitingAsAnotherSeatAndPerpetualRoundTrips() {
        fun b(n: Int) = ByteArray(22) { (n + it).toByte() }
        val w = PerfectCoin.Waiting("ab".repeat(32), 1, 100_209, ScriptType.P2WPKH, "m/84'/0'/0'/0/7", b(1), listOf(b(2)), b(3),
            "pool", null, null, 1000, PerfectCoin.agreed(seen), extraSeat = true, passwordKey = "cd".repeat(32), perpCap = 3.0, mixIndex = 8)
        val back = PerfectCoin.Waiting.parse(org.json.JSONObject(w.toJson().toString()))
        assertEquals(true, back.extraSeat); assertEquals("cd".repeat(32), back.passwordKey)
        assertEquals(3.0, back.perpCap!!, 0.0); assertEquals(8, back.mixIndex)
        // Moved by a speed-up: all of it goes along.
        val moved = back.movedTo("ef".repeat(32), 0)
        assertEquals(true, moved.extraSeat); assertEquals(3.0, moved.perpCap!!, 0.0); assertEquals(8, moved.mixIndex)
        val old = org.json.JSONObject(w.toJson().toString()).apply { remove("extraSeat"); remove("passwordKey"); remove("perpCap"); remove("mixIndex") }
        val o = PerfectCoin.Waiting.parse(old)
        assertEquals(false, o.extraSeat); assertNull(o.passwordKey); assertNull(o.perpCap); assertEquals(-1, o.mixIndex)
        // Not perpetual: no cap.
        assertNull(PerfectCoin.Waiting.parse(org.json.JSONObject(PerfectCoin.Waiting("ab".repeat(32), 1, 1, ScriptType.P2WPKH, "m", b(1),
            listOf(b(2)), b(3), "pool", null, null).toJson().toString())).perpCap)
    }
}
