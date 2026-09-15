package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Ecdsa
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import com.kilombino.pyblockwatch.crypto.TxBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger

/**
 * The unified opt-in sighash (SIGHASH_UNIFIED) has to reproduce the official vectors byte for
 * byte, or an opted-in spend is simply invalid. The fixture is the official
 * `src/test/data/unified_sighash.json` from Knots tag `v29.4.1.knots20260508`, flattened to
 * TSV (see the file header for the columns and source). This wallet signs native segwit
 * (script type 1), so those 66 vectors are the ones asserted here.
 */
class UnifiedSighashTest {

    private data class Vector(
        val scriptType: Int,
        val hashType: Int,
        val inIdx: Int,
        val scriptCode: ByteArray,
        val rawTx: ByteArray,
        val spent: List<Pair<Long, ByteArray>>,
        val expected: String,
    )

    private fun loadVectors(): List<Vector> {
        val text = javaClass.getResourceAsStream("/unified_sighash.tsv")!!
            .readBytes().toString(Charsets.UTF_8)
        return text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val f = line.split('\t')
                Vector(
                    scriptType = f[0].toInt(),
                    hashType = f[1].toInt(),
                    inIdx = f[2].toInt(),
                    scriptCode = Hashes.hexToBytes(f[3]),
                    rawTx = Hashes.hexToBytes(f[4]),
                    spent = f[5].split(',').map {
                        val (value, script) = it.split(':')
                        value.toLong() to Hashes.hexToBytes(script)
                    },
                    expected = f[6],
                )
            }.toList()
    }

    /** Just enough of a transaction deserialiser to feed the sighash; witness data is skipped. */
    private class Parsed(
        val version: Long,
        val locktime: Long,
        val outpoints: List<Triple<String, Int, Long>>,
        val outputs: List<TxBuilder.Output>,
    )

    private fun parse(raw: ByteArray): Parsed {
        var p = 0
        fun u32(): Long {
            val v = (raw[p].toLong() and 0xff) or ((raw[p + 1].toLong() and 0xff) shl 8) or
                ((raw[p + 2].toLong() and 0xff) shl 16) or ((raw[p + 3].toLong() and 0xff) shl 24)
            p += 4; return v
        }
        fun u64(): Long {
            var v = 0L
            for (i in 0 until 8) v = v or ((raw[p + i].toLong() and 0xff) shl (8 * i))
            p += 8; return v
        }
        fun varint(): Int {
            val c = raw[p].toInt() and 0xff; p += 1
            return when {
                c < 0xfd -> c
                c == 0xfd -> { val v = (raw[p].toInt() and 0xff) or ((raw[p + 1].toInt() and 0xff) shl 8); p += 2; v }
                c == 0xfe -> { var v = 0; for (i in 0 until 4) v = v or ((raw[p + i].toInt() and 0xff) shl (8 * i)); p += 4; v }
                else -> {
                    var v = 0L
                    for (i in 0 until 8) v = v or ((raw[p + i].toLong() and 0xff) shl (8 * i))
                    p += 8
                    v.toInt()
                }
            }
        }
        fun bytes(n: Int): ByteArray { val b = raw.copyOfRange(p, p + n); p += n; return b }

        val version = u32()
        val outpoints = ArrayList<Triple<String, Int, Long>>()
        for (i in 0 until varint()) {
            val hash = bytes(32).reversedArray().toHex()
            val vout = u32().toInt()
            bytes(varint())                        // scriptSig
            outpoints.add(Triple(hash, vout, u32()))
        }
        val outputs = ArrayList<TxBuilder.Output>()
        for (i in 0 until varint()) {
            val value = u64()
            outputs.add(TxBuilder.Output(bytes(varint()), value))
        }
        // Every official vector is a non-witness serialisation (no 0001 marker), so there is no
        // witness section to skip.
        return Parsed(version, u32(), outpoints, outputs)
    }

    @Test
    fun `unified sighash matches every script-type-1 vector`() {
        val vectors = loadVectors().filter { it.scriptType == 1 }
        assertEquals("the fixture should carry the 66 P2WPKH vectors", 66, vectors.size)

        for (v in vectors) {
            val tx = parse(v.rawTx)
            val inputs = tx.outpoints.mapIndexed { i, (txid, vout, sequence) ->
                // value comes from the spent output, which is what the sighash commits to.
                TxBuilder.Input(txid, vout, v.spent[i].first, BigInteger.ONE, ByteArray(33), sequence)
            }
            val got = TxBuilder.unifiedSighash(
                version = tx.version,
                inputs = inputs,
                outputs = tx.outputs,
                index = v.inIdx,
                locktime = tx.locktime,
                hashType = v.hashType,
                spentScriptPubKeys = v.spent.map { it.second },
                scriptCode = v.scriptCode,
            )
            assertEquals(
                "hashType=0x${v.hashType.toString(16)} inIdx=${v.inIdx}",
                v.expected,
                got.toHex(),
            )
        }
    }

    @Test
    fun `the wallet signs each input's unified digest, derived from Address scripts`() {
        val pub0 = Secp256k1.compress(Secp256k1.G)
        val pub1 = Secp256k1.compress(Secp256k1.multiply(BigInteger.valueOf(2), Secp256k1.G))
        val inputs = listOf(
            TxBuilder.Input("00".repeat(32), 0, 100_000L, BigInteger.ONE, pub0),
            TxBuilder.Input("11".repeat(32), 1, 50_000L, BigInteger.valueOf(2), pub1),
        )
        val outputs = listOf(TxBuilder.Output(Hashes.hexToBytes("0014" + "11".repeat(20)), 140_000L))

        // The wallet derives these byte strings internally; the test derives them the same way
        // (through Address) so a helper divergence, or a per-input wiring bug, cannot hide.
        val spentScripts = inputs.map { Address.scriptPubKey(it.pubkey, ScriptType.P2WPKH) }

        for (i in inputs.indices) {
            val scriptCode = Address.scriptPubKey(inputs[i].pubkey, ScriptType.P2PKH)
            val digest = TxBuilder.unifiedSighash(
                2, inputs, outputs, i, 0, TxBuilder.SIGHASH_ALL or TxBuilder.SIGHASH_UNIFIED,
                spentScripts, scriptCode,
            )
            assertFalse(
                "input $i: the opt-in digest must differ from BIP-143",
                TxBuilder.sighash(2, inputs, outputs, i, 0).contentEquals(digest),
            )
            val signed = TxBuilder.witnessSignature(
                2, inputs, outputs, i, 0, TxBuilder.SIGHASH_ALL or TxBuilder.SIGHASH_UNIFIED,
            )
            // RFC-6979 is deterministic, so each signature must be exactly this.
            assertEquals(
                "input $i signs its own unified digest",
                (Ecdsa.der(Ecdsa.sign(inputs[i].privateKey, digest)) + byteArrayOf(0x21)).toHex(),
                signed.toHex(),
            )
        }
        val legacy = TxBuilder.witnessSignature(2, inputs, outputs, 0, 0)
        assertEquals("unified-off spends still carry SIGHASH_ALL", 0x01, legacy.last().toInt() and 0xff)
    }

    @Test
    fun `non-unified signing rejects a hash type other than SIGHASH_ALL`() {
        val pub = Secp256k1.compress(Secp256k1.G)
        val input = TxBuilder.Input("00".repeat(32), 0, 100_000L, BigInteger.ONE, pub)
        val output = TxBuilder.Output(Hashes.hexToBytes("0014" + "11".repeat(20)), 90_000L)
        for (bad in listOf(TxBuilder.SIGHASH_NONE, TxBuilder.SIGHASH_ANYONECANPAY, 0x100, 0x121)) {
            try {
                TxBuilder.witnessSignature(2, listOf(input), listOf(output), 0, 0, bad)
                fail("hashType 0x${bad.toString(16)} should have been rejected")
            } catch (e: IllegalArgumentException) {
                if (bad == 0x100 || bad == 0x121) assertTrue(e.message!!.contains("one byte"))
                else assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun `unifiedSighash rejects an out-of-range hash type and an out-of-range index`() {
        val pub = Secp256k1.compress(Secp256k1.G)
        val inputs = listOf(TxBuilder.Input("00".repeat(32), 0, 100_000L, BigInteger.ONE, pub))
        val outputs = listOf(TxBuilder.Output(Hashes.hexToBytes("0014" + "11".repeat(20)), 90_000L))
        val spent = listOf(Address.scriptPubKey(pub, ScriptType.P2WPKH))
        val scriptCode = Address.scriptPubKey(pub, ScriptType.P2PKH)

        try {
            TxBuilder.unifiedSighash(2, inputs, outputs, 0, 0, 0x121, spent, scriptCode)
            fail("0x121 should be rejected as out of range")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("one byte"))
        }
        try {
            TxBuilder.unifiedSighash(
                2, inputs, outputs, 1, 0,
                TxBuilder.SIGHASH_ALL or TxBuilder.SIGHASH_UNIFIED, spent, scriptCode,
            )
            fail("index 1 with one input should be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("no input at that index"))
        }
    }

    @Test
    fun `SIGHASH_SINGLE with no output at the input's index is rejected`() {
        val pub = Secp256k1.compress(Secp256k1.G)
        val input = TxBuilder.Input("00".repeat(32), 0, 100_000L, BigInteger.ONE, pub)
        try {
            TxBuilder.unifiedSighash(
                2, listOf(input), emptyList(), 0, 0,
                TxBuilder.SIGHASH_SINGLE or TxBuilder.SIGHASH_UNIFIED,
                listOf(Address.scriptPubKey(pub, ScriptType.P2WPKH)),
                Address.scriptPubKey(pub, ScriptType.P2PKH),
            )
            fail("SIGHASH_SINGLE with no output at the input's index should be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("SIGHASH_SINGLE"))
        }
    }
}
