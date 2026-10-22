package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Bip39
import com.kilombino.pyblockwatch.crypto.BondPsbt
import com.kilombino.pyblockwatch.crypto.Ecdsa
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.Psbt
import com.kilombino.pyblockwatch.crypto.TxBuilder
import com.kilombino.pyblockwatch.crypto.TxParse
import com.kilombino.pyblockwatch.data.JoinMarket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Spending a fidelity bond through a PSBT, checked against a real spend: bond 72c615a4…:0 made
 * and spent from Jam (JoinMarket-NG, XBT fork) on a private BLAKE2b regtest, spend 3d998f50…
 * mined there. The seed is that throwaway regtest wallet's. RFC 6979 makes the signature
 * deterministic, so the transaction we assemble must be the mined one byte for byte.
 */
class BondPsbtTest {
    companion object {
        const val MNEMONIC = "hair horse robot tent betray wedding judge moon tower swallow yellow episode"
        const val FUND_TX = "02000000000101e6e20508e97aa2dc465bd954b4784f6fb5666945c6275cfd38f99ca486acb5ee0000000000fdffffff0280f0fa02" +
            "000000002200200981dcf3b36f7610d26a17c896aa195e818394f83fc565a2d9dd796f0ebd6f09d7effa020000000016001444ac90" +
            "d18c8b8f5436ade8fecbe5f4adfea16c6e0247304402206b9566d11a48e5c87579af8dac230b895e1cac1276fb15492aa55143ce1c" +
            "9d20022048ace4079206bb3f804fc982242f03c485a1fb1c40c82bd73277e9961519d23c212103acc2b0bcf1697324a3013f4fb7cc" +
            "8d59de0aa576a208126205073bd2e770335205010000"
        const val SPEND_TX = "020000000001017cb05f78443ec01cd3d31d30980567fb833242b483a5c682bdd317e1a415c6720000000000fdffffff01fceffa02" +
            "000000001600143a0b72fbc9d6cc43acec4ababfa90fce584269010247304402203d33ee140d1cb9fa8413b141b585f3b6e9c5b4ca" +
            "5a78753d35a86dc7fbe1156102207b14f4e2abd78c0fafe1a95ba304d77e2149912ea1e76ca3e1342f5b61306945212a040081e66a" +
            "b1752103808f8a4d55173b9a3f9a259835f7ce7069321700ebcf2a0fc5892b2649fe134aac0081e66a"
        const val JAM_SIGNATURE = "304402203d33ee140d1cb9fa8413b141b585f3b6e9c5b4ca5a78753d35a86dc7fbe1156102207b14f4e2abd78c0fafe1a95ba304d7" +
            "7e2149912ea1e76ca3e1342f5b6130694521"
        const val LOCKTIME = 1793491200L
    }

    private val master = Bip32Priv.fromSeed(Bip39.toSeed(MNEMONIC.split(" ")))
    private val node = Bip32Priv.derivePath(master, "m/84'/1'/0'/2/82")
    private val pubkey = node.publicKey()
    private val witnessScript = JoinMarket.bondScript(pubkey, LOCKTIME)

    private fun bond(fp: String = "c47272bb", prev: Boolean = true) = BondPsbt.Bond(
        "72c615a4e117d3bd82c6a583b4423283fb670598301dd3d31cc03e44785fb07c", 0, 50_000_000L, pubkey, LOCKTIME, witnessScript,
        Psbt.Origin.parse("[$fp/84'/1'/0'/2/82]")!!, if (prev) Hashes.hexToBytes(FUND_TX) else null)

    private val outputs = listOf(TxBuilder.Output(Hashes.hexToBytes("00143a0b72fbc9d6cc43acec4ababfa90fce58426901"), 49_999_868L))

    /** What a signer returns: our PSBT with a partial signature added to the input. */
    private fun signed(psbt: ByteArray, sig: ByteArray, key: ByteArray = pubkey): ByteArray {
        val p = Psbt.parse(psbt)
        return Psbt.serialize(Psbt.Parsed(p.global, listOf(p.inputs[0] + (byteArrayOf(0x02) + key to sig)), p.outputs))
    }

    private fun sign(psbt: ByteArray): ByteArray {
        val h = BondPsbt.sighash(bond(), outputs)
        return Ecdsa.der(Ecdsa.sign(node.key, h, grindLowR = true)) + byteArrayOf(0x21)
    }

    @Test
    fun scriptIsJams() {
        assertEquals("040081e66ab1752103808f8a4d55173b9a3f9a259835f7ce7069321700ebcf2a0fc5892b2649fe134aac", witnessScript.toHex())
    }

    @Test
    fun unsignedTransactionIsTheMinedOneButForTheSequence() {
        val mined = TxParse.parse(SPEND_TX)
        val ours = TxParse.parse(BondPsbt.unsignedTx(bond(), outputs).toHex())
        assertEquals(mined.inputs[0].txid, ours.inputs[0].txid)
        assertEquals(mined.inputs[0].vout, ours.inputs[0].vout)
        assertEquals(mined.outputs.map { it.value to it.scriptPubKey.toHex() }, ours.outputs.map { it.value to it.scriptPubKey.toHex() })
        assertEquals(mined.locktime, ours.locktime)
        assertEquals(LOCKTIME, ours.locktime)
        assertEquals(0xfffffffdL, mined.inputs[0].sequence) // Jam signals RBF; we use 0xfffffffe
        assertEquals(BondPsbt.SEQUENCE, ours.inputs[0].sequence)
    }

    @Test
    fun spendWithOurSequenceFinishes() {
        val sig = sign(BondPsbt.create(bond(), outputs))
        val psbt = BondPsbt.create(bond(), outputs)
        val done = BondPsbt.finish(psbt, signed(psbt, sig), bond(), outputs)
        val tx = TxParse.parse(done.rawHex)
        assertEquals(listOf(sig.toHex(), witnessScript.toHex()), tx.inputs[0].witness.map { it.toHex() })
        assertEquals(LOCKTIME, tx.locktime)
        assertEquals(BondPsbt.SEQUENCE, tx.inputs[0].sequence)
        assertTrue(BondPsbt.vbytes(bond(), outputs) >= TxParse.vsize(done.rawHex))
    }

    @Test
    fun withJamsSequenceTheResultIsByteIdenticalToTheMinedSpend() {
        // Rebuild the mined transaction's sighash with Jam's sequence and check our signer gives Jam's bytes.
        val input = TxBuilder.Input(bond().txid, 0, 50_000_000L, node.key, pubkey, 0xfffffffdL, witnessScript = witnessScript)
        val built = TxBuilder.build(listOf(input), outputs, locktime = LOCKTIME, unified = true, grindLowR = true)
        assertEquals(SPEND_TX, built.rawHex)
        assertEquals(JAM_SIGNATURE, TxParse.parse(built.rawHex).inputs[0].witness[0].toHex())
    }

    @Test
    fun psbtFields() {
        val p = Psbt.parse(BondPsbt.create(bond(), outputs))
        val m = p.inputs.single().associate { it.first.toHex() to it.second.toHex() }
        assertEquals(FUND_TX, m["00"])
        assertEquals("80f0fa0200000000220020" + Hashes.sha256(witnessScript).toHex(), m["01"])
        assertEquals("21000000", m["03"])
        assertEquals(witnessScript.toHex(), m["05"])
        assertEquals("c47272bb5400008001000080000000800200000052000000", m["06" + pubkey.toHex()])
        assertEquals(1, p.outputs.size)
    }

    @Test
    fun refusesWhatMustNotBeBroadcast() {
        val psbt = BondPsbt.create(bond(), outputs)
        val good = sign(psbt)
        fun refused(returned: ByteArray, what: String) {
            try { BondPsbt.finish(psbt, returned, bond(), outputs); fail("accepted: $what") } catch (_: Exception) { }
        }
        refused(psbt, "unsigned")
        refused(signed(psbt, good.copyOfRange(0, good.size - 1) + byteArrayOf(0x01)), "SIGHASH_ALL")
        val other = Bip32Priv.derivePath(master, "m/84'/1'/0'/2/83")
        refused(signed(psbt, Ecdsa.der(Ecdsa.sign(other.key, BondPsbt.sighash(bond(), outputs))) + byteArrayOf(0x21)), "another key")
        val otherOutputs = listOf(TxBuilder.Output(outputs[0].scriptPubKey, 49_000_000L))
        refused(signed(BondPsbt.create(bond(), otherOutputs), good), "another transaction")
    }

    @Test
    fun prevTxMustMatch() {
        try {
            BondPsbt.Bond("00".repeat(32), 0, 50_000_000L, pubkey, LOCKTIME, witnessScript,
                Psbt.Origin.parse("[c47272bb/84'/1'/0'/2/82]")!!, Hashes.hexToBytes(FUND_TX))
            fail("accepted a previous transaction with another txid")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun finalWitnessFromSignerIsAcceptedToo() {
        val psbt = BondPsbt.create(bond(), outputs)
        val sig = sign(psbt)
        val w = ByteArrayOutputStream().apply { write(2); write(sig.size); write(sig); write(witnessScript.size); write(witnessScript) }.toByteArray()
        val p = Psbt.parse(psbt)
        val returned = Psbt.serialize(Psbt.Parsed(p.global, listOf(p.inputs[0] + (byteArrayOf(0x08) to w)), p.outputs))
        val done = BondPsbt.finish(psbt, returned, bond(), outputs)
        assertEquals(sig.toHex(), TxParse.parse(done.rawHex).inputs[0].witness[0].toHex())
    }

    @Test
    fun addressOfTheBond() {
        assertEquals("bcrt1qpxqaeuandampp5n2zlyfd2set6qc898c8lzktgkem4uk7r4aduysaeda8s", JoinMarket.bondAddress(witnessScript, "bcrt"))
    }
}
