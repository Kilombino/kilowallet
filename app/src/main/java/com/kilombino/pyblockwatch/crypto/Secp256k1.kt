package com.kilombino.pyblockwatch.crypto

import java.math.BigInteger

/**
 * secp256k1 curve arithmetic, in-tree and dependency-free.
 *
 * WHY THIS EXISTS INSTEAD OF A LIBRARY
 * ------------------------------------
 * A watch-only wallet only ever needs PUBLIC key arithmetic: BIP-32 CKDpub, which
 * is `parse256(IL)·G + Kpar`. There is no signing here, no private key, and no
 * nonce generation — so the classic ECDSA footguns (biased/reused k, side-channel
 * leakage of a secret scalar) simply do not apply to this code. Every scalar we
 * multiply is public data derived from a public xpub.
 *
 * That is what makes it defensible to implement the curve in-tree rather than
 * pull in a prebuilt `.so`: the whole cryptographic path stays auditable Kotlin
 * that anyone can read and rebuild, with nothing binary to trust. A bug here
 * yields a WRONG ADDRESS (visible, harmless, caught by the BIP-32 test vectors in
 * `Bip32Test`), not a stolen key.
 *
 * Affine coordinates with an explicit modular inverse per operation. Slower than
 * Jacobian, far easier to read and verify. A gap-limit scan is ~40 derivations,
 * a few milliseconds total — performance is irrelevant at this scale, clarity is not.
 */
object Secp256k1 {
    // BigInteger.TWO only exists from Android 13 (API 33): on older phones it crashed the app.
    private val TWO: BigInteger = BigInteger.valueOf(2)


    /** Field prime: 2^256 − 2^32 − 977. */
    val P: BigInteger = BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16
    )

    /** Group order. */
    val N: BigInteger = BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16
    )

    /** Curve is y² = x³ + 7 (a = 0, b = 7). */
    private val B: BigInteger = BigInteger.valueOf(7)

    /** Generator point. */
    val G = Point(
        BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
        BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16),
    )

    /** Point at infinity, the group identity. */
    val INFINITY = Point(null, null)

    data class Point(val x: BigInteger?, val y: BigInteger?) {
        val isInfinity: Boolean get() = x == null || y == null
    }

    private fun mod(a: BigInteger): BigInteger = a.mod(P)

    /** Point addition, including the doubling case (a = 0 simplifies the slope). */
    fun add(p: Point, q: Point): Point {
        if (p.isInfinity) return q
        if (q.isInfinity) return p
        val px = p.x!!; val py = p.y!!; val qx = q.x!!; val qy = q.y!!

        if (px == qx) {
            // P + (−P) = O
            if (mod(py + qy).signum() == 0) return INFINITY
            // Doubling: λ = 3x² / 2y   (a = 0, so no + a term)
            val num = mod(BigInteger.valueOf(3) * px * px)
            val den = mod(TWO * py).modInverse(P)
            val lam = mod(num * den)
            val rx = mod(lam * lam - TWO * px)
            val ry = mod(lam * (px - rx) - py)
            return Point(rx, ry)
        }
        // Distinct points: λ = (qy − py) / (qx − px)
        val lam = mod(mod(qy - py) * mod(qx - px).modInverse(P))
        val rx = mod(lam * lam - px - qx)
        val ry = mod(lam * (px - rx) - py)
        return Point(rx, ry)
    }

    /** Scalar multiplication by double-and-add, MSB first. */
    /** k·G for a SECRET k: through libsecp256k1 when present (constant time), else [multiply]. */
    fun publicPoint(k: BigInteger): Point = NativeSecp.pubkey(k) ?: multiply(k, G)

    fun multiply(k: BigInteger, point: Point): Point {
        var scalar = k.mod(N)
        if (scalar.signum() == 0 || point.isInfinity) return INFINITY
        var result = INFINITY
        var addend = point
        while (scalar.signum() > 0) {
            if (scalar.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            scalar = scalar.shiftRight(1)
        }
        return result
    }

    /** Decompress a 33-byte SEC point (0x02/0x03 prefix) into affine coordinates. */
    fun decompress(sec: ByteArray): Point {
        require(sec.size == 33) { "compressed point must be 33 bytes, got ${sec.size}" }
        val prefix = sec[0].toInt() and 0xFF
        require(prefix == 0x02 || prefix == 0x03) { "bad SEC prefix 0x%02x".format(prefix) }
        val x = BigInteger(1, sec.copyOfRange(1, 33))
        require(x < P) { "x coordinate not in field" }

        // y² = x³ + 7. Since p ≡ 3 (mod 4), the square root is y = (x³+7)^((p+1)/4).
        val alpha = mod(x.modPow(BigInteger.valueOf(3), P) + B)
        val y = alpha.modPow((P + BigInteger.ONE).shiftRight(2), P)
        // Verify: modPow always returns something, but it is only a real root if y² == alpha.
        require(mod(y * y) == alpha) { "point is not on the curve" }

        // Prefix 0x02 means even y, 0x03 means odd y.
        val wantOdd = prefix == 0x03
        val isOdd = y.testBit(0)
        return Point(x, if (wantOdd == isOdd) y else mod(P - y))
    }

    /**
     * BIP-340 lift_x: the point with the given x coordinate and EVEN y. Used to turn a
     * Taproot internal key (an x-only pubkey) back into a curve point before tweaking.
     */
    fun liftX(x: BigInteger): Point {
        require(x < P) { "x coordinate not in field" }
        val alpha = mod(x.modPow(BigInteger.valueOf(3), P) + B)
        val y = alpha.modPow((P + BigInteger.ONE).shiftRight(2), P)
        require(mod(y * y) == alpha) { "x has no corresponding curve point" }
        return Point(x, if (y.testBit(0)) mod(P - y) else y) // even y
    }

    /** The 32-byte x-only serialisation of a point (Taproot output/internal keys). */
    fun xOnly(point: Point): ByteArray {
        require(!point.isInfinity) { "cannot serialise the point at infinity" }
        val out = ByteArray(32)
        val xb = point.x!!.toByteArray()
        val src = if (xb.size > 32) xb.copyOfRange(xb.size - 32, xb.size) else xb
        System.arraycopy(src, 0, out, 32 - src.size, src.size)
        return out
    }

    /** Serialise a point as a 65-byte uncompressed SEC pubkey (04 ‖ x ‖ y), for old keys. */
    fun uncompressed(point: Point): ByteArray {
        require(!point.isInfinity) { "cannot serialise the point at infinity" }
        fun b32(v: java.math.BigInteger): ByteArray {
            val b = v.toByteArray(); val out = ByteArray(32)
            val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
            System.arraycopy(src, 0, out, 32 - src.size, src.size); return out
        }
        return byteArrayOf(0x04) + b32(point.x!!) + b32(point.y!!)
    }

    /** Serialise a point as a 33-byte compressed SEC pubkey. */
    fun compress(point: Point): ByteArray {
        require(!point.isInfinity) { "cannot serialise the point at infinity" }
        val out = ByteArray(33)
        out[0] = if (point.y!!.testBit(0)) 0x03 else 0x02
        val xb = point.x!!.toByteArray()
        // BigInteger.toByteArray() may carry a leading sign byte or be short — right-align.
        val src = if (xb.size > 32) xb.copyOfRange(xb.size - 32, xb.size) else xb
        System.arraycopy(src, 0, out, 33 - src.size, src.size)
        return out
    }
}
