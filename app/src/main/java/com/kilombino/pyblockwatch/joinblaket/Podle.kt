package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.Secp256k1
import java.math.BigInteger

/**
 * PoDLE, the taker's anti-snooping commitment (`jmcore/podle.py`): proof that `P = xG` and
 * `P2 = xJ_i` share the discrete log `x` of a UTXO key, committed as `sha256(P2)` in `!fill`
 * and revealed in `!auth`.
 */
object Podle {
    private val N = Secp256k1.N
    private val NONCE_DOMAIN = "JoinMarket/PoDLE/nonce/v1".toByteArray(Charsets.US_ASCII)
    private val G_COMPRESSED = Secp256k1.compress(Secp256k1.G)
    private val G_UNCOMPRESSED = Secp256k1.uncompressed(Secp256k1.G)
    private val nums = HashMap<Int, Secp256k1.Point>()

    /** Makers accept indices `0 until taker_utxo_retries` (default 3). */
    val DEFAULT_INDICES = 0 until 3

    /**
     * NUMS point `J_i`: for G compressed then uncompressed, for ctr 0..255, the first valid
     * point `0x02 || sha256(G_ser || u8(i) || u8(ctr))`.
     */
    fun numsPoint(index: Int): Secp256k1.Point {
        require(index in 0..255) { "NUMS index must be 0..255" }
        synchronized(nums) { nums[index]?.let { return it } }
        for (g in listOf(G_COMPRESSED, G_UNCOMPRESSED)) for (ctr in 0..255) {
            val cand = byteArrayOf(2) + Hashes.sha256(g + byteArrayOf(index.toByte(), ctr.toByte()))
            val p = JmSig.point(cand) ?: continue
            synchronized(nums) { nums[index] = p }
            return p
        }
        error("no NUMS point for index $index") // unreachable in practice
    }

    /** A generated commitment and its opening. */
    class Commitment(
        val p: ByteArray, val p2: ByteArray, val s: ByteArray, val e: ByteArray,
        val commitment: ByteArray, val utxo: String, val index: Int,
    ) {
        /** `"P" + hex(sha256(P2))`, the last field of `!fill`. */
        val wire: String get() = "P" + commitment.toHex()

        /**
         * The `!auth` plaintext `utxo|P|P2|s|e`. With [scriptPubKeyHex] and [height] (makers
         * advertising `neutrino_compat`, i.e. every JoinBlaket maker) the utxo is the extended
         * `txid:vout:spk:height`.
         */
        fun revelation(scriptPubKeyHex: String? = null, height: Int? = null): String {
            val u = if (scriptPubKeyHex != null && height != null) "$utxo:$scriptPubKeyHex:$height" else utxo
            return listOf(u, p.toHex(), p2.toHex(), s.toHex(), e.toHex()).joinToString("|")
        }
    }

    private fun b32(v: BigInteger): ByteArray {
        val b = v.toByteArray(); val out = ByteArray(32)
        val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
        System.arraycopy(src, 0, out, 32 - src.size, src.size); return out
    }

    /**
     * RFC 6979 (HMAC-SHA256) nonce candidates for key [x] over [hash], `_rfc6979_nonce_candidates`;
     * [skip] drops that many valid candidates first (the retry rule).
     */
    internal fun nonce(x: BigInteger, hash: ByteArray, skip: Int = 0): BigInteger {
        val seed = b32(x) + b32(BigInteger(1, hash).mod(N))
        var v = ByteArray(32) { 1 }
        var k = ByteArray(32)
        k = Hashes.hmacSha256(k, v + byteArrayOf(0) + seed); v = Hashes.hmacSha256(k, v)
        k = Hashes.hmacSha256(k, v + byteArrayOf(1) + seed); v = Hashes.hmacSha256(k, v)
        var left = skip
        while (true) {
            v = Hashes.hmacSha256(k, v)
            val c = BigInteger(1, v)
            if (c.signum() > 0 && c < N) { if (left == 0) return c; left-- }
            k = Hashes.hmacSha256(k, v + byteArrayOf(0)); v = Hashes.hmacSha256(k, v)
        }
    }

    /** The message the deterministic nonce signs: legacy `txid:vout` [utxo], index, P, P2. */
    internal fun nonceHash(utxo: String, index: Int, p: ByteArray, p2: ByteArray): ByteArray {
        val u = utxo.toByteArray(Charsets.UTF_8)
        return Hashes.sha256(NONCE_DOMAIN + Wire.le(u.size.toLong(), 4).reversedArray() + u + byteArrayOf(index.toByte()) + p + p2)
    }

    /**
     * `generate_podle` for UTXO key [x] spending [utxo] (`txid:vout`, legacy form) at NUMS
     * [index]. Deterministic: the same inputs give the vectors' proof byte for byte.
     */
    fun generate(x: BigInteger, utxo: String, index: Int): Commitment {
        require(x.signum() > 0 && x < N) { "invalid private key" }
        val j = numsPoint(index)
        val p = Secp256k1.compress(Secp256k1.publicPoint(x))
        val p2 = Secp256k1.compress(Secp256k1.multiplySecret(x, j))
        val h = nonceHash(utxo, index, p, p2)
        var attempt = 0
        while (true) {
            val k = nonce(x, h, attempt++)
            val kg = Secp256k1.compress(Secp256k1.publicPoint(k))
            val kj = Secp256k1.compress(Secp256k1.multiplySecret(k, j))
            val e = Hashes.sha256(kg + kj + p + p2)
            val eInt = BigInteger(1, e).mod(N)
            if (eInt.signum() == 0) continue
            val s = Secp256k1.addSecret(k, eInt.multiply(x).mod(N))
            if (s.signum() == 0) continue
            return Commitment(p, p2, b32(s), e, Hashes.sha256(p2), utxo, index)
        }
    }

    /**
     * `verify_podle`: `sha256(P2) == commitment`, `0 < s < n`, and for some index in [indices]
     * `sha256((sG - eP) || (sJ - eP2) || P || P2) == e`.
     */
    fun verify(p: ByteArray, p2: ByteArray, s: ByteArray, e: ByteArray, commitment: ByteArray, indices: IntRange = DEFAULT_INDICES): Boolean {
        if (p.size != 33 || p2.size != 33 || s.size != 32 || e.size != 32 || commitment.size != 32) return false
        if (!Hashes.sha256(p2).contentEquals(commitment)) return false
        val pp = JmSig.point(p) ?: return false
        val pp2 = JmSig.point(p2) ?: return false
        val sInt = BigInteger(1, s)
        if (sInt.signum() == 0 || sInt >= N) return false
        val minusE = BigInteger(1, e).negate().mod(N)
        if (minusE.signum() == 0) return false
        val kg = Secp256k1.add(Secp256k1.multiply(sInt, Secp256k1.G), Secp256k1.multiply(minusE, pp))
        if (kg.isInfinity) return false
        val ep2 = Secp256k1.multiply(minusE, pp2)
        for (i in indices) {
            val kj = Secp256k1.add(Secp256k1.multiply(sInt, numsPoint(i)), ep2)
            if (kj.isInfinity) continue
            if (Hashes.sha256(Secp256k1.compress(kg) + Secp256k1.compress(kj) + p + p2).contentEquals(e)) return true
        }
        return false
    }

    /** A parsed `!auth` revelation. */
    data class Revelation(
        val txid: String, val vout: Long, val scriptPubKeyHex: String?, val height: Int?,
        val p: ByteArray, val p2: ByteArray, val s: ByteArray, val e: ByteArray,
    )

    /** Parse `utxo|P|P2|s|e` (field lengths 66/66/64/64 hex), legacy or extended utxo. */
    fun parseRevelation(text: String): Revelation {
        val f = text.split("|")
        require(f.size == 5) { "revelation needs 5 fields" }
        require(f[1].length == 66 && f[2].length == 66 && f[3].length == 64 && f[4].length == 64) { "bad revelation field length" }
        val u = Utxos.parse(f[0])
        return Revelation(u.txid, u.vout, u.scriptPubKeyHex, u.height, Wire.hex(f[1]), Wire.hex(f[2]), Wire.hex(f[3]), Wire.hex(f[4]))
    }

    /**
     * `verify_podle_binding`: P controls [scriptPubKey] as P2WPKH, P2PKH or P2SH-P2WPKH (the
     * taker only ever uses P2WPKH, but makers check all three).
     */
    fun bindsTo(p: ByteArray, scriptPubKey: ByteArray): Boolean {
        if (p.size != 33) return false
        val h = Hashes.hash160(p)
        val spk = scriptPubKey
        fun u(i: Int) = spk[i].toInt() and 0xFF
        return when {
            spk.size == 22 && u(0) == 0x00 && u(1) == 0x14 -> spk.copyOfRange(2, 22).contentEquals(h)
            spk.size == 25 && u(0) == 0x76 && u(1) == 0xa9 && u(2) == 0x14 && u(23) == 0x88 && u(24) == 0xac ->
                spk.copyOfRange(3, 23).contentEquals(h)
            spk.size == 23 && u(0) == 0xa9 && u(1) == 0x14 && u(22) == 0x87 ->
                spk.copyOfRange(2, 22).contentEquals(Hashes.hash160(byteArrayOf(0x00, 0x14) + h))
            else -> false
        }
    }
}

/**
 * UTXO references on the wire (`UTXOMetadata` in `jmcore/protocol.py`): legacy `txid:vout` or
 * extended `txid:vout:scriptpubkey_hex:blockheight`, comma-separated in lists.
 */
object Utxos {
    data class Ref(val txid: String, val vout: Long, val scriptPubKeyHex: String? = null, val height: Int? = null) {
        val legacy: String get() = "$txid:$vout"
        val extended: String get() = if (scriptPubKeyHex != null && height != null) "$legacy:$scriptPubKeyHex:$height" else legacy
    }

    /** `UTXOMetadata.from_str` with its validation (64-hex txid, uint32 vout, 4..200 hex-char spk, height >= 0). */
    fun parse(s: String): Ref {
        val parts = s.split(":")
        require(parts.size == 2 || parts.size == 4) { "invalid UTXO format" }
        val txid = parts[0]
        require(txid.length == 64) { "invalid txid length" }
        Wire.hex(txid)
        val vout = parts[1].toLong()
        require(vout in 0..0xffffffffL) { "invalid vout" }
        if (parts.size == 2) return Ref(txid, vout)
        val spk = parts[2]
        require(spk.length in 4..200) { "invalid scriptpubkey" }
        Wire.hex(spk)
        val height = parts[3].toInt()
        require(height >= 0) { "invalid blockheight" }
        return Ref(txid, vout, spk, height)
    }

    /** `parse_utxo_list`: comma-separated refs (empty string = none). */
    fun parseList(s: String): List<Ref> = if (s.isEmpty()) emptyList() else s.split(",").map { parse(it.trim()) }

    /** `format_utxo_list`. */
    fun formatList(refs: List<Ref>, extended: Boolean): String =
        refs.joinToString(",") { if (extended) it.extended else it.legacy }
}
