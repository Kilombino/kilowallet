package com.kilombino.pyblockwatch.roblo

import com.kilombino.pyblockwatch.crypto.Bech32
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the Roblosats client asks of Kilowallet's wallet (bridge: walletPay, walletPayInvoice,
 * walletAddress, walletInvoice). Each request waits for a native sheet on the ROBLO screen: the
 * amount and the address come from the coordinator and are fixed there; the user chooses coins and
 * fee, and signs with the fingerprint as in SEND. The page only gets the txid, "locked", the
 * address picked or the invoice. One request at a time; a sheet closed without paying answers
 * "cancelled". Ark is not involved in any of them.
 */
object RobloWallet {
    sealed class Request(val uuid: String, val orderRef: String) {
        class Pay(uuid: String, val address: String, val sats: Long, orderRef: String, val feeHint: Double) : Request(uuid, orderRef)
        class PayInvoice(uuid: String, val bolt11: String, orderRef: String) : Request(uuid, orderRef)
        class Address(uuid: String, val purpose: String, orderRef: String) : Request(uuid, orderRef)
        class Invoice(uuid: String, val sats: Long, val memo: String) : Request(uuid, "")
    }

    /** The request on screen, until its sheet answers. */
    val request = MutableStateFlow<Request?>(null)

    /** Set by the bridge: the answer for the page (ok + value, or the reason). */
    @Volatile var answer: ((uuid: String, ok: Boolean, value: String) -> Unit)? = null

    private val ORDER = Regex("^[A-Za-z0-9_-]{1,64}/[0-9]{1,12}$")
    private val PURPOSES = setOf("payout", "refund", "bond_return")
    const val MAX_SATS = 2_100_000_000_000_000L

    /** Why [address]/[sats] can't be a payment request, or null when they can. */
    fun checkPay(address: String, sats: Long, orderRef: String): String? = when {
        address.length !in 14..100 || address.any { it.isWhitespace() } -> "bad address"
        sats !in 1..MAX_SATS -> "bad amount"
        !ORDER.matches(orderRef) -> "bad order"
        else -> null
    }

    fun checkInvoice(bolt11: String, orderRef: String): String? = when {
        bolt11.length !in 20..2000 || !bolt11.lowercase().startsWith("ln") || bolt11.any { it.isWhitespace() } -> "bad invoice"
        !ORDER.matches(orderRef) -> "bad order"
        else -> null
    }

    fun checkAddress(purpose: String, orderRef: String): String? =
        if (purpose !in PURPOSES) "bad purpose" else if (!ORDER.matches(orderRef)) "bad order" else null

    /** Queues [r] for the screen; false when another one is still open. */
    @Synchronized fun offer(r: Request): Boolean {
        if (request.value != null) return false
        request.value = r
        return true
    }

    /** The sheet for [r] is done. */
    @Synchronized fun finish(r: Request, ok: Boolean, value: String) {
        if (request.value === r) request.value = null
        answer?.invoke(r.uuid, ok, value)
    }

    /**
     * The draft a SEND prepared really pays [req]: the same address (and script) and exactly the
     * amount, to one output. A different one is refused before anything is signed.
     */
    fun draftMatches(req: Request.Pay, draftAddress: String, draftAmount: Long, extraRecipients: Int): Boolean =
        extraRecipients == 0 && draftAmount == req.sats && sameAddress(draftAddress, labMainnet(req.address))

    private fun sameAddress(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = a.trim().lowercase().startsWith("bc1"))

    // ------------------------------------------------------------- regtest lab (debug builds only)
    //
    // The wallet speaks mainnet addresses (bc1…); the regtest lab's coordinator gives and expects
    // bcrt1…. Only in a debug build with the lab switched on (a file "roblo-lab-regtest" in the
    // app's files, written by adb), the same witness program is spelled the other way.

    @Volatile var labRegtest = false

    fun labMainnet(address: String): String {
        if (!labRegtest || !address.lowercase().startsWith("bcrt1")) return address
        val s = Bech32.decodeSegwit("bcrt", address.lowercase()) ?: return address
        return Bech32.encodeSegwit("bc", s.witnessVersion, s.program)
    }

    fun labOut(address: String): String {
        if (!labRegtest || !address.lowercase().startsWith("bc1")) return address
        val s = Bech32.decodeSegwit("bc", address.lowercase()) ?: return address
        return Bech32.encodeSegwit("bcrt", s.witnessVersion, s.program)
    }
}
