package com.kilombino.pyblockwatch.ln

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeTest {
    // The bridge's first real swap (14 Oct 2026): 1,000 sats SHA-256 for 121,662,007 msat BTC.
    private val hash = "a011e2e5304b8328936821bd285493dea2f696beba478254a72b0022943b0769"
    private val preimage = "6fc6cd4c83433ab0a6d0bfb68365ea30da87584702004822212da6b31d1bbb5f"
    private val now = 1_791_978_000L

    private val sha = Lnd.PayReq("02cd566293ad8c8fd55bf5c5be38df5fc0af2b445333b10cbbd8b8a7d4c7ed7fe3", hash, 1000, "",
        86400, now - 600, blake2b = false)
    private val hold = Lnd.PayReq(Bridge.NODE, hash, 121_662, "", 600, now - 5, blake2b = true, msat = 121_662_007)
    private val quote = Bridge.Quote("lnbc…", hash, 1000, 121_663, now + 115)
    private val accepted = 124_000L

    private fun check(h: Lnd.PayReq = hold, q: Bridge.Quote = quote, max: Long = accepted, at: Long = now) =
        Bridge.mismatch(h, h.msat, sha, q, max, at)

    @Test fun theInvoiceItAgreedToIsPaid() = assertNull(check())

    @Test fun anotherHashIsRefused() {
        assertNotNull(check(h = hold.copy(hash = "00".repeat(32))))
        assertNotNull(check(q = Bridge.Quote("lnbc…", "11".repeat(32), 1000, 121_663, now + 115)))
    }

    @Test fun anotherNodeIsRefused() = assertNotNull(check(h = hold.copy(destination = "03" + "00".repeat(32))))

    @Test fun aTimelockLongerThanTwoWeeksIsRefused() {
        assertNull(check(h = hold.copy(cltv = 1396)))     // what the bridge asks for
        assertNull(check(h = hold.copy(cltv = 2016)))
        assertNotNull(check(h = hold.copy(cltv = 2017)))
    }

    @Test fun anInvoiceOfTheOtherChainIsRefused() = assertNotNull(check(h = hold.copy(blake2b = false)))

    @Test fun moreThanAcceptedIsRefused() {
        assertNotNull(check(max = 121_662))           // 121,662,007 msat rounds up to 121,663 sats
        assertNull(check(max = 121_663))
        assertNotNull(check(h = hold.copy(msat = 0)))
    }

    @Test fun tooLittleTimeLeftIsRefused() {
        assertNotNull(check(at = quote.expiresAt - 10))
        assertNotNull(check(h = hold.copy(timestamp = now - 590)))
    }

    @Test fun theProofIsTheInvoicesSecret() {
        assertTrue(Bridge.proves(preimage, hash))
        assertTrue(Bridge.proves(preimage, hash.uppercase()))
        assertFalse(Bridge.proves("00".repeat(32), hash))
        assertFalse(Bridge.proves("zz", hash))
    }

    @Test fun theMostItAgreesToIsTheEstimatePlusTheTolerance() {
        val e = Bridge.Estimate(1000, 118_493, 0.008622, 0.0216, 0)
        assertEquals(Math.ceil(118_493 * 1.02).toLong(), e.maxPaySat)
        assertEquals(1L, Bridge.ceilSat(1))
        assertEquals(121_663L, Bridge.ceilSat(121_662_007))
    }
}
