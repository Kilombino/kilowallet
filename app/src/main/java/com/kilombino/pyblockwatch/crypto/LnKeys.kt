package com.kilombino.pyblockwatch.crypto

import java.math.BigInteger

/**
 * The BOLT 3 key and script derivations an LND force close uses, so a rescue can find and spend
 * our own outputs of a commitment: per-commitment secrets from the shachain, tweaked keys, the
 * revocation key, the obscured state number, and the to_local / anchor to_remote scripts.
 * Pinned by vectors computed with LND's own code.
 */
object LnKeys {
    private val N = Secp256k1.N

    private fun point(pub: ByteArray) = Secp256k1.decompress(pub)
    private fun b32(v: BigInteger): ByteArray {
        val b = v.toByteArray(); val out = ByteArray(32)
        val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
        System.arraycopy(src, 0, out, 32 - src.size, src.size); return out
    }

    /** LND's ECDH: SHA-256 of the compressed shared point. */
    fun ecdh(priv: BigInteger, pub: ByteArray): ByteArray =
        Hashes.sha256(Secp256k1.compress(Secp256k1.multiply(priv, point(pub))))

    /** Per-commitment secret for [state] from a shachain root (index 2^48 − 1 − state). */
    fun commitmentSecret(root: ByteArray, state: Long): ByteArray {
        val index = (1L shl 48) - 1 - state
        var buf = root.copyOf()
        for (pos in 47 downTo 0) {
            if ((index ushr pos) and 1L == 1L) {
                buf[pos / 8] = (buf[pos / 8].toInt() xor (1 shl (pos % 8))).toByte()
                buf = Hashes.sha256(buf)
            }
        }
        return buf
    }

    fun commitmentPoint(secret: ByteArray): ByteArray =
        Secp256k1.compress(Secp256k1.publicPoint(BigInteger(1, secret)))

    private fun tweak(cp: ByteArray, base: ByteArray) = BigInteger(1, Hashes.sha256(cp + base)).mod(N)

    /** basepoint + SHA256(commitPoint ‖ basepoint)·G */
    fun tweakPub(base: ByteArray, cp: ByteArray): ByteArray =
        Secp256k1.compress(Secp256k1.add(point(base), Secp256k1.multiply(tweak(cp, base), Secp256k1.G)))

    fun tweakPriv(basePriv: BigInteger, basePub: ByteArray, cp: ByteArray): BigInteger =
        basePriv.add(tweak(cp, basePub)).mod(N)

    /** revBase·SHA256(revBase ‖ cp) + cp·SHA256(cp ‖ revBase) */
    fun revocationPub(revBase: ByteArray, cp: ByteArray): ByteArray {
        val a = BigInteger(1, Hashes.sha256(revBase + cp)).mod(N)
        val b = BigInteger(1, Hashes.sha256(cp + revBase)).mod(N)
        return Secp256k1.compress(Secp256k1.add(Secp256k1.multiply(a, point(revBase)), Secp256k1.multiply(b, point(cp))))
    }

    /** The 48-bit state-number obfuscator: last 6 bytes of SHA256(initiator ‖ responder payment basepoints). */
    fun obfuscator(initiatorPayment: ByteArray, responderPayment: ByteArray): Long {
        val h = Hashes.sha256(initiatorPayment + responderPayment)
        var v = 0L
        for (i in 26 until 32) v = (v shl 8) or (h[i].toLong() and 0xFF)
        return v
    }

    fun stateNumber(locktime: Long, sequence: Long, obfuscator: Long): Long =
        (((sequence and 0xFFFFFF) shl 24) or (locktime and 0xFFFFFF)) xor obfuscator

    /** Minimal script push of a small number, as txscript writes a CSV delay. */
    fun scriptNum(n: Long): ByteArray {
        if (n == 0L) return byteArrayOf(0x00)
        if (n in 1..16) return byteArrayOf((0x50 + n).toByte())
        val bytes = ArrayList<Byte>(); var v = n
        while (v > 0) { bytes += (v and 0xFF).toByte(); v = v ushr 8 }
        if (bytes.last().toInt() and 0x80 != 0) bytes += 0
        return byteArrayOf(bytes.size.toByte()) + bytes.toByteArray()
    }

    /** OP_IF <rev> OP_ELSE <csv> OP_CSV OP_DROP <delayed> OP_ENDIF OP_CHECKSIG */
    fun toLocalScript(csv: Int, delayedPub: ByteArray, revocationPub: ByteArray): ByteArray =
        byteArrayOf(0x63, 0x21) + revocationPub + byteArrayOf(0x67) + scriptNum(csv.toLong()) +
            byteArrayOf(0xb2.toByte(), 0x75, 0x21) + delayedPub + byteArrayOf(0x68, 0xac.toByte())

    /** Anchor channels: <payment basepoint> OP_CHECKSIGVERIFY OP_1 OP_CSV */
    fun toRemoteAnchorScript(paymentPub: ByteArray): ByteArray =
        byteArrayOf(0x21) + paymentPub + byteArrayOf(0xad.toByte(), 0x51, 0xb2.toByte())

    fun p2wsh(script: ByteArray): ByteArray = byteArrayOf(0x00, 0x20) + Hashes.sha256(script)
}
