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
}
