package com.kilombino.pyblockwatch.coinjoin

import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.ScriptType
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
 *
 * Kilojoin v2 (Kilowallet 0.27): a pool has one address type, `script` = `p2wpkh` or `p2tr`
 * (every input, mixed output and change of the round is of it), and lets each person bring up
 * to `max_inputs` coins. A seat then carries `coins` (and, for v1 readers, `coin` = the first),
 * a join carries one ownership proof per coin in `proofs`, and the change is signed over all of
 * the seat's outpoints. v1 pools (one P2WPKH coin each) are still listed and joined as before;
 * a v1 wallet does not list v2 pools at all (it only accepts `v` = 1).
 *
 * Kilojoin v3 (Kilowallet 1.1.5): nobody, not the relay nor the other members, can tell which
 * mixed output is whose, and nobody can speak for the creator or for another member.
 *  - The terms carry the creator's own key (`creator`) and an RSA key for blind signatures
 *    (`blind_key`, RFC 9474, [BlindRsa]). Messages that run the pool (welcome, roster, vote_open,
 *    closing, blind_sig, outputs, reopen) come from the creator's key: the pool key every
 *    member holds no longer speaks for it.
 *  - Members prove themselves to the creator with a MAC under their seat's secret token
 *    ([mac]); the token itself never goes on the channel, so nobody can vote or leave for them.
 *  - When a round closes, each member has its mixed output signed blindly by the creator (one
 *    per seat), then hands it in with the unblinded signature from a fresh key, over its own
 *    connection, encrypted to the creator only; the creator lists all of them at once. The
 *    creator can't tell which seat an output came from, and members only see the full list.
 *  - The roster carries each seat's join key and ownership proofs, and every member checks
 *    them against the chain, not only the creator.
 * Older wallets don't list v3 pools (they only accept `v` 1 or 2).
 *
 * Kilojoin v4 (Kilowallet 1.2.0): everything as in v3, and each person gets `outputs` (2 to
 * [MAX_OUTPUTS]) mixed outputs of the pool's amount, to as many unused addresses of their own.
 * The perfect coin is then `outputs` times the amount plus the fee share. Each of a member's
 * outputs is signed blindly (one `blind` carries them all) and handed in on its own: its own
 * fresh key, its own connection, its own random wait, so not even the creator can tell which
 * outputs belong to the same person. A pool of one output each is still announced as v3, so
 * every v3 wallet can join it; wallets before v4 don't list v4 pools.
 *
 * Kilojoin v5 (Kilowallet 1.2.7): a payment through a pool, with amounts that differ. The creator
 * pays 1 to [MAX_OUTPUTS] recipients, announced as `payments` (their amounts only, which the
 * chain shows anyway; the recipients' addresses stay with the creator until the round closes).
 * Every member puts the same sum into the mix, S = sum of the payments; each joiner gets it back
 * as `outputs` (1 to [MAX_OUTPUTS]) equal outputs of `denomination` d, S = `outputs` × d, signed
 * blindly and handed in anonymously exactly as in v4. The creator asks for no blind signatures:
 * its outputs are the payments, listed by itself (script and amount) next to the joiners' outputs
 * once they are all in. Each person pays their own fee share as always, the creator's counted with
 * its real number of payment outputs. Before signing every member checks its own outputs and
 * change, that there are exactly (members − 1) × `outputs` outputs of d plus exactly the announced
 * payments, each once, of the pool's address type, and that the fee matches every seat's share.
 * What it hides: which coins paid (among the members, all the more with perfect coins), and
 * which joiner got which output. What it can't hide: the payments themselves, odd amounts that
 * belong to one person. Wallets before v5 don't list v5 pools.
 */
object Protocol {
    const val RELAY = "wss://relay.kilombino.com"
    const val KIND_POOL = 32022
    /** Seconds between an open pool's re-announcements by its creator. */
    const val HEARTBEAT = 10 * 60L
    /** An open pool not re-announced for this long has lost its creator and is not listed. */
    const val STALE_AFTER = 35 * 60L
    const val KIND_MSG = 2023
    const val TAG = "kilojoin"
    /** v2 pools: one address type, several coins per person. */
    const val VERSION = 2
    /** v3 pools: blinded outputs and the creator's own key. Newest; v1 and v2 pools still join as before. */
    const val V3 = 3
    /** v4 pools: v3 with several mixed outputs per person. */
    const val V4 = 4
    /** v5 pools: the creator pays recipients of different amounts; joiners get equal outputs. */
    const val V5 = 5
    /** Smallest payment in a v5 pool: the dust limit of a Taproot output, the highest of the two types. */
    const val MIN_PAYMENT = 330L
    /** Most mixed outputs one person may get in a v4 pool (and most payments in a v5 one). */
    const val MAX_OUTPUTS = 5
    const val V1 = 1
    /** Most coins one person may bring to a v2 pool. */
    const val MAX_INPUTS = 10
    /** BLAKE2b mainnet. Tests against a regtest node set another name so their pools never list here. */
    @Volatile var NETWORK = "blake2b"

    /** Least each person puts in a pool (amount × outputs); below it a pool is only a test. */
    const val MIN_AMOUNT = 10_000L
    const val MAX_AMOUNT = 100_000_000L
    /** Smallest output a pool may announce at all: a test pool's amount, or each of several outputs. */
    const val TEST_MIN_AMOUNT = 1_000L

    /** A test pool: less than [MIN_AMOUNT] each in all. The app lists and creates only real ones. */
    fun isTest(t: Terms): Boolean = t.amount * t.outputs < MIN_AMOUNT
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
        /** Protocol version of the pool: [V1] (one P2WPKH coin each) or [VERSION]. */
        val version: Int = V1,
        /** The one address type of the round (v2; always P2WPKH in v1). */
        val script: ScriptType = ScriptType.P2WPKH,
        /** Most coins one person may bring (v2; always 1 in v1). */
        val maxInputs: Int = 1,
        /** v3: the creator's own nostr key (x-only hex), which alone runs the pool. */
        val creatorPub: String? = null,
        /** v3: the RSA key the creator signs mixed outputs blindly with ([BlindRsa.PublicKey.encode]). */
        val blindKey: String? = null,
        /** v4: mixed outputs each person gets, every one of [amount] (1 before v4; each joiner's in v5). */
        val outputs: Int = 1,
        /** v5: the creator's payments, in sats, summing to [mixTotal]; empty before v5. */
        val payments: List<Long> = emptyList(),
    ) {
        val v3: Boolean get() = version >= V3
        /** A v5 payment pool: the creator's outputs are [payments], the joiners' [outputs] × [amount]. */
        val v5: Boolean get() = version >= V5
        /** What each person puts into the mix: [outputs] × [amount]. */
        val mixTotal: Long get() = amount * outputs
        fun toJson(): JSONObject = JSONObject()
            .put("v", version).put("app", "kilowallet").put("network", NETWORK)
            .put("id", id).put("public_key", poolPub).put("denomination", amount)
            .put("fee_rate", feeRate).put("min_peers", minPeers).put("max_peers", maxPeers).put("private", private)
            .put("timeout", expiresAt).put("state", state).put("peers", peers)
            .also { if (version >= 2) it.put("script", scriptName(script)).put("max_inputs", maxInputs) }
            .also { if (version >= V3) it.put("creator", creatorPub).put("blind_key", blindKey) }
            .also { if (version >= V4) it.put("outputs", outputs) }
            .also { if (version >= V5) it.put("payments", JSONArray(payments)) }

        companion object {
            fun parse(ev: NostrEvent): Terms? = runCatching {
                val o = JSONObject(ev.content)
                val v = o.getInt("v")
                if (v != V1 && v != VERSION && v != V3 && v != V4 && v != V5 || o.getString("network") != NETWORK) return null
                val t = Terms(
                    id = o.getString("id"), poolPub = o.getString("public_key"),
                    amount = o.getLong("denomination"), feeRate = o.getDouble("fee_rate"),
                    maxPeers = o.getInt("max_peers"), expiresAt = o.getLong("timeout"),
                    state = o.getString("state"), peers = o.optInt("peers", 0), createdAt = ev.createdAt,
                    minPeers = o.optInt("min_peers", MIN_PEERS), private = o.optBoolean("private", false),
                    version = v,
                    script = if (v == V1) ScriptType.P2WPKH else parseScript(o.getString("script")) ?: return null,
                    maxInputs = if (v == V1) 1 else o.getInt("max_inputs"),
                    creatorPub = if (v >= V3) o.getString("creator") else null,
                    blindKey = if (v >= V3) o.getString("blind_key") else null,
                    outputs = if (v >= V4) o.getInt("outputs") else 1,
                    payments = if (v >= V5) o.getJSONArray("payments").let { a -> (0 until a.length()).map { a.getLong(it) } } else emptyList(),
                )
                // A v4 pool always has several outputs each; one each is announced as v3.
                if (v == V4 && t.outputs !in 2..MAX_OUTPUTS) return null
                if (v == V5 && !paymentsOk(t.payments, t.amount, t.outputs)) return null
                if (t.v3) {
                    if (!t.creatorPub!!.matches(Regex("[0-9a-f]{64}")) || t.creatorPub == t.poolPub) return null
                    BlindRsa.PublicKey.decode(t.blindKey!!)
                }
                if (t.maxInputs !in 1..MAX_INPUTS) return null
                // Only the pool's own key may speak for it, and the terms must be in range.
                if (ev.pubkey != t.poolPub || ev.tag("d") != t.id) return null
                if (t.amount < TEST_MIN_AMOUNT || t.amount > MAX_AMOUNT) return null
                if (t.maxPeers !in MIN_PEERS..MAX_PEERS || t.feeRate < 1.0 || t.feeRate > 500.0) return null
                if (t.minPeers !in MIN_PEERS..t.maxPeers) return null
                t
            }.getOrNull()
        }
    }

    /**
     * v5: 1 to [MAX_OUTPUTS] payments of at least [MIN_PAYMENT] each, summing to exactly what each
     * joiner gets, [outputs] (1 to [MAX_OUTPUTS]) × [amount].
     */
    fun paymentsOk(payments: List<Long>, amount: Long, outputs: Int): Boolean =
        payments.size in 1..MAX_OUTPUTS && outputs in 1..MAX_OUTPUTS && payments.all { it >= MIN_PAYMENT } &&
            payments.sum() == amount * outputs

    /**
     * v5: how a joiner gets the payments' [total] back: [wanted] equal outputs when the total splits
     * evenly into that many of at least [minAmount] (the smallest output: the total itself has
     * passed [MIN_AMOUNT]), else one output of the whole total. Returns
     * (amount of each output, outputs).
     */
    fun v5Split(total: Long, wanted: Int = 1, minAmount: Long = TEST_MIN_AMOUNT): Pair<Long, Int> =
        if (wanted in 2..MAX_OUTPUTS && total % wanted == 0L && total / wanted >= minAmount) total / wanted to wanted
        else total to 1

    fun scriptName(t: ScriptType): String = if (t == ScriptType.P2TR) "p2tr" else "p2wpkh"
    fun parseScript(s: String): ScriptType? = when (s) { "p2wpkh" -> ScriptType.P2WPKH; "p2tr" -> ScriptType.P2TR; else -> null }

    /** A coin as it travels in messages (its address type is the pool's, not sent). */
    fun coinJson(c: CoinjoinTx.Coin): JSONObject = JSONObject()
        .put("txid", c.txid).put("vout", c.vout).put("value", c.value).put("pubkey", c.pubkey.toHex())

    fun parseCoin(o: JSONObject, type: ScriptType = ScriptType.P2WPKH): CoinjoinTx.Coin =
        CoinjoinTx.Coin(o.getString("txid"), o.getInt("vout"), o.getLong("value"), Hashes.hexToBytes(o.getString("pubkey")), type)
            .also {
                require(it.txid.matches(Regex("[0-9a-f]{64}")) && it.vout >= 0 && it.value > 0 && it.pubkey.size == 33)
            }

    /**
     * A member's seat: their coins (one in v1), their change (script + value, 0 = no change) and
     * the signature by the first coin's key over all of it, so nobody can swap someone else's change.
     */
    data class Seat(val tokenHash: String, val coins: List<CoinjoinTx.Coin>, val changeScript: ByteArray?, val changeValue: Long,
                    /** v3 roster: the key the seat joined with and its ownership proofs, for every member to check. */
                    val joinPub: String? = null, val proofs: List<String> = emptyList()) {
        init { require(coins.isNotEmpty()) { "a seat with no coin" } }
        constructor(tokenHash: String, coin: CoinjoinTx.Coin, changeScript: ByteArray?, changeValue: Long) :
            this(tokenHash, listOf(coin), changeScript, changeValue)
        /** The first coin: the only one in a v1 pool, the one that signs the change in v2. */
        val coin: CoinjoinTx.Coin get() = coins.first()
        val inputValue: Long get() = coins.sumOf { it.value }

        fun toJson(): JSONObject = JSONObject().put("token_hash", tokenHash).put("coin", coinJson(coin))
            .put("change_script", changeScript?.toHex() ?: "").put("change_value", changeValue)
            .also { if (coins.size > 1) it.put("coins", JSONArray().also { a -> coins.forEach { c -> a.put(coinJson(c)) } }) }
            .also { if (joinPub != null) it.put("join_pub", joinPub).put("proofs", JSONArray(proofs)) }

        companion object {
            fun parse(o: JSONObject, type: ScriptType = ScriptType.P2WPKH): Seat {
                val cs = o.optString("change_script")
                val list = o.optJSONArray("coins")?.let { a -> (0 until a.length()).map { parseCoin(a.getJSONObject(it), type) } }
                    ?: listOf(parseCoin(o.getJSONObject("coin"), type))
                val proofs = o.optJSONArray("proofs")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                return Seat(o.getString("token_hash"), list, if (cs.isEmpty()) null else Hashes.hexToBytes(cs), o.getLong("change_value"),
                    o.optString("join_pub").ifEmpty { null }, proofs)
            }
        }
    }

    /** What the change signature covers: the v1 message for one coin, all the outpoints for several. */
    fun changeMessage(poolId: String, coins: List<CoinjoinTx.Coin>, script: ByteArray?, value: Long): ByteArray =
        if (coins.size == 1) Hashes.sha256("kilojoin/v1/change|$poolId|${coins[0].outpoint}|${script?.toHex() ?: ""}|$value".toByteArray())
        else Hashes.sha256("kilojoin/v2/change|$poolId|${coins.joinToString(",") { it.outpoint }}|${script?.toHex() ?: ""}|$value".toByteArray())

    /** v3: a member's proof that it holds the seat with [token], bound to what it says ([parts]). */
    fun mac(token: String, vararg parts: Any): String =
        Hashes.hmacSha256(token.toByteArray(), ("kilojoin/v3|" + parts.joinToString("|")).toByteArray()).toHex()

    fun macOk(token: String?, mac: String, vararg parts: Any): Boolean =
        token != null && java.security.MessageDigest.isEqual(mac(token, *parts).toByteArray(), mac.toByteArray())

    fun seatsJson(seats: Collection<Seat>): JSONArray = JSONArray().also { a -> seats.forEach { a.put(it.toJson()) } }
    fun parseSeats(a: JSONArray, type: ScriptType = ScriptType.P2WPKH): List<Seat> =
        (0 until a.length()).map { Seat.parse(a.getJSONObject(it), type) }
}
