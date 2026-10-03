package com.kilombino.pyblockwatch.crypto

import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Build and sign transactions.
 *
 * The wallet's own coins are P2WPKH, signed with the BIP-143 segwit sighash. Sweeping a
 * private key also spends the older single-key forms: nested SegWit (P2SH-P2WPKH, the same
 * BIP-143 signature plus a redeem script), legacy P2PKH (the original sighash, with a
 * compressed or uncompressed key) and Taproot by key path (BIP-341 sighash, BIP-340 Schnorr,
 * BIP-86 output key). No script trees. The BIP-143 worked example is the
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
        val pubkey: ByteArray,     // 33-byte compressed (65-byte uncompressed only for P2PKH)
        val sequence: Long = 0xffffffffL,
        val type: ScriptType = ScriptType.P2WPKH,
    ) {
        init {
            require(pubkey.size == 33 || (pubkey.size == 65 && type == ScriptType.P2PKH)) {
                "SegWit inputs need a compressed public key"
            }
        }
        val isSegwit: Boolean get() = type != ScriptType.P2PKH
        /** The scriptPubKey of the output being spent. */
        val spentScript: ByteArray get() = Address.scriptPubKey(pubkey, type)
    }

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

    // ---- Legacy sighash (P2PKH) ----------------------------------------------------

    /**
     * The original sighash for a legacy P2PKH input [index], SIGHASH_ALL: the transaction
     * with this input's scriptSig replaced by its scriptCode and every other one emptied.
     */
    fun legacySighash(version: Long, inputs: List<Input>, outputs: List<Output>, index: Int, locktime: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u32le(version))
        out.write(varint(inputs.size.toLong()))
        inputs.forEachIndexed { j, i ->
            out.write(outpoint(i.txid, i.vout))
            out.write(if (j == index) varBytes(i.spentScript) else byteArrayOf(0x00))
            out.write(u32le(i.sequence))
        }
        out.write(varint(outputs.size.toLong()))
        for (o in outputs) { out.write(u64le(o.value)); out.write(varBytes(o.scriptPubKey)) }
        out.write(u32le(locktime))
        out.write(u32le(SIGHASH_ALL.toLong()))
        return Hashes.doubleSha256(out.toByteArray())
    }

    // ---- BIP-341 Taproot key-path sighash ------------------------------------------

    /**
     * BIP-341 signature message hash for a key-path spend of input [index], no annex.
     * [hashType] 0x00 is SIGHASH_DEFAULT (sign everything, 64-byte signature).
     */
    fun taprootSighash(
        version: Long, inputs: List<Input>, outputs: List<Output>, index: Int, locktime: Long, hashType: Int = 0,
    ): ByteArray = taprootMessage(
        version, locktime, hashType,
        prevouts = inputs.map { outpoint(it.txid, it.vout) }, amounts = inputs.map { it.value },
        spentScripts = inputs.map { it.spentScript }, sequences = inputs.map { it.sequence },
        outputs = outputs, index = index,
    )

    /** [taprootSighash] from the raw per-input fields, as the BIP-341 vectors give them. */
    internal fun taprootMessage(
        version: Long, locktime: Long, hashType: Int,
        prevouts: List<ByteArray>, amounts: List<Long>, spentScripts: List<ByteArray>,
        sequences: List<Long>, outputs: List<Output>, index: Int,
    ): ByteArray {
        val anyoneCanPay = (hashType and 0x80) != 0
        val base = hashType and 0x03
        val msg = ByteArrayOutputStream()
        msg.write(0x00)                       // epoch
        msg.write(hashType and 0xff)
        msg.write(u32le(version))
        msg.write(u32le(locktime))
        if (!anyoneCanPay) {
            val p = ByteArrayOutputStream(); prevouts.forEach { p.write(it) }
            val a = ByteArrayOutputStream(); amounts.forEach { a.write(u64le(it)) }
            val s = ByteArrayOutputStream(); spentScripts.forEach { s.write(varBytes(it)) }
            val q = ByteArrayOutputStream(); sequences.forEach { q.write(u32le(it)) }
            msg.write(Hashes.sha256(p.toByteArray()))
            msg.write(Hashes.sha256(a.toByteArray()))
            msg.write(Hashes.sha256(s.toByteArray()))
            msg.write(Hashes.sha256(q.toByteArray()))
        }
        if (base != 0x02 && base != 0x03) {
            val o = ByteArrayOutputStream()
            for (out in outputs) { o.write(u64le(out.value)); o.write(varBytes(out.scriptPubKey)) }
            msg.write(Hashes.sha256(o.toByteArray()))
        }
        msg.write(0x00)                       // spend type: key path, no annex
        if (anyoneCanPay) {
            msg.write(prevouts[index]); msg.write(u64le(amounts[index]))
            msg.write(varBytes(spentScripts[index])); msg.write(u32le(sequences[index]))
        } else {
            msg.write(u32le(index.toLong()))
        }
        if (base == 0x03) {
            require(index < outputs.size) { "SIGHASH_SINGLE has no output at the input's index" }
            val out = outputs[index]
            msg.write(Hashes.sha256(u64le(out.value) + varBytes(out.scriptPubKey)))
        }
        return Hashes.taggedHash("TapSighash", msg.toByteArray())
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
        msg.write(scriptType and 0xff)     // 0 bare/P2SH, 1 segwit v0, 2 taproot key path
        if (anyoneCanPay) {                // this input, committed directly
            msg.write(prevouts[index])
            msg.write(u64le(amounts[index])); msg.write(varBytes(spentScripts[index]))
            msg.write(u32le(sequences[index]))
        } else {
            msg.write(u32le(index.toLong())) // just the input's position
        }
        if (scriptType <= 1) msg.write(varBytes(scriptCode)) // script types 0 and 1
        else msg.write(0x00)               // taproot: no annex
        if (base == 0x03) {                // SIGHASH_SINGLE: the output at this input's index
            require(index < outputs.size) { "SIGHASH_SINGLE has no output at the input's index" }
            val out = outputs[index]
            msg.write(Hashes.sha256(u64le(out.value) + varBytes(out.scriptPubKey)))
        }
        return Hashes.taggedHash("UnifiedSighash", msg.toByteArray())
    }

    /**
     * The unified opt-in sighash for input [index], hash type SIGHASH_ALL: script type 1 for a
     * SegWit v0 input (P2WPKH, also when nested in P2SH) and 0 for legacy P2PKH. Delegates to
     * [unifiedMessage] with every input's spent scriptPubKey and the key's P2PKH scriptCode.
     */
    fun unifiedSighash(version: Long, inputs: List<Input>, outputs: List<Output>, index: Int, locktime: Long): ByteArray {
        return unifiedMessage(
            version = version, locktime = locktime,
            hashType = SIGHASH_UNIFIED or SIGHASH_ALL,
            scriptType = when (inputs[index].type) { ScriptType.P2PKH -> 0; ScriptType.P2TR -> 2; else -> 1 },
            prevouts = inputs.map { outpoint(it.txid, it.vout) },
            amounts = inputs.map { it.value },
            spentScripts = inputs.map { it.spentScript },
            sequences = inputs.map { it.sequence },
            outputs = outputs, index = index,
            scriptCode = Address.scriptPubKey(inputs[index].pubkey, ScriptType.P2PKH),
        )
    }

    /**
     * The DER signature + sighash byte for input [index], for its witness or its scriptSig.
     * [grindLowR] retries the nonce like Bitcoin Core so the signature matches Core's.
     */
    fun witnessSignature(
        version: Long, inputs: List<Input>, outputs: List<Output>, index: Int, locktime: Long,
        unified: Boolean = false, grindLowR: Boolean = false, auxRand: ByteArray? = null,
    ): ByteArray {
        if (inputs[index].type == ScriptType.P2TR) {
            // Key path: SIGHASH_DEFAULT (64 bytes, no hash byte) or the unified opt-in 0x21.
            val h = if (unified) unifiedSighash(version, inputs, outputs, index, locktime)
                    else taprootSighash(version, inputs, outputs, index, locktime)
            val secret = Schnorr.tweakedSecret(inputs[index].privateKey)
            val sig = if (auxRand != null) Schnorr.sign(secret, h, auxRand) else Schnorr.sign(secret, h)
            return if (unified) sig + byteArrayOf((SIGHASH_UNIFIED or SIGHASH_ALL).toByte()) else sig
        }
        val h = when {
            unified -> unifiedSighash(version, inputs, outputs, index, locktime)
            inputs[index].isSegwit -> sighash(version, inputs, outputs, index, locktime)
            else -> legacySighash(version, inputs, outputs, index, locktime)
        }
        val hashByte = if (unified) SIGHASH_UNIFIED or SIGHASH_ALL else SIGHASH_ALL
        return Ecdsa.der(Ecdsa.sign(inputs[index].privateKey, h, grindLowR)) + byteArrayOf(hashByte.toByte())
    }

    /** One data push of up to 75 bytes, enough for a signature, a key or a redeem script. */
    private fun push(b: ByteArray): ByteArray { require(b.size <= 75); return byteArrayOf(b.size.toByte()) + b }

    /**
     * Build, sign and serialise the whole SegWit transaction. When [unified] is set, every input
     * is signed with the opt-in unified sighash (0x21) so the transaction cannot be replayed onto
     * the SHA-256 chain — the caller sets it when broadcasting to the BLAKE2b chain, where the
     * fork makes the message valid; a legacy SHA-256 spend leaves it false.
     */
    fun build(
        inputs: List<Input>, outputs: List<Output>, version: Long = 2, locktime: Long = 0,
        unified: Boolean = false, grindLowR: Boolean = false, auxRand: ByteArray? = null,
    ): Signed {
        require(inputs.isNotEmpty()) { "a transaction needs at least one input" }
        require(outputs.isNotEmpty()) { "a transaction needs at least one output" }

        val witnesses = ArrayList<ByteArray>(inputs.size)
        for (i in inputs.indices) {
            witnesses.add(witnessSignature(version, inputs, outputs, i, locktime, unified, grindLowR, auxRand))
        }
        // P2WPKH proves everything in the witness; nested SegWit puts its redeem script in the
        // scriptSig; legacy P2PKH carries signature and key in the scriptSig and no witness.
        val scriptSigs = inputs.mapIndexed { i, inp ->
            when (inp.type) {
                ScriptType.P2WPKH, ScriptType.P2TR -> ByteArray(0)
                ScriptType.P2SH_P2WPKH -> push(Address.scriptPubKey(inp.pubkey, ScriptType.P2WPKH))
                else -> push(witnesses[i]) + push(inp.pubkey)
            }
        }
        val anySegwit = inputs.any { it.isSegwit }

        fun writeInputsOutputs(out: ByteArrayOutputStream) {
            out.write(varint(inputs.size.toLong()))
            inputs.forEachIndexed { j, i ->
                out.write(outpoint(i.txid, i.vout))
                out.write(varBytes(scriptSigs[j]))
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

        // A transaction with only legacy inputs has no witness at all.
        if (!anySegwit) return Signed(legacyBytes.toHex(), txid, legacyBytes.size * 4)

        // Full segwit serialisation, with marker/flag and the witness stack.
        val full = ByteArrayOutputStream()
        full.write(u32le(version))
        full.write(byteArrayOf(0x00, 0x01)) // segwit marker + flag
        writeInputsOutputs(full)
        for (i in inputs.indices) {
            if (!inputs[i].isSegwit) { full.write(varint(0)); continue } // legacy: empty witness
            if (inputs[i].type == ScriptType.P2TR) {                     // key path: the signature only
                full.write(varint(1)); full.write(varBytes(witnesses[i])); continue
            }
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
