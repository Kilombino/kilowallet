package com.kilombino.pyblockwatch.ark

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Ark backup file: the words, the engine's config and its database with the signed
 * recovery transactions. With it the coins can be withdrawn on-chain even if the Ark
 * server disappears; the words alone depend on the server to hand the coins back.
 *
 * The JSON holds the words and, only when there is one, their BIP-39 passphrase.
 *
 * Layout: "KARKBAK1", one flag byte (0 = plain, 1 = password), then for a password a
 * 16-byte salt and 12-byte IV, then gzip(JSON) — AES-256-GCM encrypted for a password,
 * with the key from PBKDF2-HMAC-SHA256.
 */
object ArkBackup {
    private val MAGIC = "KARKBAK1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 310_000

    class Snapshot(
        val words: List<String>,
        /** The words' BIP-39 passphrase, "" for none. */
        val passphrase: String = "",
        val config: String,
        val db: ByteArray,
        val dbWal: ByteArray?,
        val movements: Int,
        val created: Long,
    )

    class WrongPassword : Exception("Wrong password.")

    fun isEncrypted(file: ByteArray): Boolean {
        check(file)
        return file[MAGIC.size].toInt() == 1
    }

    fun encode(s: Snapshot, password: String?): ByteArray {
        val json = JSONObject()
            .put("format", "kilombino-ark-backup")
            // Version 2 only when there is a passphrase: an older app, which would restore
            // the words without it and open the wrong wallet, refuses the file instead.
            .put("version", if (s.passphrase.isEmpty()) 1 else 2)
            .put("network", "mainnet")
            .put("created", s.created)
            .put("movements", s.movements)
            .put("mnemonic", s.words.joinToString(" "))
            .apply { if (s.passphrase.isNotEmpty()) put("passphrase", s.passphrase) }
            .put("config", s.config)
            .put("db", Base64.encodeToString(s.db, Base64.NO_WRAP))
            .apply { s.dbWal?.let { put("db_wal", Base64.encodeToString(it, Base64.NO_WRAP)) } }
        val payload = gzip(json.toString().toByteArray(Charsets.UTF_8))
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        if (password.isNullOrEmpty()) {
            out.write(0)
            out.write(payload)
        } else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.ENCRYPT_MODE, deriveKey(password, salt)) }
            out.write(1)
            out.write(salt)
            out.write(cipher.iv)
            out.write(cipher.doFinal(payload))
        }
        return out.toByteArray()
    }

    fun decode(file: ByteArray, password: String?): Snapshot {
        check(file)
        var pos = MAGIC.size + 1
        val payload = if (file[MAGIC.size].toInt() == 1) {
            if (password.isNullOrEmpty()) throw WrongPassword()
            val salt = file.copyOfRange(pos, pos + 16); pos += 16
            val iv = file.copyOfRange(pos, pos + 12); pos += 12
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv)) }
            try { cipher.doFinal(file, pos, file.size - pos) } catch (e: AEADBadTagException) { throw WrongPassword() }
        } else {
            file.copyOfRange(pos, file.size)
        }
        val j = JSONObject(String(gunzip(payload), Charsets.UTF_8))
        require(j.optString("format") == "kilombino-ark-backup") { "Not a Kilombino Ark backup." }
        require(j.optInt("version") in 1..2) { "This backup was made by a newer version of the app." }
        require(j.optString("network") == "mainnet") { "This backup is not for XBT mainnet." }
        return Snapshot(
            words = j.getString("mnemonic").trim().split(Regex("\\s+")),
            passphrase = j.optString("passphrase", ""),
            config = j.getString("config"),
            db = Base64.decode(j.getString("db"), Base64.NO_WRAP),
            dbWal = j.optString("db_wal").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.NO_WRAP) },
            movements = j.optInt("movements"),
            created = j.optLong("created"),
        )
    }

    private fun check(file: ByteArray) {
        require(file.size > MAGIC.size + 1 && file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "Not a Kilombino Ark backup."
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    private fun gzip(b: ByteArray): ByteArray =
        ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun gunzip(b: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
}
