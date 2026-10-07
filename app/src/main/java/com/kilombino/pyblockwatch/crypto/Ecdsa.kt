package com.kilombino.pyblockwatch.crypto

import java.math.BigInteger

/**
 * ECDSA over secp256k1 — signing, the half a hot wallet needs that a watch-only one
 * never did.
 *
 * WHY THE NONCE IS DETERMINISTIC
 * ------------------------------
 * The one catastrophic ECDSA footgun is the per-signature nonce k: reuse it across
 * two signatures, or leak a few bits of it, and the private key falls out by simple
 * algebra. So k is never taken from a random source here. It is derived from the
 * private key and the message alone, by RFC 6979 (HMAC-SHA256), exactly as Bitcoin
 * Core and every serious wallet do. The same (key, message) always yields the same
 * signature — which is also what lets the BIP-143 transaction vector in the tests pin
 * this code to a byte-for-byte known-good result.
 *
 * Signatures are emitted low-S (BIP-62) and DER-encoded, the only form relayed today.
 */
object Ecdsa {

    private val N = Secp256k1.N
    private val HALF_N = N.shiftRight(1)

    data class Signature(val r: BigInteger, val s: BigInteger)

    /** Big-endian 32-byte serialisation of a scalar in [0, N). */
    private fun int2octets(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        val out = ByteArray(32)
        val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
        System.arraycopy(src, 0, out, 32 - src.size, src.size)
        return out
    }

    /** RFC 6979 bits2octets: reduce the hash mod N, then serialise. */
    private fun bits2octets(h: ByteArray): ByteArray = int2octets(BigInteger(1, h).mod(N))

    /**
     * RFC 6979 deterministic nonce generation with HMAC-SHA256, specialised to the
     * 32-byte hash / 256-bit order case (no bit-length juggling needed).
     */
    private fun deterministicK(privateKey: BigInteger, hash: ByteArray, extra: ByteArray = ByteArray(0)): BigInteger {
        val x = int2octets(privateKey)
        // libsecp256k1 appends 32 bytes of extra data (Core's low-R counter) after the hash.
        val h1 = bits2octets(hash) + extra
        var v = ByteArray(32) { 0x01 }
        var k = ByteArray(32) { 0x00 }

        k = Hashes.hmacSha256(k, v + byteArrayOf(0x00) + x + h1)
        v = Hashes.hmacSha256(k, v)
        k = Hashes.hmacSha256(k, v + byteArrayOf(0x01) + x + h1)
        v = Hashes.hmacSha256(k, v)

        while (true) {
            v = Hashes.hmacSha256(k, v)
            val candidate = BigInteger(1, v)
            if (candidate.signum() > 0 && candidate < N) return candidate
            k = Hashes.hmacSha256(k, v + byteArrayOf(0x00))
            v = Hashes.hmacSha256(k, v)
        }
    }

    /**
     * Sign a 32-byte message hash with [privateKey]. The nonce is RFC 6979; the result
     * is low-S. Returns (r, s).
     */
    fun sign(privateKey: BigInteger, hash: ByteArray, grindLowR: Boolean = false): Signature {
        require(hash.size == 32) { "message hash must be 32 bytes" }
        require(privateKey.signum() > 0 && privateKey < N) { "private key out of range" }
        NativeSecp.ecdsa(privateKey, hash, grindLowR)?.let { return it }
        if (grindLowR) {
            // Bitcoin Core's grind: retry with a 32-byte little-endian counter as extra nonce
            // data until r fits in 32 DER bytes (r < 2^255), so the signature is a byte shorter
            // and identical to what Core produces for the same key and message.
            var counter = 0L
            while (true) {
                val extra = if (counter == 0L) ByteArray(0)
                    else ByteArray(32).also { for (i in 0 until 4) it[i] = (counter ushr (8 * i)).toByte() }
                val sig = signWithNonce(privateKey, hash, deterministicK(privateKey, hash, extra))
                if (sig.r.bitLength() <= 255) return sig
                counter++
            }
        }
        return signWithNonce(privateKey, hash, deterministicK(privateKey, hash))
    }

    private fun signWithNonce(privateKey: BigInteger, hash: ByteArray, nonce: BigInteger): Signature {
        val z = BigInteger(1, hash)
        while (true) {
            val k = nonce
            val point = Secp256k1.multiply(k, Secp256k1.G)
            val r = point.x!!.mod(N)
            if (r.signum() == 0) continue
            val kInv = k.modInverse(N)
            var s = kInv.multiply(z.add(r.multiply(privateKey))).mod(N)
            if (s.signum() == 0) continue
            if (s > HALF_N) s = N.subtract(s) // low-S
            return Signature(r, s)
        }
    }

    /** DER-encode a signature (without the trailing sighash byte). */
    fun der(sig: Signature): ByteArray {
        fun trim(v: BigInteger): ByteArray {
            var b = v.toByteArray() // already big-endian, minimal two's-complement
            // BigInteger.toByteArray already adds a 0x00 pad when the high bit is set,
            // and never has excess leading zeros, so it is exactly the DER integer body.
            return b
        }
        val rb = trim(sig.r)
        val sb = trim(sig.s)
        val body = byteArrayOf(0x02) + byteArrayOf(rb.size.toByte()) + rb +
            byteArrayOf(0x02) + byteArrayOf(sb.size.toByte()) + sb
        return byteArrayOf(0x30) + byteArrayOf(body.size.toByte()) + body
    }

    /** Verify (r, s) against a message hash and a public key point. Used by the tests. */
    fun verify(publicKey: Secp256k1.Point, hash: ByteArray, sig: Signature): Boolean {
        val r = sig.r; val s = sig.s
        if (r.signum() <= 0 || r >= N || s.signum() <= 0 || s >= N) return false
        val z = BigInteger(1, hash)
        val w = s.modInverse(N)
        val u1 = z.multiply(w).mod(N)
        val u2 = r.multiply(w).mod(N)
        val point = Secp256k1.add(
            Secp256k1.multiply(u1, Secp256k1.G),
            Secp256k1.multiply(u2, publicKey),
        )
        if (point.isInfinity) return false
        return point.x!!.mod(N) == r
    }
}
