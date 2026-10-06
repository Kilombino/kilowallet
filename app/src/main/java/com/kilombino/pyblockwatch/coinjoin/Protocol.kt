package com.kilombino.pyblockwatch.coinjoin

import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kilojoin v1: coinjoin pools over nostr, after Joinstr's design (no coordinator holds funds;
 * the relay only carries messages), adapted to the BLAKE2b chain and to how Kilowallet wants
 * a round to start: people join an open pool, and once two or more are in, anyone can ask to
 * close it and the rest accept.
 *
 * Events (relay.kilombino.com):
 *  - [KIND_POOL] (parameterised replaceable, `d` = pool id, `t` = kilojoin), signed by the
 *    pool's own key, plaintext: the pool's terms and its current state. Public, so every
 *    wallet can list it and notify "new pool".
 *  - [KIND_MSG], tag `p` = pool key, NIP-44 encrypted:
 *    - join request: from a fresh join key to the pool key;
 *    - welcome / reject: from the pool key back to that join key;
 *    - everything else: from the pool key to itself — once welcomed, every member holds the
 *      pool's secret, so on the relay all members' messages look like one author.
 *
 * Sybil protection: a join must prove ownership of a real unspent coin of at least the pool
 * amount (a signature by the coin's key, checked by the pool's creator against the chain),
 * and a coin can hold one seat only. The creator therefore learns which coins joined; those
 * coins become public in the transaction anyway. What stays unlinked is which mixed output
 * belongs to which coin: mixed outputs are posted with no identity, after a random delay.
 */
object Protocol {
    const val RELAY = "wss://relay.kilombino.com"
    const val KIND_POOL = 32022
    const val KIND_MSG = 2023
    const val TAG = "kilojoin"
    const val VERSION = 1
    /** BLAKE2b mainnet. Tests against a regtest node set another name so their pools never list here. */
    @Volatile var NETWORK = "blake2b"

    const val MIN_AMOUNT = 100_000L
    const val MAX_AMOUNT = 100_000_000L
    /** Smallest amount a pool may announce at all; the app itself only lists and creates from [MIN_AMOUNT]. */
    const val TEST_MIN_AMOUNT = 1_000L
    const val MIN_PEERS = 2
    const val MAX_PEERS = 20

    /** How long people get to answer "close now?"; whoever has not answered is left out. */
    const val VOTE_SECONDS = 10 * 60L
    /** From "closing" until every mixed output and change is in. */
    const val OUTPUTS_SECONDS = 5 * 60L
    /** From the transaction being ready until everyone has signed it (needs the user's touch). */
    const val SIGN_SECONDS = 20 * 60L

    /**
     * A private pool's password never travels: the creator keeps a key derived from it, and a
     * join carries an HMAC of the join key under that key, so only someone who typed the right
     * password can make one (and a captured one is useless for any other join key).
     */
    fun passwordKey(poolId: String, password: String): ByteArray =
        Hashes.sha256("kilojoin/v1/pw|$poolId|$password".toByteArray(Charsets.UTF_8))

    fun passwordProof(pwKey: ByteArray, joinPub: String): String = Hashes.hmacSha256(pwKey, joinPub.toByteArray()).toHex()

    fun tokenHash(token: String): String = Hashes.sha256(("kilojoin/v1/token|$token").toByteArray()).toHex()

    data class Terms(
        val id: String,
        val poolPub: String,
        val amount: Long,
        val feeRate: Double,
        val maxPeers: Int,
        val expiresAt: Long,
        val state: String,      // open, closing, done, aborted
        val peers: Int,
        val createdAt: Long,
        /** Fewest people the round may close with (2 to [maxPeers]). */
        val minPeers: Int = MIN_PEERS,
        /** Joining needs the pool's password (shared by its creator outside the app). */
        val private: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("v", VERSION).put("app", "kilowallet").put("network", NETWORK)
            .put("id", id).put("public_key", poolPub).put("denomination", amount)
            .put("fee_rate", feeRate).put("min_peers", minPeers).put("max_peers", maxPeers).put("private", private)
            .put("timeout", expiresAt).put("state", state).put("peers", peers)

        companion object {
            fun parse(ev: NostrEvent): Terms? = runCatching {
                val o = JSONObject(ev.content)
                if (o.getInt("v") != VERSION || o.getString("network") != NETWORK) return null
                val t = Terms(
                    id = o.getString("id"), poolPub = o.getString("public_key"),
                    amount = o.getLong("denomination"), feeRate = o.getDouble("fee_rate"),
                    maxPeers = o.getInt("max_peers"), expiresAt = o.getLong("timeout"),
                    state = o.getString("state"), peers = o.optInt("peers", 0), createdAt = ev.createdAt,
                    minPeers = o.optInt("min_peers", MIN_PEERS), private = o.optBoolean("private", false),
                )
                // Only the pool's own key may speak for it, and the terms must be in range.
                if (ev.pubkey != t.poolPub || ev.tag("d") != t.id) return null
                if (t.amount < TEST_MIN_AMOUNT || t.amount > MAX_AMOUNT) return null
                if (t.maxPeers !in MIN_PEERS..MAX_PEERS || t.feeRate < 1.0 || t.feeRate > 500.0) return null
                if (t.minPeers !in MIN_PEERS..t.maxPeers) return null
                t
            }.getOrNull()
        }
    }

    /** A coin as it travels in messages. */
    fun coinJson(c: CoinjoinTx.Coin): JSONObject = JSONObject()
        .put("txid", c.txid).put("vout", c.vout).put("value", c.value).put("pubkey", c.pubkey.toHex())

    fun parseCoin(o: JSONObject): CoinjoinTx.Coin =
        CoinjoinTx.Coin(o.getString("txid"), o.getInt("vout"), o.getLong("value"), Hashes.hexToBytes(o.getString("pubkey")))
            .also {
                require(it.txid.matches(Regex("[0-9a-f]{64}")) && it.vout >= 0 && it.value > 0 && it.pubkey.size == 33)
            }

    /**
     * A member's seat: their coin, their change (script + value, 0 = no change) and the
     * signature by the coin's key over both, so nobody can swap someone else's change.
     */
    data class Seat(val tokenHash: String, val coin: CoinjoinTx.Coin, val changeScript: ByteArray?, val changeValue: Long) {
        fun toJson(): JSONObject = JSONObject().put("token_hash", tokenHash).put("coin", coinJson(coin))
            .put("change_script", changeScript?.toHex() ?: "").put("change_value", changeValue)

        companion object {
            fun parse(o: JSONObject): Seat {
                val cs = o.optString("change_script")
                return Seat(o.getString("token_hash"), parseCoin(o.getJSONObject("coin")),
                    if (cs.isEmpty()) null else Hashes.hexToBytes(cs), o.getLong("change_value"))
            }
        }
    }

    fun changeMessage(poolId: String, coin: CoinjoinTx.Coin, script: ByteArray?, value: Long): ByteArray =
        Hashes.sha256("kilojoin/v1/change|$poolId|${coin.outpoint}|${script?.toHex() ?: ""}|$value".toByteArray())

    fun seatsJson(seats: Collection<Seat>): JSONArray = JSONArray().also { a -> seats.forEach { a.put(it.toJson()) } }
    fun parseSeats(a: JSONArray): List<Seat> = (0 until a.length()).map { Seat.parse(a.getJSONObject(it)) }
}
