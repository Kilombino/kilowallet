package com.kilombino.pyblockwatch.coinjoin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where a round running on its own keeps the keys of its coins between app starts, so the one
 * fingerprint given when creating or joining the pool is the only one: they are encrypted with
 * a Keystore key of this app (hardware-backed where the phone has it, never exportable) and
 * deleted when the round ends or stops running on its own.
 *
 * The trade-off, said plainly: without a fingerprint to unlock them, these keys are as safe as
 * the app's own storage (like the Lightning node's wallet). They are only the keys of the coins
 * in a live round, and [PoolSession] still signs only a transaction that pays our exact output
 * and change.
 */
object AutoKeyVault {
    private const val ALIAS = "kilowallet_cj_autokeys"
    private const val PREFS = "cj_autokeys"

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

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(ctx: Context, poolId: String, keys: List<BigInteger>) {
        runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val plain = keys.joinToString(",") { it.toString(16) }.toByteArray()
            val blob = c.iv + c.doFinal(plain)
            prefs(ctx).edit().putString(poolId, blob.joinToString("") { "%02x".format(it) }).apply()
        }
    }

    fun load(ctx: Context, poolId: String): List<BigInteger>? = runCatching {
        val hex = prefs(ctx).getString(poolId, null) ?: return null
        val raw = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(c.doFinal(raw.copyOfRange(12, raw.size))).split(',').filter { it.isNotEmpty() }.map { BigInteger(it, 16) }
    }.getOrNull()

    fun remove(ctx: Context, poolId: String) = prefs(ctx).edit().remove(poolId).apply()

    /** Drops the keys of every pool not in [keep]. */
    fun keepOnly(ctx: Context, keep: Set<String>) {
        val p = prefs(ctx)
        // A perfect coin's key ("exact:…") waits for its pool and is not a round's: left alone.
        // Nor a perpetual coinjoin's branch key ("perpbranch:…"), which [Perpetual] keeps and drops itself.
        val gone = p.all.keys.filter { it !in keep && !it.startsWith("exact:") && !it.startsWith("perpbranch:") }
        if (gone.isNotEmpty()) p.edit().apply { gone.forEach { remove(it) } }.apply()
    }
}
