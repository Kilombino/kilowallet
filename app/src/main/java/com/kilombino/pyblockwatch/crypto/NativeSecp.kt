package com.kilombino.pyblockwatch.crypto

import com.kilombino.pyblockwatch.ark.ArkNative
import java.math.BigInteger

/**
 * Secret-key operations through libsecp256k1 (inside the Ark engine library), whose code runs
 * in constant time; the BigInteger code in [Secp256k1] stays for verification and public math,
 * and for builds without the library (unit tests, a CPU without the engine). Both give the same
 * bytes: RFC 6979 and BIP-340 are deterministic, and the tests compare them.
 */
object NativeSecp {
    /** Off only to compare against the Kotlin code in tests. */
    @Volatile var enabled = true
    val available: Boolean get() = enabled && !broken && runCatching { ArkNative.available }.getOrDefault(false)
    /** Set if the library is there but lacks these functions: then the Kotlin code signs. */
    @Volatile private var broken = false

    private inline fun <T> native(f: () -> T): T? = try { f() } catch (e: LinkageError) { broken = true; null }

    internal fun b32(v: BigInteger): ByteArray {
        val b = v.toByteArray(); val out = ByteArray(32)
        val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
        System.arraycopy(src, 0, out, 32 - src.size, src.size); return out
    }

    fun pubkey(secret: BigInteger): Secp256k1.Point? {
        if (!available) return null
        val k = b32(secret)
        try {
            val p = native { ArkNative.secpPubkey(k) ?: error("invalid secret key") } ?: return null
            return Secp256k1.Point(BigInteger(1, p.copyOfRange(1, 33)), BigInteger(1, p.copyOfRange(33, 65)))
        } finally { k.fill(0) }
    }

    fun ecdsa(secret: BigInteger, hash: ByteArray, lowR: Boolean): Ecdsa.Signature? {
        if (!available) return null
        val k = b32(secret)
        try {
            val s = native { ArkNative.secpEcdsaSign(k, hash, lowR) ?: error("invalid key or message") } ?: return null
            return Ecdsa.Signature(BigInteger(1, s.copyOfRange(0, 32)), BigInteger(1, s.copyOfRange(32, 64)))
        } finally { k.fill(0) }
    }

    fun schnorr(secret: BigInteger, msg: ByteArray, aux: ByteArray): ByteArray? {
        if (!available) return null
        val k = b32(secret)
        try {
            return native { ArkNative.secpSchnorrSign(k, msg, aux) ?: error("invalid key or message") }
        } finally { k.fill(0) }
    }
}
