package com.kilombino.pyblockwatch.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The one secret a hot wallet keeps: the mnemonic, encrypted at rest.
 *
 * The encryption key lives in the Android Keystore (hardware-backed where the device has a
 * TEE/StrongBox) and is created with `setUserAuthenticationRequired(true)`, so the key cannot
 * be used to decrypt — or re-encrypt — the seed without the user passing a fresh biometric or
 * device-credential check. The plaintext seed never touches disk; only the ciphertext and its
 * GCM IV sit in app-private prefs. Because the auth is bound to the [Cipher] via
 * BiometricPrompt's CryptoObject, a rooted-but-locked attacker cannot exercise the key either.
 *
 * The caller drives the prompt: get an [encryptCipher] or [decryptCipher], hand it to
 * BiometricPrompt, and only the authorised cipher that comes back can [store] or [reveal].
 */
class SeedVault(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("pyblockwatch_seed", Context.MODE_PRIVATE)

    fun hasSeed(): Boolean = prefs.contains(KEY_BLOB)

    private fun keystore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? =
        (keystore().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Every use needs a fresh check; biometric OR the device PIN/pattern is accepted.
                    setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1) // -1 = require auth for every use
                }
            }
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    /** A cipher primed to ENCRYPT the seed. Authorise it with BiometricPrompt, then call [store]. */
    fun encryptCipher(): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }

    /** A cipher primed to DECRYPT the stored seed. Authorise it, then call [reveal]. */
    fun decryptCipher(): Cipher {
        val iv = Base64.decode(prefs.getString(KEY_IV, null) ?: error("no seed stored"), Base64.NO_WRAP)
        val key = existingKey() ?: error("seed key missing")
        return Cipher.getInstance(TRANSFORMATION)
            .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
    }

    /** The mnemonic and its optional BIP-39 passphrase ("" when there is none). */
    class Secret(val words: List<String>, val passphrase: String)

    /** True when the stored seed has a passphrase. Not secret: it only says one exists. */
    fun hasPassphrase(): Boolean = prefs.getBoolean(KEY_HAS_PASSPHRASE, false)

    /**
     * Encrypt and persist the mnemonic, and its passphrase if any, with an authorised
     * [encryptCipher]. The passphrase follows the words after a newline, inside the same
     * ciphertext; a seed without one is stored exactly as before.
     */
    fun store(cipher: Cipher, mnemonic: List<String>, passphrase: String = "") {
        require('\n' !in passphrase) { "the passphrase cannot contain a line break" }
        val text = mnemonic.joinToString(" ") + if (passphrase.isEmpty()) "" else "\n$passphrase"
        val ciphertext = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_BLOB, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putBoolean(KEY_HAS_PASSPHRASE, passphrase.isNotEmpty())
            .apply()
    }

    /** Recover the mnemonic and passphrase with an authorised [decryptCipher]. */
    fun revealSecret(cipher: Cipher): Secret {
        val ciphertext = Base64.decode(prefs.getString(KEY_BLOB, null) ?: error("no seed stored"), Base64.NO_WRAP)
        val text = String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        val words = text.substringBefore('\n').trim().split(" ")
        val passphrase = if ('\n' in text) text.substringAfter('\n') else ""
        return Secret(words, passphrase)
    }

    /** Recover just the mnemonic with an authorised [decryptCipher]. */
    fun reveal(cipher: Cipher): List<String> = revealSecret(cipher).words

    fun clear() {
        prefs.edit().clear().apply()
        runCatching { keystore().deleteEntry(ALIAS) }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "pyblockwatch_seed_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BLOB = "seed_blob"
        const val KEY_IV = "seed_iv"
        const val KEY_HAS_PASSPHRASE = "seed_has_passphrase"
    }
}
