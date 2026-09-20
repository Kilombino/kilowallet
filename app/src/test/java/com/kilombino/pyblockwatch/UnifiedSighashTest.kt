package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.TxBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unified opt-in sighash (`doc/unified-sighash.md`, Knots v29.4.1) is what protects a BLAKE2b
 * spend from being replayed onto the shared-history SHA-256 chain. Its message must be byte-for-byte
 * what the reference implementation produces, so this pins [TxBuilder.unifiedMessage] against every
 * script-type-0 and script-type-1 vector in `unified_sighash.json` (142 of the 166; the four taproot
 * vectors are excluded because this wallet never spends taproot). A single wrong byte moves the
 * digest and the test fails.
 *
 * The vectors are carried in `app/src/test/resources/unified_sighash_type01.tsv`; each line gives the
 * scriptCode, raw transaction, input index, hash type, script type, the spent outputs, and the
 * expected sighash (raw bytes, not reversed display order).
 */
class UnifiedSighashTest {

    /** A minimal reader over a raw transaction: everything the message needs and nothing more. */
    private class Reader(val b: ByteArray) {
        var o = 0
        fun u8(): Int = b[o++].toInt() and 0xFF
        fun take(n: Int): ByteArray = b.copyOfRange(o, o + n).also { o += n }
        fun u32(): Long { var v = 0L; for (i in 0 until 4) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i)); o += 4; return v }
        fun u64(): Long { var v = 0L; for (i in 0 until 8) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i)); o += 8; return v }
        fun varint(): Long = when (val n = u8()) {
            in 0..0xfc -> n.toLong()
            0xfd -> (u8().toLong()) or (u8().toLong() shl 8)
            0xfe -> u32()
            else -> u64()
        }
    }

    private data class ParsedTx(
        val version: Long,
        val prevouts: List<ByteArray>, // 36-byte outpoints, in input order
        val sequences: List<Long>,
        val outputs: List<TxBuilder.Output>,
        val locktime: Long,
    )

    private fun parseTx(hex: String): ParsedTx {
        val r = Reader(Hashes.hexToBytes(hex))
        val version = r.u32()
        val segwit = r.b[r.o] == 0x00.toByte() && r.b[r.o + 1] == 0x01.toByte()
        if (segwit) r.o += 2
        val nIn = r.varint().toInt()
        val prevouts = ArrayList<ByteArray>(nIn)
        val sequences = ArrayList<Long>(nIn)
        repeat(nIn) {
            prevouts.add(r.take(36))       // 32-byte hash + 4-byte index, already internal order
            r.take(r.varint().toInt())     // scriptSig, ignored
            sequences.add(r.u32())
        }
        val nOut = r.varint().toInt()
        val outputs = ArrayList<TxBuilder.Output>(nOut)
        repeat(nOut) {
            val value = r.u64()
            val spk = r.take(r.varint().toInt())
            outputs.add(TxBuilder.Output(spk, value))
        }
        if (segwit) repeat(nIn) { repeat(r.varint().toInt()) { r.take(r.varint().toInt()) } }
        return ParsedTx(version, prevouts, sequences, outputs, r.u32())
    }

    private fun vectors(): List<String> =
        javaClass.getResourceAsStream("/unified_sighash_type01.tsv")!!
            .bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }

    @Test
    fun `unified message matches every script type 0 and 1 vector`() {
        val rows = vectors()
        assertEquals("expected 142 script-type-0/1 vectors", 142, rows.size)
        var byType = intArrayOf(0, 0)
        for (line in rows) {
            val col = line.split("\t")
            val scriptCode = col[0]
            val rawTx = col[1]
            val inIdx = col[2].toInt()
            val hashType = col[3].toInt()
            val scriptType = col[4].toInt()
            val expected = col[6]
            val spent = col[5].split("|").map {
                val (v, spk) = it.split(":")
                v.toLong() to Hashes.hexToBytes(spk)
            }
            val tx = parseTx(rawTx)
            val got = TxBuilder.unifiedMessage(
                version = tx.version, locktime = tx.locktime,
                hashType = hashType, scriptType = scriptType,
                prevouts = tx.prevouts, amounts = spent.map { it.first },
                spentScripts = spent.map { it.second }, sequences = tx.sequences,
                outputs = tx.outputs, index = inIdx, scriptCode = Hashes.hexToBytes(scriptCode),
            )
            assertEquals("vector (type $scriptType, hashType $hashType, in $inIdx)", expected, got.toHex())
            byType[scriptType]++
        }
        assertEquals(76, byType[0])
        assertEquals(66, byType[1])
    }

    @Test
    fun `p2wpkh wrapper opts in with 0x21 and differs from the bip143 sighash`() {
        // Two real P2WPKH inputs; the wrapper must produce the unified (script type 1, SIGHASH_ALL)
        // message, and a witness signature whose trailing hash-type byte is 0x21.
        val seed = Hashes.hexToBytes("000102030405060708090a0b0c0d0e0f")
        val n0 = com.kilombino.pyblockwatch.crypto.Bip32Priv.derivePath(
            com.kilombino.pyblockwatch.crypto.Bip32Priv.fromSeed(seed), "m/84'/0'/0'/0/0")
        val n1 = com.kilombino.pyblockwatch.crypto.Bip32Priv.derivePath(
            com.kilombino.pyblockwatch.crypto.Bip32Priv.fromSeed(seed), "m/84'/0'/0'/0/1")
        val inputs = listOf(
            TxBuilder.Input("a".repeat(64), 0, 100_000L, n0.key, n0.publicKey()),
            TxBuilder.Input("b".repeat(64), 1, 60_000L, n1.key, n1.publicKey()),
        )
        val to = com.kilombino.pyblockwatch.crypto.Address.decodeToScriptPubKey("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
        val outputs = listOf(TxBuilder.Output(to, 150_000L))

        val bip143 = TxBuilder.sighash(2, inputs, outputs, 0, 0)
        val unified = TxBuilder.unifiedSighash(2, inputs, outputs, 0, 0)
        assertTrue("opting in must change the message", !bip143.contentEquals(unified))

        val legacyWit = TxBuilder.witnessSignature(2, inputs, outputs, 0, 0, unified = false)
        val unifiedWit = TxBuilder.witnessSignature(2, inputs, outputs, 0, 0, unified = true)
        assertEquals(0x01, legacyWit.last().toInt() and 0xFF)
        assertEquals(0x21, unifiedWit.last().toInt() and 0xFF)

        // The wrapper must equal the core called with P2WPKH's own spk and implied-P2PKH scriptCode.
        val scriptCode = Hashes.hexToBytes(
            "76a914" + Hashes.hash160(inputs[0].pubkey).toHex() + "88ac")
        val core = TxBuilder.unifiedMessage(
            version = 2, locktime = 0, hashType = 0x21, scriptType = 1,
            prevouts = inputs.map { Hashes.hexToBytes(it.txid).reversedArray() + byteArrayOf(it.vout.toByte(), 0, 0, 0) },
            amounts = inputs.map { it.value },
            spentScripts = inputs.map { byteArrayOf(0x00, 0x14) + Hashes.hash160(it.pubkey) },
            sequences = inputs.map { it.sequence },
            outputs = outputs, index = 0, scriptCode = scriptCode,
        )
        assertEquals(core.toHex(), unified.toHex())
    }
}
