package com.kilombino.pyblockwatch.roblo

import com.kilombino.pyblockwatch.coinjoin.Nip44
import com.kilombino.pyblockwatch.coinjoin.NostrEvent
import com.kilombino.pyblockwatch.crypto.Hashes
import org.json.JSONObject
import java.math.BigInteger

/**
 * The coordinator's notices to a robot: NIP-17 private messages, NIP-59 gift-wrapped. A kind-1059
 * wrap (signed by a throwaway key, created_at randomised) holds, NIP-44 encrypted to the robot, a
 * kind-13 seal signed by the coordinator, which holds, NIP-44 encrypted again, the unsigned
 * kind-14 rumor: the notice's text and its tags (order_id "kilobot/24", action "1" when the
 * robot has to do something). The same unwrapping as the Roblosats app (quartz), on Kilowallet's
 * own NIP-44 and Schnorr.
 */
object GiftWrap {
    data class Rumor(
        val id: String,
        val pubkey: String,
        val createdAt: Long,
        val kind: Int,
        val tags: List<List<String>>,
        val content: String,
    ) {
        fun tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
    }

    /**
     * The rumor inside [wrap] for the robot whose key is [robotSecret], or null when it is not a
     * well-formed gift wrap for that robot: wrong kind, not decryptable, seal not signed by its
     * author, or a rumor that claims another author than the seal's.
     */
    fun unwrap(wrap: JSONObject, robotSecret: BigInteger): Rumor? = runCatching {
        if (wrap.getInt("kind") != 1059) return null
        val w = NostrEvent.parse(wrap)
        if (!w.verify()) return null
        val sealJson = Nip44.decrypt(w.content, Nip44.conversationKey(robotSecret, Hashes.hexToBytes(w.pubkey))) ?: return null
        val seal = NostrEvent.parse(JSONObject(sealJson))
        if (seal.kind != 13 || !seal.verify()) return null
        val rumorJson = Nip44.decrypt(seal.content, Nip44.conversationKey(robotSecret, Hashes.hexToBytes(seal.pubkey))) ?: return null
        val r = JSONObject(rumorJson)
        val pub = r.getString("pubkey")
        if (pub != seal.pubkey) return null
        val ta = r.getJSONArray("tags")
        val tags = (0 until ta.length()).map { i -> val t = ta.getJSONArray(i); (0 until t.length()).map { t.getString(it) } }
        val createdAt = r.getLong("created_at"); val kind = r.getInt("kind"); val content = r.getString("content")
        // A rumor's id is not signed but must still be its hash: it names the notice for the dedup.
        val id = NostrEvent.hash(pub, createdAt, kind, tags, content).joinToString("") { "%02x".format(it) }
        if (r.has("id") && r.getString("id") != id) return null
        Rumor(id, pub, createdAt, kind, tags, content)
    }.getOrNull()
}
