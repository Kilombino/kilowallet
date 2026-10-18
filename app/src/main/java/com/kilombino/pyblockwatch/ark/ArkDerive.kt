package com.kilombino.pyblockwatch.ark

import com.kilombino.pyblockwatch.crypto.Bip39
import com.kilombino.pyblockwatch.crypto.Hashes

/** The derivation behind [Ark.derivedWords], apart so it can be tested without Android. */
object ArkDerive {
    private val KEY = "Kilowallet Ark v1".toByteArray()

    fun words(words: List<String>, passphrase: String): List<String> {
        val seed = Bip39.toSeed(words, passphrase)
        try {
            val h = Hashes.hmacSha512(KEY, seed)
            try { return Bip39.fromEntropy(h.copyOf(32)) } finally { h.fill(0) }
        } finally { seed.fill(0) }
    }
}
