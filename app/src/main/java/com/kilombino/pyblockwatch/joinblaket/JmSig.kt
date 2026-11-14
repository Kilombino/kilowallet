package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.crypto.Ecdsa
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Secp256k1
import java.math.BigInteger
import java.util.Base64

/**
 * The ECDSA conventions JoinMarket (and so JoinBlaket) uses for everything that is not a
 * transaction signature: the Bitcoin signed-message hash, RFC 6979 nonces WITHOUT Core's low-R
 * grinding (`_ecdsa_sig_grind_low_r=False` in `jmcore/crypto.py`), low-S, DER, base64 on the
 * wire; and the strict DER / low-S check every received signature must pass
 * (`_is_strict_low_s_der_signature`).
 */
object JmSig {
    private val N = Secp256k1.N
    private val HALF_N = N.shiftRight(1)

    /** `sha256d("\x18Bitcoin Signed Message:\n" || compactsize(len) || msg)`. */
    fun messageHash(msg: ByteArray): ByteArray {
        val prefix = "\u0018Bitcoin Signed Message:\n".toByteArray(Charsets.US_ASCII)
        return Hashes.doubleSha256(prefix + Wire.compactSize(msg.size.toLong()) + msg)
    }

    /** [messageHash] of the UTF-8 bytes of [msg]. */
    fun messageHash(msg: String): ByteArray = messageHash(msg.toByteArray(Charsets.UTF_8))

    /** DER signature (no hash-type byte) of [hash]: RFC 6979, no low-R grinding, low-S. */
    fun signHash(privateKey: BigInteger, hash: ByteArray): ByteArray =
        Ecdsa.der(Ecdsa.sign(privateKey, hash, grindLowR = false))

    /** base64(DER) over the signed-message hash of [msg]: `ecdsa_sign` / `sign_bytes`. */
    fun signMessage(privateKey: BigInteger, msg: ByteArray): String =
        Base64.getEncoder().encodeToString(signHash(privateKey, messageHash(msg)))

    /**
     * Parse [sig] only if it is strict DER with 1 <= r < n and 1 <= s <= n/2, exactly the
     * rules of `_is_strict_low_s_der_signature`; null otherwise.
     */
    fun parseStrictDer(sig: ByteArray): Ecdsa.Signature? {
        fun u(i: Int) = sig[i].toInt() and 0xFF
        if (sig.size !in 8..72 || u(0) != 0x30 || u(1) != sig.size - 2 || u(2) != 0x02) return null
        val rLen = u(3)
        if (rLen == 0 || 5 + rLen >= sig.size) return null
        if (u(4) and 0x80 != 0) return null
        if (rLen > 1 && u(4) == 0 && u(5) and 0x80 == 0) return null
        val r = BigInteger(1, sig.copyOfRange(4, 4 + rLen))
        val sType = 4 + rLen
        if (u(sType) != 0x02) return null
        val sLen = u(sType + 1)
        val sStart = sType + 2
        if (sLen == 0 || sStart + sLen != sig.size) return null
        if (u(sStart) and 0x80 != 0) return null
        if (sLen > 1 && u(sStart) == 0 && u(sStart + 1) and 0x80 == 0) return null
        val s = BigInteger(1, sig.copyOfRange(sStart, sStart + sLen))
        if (r.signum() <= 0 || r >= N || s.signum() <= 0 || s > HALF_N) return null
        return Ecdsa.Signature(r, s)
    }

    /** A 33-byte compressed key as a curve point, or null if it is not one. */
    fun point(pubkey: ByteArray): Secp256k1.Point? =
        runCatching { Secp256k1.decompress(pubkey) }.getOrNull()

    /** `verify_strict_ecdsa`: strict DER, low-S and a valid signature over [hash] by [pubkey]. */
    fun verifyHash(hash: ByteArray, der: ByteArray, pubkey: ByteArray): Boolean {
        val sig = parseStrictDer(der) ?: return false
        val p = point(pubkey) ?: return false
        return Ecdsa.verify(p, hash, sig)
    }

    /** `ecdsa_verify`: base64 [sigB64] over the signed-message hash of [msg]. */
    fun verifyMessage(msg: ByteArray, sigB64: String, pubkey: ByteArray): Boolean {
        val der = runCatching { Base64.getDecoder().decode(sigB64) }.getOrNull() ?: return false
        return verifyHash(messageHash(msg), der, pubkey)
    }

    fun verifyMessage(msg: String, sigB64: String, pubkey: ByteArray): Boolean =
        verifyMessage(msg.toByteArray(Charsets.UTF_8), sigB64, pubkey)
}
