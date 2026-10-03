package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Aezeed
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.LnKeys
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.TxBuilder
import com.kilombino.pyblockwatch.data.Scb
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Manual end-to-end check (runs only with FORCE_SPEND_TX set): spends a to_local output (state 5
 * of the test channel, CSV 2) and an anchor to_remote output funded on the BLAKE2b chain, with
 * the unified sighash, and prints the raw transaction for a node to accept.
 */
class ForceCloseSpendTest {
    @Test
    fun `print the spend of the funded test outputs`() {
        val fundingTx = System.getenv("FORCE_SPEND_TX"); assumeTrue(fundingTx != null)
        val dest = System.getenv("FORCE_SPEND_TO")!!
        val words = ("about luggage aunt phone relief reunion crack pig cigar travel spin veteran " +
            "dinner art wealth thrive zone venture valve setup phone chief artist high").split(" ")
        val root = Bip32Priv.fromSeed(Aezeed.decode(words, "").entropy)
        val hex = javaClass.getResourceAsStream("/scb_vector.hex")!!.bufferedReader().readText().trim()
        val chans = Scb.decode(Hashes.hexToBytes(hex), root)
        fun node(fam: Int, idx: Int) = Bip32Priv.derivePath(root, "m/1017'/0'/$fam'/0/$idx")
        // to_local of channel 0 at state 5
        val c = chans[0]
        val revRoot = LnKeys.ecdh(node(5, c.shaChainIndex).key, node(0, c.localKeyIndex[0]!!).publicKey())
        val cp = LnKeys.commitmentPoint(LnKeys.commitmentSecret(revRoot, 5))
        val delayNode = node(4, c.localKeyIndex[4]!!)
        val delayed = LnKeys.tweakPub(delayNode.publicKey(), cp)
        val toLocal = LnKeys.toLocalScript(c.localCsvDelay, delayed, LnKeys.revocationPub(c.remoteRevocation, cp))
        val in0 = TxBuilder.Input(fundingTx, 0, 3000, LnKeys.tweakPriv(delayNode.key, delayNode.publicKey(), cp), delayed,
            sequence = c.localCsvDelay.toLong(), witnessScript = toLocal, witnessExtra = listOf(ByteArray(0)))
        // anchor to_remote of channel 1
        val pay = node(3, chans[1].localKeyIndex[3]!!)
        val in1 = TxBuilder.Input(fundingTx, 2, 3000, pay.key, pay.publicKey(),
            sequence = 1, witnessScript = LnKeys.toRemoteAnchorScript(pay.publicKey()))
        val tx = TxBuilder.build(listOf(in0, in1), listOf(TxBuilder.Output(Address.decodeToScriptPubKey(dest), 5600)),
            unified = true, grindLowR = true)
        println("FORCESPEND ${tx.rawHex}")
    }
}
