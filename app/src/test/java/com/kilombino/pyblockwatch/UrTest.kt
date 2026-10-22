package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.crypto.Ur
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The animated-QR codec against SeedSigner's own (Foundation Devices' UR port): ur_bond_psbt.json
 * holds the first 30 parts SeedSigner's UREncoder makes for a bond PSBT at 60-byte fragments.
 */
class UrTest {
    private val vector = JSONObject(javaClass.classLoader!!.getResource("ur_bond_psbt.json")!!.readText())
    private val psbt = java.util.Base64.getDecoder().decode(vector.getString("psbt"))
    private val parts = vector.getJSONArray("parts").let { a -> (0 until a.length()).map { a.getString(it) } }

    @Test
    fun encoderMakesSeedSignersPartsExactly() {
        val enc = Ur.psbtEncoder(psbt, vector.getInt("maxFragment"))
        assertEquals(parts, List(parts.size) { enc.nextPart() })
    }

    @Test
    fun decodesThePureParts() {
        val d = Ur.Decoder()
        var done = false
        for (p in parts.take(9)) done = d.receive(p.uppercase())   // QR alphanumeric mode upper-cases it
        assertTrue(done)
        assertArrayEquals(psbt, d.psbt())
    }

    @Test
    fun decodesFromMixedPartsWhenFramesAreMissed() {
        // Miss every other pure frame: the fountain parts must fill the gaps.
        val d = Ur.Decoder()
        val order = parts.take(9).filterIndexed { i, _ -> i % 2 == 0 } + parts.drop(9)
        var done = false
        for (p in order) { if (d.receive(p)) { done = true; break } }
        assertTrue(done)
        assertArrayEquals(psbt, d.psbt())
    }

    @Test
    fun singlePartRoundTrip() {
        val small = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte(), 1, 2, 3)
        val enc = Ur.psbtEncoder(small)
        assertTrue(enc.isSinglePart)
        val d = Ur.Decoder()
        assertTrue(d.receive(enc.nextPart()))
        assertArrayEquals(small, d.psbt())
    }

    @Test
    fun ownEncoderRoundTripsLargeMessages() {
        val big = ByteArray(5000) { (it * 31 + 7).toByte() }
        val enc = Ur.psbtEncoder(big, 200)
        val d = Ur.Decoder()
        var n = 0
        while (!d.receive(enc.nextPart())) { n++; if (n % 3 == 0) enc.nextPart(); assertTrue(n < 500) }
        assertArrayEquals(big, d.psbt())
    }

    @Test
    fun aFrameOfAnotherMessageStartsOver() {
        val d = Ur.Decoder()
        d.receive(parts[0])
        val other = Ur.psbtEncoder(ByteArray(300) { 1 }, 60)
        assertFalse(d.receive(other.nextPart()))
        assertTrue(d.progress > 0f && d.progress < 0.5f)
    }
}
