package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.Schnorr
import com.kilombino.pyblockwatch.crypto.TxBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Taproot key-path spending, for sweeping a key's bc1p coins: BIP-340 signatures against the
 * official test vectors, and the BIP-341 tweak, sighash and witness against the BIP-341 wallet
 * test vectors (keyPathSpending).
 */
class TaprootTest {

    private fun resource(name: String) =
        javaClass.getResourceAsStream(name)!!.bufferedReader().readLines()

    @Test
    fun `bip340 signing and verification vectors`() {
        var signed = 0
        for (line in resource("/bip340_vectors.csv").drop(1)) {
            val c = line.split(",")
            val pub = Hashes.hexToBytes(c[2])
            val msg = Hashes.hexToBytes(c[4])
            val sig = Hashes.hexToBytes(c[5])
            val ok = c[6] == "TRUE"
            if (msg.size != 32) continue // variable-length message vectors: this wallet signs 32-byte hashes
            if (c[1].isNotEmpty()) {
                val got = Schnorr.sign(BigInteger(c[1], 16), msg, Hashes.hexToBytes(c[3]))
                assertEquals("vector ${c[0]}", c[5].lowercase(), got.toHex())
                signed++
            }
            assertEquals("verify vector ${c[0]}", ok, Schnorr.verify(pub, msg, sig))
        }
        assertTrue(signed >= 4)
    }

    @Test
    fun `bip341 key-path tweak, sighash and signature vectors`() {
        val lines = resource("/bip341_keypath.tsv")
        val raw = Hashes.hexToBytes(lines.first { it.startsWith("# rawUnsignedTx") }.split("\t")[1])
        val utxos = lines.first { it.startsWith("# utxos") }.split("\t")[1].split("|").map {
            val (v, s) = it.split(":"); v.toLong() to Hashes.hexToBytes(s)
        }
        // Parse the unsigned transaction: version, outpoints, sequences, outputs, locktime.
        var o = 0
        fun u8() = raw[o++].toInt() and 0xFF
        fun le(n: Int): Long { var v = 0L; for (i in 0 until n) v = v or ((raw[o + i].toLong() and 0xFF) shl (8 * i)); o += n; return v }
        fun varint(): Int = when (val n = u8()) { 0xfd -> le(2).toInt(); else -> n }
        val version = le(4)
        val prevouts = ArrayList<ByteArray>(); val sequences = ArrayList<Long>()
        repeat(varint()) {
            prevouts += raw.copyOfRange(o, o + 36); o += 36
            val ss = varint(); o += ss
            sequences += le(4)
        }
        val outputs = ArrayList<TxBuilder.Output>()
        repeat(varint()) {
            val v = le(8); val n = varint()
            outputs += TxBuilder.Output(raw.copyOfRange(o, o + n), v); o += n
        }
        val locktime = le(4)

        var checked = 0
        for (line in lines.filter { !it.startsWith("#") && it.isNotBlank() }) {
            val c = line.split("\t")
            val index = c[0].toInt()
            val merkle = if (c[2] == "-") null else Hashes.hexToBytes(c[2])
            val hashType = c[3].toInt()
            val tweaked = Schnorr.tweakedSecret(BigInteger(c[1], 16), merkle)
            assertEquals("tweaked key, input $index", c[4], tweaked.toString(16).padStart(64, '0'))
            val sighash = TxBuilder.taprootMessage(
                version, locktime, hashType, prevouts, utxos.map { it.first }, utxos.map { it.second },
                sequences, outputs, index,
            )
            assertEquals("sighash, input $index", c[5], sighash.toHex())
            val sig = Schnorr.sign(tweaked, sighash, ByteArray(32)) +
                (if (hashType == 0) ByteArray(0) else byteArrayOf(hashType.toByte()))
            assertEquals("witness, input $index", c[6], sig.toHex())
            checked++
        }
        assertEquals(7, checked)
    }
}
