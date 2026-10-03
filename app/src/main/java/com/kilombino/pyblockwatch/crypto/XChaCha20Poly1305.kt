package com.kilombino.pyblockwatch.crypto

import java.math.BigInteger

/**
 * XChaCha20-Poly1305 decryption (RFC 8439 with the 24-byte-nonce extension), as LND uses it
 * to encrypt channel.backup. Pinned by a backup made with LND's own code.
 */
object XChaCha20Poly1305 {

    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun rounds(x: IntArray) {
        fun qr(a: Int, b: Int, c: Int, d: Int) {
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
        }
        repeat(10) {
            qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15)
            qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14)
        }
    }

    private fun state(key: ByteArray, counter: Int, nonce12: ByteArray) = IntArray(16).also { s ->
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le32(key, i * 4)
        s[12] = counter
        for (i in 0 until 3) s[13 + i] = le32(nonce12, i * 4)
    }

    private fun block(key: ByteArray, counter: Int, nonce12: ByteArray): ByteArray {
        val s = state(key, counter, nonce12); val x = s.copyOf(); rounds(x)
        val out = ByteArray(64)
        for (i in 0 until 16) { val v = x[i] + s[i]; for (j in 0 until 4) out[i * 4 + j] = (v ushr (8 * j)).toByte() }
        return out
    }

    /** HChaCha20: the subkey for an extended nonce. */
    private fun hchacha(key: ByteArray, nonce16: ByteArray): ByteArray {
        val x = IntArray(16)
        x[0] = 0x61707865; x[1] = 0x3320646e; x[2] = 0x79622d32; x[3] = 0x6b206574
        for (i in 0 until 8) x[4 + i] = le32(key, i * 4)
        for (i in 0 until 4) x[12 + i] = le32(nonce16, i * 4)
        rounds(x)
        val out = ByteArray(32)
        for ((k, i) in intArrayOf(0, 1, 2, 3, 12, 13, 14, 15).withIndex()) for (j in 0 until 4) out[k * 4 + j] = (x[i] ushr (8 * j)).toByte()
        return out
    }

    private fun poly1305(key: ByteArray, msg: ByteArray): ByteArray {
        val p = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
        val rBytes = key.copyOfRange(0, 16).also {
            it[3] = (it[3].toInt() and 15).toByte(); it[7] = (it[7].toInt() and 15).toByte()
            it[11] = (it[11].toInt() and 15).toByte(); it[15] = (it[15].toInt() and 15).toByte()
            it[4] = (it[4].toInt() and 252).toByte(); it[8] = (it[8].toInt() and 252).toByte(); it[12] = (it[12].toInt() and 252).toByte()
        }
        fun leNum(b: ByteArray) = BigInteger(1, b.reversedArray())
        val r = leNum(rBytes); val s = leNum(key.copyOfRange(16, 32))
        var acc = BigInteger.ZERO
        var i = 0
        while (i < msg.size) {
            val chunk = msg.copyOfRange(i, minOf(i + 16, msg.size)) + byteArrayOf(1)
            acc = acc.add(leNum(chunk)).multiply(r).mod(p)
            i += 16
        }
        val t = acc.add(s).mod(BigInteger.ONE.shiftLeft(128))
        val tb = t.toByteArray().reversedArray()
        return ByteArray(16) { if (it < tb.size) tb[it] else 0 }
    }

    private fun pad16(n: Int) = ByteArray((16 - n % 16) % 16)
    private fun le64(n: Long) = ByteArray(8) { (n ushr (8 * it)).toByte() }

    /** Open [ciphertext] (with its 16-byte tag at the end); null when it does not authenticate. */
    fun open(key: ByteArray, nonce24: ByteArray, ciphertext: ByteArray, ad: ByteArray): ByteArray? {
        require(key.size == 32 && nonce24.size == 24 && ciphertext.size >= 16)
        val subKey = hchacha(key, nonce24.copyOfRange(0, 16))
        val nonce12 = ByteArray(4) + nonce24.copyOfRange(16, 24)
        val ct = ciphertext.copyOfRange(0, ciphertext.size - 16)
        val tag = ciphertext.copyOfRange(ciphertext.size - 16, ciphertext.size)
        val polyKey = block(subKey, 0, nonce12).copyOf(32)
        val macData = ad + pad16(ad.size) + ct + pad16(ct.size) + le64(ad.size.toLong()) + le64(ct.size.toLong())
        if (!java.security.MessageDigest.isEqual(poly1305(polyKey, macData), tag)) return null
        val out = ByteArray(ct.size)
        var counter = 1; var off = 0
        while (off < ct.size) {
            val ks = block(subKey, counter++, nonce12)
            for (k in 0 until minOf(64, ct.size - off)) out[off + k] = (ct[off + k].toInt() xor ks[k].toInt()).toByte()
            off += 64
        }
        return out
    }
}
