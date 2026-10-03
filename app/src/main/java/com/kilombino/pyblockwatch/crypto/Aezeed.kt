package com.kilombino.pyblockwatch.crypto

/**
 * LND's aezeed: 24 words (the BIP-39 English list) encoding 33 bytes — version, 23 bytes of
 * AEZ ciphertext, a 5-byte scrypt salt and a CRC-32C checksum. Deciphering with the passphrase
 * ("aezeed" when none was set) gives the wallet birthday and the 16 bytes of entropy that LND
 * uses directly as its BIP-32 seed. Checked against LND's own vectors.
 */
object Aezeed {

    class Seed(val entropy: ByteArray, val birthdayDays: Int)

    class WrongPassphrase : Exception("Wrong passphrase for this LND seed (or a mistyped word that still passes the checksum).")

    private val CRC32C = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x82F63B78.toInt() else c ushr 1 }
        c
    }

    private fun crc32c(b: ByteArray, len: Int): Int {
        var c = -1
        for (i in 0 until len) c = CRC32C[(c xor b[i].toInt()) and 0xFF] xor (c ushr 8)
        return c.inv()
    }

    /** [scryptN] is LND's 32768; LND's own unit-test vectors were made with 16. */
    fun decode(words: List<String>, passphrase: String, scryptN: Int = 32768): Seed {
        require(words.size == 24) { "An LND seed has 24 words, got ${words.size}." }
        val bits = StringBuilder()
        for (w in words) {
            val idx = Bip39Wordlist.INDEX[w] ?: throw IllegalArgumentException("\"$w\" is not a seed word.")
            bits.append(idx.toString(2).padStart(11, '0'))
        }
        val raw = ByteArray(33) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
        require(raw[0].toInt() == 0) { "Unknown aezeed version ${raw[0]}: is this an LND seed?" }
        val sum = ((raw[29].toInt() and 0xFF) shl 24) or ((raw[30].toInt() and 0xFF) shl 16) or
            ((raw[31].toInt() and 0xFF) shl 8) or (raw[32].toInt() and 0xFF)
        require(crc32c(raw, 29) == sum) { "These words do not form an LND seed (checksum mismatch)." }
        val salt = raw.copyOfRange(24, 29)
        val pass = (passphrase.ifEmpty { "aezeed" }).toByteArray(Charsets.UTF_8)
        val key = Scrypt.derive(pass, salt, scryptN, 8, 1, 32)
        val ad = byteArrayOf(raw[0]) + salt
        val plain = Aez.decrypt(key, ByteArray(0), listOf(ad), 4, raw.copyOfRange(1, 24))
            ?: throw WrongPassphrase()
        val birthday = ((plain[1].toInt() and 0xFF) shl 8) or (plain[2].toInt() and 0xFF)
        return Seed(plain.copyOfRange(3, 19), birthday)
    }
}
