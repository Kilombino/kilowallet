package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.chain.NodeEndpoint
import com.kilombino.pyblockwatch.crypto.BondPsbt
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.Psbt
import com.kilombino.pyblockwatch.crypto.Ur
import com.kilombino.pyblockwatch.data.JamWatch
import com.kilombino.pyblockwatch.data.JoinMarket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Manual end-to-end run of the bond spend against a BLAKE2b regtest, the code paths the app takes
 * minus the camera and the screens. Skipped unless BOND_E2E_DIR is set.
 *
 *  - prepare (BOND_E2E_PHASE=prepare): reads Jam's export (export.txt), asks the Electrum server
 *    BOND_E2E_ELECTRUM (host:port) for the bond's state like the Jam screen does, builds the PSBT
 *    like the SPEND WITH SEEDSIGNER flow does (to BOND_E2E_DEST, BOND_E2E_FEERATE sat/vB) and
 *    writes it out as the animated QR's frames (unsigned.psbt, unsigned-frames.txt).
 *  - finish (BOND_E2E_PHASE=finish): reads the frames scanned off SeedSigner's screen
 *    (signed-frames.txt), decodes them, checks the signature and writes the transaction (spend.hex).
 */
class BondSpendRegtestE2E {
    private val dir = System.getenv("BOND_E2E_DIR")?.let { File(it) }

    private fun state(e: JoinMarket.Export, tn: Int, endpoint: NodeEndpoint): JoinMarket.BondState =
        JamWatch.bondStates(e, emptyList(), endpoint, null, { null }, { _, _ -> }).single { it.bond.timenumber == tn }

    @Test
    fun run() {
        assumeTrue(dir != null)
        val phase = System.getenv("BOND_E2E_PHASE") ?: "prepare"
        val (host, port) = System.getenv("BOND_E2E_ELECTRUM")!!.split(":")
        val endpoint = NodeEndpoint(host, port.toInt(), isCustom = true)
        val e = JoinMarket.parse(File(dir, "export.txt").readText(), allowTestNetworks = true)
        val tn = System.getenv("BOND_E2E_TIMENUMBER")!!.toInt()
        val st = state(e, tn, endpoint)
        println("BOND tn=$tn value=${st.value} utxo=${st.txid} height=${st.height} locktime=${st.locktime} " +
            "status=${st.status(System.getenv("BOND_E2E_NOW")?.toLong() ?: (System.currentTimeMillis() / 1000))}")
        val prev = ElectrumClient(endpoint, null).run { try { connect(); transaction(st.txid!!.substringBefore(":")) } finally { close() } }
        val (bond, outputs) = JamWatch.bondSpend(e, st, prev, System.getenv("BOND_E2E_DEST")!!, System.getenv("BOND_E2E_FEERATE")!!.toDouble())
        when (phase) {
            "prepare" -> {
                val psbt = BondPsbt.create(bond, outputs)
                File(dir, "unsigned.psbt").writeText(Psbt.base64(psbt))
                val enc = Ur.psbtEncoder(psbt, 100)
                File(dir, "unsigned-frames.txt").writeText(List(enc.seqLen * 3) { enc.nextPart() }.joinToString("\n"))
                println("PSBT ${psbt.size} bytes, ${enc.seqLen} fragments, fee ${bond.value - outputs.sumOf { it.value }}")
            }
            "finish" -> {
                val psbt = Psbt.decodeText(File(dir, "unsigned.psbt").readText())
                assertTrue("the PSBT made now is the one exported", psbt.contentEquals(BondPsbt.create(bond, outputs)))
                val d = Ur.Decoder()
                val frames = File(dir, "signed-frames.txt").readLines().filter { it.isNotBlank() }
                val done = frames.any { d.receive(it) }
                assertTrue("the signed frames make a whole PSBT", done)
                val signed = BondPsbt.finish(psbt, d.psbt(), bond, outputs)
                File(dir, "spend.hex").writeText(signed.rawHex)
                File(dir, "signed.psbt").writeText(Psbt.base64(d.psbt()))
                println("SPEND ${signed.txid} ${signed.vbytes} vB from ${frames.size} frames")
                assertEquals(bond.locktime, com.kilombino.pyblockwatch.crypto.TxParse.parse(signed.rawHex).locktime)
                println("WITNESS " + com.kilombino.pyblockwatch.crypto.TxParse.parse(signed.rawHex).inputs[0].witness.joinToString(" ") { it.toHex() })
            }
        }
    }
}
