package com.kilombino.pyblockwatch.coinjoin

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Ecdsa
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import com.kilombino.pyblockwatch.crypto.TxBuilder
import java.math.BigInteger
import kotlin.math.ceil

/**
 * The transaction side of a coinjoin, kept free of networking so it can be tested alone.
 *
 * Shape: one P2WPKH input per participant; one mix output of exactly the pool amount per
 * participant, all identical; and a change output per participant who has any. Each person
 * pays their own share of the miner fee out of their change, so the mix outputs stay equal
 * and nobody pays for anyone else.
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
    // P2WPKH vbytes: input 68, output 31, transaction overhead 10.5 split among everyone.
    private const val IN_VB = 68.0
    private const val OUT_VB = 31.0
    private const val OVERHEAD_VB = 10.5

    /** A participant's registered input, as everybody sees it. */
    data class Coin(val txid: String, val vout: Int, val value: Long, val pubkey: ByteArray) {
        val outpoint: String get() = "$txid:$vout"
        val script: ByteArray get() = Address.scriptPubKey(pubkey, ScriptType.P2WPKH)
    }

    /**
     * What each participant pays the miners: their input, their mix output, their change and
     * half the transaction overhead (the share in a pool of two, the most anyone can owe).
     * Fixed per person and known at join time, so the change can be signed there and then.
     */
    fun feeShare(feeRate: Double, withChange: Boolean = true): Long =
        ceil(feeRate * (IN_VB + OUT_VB + (if (withChange) OUT_VB else 0.0) + OVERHEAD_VB / 2)).toLong()

    /**
     * The change for [inputValue] in a pool of [amount] at [feeRate], or 0 when it would be
     * dust (the dust then goes to the fee). Null if the coin is too small for the pool.
     */
    fun change(inputValue: Long, amount: Long, feeRate: Double): Long? {
        val withChange = inputValue - amount - feeShare(feeRate, true)
        if (withChange > DUST) return withChange
        val without = inputValue - amount - feeShare(feeRate, false)
        return if (without >= 0) 0L else null
    }

    /** The smallest coin that can join a pool: the amount plus the fee share without change. */
    fun minimumCoin(amount: Long, feeRate: Double): Long = amount + feeShare(feeRate, false)

    data class Plan(
        val coins: List<Coin>,
        val outputs: List<TxBuilder.Output>,
    ) {
        val inputs: List<TxBuilder.Input> get() = coins.map {
            // The private key is never read when only assembling or checking; it is a placeholder.
            TxBuilder.Input(it.txid, it.vout, it.value, BigInteger.ONE, it.pubkey, SEQUENCE, ScriptType.P2WPKH)
        }
        val fee: Long get() = coins.sumOf { it.value } - outputs.sumOf { it.value }
    }

    private fun cmp(a: ByteArray, b: ByteArray): Int = a.toHex().compareTo(b.toHex())

    /**
     * The canonical transaction: [mixScripts] get [amount] each, every (script, value) in
     * [changes] its change. Throws if the pieces do not add up to a sane coinjoin.
     */
    fun plan(coins: List<Coin>, mixScripts: List<ByteArray>, changes: List<TxBuilder.Output>, amount: Long): Plan {
        require(coins.size >= 2) { "a coinjoin needs at least two people" }
        require(mixScripts.size == coins.size) { "one mix output per input" }
        require(coins.map { it.outpoint }.toSet().size == coins.size) { "the same coin was registered twice" }
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

    /** Our signature for our input [mine] in [plan]: unified sighash, SIGHASH_ALL. */
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
        Ecdsa.verify(Secp256k1.decompress(coin.pubkey), h, parseDer(sig.copyOfRange(0, sig.size - 1)))
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
    fun checkOurs(plan: Plan, mixScript: ByteArray, change: TxBuilder.Output?, amount: Long, feeRate: Double): String? {
        val mix = plan.outputs.count { it.scriptPubKey.contentEquals(mixScript) && it.value == amount }
        if (mix != 1) return "our mixed output is missing"
        if (plan.outputs.count { it.value == amount } < plan.coins.size) return "the mixed outputs are not all equal"
        if (change != null && plan.outputs.none { it.scriptPubKey.contentEquals(change.scriptPubKey) && it.value == change.value })
            return "our change is missing or wrong"
        val vb = OVERHEAD_VB + IN_VB * plan.coins.size + OUT_VB * plan.outputs.size
        if (plan.fee < kotlin.math.floor(feeRate * vb * 0.95)) return "the fee is too low"
        // Each person's fixed share, plus any dust folded into the fee; past that is a mistake.
        if (plan.fee > feeShare(feeRate, true) * plan.coins.size + (DUST + 1) * plan.coins.size) return "the fee is too high"
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
