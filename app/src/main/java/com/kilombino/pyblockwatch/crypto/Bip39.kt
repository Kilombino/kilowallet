package com.kilombino.pyblockwatch.crypto

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * BIP-39: turn entropy into a mnemonic, and a mnemonic into the 64-byte seed that BIP-32
 * grows a whole wallet from.
 *
 * Entropy can come from the system RNG or, for people who trust dice over a phone's RNG,
 * from physical dice the SeedSigner way — see [entropyFromDiceRolls]. Either way the seed
 * derivation (PBKDF2-HMAC-SHA512, 2048 rounds) is the platform's, and the checksum and
 * word mapping are pinned by the official BIP-39 vectors in the tests.
 */
object Bip39 {

    private val WORDS = Bip39Wordlist.WORDS
    private val INDEX = Bip39Wordlist.INDEX

    /** Encode entropy (16/20/24/28/32 bytes) as a mnemonic with its checksum word(s). */
    fun fromEntropy(entropy: ByteArray): List<String> {
        require(entropy.size in intArrayOf(16, 20, 24, 28, 32)) {
            "entropy must be 128–256 bits in 32-bit steps, got ${entropy.size * 8} bits"
        }
        val checksumBits = entropy.size * 8 / 32
        val hash = Hashes.sha256(entropy)
        // Bit string: entropy bits followed by the first `checksumBits` bits of SHA256(entropy).
        val totalBits = entropy.size * 8 + checksumBits
        val words = ArrayList<String>(totalBits / 11)
        var acc = 0
        var bits = 0
        fun feed(byte: Int, take: Int) {
            for (i in 7 downTo 8 - take) {
                acc = (acc shl 1) or ((byte ushr i) and 1)
                bits++
                if (bits == 11) { words.add(WORDS[acc]); acc = 0; bits = 0 }
            }
        }
        for (b in entropy) feed(b.toInt() and 0xFF, 8)
        // Append exactly `checksumBits` bits from the hash's first byte(s).
        var remaining = checksumBits
        var hi = 0
        while (remaining > 0) {
            val take = minOf(8, remaining)
            feed(hash[hi].toInt() and 0xFF, take)
            remaining -= take; hi++
        }
        return words
    }

    /** True when the words are all in the list and the checksum matches. */
    fun isValid(mnemonic: List<String>): Boolean {
        if (mnemonic.size !in intArrayOf(12, 15, 18, 21, 24)) return false
        val bits = StringBuilder()
        for (w in mnemonic) {
            val idx = INDEX[w] ?: return false
            bits.append(idx.toString(2).padStart(11, '0'))
        }
        val entBits = mnemonic.size * 11 * 32 / 33
        val csBits = mnemonic.size * 11 - entBits
        val entropy = ByteArray(entBits / 8)
        for (i in entropy.indices) {
            entropy[i] = bits.substring(i * 8, i * 8 + 8).toInt(2).toByte()
        }
        val hash = Hashes.sha256(entropy)
        val expected = StringBuilder()
        for (i in 0 until csBits) {
            expected.append((hash[i / 8].toInt() ushr (7 - i % 8)) and 1)
        }
        return bits.substring(entBits) == expected.toString()
    }

    /**
     * PBKDF2-HMAC-SHA512(mnemonic, "mnemonic"+passphrase, 2048, 512 bits) → 64-byte seed.
     * Both are NFKD-normalised as BIP-39 requires, so a passphrase with accents gives the
     * same seed here as in any other wallet.
     */
    fun toSeed(mnemonic: List<String>, passphrase: String = ""): ByteArray {
        val phrase = nfkd(mnemonic.joinToString(" "))
        val salt = "mnemonic${nfkd(passphrase)}".toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(phrase.toCharArray(), salt, 2048, 512)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return factory.generateSecret(spec).encoded
    }

    private fun nfkd(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD)

    /**
     * SeedSigner-compatible dice entropy: SHA-256 over the roll string (each die a digit
     * '1'..'6'), truncated to the requested strength. 50 rolls give a 12-word seed, 99 a
     * 24-word one — the same rolls in SeedSigner produce the same seed, so a paranoid user
     * can cross-check on air-gapped hardware.
     */
    fun entropyFromDiceRolls(rolls: String, strengthBits: Int): ByteArray {
        require(strengthBits == 128 || strengthBits == 256) { "strength must be 128 or 256 bits" }
        val needed = if (strengthBits == 128) 50 else 99
        require(rolls.length == needed) { "need exactly $needed dice rolls, got ${rolls.length}" }
        require(rolls.all { it in '1'..'6' }) { "each roll must be a digit 1–6" }
        return Hashes.sha256(rolls.toByteArray(Charsets.US_ASCII)).copyOf(strengthBits / 8)
    }
}
