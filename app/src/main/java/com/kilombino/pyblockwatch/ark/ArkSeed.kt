package com.kilombino.pyblockwatch.ark

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Ark wallet's words, encrypted at rest by an Android Keystore key.
 *
 * Unlike the spending wallet's [com.kilombino.pyblockwatch.data.SeedVault], this key does
 * not ask for a fingerprint on every use: the Ark engine has to start in the background to
 * renew coins before they expire. The key is bound to an unlocked device where Android
 * supports it (API 28+), so a locked phone cannot decrypt the words. The engine receives
 * them in memory and never writes them to a file.
 */
internal class ArkSeed(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("kilombino_ark_seed", Context.MODE_PRIVATE)

    fun has(): Boolean = prefs.contains(KEY_BLOB)

    private fun keystore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun key(): SecretKey {
        (keystore().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        // Bound to an unlocked device where possible. Android refuses that on a phone with no
        // screen lock; such a phone gets the plain app-private key (nothing locks it anyway).
        return runCatching { generate(unlockedOnly = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) }
            .getOrElse { generate(unlockedOnly = false) }
    }

    private fun generate(unlockedOnly: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply { if (unlockedOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setUnlockedDeviceRequired(true) }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }.generateKey()
    }

    fun save(words: List<String>) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = cipher.doFinal(words.joinToString(" ").toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
    }

    fun load(): List<String>? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        val iv = Base64.decode(prefs.getString(KEY_IV, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return String(cipher.doFinal(Base64.decode(blob, Base64.NO_WRAP)), Charsets.UTF_8).trim().split(" ")
    }

    fun clear() {
        prefs.edit().clear().commit()
        runCatching { keystore().deleteEntry(ALIAS) }
    }

    /**
     * Wallets made by the first test builds kept the words in a plain `mnemonic` file in the
     * engine's datadir. Move them into the Keystore and delete the file.
     */
    fun migrateFrom(datadir: File) {
        val f = File(datadir, "mnemonic")
        if (!f.exists()) return
        if (!has()) save(f.readText().trim().split(Regex("\\s+")))
        f.delete()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "kilombino_ark_seed_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BLOB = "ark_seed_blob"
        const val KEY_IV = "ark_seed_iv"
    }
}
