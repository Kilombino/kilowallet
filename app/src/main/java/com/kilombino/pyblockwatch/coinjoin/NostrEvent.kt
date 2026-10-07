package com.kilombino.pyblockwatch.coinjoin

import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.Schnorr
import com.kilombino.pyblockwatch.crypto.Secp256k1
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.SecureRandom

/** A NIP-01 event. [id] and [sig] are hex; [pubkey] is the x-only key in hex. */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("pubkey", pubkey).put("created_at", createdAt).put("kind", kind)
        .put("tags", JSONArray().also { a -> tags.forEach { t -> a.put(JSONArray(t)) } })
        .put("content", content).put("sig", sig)

    /** True when the id is the hash of the content and the signature is the author's. */
    fun verify(): Boolean = runCatching {
        val h = hash(pubkey, createdAt, kind, tags, content)
        h.toHex() == id && Schnorr.verify(Hashes.hexToBytes(pubkey), h, Hashes.hexToBytes(sig))
    }.getOrDefault(false)

    companion object {
        /**
         * The NIP-01 serialisation `[0,pubkey,created_at,kind,tags,content]`. Written by hand:
         * org.json escapes "/" as "\/", which would give every event a wrong id.
         */
        internal fun serialize(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
            val sb = StringBuilder("[0,")
            str(sb, pubkey); sb.append(',').append(createdAt).append(',').append(kind).append(",[")
            tags.forEachIndexed { i, t ->
                if (i > 0) sb.append(',')
                sb.append('['); t.forEachIndexed { j, v -> if (j > 0) sb.append(','); str(sb, v) }; sb.append(']')
            }
            sb.append("],"); str(sb, content); sb.append(']')
            return sb.toString()
        }

        private fun str(sb: StringBuilder, s: String) {
            sb.append('"')
            for (c in s) when (c) {
                '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b"); '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
            sb.append('"')
        }

        fun hash(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): ByteArray =
            Hashes.sha256(serialize(pubkey, createdAt, kind, tags, content).toByteArray(Charsets.UTF_8))

        fun sign(secret: BigInteger, kind: Int, tags: List<List<String>>, content: String,
                 createdAt: Long = System.currentTimeMillis() / 1000): NostrEvent {
            val pub = Secp256k1.xOnly(Secp256k1.publicPoint(secret)).toHex()
            val h = hash(pub, createdAt, kind, tags, content)
            return NostrEvent(h.toHex(), pub, createdAt, kind, tags, content, Schnorr.sign(secret, h).toHex())
        }

        fun parse(o: JSONObject): NostrEvent {
            val ta = o.getJSONArray("tags")
            val tags = (0 until ta.length()).map { i ->
                val t = ta.getJSONArray(i); (0 until t.length()).map { t.getString(it) }
            }
            return NostrEvent(o.getString("id"), o.getString("pubkey"), o.getLong("created_at"),
                o.getInt("kind"), tags, o.getString("content"), o.getString("sig"))
        }

        /** A fresh random key: every pool and every role in it gets its own identity. */
        fun newSecret(): BigInteger {
            val r = SecureRandom()
            while (true) {
                val k = BigInteger(1, ByteArray(32).also { r.nextBytes(it) })
                if (k.signum() > 0 && k < Secp256k1.N) return k
            }
        }

        fun pubOf(secret: BigInteger): String = Secp256k1.xOnly(Secp256k1.publicPoint(secret)).toHex()
    }
}
