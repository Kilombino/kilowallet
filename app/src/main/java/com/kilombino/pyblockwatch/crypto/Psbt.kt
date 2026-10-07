package com.kilombino.pyblockwatch.crypto

import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * BIP-174 PSBTs for a watch-only wallet: it builds the transaction, a separate signer holds the
 * keys. Every input asks for the unified sighash (PSBT_IN_SIGHASH_TYPE = 0x21), so a spend
 * signed for the BLAKE2b chain cannot be replayed on the SHA-256 one.
 *
 * Only standard fields: witness UTXO, sighash type, redeem script (nested SegWit), BIP-32 /
 * Taproot BIP-32 derivations and the Taproot internal key. On the way back, [finish] trusts
 * nothing in the returned PSBT but the signatures: the transaction must be byte for byte the
 * one exported, and each signature is checked here against the sighash computed from our own
 * copy of the coins (as RedWallet does).
 */
object Psbt {
    private val MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())
    const val SIGHASH = TxBuilder.SIGHASH_UNIFIED or TxBuilder.SIGHASH_ALL // 0x21

    /** Where a key sits under its master: fingerprint and full path (hardened = +2^31). */
    class Origin(val fingerprint: ByteArray, val path: List<Long>) {
        init { require(fingerprint.size == 4) }
        fun bytes(): ByteArray = fingerprint + path.flatMap { v -> (0 until 4).map { ((v ushr (8 * it)) and 0xff).toByte() } }
        fun child(chain: Int, index: Int) = Origin(fingerprint, path + chain.toLong() + index.toLong())

        companion object {
            /** `[d34db33f/84'/0'/0']` (or with h), as wallets print a key origin; null when blank. */
            fun parse(s: String): Origin? {
                val t = s.trim().removePrefix("[").removeSuffix("]").trim()
                if (t.isEmpty()) return null
                val parts = t.split("/")
                require(parts[0].length == 8 && parts[0].all { it in "0123456789abcdefABCDEF" }) {
                    "The key origin starts with the 8-hex-digit master fingerprint, like [d34db33f/84'/0'/0']."
                }
                val path = parts.drop(1).map { p ->
                    val hard = p.endsWith("'") || p.endsWith("h") || p.endsWith("H")
                    val n = p.trimEnd('\'', 'h', 'H').toLongOrNull()
                    require(n != null && n in 0 until 0x80000000L) { "Bad step \"$p\" in the key origin." }
                    if (hard) n + 0x80000000L else n
                }
                return Origin(Hashes.hexToBytes(parts[0]), path)
            }
        }
    }

    /** A coin to spend: everything needed to compute its sighash and finish its witness. */
    class Coin(
        val txid: String, val vout: Int, val value: Long, val type: ScriptType,
        val pubkey: ByteArray,          // 33-byte compressed; the internal key for Taproot
        val origin: Origin? = null,
        val sequence: Long = 0xfffffffdL,
    ) {
        init {
            require(type == ScriptType.P2WPKH || type == ScriptType.P2SH_P2WPKH || type == ScriptType.P2TR) {
                "Signing elsewhere works for SegWit (bc1q), nested SegWit (3…) and Taproot (bc1p) coins."
            }
        }
        // The private key is never read for a PSBT: the input only feeds the sighash and the witness.
        internal fun asInput() = TxBuilder.Input(txid, vout, value, BigInteger.ONE, pubkey, sequence, type)
        val spentScript: ByteArray get() = Address.scriptPubKey(pubkey, type)
    }

    /** A change output of ours, so a signer can recognise it. */
    class Change(val pubkey: ByteArray, val type: ScriptType, val origin: Origin?)

    // --------------------------------------------------------------------------- encoding

    private fun varint(n: Long): ByteArray = when {
        n < 0xfd -> byteArrayOf(n.toByte())
        n <= 0xffff -> byteArrayOf(0xfd.toByte(), n.toByte(), (n ushr 8).toByte())
        n <= 0xffffffffL -> byteArrayOf(0xfe.toByte()) + ByteArray(4) { (n ushr (8 * it)).toByte() }
        else -> byteArrayOf(0xff.toByte()) + ByteArray(8) { (n ushr (8 * it)).toByte() }
    }
    private fun le(v: Long, n: Int) = ByteArray(n) { ((v ushr (8 * it)) and 0xff).toByte() }
    private fun vb(b: ByteArray) = varint(b.size.toLong()) + b

    private fun ByteArrayOutputStream.kv(key: ByteArray, value: ByteArray) { write(vb(key)); write(vb(value)) }

    /** The unsigned transaction: empty scriptSigs, no witness. */
    fun unsignedTx(coins: List<Coin>, outputs: List<TxBuilder.Output>, version: Long = 2, locktime: Long = 0): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(le(version, 4))
        o.write(varint(coins.size.toLong()))
        for (c in coins) {
            o.write(Hashes.hexToBytes(c.txid).reversedArray()); o.write(le(c.vout.toLong(), 4))
            o.write(0); o.write(le(c.sequence, 4))
        }
        o.write(varint(outputs.size.toLong()))
        for (out in outputs) { o.write(le(out.value, 8)); o.write(vb(out.scriptPubKey)) }
        o.write(le(locktime, 4))
        return o.toByteArray()
    }

    /** The PSBT to hand to the signer. [change] holds an entry for each output that is ours. */
    fun create(coins: List<Coin>, outputs: List<TxBuilder.Output>, change: Map<Int, Change> = emptyMap()): ByteArray {
        require(coins.isNotEmpty() && outputs.isNotEmpty())
        val o = ByteArrayOutputStream()
        o.write(MAGIC)
        o.kv(byteArrayOf(0x00), unsignedTx(coins, outputs))
        o.write(0)
        for (c in coins) {
            o.kv(byteArrayOf(0x01), le(c.value, 8) + vb(c.spentScript))             // WITNESS_UTXO
            o.kv(byteArrayOf(0x03), le(SIGHASH.toLong(), 4))                         // SIGHASH_TYPE
            when (c.type) {
                ScriptType.P2TR -> {
                    val x = c.pubkey.copyOfRange(1, 33)
                    c.origin?.let { o.kv(byteArrayOf(0x16) + x, varint(0) + it.bytes()) } // TAP_BIP32_DERIVATION
                    o.kv(byteArrayOf(0x17), x)                                       // TAP_INTERNAL_KEY
                }
                else -> {
                    if (c.type == ScriptType.P2SH_P2WPKH)
                        o.kv(byteArrayOf(0x04), Address.scriptPubKey(c.pubkey, ScriptType.P2WPKH)) // REDEEM_SCRIPT
                    c.origin?.let { o.kv(byteArrayOf(0x06) + c.pubkey, it.bytes()) } // BIP32_DERIVATION
                }
            }
            o.write(0)
        }
        for (i in outputs.indices) {
            change[i]?.let { ch ->
                when (ch.type) {
                    ScriptType.P2TR -> {
                        val x = ch.pubkey.copyOfRange(1, 33)
                        o.kv(byteArrayOf(0x05), x)                                   // TAP_INTERNAL_KEY
                        ch.origin?.let { o.kv(byteArrayOf(0x07) + x, varint(0) + it.bytes()) }
                    }
                    else -> {
                        if (ch.type == ScriptType.P2SH_P2WPKH)
                            o.kv(byteArrayOf(0x00), Address.scriptPubKey(ch.pubkey, ScriptType.P2WPKH))
                        ch.origin?.let { o.kv(byteArrayOf(0x02) + ch.pubkey, it.bytes()) }
                    }
                }
            }
            o.write(0)
        }
        return o.toByteArray()
    }

    // --------------------------------------------------------------------------- decoding

    class Parsed(val global: List<Pair<ByteArray, ByteArray>>, val inputs: List<List<Pair<ByteArray, ByteArray>>>,
                 val outputs: List<List<Pair<ByteArray, ByteArray>>>) {
        val unsignedTx: ByteArray get() = global.first { it.first.contentEquals(byteArrayOf(0x00)) }.second
    }

    /** A file from a signer: a binary PSBT, or the same as text. */
    fun decodeFile(b: ByteArray): ByteArray =
        if (b.size >= 5 && b.copyOfRange(0, 5).contentEquals(MAGIC)) b else decodeText(String(b, Charsets.US_ASCII))

    /** Raw bytes, base64 or hex: whatever the signer handed back. */
    fun decodeText(s: String): ByteArray {
        val t = s.trim()
        if (t.length % 2 == 0 && t.all { it in "0123456789abcdefABCDEF" }) return Hashes.hexToBytes(t)
        return java.util.Base64.getDecoder().decode(t.filterNot { it.isWhitespace() })
    }

    fun parse(b: ByteArray): Parsed {
        require(b.size > 5 && b.copyOfRange(0, 5).contentEquals(MAGIC)) { "This is not a PSBT." }
        var o = 5
        fun varint(): Long {
            val n = b[o++].toInt() and 0xff
            fun r(k: Int): Long { var v = 0L; for (i in 0 until k) v = v or ((b[o + i].toLong() and 0xff) shl (8 * i)); o += k; return v }
            return when (n) { 0xfd -> r(2); 0xfe -> r(4); 0xff -> r(8); else -> n.toLong() }
        }
        fun map(): List<Pair<ByteArray, ByteArray>> {
            val m = mutableListOf<Pair<ByteArray, ByteArray>>()
            while (true) {
                val kl = varint().toInt()
                if (kl == 0) return m
                require(o + kl <= b.size) { "The PSBT is cut short." }
                val k = b.copyOfRange(o, o + kl); o += kl
                val vl = varint().toInt()
                require(vl >= 0 && o + vl <= b.size) { "The PSBT is cut short." }
                val v = b.copyOfRange(o, o + vl); o += vl
                m += k to v
            }
        }
        val global = map()
        val tx = global.firstOrNull { it.first.contentEquals(byteArrayOf(0x00)) }?.second
            ?: error("The PSBT has no transaction in it.")
        val parsed = TxParse.parse(tx.toHex())
        val ins = List(parsed.inputs.size) { map() }
        val outs = List(parsed.outputs.size) { map() }
        return Parsed(global, ins, outs)
    }

    // --------------------------------------------------------------------------- finishing

    private fun der(sig: ByteArray): Ecdsa.Signature {
        // Strict DER: 30 len 02 rlen r 02 slen s.
        require(sig.size >= 8 && sig[0] == 0x30.toByte() && (sig[1].toInt() and 0xff) == sig.size - 2) { "bad signature encoding" }
        var o = 2
        fun int(): BigInteger {
            require(sig[o] == 0x02.toByte()); val l = sig[o + 1].toInt() and 0xff
            require(l in 1..33 && o + 2 + l <= sig.size); val v = BigInteger(1, sig.copyOfRange(o + 2, o + 2 + l)); o += 2 + l; return v
        }
        val r = int(); val s = int()
        require(o == sig.size) { "bad signature encoding" }
        return Ecdsa.Signature(r, s)
    }

    private fun readWitness(v: ByteArray): List<ByteArray> {
        var o = 0
        fun varint(): Int { val n = v[o++].toInt() and 0xff; require(n < 0xfd); return n }
        return List(varint()) { val l = varint(); v.copyOfRange(o, o + l).also { o += l } }
    }

    /**
     * Check what the signer returned and build the transaction to broadcast. [exported] is the
     * PSBT we made (kept since export); the returned one must carry the very same transaction.
     * Every signature must be a unified (0x21) one that verifies for that coin: an old-style
     * signature would also be valid on the SHA-256 chain, so it is refused, never broadcast.
     */
    fun finish(exported: ByteArray, returned: ByteArray, coins: List<Coin>, outputs: List<TxBuilder.Output>): TxBuilder.Signed {
        val mine = parse(exported)
        val theirs = parse(returned)
        require(theirs.unsignedTx.contentEquals(mine.unsignedTx)) {
            "This PSBT is not the one you exported: its transaction is different. Nothing was sent."
        }
        require(mine.unsignedTx.contentEquals(unsignedTx(coins, outputs))) { "internal: the saved request does not match" }
        val inputs = coins.map { it.asInput() }
        val witnesses = coins.mapIndexed { i, c ->
            val m = theirs.inputs[i]
            fun field(t: Int) = m.filter { it.first.isNotEmpty() && it.first[0] == t.toByte() }
            val h = TxBuilder.unifiedSighash(2, inputs, outputs, i, 0)
            val n = i + 1
            if (c.type == ScriptType.P2TR) {
                val sig = field(0x13).firstOrNull()?.second
                    ?: field(0x08).firstOrNull()?.second?.let { readWitness(it).singleOrNull() }
                    ?: error("Input $n is not signed.")
                require(sig.size == 65 && (sig[64].toInt() and 0xff) == SIGHASH) {
                    "Input $n was signed the old way, which is also valid on the SHA-256 chain (replayable). " +
                        "Use a signer that supports the BLAKE2b unified sighash (0x21). Nothing was sent."
                }
                require(Schnorr.verify(Address.taprootOutputKey(c.pubkey), h, sig.copyOfRange(0, 64))) {
                    "Input $n: the signature does not verify. Nothing was sent."
                }
                sig
            } else {
                val sig = field(0x02).firstOrNull { it.first.copyOfRange(1, it.first.size).contentEquals(c.pubkey) }?.second
                    ?: field(0x08).firstOrNull()?.second?.let { w ->
                        readWitness(w).takeIf { it.size == 2 && it[1].contentEquals(c.pubkey) }?.get(0)
                    }
                    ?: error("Input $n is not signed (or signed by another key).")
                require((sig.last().toInt() and 0xff) == SIGHASH) {
                    "Input $n was signed the old way, which is also valid on the SHA-256 chain (replayable). " +
                        "Use a signer that supports the BLAKE2b unified sighash (0x21). Nothing was sent."
                }
                val s = der(sig.copyOfRange(0, sig.size - 1))
                require(s.s <= Secp256k1.N.shiftRight(1)) { "Input $n: high-S signature (non-standard). Nothing was sent." }
                require(Ecdsa.verify(Secp256k1.decompress(c.pubkey), h, s)) {
                    "Input $n: the signature does not verify. Nothing was sent."
                }
                sig
            }
        }
        return TxBuilder.assemble(inputs, outputs, witnesses)
    }

    /** Write [p] back out (tests use it to make altered PSBTs). */
    fun serialize(p: Parsed): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(MAGIC)
        for (m in listOf(p.global) + p.inputs + p.outputs) { m.forEach { (k, v) -> o.kv(k, v) }; o.write(0) }
        return o.toByteArray()
    }

    fun base64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
}
