package com.kilombino.pyblockwatch.crypto

/**
 * scrypt (RFC 7914), as LND's aezeed uses it to turn the seed passphrase into the AEZ key
 * (N = 32768, r = 8, p = 1: 32 MiB of memory). Pinned by the RFC's test vectors.
 */
object Scrypt {

    private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val out = ByteArray(dkLen)
        var block = 1
        var off = 0
        while (off < dkLen) {
            var u = Hashes.hmacSha256(password, salt + byteArrayOf(
                (block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = Hashes.hmacSha256(password, u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val n = minOf(32, dkLen - off)
            System.arraycopy(t, 0, out, off, n)
            off += n; block++
        }
        return out
    }

    private fun salsa208(b: IntArray) {
        val x = b.copyOf()
        fun r(a: Int, n: Int) = Integer.rotateLeft(a, n)
        repeat(4) {
            x[4] = x[4] xor r(x[0] + x[12], 7); x[8] = x[8] xor r(x[4] + x[0], 9)
            x[12] = x[12] xor r(x[8] + x[4], 13); x[0] = x[0] xor r(x[12] + x[8], 18)
            x[9] = x[9] xor r(x[5] + x[1], 7); x[13] = x[13] xor r(x[9] + x[5], 9)
            x[1] = x[1] xor r(x[13] + x[9], 13); x[5] = x[5] xor r(x[1] + x[13], 18)
            x[14] = x[14] xor r(x[10] + x[6], 7); x[2] = x[2] xor r(x[14] + x[10], 9)
            x[6] = x[6] xor r(x[2] + x[14], 13); x[10] = x[10] xor r(x[6] + x[2], 18)
            x[3] = x[3] xor r(x[15] + x[11], 7); x[7] = x[7] xor r(x[3] + x[15], 9)
            x[11] = x[11] xor r(x[7] + x[3], 13); x[15] = x[15] xor r(x[11] + x[7], 18)
            x[1] = x[1] xor r(x[0] + x[3], 7); x[2] = x[2] xor r(x[1] + x[0], 9)
            x[3] = x[3] xor r(x[2] + x[1], 13); x[0] = x[0] xor r(x[3] + x[2], 18)
            x[6] = x[6] xor r(x[5] + x[4], 7); x[7] = x[7] xor r(x[6] + x[5], 9)
            x[4] = x[4] xor r(x[7] + x[6], 13); x[5] = x[5] xor r(x[4] + x[7], 18)
            x[11] = x[11] xor r(x[10] + x[9], 7); x[8] = x[8] xor r(x[11] + x[10], 9)
            x[9] = x[9] xor r(x[8] + x[11], 13); x[10] = x[10] xor r(x[9] + x[8], 18)
            x[12] = x[12] xor r(x[15] + x[14], 7); x[13] = x[13] xor r(x[12] + x[15], 9)
            x[14] = x[14] xor r(x[13] + x[12], 13); x[15] = x[15] xor r(x[14] + x[13], 18)
        }
        for (i in 0 until 16) b[i] += x[i]
    }

    /** BlockMix over 2r 64-byte blocks held as ints, in place via [tmp]. */
    private fun blockMix(b: IntArray, r: Int, tmp: IntArray) {
        val x = b.copyOfRange((2 * r - 1) * 16, 2 * r * 16)
        for (i in 0 until 2 * r) {
            for (k in 0 until 16) x[k] = x[k] xor b[i * 16 + k]
            salsa208(x)
            // Even blocks go to the first half, odd blocks to the second.
            val dst = (i / 2 + (i % 2) * r) * 16
            System.arraycopy(x, 0, tmp, dst, 16)
        }
        System.arraycopy(tmp, 0, b, 0, 32 * r)
    }

    fun derive(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, dkLen: Int): ByteArray {
        require(n > 1 && n and (n - 1) == 0) { "N must be a power of two" }
        val blockInts = 32 * r
        val b = pbkdf2Sha256(password, salt, 1, p * 128 * r)
        val v = IntArray(n * blockInts)
        val tmp = IntArray(blockInts)
        for (pi in 0 until p) {
            val x = IntArray(blockInts) { k ->
                val o = pi * 128 * r + k * 4
                (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                    ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
            }
            for (i in 0 until n) { System.arraycopy(x, 0, v, i * blockInts, blockInts); blockMix(x, r, tmp) }
            for (i in 0 until n) {
                val j = x[(2 * r - 1) * 16] and (n - 1)
                for (k in 0 until blockInts) x[k] = x[k] xor v[j * blockInts + k]
                blockMix(x, r, tmp)
            }
            for (k in 0 until blockInts) {
                val o = pi * 128 * r + k * 4
                b[o] = x[k].toByte(); b[o + 1] = (x[k] ushr 8).toByte()
                b[o + 2] = (x[k] ushr 16).toByte(); b[o + 3] = (x[k] ushr 24).toByte()
            }
        }
        return pbkdf2Sha256(password, b, 1, dkLen)
    }
}
