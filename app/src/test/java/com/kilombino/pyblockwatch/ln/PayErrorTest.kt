package com.kilombino.pyblockwatch.ln

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PayErrorTest {
    @Test fun aPaymentStillOnItsWaySaysSo() {
        // What LND answers when the same invoice is paid again while the first try is in flight.
        val e = LndError("rpc error: code = AlreadyExists desc = payment is in transition")
        val text = Lnd.payError(e)
        assertTrue(text, text.startsWith("This invoice is already being paid"))
        assertTrue(text, "come back to your channel" in text)
    }

    @Test fun otherFailuresKeepTheNodesWords() {
        assertEquals("Not paid: no route to the destination with enough liquidity",
            Lnd.payError(LndError("no route to the destination with enough liquidity")))
    }

    @Test fun aSha256InvoiceStillPointsToTheBridge() {
        assertEquals(Lnd.SHA256_INVOICE, Lnd.payError(LndError("invoice lacks option_blake2b")))
    }

    @Test fun anOffersFailureDoesNotDumpTheInvoice() {
        val e = LndError("rpc error: code = Aborted desc = payment failed: no_route; payment hash 411f6ffe; " +
            "retry with this invoice, not the offer: lni1qqgq0n9nvykvkfl048czreff0zewgkq3qdl3gcz4k7xeh9sdx5")
        assertEquals("Not paid: no route to the destination with enough liquidity", Lnd.payError(e))
        val other = Lnd.payError(LndError("payment failed: something; retry with this invoice, not the offer: lni1abc"))
        assertTrue(other, "lni1" !in other)
    }
}
