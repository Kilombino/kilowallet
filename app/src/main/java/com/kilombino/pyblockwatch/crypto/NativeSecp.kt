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

    /**
     * Whether the library's ECDH and tweak agree with the Kotlin code, checked once: with small
     * public numbers (7·(5·G), 5+7) for byte order and calling convention, then with random
     * full-size scalars, including sums that wrap around the group order. A library that
     * disagreed would derive wrong keys, so it is then left unused for these two (the Kotlin
     * code gives the right answer, though not in constant time; settings shows which runs).
     */
    private val newOpsOk: Boolean by lazy {
        runCatching {
            val p = Secp256k1.multiply(BigInteger.valueOf(5), Secp256k1.G)
            val k = b32(BigInteger.valueOf(7))
            val e = ArkNative.secpEcdh(k, byteArrayOf(4) + b32(p.x!!) + b32(p.y!!)) ?: return@runCatching false
            val want = Secp256k1.multiply(BigInteger.valueOf(35), Secp256k1.G)
            val t = ArkNative.secpTweakAdd(b32(BigInteger.valueOf(5)), k) ?: return@runCatching false
            if (!(BigInteger(1, e.copyOfRange(1, 33)) == want.x && BigInteger(1, e.copyOfRange(33, 65)) == want.y &&
                    BigInteger(1, t) == BigInteger.valueOf(12))) return@runCatching false
            val rnd = java.security.SecureRandom()
            fun scalar() = BigInteger(256, rnd).mod(Secp256k1.N.subtract(BigInteger.ONE)).add(BigInteger.ONE)
            repeat(4) {
                val a = scalar(); val b = scalar()
                val q = Secp256k1.multiply(b, Secp256k1.G)
                val r = ArkNative.secpEcdh(b32(a), byteArrayOf(4) + b32(q.x!!) + b32(q.y!!)) ?: return@runCatching false
                val w = Secp256k1.multiply(a.multiply(b).mod(Secp256k1.N), Secp256k1.G)
                if (BigInteger(1, r.copyOfRange(1, 33)) != w.x || BigInteger(1, r.copyOfRange(33, 65)) != w.y) return@runCatching false
                // A tweak near the order, so the sum wraps.
                val big = Secp256k1.N.subtract(BigInteger.valueOf(1L + it))
                for (tw in listOf(b, big)) {
                    val s = ArkNative.secpTweakAdd(b32(a), b32(tw))
                    val exp = a.add(tw).mod(Secp256k1.N)
                    if (exp.signum() != 0 && (s == null || BigInteger(1, s) != exp)) return@runCatching false
                }
            }
            true
        }.getOrElse { false }
    }

    /** Whether secret ECDH and key tweaks run in the library (shown in settings). */
    val ecdhNative: Boolean get() = available && newOpsOk

    /** secret · [point] in constant time, or null without the library (the caller does it in Kotlin). */
    fun ecdh(secret: BigInteger, point: Secp256k1.Point): Secp256k1.Point? {
        if (!available || point.isInfinity || !newOpsOk) return null
        val k = b32(secret)
        try {
            val p = native { ArkNative.secpEcdh(k, byteArrayOf(4) + b32(point.x!!) + b32(point.y!!)) ?: error("invalid key or point") } ?: return null
            // The shared point is secret too: copy it out and wipe the library's buffer.
            try { return Secp256k1.Point(BigInteger(1, p.copyOfRange(1, 33)), BigInteger(1, p.copyOfRange(33, 65))) } finally { p.fill(0) }
        } finally { k.fill(0) }
    }

    /** (secret + tweak) mod n in constant time, or null without the library. */
    fun tweakAdd(secret: BigInteger, tweak: BigInteger): BigInteger? {
        if (!available || !newOpsOk) return null
        val k = b32(secret); val t = b32(tweak)
        try {
            val r = native { ArkNative.secpTweakAdd(k, t) ?: error("invalid key or tweak") } ?: return null
            return BigInteger(1, r).also { r.fill(0) }
        } finally { k.fill(0); t.fill(0) } // the tweak is secret as well (in BIP-32 it is IL)
    }

    fun schnorr(secret: BigInteger, msg: ByteArray, aux: ByteArray): ByteArray? {
        if (!available) return null
        val k = b32(secret)
        try {
            return native { ArkNative.secpSchnorrSign(k, msg, aux) ?: error("invalid key or message") }
        } finally { k.fill(0) }
    }
}
