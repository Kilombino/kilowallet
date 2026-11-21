package com.kilombino.pyblockwatch.roblo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F3: what the client may ask of the wallet, and the checks before anything is signed. */
class RobloWalletTest {
    private val addr = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
    @After fun clean() { RobloWallet.request.value = null; RobloWallet.labRegtest = false; RobloWallet.answer = null }

    @Test fun requestsAreChecked() {
        assertNull(RobloWallet.checkPay(addr, 3330, "kilobot/24"))
        assertNotNull(RobloWallet.checkPay(addr, 0, "kilobot/24"))
        assertNotNull(RobloWallet.checkPay(addr, RobloWallet.MAX_SATS + 1, "kilobot/24"))
        assertNotNull(RobloWallet.checkPay("bc1 q", 3330, "kilobot/24"))
        assertNotNull(RobloWallet.checkPay(addr, 3330, "kilobot/24/x"))
        assertNull(RobloWallet.checkInvoice("lnbcrt15u1p4sp076pp5raj0z2qlljzxg7x8hq", "kilobot/24"))
        assertNotNull(RobloWallet.checkInvoice("bitcoin:bc1q...", "kilobot/24"))
        assertNull(RobloWallet.checkAddress("payout", "kilobot/24"))
        assertNotNull(RobloWallet.checkAddress("anything", "kilobot/24"))
    }

    /** The sheet signs only a draft paying exactly the coordinator's address and amount, to one recipient. */
    @Test fun theDraftMustBeThePaymentAsked() {
        val r = RobloWallet.Request.Pay("u", addr, 3330, "kilobot/24", 1.2)
        assertTrue(RobloWallet.draftMatches(r, addr, 3330, 0))
        assertTrue(RobloWallet.draftMatches(r, addr.uppercase(), 3330, 0))
        assertFalse(RobloWallet.draftMatches(r, addr, 3331, 0))
        assertFalse(RobloWallet.draftMatches(r, "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", 3330, 0))
        assertFalse(RobloWallet.draftMatches(r, addr, 3330, 1))
    }

    @Test fun oneRequestAtATimeAndEachAnsweredOnce() {
        val answers = ArrayList<Triple<String, Boolean, String>>()
        RobloWallet.answer = { u, ok, v -> answers += Triple(u, ok, v) }
        val a = RobloWallet.Request.Address("u1", "payout", "kilobot/24")
        assertTrue(RobloWallet.offer(a))
        assertFalse(RobloWallet.offer(RobloWallet.Request.Invoice("u2", 1000, "m")))
        RobloWallet.finish(a, true, addr)
        assertNull(RobloWallet.request.value)
        assertEquals(listOf(Triple("u1", true, addr)), answers)
        assertTrue(RobloWallet.offer(RobloWallet.Request.Invoice("u2", 1000, "m")))
    }

    /** Regtest lab only: the same witness program spelled bcrt1 for the lab coordinator. */
    @Test fun labSpelling() {
        val bcrt = "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"
        assertEquals(bcrt, RobloWallet.labMainnet(bcrt)) // lab off: untouched
        RobloWallet.labRegtest = true
        assertEquals(addr, RobloWallet.labMainnet(bcrt))
        assertEquals(bcrt, RobloWallet.labOut(addr))
        val r = RobloWallet.Request.Pay("u", bcrt, 3330, "kilobot/24", 1.2)
        assertTrue(RobloWallet.draftMatches(r, addr, 3330, 0))
    }
}
