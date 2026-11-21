package com.kilombino.pyblockwatch.roblo

import com.kilombino.pyblockwatch.coinjoin.NostrEvent
import com.kilombino.pyblockwatch.crypto.Hashes
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest

/**
 * What Kilowallet's background watcher reads from the client's storage ([RobloStore]): the robots
 * of the garage ("garage_slots", as the client saves it), the coordinators' nostr keys and relays.
 * The robot's nostr key is the client's: SHA-256(SHA-512(token)), checked against the public key
 * the client saved with it.
 */
object RobloGarage {
    /** One robot: its nostr keys, and the order it follows (null: none). */
    data class Robot(val pubkey: String, val secret: BigInteger, val hashId: String, val active: Order?)

    data class Order(val shortAlias: String, val id: Long, val status: Int, val onchain: Boolean, val pendingOnchain: Boolean) {
        val path get() = "$shortAlias/$id"
    }

    /** Statuses in which the coordinator may need the robot soon: the watcher keeps a connection open. */
    val LIVE_STATUSES = setOf(0, 1, 3, 6, 7, 8, 9, 10, 11, 16)

    fun secretOf(token: String): BigInteger {
        val h = MessageDigest.getInstance("SHA-256").digest(MessageDigest.getInstance("SHA-512").digest(token.toByteArray(Charsets.UTF_8)))
        return BigInteger(1, h)
    }

    /** secp256k1's group order: a robot's secret must be in 1..N-1 to be a key at all. */
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

    /**
     * The robots of [garageSlots], each one read on its own: a slot that is not a robot, has no
     * usable key or saved another key than its token's is left out and never stops the others,
     * and nothing here throws (Roblosats 0.9.13: a robot with a bad key killed its app's
     * notification service). One snapshot: callers read "garage_slots" once and pass it in.
     */
    fun robots(garageSlots: String): List<Robot> {
        if (garageSlots.isBlank()) return emptyList()
        val g = runCatching { JSONObject(garageSlots) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Robot>()
        for (k in g.keys()) runCatching { robot(g.optJSONObject(k)) }.getOrNull()?.let { out += it }
        return out
    }

    private fun robot(s: JSONObject?): Robot? {
        s ?: return null
        val token = s.optString("token", "").takeIf { it.isNotEmpty() && it != "null" } ?: return null
        val sec = secretOf(token)
        if (sec.signum() <= 0 || sec >= N) return null
        val pub = NostrEvent.pubOf(sec)
        if (!pub.matches(HEX64)) return null
        val saved = s.optString("nostrPubKey", "")
        if (saved.isNotEmpty() && saved != pub) return null // not the key the client uses: leave it
        val active = s.optJSONObject("activeOrder")?.let { o ->
            val alias = o.optString("shortAlias", ""); val id = o.optLong("id", 0)
            if (alias.isEmpty() || id <= 0) null else {
                val onchain = o.opt("escrow_type").let { it == "onchain" || it == 1 }
                val st = o.optInt("status", -1)
                val pending = onchain && st in setOf(12, 14, 17, 18) &&
                    (o.optInt("escrow_status_code", -1) in setOf(2, 4) || o.optInt("bond_status_code", -1) == 1)
                Order(alias, id, st, onchain, pending)
            }
        }
        return Robot(pub, sec, s.optString("hashId", ""), active)
    }

    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /** The order that keeps the watcher connected: the first one in a live status. */
    fun liveOrder(robots: List<Robot>): Order? =
        robots.mapNotNull { it.active }.firstOrNull { it.status in LIVE_STATUSES || it.pendingOnchain }

    /** The coordinators' relays the client keeps ("federation_relays": a JSON list of ws(s) addresses). */
    fun relays(federationRelays: String): List<String> = runCatching {
        val a = JSONArray(federationRelays)
        (0 until a.length()).map { a.getString(it).trim() }.filter { it.startsWith("ws://") || it.startsWith("wss://") }.distinct()
    }.getOrDefault(emptyList())

    /** The coordinators' nostr keys ("federation_pubkeys", JSON list or any text with the hex keys in it). */
    fun coordinatorKeys(federationPubkeys: String): Set<String> =
        Regex("[0-9a-f]{64}").findAll(federationPubkeys.lowercase()).map { it.value }.toSet()

    internal fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    internal fun bytes(h: String) = Hashes.hexToBytes(h)
}

/**
 * Each notice shown once, ever, as the Roblosats app does (NotificationDedup, 0.9.6): the ids of the
 * rumors already shown are kept (300 newest), and nothing older than the last one shown comes back.
 * Same storage keys as the client, so a relay replaying old gift wraps repeats nothing.
 */
object RobloDedup {
    const val KEY_LAST = "last_notification"
    const val KEY_SEEN = "notified_rumors"
    const val MAX_SEEN = 300

    interface Store { fun get(key: String): String; fun set(key: String, value: String) }

    fun parse(raw: String): MutableMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        if (raw.isEmpty()) return out
        for (item in raw.split(';')) {
            val i = item.lastIndexOf(':'); if (i <= 0) continue
            val ts = item.substring(i + 1).toLongOrNull() ?: continue
            out[item.substring(0, i)] = ts
        }
        return out
    }

    fun format(seen: Map<String, Long>): String =
        seen.entries.sortedByDescending { it.value }.take(MAX_SEEN).joinToString(";") { "${it.key}:${it.value}" }

    @Synchronized
    fun shouldNotify(store: Store, rumorId: String, createdAt: Long): Boolean {
        val seen = parse(store.get(KEY_SEEN))
        if (seen.containsKey(rumorId)) return false
        val last = store.get(KEY_LAST).toLongOrNull()
        if (last != null && createdAt < last) return false
        seen[rumorId] = createdAt
        store.set(KEY_SEEN, format(seen))
        if (last == null || createdAt > last) store.set(KEY_LAST, createdAt.toString())
        return true
    }
}

/**
 * Taps on a Roblosats notification: "open order kilobot/24 with robot <hex>". Validated as the
 * Roblosats app does (the order path and robot come from a coordinator's message), kept until the
 * page takes it ([RobloBridge.takePendingNavigation]), as {"path": …, "robot": …}.
 */
object RobloLinks {
    const val ACTION = "com.kilombino.pyblockwatch.ROBLO_OPEN"
    const val EXTRA_PATH = "roblo_path"
    const val EXTRA_ROBOT = "roblo_robot"
    private val PATH = Regex("^[A-Za-z0-9_-]{1,64}/[0-9]{1,12}$")
    private val ROBOT = Regex("^[0-9a-f]{64}$")

    /** The navigation for the page, or null when [path] is not a valid order path. */
    fun navigation(path: String?, robot: String?): String? {
        if (path == null || !PATH.matches(path)) return null
        return JSONObject().put("path", path).put("robot", robot?.takeIf { ROBOT.matches(it) } ?: "").toString()
    }

    /** Waiting for the page; "" when none. */
    @Volatile var pending: String = ""

    /** A tap asked to open ROBLO (the screen asks for the fingerprint, then shows it). */
    val asked = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun take(): String { val p = pending; pending = ""; return p }
}
