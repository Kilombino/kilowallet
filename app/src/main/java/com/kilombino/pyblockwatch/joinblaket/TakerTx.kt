package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.Secp256k1
import com.kilombino.pyblockwatch.crypto.TxBuilder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.SecureRandom

/** Reorders the inputs and outputs of a CoinJoin; production uses [Secure], vectors [None]. */
interface Shuffler {
    fun <T> shuffle(list: MutableList<T>)

    /** CSPRNG Fisher-Yates, as `secure_random.shuffle` in `taker/tx_builder.py`. */
    object Secure : Shuffler {
        private val rng = SecureRandom()
        override fun <T> shuffle(list: MutableList<T>) = java.util.Collections.shuffle(list, rng)
    }

    /** Keeps insertion order (only for reproducing the vectors). */
    object None : Shuffler {
        override fun <T> shuffle(list: MutableList<T>) {}
    }
}

/**
 * The CoinJoin transaction from the taker's side (`taker/tx_builder.py`,
 * `jmwallet/wallet/signing.py`): build the unsigned tx, the unified 0x21 sighash of each input,
 * sign our P2WPKH inputs, verify the makers' signatures, assemble and serialize.
 */
object TakerTx {
    const val VERSION = 2L
    const val SIGHASH_UNIFIED_ALL = TxBuilder.SIGHASH_UNIFIED or TxBuilder.SIGHASH_ALL
    /** Non-final, non-RBF sequence used when nLockTime != 0. */
    const val LOCKTIME_SEQUENCE = 0xfffffffeL

    /** A spent output: outpoint plus the value and scriptPubKey it locks (ours, or our lookup of a maker's). */
    data class Utxo(val txid: String, val vout: Long, val value: Long, val scriptPubKey: ByteArray) {
        val outpoint: Pair<String, Long> get() = txid.lowercase() to vout
    }

    /** One maker's part, after its `!ioauth` passed the checks. */
    data class MakerPart(
        val nick: String, val utxos: List<Utxo>, val cjAddress: String, val changeAddress: String,
        val cjfee: Long, val txfee: Long,
    )

    /** Who an output belongs to and whether it is the CoinJoin or the change output. */
    data class OutputOwner(val owner: String, val kind: String)

    /** The unsigned CoinJoin plus its ownership metadata. */
    class Unsigned(
        val inputs: List<Utxo>,
        val outputs: List<TxBuilder.Output>,
        val locktime: Long,
        val inputOwners: List<String>,
        val outputOwners: List<OutputOwner>,
    ) {
        val sequence: Long get() = if (locktime != 0L) LOCKTIME_SEQUENCE else 0xffffffffL

        /** Version 2, no marker/flag/witness: the bytes `!tx` carries (base64) and makers parse. */
        fun serialize(): ByteArray = TakerTx.serialize(this, null)

        fun txid(): String = Hashes.doubleSha256(serialize()).reversedArray().toHex()

        /** Index of the input spending [txid]:[vout], or -1. */
        fun indexOf(txid: String, vout: Long): Int = inputs.indexOfFirst { it.outpoint == (txid.lowercase() to vout) }
    }

    /**
     * Anti-fee-sniping nLockTime (`compute_tx_locktime`): 0 at height 0, else with probability
     * 1/10 `max(1, height - randint(0, 99))`, otherwise `height`. [randint] is inclusive.
     */
    fun locktime(height: Long, randint: (Int, Int) -> Int = { a, b -> a + SecureRandom().nextInt(b - a + 1) }): Long {
        require(height in 0..0xffffffffL) { "invalid height" }
        if (height == 0L) return 0
        if (randint(0, 9) == 0) return maxOf(1L, height - randint(0, 99))
        return height
    }

    /**
     * `build_coinjoin_tx`: inputs = ours then each maker's (in [makers] order); outputs = our cj,
     * makers' cj, our change (only if > 2730 and [takerChangeAddress] is set), makers' change
     * (only if >= 27300); then [shuffler] reorders inputs and outputs. Throws if a maker's
     * change is negative.
     */
    fun build(
        takerUtxos: List<Utxo>, takerCjAddress: String, takerChangeAddress: String?,
        makers: List<MakerPart>, cjAmount: Long, txFee: Long, locktime: Long,
        shuffler: Shuffler = Shuffler.Secure,
    ): Unsigned {
        val ins = ArrayList<Pair<Utxo, String>>()
        takerUtxos.forEach { ins.add(it to "taker") }
        makers.forEach { m -> m.utxos.forEach { ins.add(it to m.nick) } }

        val outs = ArrayList<Pair<TxBuilder.Output, OutputOwner>>()
        fun out(addr: String, value: Long) = TxBuilder.Output(Address.decodeToScriptPubKey(addr), value)
        outs.add(out(takerCjAddress, cjAmount) to OutputOwner("taker", "cj"))
        makers.forEach { outs.add(out(it.cjAddress, cjAmount) to OutputOwner(it.nick, "cj")) }
        val takerChange = Fees.takerChange(takerUtxos.sumOf { it.value }, cjAmount, makers.map { it.cjfee }, txFee)
        if (takerChange > Fees.TAKER_DUST && !takerChangeAddress.isNullOrEmpty()) {
            outs.add(out(takerChangeAddress, takerChange) to OutputOwner("taker", "change"))
        }
        for (m in makers) {
            val change = Fees.makerChange(m.utxos.sumOf { it.value }, cjAmount, m.txfee, m.cjfee)
            require(change >= 0) { "maker ${m.nick} has insufficient funds" }
            if (change >= Fees.MAKER_DUST) outs.add(out(m.changeAddress, change) to OutputOwner(m.nick, "change"))
        }
        shuffler.shuffle(ins); shuffler.shuffle(outs)
        return Unsigned(ins.map { it.first }, outs.map { it.first }, locktime, ins.map { it.second }, outs.map { it.second })
    }

    /** The P2WPKH scriptCode `76 a9 14 <hash160(pub)> 88 ac`. */
    fun scriptCode(pubkey: ByteArray): ByteArray =
        byteArrayOf(0x76, 0xa9.toByte(), 0x14) + Hashes.hash160(pubkey) + byteArrayOf(0x88.toByte(), 0xac.toByte())

    private fun outpointBytes(u: Utxo) = Hashes.hexToBytes(u.txid).reversedArray() + Wire.le(u.vout, 4)

    /** The 202-byte unified message (segwit v0, 0x21) of input [index] signed with [pubkey]'s scriptCode. */
    fun unifiedPreimage(tx: Unsigned, index: Int, pubkey: ByteArray): ByteArray = TxBuilder.unifiedPreimage(
        version = VERSION, locktime = tx.locktime, hashType = SIGHASH_UNIFIED_ALL, scriptType = 1,
        prevouts = tx.inputs.map(::outpointBytes), amounts = tx.inputs.map { it.value },
        spentScripts = tx.inputs.map { it.scriptPubKey }, sequences = tx.inputs.map { tx.sequence },
        outputs = tx.outputs, index = index, scriptCode = scriptCode(pubkey),
    )

    /** `TaggedHash("UnifiedSighash", preimage)`: what input [index] signs. */
    fun sighash(tx: Unsigned, index: Int, pubkey: ByteArray): ByteArray =
        Hashes.taggedHash("UnifiedSighash", unifiedPreimage(tx, index, pubkey))

    /** `DER || 0x21` for our P2WPKH input [index] (RFC 6979, no low-R grinding, low-S). */
    fun signInput(tx: Unsigned, index: Int, privateKey: BigInteger): ByteArray {
        val pub = Secp256k1.compress(Secp256k1.publicPoint(privateKey))
        require(p2wpkh(pub).contentEquals(tx.inputs[index].scriptPubKey)) { "key does not own input $index" }
        return JmSig.signHash(privateKey, sighash(tx, index, pub)) + byteArrayOf(SIGHASH_UNIFIED_ALL.toByte())
    }

    /** P2WPKH scriptPubKey of a compressed key. */
    fun p2wpkh(pubkey: ByteArray): ByteArray = byteArrayOf(0x00, 0x14) + Hashes.hash160(pubkey)

    /**
     * A maker's witness for input [index]: [pubkey] must own the input as P2WPKH and [sig] must
     * be strict DER, low-S, valid over the unified sighash, with hash type 0x21 and nothing else
     * (a 0x01 signature would be replayable on the SHA-256 chain).
     */
    fun verifyInput(tx: Unsigned, index: Int, sig: ByteArray, pubkey: ByteArray): Boolean {
        if (sig.isEmpty() || (sig.last().toInt() and 0xFF) != SIGHASH_UNIFIED_ALL) return false
        if (pubkey.size != 33 || !p2wpkh(pubkey).contentEquals(tx.inputs[index].scriptPubKey)) return false
        return JmSig.verifyHash(sighash(tx, index, pubkey), sig.copyOfRange(0, sig.size - 1), pubkey)
    }

    /**
     * Match one maker's `!sig` witnesses to its inputs as the taker does (each signature tried
     * against the maker's not-yet-matched inputs in tx order). Returns input index to witness,
     * or null unless every one of the maker's inputs got exactly one verified signature.
     */
    fun matchMakerSignatures(tx: Unsigned, nick: String, sigs: List<Pair<ByteArray, ByteArray>>): Map<Int, Pair<ByteArray, ByteArray>>? {
        val mine = tx.inputOwners.indices.filter { tx.inputOwners[it] == nick }
        val matched = LinkedHashMap<Int, Pair<ByteArray, ByteArray>>()
        for ((sig, pub) in sigs) {
            val idx = mine.firstOrNull { it !in matched && verifyInput(tx, it, sig, pub) } ?: continue
            matched[idx] = sig to pub
        }
        return if (mine.isNotEmpty() && matched.size == mine.size) matched else null
    }

    /**
     * The signed transaction: segwit serialization with witness `[sig, pubkey]` per input. Every
     * input must have one; a CoinJoin with an unsigned input must never be broadcast.
     */
    fun assemble(tx: Unsigned, witnesses: Map<Int, Pair<ByteArray, ByteArray>>): ByteArray {
        require(tx.inputs.indices.all { it in witnesses }) { "every input needs a signature" }
        return serialize(tx, witnesses)
    }

    private fun serialize(tx: Unsigned, witnesses: Map<Int, Pair<ByteArray, ByteArray>>?): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(Wire.le(VERSION, 4))
        if (witnesses != null) o.write(byteArrayOf(0x00, 0x01))
        o.write(Wire.compactSize(tx.inputs.size.toLong()))
        for (i in tx.inputs) { o.write(outpointBytes(i)); o.write(0); o.write(Wire.le(tx.sequence, 4)) }
        o.write(Wire.compactSize(tx.outputs.size.toLong()))
        for (out in tx.outputs) {
            o.write(Wire.le(out.value, 8)); o.write(Wire.compactSize(out.scriptPubKey.size.toLong())); o.write(out.scriptPubKey)
        }
        if (witnesses != null) for (i in tx.inputs.indices) {
            val (sig, pub) = witnesses.getValue(i)
            o.write(2)
            o.write(Wire.compactSize(sig.size.toLong())); o.write(sig)
            o.write(Wire.compactSize(pub.size.toLong())); o.write(pub)
        }
        o.write(Wire.le(tx.locktime, 4))
        return o.toByteArray()
    }
}
