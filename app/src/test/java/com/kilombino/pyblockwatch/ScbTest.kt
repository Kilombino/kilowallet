package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.crypto.Aezeed
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.LnKeys
import com.kilombino.pyblockwatch.data.Scb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * channel.backup made by LND's own chanbackup code (two channels: tweakless and anchors) for
 * the real-parameter test seed: decrypt, list the channels, and rebuild each funding script
 * exactly as LND does.
 */
class ScbTest {
    private val words = ("about luggage aunt phone relief reunion crack pig cigar travel spin veteran " +
        "dinner art wealth thrive zone venture valve setup phone chief artist high").split(" ")
    private fun res(n: String) = javaClass.getResourceAsStream(n)!!.bufferedReader().readText().trim()

    @Test
    fun `decrypts and lists the channels with their funding scripts`() {
        val root = Bip32Priv.fromSeed(Aezeed.decode(words, "").entropy)
        val chans = Scb.decode(Hashes.hexToBytes(res("/scb_vector.hex")), root)
        val expected = res("/scb_vector_channels.tsv").lines().map { it.split("\t") }
        assertEquals(2, chans.size)
        chans.zip(expected).forEach { (c, e) ->
            assertEquals(e[1], c.fundingTxid)
            assertEquals(e[2].toInt(), c.fundingVout)
            assertEquals(e[3].replace(":", "x"), c.shortChannelId)
            assertEquals(e[4].toLong(), c.capacity)
            assertEquals(e[5], c.remoteNode)
            val local = Bip32Priv.derivePath(root, "m/1017'/0'/0'/0/${c.localKeyIndex[0]}").publicKey()
            assertEquals(e[6], c.fundingScript(local).toHex())
        }
        assertEquals(1, chans[0].version); assertEquals(3, chans[1].version)
    }

    @Test
    fun `another seed cannot open it`() {
        val other = Bip32Priv.fromSeed(ByteArray(16) { 7 })
        assertThrows(Scb.WrongSeed::class.java) { Scb.decode(Hashes.hexToBytes(res("/scb_vector.hex")), other) }
    }

    @Test
    fun `force close keys and scripts match lnd`() {
        val root = Bip32Priv.fromSeed(Aezeed.decode(words, "").entropy)
        val chans = Scb.decode(Hashes.hexToBytes(res("/scb_vector.hex")), root)
        fun pub(fam: Int, idx: Int) = Bip32Priv.derivePath(root, "m/1017'/0'/$fam'/0/$idx").publicKey()
        fun priv(fam: Int, idx: Int) = Bip32Priv.derivePath(root, "m/1017'/0'/$fam'/0/$idx").key
        for (row in res("/scb_vector_force.tsv").lines().map { it.split("\t") }) {
            val c = chans[row[1].toInt()]
            val multisig = pub(0, c.localKeyIndex[0]!!)
            val revRoot = LnKeys.ecdh(priv(5, c.shaChainIndex), multisig)
            val secret = LnKeys.commitmentSecret(revRoot, 5)
            assertEquals(row[2], secret.toHex())
            val cp = LnKeys.commitmentPoint(secret)
            assertEquals(row[3], cp.toHex())
            val delayBase = pub(4, c.localKeyIndex[4]!!)
            val delayed = LnKeys.tweakPub(delayBase, cp)
            assertEquals(row[4], delayed.toHex())
            assertEquals(row[5], LnKeys.tweakPriv(priv(4, c.localKeyIndex[4]!!), delayBase, cp).toString(16).padStart(64, '0'))
            val rev = LnKeys.revocationPub(c.remoteRevocation, cp)
            assertEquals(row[6], rev.toHex())
            val ourPay = pub(3, c.localKeyIndex[3]!!)
            val obf = if (c.isInitiator) LnKeys.obfuscator(ourPay, c.remotePayment) else LnKeys.obfuscator(c.remotePayment, ourPay)
            assertEquals(5L, LnKeys.stateNumber(row[7].toLong(), row[8].toLong(), obf))
            val script = LnKeys.toLocalScript(c.localCsvDelay, delayed, rev)
            assertEquals(row[9], script.toHex())
            assertEquals(row[10], LnKeys.p2wsh(script).toHex())
            assertEquals(row[11], LnKeys.p2wsh(LnKeys.toRemoteAnchorScript(ourPay)).toHex())
        }
    }
}
