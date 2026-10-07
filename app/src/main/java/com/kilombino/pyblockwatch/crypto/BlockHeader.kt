package com.kilombino.pyblockwatch.crypto

import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import java.math.BigInteger

/**
 * Block headers, enough to check a server's word for a payment (SPV): the merkle root a
 * transaction must prove into, and the proof of work that makes a header expensive to fake.
 *
 * A BLAKE2b-chain header is 164 bytes: the classic 80, then nonce2, nonce3, extranonce,
 * time offset, tx count, flags, XOR clear bits, XOR key, height and the merge-mining hook.
 * Its block hash (which is also its proof of work) follows the reference code in rust-bitcoin's
 * blake2b_header.rs byte for byte; the tests pin it against real mainnet headers.
 */
object BlockHeader {
    class Parsed(val raw: ByteArray) {
        private fun le32(o: Int): Long = (0 until 4).fold(0L) { a, i -> a or ((raw[o + i].toLong() and 0xff) shl (8 * i)) }
        val version: Long get() = le32(0)
        val extended: Boolean get() = (version and 0x80000000L) != 0L
        /** Merkle root as serialised (internal byte order). */
        val merkleRoot: ByteArray get() = raw.copyOfRange(36, 68)
        val bits: Long get() = le32(72)
    }

    fun parse(hex: String): Parsed {
        val b = Hashes.hexToBytes(hex)
        require(b.size == 80 || b.size == 164) { "a header is 80 or 164 bytes, not ${b.size}" }
        val p = Parsed(b)
        require(p.extended == (b.size == 164)) { "header size does not match its version" }
        return p
    }

    private fun tag(t: String, data: ByteArray): ByteArray {
        val th = Hashes.sha256(t.toByteArray())
        return Hashes.sha256(th + th + data)
    }

    private fun u32(v: Long) = ByteArray(4) { ((v ushr (8 * it)) and 0xff).toByte() }

    /** The block hash in display order (hex, as an explorer shows it). */
    fun blockHash(h: Parsed): String {
        val r = h.raw
        if (!h.extended) return Hashes.doubleSha256(r).reversedArray().toHex()
        fun le(o: Int, n: Int): Long = (0 until n).fold(0L) { a, i -> a or ((r[o + i].toLong() and 0xff) shl (8 * i)) }
        val prevDisplay = r.copyOfRange(4, 36).reversedArray()
        val time = le(68, 4)
        val nonce = le(76, 4)
        val nonce2 = le(80, 4); val nonce3 = le(84, 4)
        val extranonce = r.copyOfRange(88, 104)
        val timeOffset = le(104, 4)
        val txcount = le(108, 2)
        val flags = r[110].toInt() and 0xff
        val clearBits = r[111].toInt() and 0xff
        val xorKey = r.copyOfRange(112, 128)
        val height = r.copyOfRange(128, 132)
        val mmRhs = r.copyOfRange(132, 164)

        val mask = ByteArray(32)
        if (xorKey.any { it.toInt() != 0 }) {
            val m = tag("Bitcoin block hash PoW XOR mask", xorKey)
            val full = clearBits / 8
            for (i in 0 until minOf(full, 32)) m[i] = 0
            if (full < 32) m[full] = (m[full].toInt() and (0xff ushr (clearBits % 8))).toByte()
            System.arraycopy(m, 0, mask, 0, 32)
        }
        val h1 = r.copyOfRange(0, 4) + prevDisplay + height + h.merkleRoot + u32(time) + byteArrayOf(0) +
            r.copyOfRange(72, 76) + u32(txcount) + byteArrayOf(flags.toByte(), clearBits.toByte()) +
            tag("Bitcoin block hash PoW XOR key", xorKey)
        val h2 = tag("Merge-mining hook", tag("Bitcoin block header 1", h1) + ByteArray(32) + mmRhs)
        val first = Blake2b.digest(ByteArray(4) + h2 + extranonce, 32)
        var second = when (flags and 3) {
            0 -> tag("Bitcoin prevblock header, hashed", prevDisplay).also { for (i in 0 until 6) it[i] = 0 }
            2 -> ByteArray(48) + h2
            3 -> ByteArray(80) + h2
            else -> ByteArray(0)
        }
        second += u32(nonce) + u32(nonce2)
        second += if ((flags and 3) == 1) u32(nonce3) + u32(timeOffset) else u32(timeOffset) + u32(nonce3)
        second += first
        if ((flags and 3) == 1) second += h2
        val result = Blake2b.digest(second, 32)
        for (i in 0 until 32) result[i] = (result[i].toInt() xor mask[i].toInt()).toByte()
        // rust-bitcoin reverses this into its internal order, and display order reverses it back.
        return result.toHex()
    }

    /** The target [bits] encodes (compact form). */
    fun target(bits: Long): BigInteger {
        val exp = ((bits ushr 24) and 0xff).toInt()
        val mant = BigInteger.valueOf(bits and 0x007fffff)
        return if (exp <= 3) mant.shiftRight(8 * (3 - exp)) else mant.shiftLeft(8 * (exp - 3))
    }

    /** True when the header's hash is at or below the target its own bits claim. */
    fun meetsItsTarget(h: Parsed): Boolean = BigInteger(blockHash(h), 16) <= target(h.bits)

    /**
     * Whether [txid] is in the block of [header], by the Electrum merkle branch [branch] (hex,
     * display order, as `blockchain.transaction.get_merkle` returns them) at position [pos].
     */
    fun inBlock(txid: String, branch: List<String>, pos: Int, header: Parsed): Boolean {
        var h = Hashes.hexToBytes(txid).reversedArray()
        var p = pos
        for (s in branch) {
            val sib = Hashes.hexToBytes(s).reversedArray()
            h = Hashes.doubleSha256(if (p and 1 == 0) h + sib else sib + h)
            p = p shr 1
        }
        return h.contentEquals(header.merkleRoot)
    }
}
