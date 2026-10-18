package com.kilombino.pyblockwatch.ln

import android.content.Context
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Moving the Lightning node to another phone with its channels open: the node's whole state
 * (wallet, channel database, macaroons, its wallet password) packed and encrypted with a
 * password the user chooses. A channel backup can only close channels; this keeps them.
 *
 * The danger is two copies: if the old phone ever ran the node again it would publish an old
 * channel state, which the peer punishes by taking the whole channel. So the export stops the
 * node and deletes it from this phone once the file is written and read back.
 */
object LnMove {
    /** v2: the key from scrypt (memory-hard, 2^16 × 8: 64 MB). v1 files (PBKDF2) still open. */
    private val MAGIC = "KWLNMOVE2".toByteArray(Charsets.US_ASCII)
    private val MAGIC_V1 = "KWLNMOVE1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 310_000
    private const val SCRYPT_N = 1 shl 16
    /** The file holds the node's keys: a password at least this long (an offline guess is cheap with a short one). */
    const val MIN_PASSWORD = 12

    /** The chain sync data: large, and the new phone downloads it again. */
    private val SKIP = setOf("neutrino.db", "block_headers.bin", "reg_filter_headers.bin")

    /**
     * Stops the node, packs it encrypted with [password], checks the pack opens again, and
     * only then deletes the node here. Returns the pack; the caller saves it to a file.
     */
    suspend fun export(ctx: Context, password: String): ByteArray {
        require(password.length >= MIN_PASSWORD) { "The password needs at least $MIN_PASSWORD characters." }
        val app = ctx.applicationContext
        // Marked before anything stops: if the app dies between writing the file and forgetting
        // the node here, it must not bring this node up again on its own (the file may already
        // be running on the other phone).
        app.getSharedPreferences("lightning", Context.MODE_PRIVATE).edit().putBoolean("moving", true).commit()
        Lightning.watch(false)
        Lnd.stop()
        delay(6_000)    // LND lets go of its database files a little after StopDaemon answers
        val dir = Lnd.dir(app)
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            dir.walkTopDown().filter { it.isFile && it.name !in SKIP && !it.path.contains("/logs/") }.forEach { f ->
                z.putNextEntry(ZipEntry(f.relativeTo(dir).path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
            z.putNextEntry(ZipEntry("kilowallet/password")); z.write(LnSeed.password(app)); z.closeEntry()
            z.putNextEntry(ZipEntry("kilowallet/birthday"))
            z.write(app.getSharedPreferences("lightning", Context.MODE_PRIVATE).getLong("birthday", 0L).toString().toByteArray())
            z.closeEntry()
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(password, salt, v1 = false)) }
        val pack = MAGIC + salt + c.iv + c.doFinal(zip.toByteArray())
        // Read it back before anything is deleted: a pack that does not open is no move.
        check(entries(pack, password).any { it.first.endsWith("wallet.db") }) { "The pack did not read back" }
        return pack
    }

    /** The move did not happen (no file, or it could not be saved): this node may start here again. */
    fun cancelMove(ctx: Context) {
        ctx.applicationContext.getSharedPreferences("lightning", Context.MODE_PRIVATE).edit().putBoolean("moving", false).apply()
    }

    /** After the pack is saved: the node no longer lives on this phone. */
    fun forgetHere(ctx: Context) {
        val app = ctx.applicationContext
        Lnd.dir(app).deleteRecursively()
        app.getSharedPreferences("lightning", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", false).putBoolean("moved_away", true).putBoolean("moving", false).apply()
        Lightning.markOff()
    }

    /** Puts a moved node in place before Lightning is turned on here. */
    fun import(ctx: Context, pack: ByteArray, password: String) {
        val app = ctx.applicationContext
        check(!Lnd.running) { "Lightning is running on this phone already" }
        val files = entries(pack, password)
        require(files.any { it.first.endsWith("wallet.db") }) { "This pack has no Lightning wallet" }
        val dir = Lnd.dir(app)
        dir.deleteRecursively(); dir.mkdirs()
        val prefs = app.getSharedPreferences("lightning", Context.MODE_PRIVATE).edit()
        for ((name, bytes) in files) {
            when (name) {
                "kilowallet/password" -> LnSeed.setPassword(app, bytes)
                "kilowallet/birthday" -> String(bytes).toLongOrNull()?.takeIf { it > 0 }?.let { prefs.putLong("birthday", it) }
                else -> {
                    val f = File(dir, name)
                    require(f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { "bad entry $name" }
                    f.parentFile?.mkdirs(); f.writeBytes(bytes)
                }
            }
        }
        prefs.putBoolean("enabled", true).putBoolean("moved_away", false).apply()
    }

    class WrongPassword : Exception("Wrong password, or not a Lightning move file.")

    private fun entries(pack: ByteArray, password: String): List<Pair<String, ByteArray>> {
        if (pack.size < MAGIC.size + 28) throw WrongPassword()
        val head = pack.copyOfRange(0, MAGIC.size)
        val v1 = head.contentEquals(MAGIC_V1)
        if (!v1 && !head.contentEquals(MAGIC)) throw WrongPassword()
        var p = MAGIC.size
        val salt = pack.copyOfRange(p, p + 16); p += 16
        val iv = pack.copyOfRange(p, p + 12); p += 12
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(password, salt, v1), GCMParameterSpec(128, iv)) }
                .doFinal(pack, p, pack.size - p)
        } catch (e: javax.crypto.AEADBadTagException) { throw WrongPassword() }
        val out = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(plain)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) out += e.name to z.readBytes() }
        }
        return out
    }

    private fun key(password: String, salt: ByteArray, v1: Boolean): SecretKeySpec {
        if (!v1) {
            val pw = password.toByteArray(Charsets.UTF_8)
            try { return SecretKeySpec(com.kilombino.pyblockwatch.crypto.Scrypt.derive(pw, salt, SCRYPT_N, 8, 1, 32), "AES") } finally { pw.fill(0) }
        }
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)).encoded
        return SecretKeySpec(raw, "AES")
    }
}
