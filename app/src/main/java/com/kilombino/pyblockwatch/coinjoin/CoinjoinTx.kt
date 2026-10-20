package com.kilombino.pyblockwatch.coinjoin

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Ecdsa
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.Schnorr
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import com.kilombino.pyblockwatch.crypto.TxBuilder
import java.math.BigInteger
import kotlin.math.ceil

/**
 * The transaction side of a coinjoin, kept free of networking so it can be tested alone.
 *
 * Shape: one or more inputs per participant, all of the pool's one address type (native
 * SegWit P2WPKH, or Taproot P2TR by key path, never both); one mix output of exactly the pool
 * amount per participant, all identical; and a change output per participant who has any, of
 * that same type. Each person pays their own share of the miner fee (their inputs, their
 * outputs, part of the overhead) out of their change, so the mix outputs stay equal and nobody
 * pays for anyone else.
 *
 * Ordering is canonical (inputs by outpoint, mix outputs by script, then the change outputs
 * by script), so every participant builds the very same transaction and the same txid, and
 * no position gives away whose output is whose.
 *
 * Signatures are the unified sighash with SIGHASH_ALL (0x21): every input signs every input
 * and every output, so a signature cannot be reused in any other transaction, and the spend is
 * valid only on the BLAKE2b chain (no replay onto the SHA-256 spamchain).
 */
object CoinjoinTx {
    const val VERSION = 2L
    const val SEQUENCE = 0xfffffffdL
    /** Same threshold as the wallet's sends: below it, change goes to the fee instead. */
    const val DUST = 294L
    private const val OVERHEAD_VB = 10.5

    /**
     * Input vbytes: P2WPKH 68 (72-byte signature at most); Taproot key path 57.75 (a 64-byte
     * Schnorr signature plus the unified sighash byte).
     */
    fun inputVb(type: ScriptType): Double = if (type == ScriptType.P2TR) 57.75 else 68.0
    /** Output vbytes: P2WPKH 31, P2TR 43. */
    fun outputVb(type: ScriptType): Double = if (type == ScriptType.P2TR) 43.0 else 31.0

    /** A participant's registered input, as everybody sees it ([type] is the pool's). */
    data class Coin(val txid: String, val vout: Int, val value: Long, val pubkey: ByteArray,
                    val type: ScriptType = ScriptType.P2WPKH) {
        val outpoint: String get() = "$txid:$vout"
        /** For Taproot, [pubkey] is the BIP-86 internal key; the script holds its tweaked output key. */
        val script: ByteArray get() = Address.scriptPubKey(pubkey, type)
    }

    /**
     * What each participant pays the miners: their [inputs], their [outputs] mix outputs (one
     * before v4), their change and half the transaction overhead (the share in a pool of two, the
     * most anyone can owe). Fixed per person and known at join time, so the change can be signed
     * there and then.
     */
    fun feeShare(feeRate: Double, withChange: Boolean = true, inputs: Int = 1, type: ScriptType = ScriptType.P2WPKH, outputs: Int = 1): Long =
        ceil(feeRate * (inputVb(type) * inputs + outputVb(type) * outputs + (if (withChange) outputVb(type) else 0.0) + OVERHEAD_VB / 2)).toLong()

    /**
     * The change for [inputValue] (all of a person's coins together) in a pool of [amount] at
     * [feeRate], or 0 when it would be dust (the dust then goes to the fee). Null if the coins
     * are too small for the pool.
     */
    fun change(inputValue: Long, amount: Long, feeRate: Double, inputs: Int = 1, type: ScriptType = ScriptType.P2WPKH, outputs: Int = 1): Long? {
        val withChange = inputValue - amount * outputs - feeShare(feeRate, true, inputs, type, outputs)
        if (withChange > DUST) return withChange
        val without = inputValue - amount * outputs - feeShare(feeRate, false, inputs, type, outputs)
        return if (without >= 0) 0L else null
    }

    /**
     * The smallest single coin that can join a pool (its perfect coin): [outputs] times the amount
     * plus the fee share without change.
     */
    fun minimumCoin(amount: Long, feeRate: Double, type: ScriptType = ScriptType.P2WPKH, outputs: Int = 1): Long =
        amount * outputs + feeShare(feeRate, false, 1, type, outputs)

    data class Plan(
        val coins: List<Coin>,
        val outputs: List<TxBuilder.Output>,
    ) {
        val inputs: List<TxBuilder.Input> get() = coins.map {
            // The private key is never read when only assembling or checking; it is a placeholder.
            TxBuilder.Input(it.txid, it.vout, it.value, BigInteger.ONE, it.pubkey, SEQUENCE, it.type)
        }
        val fee: Long get() = coins.sumOf { it.value } - outputs.sumOf { it.value }
    }

    private fun cmp(a: ByteArray, b: ByteArray): Int = a.toHex().compareTo(b.toHex())

    /**
     * The canonical transaction: [mixScripts] get [amount] each ([perPerson] of them for each
     * person, v4), every (script, value) in [changes] its change. Throws if the pieces do not add
     * up to a sane coinjoin.
     */
    fun plan(coins: List<Coin>, mixScripts: List<ByteArray>, changes: List<TxBuilder.Output>, amount: Long, perPerson: Int = 1): Plan {
        require(perPerson >= 1 && mixScripts.size % perPerson == 0) { "every person gets the same number of mixed outputs" }
        val people = mixScripts.size / perPerson
        require(people >= 2) { "a coinjoin needs at least two people" }
        require(coins.size >= people) { "every person brings at least one coin" }
        require(coins.map { it.outpoint }.toSet().size == coins.size) { "the same coin was registered twice" }
        // One address type for everything: a P2WPKH coin or output in a Taproot round (or the
        // other way round) would point at its owner.
        val type = coins.first().type
        require(coins.all { it.type == type }) { "coins of two address types" }
        require((mixScripts + changes.map { it.scriptPubKey }).all { isType(it, type) }) { "an output of another address type" }
        val allScripts = (mixScripts + changes.map { it.scriptPubKey }).map { it.toHex() }
        require(allScripts.toSet().size == allScripts.size) { "an address appears twice" }
        val inputScripts = coins.map { it.script.toHex() }.toSet()
        require(allScripts.none { it in inputScripts }) { "an output pays back to an input address" }
        require(changes.all { it.value > DUST }) { "a change output is dust" }
        val outs = mixScripts.sortedWith(::cmp).map { TxBuilder.Output(it, amount) } +
            changes.sortedWith { a, b -> cmp(a.scriptPubKey, b.scriptPubKey) }
        val p = Plan(coins.sortedWith(compareBy({ it.txid }, { it.vout })), outs)
        require(p.fee >= 0) { "the outputs spend more than the inputs" }
        return p
    }

    /** True when [script] is an output of [type]: `0014<20>` for P2WPKH, `5120<32>` for P2TR. */
    fun isType(script: ByteArray, type: ScriptType): Boolean = when (type) {
        ScriptType.P2TR -> script.size == 34 && script[0] == 0x51.toByte() && script[1] == 0x20.toByte()
        else -> script.size == 22 && script[0] == 0.toByte() && script[1] == 0x14.toByte()
    }

    /** Our signature for our input [mine] in [plan]: unified sighash, SIGHASH_ALL (ECDSA, or Schnorr for Taproot). */
    fun sign(plan: Plan, mine: Coin, privateKey: BigInteger): ByteArray {
        val inputs = plan.inputs.toMutableList()
        val i = plan.coins.indexOfFirst { it.outpoint == mine.outpoint }
        require(i >= 0) { "our coin is not in the transaction" }
        inputs[i] = inputs[i].copy(privateKey = privateKey)
        return TxBuilder.witnessSignature(VERSION, inputs, plan.outputs, i, 0, unified = true, grindLowR = true)
    }

    /** True when [sig] is a valid unified SIGHASH_ALL signature of [coin]'s input in [plan]. */
    fun verify(plan: Plan, coin: Coin, sig: ByteArray): Boolean = runCatching {
        val i = plan.coins.indexOfFirst { it.outpoint == coin.outpoint }
        if (i < 0 || sig.isEmpty()) return false
        if ((sig.last().toInt() and 0xFF) != (TxBuilder.SIGHASH_UNIFIED or TxBuilder.SIGHASH_ALL)) return false
        val h = TxBuilder.unifiedSighash(VERSION, plan.inputs, plan.outputs, i, 0)
        if (coin.type == ScriptType.P2TR) {
            // Key path: 64-byte Schnorr + the hash byte, against the tweaked output key.
            sig.size == 65 && Schnorr.verify(coin.script.copyOfRange(2, 34), h, sig.copyOfRange(0, 64))
        } else Ecdsa.verify(Secp256k1.decompress(coin.pubkey), h, parseDer(sig.copyOfRange(0, sig.size - 1)))
    }.getOrDefault(false)

    /** The txid [plan] will have: it does not cover the witnesses, so it is known before signing. */
    fun txid(plan: Plan): String =
        TxBuilder.assemble(plan.inputs, plan.outputs, plan.coins.map { ByteArray(72) }, VERSION, 0).txid

    /** The final transaction from everyone's signatures, keyed by outpoint. */
    fun assemble(plan: Plan, sigs: Map<String, ByteArray>): TxBuilder.Signed {
        val w = plan.coins.map { sigs[it.outpoint] ?: error("missing the signature of ${it.outpoint}") }
        return TxBuilder.assemble(plan.inputs, plan.outputs, w, VERSION, 0)
    }

    /**
     * What we check before signing: our mix output is there with the full amount, our change
     * is exactly what we expect, and the fee is in a sane range for the pool's rate.
     */
    fun checkOurs(plan: Plan, mixScript: ByteArray, change: TxBuilder.Output?, amount: Long, feeRate: Double,
                  seatInputs: List<Int> = plan.coins.map { 1 }): String? =
        checkOurs(plan, listOf(mixScript), change, amount, feeRate, seatInputs, 1)

    /** The same for a v4 round: each of our [mixScripts] once, [perPerson] mixed outputs for every seat. */
    fun checkOurs(plan: Plan, mixScripts: List<ByteArray>, change: TxBuilder.Output?, amount: Long, feeRate: Double,
                  seatInputs: List<Int>, perPerson: Int): String? {
        val type = plan.coins.first().type
        val people = seatInputs.size
        if (mixScripts.size != perPerson || mixScripts.map { it.toHex() }.toSet().size != perPerson) return "our mixed outputs are wrong"
        for (mixScript in mixScripts) {
            val mix = plan.outputs.count { it.scriptPubKey.contentEquals(mixScript) && it.value == amount }
            if (mix != 1) return "our mixed output is missing"
        }
        if (plan.outputs.count { it.value == amount } < people * perPerson) return "the mixed outputs are not all equal"
        if (seatInputs.sum() != plan.coins.size) return "the coins do not match the seats"
        if (change != null && plan.outputs.none { it.scriptPubKey.contentEquals(change.scriptPubKey) && it.value == change.value })
            return "our change is missing or wrong"
        val vb = OVERHEAD_VB + inputVb(type) * plan.coins.size + outputVb(type) * plan.outputs.size
        if (plan.fee < kotlin.math.floor(feeRate * vb * 0.95)) return "the fee is too low"
        // Each person's fixed share, plus any dust folded into the fee; past that is a mistake.
        if (plan.fee > seatInputs.sumOf { feeShare(feeRate, true, it, type, perPerson) } + (DUST + 1) * people) return "the fee is too high"
        return null
    }

    /** DER → (r, s), strict enough for signatures we are about to verify anyway. */
    internal fun parseDer(der: ByteArray): Ecdsa.Signature {
        require(der[0].toInt() == 0x30)
        var o = 2
        require(der[o].toInt() == 0x02); val rl = der[o + 1].toInt(); val r = BigInteger(1, der.copyOfRange(o + 2, o + 2 + rl)); o += 2 + rl
        require(der[o].toInt() == 0x02); val sl = der[o + 1].toInt(); val s = BigInteger(1, der.copyOfRange(o + 2, o + 2 + sl))
        return Ecdsa.Signature(r, s)
    }

    /** Proof that whoever joins really owns [coin]: an ECDSA signature by its key over the pool and join key. */
    fun ownershipMessage(poolId: String, joinPub: String, coin: Coin): ByteArray =
        Hashes.sha256("kilojoin/v1/own|$poolId|$joinPub|${coin.outpoint}|${coin.value}".toByteArray())

    fun proveOwnership(poolId: String, joinPub: String, coin: Coin, privateKey: BigInteger): ByteArray =
        Ecdsa.der(Ecdsa.sign(privateKey, ownershipMessage(poolId, joinPub, coin)))

    fun checkOwnership(poolId: String, joinPub: String, coin: Coin, der: ByteArray): Boolean = runCatching {
        Ecdsa.verify(Secp256k1.decompress(coin.pubkey), ownershipMessage(poolId, joinPub, coin), parseDer(der))
    }.getOrDefault(false)
}
