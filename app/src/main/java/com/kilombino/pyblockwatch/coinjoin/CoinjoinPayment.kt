package com.kilombino.pyblockwatch.coinjoin

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bech32
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.data.Scanner
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Paying through a coinjoin, from SEND: when every recipient gets the same amount and all their
 * addresses are of one type, a new pool can be opened with that amount, one mixed output per
 * recipient, and those outputs going straight to the recipients' addresses
 * ([Destination.Kind.PAY]). Nobody watching the chain can tell which of the pool's identical
 * outputs are the payment. The change stays in this wallet as in any round.
 *
 * With different amounts (or when the user prefers it) it is a payment pool, Kilojoin v5
 * ([Protocol]): the others put in the same total and get it back in equal outputs; the payments
 * show on the chain as odd amounts, one person's, but not which coins paid them.
 *
 * The payment only goes out when the pool closes; if it never fills, nothing is paid and the
 * coins stay here. The recipients' addresses are theirs: never derived, reserved or watched as
 * this wallet's.
 */
object CoinjoinPayment {
    /** What SEND hands the coinjoin tab: the pool to open for this payment. */
    class Request(
        val amount: Long, val type: ScriptType,
        /** The recipients' scripts, one mixed output each, in the order typed. */
        val scripts: List<ByteArray>, val addresses: List<String>,
        /** Coins ticked with coin control in SEND (preselected in the pool's coin picker), if any. */
        val coins: List<Scanner.SpendableUtxo>, val feeRate: Double,
        /** A payment pool (v5): each recipient's amount, in the order of [scripts]; null for equal outputs (v4). */
        val payments: List<Long>? = null,
        /** SEND as it was, for BACK in the pool's sheet. */
        val draft: Draft? = null,
    )

    /** The SEND form as it was when its payment went to a pool: what was typed and ticked. */
    class Draft(
        val to: String, val amount: String, val feeRate: String, val coinControl: Boolean, val selected: List<String>,
        val extraTo: List<String>, val extraAmount: List<String>,
    )

    /** BACK from a payment's pool sheet: SEND opens again with this, then it goes back to null. */
    val backToSend = MutableStateFlow<Draft?>(null)

    /** The payment waiting for its pool to be opened in the coinjoin tab; null once done or cancelled. */
    val pending = MutableStateFlow<Request?>(null)

    /**
     * Whether the recipients fit a pool: [type] when they do. [ok]: all the same [amount], a pool
     * of equal outputs; [paymentOk]: a payment pool of their [amounts] (v5); else [reason] says why
     * neither (as shown).
     */
    class Check(val type: ScriptType?, val amount: Long, val scripts: List<ByteArray>, val reason: String?,
                val amounts: List<Long> = emptyList(), val paymentOk: Boolean = false) {
        val ok: Boolean get() = reason == null && amount > 0
        val any: Boolean get() = ok || paymentOk
    }

    /** One address type for every recipient: what a pool can never mix. */
    const val NEEDS = "Needs one address type (all bc1q or all bc1p)."

    /**
     * Whether [recipients] (address, sats: the first one and the extra ones of SEND) can be paid by
     * one pool: 1 to [Protocol.MAX_OUTPUTS] of them, all the same amount within the pool limits,
     * all plain native SegWit (bc1q, P2WPKH) or all Taproot (bc1p) addresses, none twice.
     * [taprootOk]: this wallet has Taproot pools on ([com.kilombino.pyblockwatch.ui.WalletViewModel.taprootPools]).
     */
    fun check(recipients: List<Pair<String, Long>>, taprootOk: Boolean, minAmount: Long = Protocol.MIN_AMOUNT): Check {
        fun no(reason: String) = Check(null, 0, emptyList(), reason)
        if (recipients.isEmpty()) return no(NEEDS)
        if (recipients.size > Protocol.MAX_OUTPUTS) return no("A pool gives at most ${Protocol.MAX_OUTPUTS} outputs: ${Protocol.MAX_OUTPUTS} recipients at most.")
        val amount = recipients.first().second
        val scripts = recipients.map { (a, _) ->
            val t = a.trim()
            if (t.contains("@") || t.lowercase().startsWith("sp1")) return no("Only plain bc1q or bc1p addresses can be paid through a coinjoin.")
            runCatching { Address.decodeToScriptPubKey(t) }.getOrNull() ?: return no(NEEDS)
        }
        val type = when {
            scripts.all { CoinjoinTx.isType(it, ScriptType.P2WPKH) } -> ScriptType.P2WPKH
            scripts.all { CoinjoinTx.isType(it, ScriptType.P2TR) } -> ScriptType.P2TR
            else -> return no(NEEDS)
        }
        if (type == ScriptType.P2TR && !taprootOk) return no("Taproot recipients need a Taproot pool: turn on Taproot addresses in settings.")
        if (scripts.map { it.toList() }.toSet().size != scripts.size) return no("The same address twice: every output of a pool is a different address.")
        val amounts = recipients.map { it.second }
        val total = amounts.sum()
        // A payment pool (v5): any amounts above dust, all of them together within a pool's limits.
        val paymentOk = amounts.all { it >= Protocol.MIN_PAYMENT } && total in minAmount..Protocol.MAX_AMOUNT
        // The same amount to everyone (a v4 pool): each a pool's output, all of them together a person's minimum.
        val equal = amounts.all { it == amount } && amount in Protocol.TEST_MIN_AMOUNT..Protocol.MAX_AMOUNT && total >= minAmount
        // Short of a pool's minimum is the usual case, while the amounts are still being typed: say how short.
        if (!equal && !paymentOk && total < minAmount && amounts.all { it >= Protocol.MIN_PAYMENT }) return Check(type, 0, scripts,
            "Together they add up to $total sats: a pool needs at least $minAmount sats in all.", amounts, false)
        if (!equal && !paymentOk) return Check(type, 0, scripts,
            "Each payment must be at least ${Protocol.MIN_PAYMENT} sats, and all of them together ${minAmount} to ${Protocol.MAX_AMOUNT} sats.",
            amounts, false)
        return Check(type, if (equal) amount else 0, scripts, null, amounts, paymentOk)
    }

    /** The bc1q / bc1p address of a recipient's script. */
    fun address(script: ByteArray): String = when {
        CoinjoinTx.isType(script, ScriptType.P2WPKH) -> Bech32.encodeSegwit("bc", 0, script.copyOfRange(2, 22))
        CoinjoinTx.isType(script, ScriptType.P2TR) -> Bech32.encodeSegwit("bc", 1, script.copyOfRange(2, 34))
        else -> error("not a pool's address type")
    }
}
