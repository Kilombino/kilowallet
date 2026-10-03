package com.kilombino.pyblockwatch.crypto

/**
 * AEZ v5 decryption, the subset LND's aezeed needs: a key of any length (extracted with
 * BLAKE2b-384), an empty nonce, associated data, and a ciphertext under 32 bytes (AEZ-tiny).
 * A straight port of Yawning Angel's Go implementation (CC0), which LND uses; checked against
 * LND's own aezeed vectors.
 */
object Aez {
    private const val BS = 16

    // AES T-table for the round function, built from the S-box: te0[x] = (2s, s, s, 3s).
    private val SBOX = IntArray(256).also { s ->
        var p = 1; var q = 1
        do {
            p = p xor ((p shl 1) and 0xFF) xor (if (p and 0x80 != 0) 0x1B else 0)
            q = q xor (q shl 1); q = q xor (q shl 2); q = q xor (q shl 4); q = q and 0xFF
            if (q and 0x80 != 0) q = q xor 0x09
            val x = q xor Integer.rotateLeft(q, 1) xor Integer.rotateLeft(q, 2) xor
                Integer.rotateLeft(q, 3) xor Integer.rotateLeft(q, 4)
            s[p] = ((x xor (x ushr 8)) xor 0x63) and 0xFF
        } while (p != 1)
        s[0] = 0x63
    }
    private fun xtime(b: Int) = ((b shl 1) xor (if (b and 0x80 != 0) 0x1B else 0)) and 0xFF
    internal val TE0 = IntArray(256) { val s = SBOX[it]; (xtime(s) shl 24) or (s shl 16) or (s shl 8) or (xtime(s) xor s) }
    private val TE1 = IntArray(256) { Integer.rotateRight(TE0[it], 8) }
    private val TE2 = IntArray(256) { Integer.rotateRight(TE0[it], 16) }
    private val TE3 = IntArray(256) { Integer.rotateRight(TE0[it], 24) }

    private fun be32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun xor16(a: ByteArray, b: ByteArray) = ByteArray(BS) { (a[it].toInt() xor b[it].toInt()).toByte() }

    private fun double(p: ByteArray): ByteArray {
        val out = ByteArray(BS)
        for (i in 0 until 15) out[i] = (((p[i].toInt() shl 1) or ((p[i + 1].toInt() and 0xFF) ushr 7)) and 0xFF).toByte()
        out[15] = (((p[15].toInt() shl 1) and 0xFF) xor (if (p[0].toInt() and 0x80 != 0) 135 else 0)).toByte()
        return out
    }

    private fun mult(x: Int, src: ByteArray): ByteArray {
        var t = src.copyOf(); var r = ByteArray(BS); var n = x
        while (n != 0) { if (n and 1 != 0) r = xor16(r, t); t = double(t); n = n ushr 1 }
        return r
    }

    private class State(key: ByteArray) {
        val ext: ByteArray = if (key.size == 48) key.copyOf() else Blake2b.digest(key, 48)
        val i0 = ext.copyOfRange(0, 16); val i1 = double(i0)
        val j0 = ext.copyOfRange(16, 32); val j1 = double(j0); val j2 = double(j1)
        val l = Array(8) { ByteArray(BS) }.also { l ->
            l[1] = ext.copyOfRange(32, 48); l[2] = double(l[1]); l[3] = xor16(l[2], l[1])
            l[4] = double(l[2]); l[5] = xor16(l[4], l[1]); l[6] = double(l[3]); l[7] = xor16(l[6], l[1])
        }
        private val keys = IntArray(12) { be32(ext, it * 4) }
        private val aes4Key = keys.copyOfRange(4, 8) + keys.copyOfRange(0, 4) + keys.copyOfRange(8, 12) + IntArray(4) // J I L 0
        private val aes10Key = keys + keys + keys + keys.copyOfRange(0, 4)                           // (I J L)x3 I

        private fun rounds(block: ByteArray, n: Int): ByteArray {
            val k = if (n == 4) aes4Key else aes10Key
            var s0 = be32(block, 0); var s1 = be32(block, 4); var s2 = be32(block, 8); var s3 = be32(block, 12)
            for (r in 0 until n) {
                val o = r * 4
                val t0 = TE0[s0 ushr 24] xor TE1[(s1 ushr 16) and 0xFF] xor TE2[(s2 ushr 8) and 0xFF] xor TE3[s3 and 0xFF] xor k[o]
                val t1 = TE0[s1 ushr 24] xor TE1[(s2 ushr 16) and 0xFF] xor TE2[(s3 ushr 8) and 0xFF] xor TE3[s0 and 0xFF] xor k[o + 1]
                val t2 = TE0[s2 ushr 24] xor TE1[(s3 ushr 16) and 0xFF] xor TE2[(s0 ushr 8) and 0xFF] xor TE3[s1 and 0xFF] xor k[o + 2]
                val t3 = TE0[s3 ushr 24] xor TE1[(s0 ushr 16) and 0xFF] xor TE2[(s1 ushr 8) and 0xFF] xor TE3[s2 and 0xFF] xor k[o + 3]
                s0 = t0; s1 = t1; s2 = t2; s3 = t3
            }
            val out = ByteArray(BS)
            for ((idx, s) in intArrayOf(s0, s1, s2, s3).withIndex()) {
                out[idx * 4] = (s ushr 24).toByte(); out[idx * 4 + 1] = (s ushr 16).toByte()
                out[idx * 4 + 2] = (s ushr 8).toByte(); out[idx * 4 + 3] = s.toByte()
            }
            return out
        }

        fun aes4(j: ByteArray, i: ByteArray, l: ByteArray, src: ByteArray) =
            rounds(ByteArray(BS) { (j[it].toInt() xor i[it].toInt() xor l[it].toInt() xor src[it].toInt()).toByte() }, 4)

        fun aes10(l: ByteArray, src: ByteArray) = rounds(xor16(src, l), 10)
    }

    private val ZERO = ByteArray(BS)

    private fun hash(e: State, nonce: ByteArray, ad: List<ByteArray>, tauBits: Int): ByteArray {
        val buf = ByteArray(BS).also { it[12] = (tauBits ushr 24).toByte(); it[13] = (tauBits ushr 16).toByte(); it[14] = (tauBits ushr 8).toByte(); it[15] = tauBits.toByte() }
        var sum = e.aes4(xor16(e.j0, e.j1), e.i1, e.l[1], buf)
        fun absorb(data: ByteArray, j: ByteArray) {
            var i = e.i1.copyOf(); var off = 0; var idx = 1
            while (data.size - off >= BS) {
                sum = xor16(sum, e.aes4(j, i, e.l[idx % 8], data.copyOfRange(off, off + BS)))
                off += BS; if (idx % 8 == 0) i = double(i); idx++
            }
            val rem = data.size - off
            if (rem > 0 || data.isEmpty()) {
                val b = ByteArray(BS); System.arraycopy(data, off, b, 0, rem); b[rem] = 0x80.toByte()
                sum = xor16(sum, e.aes4(j, e.i0, e.l[0], b))
            }
        }
        absorb(nonce, e.j2)
        ad.forEachIndexed { k, p -> absorb(p, mult(5 + k, e.j0)) }
        return sum
    }

    /** AEZ-tiny decipher (d = 1) of [input] under 32 bytes. */
    private fun tinyDecipher(e: State, delta: ByteArray, input: ByteArray): ByteArray {
        val n = input.size
        var i = 7
        val rounds = when { n == 1 -> 24; n == 2 -> 16; n < 16 -> 10; else -> { i = 6; 8 } }
        val l = ByteArray(BS); val r = ByteArray(BS)
        System.arraycopy(input, 0, l, 0, (n + 1) / 2)
        System.arraycopy(input, n / 2, r, 0, (n + 1) / 2)
        var mask = 0x00; var pad = 0x80
        if (n and 1 != 0) {
            for (k in 0 until n / 2) r[k] = (((r[k].toInt() shl 4) or ((r[k + 1].toInt() and 0xFF) ushr 4)) and 0xFF).toByte()
            r[n / 2] = ((r[n / 2].toInt() shl 4) and 0xFF).toByte()
            pad = 0x08; mask = 0xF0
        }
        if (n < 16) {
            val b = ByteArray(BS); System.arraycopy(input, 0, b, 0, n); b[0] = (b[0].toInt() or 0x80).toByte()
            val t = e.aes4(ZERO, e.i1, e.l[3], xor16(delta, b))
            l[0] = (l[0].toInt() xor (t[0].toInt() and 0x80)).toByte()
        }
        var j = rounds - 1
        val step = -1
        for (k in 0 until rounds / 2) {
            var b = ByteArray(BS); System.arraycopy(r, 0, b, 0, (n + 1) / 2)
            b[n / 2] = ((b[n / 2].toInt() and mask) or pad).toByte()
            b = xor16(b, delta); b[15] = (b[15].toInt() xor j).toByte()
            var t = e.aes4(ZERO, e.i1, e.l[i], b)
            for (x in 0 until BS) l[x] = (l[x].toInt() xor t[x].toInt()).toByte()

            b = ByteArray(BS); System.arraycopy(l, 0, b, 0, (n + 1) / 2)
            b[n / 2] = ((b[n / 2].toInt() and mask) or pad).toByte()
            b = xor16(b, delta); b[15] = (b[15].toInt() xor (j + step)).toByte()
            t = e.aes4(ZERO, e.i1, e.l[i], b)
            for (x in 0 until BS) r[x] = (r[x].toInt() xor t[x].toInt()).toByte()
            j += 2 * step
        }
        val buf = ByteArray(2 * BS)
        System.arraycopy(r, 0, buf, 0, n / 2)
        System.arraycopy(l, 0, buf, n / 2, (n + 1) / 2)
        if (n and 1 != 0) {
            for (k in n - 1 downTo n / 2 + 1) buf[k] = ((((buf[k].toInt() and 0xFF) ushr 4) or (buf[k - 1].toInt() shl 4)) and 0xFF).toByte()
            buf[n / 2] = ((((l[0].toInt() and 0xFF) ushr 4) or (r[n / 2].toInt() and 0xF0)) and 0xFF).toByte()
        }
        return buf.copyOf(n)
    }

    /**
     * Decrypt and authenticate; null when the tag (the last [tau] zero bytes) does not check,
     * i.e. a wrong key or passphrase, or tampered data.
     */
    fun decrypt(key: ByteArray, nonce: ByteArray, ad: List<ByteArray>, tau: Int, ciphertext: ByteArray): ByteArray? {
        require(ciphertext.size > tau && ciphertext.size < 32) { "only AEZ-tiny ciphertexts are supported" }
        val e = State(key)
        val delta = hash(e, nonce, ad, tau * 8)
        val x = tinyDecipher(e, delta, ciphertext)
        var sum = 0
        for (k in 0 until tau) sum = sum or x[x.size - tau + k].toInt()
        return if (sum == 0) x.copyOf(x.size - tau) else null
    }
}
