package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.TxParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only Lightning channel closes are ever replayed from the SHA-256 chain onto BLAKE2b. These are
 * real mainnet transactions from before the fork (block 900000 onwards): a cooperative close, a
 * force close (commitment) and an ordinary P2WPKH spend, which must never qualify.
 */
class TxParseTest {
    private fun hex(name: String) = javaClass.getResourceAsStream(name)!!.bufferedReader().readText().trim()

    private fun txid(raw: String): String {
        val tx = TxParse.parse(raw)
        // Rebuild the non-witness serialisation and hash it: proves the parser read every field.
        val out = java.io.ByteArrayOutputStream()
        fun le(v: Long, n: Int) { for (i in 0 until n) out.write(((v ushr (8 * i)) and 0xFF).toInt()) }
        fun vi(n: Int) { require(n < 0xfd); out.write(n) }
        le(tx.version, 4); vi(tx.inputs.size)
        tx.inputs.forEach { out.write(Hashes.hexToBytes(it.txid).reversedArray()); le(it.vout.toLong(), 4); vi(it.scriptSig.size); out.write(it.scriptSig); le(it.sequence, 4) }
        vi(tx.outputs.size); tx.outputs.forEach { le(it.value, 8); vi(it.scriptPubKey.size); out.write(it.scriptPubKey) }
        le(tx.locktime, 4)
        return Hashes.doubleSha256(out.toByteArray()).reversedArray().toHex()
    }

    @Test
    fun `a cooperative close qualifies for replay`() {
        val raw = hex("/ln_coop_close.hex")
        assertEquals("eab3c0ce54cb0dfb6fd02c7c8fc25a3dc589ef0b239f941302ba05b18c2da2eb", txid(raw))
        val tx = TxParse.parse(raw)
        assertTrue(TxParse.spendsChannelFunding(tx))
        assertFalse(TxParse.isCommitment(tx))
    }

    @Test
    fun `a force close is recognised as a commitment`() {
        val raw = hex("/ln_force_close.hex")
        assertEquals("c99afec4e78a09be7d20c933d12669daabaf11cc44f65e04ff734217f96b897d", txid(raw))
        val tx = TxParse.parse(raw)
        assertTrue(TxParse.spendsChannelFunding(tx))
        assertTrue(TxParse.isCommitment(tx))
    }

    @Test
    fun `an ordinary wallet spend never qualifies`() {
        assertFalse(TxParse.spendsChannelFunding(TxParse.parse(hex("/plain_p2wpkh_spend.hex"))))
    }
}
