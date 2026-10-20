package com.kilombino.pyblockwatch.crypto

import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Spending a JoinMarket fidelity bond from the read-only JoinMarket wallet: the PSBT that a
 * signer holding Jam's seed signs (our SeedSigner fork does), and the finished transaction.
 *
 * The bond's coins sit in a P2WSH of `<locktime> OP_CHECKLOCKTIMEVERIFY OP_DROP <pubkey>
 * OP_CHECKSIG`, so the spend carries nLockTime = the bond's locktime and a non-final sequence
 * (0xfffffffe), and its witness is `<signature> <witness script>`. The input asks for the unified
 * sighash (0x21), which makes the spend invalid on the SHA-256 chain and commits to the amount
 * of every input; it also carries the previous transaction when we have it, for signers that
 * check amounts against it. The key's origin is m/84'/coin'/0'/2/timenumber; the master
 * fingerprint is 00000000 when the export did not carry it, which SeedSigner fills in by
 * deriving the key from its seed and comparing.
 *
 * As with [Psbt.finish], nothing from the signer is trusted but the signature, and it is
 * checked against our own copy of the transaction before anything is broadcast.
 */
object BondPsbt {
    private val MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())
    const val SIGHASH = Psbt.SIGHASH                // 0x21
    const val SEQUENCE = 0xfffffffeL                // enables nLockTime, no RBF signalling needed
    const val VERSION = 2L

    /** The bond coin, as the JoinMarket wallet knows it. */
    class Bond(
        val txid: String, val vout: Int, val value: Long,
        val pubkey: ByteArray, val locktime: Long, val witnessScript: ByteArray,
        val origin: Psbt.Origin,
        /** The raw transaction that created the bond (PSBT_IN_NON_WITNESS_UTXO), if known. */
        val prevTx: ByteArray? = null,
    ) {
        init {
            require(pubkey.size == 33 && witnessScript.isNotEmpty())
            prevTx?.let { raw ->
                val tx = TxParse.parse(raw.toHex())
                require(TxParse.txid(tx) == txid) { "The bond's previous transaction does not match its txid." }
                val out = tx.outputs.getOrNull(vout) ?: error("The bond's previous transaction has no output $vout.")
                require(out.value == value && out.scriptPubKey.contentEquals(spentScript)) {
                    "The bond's previous transaction does not match the bond."
                }
            }
        }
        val spentScript: ByteArray get() = byteArrayOf(0x00, 0x20) + Hashes.sha256(witnessScript)
        internal fun asInput() = TxBuilder.Input(txid, vout, value, BigInteger.ONE, pubkey, SEQUENCE,
            ScriptType.P2WPKH, witnessScript = witnessScript)
    }

    private fun varint(n: Long): ByteArray = when {
        n < 0xfd -> byteArrayOf(n.toByte())
        n <= 0xffff -> byteArrayOf(0xfd.toByte(), n.toByte(), (n ushr 8).toByte())
        else -> byteArrayOf(0xfe.toByte()) + ByteArray(4) { (n ushr (8 * it)).toByte() }
    }
    private fun le(v: Long, n: Int) = ByteArray(n) { ((v ushr (8 * it)) and 0xff).toByte() }
    private fun vb(b: ByteArray) = varint(b.size.toLong()) + b
    private fun ByteArrayOutputStream.kv(key: ByteArray, value: ByteArray) { write(vb(key)); write(vb(value)) }

    fun unsignedTx(bond: Bond, outputs: List<TxBuilder.Output>): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(le(VERSION, 4))
        o.write(1)
        o.write(Hashes.hexToBytes(bond.txid).reversedArray()); o.write(le(bond.vout.toLong(), 4))
        o.write(0); o.write(le(SEQUENCE, 4))
        o.write(varint(outputs.size.toLong()))
        for (out in outputs) { o.write(le(out.value, 8)); o.write(vb(out.scriptPubKey)) }
        o.write(le(bond.locktime, 4))
        return o.toByteArray()
    }

    /** The PSBT for SeedSigner: one bond input, [outputs] as given (usually one, the whole bond less the fee). */
    fun create(bond: Bond, outputs: List<TxBuilder.Output>): ByteArray {
        require(outputs.isNotEmpty())
        require(outputs.sumOf { it.value } < bond.value) { "The outputs add up to more than the bond." }
        val o = ByteArrayOutputStream()
        o.write(MAGIC)
        o.kv(byteArrayOf(0x00), unsignedTx(bond, outputs))
        o.write(0)
        bond.prevTx?.let { o.kv(byteArrayOf(0x00), it) }                              // NON_WITNESS_UTXO
        o.kv(byteArrayOf(0x01), le(bond.value, 8) + vb(bond.spentScript))           // WITNESS_UTXO
        o.kv(byteArrayOf(0x03), le(SIGHASH.toLong(), 4))                            // SIGHASH_TYPE 0x21
        o.kv(byteArrayOf(0x05), bond.witnessScript)                                 // WITNESS_SCRIPT
        o.kv(byteArrayOf(0x06) + bond.pubkey, bond.origin.bytes())                  // BIP32_DERIVATION
        o.write(0)
        repeat(outputs.size) { o.write(0) }
        return o.toByteArray()
    }

    /** The unified sighash the signer must have signed. */
    fun sighash(bond: Bond, outputs: List<TxBuilder.Output>): ByteArray =
        TxBuilder.unifiedSighash(VERSION, listOf(bond.asInput()), outputs, 0, bond.locktime)

    /**
     * Check the signed PSBT and build the transaction to broadcast. The returned PSBT must carry
     * the very transaction we exported, and its signature must be a low-S 0x21 signature by the
     * bond's key over our own sighash.
     */
    fun finish(exported: ByteArray, returned: ByteArray, bond: Bond, outputs: List<TxBuilder.Output>): TxBuilder.Signed {
        val mine = Psbt.parse(exported)
        val theirs = Psbt.parse(returned)
        require(mine.unsignedTx.contentEquals(unsignedTx(bond, outputs))) { "internal: the saved request does not match" }
        require(theirs.unsignedTx.contentEquals(mine.unsignedTx)) {
            "This PSBT is not the one you exported: its transaction is different. Nothing was sent."
        }
        val m = theirs.inputs.single()
        fun field(t: Int) = m.filter { it.first.isNotEmpty() && it.first[0] == t.toByte() }
        val sig = field(0x02).firstOrNull { it.first.copyOfRange(1, it.first.size).contentEquals(bond.pubkey) }?.second
            ?: field(0x08).firstOrNull()?.second?.let { w ->
                Psbt.readWitness(w).takeIf { it.size == 2 && it[1].contentEquals(bond.witnessScript) }?.get(0)
            }
            ?: error("The bond is not signed (or signed by another key).")
        require(sig.isNotEmpty() && (sig.last().toInt() and 0xff) == SIGHASH) {
            "The bond was signed the old way, which is also valid on the SHA-256 chain (replayable). " +
                "Use a signer that supports the BLAKE2b unified sighash (0x21). Nothing was sent."
        }
        val s = Psbt.der(sig.copyOfRange(0, sig.size - 1))
        require(s.s <= Secp256k1.N.shiftRight(1)) { "High-S signature (non-standard). Nothing was sent." }
        require(Ecdsa.verify(Secp256k1.decompress(bond.pubkey), sighash(bond, outputs), s)) {
            "The signature does not verify. Nothing was sent."
        }
        return TxBuilder.assemble(listOf(bond.asInput()), outputs, listOf(sig), VERSION, bond.locktime)
    }

    /** vbytes of the finished spend: one P2WSH bond input (72-byte signature + 41..44-byte script). */
    fun vbytes(bond: Bond, outputs: List<TxBuilder.Output>): Int {
        val base = 4 + 1 + (32 + 4 + 1 + 4) + varint(outputs.size.toLong()).size + outputs.sumOf { 8 + vb(it.scriptPubKey).size } + 4
        val witness = 2 + 1 + (1 + 73) + vb(bond.witnessScript).size
        return (base * 4 + witness + 3) / 4
    }
}
