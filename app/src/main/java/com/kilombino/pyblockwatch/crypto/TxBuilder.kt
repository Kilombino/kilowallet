package com.kilombino.pyblockwatch.crypto

import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Build and sign native-SegWit (P2WPKH) transactions.
 *
 * Every input this wallet spends is P2WPKH. Signing produces one of two digests, chosen by
 * the hash-type byte passed to [witnessSignature]: the BIP-143 segwit sighash (SIGHASH_ALL,
 * what the SHA256d chain requires), or the unified opt-in sighash ([unifiedSighash],
 * SIGHASH_UNIFIED) that the BLAKE2b chain uses so a spend cannot be replayed onto SHA256d.
 * No legacy (pre-segwit) sighash, no script trees. The BIP-143 worked example is the anchor
 * in the tests: the sighash and the witness signature it publishes must come out of this
 * code byte for byte, which pins the whole path (prevout/sequence/output commitments,
 * scriptCode, RFC-6979 ECDSA, DER).
 *
 * Amounts are satoshis. Fee is (sum of inputs − sum of outputs); the caller does coin
 * selection and change, this class does the bytes.
 */
object TxBuilder {

    const val SIGHASH_ALL = 0x01
    const val SIGHASH_NONE = 0x02
    const val SIGHASH_SINGLE = 0x03
    const val SIGHASH_UNIFIED = 0x20
    const val SIGHASH_ANYONECANPAY = 0x80

    /** The low five bits of a hash type: the output commitment (ALL / NONE / SINGLE). */
    private const val SIGHASH_OUTPUT_MASK = 0x1f

    /** The unified message's script-type byte for native segwit (v0). */
    private const val SCRIPT_TYPE_SEGWIT_V0 = 1

    /**
     * The witness program (0x0014‖hash160) that is a spent P2WPKH output's scriptPubKey.
     * Delegates to [Address.scriptPubKey] so the two definitions cannot drift.
     */
    private fun p2wpkhScriptPubKey(pubkey: ByteArray): ByteArray =
        Address.scriptPubKey(pubkey, ScriptType.P2WPKH)

    /**
     * The implied P2PKH scriptCode of a P2WPKH spend, as BIP-143 defines it — which is
     * exactly the P2PKH script of the same key hash.
     */
    private fun p2pkhScriptCode(pubkey: ByteArray): ByteArray =
        Address.scriptPubKey(pubkey, ScriptType.P2PKH)

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
        val scriptCode = p2pkhScriptCode(inp.pubkey)

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

    // ---- unified opt-in sighash (SIGHASH_UNIFIED, script type 1) --------------------

    /**
     * The unified opt-in signature hash for segwit-v0 (P2WPKH) spends, defined by
     * `doc/unified-sighash.md` in Knots `v29.4.1.knots20260508`. Selected by the
     * [SIGHASH_UNIFIED] bit in [hashType]. Committing to every input's amount and
     * scriptPubKey is what makes an opted-in signature invalid on the SHA256d chain (no
     * replay) and what closes CVE-2020-14199.
     *
     * [spentScriptPubKeys] is the scriptPubKey of every output being spent, in input order
     * (a P2WPKH witness program here). [scriptCode] is the signed input's scriptCode as the
     * legacy rules supply it.
     */
    fun unifiedSighash(
        version: Long,
        inputs: List<Input>,
        outputs: List<Output>,
        index: Int,
        locktime: Long,
        hashType: Int,
        spentScriptPubKeys: List<ByteArray>,
        scriptCode: ByteArray,
    ): ByteArray {
        require(hashType in 0x00..0xff) { "hash type is one byte" }
        require(hashType and SIGHASH_UNIFIED != 0) { "not a unified signature hash" }
        require(index in inputs.indices) { "no input at that index" }
        require(spentScriptPubKeys.size == inputs.size) { "one spent scriptPubKey per input" }

        // Aggregates: one SHA-256 each (BIP-143 uses a double SHA-256).
        val prevouts = ByteArrayOutputStream()
        val amounts = ByteArrayOutputStream()
        val scripts = ByteArrayOutputStream()
        val sequences = ByteArrayOutputStream()
        for (i in inputs.indices) {
            val inp = inputs[i]
            prevouts.write(outpoint(inp.txid, inp.vout))
            amounts.write(u64le(inp.value))
            scripts.write(varBytes(spentScriptPubKeys[i]))
            sequences.write(u32le(inp.sequence))
        }
        val outs = ByteArrayOutputStream()
        for (o in outputs) { outs.write(u64le(o.value)); outs.write(varBytes(o.scriptPubKey)) }

        val anyoneCanPay = (hashType and SIGHASH_ANYONECANPAY) != 0
        val base = hashType and SIGHASH_OUTPUT_MASK

        val msg = ByteArrayOutputStream()
        msg.write(0x00)                                 // epoch
        msg.write(hashType)                             // hash type
        msg.write(u32le(version))                       // transaction version
        msg.write(u32le(locktime and 0xffffffffL))      // locktime, zero-extended to 5 bytes
        msg.write(0x00)
        if (!anyoneCanPay) {
            msg.write(Hashes.sha256(prevouts.toByteArray()))
            msg.write(Hashes.sha256(amounts.toByteArray()))
            msg.write(Hashes.sha256(scripts.toByteArray()))
            msg.write(Hashes.sha256(sequences.toByteArray()))
        }
        if (base != SIGHASH_NONE && base != SIGHASH_SINGLE) {   // every other base signs all outputs
            msg.write(Hashes.sha256(outs.toByteArray()))
        }
        msg.write(SCRIPT_TYPE_SEGWIT_V0)                // script type 1 = segwit v0
        if (anyoneCanPay) {
            val inp = inputs[index]
            msg.write(outpoint(inp.txid, inp.vout))
            msg.write(u64le(inp.value))
            msg.write(varBytes(spentScriptPubKeys[index]))
            msg.write(u32le(inp.sequence))
        } else {
            msg.write(u32le(index.toLong()))
        }
        msg.write(varBytes(scriptCode))
        if (base == SIGHASH_SINGLE) {
            require(index < outputs.size) { "SIGHASH_SINGLE with no output at the input's index is invalid" }
            val o = outputs[index]
            val single = ByteArrayOutputStream()
            single.write(u64le(o.value)); single.write(varBytes(o.scriptPubKey))
            msg.write(Hashes.sha256(single.toByteArray()))
        }
        return Hashes.taggedHash("UnifiedSighash", msg.toByteArray())
    }

    /** The DER signature + sighash byte that goes in input [index]'s witness. */
    fun witnessSignature(
        version: Long,
        inputs: List<Input>,
        outputs: List<Output>,
        index: Int,
        locktime: Long,
        hashType: Int = SIGHASH_ALL,
    ): ByteArray {
        require(hashType in 0x00..0xff) { "hash type is one byte" }
        require(hashType == SIGHASH_ALL || hashType and SIGHASH_UNIFIED != 0) {
            "non-unified signatures support SIGHASH_ALL only; set SIGHASH_UNIFIED for any other type"
        }
        val h = if (hashType and SIGHASH_UNIFIED != 0) {
            unifiedSighash(
                version, inputs, outputs, index, locktime, hashType,
                inputs.map { p2wpkhScriptPubKey(it.pubkey) },
                p2pkhScriptCode(inputs[index].pubkey),
            )
        } else {
            sighash(version, inputs, outputs, index, locktime)
        }
        return Ecdsa.der(Ecdsa.sign(inputs[index].privateKey, h)) + byteArrayOf(hashType.toByte())
    }

    /** Build, sign and serialise the whole SegWit transaction. */
    fun build(
        inputs: List<Input>,
        outputs: List<Output>,
        version: Long = 2,
        locktime: Long = 0,
        hashType: Int = SIGHASH_ALL,
    ): Signed {
        require(inputs.isNotEmpty()) { "a transaction needs at least one input" }
        require(outputs.isNotEmpty()) { "a transaction needs at least one output" }

        val witnesses = ArrayList<ByteArray>(inputs.size)
        for (i in inputs.indices) {
            witnesses.add(witnessSignature(version, inputs, outputs, i, locktime, hashType))
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
