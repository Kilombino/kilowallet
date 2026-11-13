package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.crypto.Bech32
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import java.math.BigDecimal
import java.util.Base64
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Offer types on the wire; only the native segwit ones (`sw0…`) are usable. */
enum class OfferType(val wire: String, val absolute: Boolean, val usable: Boolean) {
    SW0_ABSOLUTE("sw0absoffer", true, true),
    SW0_RELATIVE("sw0reloffer", false, true),
    SW_ABSOLUTE("swabsoffer", true, false),
    SW_RELATIVE("swreloffer", false, false),
}

/**
 * One maker offer. [cjfee] keeps the normalized string Python keeps: `str(int(x))` for
 * absolute offers, the fixed-point Decimal string for relative ones.
 */
data class Offer(
    val counterparty: String,
    val oid: Long,
    val type: OfferType,
    val minsize: Long,
    val maxsize: Long,
    val txfee: Long,
    val cjfee: String,
    val bondProof: String? = null,
)

/**
 * Parsing of offer privmsgs (`_parse_offer_from_message` in `jmcore/directory_client.py`):
 * `<type> <oid> <minsize> <maxsize> <txfee> <cjfee>[!neutrino][!tbond <proof_b64> ...]`.
 */
object Offers {
    const val MAX_MONEY = 2_100_000_000_000_000L

    /**
     * `normalize_relative_cjfee`: a finite, non-negative Decimal with at most 32 digits and an
     * exponent in [-32, 32], formatted fixed-point (`format(d, "f")`).
     */
    fun normalizeRelativeCjfee(s: String): String {
        require(!s.trim().startsWith("-")) { "negative cjfee" }
        val d = BigDecimal(s.trim())
        require(d.signum() >= 0 && d.unscaledValue().abs().toString().length <= 32 && -d.scale() in -32..32) { "unreasonable cjfee" }
        return d.toPlainString()
    }

    /**
     * Parse [rest] (the privmsg content after `from!to!`, signature fields already removed by
     * [Nick.verifyPrivmsg] or not: only the first token after `tbond` is taken). The bond proof is
     * returned as given; verify it with [BondProof.decode] + [BondProof.verify] against
     * `(taker_nick = our nick, maker_nick = from)`. Null if the line is not a valid offer.
     */
    fun parse(rest: String, fromNick: String): Offer? {
        if (!Nick.isValid(fromNick)) return null
        val type = OfferType.values().firstOrNull { rest.startsWith(it.wire) } ?: return null
        val parts = rest.split("!")
        var proof: String? = null
        for (flag in parts.drop(1)) if (flag.startsWith("tbond ")) {
            flag.substring(6).split(Regex("\\s+")).firstOrNull { it.isNotEmpty() }?.let { proof = it }
        }
        val f = parts[0].trim().split(Regex("\\s+"))
        if (f.size < 6) return null
        return runCatching {
            val minsize = f[2].toLong(); val maxsize = f[3].toLong(); val txfee = f[4].toLong()
            require(f[1].toLong() >= 0)
            require(minsize in 0..MAX_MONEY && maxsize in 0..MAX_MONEY && txfee in 0..MAX_MONEY)
            val cjfee = if (type.absolute) f[5].toLong().toString() else normalizeRelativeCjfee(f[5])
            Offer(fromNick, f[1].toLong(), type, minsize, maxsize, txfee, cjfee, proof)
        }.getOrNull()
    }
}

/**
 * The 252-byte fidelity bond proof (`struct "<72s72s33sH33s32sII"`, `verify_fidelity_bond_proof`
 * in `jmcore/crypto.py`): nick signature, certificate signature (both DER left-padded with 0xff
 * to 72), certificate key, certificate expiry in 2016-block periods, bond UTXO key, txid in
 * display order, vout and locktime.
 */
class BondProof private constructor(
    val nickSig: ByteArray,
    val certSig: ByteArray,
    val certPub: ByteArray,
    val certExpiryEncoded: Int,
    val utxoPub: ByteArray,
    val txid: String,
    val vout: Long,
    val locktime: Long,
) {
    /** The block height after which the certificate is expired. */
    val certExpiryHeight: Long get() = certExpiryEncoded * 2016L

    /** Skip bonds whose certificate expired: `current_height > cert_expiry * 2016`. */
    fun expiredAt(height: Long): Boolean = height > certExpiryHeight

    /** `<locktime> OP_CLTV OP_DROP <utxo_pub> OP_CHECKSIG`. */
    val freezeScript: ByteArray get() = Bond.freezeScript(utxoPub, locktime)
    val scriptPubKey: ByteArray get() = Bond.p2wsh(freezeScript)

    /**
     * Both signatures: the certificate key over `taker_nick|maker_nick`, the UTXO key over
     * `fidelity-bond-cert|<cert_pub raw or hex>|<expiry>`, each on the signed-message hash with
     * strict DER / low-S. Returns null if valid, else the reason.
     */
    fun verify(makerNick: String, takerNick: String): String? {
        val nick = strip(nickSig) ?: return "nick signature DER header not found"
        val cert = strip(certSig) ?: return "certificate signature DER header not found"
        val nickMsg = "$takerNick|$makerNick".toByteArray(Charsets.US_ASCII)
        if (!JmSig.verifyHash(JmSig.messageHash(nickMsg), nick, certPub)) return "nick signature verification failed"
        val head = "fidelity-bond-cert|".toByteArray(Charsets.US_ASCII)
        val tail = "|$certExpiryEncoded".toByteArray(Charsets.US_ASCII)
        val raw = head + certPub + tail
        val ascii = head + certPub.toHex().toByteArray(Charsets.US_ASCII) + tail
        if (!JmSig.verifyHash(JmSig.messageHash(raw), cert, utxoPub) &&
            !JmSig.verifyHash(JmSig.messageHash(ascii), cert, utxoPub)) return "certificate signature verification failed"
        return null
    }

    companion object {
        const val SIZE = 252

        private fun strip(padded: ByteArray): ByteArray? {
            val i = padded.indexOf(0x30.toByte())
            return if (i < 0) null else padded.copyOfRange(i, padded.size)
        }

        /** Decode the base64 proof; null unless it is exactly 252 bytes. */
        fun decode(b64: String): BondProof? {
            val b = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return null
            if (b.size != SIZE) return null
            return BondProof(
                nickSig = b.copyOfRange(0, 72), certSig = b.copyOfRange(72, 144), certPub = b.copyOfRange(144, 177),
                certExpiryEncoded = Wire.readLe(b, 177, 2).toInt(), utxoPub = b.copyOfRange(179, 212),
                txid = b.copyOfRange(212, 244).toHex(), vout = Wire.readLe(b, 244, 4), locktime = Wire.readLe(b, 248, 4),
            )
        }
    }
}

/** Fidelity bond scripts and value (`jmcore/btc_script.py`, `jmcore/bond_calc.py`). */
object Bond {
    const val INTEREST_RATE = 0.015
    /** The configurable exponent is never passed by the taker (`taker/taker.py:943-948`): always 1.3. */
    const val EXPONENT = 1.3
    private const val YEAR = 60.0 * 60 * 24 * 365.2425

    /** Minimal script push of an integer (CScriptNum), as python-bitcointx's `CScript([n])`. */
    fun pushNumber(n: Long): ByteArray {
        if (n == 0L) return byteArrayOf(0x00)
        if (n == -1L) return byteArrayOf(0x4f)
        if (n in 1..16) return byteArrayOf((0x50 + n).toByte())
        val out = ArrayList<Byte>()
        var a = kotlin.math.abs(n)
        while (a > 0) { out.add((a and 0xff).toByte()); a = a ushr 8 }
        if (out.last().toInt() and 0x80 != 0) out.add(if (n < 0) 0x80.toByte() else 0)
        else if (n < 0) out[out.size - 1] = (out.last().toInt() or 0x80).toByte()
        return byteArrayOf(out.size.toByte()) + out.toByteArray()
    }

    /** `mk_freeze_script`: `<locktime> OP_CHECKLOCKTIMEVERIFY OP_DROP <pubkey> OP_CHECKSIG`. */
    fun freezeScript(pubkey: ByteArray, locktime: Long): ByteArray {
        require(pubkey.size == 33) { "bond key must be 33 bytes" }
        return pushNumber(locktime) + byteArrayOf(0xb1.toByte(), 0x75, 0x21) + pubkey + byteArrayOf(0xac.toByte())
    }

    /** `OP_0 <sha256(script)>`. */
    fun p2wsh(script: ByteArray): ByteArray = byteArrayOf(0x00, 0x20) + Hashes.sha256(script)

    /** The bc1 address of a P2WSH scriptPubKey. */
    fun address(p2wshScriptPubKey: ByteArray): String =
        Bech32.encodeSegwit("bc", 0, p2wshScriptPubKey.copyOfRange(2, p2wshScriptPubKey.size))

    /**
     * `calculate_timelocked_fidelity_bond_value` with the same double arithmetic in the same order;
     * only a selection weight, so a last-ulp difference between libms would not matter.
     */
    fun value(utxoValue: Long, confirmationTime: Long, locktime: Long, now: Long,
              r: Double = INTEREST_RATE, exponent: Double = EXPONENT): Long {
        val ttm = (locktime - confirmationTime) / YEAR
        val lockYears = locktime / YEAR
        val nowYears = now / YEAR
        val a = max(0.0, min(1.0, exp(r * ttm) - 1) - min(1.0, exp(r * max(0.0, nowYears - lockYears)) - 1))
        return (utxoValue * a).pow(exponent).toLong()
    }
}
