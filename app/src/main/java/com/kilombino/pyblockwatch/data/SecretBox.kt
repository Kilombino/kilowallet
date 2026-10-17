package com.kilombino.pyblockwatch.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secrets kept in settings (your own node's RPC passwords), encrypted with a key of this
 * app's own in the system keystore, so the settings file alone does not give them away. The
 * key needs no unlock: the background checks read them with the phone locked. On a computer the
 * keystore is the one opened with the wallet's password.
 */
object SecretBox {
    private const val ALIAS = "kilowallet_settings_secrets"
    private const val PREFIX = "sealed1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    fun isSealed(s: String?) = s != null && s.startsWith(PREFIX)

    /** [plain] encrypted, or null if the keystore cannot be used. */
    fun seal(plain: String): String? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        PREFIX + (c.iv + c.doFinal(plain.toByteArray())).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** The text [sealed] holds, or null if it cannot be opened. */
    fun open(sealed: String): String? = runCatching {
        val raw = sealed.removePrefix(PREFIX).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(c.doFinal(raw.copyOfRange(12, raw.size)))
    }.getOrNull()
}
