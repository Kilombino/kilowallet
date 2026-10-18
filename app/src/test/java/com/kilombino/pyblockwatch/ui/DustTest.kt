package com.kilombino.pyblockwatch.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Bitcoin Core's dust limits at 3 sat/vB, by output type. */
class DustTest {
    private fun s(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    @Test fun limits() {
        assertEquals(294L, dustFor(s(0x00, 0x14) + ByteArray(20)))   // bc1q
        assertEquals(330L, dustFor(s(0x51, 0x20) + ByteArray(32)))   // bc1p
        assertEquals(330L, dustFor(s(0x00, 0x20) + ByteArray(32)))   // P2WSH
        assertEquals(540L, dustFor(s(0xa9, 0x14) + ByteArray(20) + s(0x87))) // P2SH
        assertEquals(546L, dustFor(s(0x76, 0xa9, 0x14) + ByteArray(20) + s(0x88, 0xac))) // P2PKH
    }
}
