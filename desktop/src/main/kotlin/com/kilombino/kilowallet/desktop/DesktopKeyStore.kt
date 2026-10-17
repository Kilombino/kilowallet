package com.kilombino.kilowallet.desktop

import android.content.DesktopContext
import android.security.keystore.KeyGenParameterSpec
import com.kilombino.pyblockwatch.crypto.Scrypt
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.Cipher
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android's Keystore on the desktop. The wallet's code asks "AndroidKeyStore" for its keys
 * (the one that encrypts the recovery words, Ark's, Lightning's, the coinjoin auto-keys); here
 * they are random AES keys kept in one file, each wrapped (AES-GCM) under a master key derived
 * from the wallet's password with scrypt. Without the password the file is useless; while the
 * app is unlocked the master key lives only in memory.
 *
 * The password protects the disk. A running, unlocked copy holds the master key: the background
 * work (Lightning, coinjoin on its own, Ark renewals, your node's RPC) needs it. It can lock
 * itself after a while without use ([autoLockMinutes]; off unless chosen), or be locked by hand,
 * and then asks for the password again; the background work that needs a key waits meanwhile.
 * A wrong password costs a growing wait (1, 2, 4… up to 60 seconds).
 */
object DesktopKeyStore {
    private val file: File get() = File(DesktopContext.root("no_backup"), "keystore.json")
    private const val CHECK = "kilowallet desktop keystore v1"

    @Volatile private var master: SecretKey? = null

    fun exists(): Boolean = file.exists()
    fun unlocked(): Boolean = master != null
    fun lock() { master = null }

    // --- wrong passwords: each one doubles the wait before the next is tried

    @Volatile private var failures = 0
    @Volatile private var blockedUntil = 0L

    /** Seconds before another password is tried, or 0. */
    fun waitSeconds(): Int = ((blockedUntil - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)

    private fun failed() {
        failures++
        blockedUntil = System.currentTimeMillis() + 1000L * minOf(60, 1 shl minOf(failures - 1, 6))
    }

    // --- locking after a while without use

    @Volatile private var lastUse = System.currentTimeMillis()
    private val autoLockFile: File get() = File(DesktopContext.root("no_backup"), "autolock")

    /** Minutes without use before the app locks itself; 0 = never (the default). */
    var autoLockMinutes: Int
        get() = runCatching { autoLockFile.readText().trim().toInt() }.getOrDefault(0).coerceIn(0, 24 * 60)
        set(v) { runCatching { autoLockFile.writeText(v.coerceIn(0, 24 * 60).toString()); DesktopContext.private(autoLockFile) } }

    /** Something was typed or clicked in the window. */
    fun touch() { lastUse = System.currentTimeMillis() }

    /** Locks when the app has gone unused for longer than chosen; true if it did. */
    fun lockIfIdle(): Boolean {
        val m = autoLockMinutes
        if (m == 0 || master == null || System.currentTimeMillis() - lastUse < m * 60_000L) return false
        lock()
        return true
    }

    /** First run: sets the password; the file starts empty. */
    @Synchronized fun create(password: CharArray) {
        check(!exists()) { "a keystore already exists" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = derive(password, salt, N)
        val o = JSONObject().put("v", 1).put("salt", hex(salt)).put("n", N).put("check", wrap(key, CHECK.toByteArray())).put("keys", JSONObject())
        write(o)
        master = key
    }

    /** True when [password] is the wallet's; it then stays unlocked. */
    @Synchronized fun unlock(password: CharArray): Boolean {
        val k = tryPassword(password) ?: return false
        master = k
        touch()
        return true
    }

    /** Checks the password without changing anything (to confirm before a spend). */
    fun verify(password: CharArray): Boolean = tryPassword(password) != null

    @Synchronized fun changePassword(old: CharArray, new: CharArray): Boolean {
        val oldKey = tryPassword(old) ?: return false
        val o = read()
        val keys = o.getJSONObject("keys")
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val newKey = derive(new, salt, N)
        val re = JSONObject()
        for (alias in keys.keys()) re.put(alias, wrap(newKey, unwrap(oldKey, keys.getString(alias))))
        write(o.put("salt", hex(salt)).put("n", N).put("check", wrap(newKey, CHECK.toByteArray())).put("keys", re))
        master = newKey
        return true
    }

    /** Forgets everything (a wallet wipe): the next start asks for a new password. */
    @Synchronized fun wipe() { master = null; file.delete() }

    private fun tryPassword(password: CharArray): SecretKey? {
        if (waitSeconds() > 0) return null // still waiting after a wrong one: not even tried
        val o = runCatching { read() }.getOrNull() ?: return null
        val k = derive(password, unhex(o.getString("salt")), o.getInt("n"))
        val ok = runCatching { if (String(unwrap(k, o.getString("check"))) == CHECK) k else null }.getOrNull()
        if (ok == null) failed() else failures = 0
        return ok
    }

    // --- the keys the wallet's code asks for, by alias

    @Synchronized internal fun get(alias: String): SecretKey? {
        val keys = runCatching { read().getJSONObject("keys") }.getOrNull() ?: return null
        val w = keys.optString(alias, "").ifEmpty { return null }
        val m = master ?: throw android.security.keystore.UserNotAuthenticatedException("Kilowallet is locked: enter its password.")
        return SecretKeySpec(unwrap(m, w), "AES")
    }

    @Synchronized internal fun generate(alias: String): SecretKey {
        val m = master ?: throw android.security.keystore.UserNotAuthenticatedException("Kilowallet is locked: enter its password.")
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val o = read()
        o.getJSONObject("keys").put(alias, wrap(m, raw))
        write(o)
        return SecretKeySpec(raw, "AES")
    }

    @Synchronized internal fun delete(alias: String) {
        val o = runCatching { read() }.getOrNull() ?: return
        o.getJSONObject("keys").remove(alias)
        write(o)
    }

    @Synchronized internal fun aliases(): List<String> =
        runCatching { read().getJSONObject("keys").keys().asSequence().toList() }.getOrDefault(emptyList())

    // --- storage

    /** scrypt cost: 2^17 × 8 × 128 B = 128 MB and about a second on a desktop, once per unlock. */
    private const val N = 1 shl 17

    private fun derive(password: CharArray, salt: ByteArray, n: Int): SecretKey {
        // Straight from the characters to UTF-8 bytes, without an immutable String in between.
        val buf = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(password))
        val pw = ByteArray(buf.remaining()).also { buf.get(it) }
        if (buf.hasArray()) buf.array().fill(0)
        try { return SecretKeySpec(Scrypt.derive(pw, salt, n, 8, 1, 32), "AES") } finally { pw.fill(0) }
    }

    private fun wrap(k: SecretKey, plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
        return hex(c.iv + c.doFinal(plain))
    }

    private fun unwrap(k: SecretKey, w: String): ByteArray {
        val b = unhex(w)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, b, 0, 12)) }
        return c.doFinal(b, 12, b.size - 12)
    }

    private fun read(): JSONObject = JSONObject(file.readText())

    private fun write(o: JSONObject) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString(1))
        DesktopContext.private(tmp)
        check(tmp.renameTo(file)) { "could not save the keystore" }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** Makes KeyStore.getInstance("AndroidKeyStore") and KeyGenerator.getInstance("AES", "AndroidKeyStore") reach this store. */
    fun install() {
        if (java.security.Security.getProvider("AndroidKeyStore") == null) java.security.Security.addProvider(DesktopProvider())
    }
}

class DesktopProvider : Provider("AndroidKeyStore", "1.0", "Kilowallet desktop keystore") {
    init {
        put("KeyStore.AndroidKeyStore", DesktopKeyStoreSpi::class.java.name)
        put("KeyGenerator.AES", DesktopKeyGeneratorSpi::class.java.name)
    }
}

class DesktopKeyStoreSpi : KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?): Key? = DesktopKeyStore.get(alias)
    override fun engineGetEntry(alias: String, p: KeyStore.ProtectionParameter?): KeyStore.Entry? =
        DesktopKeyStore.get(alias)?.let { KeyStore.SecretKeyEntry(it) }
    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
    override fun engineGetCertificate(alias: String?): Certificate? = null
    override fun engineGetCreationDate(alias: String?): Date? = null
    override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) = throw UnsupportedOperationException()
    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = throw UnsupportedOperationException()
    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = throw UnsupportedOperationException()
    override fun engineDeleteEntry(alias: String) = DesktopKeyStore.delete(alias)
    override fun engineAliases(): Enumeration<String> = Collections.enumeration(DesktopKeyStore.aliases())
    override fun engineContainsAlias(alias: String): Boolean = alias in DesktopKeyStore.aliases()
    override fun engineSize(): Int = DesktopKeyStore.aliases().size
    override fun engineIsKeyEntry(alias: String): Boolean = engineContainsAlias(alias)
    override fun engineIsCertificateEntry(alias: String?): Boolean = false
    override fun engineGetCertificateAlias(cert: Certificate?): String? = null
    override fun engineStore(stream: OutputStream?, password: CharArray?) {}
    override fun engineLoad(stream: InputStream?, password: CharArray?) {}
}

class DesktopKeyGeneratorSpi : KeyGeneratorSpi() {
    private var alias: String? = null
    override fun engineInit(random: SecureRandom?) = throw IllegalStateException("a KeyGenParameterSpec is needed")
    override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
        alias = (params as? KeyGenParameterSpec ?: throw java.security.InvalidAlgorithmParameterException("not a KeyGenParameterSpec")).keystoreAlias
    }
    override fun engineInit(keysize: Int, random: SecureRandom?) = throw IllegalStateException("a KeyGenParameterSpec is needed")
    override fun engineGenerateKey(): SecretKey = DesktopKeyStore.generate(alias ?: error("not initialised"))
}
