package com.kilombino.pyblockwatch.ln

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.kilombino.pyblockwatch.crypto.Base58
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Secp256k1
import java.math.BigInteger
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the Lightning node's keys come from: the same recovery words as the rest of the
 * wallet, but a master key of its own. LND's on-chain wallet uses the BIP-84 and BIP-86 paths
 * the hot wallet already uses, so the root it gets is not the words' BIP-32 root (both would
 * claim the same coins) but one tagged apart from it, deterministically: the words alone bring
 * the node back.
 */
object LnSeed {
    private const val TAG = "Kilowallet Lightning v1"

    /** The node's BIP-32 master key (mainnet xprv, depth 0), from the BIP-39 seed bytes. */
    fun masterXprv(bip39Seed: ByteArray): String {
        val i = Hashes.hmacSha512(TAG.toByteArray(Charsets.UTF_8), bip39Seed)
        val k = BigInteger(1, i.copyOfRange(0, 32))
        require(k.signum() != 0 && k < Secp256k1.N) { "invalid Lightning master key" }
        val out = ByteArray(78)
        byteArrayOf(0x04, 0x88.toByte(), 0xAD.toByte(), 0xE4.toByte()).copyInto(out, 0)
        // depth 0, parent fingerprint 0, child number 0 (bytes 4..12 stay zero)
        i.copyInto(out, 13, 32, 64)            // chain code
        out[45] = 0
        i.copyInto(out, 46, 0, 32)             // key
        i.fill(0)
        return Base58.encodeChecked(out)
    }

    /**
     * The node's identity key, hex: LND's m/1017'/0'/6'/0/0 under [masterXprv]'s root, the pubkey
     * peers see. Compared with the running node's, it tells whether that node comes from these
     * words (a node moved in from another phone keeps the keys of that phone's words).
     */
    fun nodePubkey(bip39Seed: ByteArray): String {
        val i = Hashes.hmacSha512(TAG.toByteArray(Charsets.UTF_8), bip39Seed)
        val master = com.kilombino.pyblockwatch.crypto.Bip32Priv.ExtendedPrivKey(BigInteger(1, i.copyOfRange(0, 32)), i.copyOfRange(32, 64), 0)
        i.fill(0)
        return com.kilombino.pyblockwatch.crypto.Bip32Priv.derivePath(master, "m/1017'/0'/6'/0/0").publicKey()
            .joinToString("") { "%02x".format(it) }
    }

    /** The pubkey a node made from the wallet's words has, kept when the words were last at hand; null if never. */
    fun expectedPubkey(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("words_pubkey", null)

    fun rememberExpected(ctx: Context, bip39Seed: ByteArray) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("words_pubkey", nodePubkey(bip39Seed)).apply()
    }

    // ---------------------------------------------------------------- the wallet password

    private const val KEY_ALIAS = "kilowallet_lnd_password"
    private const val PREFS = "lightning"

    /**
     * The password LND encrypts its wallet file with: random, made once, kept encrypted with a
     * Keystore key that needs no fingerprint, so the node can start in the background. It
     * protects the file at rest; the channels' money is in the keys LND derives, which come
     * from the recovery words.
     */
    fun password(ctx: Context): ByteArray {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString("pw", null)
        if (stored != null) {
            val raw = Hashes.hexToBytes(stored)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            return c.doFinal(raw.copyOfRange(12, raw.size))
        }
        val pw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hex = pw.joinToString("") { "%02x".format(it) }.toByteArray()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val enc = c.iv + c.doFinal(hex)
        prefs.edit().putString("pw", enc.joinToString("") { "%02x".format(it) }).apply()
        return hex
    }

    /** Takes over the wallet password of a node moved in from another phone ([LnMove]). */
    fun setPassword(ctx: Context, pw: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val enc = c.iv + c.doFinal(pw)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pw", enc.joinToString("") { "%02x".format(it) }).commit()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }
}
