package com.kilombino.pyblockwatch.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The difficulty below the checkpoint, as Kilombino's node has it. */
class PeriodBitsTest {
    @Test fun knownPeriods() {
        assertEquals(0x1d00ffffL, HeaderChain.periodBits(0))
        assertEquals(0x1d00ffffL, HeaderChain.periodBits(32_255))
        // The last SHA256d blocks share the period of the activation, at the old difficulty.
        assertEquals(0x1702353dL, HeaderChain.periodBits(961_639))
        // From the activation on: the cut difficulty, then each BLAKE2b period.
        assertEquals(0x1a008d4fL, HeaderChain.periodBits(961_640))
        assertEquals(0x1a008d4fL, HeaderChain.periodBits(963_647))
        assertEquals(0x1a00f0b5L, HeaderChain.periodBits(963_648))
        assertEquals(0x193c2d40L, HeaderChain.periodBits(967_679))
        assertEquals(0x1900edbaL, HeaderChain.periodBits(975_743))
        assertNull(HeaderChain.periodBits(HeaderChain.CHECKPOINT_HEIGHT))
    }
}
