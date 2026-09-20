package com.kilombino.pyblockwatch.crypto

import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Build and sign native-SegWit (P2WPKH) transactions.
 *
 * Every input this wallet spends is P2WPKH, so signing is the BIP-143 segwit sighash and
 * nothing else — no legacy sighash, no script trees. The BIP-143 worked example is the
 * anchor in the tests: the sighash and the witness signature it publishes must come out of
 * this code byte for byte, which pins the whole path (prevout/sequence/output commitments,
 * scriptCode, RFC-6979 ECDSA, DER).
 *
 * Amounts are satoshis. Fee is (sum of inputs − sum of outputs); the caller does coin
 * selection and change, this class does the bytes.
 *
 * Spends can opt into the unified sighash (see [SIGHASH_UNIFIED]) for replay protection on the
 * BLAKE2b chain; the BIP-143 path stays the default so a SHA-256 spend, which no fork validates,
 * remains legacy SIGHASH_ALL.
 */
object TxBuilder {

    const val SIGHASH_ALL = 0x01

    /**
     * Opt-in unified sighash (`doc/unified-sighash.md`, Knots v29.4.1). Setting this bit picks a
     * different message that a node without the BLAKE2b fork cannot reconstruct, so the signature
     * does not verify there: a spend signed with it on the BLAKE2b chain cannot be replayed onto
     * the shared-history SHA-256 chain. Byte becomes 0x21 (`SIGHASH_UNIFIED or SIGHASH_ALL`).
     */
    const val SIGHASH_UNIFIED = 0x20

    /** A coin being spent, with the key that unlocks it. */
    data class Input(
        val txid: String,          // display (big-endian) txid, as an explorer shows it
        val vout: Int,
        val value: Long,           // satoshis locked in this output
        val privateKey: BigInteger,
        val pubkey: ByteArray,     // 33-byte compressed, must hash to this input's address
        val sequence: Long = 0xffffffffL,
    )

    data class Output(val scriptPubKey: ByteArray, val value: Long)

    data class Signed(val rawHex: String, val txid: String, val weight: Int) {
        /** Virtual size in vbytes, rounded up — what fee rate is quoted against. */
        val vbytes: Int get() = (weight + 3) / 4
    }

    // ---- little-endian / varint writers ---------------------------------------------

    private fun u32le(v: Long): ByteArray = byteArrayOf(
        v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte(),
    )

    private fun u64le(v: Long): ByteArray = ByteArray(8) { ((v ushr (8 * it)) and 0xFF).toByte() }

    private fun varint(n: Long): ByteArray = when {
        n < 0xfd -> byteArrayOf(n.toByte())
        n <= 0xffff -> byteArrayOf(0xfd.toByte(), n.toByte(), (n ushr 8).toByte())
        n <= 0xffffffffL -> byteArrayOf(0xfe.toByte()) + u32le(n)
        else -> byteArrayOf(0xff.toByte()) + u64le(n)
    }

    private fun varBytes(b: ByteArray): ByteArray = varint(b.size.toLong()) + b

    /** The internal (little-endian) outpoint: reversed txid ‖ vout. */
    private fun outpoint(txid: String, vout: Int): ByteArray =
        Hashes.hexToBytes(txid).reversedArray() + u32le(vout.toLong())

    // ---- BIP-143 sighash ------------------------------------------------------------

    /**
     * The BIP-143 sighash for input [index], SIGHASH_ALL. [inputs] supplies every
     * outpoint/sequence (all inputs are committed to), and [index]'s own value + pubkey.
     */
    fun sighash(version: Long, inputs: List<Input>, outputs: List<Output>, index: Int, locktime: Long): ByteArray {
        val prevouts = ByteArrayOutputStream()
        val sequences = ByteArrayOutputStream()
        for (i in inputs) {
            prevouts.write(outpoint(i.txid, i.vout))
            sequences.write(u32le(i.sequence))
        }
        val outs = ByteArrayOutputStream()
        for (o in outputs) { outs.write(u64le(o.value)); outs.write(varBytes(o.scriptPubKey)) }

        val hashPrevouts = Hashes.doubleSha256(prevouts.toByteArray())
        val hashSequence = Hashes.doubleSha256(sequences.toByteArray())
        val hashOutputs = Hashes.doubleSha256(outs.toByteArray())

        val inp = inputs[index]
        // scriptCode for P2WPKH is the P2PKH script of the same key hash.
        val scriptCode = Address.scriptPubKey(inp.pubkey, ScriptType.P2PKH)

        val pre = ByteArrayOutputStream()
        pre.write(u32le(version))
        pre.write(hashPrevouts)
        pre.write(hashSequence)
        pre.write(outpoint(inp.txid, inp.vout))
        pre.write(varBytes(scriptCode))
        pre.write(u64le(inp.value))
        pre.write(u32le(inp.sequence))
        pre.write(hashOutputs)
        pre.write(u32le(locktime))
        pre.write(u32le(SIGHASH_ALL.toLong()))
        return Hashes.doubleSha256(pre.toByteArray())
    }

    // ---- Unified opt-in sighash (anti-replay) ---------------------------------------

    /** Locktime as five little-endian bytes, zero-extended: the unified message widens the field. */
    private fun u40le(v: Long): ByteArray = ByteArray(5) { ((v ushr (8 * it)) and 0xFF).toByte() }

    /**
     * The unified opt-in message for a bare/P2SH (script type 0) or segwit-v0 (script type 1)
     * input, as `doc/unified-sighash.md` specifies it, given the transaction fields directly.
     * Verified byte-for-byte against every script-type-0 and script-type-1 vector (142 of the
     * 166) in `unified_sighash.json`; the two taproot script types are not handled here because
     * this wallet never spends them.
     *
     * Differs from BIP-143: single SHA-256 aggregates (not double); two extra aggregates over
     * every spent amount and every spent scriptPubKey (this is what closes CVE-2020-14199); a
     * 5-byte zero-extended locktime; an epoch byte, a script-type byte and the input index (or,
     * under ANYONECANPAY, this input's own outpoint/amount/script/sequence) in place of BIP-143's
     * per-input block; and a BIP-340 tagged hash instead of the final double SHA-256. Aggregates
     * over sequences stay present under SIGHASH_NONE/SINGLE, unlike the legacy algorithm.
     *
     * [prevouts], [amounts], [spentScripts] and [sequences] are one entry per input in input
     * order; [scriptCode] is the input's, already reduced for any executed OP_CODESEPARATOR.
     */
    internal fun unifiedMessage(
        version: Long, locktime: Long, hashType: Int, scriptType: Int,
        prevouts: List<ByteArray>, amounts: List<Long>, spentScripts: List<ByteArray>,
        sequences: List<Long>, outputs: List<Output>, index: Int, scriptCode: ByteArray,
    ): ByteArray {
        val anyoneCanPay = (hashType and 0x80) != 0
        val base = hashType and 0x1f // 1 ALL, 2 NONE, 3 SINGLE

        val msg = ByteArrayOutputStream()
        msg.write(0x00)                    // epoch
        msg.write(hashType and 0xff)       // hash type, one byte
        msg.write(u32le(version))
        msg.write(u40le(locktime))         // 5-byte, zero-extended
        if (!anyoneCanPay) {
            val p = ByteArrayOutputStream(); prevouts.forEach { p.write(it) }
            val a = ByteArrayOutputStream(); amounts.forEach { a.write(u64le(it)) }
            val s = ByteArrayOutputStream(); spentScripts.forEach { s.write(varBytes(it)) }
            val q = ByteArrayOutputStream(); sequences.forEach { q.write(u32le(it)) }
            msg.write(Hashes.sha256(p.toByteArray())) // sha_prevouts
            msg.write(Hashes.sha256(a.toByteArray())) // sha_amounts
            msg.write(Hashes.sha256(s.toByteArray())) // sha_scripts
            msg.write(Hashes.sha256(q.toByteArray())) // sha_sequences
        }
        if (base != 0x02 && base != 0x03) { // neither NONE nor SINGLE → commit every output
            val o = ByteArrayOutputStream()
            for (out in outputs) { o.write(u64le(out.value)); o.write(varBytes(out.scriptPubKey)) }
            msg.write(Hashes.sha256(o.toByteArray())) // sha_outputs
        }
        msg.write(scriptType and 0xff)     // 0 bare/P2SH, 1 segwit v0
        if (anyoneCanPay) {                // this input, committed directly
            msg.write(prevouts[index])
            msg.write(u64le(amounts[index])); msg.write(varBytes(spentScripts[index]))
            msg.write(u32le(sequences[index]))
        } else {
            msg.write(u32le(index.toLong())) // just the input's position
        }
        msg.write(varBytes(scriptCode))    // script types 0 and 1
        if (base == 0x03) {                // SIGHASH_SINGLE: the output at this input's index
            require(index < outputs.size) { "SIGHASH_SINGLE has no output at the input's index" }
            val out = outputs[index]
            msg.write(Hashes.sha256(u64le(out.value) + varBytes(out.scriptPubKey)))
        }
        return Hashes.taggedHash("UnifiedSighash", msg.toByteArray())
    }

    /**
     * The unified opt-in sighash for input [index], hash type SIGHASH_ALL, script type 1 — the
     * only shape this wallet signs, every input being P2WPKH. Delegates to [unifiedMessage],
     * supplying the P2WPKH spent scriptPubKey (OP_0 hash160) and BIP-143 implied-P2PKH scriptCode.
     */
    fun unifiedSighash(version: Long, inputs: List<Input>, outputs: List<Output>, index: Int, locktime: Long): ByteArray {
        return unifiedMessage(
            version = version, locktime = locktime,
            hashType = SIGHASH_UNIFIED or SIGHASH_ALL, scriptType = 1,
            prevouts = inputs.map { outpoint(it.txid, it.vout) },
            amounts = inputs.map { it.value },
            spentScripts = inputs.map { Address.scriptPubKey(it.pubkey, ScriptType.P2WPKH) },
            sequences = inputs.map { it.sequence },
            outputs = outputs, index = index,
            scriptCode = Address.scriptPubKey(inputs[index].pubkey, ScriptType.P2PKH),
        )
    }

    /** The DER signature + sighash byte that goes in input [index]'s witness. */
    fun witnessSignature(version: Long, inputs: List<Input>, outputs: List<Output>, index: Int, locktime: Long, unified: Boolean = false): ByteArray {
        val h = if (unified) unifiedSighash(version, inputs, outputs, index, locktime)
                else sighash(version, inputs, outputs, index, locktime)
        val hashByte = if (unified) SIGHASH_UNIFIED or SIGHASH_ALL else SIGHASH_ALL
        return Ecdsa.der(Ecdsa.sign(inputs[index].privateKey, h)) + byteArrayOf(hashByte.toByte())
    }

    /**
     * Build, sign and serialise the whole SegWit transaction. When [unified] is set, every input
     * is signed with the opt-in unified sighash (0x21) so the transaction cannot be replayed onto
     * the SHA-256 chain — the caller sets it when broadcasting to the BLAKE2b chain, where the
     * fork makes the message valid; a legacy SHA-256 spend leaves it false.
     */
    fun build(inputs: List<Input>, outputs: List<Output>, version: Long = 2, locktime: Long = 0, unified: Boolean = false): Signed {
        require(inputs.isNotEmpty()) { "a transaction needs at least one input" }
        require(outputs.isNotEmpty()) { "a transaction needs at least one output" }

        val witnesses = ArrayList<ByteArray>(inputs.size)
        for (i in inputs.indices) {
            witnesses.add(witnessSignature(version, inputs, outputs, i, locktime, unified))
        }

        fun writeInputsOutputs(out: ByteArrayOutputStream) {
            out.write(varint(inputs.size.toLong()))
            for (i in inputs) {
                out.write(outpoint(i.txid, i.vout))
                out.write(byteArrayOf(0x00)) // empty scriptSig (witness carries the proof)
                out.write(u32le(i.sequence))
            }
            out.write(varint(outputs.size.toLong()))
            for (o in outputs) { out.write(u64le(o.value)); out.write(varBytes(o.scriptPubKey)) }
        }

        // Non-witness serialisation, for the txid.
        val legacy = ByteArrayOutputStream()
        legacy.write(u32le(version))
        writeInputsOutputs(legacy)
        legacy.write(u32le(locktime))
        val legacyBytes = legacy.toByteArray()
        val txid = Hashes.doubleSha256(legacyBytes).reversedArray().toHex()

        // Full segwit serialisation, with marker/flag and the witness stack.
        val full = ByteArrayOutputStream()
        full.write(u32le(version))
        full.write(byteArrayOf(0x00, 0x01)) // segwit marker + flag
        writeInputsOutputs(full)
        for (i in inputs.indices) {
            full.write(varint(2))                       // two witness items: signature, pubkey
            full.write(varBytes(witnesses[i]))
            full.write(varBytes(inputs[i].pubkey))
        }
        full.write(u32le(locktime))
        val fullBytes = full.toByteArray()

        // weight = base*3 + total (BIP-141): base = non-witness, total = full serialisation.
        val weight = legacyBytes.size * 3 + fullBytes.size
        return Signed(fullBytes.toHex(), txid, weight)
    }
}
