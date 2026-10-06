package com.kilombino.pyblockwatch.coinjoin

import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Secp256k1
import com.kilombino.pyblockwatch.crypto.XChaCha20Poly1305
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * NIP-44 v2 encryption (ChaCha20 + HMAC-SHA256, padded), the payload format nostr clients
 * use for private messages. Pinned by the official vectors (paulmillr/nip44).
 */
object Nip44 {
    private val SALT = "nip44-v2".toByteArray()

    private fun b32(v: BigInteger): ByteArray {
        val b = v.toByteArray(); val out = ByteArray(32)
        val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
        System.arraycopy(src, 0, out, 32 - src.size, src.size); return out
    }

    /** Shared secret between our [secret] and their x-only [pubX], as HKDF-extract. */
    fun conversationKey(secret: BigInteger, pubX: ByteArray): ByteArray {
        val shared = Secp256k1.multiply(secret, Secp256k1.liftX(BigInteger(1, pubX)))
        return Hashes.hmacSha256(SALT, b32(shared.x!!))
    }

    /** HKDF-expand(convKey, nonce, 76) → (chacha key, chacha nonce, hmac key). */
    internal fun messageKeys(convKey: ByteArray, nonce: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0); var i = 1
        while (out.size() < 76) { t = Hashes.hmacSha256(convKey, t + nonce + byteArrayOf(i.toByte())); out.write(t); i++ }
        val okm = out.toByteArray()
        return Triple(okm.copyOfRange(0, 32), okm.copyOfRange(32, 44), okm.copyOfRange(44, 76))
    }

    internal fun paddedLen(len: Int): Int {
        if (len <= 32) return 32
        val nextPower = 1 shl (32 - Integer.numberOfLeadingZeros(len - 1))
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((len - 1) / chunk + 1)
    }

    fun encrypt(plaintext: String, convKey: ByteArray, nonce: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }): String {
        val p = plaintext.toByteArray(Charsets.UTF_8)
        require(p.size in 1..65535) { "NIP-44 message length out of range" }
        val padded = ByteArray(2 + paddedLen(p.size))
        padded[0] = (p.size ushr 8).toByte(); padded[1] = p.size.toByte()
        System.arraycopy(p, 0, padded, 2, p.size)
        val (key, n12, hmacKey) = messageKeys(convKey, nonce)
        val ct = XChaCha20Poly1305.chacha20(key, n12, padded)
        val mac = Hashes.hmacSha256(hmacKey, nonce + ct)
        return Base64.getEncoder().encodeToString(byteArrayOf(2) + nonce + ct + mac)
    }

    /** Null when the payload is malformed or its MAC does not match. */
    fun decrypt(payload: String, convKey: ByteArray): String? = runCatching {
        if (payload.startsWith("#")) return null
        val d = Base64.getDecoder().decode(payload)
        if (d.size < 99 || d[0].toInt() != 2) return null
        val nonce = d.copyOfRange(1, 33)
        val ct = d.copyOfRange(33, d.size - 32)
        val mac = d.copyOfRange(d.size - 32, d.size)
        val (key, n12, hmacKey) = messageKeys(convKey, nonce)
        if (!MessageDigest.isEqual(mac, Hashes.hmacSha256(hmacKey, nonce + ct))) return null
        val padded = XChaCha20Poly1305.chacha20(key, n12, ct)
        val len = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        if (len == 0 || padded.size != 2 + paddedLen(len)) return null
        String(padded, 2, len, Charsets.UTF_8)
    }.getOrNull()
}
