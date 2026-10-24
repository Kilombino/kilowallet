package com.kilombino.pyblockwatch.coinjoin

import com.kilombino.pyblockwatch.crypto.Ecdsa
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.Secp256k1
import com.kilombino.pyblockwatch.crypto.TxBuilder
import org.json.JSONObject
import java.math.BigInteger
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * One wallet's part in one pool: the Kilojoin v1 state machine (see [Protocol]).
 *
 * The pool's creator also keeps the roster: it checks every join against the chain, hands out
 * seats, counts the votes and calls the phases. Every member (the creator included) posts its
 * mixed output, checks the final transaction, signs its own input and broadcasts once all
 * signatures are in. Messages replay from the relay on reconnect, so every step is idempotent.
 */
class PoolSession(private val env: Env, private val s: State) {

    /** What the session needs from the wallet. All calls may block; none run on the UI thread. */
    interface Env {
        /** True when [coin] is unspent on the BLAKE2b chain with exactly its stated value. */
        fun coinUnspent(coin: CoinjoinTx.Coin): Boolean
        fun broadcast(rawHex: String): String
        /** Confirmations of [txid], 0 in the mempool, null when unknown. */
        fun confirmations(txid: String): Int?
        fun save(state: State)
        fun event(e: Event)
        fun now(): Long = System.currentTimeMillis() / 1000
        fun log(msg: String) {}
    }

    enum class Alone { ACCEPTED, ASKED_TO_CLOSE, SIGNED }

    sealed class Event(val pool: String) {
        class Joined(pool: String, val peers: Int) : Event(pool)
        class Welcomed(pool: String) : Event(pool)
        class Rejected(pool: String, val reason: String) : Event(pool)
        class CloseRequested(pool: String, val peers: Int, val byMe: Boolean) : Event(pool)
        class CloseRefused(pool: String) : Event(pool)
        class Closing(pool: String, val peers: Int) : Event(pool)
        class SignNeeded(pool: String, val peers: Int) : Event(pool)
        /** Run on its own: we accepted a close, asked to close, or signed, without the user. */
        class DidAlone(pool: String, val what: Alone) : Event(pool)
        class Broadcast(pool: String, val txid: String) : Event(pool)
        class Confirmed(pool: String, val txid: String) : Event(pool)
        class Aborted(pool: String, val reason: String) : Event(pool)
        /** Under an hour left, and enough people to close it now. */
        class ExpiringSoon(pool: String, val peers: Int, val minutes: Int) : Event(pool)
        class Changed(pool: String) : Event(pool)
    }

    enum class Phase { JOINING, OPEN, VOTING, CLOSING, SIGNING, BROADCAST, CONFIRMED, ABORTED, REJECTED }

    /** Everything that must survive the app being killed. */
    class State(
        val terms: Protocol.Terms,
        val creator: Boolean,
        var poolSecret: String?,          // hex; known to the creator from the start, to members once welcomed
        val joinSecret: String,           // hex; the one-off key this wallet joined with
        var token: String?,               // our seat's secret token
        val seat: Protocol.Seat,          // our coins and change, as signed at join time
        val joinProofs: List<String>,     // hex DER, one per coin, sent with the join request
        val changeSig: String,            // hex DER
        /** Our mixed outputs: one before v4, [Protocol.Terms.outputs] in a v4 pool. */
        val mixScripts: List<ByteArray>,
        val coinPaths: List<String>,      // BIP-32 paths of our coins' keys, to sign at the end
        val pwKey: String? = null,        // creator of a private pool: key derived from its password
        val pwProof: String? = null,      // joiner of a private pool: proof sent with the join
        var phase: Phase = Phase.JOINING,
        var reason: String = "",
        var seats: MutableList<Protocol.Seat> = mutableListOf(),   // the latest roster
        var round: MutableList<Protocol.Seat> = mutableListOf(),   // seats in the closing round
        var rosterSeq: Int = 0,           // creator: rosters sent; member: newest roster applied
        var voteId: String? = null,
        var voteBy: String? = null,       // token hash of whoever asked to close
        var voteDeadline: Long = 0,
        var phaseDeadline: Long = 0,
        val outputs: MutableSet<String> = linkedSetOf(),           // mixed output scripts, hex
        val sigs: MutableMap<String, String> = linkedMapOf(),      // outpoint → sig hex
        var postedOutput: Boolean = false,
        var postedSig: Boolean = false,
        var votedOn: String? = null,
        var txid: String? = null,
        val created: Long = System.currentTimeMillis() / 1000,
        // Creator only.
        val accepts: MutableSet<String> = mutableSetOf(),          // token hashes that said yes
        val joinPubs: MutableMap<String, String> = mutableMapOf(), // join pub → token hash
        val tokens: MutableMap<String, String> = mutableMapOf(),   // token hash → token
        var warnedExpiry: Boolean = false, // the under-an-hour warning was given
        // v3, creator only: its own key, and the RSA key it signs outputs blindly with.
        val creatorSecret: String? = null,
        val blindPriv: String? = null,
        var roundId: String? = null,      // v3: the closing round outputs are signed for
        // v3, member: our outputs' blinding (one per mixed output), kept so a resent request is the same one.
        var blindPrefixes: List<String> = emptyList(),
        var blindInvs: List<String> = emptyList(),
        var blindedMsgs: List<String> = emptyList(),
        var outSigs: List<String> = emptyList(),  // our outputs' unblinded signatures
        val handedIn: MutableSet<Int> = mutableSetOf(), // v4: which of our outputs went in already
        // v3, creator: per round, the blind signature given to each seat, and the outputs handed in.
        val blindSigs: MutableMap<String, String> = mutableMapOf(),
        val blindedOf: MutableMap<String, String> = mutableMapOf(),
        val regOutputs: MutableSet<String> = linkedSetOf(),
        // v5: the creator's payments as listed when the round closed, "script hex:sats".
        val payOutputs: MutableList<String> = mutableListOf(),
    ) {
        val poolId: String get() = terms.id
        val joinProof: String get() = joinProofs.first()
        val coinPath: String get() = coinPaths.first()
        /** Our first (before v4, only) mixed output. */
        val mixScript: ByteArray get() = mixScripts.first()
        fun toJson(): JSONObject = JSONObject()
            .put("terms", terms.toJson().put("created_at", terms.createdAt))
            .put("creator", creator).put("pool_secret", poolSecret ?: "").put("join_secret", joinSecret)
            .put("token", token ?: "").put("seat", seat.toJson()).put("join_proof", joinProof)
            .put("join_proofs", org.json.JSONArray(joinProofs)).put("coin_paths", org.json.JSONArray(coinPaths))
            .put("change_sig", changeSig).put("mix_script", mixScript.toHex()).put("coin_path", coinPath)
            .put("mix_scripts", org.json.JSONArray(mixScripts.map { it.toHex() }))
            .put("pw_key", pwKey ?: "").put("pw_proof", pwProof ?: "")
            .put("phase", phase.name).put("reason", reason)
            .put("seats", Protocol.seatsJson(seats)).put("round", Protocol.seatsJson(round))
            .put("roster_seq", rosterSeq).put("vote_id", voteId ?: "").put("vote_by", voteBy ?: "").put("vote_deadline", voteDeadline)
            .put("phase_deadline", phaseDeadline)
            .put("outputs", org.json.JSONArray(outputs.toList()))
            .put("sigs", JSONObject(sigs as Map<*, *>))
            .put("posted_output", postedOutput).put("posted_sig", postedSig).put("voted_on", votedOn ?: "")
            .put("txid", txid ?: "").put("created", created)
            .put("accepts", org.json.JSONArray(accepts.toList()))
            .put("join_pubs", JSONObject(joinPubs as Map<*, *>)).put("tokens", JSONObject(tokens as Map<*, *>))
            .put("warned_expiry", warnedExpiry)
            .put("creator_secret", creatorSecret ?: "").put("blind_priv", blindPriv ?: "").put("round_id", roundId ?: "")
            .put("blind_prefixes", org.json.JSONArray(blindPrefixes)).put("blind_invs", org.json.JSONArray(blindInvs))
            .put("blinded_msgs", org.json.JSONArray(blindedMsgs)).put("out_sigs", org.json.JSONArray(outSigs))
            .put("handed_in", org.json.JSONArray(handedIn.toList()))
            .put("blind_sigs", JSONObject(blindSigs as Map<*, *>)).put("blinded_of", JSONObject(blindedOf as Map<*, *>))
            .put("reg_outputs", org.json.JSONArray(regOutputs.toList()))
            .put("pay_outputs", org.json.JSONArray(payOutputs))

        companion object {
            private fun JSONObject.str(k: String): String? = optString(k).ifEmpty { null }
            private fun JSONObject.map(k: String): MutableMap<String, String> {
                val o = optJSONObject(k) ?: return mutableMapOf()
                return o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
            }
            fun parse(o: JSONObject): State {
                val t = o.getJSONObject("terms")
                val v = t.optInt("v", Protocol.V1)
                val terms = Protocol.Terms(t.getString("id"), t.getString("public_key"), t.getLong("denomination"),
                    t.getDouble("fee_rate"), t.getInt("max_peers"), t.getLong("timeout"), t.getString("state"),
                    t.optInt("peers"), t.optLong("created_at"), t.optInt("min_peers", Protocol.MIN_PEERS),
                    t.optBoolean("private", false), v,
                    if (v == Protocol.V1) com.kilombino.pyblockwatch.crypto.ScriptType.P2WPKH
                    else Protocol.parseScript(t.getString("script")) ?: error("unknown script"),
                    if (v == Protocol.V1) 1 else t.getInt("max_inputs"),
                    t.optString("creator").ifEmpty { null }, t.optString("blind_key").ifEmpty { null },
                    if (v >= Protocol.V4) t.getInt("outputs") else 1,
                    t.optJSONArray("payments")?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList())
                val type = terms.script
                fun list(k: String, one: String): List<String> = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    ?: listOf(o.getString(one))
                // Before v4 a member kept one output's blinding in single fields.
                fun list0(k: String, one: String): List<String> = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    ?: listOfNotNull(o.str(one))
                val outs = o.optJSONArray("outputs"); val acc = o.optJSONArray("accepts")
                return State(
                    terms, o.getBoolean("creator"), o.str("pool_secret"), o.getString("join_secret"), o.str("token"),
                    Protocol.Seat.parse(o.getJSONObject("seat"), type), list("join_proofs", "join_proof"), o.getString("change_sig"),
                    list("mix_scripts", "mix_script").map { Hashes.hexToBytes(it) }, list("coin_paths", "coin_path"),
                    o.str("pw_key"), o.str("pw_proof"),
                    Phase.valueOf(o.getString("phase")), o.optString("reason"),
                    Protocol.parseSeats(o.getJSONArray("seats"), type).toMutableList(),
                    Protocol.parseSeats(o.getJSONArray("round"), type).toMutableList(),
                    o.optInt("roster_seq"), o.str("vote_id"), o.str("vote_by"), o.optLong("vote_deadline"), o.optLong("phase_deadline"),
                    (0 until (outs?.length() ?: 0)).map { outs!!.getString(it) }.toMutableSet(),
                    o.map("sigs"), o.optBoolean("posted_output"), o.optBoolean("posted_sig"), o.str("voted_on"),
                    o.str("txid"), o.optLong("created"),
                    (0 until (acc?.length() ?: 0)).map { acc!!.getString(it) }.toMutableSet(),
                    o.map("join_pubs"), o.map("tokens"), o.optBoolean("warned_expiry"),
                    o.str("creator_secret"), o.str("blind_priv"), o.str("round_id"),
                    list0("blind_prefixes", "blind_prefix"), list0("blind_invs", "blind_inv"),
                    list0("blinded_msgs", "blinded_msg"), list0("out_sigs", "out_sig"),
                    o.optJSONArray("handed_in")?.let { a -> (0 until a.length()).map { a.getInt(it) } }?.toMutableSet() ?: mutableSetOf(),
                    o.map("blind_sigs"), o.map("blinded_of"),
                    o.optJSONArray("reg_outputs")?.let { a -> (0 until a.length()).map { a.getString(it) } }?.toMutableSet() ?: linkedSetOf(),
                    o.optJSONArray("pay_outputs")?.let { a -> (0 until a.length()).map { a.getString(it) } }?.toMutableList() ?: mutableListOf(),
                )
            }
        }
    }

    private val rnd = SecureRandom()
    private var relay: RelayClient? = null
    private var timer: ScheduledExecutorService? = null
    /**
     * Events are handled here, never on the relay's reader thread: handling one often means
     * publishing a reply and waiting for the relay's OK, which that same thread delivers.
     */
    private var worker = Executors.newSingleThreadExecutor()
    private val poolKey: BigInteger? get() = s.poolSecret?.let { BigInteger(it, 16) }
    private val joinKey = BigInteger(s.joinSecret, 16)
    private val joinPub = NostrEvent.pubOf(joinKey)
    private val myTokenHash: String? get() = s.token?.let { Protocol.tokenHash(it) }
    private val terms = s.terms
    private val creatorKey: BigInteger? get() = s.creatorSecret?.let { BigInteger(it, 16) }
    private val blindPk: BlindRsa.PublicKey? by lazy { terms.blindKey?.let { BlindRsa.PublicKey.decode(it) } }
    /** Seats whose coins this wallet already checked against the chain (v3 roster). */
    private val verifiedSeats = mutableSetOf<String>()
    private var lastBlindSent = 0L
    /** Our outputs waiting for their random moment to be handed in (by index). */
    private val outputScheduled = mutableSetOf<Int>()
    /** Mixed outputs each person gets: 1 before v4 (each joiner, in v5). */
    private val perPerson: Int get() = terms.outputs

    /** v5: the creator's seat, the one it joined with the pool's own key. */
    private fun isCreatorSeat(seat: Protocol.Seat) = seat.joinPub == terms.poolPub

    /** Seats whose mixed outputs are handed in blindly: all of them, but the creator's in v5. */
    private val handingIn: Int get() = if (terms.v5) s.round.count { !isCreatorSeat(it) } else s.round.size

    val state: State get() = s

    /**
     * "On its own": the keys of our coins, unlocked once at join and kept in memory only (never in
     * [State], never saved). With them this session accepts a request to close, asks to close
     * itself when the pool is about to expire with enough people, and signs as soon as the
     * transaction passes the same checks a person signing would rely on ([CoinjoinTx.checkOurs]:
     * our exact output, our change, our fee share). A failed check, or keys that do not sign,
     * drop them and fall back to asking. Lost with the process, so a restarted app asks again.
     */
    @Volatile var autoKeys: List<BigInteger>? = null
        set(v) { require(v == null || v.size == s.seat.coins.size) { "one key per coin is needed" }; field = v }
    val done: Boolean get() = s.phase in setOf(Phase.CONFIRMED, Phase.ABORTED, Phase.REJECTED)

    /** The plan once every output is in; null before. */
    @Synchronized fun plan(): CoinjoinTx.Plan? = runCatching { buildPlan() }.getOrNull()

    // ------------------------------------------------------------------------------- lifecycle

    @Synchronized
    fun start() {
        if (done) return
        if (timer != null) return // already running
        if (worker.isShutdown) worker = Executors.newSingleThreadExecutor()
        timer = Executors.newSingleThreadScheduledExecutor().also { it.scheduleWithFixedDelay({ tick() }, 5, 15, TimeUnit.SECONDS) }
        connect()
    }

    @Synchronized
    fun stop() {
        timer?.shutdownNow(); timer = null
        worker.shutdown()
        relay?.close(); relay = null
    }

    private fun connect() {
        val r = RelayClient(Protocol.RELAY, { _, ev -> runCatching { worker.execute { onEvent(ev) } } }, { relay = null })
        r.connect()
        relay = r
        // Our own inbox (welcome/reject), and the pool channel once we hold its key.
        r.subscribe("in", listOf(JSONObject().put("kinds", org.json.JSONArray().put(Protocol.KIND_MSG))
            .put("#p", org.json.JSONArray().put(joinPub))))
        r.subscribe("pool", listOf(JSONObject().put("kinds", org.json.JSONArray().put(Protocol.KIND_MSG))
            .put("#p", org.json.JSONArray().put(terms.poolPub))))
        // The pool's public listing, which its creator re-announces while it is alive: how the
        // others find out that the creator's app is gone, as the pool bot does.
        if (!s.creator) r.subscribe("listing", listOf(JSONObject().put("kinds", org.json.JSONArray().put(Protocol.KIND_POOL))
            .put("authors", org.json.JSONArray().put(terms.poolPub)).put("#d", org.json.JSONArray().put(terms.id))))
        // v3: outputs come to the creator's own key, each from a fresh key.
        if (s.creator && terms.v3) r.subscribe("outs", listOf(JSONObject().put("kinds", org.json.JSONArray().put(Protocol.KIND_MSG))
            .put("#p", org.json.JSONArray().put(terms.creatorPub))))
        if (s.phase == Phase.JOINING && !s.creator) sendJoin()
        if (s.creator && s.phase == Phase.JOINING) creatorStart()
    }

    private fun ensureConnected() {
        val r = relay
        if (r != null && r.connected) {
            val quiet = System.currentTimeMillis() - r.lastHeard
            // Ping after a quiet minute; nothing back for another minute means a dead link.
            if (quiet < 60_000) return
            if (quiet < 120_000) { if (r.ping()) return }
            env.log("relay silent for ${quiet / 1000} s: reconnecting")
            r.close(); relay = null
        }
        // Reconnecting replays the channel, so anything missed meanwhile is handled now.
        runCatching { connect() }
    }

    private fun save() { env.save(s); env.event(Event.Changed(s.poolId)) }

    // ------------------------------------------------------------------------------- sending

    private fun publish(kind: Int, tags: List<List<String>>, content: String, key: BigInteger): Boolean {
        ensureConnected()
        val ev = NostrEvent.sign(key, kind, tags, content)
        val r = relay?.publish(ev)
        env.log("publish kind $kind -> ${r?.first} ${r?.second ?: "no relay"}")
        return r?.first == true
    }

    /** A message on the pool channel: from the pool key, encrypted to itself. */
    private fun channel(o: JSONObject): Boolean {
        val k = poolKey ?: return false
        val conv = Nip44.conversationKey(k, Hashes.hexToBytes(terms.poolPub))
        return publish(Protocol.KIND_MSG, listOf(listOf("p", terms.poolPub)), Nip44.encrypt(o.toString(), conv), k)
    }

    /**
     * What runs the pool: from the creator's own key in v3 (members check it is the creator's),
     * from the pool key as any other message before.
     */
    private fun creatorChannel(o: JSONObject): Boolean {
        if (!terms.v3) return channel(o)
        val k = creatorKey ?: return false
        val conv = Nip44.conversationKey(k, Hashes.hexToBytes(terms.poolPub))
        return publish(Protocol.KIND_MSG, listOf(listOf("p", terms.poolPub)), Nip44.encrypt(o.toString(), conv), k)
    }

    /** The key the creator answers join requests with. */
    private val replyKey: BigInteger? get() = if (terms.v3) creatorKey else poolKey

    private fun direct(toPub: String, o: JSONObject, from: BigInteger): Boolean {
        val conv = Nip44.conversationKey(from, Hashes.hexToBytes(toPub))
        return publish(Protocol.KIND_MSG, listOf(listOf("p", toPub)), Nip44.encrypt(o.toString(), conv), from)
    }

    /** When this wallet last published the pool's announcement (creator only, this run). */
    private var lastAnnounce = 0L

    private fun announce() {
        val k = poolKey ?: return
        lastAnnounce = env.now()
        val state = when (s.phase) {
            Phase.JOINING, Phase.OPEN, Phase.VOTING -> "open"
            Phase.CLOSING, Phase.SIGNING -> "closing"
            Phase.BROADCAST, Phase.CONFIRMED -> "done"
            else -> "aborted"
        }
        // Once a round is called, only its seats take part: whoever stayed silent in the vote
        // keeps a seat in the pool but is not in the transaction.
        val peers = if (state == "open" || s.round.isEmpty()) s.seats.size else s.round.size
        val t = terms.copy(state = state, peers = peers)
        publish(Protocol.KIND_POOL, listOf(listOf("d", terms.id), listOf("t", Protocol.TAG), listOf("network", Protocol.NETWORK)),
            t.toJson().toString(), k)
    }

    private fun sendJoin() {
        val o = JSONObject().put("type", "join").put("seat", s.seat.toJson())
            .put("proof", s.joinProof).put("change_sig", s.changeSig)
        if (s.joinProofs.size > 1) o.put("proofs", org.json.JSONArray(s.joinProofs))
        s.pwProof?.let { o.put("pw", it) }
        direct(terms.poolPub, o, joinKey)
    }

    // ------------------------------------------------------------------------------- receiving

    /**
     * When the pool was last announced; 0 until the relay has said. Only its age is used: the
     * others hold the pool key too, so what an announcement says could be one of them, but
     * keeping it fresh can only make this wait for the pool's own deadlines.
     */
    @Volatile private var listedAt = 0L

    private fun onEvent(ev: NostrEvent) {
        if (ev.kind == Protocol.KIND_POOL) {
            if (ev.pubkey == terms.poolPub && ev.tag("d") == terms.id && ev.createdAt > listedAt) listedAt = ev.createdAt
            return
        }
        if (ev.kind != Protocol.KIND_MSG) return
        env.log("event from ${ev.pubkey.take(8)} p=${ev.tag("p")?.take(8)}")
        synchronized(this) {
            runCatching {
                when {
                    // Pool channel: author and recipient are the pool key.
                    ev.pubkey == terms.poolPub && ev.tag("p") == terms.poolPub -> {
                        val k = poolKey ?: return
                        val msg = Nip44.decrypt(ev.content, Nip44.conversationKey(k, Hashes.hexToBytes(terms.poolPub))) ?: return
                        onChannel(JSONObject(msg), ev.createdAt, fromCreator = !terms.v3)
                    }
                    // v3: the creator's own messages on the channel.
                    terms.v3 && ev.pubkey == terms.creatorPub && ev.tag("p") == terms.poolPub -> {
                        val k = poolKey ?: return
                        val msg = Nip44.decrypt(ev.content, Nip44.conversationKey(k, Hashes.hexToBytes(terms.creatorPub))) ?: return
                        onChannel(JSONObject(msg), ev.createdAt, fromCreator = true)
                    }
                    // A reply from the pool (v3: from its creator) to our join key.
                    ev.pubkey == (if (terms.v3) terms.creatorPub else terms.poolPub) && ev.tag("p") == joinPub -> {
                        val msg = Nip44.decrypt(ev.content, Nip44.conversationKey(joinKey, Hashes.hexToBytes(ev.pubkey))) ?: return
                        onDirect(JSONObject(msg))
                    }
                    // v3: a mixed output handed in from a fresh key, readable by the creator only.
                    s.creator && terms.v3 && ev.tag("p") == terms.creatorPub -> {
                        val k = creatorKey ?: return
                        val msg = Nip44.decrypt(ev.content, Nip44.conversationKey(k, Hashes.hexToBytes(ev.pubkey))) ?: return
                        onOutputHandedIn(JSONObject(msg))
                    }
                    // A join request to the pool (only the creator can read and answer it).
                    s.creator && ev.tag("p") == terms.poolPub && ev.pubkey != terms.poolPub && ev.pubkey != terms.creatorPub -> {
                        val k = poolKey ?: return
                        val msg = Nip44.decrypt(ev.content, Nip44.conversationKey(k, Hashes.hexToBytes(ev.pubkey))) ?: return
                        onJoinRequest(ev.pubkey, JSONObject(msg))
                    }
                }
            }
        }
    }

    private fun onDirect(o: JSONObject) {
        if (s.phase != Phase.JOINING) return
        when (o.optString("type")) {
            "welcome" -> {
                val secret = o.getString("pool_secret")
                // Anyone can write to our join key; only a secret that matches the pool's key is real.
                if (NostrEvent.pubOf(BigInteger(secret, 16)) != terms.poolPub) return
                val token = o.getString("token")
                s.poolSecret = secret; s.token = token; s.phase = Phase.OPEN
                s.rosterSeq = o.optInt("seq") - 1 // the roster with our seat is the first one that counts
                save(); env.event(Event.Welcomed(s.poolId))
                // The channel was subscribed before we could read it: replay it now.
                relay?.unsubscribe("pool")
                relay?.subscribe("pool", listOf(JSONObject().put("kinds", org.json.JSONArray().put(Protocol.KIND_MSG))
                    .put("#p", org.json.JSONArray().put(terms.poolPub))), waitEose = false)
            }
            "reject" -> {
                // Unauthenticated, like any message to our join key; it only makes us give up,
                // which the user can always retry. Accept it only before we are welcomed.
                s.phase = Phase.REJECTED; s.reason = o.optString("reason", "rejected")
                save(); env.event(Event.Rejected(s.poolId, s.reason))
            }
        }
    }

    // ------------------------------------------------------------------------------- creator

    private fun creatorStart() {
        val token = randomHex(16)
        s.token = token
        s.tokens[Protocol.tokenHash(token)] = token
        s.seats = mutableListOf(if (terms.v3) s.seat.copy(tokenHash = Protocol.tokenHash(token), joinPub = terms.poolPub, proofs = s.joinProofs)
            else s.seat.copy(tokenHash = Protocol.tokenHash(token)))
        s.phase = Phase.OPEN
        save()
        announce()
        creatorChannel(roster())
    }

    /** The roster, numbered: a member replaying the channel ignores the ones from before it joined. */
    private fun roster(): JSONObject {
        s.rosterSeq++; save()
        return JSONObject().put("type", "roster").put("seq", s.rosterSeq).put("seats", Protocol.seatsJson(s.seats))
    }

    private fun reject(joinPub: String, reason: String) { env.log("reject: $reason"); replyKey?.let { direct(joinPub, JSONObject().put("type", "reject").put("reason", reason), it) } }

    private fun onJoinRequest(from: String, o: JSONObject) {
        if (o.optString("type") != "join") return
        if (s.joinPubs.containsKey(from)) return // already handled (history replay)
        if (s.phase != Phase.OPEN) return reject(from, "the pool is no longer open")
        if (s.seats.size >= terms.maxPeers) return reject(from, "the pool is full")
        s.pwKey?.let { k ->
            val ok = java.security.MessageDigest.isEqual(
                Protocol.passwordProof(Hashes.hexToBytes(k), from).toByteArray(), o.optString("pw").toByteArray())
            if (!ok) return reject(from, "wrong password")
        }
        val seat = runCatching { Protocol.Seat.parse(o.getJSONObject("seat"), terms.script) }.getOrNull() ?: return reject(from, "malformed request")
        val coins = seat.coins
        if (coins.size > terms.maxInputs) return reject(from, "this pool takes at most ${terms.maxInputs} coin(s) per person")
        val outpoints = coins.map { it.outpoint }
        if (outpoints.toSet().size != outpoints.size) return reject(from, "the same coin twice")
        if (s.seats.any { st -> st.coins.any { it.outpoint in outpoints } }) return reject(from, "that coin already has a seat")
        val proofs = o.optJSONArray("proofs")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: listOf(o.getString("proof"))
        if (proofs.size != coins.size) return reject(from, "one proof per coin is needed")
        if (coins.indices.any { !CoinjoinTx.checkOwnership(terms.id, from, coins[it], Hashes.hexToBytes(proofs[it])) })
            return reject(from, "the coin's signature is wrong")
        val expected = CoinjoinTx.change(seat.inputValue, terms.amount, terms.feeRate, coins.size, terms.script, perPerson)
            ?: return reject(from, "the coin is too small for this pool")
        if (seat.changeValue != expected) return reject(from, "the change does not match the pool's fee")
        if ((expected > 0) != (seat.changeScript != null)) return reject(from, "the change does not match the pool's fee")
        seat.changeScript?.let { if (!CoinjoinTx.isType(it, terms.script)) return reject(from, "the change is of another address type") }
        val changeOk = runCatching {
            Ecdsa.verify(Secp256k1.decompress(seat.coin.pubkey), Protocol.changeMessage(terms.id, coins, seat.changeScript, seat.changeValue),
                CoinjoinTx.parseDer(Hashes.hexToBytes(o.getString("change_sig"))))
        }.getOrDefault(false)
        if (!changeOk) return reject(from, "the change's signature is wrong")
        if (coins.any { !runCatching { env.coinUnspent(it) }.getOrDefault(false) }) return reject(from, "that coin is not unspent on the chain")
        val token = randomHex(16)
        val th = Protocol.tokenHash(token)
        // v3: the seat carries its join key and proofs, so every member can check the coin too.
        val placed = if (terms.v3) seat.copy(tokenHash = th, joinPub = from, proofs = proofs) else seat.copy(tokenHash = th)
        s.tokens[th] = token; s.joinPubs[from] = th; s.seats.add(placed)
        val roster = roster()
        // Always 64 hex chars: pools made before 1.2.4 stored the key without leading zeros.
        direct(from, JSONObject().put("type", "welcome").put("pool_secret", s.poolSecret?.padStart(64, '0')).put("token", token)
            .put("seq", s.rosterSeq), replyKey!!)
        creatorChannel(roster)
        announce()
        env.event(Event.Joined(s.poolId, s.seats.size))
        // Full: ask everyone whether to close now (the creator's own seat counts as a yes).
        if (s.seats.size >= terms.maxPeers) {
            if (terms.v3) creatorChannel(JSONObject().put("type", "vote_open").put("by", myTokenHash).put("vote_id", randomHex(8)))
            else channel(JSONObject().put("type", "close_request").put("token", s.token).put("vote_id", randomHex(8)))
        }
    }

    private fun callClosing(seats: List<Protocol.Seat>, why: String) {
        s.round = seats.toMutableList()
        // v3: a fresh round id; blind signatures and outputs count for this round only.
        if (terms.v3) { s.roundId = randomHex(8); s.blindSigs.clear(); s.blindedOf.clear(); s.regOutputs.clear(); save() }
        creatorChannel(JSONObject().put("type", "closing").put("why", why).put("seats", Protocol.seatsJson(seats))
            .put("deadline", env.now() + Protocol.OUTPUTS_SECONDS).also { o -> s.roundId?.let { if (terms.v3) o.put("round_id", it) } })
    }

    private fun creatorOnVote(tokenHash: String, voteId: String, accept: Boolean) {
        if (s.phase != Phase.VOTING || voteId != s.voteId) return
        if (s.seats.none { it.tokenHash == tokenHash }) return
        if (!accept) {
            // Whoever says "not yet" leaves this round (their coin is free again); the pool
            // reopens for more people, or for another vote if enough are still in.
            if (tokenHash != myTokenHash) {
                s.seats.removeAll { it.tokenHash == tokenHash }; s.accepts.remove(tokenHash); save()
                creatorChannel(roster()); announce()
            }
            // Always reopen: the others said yes to closing with the people there were, not
            // with fewer. They are asked again (or more people join first).
            creatorChannel(JSONObject().put("type", "reopen").put("vote_id", voteId))
            return
        }
        s.accepts.add(tokenHash); save()
        if (s.seats.all { it.tokenHash in s.accepts }) callClosing(s.seats.toList(), "agreed")
    }

    // ------------------------------------------------------------------------------- channel

    /**
     * A channel message. [fromCreator]: it came from the creator's own key (v3), or the pool is
     * v1/v2, where every member speaks with the pool key and all is taken as before.
     */
    private fun onChannel(o: JSONObject, at: Long, fromCreator: Boolean) {
        val v3 = terms.v3
        when (o.optString("type")) {
            "roster" -> {
                if (!fromCreator || s.phase !in setOf(Phase.OPEN, Phase.VOTING)) return
                val seq = o.optInt("seq")
                if (s.creator || seq <= s.rosterSeq) return
                s.rosterSeq = seq
                val seats = Protocol.parseSeats(o.getJSONArray("seats"), terms.script)
                val before = s.seats.size
                s.seats = seats.toMutableList()
                // Removed from the roster (e.g. our coin was spent): nothing more to do here.
                if (myTokenHash != null && seats.none { it.tokenHash == myTokenHash }) {
                    s.phase = Phase.ABORTED; s.reason = "left the pool"
                }
                save()
                // v3: every seat's coins are checked here too, not only by the creator.
                if (v3 && s.phase != Phase.ABORTED) seats.firstOrNull { !seatIsReal(it) }?.let {
                    s.phase = Phase.ABORTED; s.reason = "the pool listed a seat whose coin is not real"; save()
                    env.event(Event.Aborted(s.poolId, s.reason)); return
                }
                if (!s.creator && seats.size > before && before > 0) env.event(Event.Joined(s.poolId, seats.size))
            }
            "close_request" -> {
                if (v3) {
                    // Only the creator reads it: a member proves its seat with a MAC, never the token.
                    if (!s.creator || s.phase != Phase.OPEN || s.seats.size < terms.minPeers) return
                    val th = o.getString("token_hash"); val id = o.getString("vote_id")
                    if (s.seats.none { it.tokenHash == th } || !Protocol.macOk(s.tokens[th], o.getString("mac"), "close", id)) return
                    creatorChannel(JSONObject().put("type", "vote_open").put("by", th).put("vote_id", id))
                    return
                }
                val token = o.getString("token")
                openVote(Protocol.tokenHash(token), o.getString("vote_id"), at)
            }
            "vote_open" -> if (v3 && fromCreator) openVote(o.getString("by"), o.getString("vote_id"), at)
            "vote" -> if (s.creator) {
                if (v3) {
                    val th = o.getString("token_hash"); val id = o.getString("vote_id"); val yes = o.getBoolean("accept")
                    if (!Protocol.macOk(s.tokens[th], o.getString("mac"), "vote", id, yes)) return
                    creatorOnVote(th, id, yes)
                } else {
                    val th = Protocol.tokenHash(o.getString("token"))
                    creatorOnVote(th, o.getString("vote_id"), o.getBoolean("accept"))
                }
            }
            "reopen" -> {
                if (!fromCreator || s.phase != Phase.VOTING || o.optString("vote_id") != s.voteId) return
                s.phase = Phase.OPEN; s.voteId = null; s.accepts.clear(); save()
                env.event(Event.CloseRefused(s.poolId))
            }
            "closing" -> {
                if (!fromCreator || s.phase !in setOf(Phase.OPEN, Phase.VOTING)) return
                val seats = Protocol.parseSeats(o.getJSONArray("seats"), terms.script)
                // v5: a payment round needs its creator, whose payments are the round's other side.
                if (terms.v5 && seats.count { isCreatorSeat(it) } != 1) {
                    s.phase = Phase.ABORTED; s.reason = "the round closed without the payment"; save()
                    env.event(Event.Aborted(s.poolId, s.reason)); return
                }
                if (myTokenHash == null || seats.none { it.tokenHash == myTokenHash }) {
                    s.phase = Phase.ABORTED; s.reason = "the round closed without us"; save()
                    env.event(Event.Aborted(s.poolId, s.reason)); return
                }
                // A round smaller than the pool's own minimum mixes too little to be worth signing
                // (a creator could close it with its own extra seats): leave it.
                if (seats.size < terms.minPeers) {
                    s.phase = Phase.ABORTED; s.reason = "the round closed with fewer than ${terms.minPeers} people"; save()
                    env.event(Event.Aborted(s.poolId, s.reason)); return
                }
                if (v3) {
                    val id = o.optString("round_id")
                    if (id.isEmpty() || (s.creator && id != s.roundId)) return
                    s.roundId = id
                }
                s.round = seats.toMutableList(); s.phase = Phase.CLOSING
                s.phaseDeadline = o.optLong("deadline", at + Protocol.OUTPUTS_SECONDS)
                save()
                // The public listing follows the round: "closing" now, "done" once it is out.
                if (s.creator) announce()
                env.event(Event.Closing(s.poolId, seats.size))
                if (!v3) scheduleOutput()
                else if (s.creator) {
                    // The creator's own output needs no blinding from itself: it goes straight in.
                    // v5: its outputs are the payments, listed with the others at the end.
                    if (!terms.v5) s.mixScripts.forEach { s.regOutputs.add(it.toHex()) }
                    s.postedOutput = true; save()
                    listOutputsIfAll()
                } else sendBlind()
            }
            "output" -> {
                if (v3 || s.phase != Phase.CLOSING) return
                val script = o.getString("script")
                if (!scriptOk(script)) return
                s.outputs.add(script); save()
                if (s.outputs.size > s.round.size) return abort("more mixed outputs than people")
                if (s.outputs.size == s.round.size) toSigning()
            }
            "blind" -> if (v3 && s.creator && s.phase == Phase.CLOSING) {
                // A seat of the round asks for its outputs to be signed without showing them: one
                // request per seat, with exactly as many outputs as each person gets (v4: several).
                val th = o.getString("token_hash"); val id = o.getString("round_id")
                val blinded = blindedList(o) ?: return
                if (id != s.roundId || s.round.none { it.tokenHash == th }) return
                val digest = blindDigest(blinded)
                if (!Protocol.macOk(s.tokens[th], o.getString("mac"), "blind", id, digest)) return
                val sig = s.blindSigs[th]?.let { if (s.blindedOf[th] == digest) it else return }
                    ?: BlindRsa.KeyPair.decode(s.blindPriv ?: return).let { kp ->
                        blinded.joinToString(",") { BlindRsa.blindSign(kp, Hashes.hexToBytes(it)).toHex() }
                    }.also { s.blindSigs[th] = it; s.blindedOf[th] = digest; save() }
                val reply = JSONObject().put("type", "blind_sig").put("token_hash", th).put("round_id", id)
                if (perPerson == 1) reply.put("sig", sig) else reply.put("sigs", org.json.JSONArray(sig.split(",")))
                creatorChannel(reply)
            }
            "blind_sig" -> if (v3 && fromCreator && s.phase == Phase.CLOSING && !s.creator) {
                if (o.getString("token_hash") != myTokenHash || o.getString("round_id") != s.roundId || s.outSigs.isNotEmpty()) return
                val pk = blindPk ?: return
                if (s.blindInvs.size != perPerson || s.blindPrefixes.size != perPerson) return
                val given = if (perPerson == 1) listOf(o.getString("sig"))
                    else o.getJSONArray("sigs").let { a -> (0 until a.length()).map { a.getString(it) } }
                if (given.size != perPerson) return abort("the creator's blind signature is wrong")
                val sigs = s.mixScripts.indices.map { i ->
                    val prepared = Hashes.hexToBytes(s.blindPrefixes[i]) + s.mixScripts[i]
                    runCatching { BlindRsa.finalize(pk, prepared, Hashes.hexToBytes(given[i]), BigInteger(s.blindInvs[i], 16)).toHex() }
                        .getOrElse { return abort("the creator's blind signature is wrong") }
                }
                s.outSigs = sigs; save()
                scheduleOutput()
            }
            "outputs" -> if (v3 && fromCreator && s.phase == Phase.CLOSING && o.optString("round_id") == s.roundId) {
                val a = o.getJSONArray("scripts")
                val list = (0 until a.length()).map { a.getString(it) }
                if (list.size != handingIn * perPerson || list.toSet().size != list.size || !list.all { scriptOk(it) }) return abort("the list of mixed outputs is wrong")
                // v5: the creator's payments, exactly the amounts announced, of the pool's type, none
                // of them an address already in the round.
                val pays = if (!terms.v5) emptyList() else {
                    val p = o.optJSONArray("payments") ?: return abort("the payments are missing")
                    val l = (0 until p.length()).map { p.getJSONObject(it) }.map { it.getString("script") to it.getLong("value") }
                    if (l.map { it.second }.sorted() != terms.payments.sorted() || !l.all { scriptOk(it.first) } ||
                        l.map { it.first }.toSet().size != l.size || l.any { it.first in list }) return abort("the payments are not the ones announced")
                    l.map { "${it.first}:${it.second}" }
                }
                s.outputs.clear(); s.outputs.addAll(list); s.payOutputs.clear(); s.payOutputs.addAll(pays); save()
                toSigning()
            }
            "sig" -> {
                if (s.phase != Phase.SIGNING && s.phase != Phase.BROADCAST) return
                val plan = buildPlan() ?: return
                val outpoint = o.getString("outpoint")
                val coin = plan.coins.firstOrNull { it.outpoint == outpoint } ?: return
                val sig = o.getString("sig")
                if (!CoinjoinTx.verify(plan, coin, Hashes.hexToBytes(sig))) return
                s.sigs[outpoint] = sig; save()
                maybeBroadcast(plan)
            }
            "tx" -> if (s.txid == null && s.phase in setOf(Phase.SIGNING, Phase.BROADCAST)) {
                // Someone else broadcast first. The txid leaves out the signatures, so we can
                // check it is the transaction we agreed to, then just wait for it to confirm.
                val plan = buildPlan() ?: return
                if (s.sigs.size == plan.coins.size) return maybeBroadcast(plan)
                val txid = o.getString("txid")
                if (txid != CoinjoinTx.txid(plan)) return
                s.txid = txid; s.phase = Phase.BROADCAST; s.phaseDeadline = env.now(); save()
                // Another wallet sent it: the creator still tells the listing the round is done.
                if (s.creator) announce()
                env.event(Event.Broadcast(s.poolId, txid))
            }
            "leave" -> if (s.creator && s.phase in setOf(Phase.OPEN, Phase.VOTING)) {
                val th = if (v3) o.getString("token_hash").takeIf { Protocol.macOk(s.tokens[it], o.getString("mac"), "leave") } ?: return
                    else Protocol.tokenHash(o.optString("token"))
                if (s.seats.removeAll { it.tokenHash == th }) {
                    s.accepts.remove(th); save()
                    creatorChannel(roster()); announce()
                    if (s.phase == Phase.VOTING && s.seats.size < terms.minPeers)
                        creatorChannel(JSONObject().put("type", "reopen").put("vote_id", s.voteId))
                    else if (s.phase == Phase.VOTING && s.seats.all { it.tokenHash in s.accepts })
                        callClosing(s.seats.toList(), "agreed")
                }
            }
            "abort" -> if (!done && s.phase != Phase.BROADCAST) {
                s.phase = Phase.ABORTED; s.reason = o.optString("reason", "aborted"); save()
                env.event(Event.Aborted(s.poolId, s.reason))
            }
        }
    }

    /** A vote on closing now, asked by the seat [th]: v1/v2 from its close request, v3 from the creator. */
    private fun openVote(th: String, voteId: String, at: Long) {
        if (s.phase != Phase.OPEN || s.seats.size < terms.minPeers) return
        if (s.seats.none { it.tokenHash == th }) return
        s.phase = Phase.VOTING; s.voteId = voteId; s.voteBy = th
        s.voteDeadline = at + Protocol.VOTE_SECONDS
        s.accepts.clear(); s.accepts.add(th)
        save()
        env.event(Event.CloseRequested(s.poolId, s.seats.size, th == myTokenHash))
        if (autoKeys != null && th != myTokenHash) {
            runCatching { vote(true) }.onSuccess { env.event(Event.DidAlone(s.poolId, Alone.ACCEPTED)) }
        }
        if (s.creator && s.seats.all { it.tokenHash in s.accepts }) callClosing(s.seats.toList(), "agreed")
    }

    private fun scriptOk(script: String) =
        script.matches(Regex(if (terms.script == com.kilombino.pyblockwatch.crypto.ScriptType.P2TR) "5120[0-9a-f]{64}" else "0014[0-9a-f]{40}"))

    /** v3: a listed seat's coins are signed for its join key and unspent on the chain. */
    private fun seatIsReal(seat: Protocol.Seat): Boolean {
        if (seat.tokenHash in verifiedSeats || seat.tokenHash == myTokenHash) return true
        val jp = seat.joinPub ?: return false
        if (seat.proofs.size != seat.coins.size) return false
        val ok = seat.coins.indices.all { CoinjoinTx.checkOwnership(terms.id, jp, seat.coins[it], Hashes.hexToBytes(seat.proofs[it])) } &&
            seat.coins.all { runCatching { env.coinUnspent(it) }.getOrDefault(false) }
        if (ok) verifiedSeats.add(seat.tokenHash)
        return ok
    }

    /**
     * v3 member: ask the creator to sign our outputs blindly (the same request again if resent).
     * v3 sends one `blinded` string; v4 an array of them, one per mixed output, in one request.
     */
    private fun sendBlind() {
        if (s.outSigs.isNotEmpty()) { scheduleOutput(); return }
        val pk = blindPk ?: return abort("the pool has no blind-signing key")
        val round = s.roundId ?: return
        if (s.blindedMsgs.size != perPerson) {
            val prefixes = mutableListOf<String>(); val invs = mutableListOf<String>(); val msgs = mutableListOf<String>()
            for (script in s.mixScripts) {
                val prepared = BlindRsa.prepare(script)
                val b = BlindRsa.blind(pk, prepared)
                prefixes += prepared.copyOfRange(0, BlindRsa.PREFIX_LEN).toHex()
                invs += b.inv.toString(16); msgs += b.blindedMsg.toHex()
            }
            s.blindPrefixes = prefixes; s.blindInvs = invs; s.blindedMsgs = msgs; save()
        }
        val digest = blindDigest(s.blindedMsgs)
        lastBlindSent = env.now()
        val o = JSONObject().put("type", "blind").put("token_hash", myTokenHash).put("round_id", round)
            .put("mac", Protocol.mac(s.token ?: return, "blind", round, digest))
        if (perPerson == 1) o.put("blinded", s.blindedMsgs.first()) else o.put("blinded", org.json.JSONArray(s.blindedMsgs))
        channel(o)
    }

    /** The blinded messages of a `blind` request: one string (v3) or exactly [perPerson] of them (v4). */
    private fun blindedList(o: JSONObject): List<String>? {
        val list = if (perPerson == 1) listOf(o.optString("blinded"))
            else o.optJSONArray("blinded")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: return null
        if (list.size != perPerson || list.any { !it.matches(Regex("([0-9a-f]{2})+")) }) return null
        return list
    }

    /** What a `blind` request's MAC covers: sha256 of its blinded messages back to back (v3: of the one). */
    private fun blindDigest(blinded: List<String>): String =
        Hashes.sha256(blinded.map { Hashes.hexToBytes(it) }.reduce { a, b -> a + b }).toHex()

    /** v3 creator: an output handed in with its unblinded signature, from a key nobody can tie to a seat. */
    private fun onOutputHandedIn(o: JSONObject) {
        if (o.optString("type") != "output" || s.phase != Phase.CLOSING || o.optString("round_id") != s.roundId) return
        val script = o.getString("script")
        if (!scriptOk(script)) return
        val prefix = Hashes.hexToBytes(o.getString("prefix"))
        if (prefix.size != BlindRsa.PREFIX_LEN) return
        val pk = blindPk ?: return
        if (!BlindRsa.verify(pk, prefix + Hashes.hexToBytes(script), Hashes.hexToBytes(o.getString("sig")))) return
        s.regOutputs.add(script); save()
        listOutputsIfAll()
    }

    /** v3 creator: once every seat of the round has its outputs in, list them all at once. */
    private fun listOutputsIfAll() {
        if (s.phase != Phase.CLOSING) return
        val want = handingIn * perPerson
        if (s.regOutputs.size > want) return abort("more mixed outputs than people")
        if (s.regOutputs.size < want) return
        // Sorted, so the list says nothing about who handed in first.
        val o = JSONObject().put("type", "outputs").put("round_id", s.roundId)
            .put("scripts", org.json.JSONArray(s.regOutputs.sorted()))
        // v5: our payments, shown for the first time now that the round is closed.
        if (terms.v5) o.put("payments", org.json.JSONArray(s.mixScripts.indices.map { i ->
            JSONObject().put("script", s.mixScripts[i].toHex()).put("value", terms.payments[i])
        }))
        creatorChannel(o)
    }

    /**
     * Each of our outputs after a random wait of its own, so nobody can tie them to us, or (v4)
     * to each other, by when they show up.
     */
    private fun scheduleOutput() {
        if (s.postedOutput) return
        for (i in s.mixScripts.indices) {
            if (i in s.handedIn || i in outputScheduled) continue
            outputScheduled += i
            val delay = 3L + rnd.nextInt(40)
            timer?.schedule({
                synchronized(this) {
                    outputScheduled -= i
                    if (s.phase == Phase.CLOSING && !s.postedOutput && i !in s.handedIn) {
                        val ok = if (terms.v3) handInOutput(i)
                            else channel(JSONObject().put("type", "output").put("script", s.mixScript.toHex()))
                        if (ok) {
                            s.handedIn += i
                            if (s.handedIn.size == s.mixScripts.size) s.postedOutput = true
                            save()
                        }
                    }
                }
            }, delay, TimeUnit.SECONDS)
        }
    }

    /**
     * v3: our output [i] with its unblinded signature, from a key made for it alone, over a
     * connection of its own (through Tor when it runs), encrypted to the creator: nothing in it
     * points back at our seat, nor (v4) at our other outputs.
     */
    private fun handInOutput(i: Int): Boolean {
        val sig = s.outSigs.getOrNull(i) ?: return false
        val fresh = NostrEvent.newSecret()
        val o = JSONObject().put("type", "output").put("round_id", s.roundId).put("script", s.mixScripts[i].toHex())
            .put("prefix", s.blindPrefixes[i]).put("sig", sig)
        val ev = NostrEvent.sign(fresh, Protocol.KIND_MSG, listOf(listOf("p", terms.creatorPub!!)),
            Nip44.encrypt(o.toString(), Nip44.conversationKey(fresh, Hashes.hexToBytes(terms.creatorPub))))
        val r = RelayClient(Protocol.RELAY, { _, _ -> }, {})
        return try {
            r.connect()
            val res = r.publish(ev)
            env.log("hand in output -> ${res.first} ${res.second}")
            res.first
        } catch (e: Exception) { env.log("hand in output failed: ${e.message}"); false }
        finally { runCatching { r.close() } }
    }

    /** v5: the creator's payments as listed at closing. */
    private fun payments(): List<TxBuilder.Output> = s.payOutputs.map {
        TxBuilder.Output(Hashes.hexToBytes(it.substringBefore(':')), it.substringAfter(':').toLong())
    }

    private fun buildPlan(): CoinjoinTx.Plan? {
        if (s.round.size < Protocol.MIN_PEERS || s.outputs.size != handingIn * perPerson) return null
        if (terms.v5 && s.payOutputs.size != terms.payments.size) return null
        return CoinjoinTx.plan(
            s.round.flatMap { it.coins },
            s.outputs.map { Hashes.hexToBytes(it) },
            s.round.filter { it.changeScript != null && it.changeValue > 0 }.map { TxBuilder.Output(it.changeScript!!, it.changeValue) },
            terms.amount, perPerson, payments(),
        )
    }

    /** What we check before signing [plan]: null when it pays us right, else why not. */
    private fun checkPlan(plan: CoinjoinTx.Plan): String? {
        val change = s.seat.changeScript?.let { TxBuilder.Output(it, s.seat.changeValue) }
        if (!terms.v5) return CoinjoinTx.checkOurs(plan, s.mixScripts, change, terms.amount, terms.feeRate, s.round.map { it.coins.size }, perPerson)
        // v5: ours are the payments (creator) or our mixed outputs; the creator's seat has as many outputs as payments.
        val mine = if (s.creator) s.mixScripts.indices.map { TxBuilder.Output(s.mixScripts[it], terms.payments[it]) }
            else s.mixScripts.map { TxBuilder.Output(it, terms.amount) }
        return CoinjoinTx.checkPayment(plan, mine, change, terms.amount, terms.feeRate, s.round.map { it.coins.size },
            s.round.map { if (isCreatorSeat(it)) terms.payments.size else perPerson }, perPerson, terms.payments,
            s.round.count { it.changeScript != null && it.changeValue > 0 })
    }

    private fun toSigning() {
        val plan = runCatching { buildPlan() }.getOrElse { return abort("the transaction does not add up: ${it.message}") } ?: return
        checkPlan(plan)?.let { return abort(it) }
        s.phase = Phase.SIGNING; s.phaseDeadline = env.now() + Protocol.SIGN_SECONDS; save()
        autoKeys?.let { keys ->
            runCatching { sign(keys) }
                .onSuccess { env.event(Event.DidAlone(s.poolId, Alone.SIGNED)); return }
                .onFailure { autoKeys = null; env.log("did not sign on its own: ${it.message}") }
        }
        env.event(Event.SignNeeded(s.poolId, plan.coins.size))
    }

    /** The user approved: sign our one input with [privateKey] (derived from [State.coinPath]). */
    fun sign(privateKey: BigInteger) = sign(listOf(privateKey))

    /** The user approved: sign each of our inputs, [privateKeys] in the order of [State.coinPaths]. */
    @Synchronized
    fun sign(privateKeys: List<BigInteger>) {
        check(s.phase == Phase.SIGNING) { "nothing to sign right now" }
        require(privateKeys.size == s.seat.coins.size) { "one key per coin is needed" }
        val plan = buildPlan() ?: error("the transaction is not complete")
        checkPlan(plan)?.let { error(it) }
        s.seat.coins.forEachIndexed { i, c ->
            val mine = plan.coins.first { it.outpoint == c.outpoint }
            val sig = CoinjoinTx.sign(plan, mine, privateKeys[i]).toHex()
            s.sigs[mine.outpoint] = sig
            check(channel(JSONObject().put("type", "sig").put("outpoint", mine.outpoint).put("sig", sig))) { "the relay did not take our signature" }
        }
        s.postedSig = true; save()
        maybeBroadcast(plan)
    }

    private fun maybeBroadcast(plan: CoinjoinTx.Plan) {
        if (s.sigs.size < plan.coins.size || s.txid != null) return
        val tx = CoinjoinTx.assemble(plan, s.sigs.mapValues { Hashes.hexToBytes(it.value) })
        val ok = runCatching { env.broadcast(tx.rawHex) }
        // Another member may have broadcast it already: the same txid is just as good. A
        // conflict is not: another transaction spends one of the coins, so this one never will be.
        if (ok.isFailure && runCatching { env.confirmations(tx.txid) }.getOrNull() == null) {
            val msg = ok.exceptionOrNull()?.message ?: "broadcast failed"
            if (!msg.contains("already", true)) return abort("broadcast failed: $msg")
        }
        s.txid = tx.txid; s.phase = Phase.BROADCAST; s.phaseDeadline = env.now(); save()
        channel(JSONObject().put("type", "tx").put("txid", tx.txid))
        announce()
        env.event(Event.Broadcast(s.poolId, tx.txid))
    }

    private fun abort(reason: String) {
        if (done) return
        s.phase = Phase.ABORTED; s.reason = reason; save()
        channel(JSONObject().put("type", "abort").put("reason", reason))
        if (s.creator) announce()
        env.event(Event.Aborted(s.poolId, reason))
    }

    // ------------------------------------------------------------------------------- user actions

    /** Ask everyone to close the pool now (two or more people in). */
    @Synchronized
    fun requestClose() {
        check(s.phase == Phase.OPEN) { "the pool is not open" }
        check(s.seats.size >= terms.minPeers) { "at least ${terms.minPeers} people are needed" }
        val id = randomHex(8)
        when {
            !terms.v3 -> channel(JSONObject().put("type", "close_request").put("token", s.token).put("vote_id", id))
            s.creator -> creatorChannel(JSONObject().put("type", "vote_open").put("by", myTokenHash).put("vote_id", id))
            else -> channel(JSONObject().put("type", "close_request").put("token_hash", myTokenHash).put("vote_id", id)
                .put("mac", Protocol.mac(s.token ?: return, "close", id)))
        }
    }

    @Synchronized
    fun vote(accept: Boolean) {
        val id = s.voteId ?: return
        if (s.phase != Phase.VOTING || s.votedOn == id) return
        if (terms.v3) channel(JSONObject().put("type", "vote").put("token_hash", myTokenHash).put("vote_id", id).put("accept", accept)
            .put("mac", Protocol.mac(s.token ?: return, "vote", id, accept)))
        else channel(JSONObject().put("type", "vote").put("token", s.token).put("vote_id", id).put("accept", accept))
        s.votedOn = id
        // A member saying "not yet" leaves this round; the creator keeps its pool open.
        if (!accept && !s.creator) { s.phase = Phase.ABORTED; s.reason = LEFT_BY_CHOICE }
        save()
    }

    /** Leave an open pool: the creator ends it for everyone, a member just gives up its seat. */
    @Synchronized
    fun leave() {
        if (s.phase !in setOf(Phase.JOINING, Phase.OPEN, Phase.VOTING)) return
        if (s.creator) { abort("the creator closed the pool"); return }
        if (s.phase == Phase.JOINING) { s.phase = Phase.ABORTED; s.reason = LEFT_BY_CHOICE; save(); return }
        if (terms.v3) channel(JSONObject().put("type", "leave").put("token_hash", myTokenHash ?: "")
            .put("mac", s.token?.let { Protocol.mac(it, "leave") } ?: ""))
        else channel(JSONObject().put("type", "leave").put("token", s.token ?: ""))
        s.phase = Phase.ABORTED; s.reason = LEFT_BY_CHOICE; save()
    }

    // ------------------------------------------------------------------------------- timers

    private fun tick() = synchronized(this) {
        runCatching {
            val now = env.now()
            ensureConnected()
            // Heartbeat: an open pool's creator re-announces it, so a pool whose creator's app is
            // gone stops being listed (see CoinjoinHub.fetchPools) instead of taking join requests
            // nobody will ever answer.
            if (s.creator && s.phase in setOf(Phase.OPEN, Phase.VOTING) && now - lastAnnounce > Protocol.HEARTBEAT) announce()
            // A creator that stopped re-announcing (every HEARTBEAT while open) is gone and will
            // not take the round anywhere: let it go instead of showing it until it expires.
            if (!s.creator && s.phase in setOf(Phase.JOINING, Phase.OPEN, Phase.VOTING) && listedAt > 0 &&
                now - listedAt > Protocol.STALE_AFTER) {
                s.phase = Phase.ABORTED
                s.reason = "the pool's creator stopped answering"
                save(); env.event(Event.Aborted(s.poolId, s.reason))
                return@runCatching
            }
            when (s.phase) {
                Phase.OPEN, Phase.JOINING -> if (s.phase == Phase.OPEN && !s.warnedExpiry && now <= terms.expiresAt &&
                    terms.expiresAt - now <= EXPIRY_WARN_SECONDS && s.seats.size >= terms.minPeers && s.seats.size < terms.maxPeers) {
                    // A last call: enough people to mix, and the pool is about to expire for nothing.
                    s.warnedExpiry = true; save()
                    if (autoKeys != null && runCatching { requestClose() }.isSuccess) env.event(Event.DidAlone(s.poolId, Alone.ASKED_TO_CLOSE))
                    else env.event(Event.ExpiringSoon(s.poolId, s.seats.size, ((terms.expiresAt - now) / 60).toInt().coerceAtLeast(1)))
                } else if (now > terms.expiresAt) {
                    if (s.creator) abort("the pool expired") else { s.phase = Phase.ABORTED; s.reason = "the pool expired"; save(); env.event(Event.Aborted(s.poolId, s.reason)) }
                } else if (s.phase == Phase.JOINING && !s.creator && now - s.created > JOIN_TIMEOUT) {
                    // Only the creator's wallet lets people in; if it is closed, or the pool ended
                    // meanwhile, nobody ever answers. Give up instead of waiting until it expires.
                    s.phase = Phase.ABORTED
                    s.reason = "the pool did not answer (it may have ended, or its creator's app is closed)"
                    save(); env.event(Event.Aborted(s.poolId, s.reason))
                }
                Phase.VOTING -> if (now > terms.expiresAt) {
                    // A vote does not keep a pool alive past its own expiry.
                    if (s.creator) abort("the pool expired") else { s.phase = Phase.ABORTED; s.reason = "the pool expired"; save(); env.event(Event.Aborted(s.poolId, s.reason)) }
                } else if (!s.creator && now > s.voteDeadline + VOTE_GRACE) {
                    // Only the creator ends a vote, by closing or reopening. Nothing came: its app
                    // is gone, and waiting would show this round forever. The coin never left.
                    s.phase = Phase.ABORTED
                    s.reason = "the pool's creator stopped answering"
                    save(); env.event(Event.Aborted(s.poolId, s.reason))
                } else if (s.creator && now > s.voteDeadline) {
                    // Whoever did not answer is left out, if two or more said yes.
                    val yes = s.seats.filter { it.tokenHash in s.accepts }
                    if (yes.size >= terms.minPeers) callClosing(yes, "agreed")
                    else {
                        // Too few for now. Whoever did not answer gives up the seat too, so a
                        // silent wallet cannot hold the pool (and every later vote) hostage.
                        val silent = s.seats.filter { it.tokenHash !in s.accepts && it.tokenHash != myTokenHash }
                        if (silent.isNotEmpty()) {
                            s.seats.removeAll(silent.toSet()); save()
                            creatorChannel(roster()); announce()
                        }
                        creatorChannel(JSONObject().put("type", "reopen").put("vote_id", s.voteId))
                    }
                }
                Phase.CLOSING -> if (now > s.phaseDeadline + 60) abort("not everyone sent their output in time")
                else if (terms.v3 && !s.creator) {
                    // A request or an output the relay did not take is sent again.
                    if (s.outSigs.isEmpty() && now - lastBlindSent > 30) sendBlind()
                    else if (s.outSigs.isNotEmpty() && !s.postedOutput) scheduleOutput()
                } else if (terms.v3 && s.creator) listOutputsIfAll()
                Phase.SIGNING -> if (now > s.phaseDeadline + 60) abort("not everyone signed in time")
                Phase.BROADCAST -> s.txid?.let { txid ->
                    val c = runCatching { env.confirmations(txid) }.getOrNull()
                    if (c != null && c >= 1) { s.phase = Phase.CONFIRMED; save(); env.event(Event.Confirmed(s.poolId, txid)); stop() }
                    else if (c == null) {
                        // Never seen by the server half a day after it went out: a coin of the round
                        // was spent elsewhere, so this transaction can't confirm. Let the round go
                        // (its coins are free again) instead of waiting for good.
                        if (s.phaseDeadline > 0 && now > s.phaseDeadline + BROADCAST_GIVE_UP) {
                            s.phase = Phase.ABORTED; s.reason = "the transaction never appeared: a coin of the round was spent elsewhere"
                            save(); env.event(Event.Aborted(s.poolId, s.reason)); stop()
                        }
                    } else s.phaseDeadline = now // seen (unconfirmed): wait for it, however long it takes
                }
                else -> {}
            }
        }
    }

    private fun randomHex(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }.toHex()

    companion object {
        /** Reason of a round this wallet left on purpose: its card goes away by itself. */
        const val LEFT_BY_CHOICE = "you left this round"
        /** Seconds a broadcast transaction may stay unseen before the round is given up. */
        const val BROADCAST_GIVE_UP = 12 * 3600L
        /** Seconds a join request waits for the creator's answer. */
        const val JOIN_TIMEOUT = 5 * 60
        /** Seconds past a vote's deadline the others wait for the creator to close or reopen. */
        const val VOTE_GRACE = 3 * 60L
        /** Seconds before expiry when a pool that could close is called out. */
        const val EXPIRY_WARN_SECONDS = 3600

        /**
         * Everything to create a seat, done once at join/create time with the coin's key at hand:
         * the ownership proof and the signed change. The key itself is not kept.
         */
        fun newState(
            terms: Protocol.Terms, creator: Boolean, poolSecret: String?, coin: CoinjoinTx.Coin, coinKey: BigInteger,
            coinPath: String, mixScript: ByteArray, changeScript: ByteArray?,
            password: String? = null,
        ): State = newState(terms, creator, poolSecret, listOf(coin), listOf(coinKey), listOf(coinPath), listOf(mixScript), changeScript, password)

        fun newState(
            terms: Protocol.Terms, creator: Boolean, poolSecret: String?, coins: List<CoinjoinTx.Coin>, coinKeys: List<BigInteger>,
            coinPaths: List<String>, mixScript: ByteArray, changeScript: ByteArray?,
            password: String? = null, creatorSecret: String? = null, blindPriv: String? = null,
        ): State = newState(terms, creator, poolSecret, coins, coinKeys, coinPaths, listOf(mixScript), changeScript, password, creatorSecret, blindPriv)

        /**
         * [coins] with their [coinKeys] and [coinPaths], same order; the first one signs the change.
         * [mixScripts]: one unused address per mixed output the pool gives each person.
         */
        fun newState(
            terms: Protocol.Terms, creator: Boolean, poolSecret: String?, coins: List<CoinjoinTx.Coin>, coinKeys: List<BigInteger>,
            coinPaths: List<String>, mixScripts: List<ByteArray>, changeScript: ByteArray?,
            password: String? = null, creatorSecret: String? = null, blindPriv: String? = null,
        ): State {
            if (creator && terms.v3) require(creatorSecret != null && blindPriv != null) { "a v3 pool's creator needs its keys" }
            require(coins.isNotEmpty() && coinKeys.size == coins.size && coinPaths.size == coins.size) { "one key and path per coin" }
            require(coins.size <= terms.maxInputs) { "this pool takes at most ${terms.maxInputs} coin(s) per person" }
            require(coins.all { it.type == terms.script }) { "these coins are not of the pool's address type" }
            require(coins.map { it.outpoint }.toSet().size == coins.size) { "the same coin twice" }
            // v5: the creator's outputs are its payments, one script per announced amount.
            val outs = if (terms.v5 && creator) terms.payments.size else terms.outputs
            require(mixScripts.size == outs) { if (terms.v5 && creator) "one address per payment" else "this pool gives ${terms.outputs} mixed output(s) each" }
            require(mixScripts.all { CoinjoinTx.isType(it, terms.script) }) { "the mixed output is not of the pool's address type" }
            require(mixScripts.map { it.toHex() }.toSet().size == mixScripts.size) { "the same mixed address twice" }
            require(changeScript == null || mixScripts.none { it.contentEquals(changeScript) }) { "the change and a mixed output share an address" }
            val changeValue = CoinjoinTx.changeOf(coins.sumOf { it.value }, terms.mixTotal, terms.feeRate, coins.size, terms.script, outs)
                ?: error("these coins are too small for the pool")
            val cs = if (changeValue > 0) changeScript ?: error("a change address is needed") else null
            cs?.let { require(CoinjoinTx.isType(it, terms.script)) { "the change is not of the pool's address type" } }
            val joinSecret = NostrEvent.newSecret()
            val joinPub = NostrEvent.pubOf(joinSecret)
            val proofs = coins.indices.map {
                CoinjoinTx.proveOwnership(terms.id, if (creator) terms.poolPub else joinPub, coins[it], coinKeys[it]).toHex()
            }
            val changeSig = Ecdsa.der(Ecdsa.sign(coinKeys[0], Protocol.changeMessage(terms.id, coins, cs, changeValue))).toHex()
            return State(
                terms = terms, creator = creator, poolSecret = poolSecret, joinSecret = joinSecret.toString(16),
                token = null, seat = Protocol.Seat("", coins, cs, changeValue), joinProofs = proofs, changeSig = changeSig,
                mixScripts = mixScripts, coinPaths = coinPaths,
                pwKey = if (creator && terms.private) Protocol.passwordKey(terms.id, password ?: error("a private pool needs a password")).toHex() else null,
                pwProof = if (!creator && terms.private)
                    Protocol.passwordProof(Protocol.passwordKey(terms.id, password ?: error("this pool needs its password")), joinPub) else null,
                creatorSecret = if (creator) creatorSecret else null, blindPriv = if (creator) blindPriv else null,
            )
        }

        /**
         * Terms for a new pool, with its own fresh key. Returns (terms, pool secret hex). A native
         * SegWit pool of one coin each is a v1 pool, which older wallets can join too; Taproot or
         * several coins per person make it v2.
         */
        /** A new pool's terms and keys; the v3 ones ([creatorSecret], [blindPriv]) stay with its creator. */
        data class NewPool(val terms: Protocol.Terms, val secret: String, val creatorSecret: String? = null, val blindPriv: String? = null)

        fun newPool(amount: Long, feeRate: Double, maxPeers: Int, hours: Int, minPeers: Int = Protocol.MIN_PEERS,
                    private: Boolean = false, script: com.kilombino.pyblockwatch.crypto.ScriptType = com.kilombino.pyblockwatch.crypto.ScriptType.P2WPKH,
                    maxInputs: Int = 1, v3: Boolean = false, outputs: Int = 1,
                    /** v5: the creator's payments, summing to [outputs] × [amount]. */
                    payments: List<Long> = emptyList()): NewPool {
            val k = NostrEvent.newSecret()
            val id = ByteArray(8).also { SecureRandom().nextBytes(it) }.toHex()
            val now = System.currentTimeMillis() / 1000
            require(minPeers in Protocol.MIN_PEERS..maxPeers) { "the minimum must be between 2 and the maximum" }
            require(maxInputs in 1..Protocol.MAX_INPUTS) { "1 to ${Protocol.MAX_INPUTS} coins per person" }
            val v1 = script == com.kilombino.pyblockwatch.crypto.ScriptType.P2WPKH && maxInputs == 1
            require(outputs in 1..Protocol.MAX_OUTPUTS) { "1 to ${Protocol.MAX_OUTPUTS} mixed outputs per person" }
            require(outputs == 1 || v3) { "several outputs each need a v4 pool" }
            require(payments.isEmpty() || (v3 && Protocol.paymentsOk(payments, amount, outputs))) {
                "the payments must add up to what each joiner gets"
            }
            if (v3) {
                val ck = NostrEvent.newSecret()
                val blind = BlindRsa.generate()
                return NewPool(Protocol.Terms(id, NostrEvent.pubOf(k), amount, feeRate, maxPeers, now + hours * 3600L, "open", 1, now,
                    // One output each stays v3, which every v3 wallet can join; more need v4.
                    minPeers, private, if (payments.isNotEmpty()) Protocol.V5 else if (outputs > 1) Protocol.V4 else Protocol.V3,
                    script, maxInputs, NostrEvent.pubOf(ck), blind.public.encode(), outputs, payments),
                    k.toString(16).padStart(64, '0'), ck.toString(16).padStart(64, '0'), blind.encodePrivate())
            }
            return NewPool(Protocol.Terms(id, NostrEvent.pubOf(k), amount, feeRate, maxPeers, now + hours * 3600L, "open", 1, now,
                minPeers, private, if (v1) Protocol.V1 else Protocol.VERSION, script, maxInputs), k.toString(16).padStart(64, '0'))
        }
    }
}
