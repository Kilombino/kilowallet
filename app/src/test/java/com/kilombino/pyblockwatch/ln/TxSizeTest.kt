package com.kilombino.pyblockwatch.ln

import org.junit.Assert.assertEquals
import org.junit.Test

class TxSizeTest {
    // A real cooperative close on the BLAKE2b chain (14ef787a…): 192.25 vB, 242 sats of fee.
    private val close = "02000000000101c848cb45ea42f01a6749a983a8abc698d1c8502d72fb35c0ce901f7b6fa90b450000000000ffffffff026aa7050000000000225120c1ba710fe249e2d9a19e352335fba39c58d43668a78bccb53ff3e1a91467ad36322a2b00000000002251200e2ccf01bca409e694e8747d1bf4213ebc8fcafc7c8df8f08eb77e5792fab55e0400483045022100e61be6c3a71b34ec88fe5f0906d3218d63bc83f6e13bc84c28bb5b7cffefa1f402200a864811f8813fa8565a2aab8c76340fd0b468ffce1fe215d2db36c14d68e8db21473044022055140ea4d15a8291749fcb195bc4cd230333a497f000cfd857cb29e8656fa946022052e2ee7b89f61d0604d389f381bfcb406596265e70f6a869b7862491960eebe92147522102b03bc48ff0858f9cb3bf037c72d54c67a55ca1030d7813da9869fb9fe74a5708210323a6fd696657efee78fbe20f5f9c7dc641d397db32f5dc43ce52d499f3a3c7a952ae00000000"

    @Test fun readsSizeAndOutputs() {
        val t = TxSize.of(close)
        assertEquals(193L, t.vsize)
        assertEquals(370_538L + 2_828_850L, t.outputs)
    }

    @Test fun childLiftsThePair() {
        // 193 vB paying 242 sats; the pair at 3 sat/vB needs 912 sats: the child pays 670.
        val r = cpfpChildRate(3, 193, 242, CPFP_CHILD_VSIZE)
        assertEquals(7L, r)
        assert(r * CPFP_CHILD_VSIZE + 242 >= 3 * (193 + CPFP_CHILD_VSIZE))
        // A parent that already pays enough: the child still pays the target.
        assertEquals(2L, cpfpChildRate(2, 193, 5_000, CPFP_CHILD_VSIZE))
    }
}
