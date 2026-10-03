package com.kilombino.pyblockwatch.crypto

import java.math.BigInteger
import java.security.SecureRandom

/**
 * BIP-340 Schnorr signatures and the BIP-341/86 key tweak, for spending Taproot (bc1p)
 * outputs by key path. Pinned by the official BIP-340 and BIP-341 test vectors.
 */
object Schnorr {
    private val N = Secp256k1.N

    private fun b32(v: BigInteger): ByteArray {
        val b = v.toByteArray(); val out = ByteArray(32)
        val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
        System.arraycopy(src, 0, out, 32 - src.size, src.size); return out
    }

    private fun hasEvenY(p: Secp256k1.Point) = !p.y!!.testBit(0)

    /**
     * The secret key for a Taproot output: the internal key, negated if its point has an odd
     * y, plus the tweak `TapTweak(x(P) ‖ merkleRoot)`. No merkle root means BIP-86 (no scripts).
     */
    fun tweakedSecret(internal: BigInteger, merkleRoot: ByteArray? = null): BigInteger {
        val p = Secp256k1.multiply(internal, Secp256k1.G)
        val d = if (hasEvenY(p)) internal else N.subtract(internal)
        val t = BigInteger(1, Hashes.taggedHash("TapTweak", Secp256k1.xOnly(p) + (merkleRoot ?: ByteArray(0))))
        require(t < N) { "tweak out of range" }
        return d.add(t).mod(N).also { require(it.signum() != 0) { "tweaked key is zero" } }
    }

    /** BIP-340 signature of a 32-byte message. [aux] is fresh randomness unless a test pins it. */
    fun sign(secret: BigInteger, msg: ByteArray, aux: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }): ByteArray {
        require(msg.size == 32 && aux.size == 32)
        require(secret.signum() > 0 && secret < N) { "secret key out of range" }
        val p = Secp256k1.multiply(secret, Secp256k1.G)
        val d = if (hasEvenY(p)) secret else N.subtract(secret)
        val px = Secp256k1.xOnly(p)
        val auxHash = Hashes.taggedHash("BIP0340/aux", aux)
        val db = b32(d)
        val t = ByteArray(32) { (db[it].toInt() xor auxHash[it].toInt()).toByte() }
        val k0 = BigInteger(1, Hashes.taggedHash("BIP0340/nonce", t + px + msg)).mod(N)
        require(k0.signum() != 0) { "nonce is zero" }
        val r = Secp256k1.multiply(k0, Secp256k1.G)
        val k = if (hasEvenY(r)) k0 else N.subtract(k0)
        val rx = Secp256k1.xOnly(r)
        val e = BigInteger(1, Hashes.taggedHash("BIP0340/challenge", rx + px + msg)).mod(N)
        val sig = rx + b32(k.add(e.multiply(d)).mod(N))
        check(verify(px, msg, sig)) { "Schnorr self-check failed" }
        return sig
    }

    /** BIP-340 verification against an x-only public key. */
    fun verify(pubX: ByteArray, msg: ByteArray, sig: ByteArray): Boolean = runCatching {
        if (sig.size != 64) return false
        val p = Secp256k1.liftX(BigInteger(1, pubX))
        val r = BigInteger(1, sig.copyOfRange(0, 32))
        val s = BigInteger(1, sig.copyOfRange(32, 64))
        if (r >= Secp256k1.P || s >= N) return false
        val e = BigInteger(1, Hashes.taggedHash("BIP0340/challenge", sig.copyOfRange(0, 32) + pubX + msg)).mod(N)
        val rr = Secp256k1.add(Secp256k1.multiply(s, Secp256k1.G), Secp256k1.multiply(N.subtract(e), p))
        !rr.isInfinity && hasEvenY(rr) && rr.x == r
    }.getOrDefault(false)
}
