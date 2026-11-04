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
        /** [coinUnspent] for several coins at once (one connection); null where the chain could not be asked. */
        fun coinsUnspent(coins: List<CoinjoinTx.Coin>): List<Boolean?> = coins.map { c -> runCatching { coinUnspent(c) }.getOrNull() }
        fun broadcast(rawHex: String): String
        /** Confirmations of [txid], 0 in the mempool, null when unknown. */
        fun confirmations(txid: String): Int?
        /**
         * v6: confirmations of the transaction that spends [coin], when that is not [roundTxid] (0 in the mempool);
         * null when the coin is unspent, spent by the round itself, or the chain could not tell. Null never frees a round.
         */
        fun conflictDepth(coin: CoinjoinTx.Coin, roundTxid: String): Int? = null
        fun save(state: State)
        fun event(e: Event)
        fun now(): Long = System.currentTimeMillis() / 1000
        fun log(msg: String) {}
        /**
         * The token hashes of every seat this wallet holds in pool [poolId] (this one included, once
         * welcomed). They are one person: every decision on how many people a round has counts them
         * as one ([people]). Nothing of it goes on the wire; other wallets count seats as before.
         */
        fun ownSeats(poolId: String): Set<String> = emptySet()
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
        /**
         * Another seat of this phone in the same pool: a short random id of its own ("" for the first
         * seat, and for every round saved before seats could be more than one, so they keep their key).
         */
        val seatId: String = "",
        /** Not before this time (epoch seconds) is the join sent, or the pool opened: a random wait. */
        val joinAfter: Long = 0,
        /** A perpetual coinjoin's round: the remix ([Perpetual]) this seat belongs to. */
        val perpId: String? = null,
        /**
         * v3: how many times the round was closed again without seats that never asked for their outputs'
         * blind signatures (a phone asleep at closing). 0 for the first closing.
         */
        var closeSeq: Int = 0,
        /** Creator: the vote its closing went out for. Another yes or the vote's deadline doesn't close it again. */
        var closedVote: String? = null,
        /** Member: when the closing we took was sent; only a later one from the creator replaces it (see "closing"). */
        var closingAt: Long = 0,
        /** When the round's transaction went out (epoch seconds; 0 before, and for rounds saved before this). */
        var sentAt: Long = 0,
        /** When the round ended: confirmed, cancelled or refused (epoch seconds; 0 while it goes on). */
        var endedAt: Long = 0,
        // ---- v6 (asynchronous rounds); all 0/empty before v6.
        /** The roster number the current vote is over. */
        var voteSeq: Int = 0,
        /** The number of the newest vote applied (creator and member alike); an older vote_open, reopen or closing is stale. */
        var voteN: Int = 0,
        /** The number of the newest vote that ended without a round, or whose round was called off. */
        var voteEndedN: Int = 0,
        /** Member: the vote whose answer the creator acknowledged recording (`vote_ack`, or our id in a snapshot). */
        var voteAcked: String? = null,
        /** Creator: votes it numbered so far, and their ids, so a replayed request never opens one again. */
        var votesSent: Int = 0,
        val usedVotes: MutableSet<String> = linkedSetOf(),
        /** Creator: votes it opened again by itself after one timed out with silent seats. */
        var revotes: Int = 0,
        /** Creator: the newest message of each type that runs the pool, `{"at":…,"msg":…}`, replayed in snapshots. */
        val lastMsgs: MutableMap<String, String> = linkedMapOf(),
        /** Snapshots: numbered by the creator; a member applies only a newer one. */
        var stateN: Int = 0,
        /** When this wallet had signed all its inputs (its coins are reserved from then until the round resolves). */
        var signedAt: Long = 0,
        /** v6: confirmations of the round's transaction at the last look (it counts as confirmed at [DEPTH]). */
        var confs: Int = 0,
    ) {
        val poolId: String get() = terms.id
        /** What this seat is kept under on the phone: the pool's id, or with [seatId] for another seat in it. */
        val key: String get() = keyOf(poolId, seatId)
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
            .put("seat_id", seatId).put("join_after", joinAfter).put("perp_id", perpId ?: "")
            .put("close_seq", closeSeq).put("closed_vote", closedVote ?: "").put("closing_at", closingAt)
            .put("sent_at", sentAt).put("ended_at", endedAt)
            .also { if (terms.v6) it.put("vote_seq", voteSeq).put("vote_n", voteN).put("vote_ended_n", voteEndedN)
                .put("vote_acked", voteAcked ?: "").put("votes_sent", votesSent).put("used_votes", org.json.JSONArray(usedVotes.toList()))
                .put("revotes", revotes).put("last_msgs", JSONObject(lastMsgs as Map<*, *>)).put("state_n", stateN).put("signed_at", signedAt)
                .put("confs", confs) }

        companion object {
            private fun JSONObject.str(k: String): String? = optString(k).ifEmpty { null }
            private fun JSONObject.map(k: String): MutableMap<String, String> {
                val o = optJSONObject(k) ?: return mutableMapOf()
                return o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
            }
            /** The key of seat [seatId] of pool [poolId]: the pool id alone for its first seat. */
            fun keyOf(poolId: String, seatId: String): String = if (seatId.isEmpty()) poolId else "$poolId#$seatId"
            /** The pool a seat [key] is in. */
            fun poolOf(key: String): String = key.substringBefore('#')

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
                    t.optJSONArray("payments")?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList(),
                    t.optJSONObject("async")?.let { Protocol.Async.parse(it) })
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
                    o.optString("seat_id"), o.optLong("join_after"), o.str("perp_id"), o.optInt("close_seq"),
                    o.str("closed_vote"), o.optLong("closing_at"),
                    // Rounds sent before these were kept: phase_deadline was set to the time it went out.
                    o.optLong("sent_at").takeIf { it > 0 } ?: if (o.optString("phase") in setOf("BROADCAST", "CONFIRMED")) o.optLong("phase_deadline") else 0L,
                    o.optLong("ended_at"),
                    o.optInt("vote_seq"), o.optInt("vote_n"), o.optInt("vote_ended_n"), o.str("vote_acked"),
                    o.optInt("votes_sent"),
                    o.optJSONArray("used_votes")?.let { a -> (0 until a.length()).map { a.getString(it) } }?.toMutableSet() ?: linkedSetOf(),
                    o.optInt("revotes"), o.map("last_msgs"), o.optInt("state_n"), o.optLong("signed_at"), o.optInt("confs"),
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
    /** This seat's own Tor circuit (see [RelayClient]): the same across restarts, another for every seat. */
    private val isolation = "kj-" + Hashes.sha256(s.joinSecret.toByteArray()).toHex().take(16)
    /** The join was sent on this run (it goes again on every reconnect while waiting, as before). */
    @Volatile private var joinSent = false
    private val terms = s.terms
    private val creatorKey: BigInteger? get() = s.creatorSecret?.let { BigInteger(it, 16) }
    private val blindPk: BlindRsa.PublicKey? by lazy { terms.blindKey?.let { BlindRsa.PublicKey.decode(it) } }
    /** Seats whose coins this wallet already checked against the chain (v3 roster). */
    private val verifiedSeats = mutableSetOf<String>()
    /** Member, v3: listed seats the chain says are not real, and since when (the creator gets [FAKE_GRACE] to take them out). */
    private val fakeSince = mutableMapOf<String, Long>()
    /** Creator: when the seats' coins were last checked against the chain; member: the fake seats. */
    private var lastSeatCheck = 0L
    private var lastBlindSent = 0L
    /** Our outputs waiting for their random moment to be handed in (by index). */
    private val outputScheduled = mutableSetOf<Int>()
    /** Mixed outputs each person gets: 1 before v4 (each joiner, in v5). */
    private val perPerson: Int get() = terms.outputs

    // ---- v6, in memory only (the round itself is in [State]).
    /** A vote_open that came before the roster it is over (events out of order): applied once that roster is in. */
    private var pendingVoteOpen: Pair<JSONObject, Long>? = null
    private var lastVoteSent = 0L
    private var lastSync = 0L
    private var lastStatePub = 0L
    private var lastResolve = 0L
    private var lastSigSent = 0L

    /** v5: the creator's seat, the one it joined with the pool's own key. */
    private fun isCreatorSeat(seat: Protocol.Seat) = seat.joinPub == terms.poolPub

    /** Seats whose mixed outputs are handed in blindly: all of them, but the creator's in v5. */
    private val handingIn: Int get() = if (terms.v5) s.round.count { !isCreatorSeat(it) } else s.round.size

    val state: State get() = s

    /** This wallet's seats in the pool, by token hash: [Env.ownSeats] and our own. */
    private fun ownSeats(): Set<String> = env.ownSeats(s.poolId) + listOfNotNull(myTokenHash)

    /** The people among [seats]: our own seats in them count as one (see [distinctPeople]). */
    private fun people(seats: Collection<Protocol.Seat>): Int = distinctPeople(seats.map { it.tokenHash }, ownSeats())

    /** People in the pool now, as this wallet sees them (its own seats as one). */
    fun people(): Int = synchronized(this) { people(if (s.round.isNotEmpty() && s.phase !in setOf(Phase.OPEN, Phase.VOTING)) s.round else s.seats) }

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

    /** The round's txid: the one sent, or while signing the one it will have (signatures don't change it). */
    @Synchronized fun plannedTxid(): String? = s.txid ?: if (s.phase == Phase.SIGNING) plan()?.let { CoinjoinTx.txid(it) } else null

    // ------------------------------------------------------------------------------- lifecycle

    @Synchronized
    fun start() {
        if (done) return
        if (timer != null) return // already running
        if (worker.isShutdown) worker = Executors.newSingleThreadExecutor()
        timer = Executors.newSingleThreadScheduledExecutor().also { it.scheduleWithFixedDelay({ tick() }, minOf(5, TICK_SECONDS), TICK_SECONDS, TimeUnit.SECONDS) }
        connect()
    }

    @Synchronized
    fun stop() {
        timer?.shutdownNow(); timer = null
        worker.shutdown()
        relay?.close(); relay = null
    }

    private fun connect() {
        val r = RelayClient(Protocol.RELAY, { _, ev -> runCatching { worker.execute { onEvent(ev) } } }, { relay = null }, isolation)
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
        // Another seat of this phone waits its random moment first (see [State.joinAfter]).
        if (s.phase == Phase.JOINING && env.now() >= s.joinAfter) { if (s.creator) creatorStart() else sendJoin() }
        // v6: back online, ask the creator where the round stands (its answer goes through the usual checks).
        if (terms.v6 && !s.creator && s.token != null && !done) worker.execute { synchronized(this) { runCatching { sendSync() } } }
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

    private fun save() {
        // The times HISTORY shows: when it went out, and when it was over.
        if (s.phase in setOf(Phase.BROADCAST, Phase.CONFIRMED) && s.sentAt == 0L) s.sentAt = env.now()
        if (s.phase in setOf(Phase.CONFIRMED, Phase.ABORTED, Phase.REJECTED) && s.endedAt == 0L) s.endedAt = env.now()
        env.save(s); env.event(Event.Changed(s.poolId))
    }

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
        // v6: the terms this wallet joins under, its windows and final time among them.
        if (terms.v6) o.put("terms", Protocol.termsHash(terms))
        joinSent = true
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
                        onOutputHandedIn(JSONObject(msg), ev.pubkey)
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
                // v6: welcomed under the terms we joined for, or not at all.
                if (terms.v6 && o.optString("terms") != Protocol.termsHash(terms)) {
                    s.phase = Phase.REJECTED; s.reason = "the pool's terms are not the ones it announced"
                    save(); env.event(Event.Rejected(s.poolId, s.reason)); return
                }
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
        joinSent = true
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
            .also { keepLast(it) }
    }

    private fun reject(joinPub: String, reason: String) { env.log("reject: $reason"); replyKey?.let { direct(joinPub, JSONObject().put("type", "reject").put("reason", reason), it) } }

    private fun onJoinRequest(from: String, o: JSONObject) {
        if (o.optString("type") != "join") return
        if (s.joinPubs.containsKey(from)) return // already handled (history replay)
        if (s.phase != Phase.OPEN) return reject(from, "the pool is no longer open")
        // A seated coin spent since it joined would make every newcomer leave again: out first.
        evictSpent()
        if (s.phase != Phase.OPEN) return reject(from, "the pool is no longer open")
        if (s.seats.size >= terms.maxPeers) return reject(from, "the pool is full")
        if (terms.v6 && o.optString("terms") != Protocol.termsHash(terms)) return reject(from, "the pool's terms are not the ones you joined for")
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
        val held = s.seats.filter { st -> st.coins.any { it.outpoint in outpoints } }
        if (held.any { it.tokenHash == myTokenHash }) return reject(from, "that coin already has a seat")
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
        // The same coin joining again, proven by its own key: the wallet lost its earlier seat
        // (it left without being heard, or its app was reinstalled), so that seat gives way.
        // Before, the coin was refused for as long as the pool lived.
        if (held.isNotEmpty()) {
            val gone = held.map { it.tokenHash }.toSet()
            s.seats.removeAll { it.tokenHash in gone }
            gone.forEach { th -> s.tokens.remove(th); s.accepts.remove(th) }
            // Its old join key stays listed: forgetting it made every replay of the channel hand the
            // seat back to that key and then to the new one, again and again.
        }
        val token = randomHex(16)
        val th = Protocol.tokenHash(token)
        // v3: the seat carries its join key and proofs, so every member can check the coin too.
        val placed = if (terms.v3) seat.copy(tokenHash = th, joinPub = from, proofs = proofs) else seat.copy(tokenHash = th)
        s.tokens[th] = token; s.joinPubs[from] = th; s.seats.add(placed)
        val roster = roster()
        // Always 64 hex chars: pools made before 1.2.4 stored the key without leading zeros.
        direct(from, JSONObject().put("type", "welcome").put("pool_secret", s.poolSecret?.padStart(64, '0')).put("token", token)
            .put("seq", s.rosterSeq).also { if (terms.v6) it.put("terms", Protocol.termsHash(terms)) }, replyKey!!)
        creatorChannel(roster)
        announce()
        // A coin taking its seat back is nobody new.
        if (held.isEmpty()) env.event(Event.Joined(s.poolId, s.seats.size))
        // Full: ask everyone whether to close now (the creator's own seat counts as a yes).
        // Not when the seats are only this wallet's own and too few others: that vote could only fail.
        if (s.seats.size >= terms.maxPeers && people(s.seats) >= terms.minPeers) {
            if (terms.v6) sendVoteOpen(myTokenHash!!, randomHex(8))
            else if (terms.v3) creatorChannel(JSONObject().put("type", "vote_open").put("by", myTokenHash).put("vote_id", randomHex(8)))
            else channel(JSONObject().put("type", "close_request").put("token", s.token).put("vote_id", randomHex(8)))
        }
    }

    /**
     * Creator: the seats among [seats] (never our own) whose coins the chain says are spent. A
     * server out of reach proves nothing: those seats stay.
     */
    private fun spentSeats(seats: List<Protocol.Seat>): Set<String> {
        val others = seats.filter { it.tokenHash != myTokenHash }
        val coins = others.flatMap { st -> st.coins.map { st.tokenHash to it } }
        if (coins.isEmpty()) return emptySet()
        val unspent = runCatching { env.coinsUnspent(coins.map { it.second }) }.getOrNull() ?: return emptySet()
        if (unspent.size != coins.size) return emptySet()
        return coins.indices.filter { unspent[it] == false }.map { coins[it].first }.toSet()
    }

    /**
     * Creator: takes the seats [gone] out of the pool, as their own leave would: a new roster, the
     * listing, and a vote under way reopened (too few now) or closed (all the rest said yes).
     */
    private fun dropSeats(gone: Set<String>, why: String): Boolean {
        if (!s.seats.removeAll { it.tokenHash in gone }) return false
        env.log("seats out ($why): ${gone.size}")
        gone.forEach { s.accepts.remove(it) }; save()
        creatorChannel(roster()); announce()
        // v6: a vote is over the roster it was opened on; another roster ends it, never closes it with fewer.
        if (terms.v6) { if (s.phase == Phase.VOTING) sendReopen(if (why == "left") "withdrawn" else "roster_changed"); return true }
        if (s.phase == Phase.VOTING && people(s.seats) < terms.minPeers)
            creatorChannel(JSONObject().put("type", "reopen").put("vote_id", s.voteId))
        else if (s.phase == Phase.VOTING && s.seats.all { it.tokenHash in s.accepts })
            callClosing(s.seats.toList(), "agreed")
        return true
    }

    /**
     * Creator: a coin is checked unspent when it joins, but its owner may spend it afterwards (a
     * perfect coin made from it, a payment). Its seat would stay listed for good, and every v3
     * member checks the seats it is shown: each newcomer would find it fake and leave. Seats whose
     * coins are gone are taken out like a leave.
     */
    private fun evictSpent(): Boolean {
        lastSeatCheck = env.now()
        val gone = spentSeats(s.seats)
        return gone.isNotEmpty() && dropSeats(gone, "coin spent")
    }

    @Synchronized
    private fun callClosing(seats: List<Protocol.Seat>, why: String) {
        // Never a round of fewer people than the pool's minimum: this wallet's own seats are one person,
        // however many of them said yes (a coinjoin with itself mixes nothing).
        if (people(seats) < terms.minPeers) {
            env.log("not closing: ${seats.size} seats but only ${people(seats)} people")
            if (s.phase == Phase.VOTING) creatorChannel(JSONObject().put("type", "reopen").put("vote_id", s.voteId))
            return
        }
        // One closing per vote. Until it comes back from the relay we are still voting, and the last
        // yes and the vote's deadline (looked at every 15 s, with the coins checked over Tor in
        // between) used to close it again, each time with a new round id: the others kept the first
        // one, their requests for blind signatures no longer matched ours, and the round timed out.
        if (s.voteId != null && s.closedVote == s.voteId) return
        s.closedVote = s.voteId
        // Never a round with a coin that is already spent: it could not be broadcast.
        val spent = spentSeats(seats)
        if (spent.isNotEmpty()) {
            s.closedVote = null
            lastSeatCheck = env.now()
            if (!dropSeats(spent, "coin spent at closing") && s.phase == Phase.VOTING)
                creatorChannel(JSONObject().put("type", "reopen").put("vote_id", s.voteId))
            return
        }
        // v6: only the whole roster the vote was over, every seat of it having said yes.
        if (terms.v6 && (seats.map { it.tokenHash }.toSet() != s.seats.map { it.tokenHash }.toSet() || s.voteSeq != s.rosterSeq)) {
            env.log("not closing: the round is not the roster that was voted"); s.closedVote = null; return
        }
        s.round = seats.toMutableList()
        // v3: a fresh round id; blind signatures and outputs count for this round only.
        // v6: a seat's blind signatures are given once for the pool's whole life (a round called off and voted again
        // gets the same outputs back, never another set).
        if (terms.v3) { s.roundId = randomHex(8); if (!terms.v6) { s.blindSigs.clear(); s.blindedOf.clear() }; s.regOutputs.clear(); save() }
        val closing = JSONObject().put("type", "closing").put("why", why).put("seats", Protocol.seatsJson(seats))
            .put("deadline", if (terms.v6) capped(env.now() + terms.outputsSeconds) else env.now() + Protocol.OUTPUTS_SECONDS)
            .also { o -> s.roundId?.let { if (terms.v3) o.put("round_id", it) } }
            .also { o -> if (terms.v6) o.put("vote_id", s.voteId).put("n", s.voteN).put("seq", s.voteSeq) }
        val sent = creatorChannel(closing)
        if (sent && terms.v6) keepLast(closing)
        // Not taken by the relay: the next yes or look at the deadline tries again.
        if (!sent) s.closedVote = null
        save()
    }

    private fun creatorOnVote(tokenHash: String, voteId: String, accept: Boolean, at: Long = 0) {
        if (terms.v6) return creatorOnVoteV6(tokenHash, voteId, accept, at)
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
                // v6: a round called off goes back to the pool, so a member still collecting outputs takes the new roster.
                if (!fromCreator || s.phase !in setOf(Phase.OPEN, Phase.VOTING) && !(terms.v6 && s.phase == Phase.CLOSING)) return
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
                // A seat that can't be checked now (the server is out of reach) is checked again with
                // the next roster: it used to count as fake and end the round for us. One that is not
                // real gets the creator some minutes to take it out (its coin may have been spent
                // after it joined) before we leave; a round never closes with it (see "closing").
                if (v3 && s.phase != Phase.ABORTED && checkFakeSeats(seats)) return
                if (!s.creator && seats.size > before && before > 0) env.event(Event.Joined(s.poolId, seats.size))
                // v6: a vote over this roster that came first.
                pendingVoteOpen?.let { (vo, vat) -> if (vo.optInt("seq") <= s.rosterSeq) { pendingVoteOpen = null; openVoteV6(vo, vat) } }
            }
            "close_request" -> {
                if (v3) {
                    // Only the creator reads it: a member proves its seat with a MAC, never the token.
                    if (!s.creator || s.phase != Phase.OPEN || people(s.seats) < terms.minPeers) return
                    val th = o.getString("token_hash"); val id = o.getString("vote_id")
                    if (s.seats.none { it.tokenHash == th } || !Protocol.macOk(s.tokens[th], o.getString("mac"), "close", id)) return
                    // v6: a request replayed from the relay (after a restart) never opens its vote again.
                    if (terms.v6) { if (id !in s.usedVotes) sendVoteOpen(th, id); return }
                    creatorChannel(JSONObject().put("type", "vote_open").put("by", th).put("vote_id", id))
                    return
                }
                val token = o.getString("token")
                openVote(Protocol.tokenHash(token), o.getString("vote_id"), at)
            }
            "vote_open" -> if (terms.v6) { if (fromCreator) openVoteV6(o, at) } else if (v3 && fromCreator) openVote(o.getString("by"), o.getString("vote_id"), at)
            "vote" -> if (s.creator) {
                if (v3) {
                    val th = o.getString("token_hash"); val id = o.getString("vote_id"); val yes = o.getBoolean("accept")
                    if (!Protocol.macOk(s.tokens[th], o.getString("mac"), "vote", id, yes)) return
                    creatorOnVote(th, id, yes, at)
                } else {
                    val th = Protocol.tokenHash(o.getString("token"))
                    creatorOnVote(th, o.getString("vote_id"), o.getBoolean("accept"))
                }
            }
            "vote_ack" -> if (terms.v6 && fromCreator && !s.creator) onVoteAck(o)
            "state" -> if (terms.v6 && fromCreator && !s.creator) applyState(o)
            "sync" -> if (terms.v6 && s.creator) onSync(o)
            "reopen" -> {
                if (terms.v6) { if (fromCreator) onReopenV6(o); return }
                if (!fromCreator || s.phase != Phase.VOTING || o.optString("vote_id") != s.voteId) return
                s.phase = Phase.OPEN; s.voteId = null; s.accepts.clear(); save()
                env.event(Event.CloseRefused(s.poolId))
            }
            "closing" -> {
                // v3: the round closed again ("again": n) without seats that never asked for their blind
                // signatures; only a member still collecting outputs takes it, and only a newer one (replays).
                val again = o.optInt("again", 0)
                if (!fromCreator) return
                // v6: only the round of the vote under way, over the roster it was voted on, and only if we said yes to it
                // here: a closing we have no yes of our own for (history lost, or a creator that lies) never leads to signing.
                if (terms.v6 && !closingOkV6(o)) return
                // A creator before 1.3.9 could close the same vote again with a new round id, and goes on
                // with its latest: a later closing replaces ours while we wait for our blind signatures.
                val replaces = v3 && !s.creator && again == 0 && s.phase == Phase.CLOSING && s.closeSeq == 0 &&
                    s.outSigs.isEmpty() && at > s.closingAt && o.optString("round_id").let { it.isNotEmpty() && it != s.roundId }
                if (again == 0 && s.phase !in setOf(Phase.OPEN, Phase.VOTING) && !replaces) return
                if (again > 0 && (!v3 || s.creator || s.phase != Phase.CLOSING || again <= s.closeSeq)) return
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
                // Our own seats count as one person here: a round of us and one other is not a round of three.
                if (people(seats) < terms.minPeers) {
                    s.phase = Phase.ABORTED; s.reason = "the round closed with fewer than ${terms.minPeers} people"; save()
                    env.event(Event.Aborted(s.poolId, s.reason)); return
                }
                if (v3) {
                    val id = o.optString("round_id")
                    if (id.isEmpty() || (s.creator && id != s.roundId)) return
                    // Every seat of the round checked again now: a coin spent since it joined, or one
                    // never checked that can't be checked now, and we don't go on with this round.
                    if (!s.creator) seats.firstOrNull { st ->
                        val known = st.tokenHash in verifiedSeats
                        when (seatIsReal(st, fresh = true)) { true -> false; false -> true; null -> !known }
                    }?.let {
                        s.phase = Phase.ABORTED; s.reason = "the round closed with a seat whose coin is not real or could not be checked"; save()
                        env.event(Event.Aborted(s.poolId, s.reason)); return
                    }
                    s.roundId = id
                }
                if (again > 0) {
                    // Our outputs' blind signatures still hold (the same key signed them): they go in again, to this round.
                    s.closeSeq = again; s.outputs.clear(); s.handedIn.clear(); s.postedOutput = false
                    env.log("the round closed again without ${s.round.size - seats.size} seat(s) that never asked for their signature")
                }
                if (replaces) env.log("the creator closed the round again: round ${s.roundId} -> ${o.optString("round_id")}")
                s.round = seats.toMutableList(); s.phase = Phase.CLOSING; s.closingAt = at
                s.phaseDeadline = o.optLong("deadline", at + Protocol.OUTPUTS_SECONDS)
                save()
                // The public listing follows the round: "closing" now, "done" once it is out.
                if (s.creator) announce()
                if (s.creator) publishState(force = true)
                env.event(Event.Closing(s.poolId, people(seats)))
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
                val fresh = s.sigs[outpoint] != sig
                s.sigs[outpoint] = sig; save()
                if (fresh) publishState()
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
                dropSeats(setOf(th), "left")
            }
            // A round closed again takes an abort from its creator only: a seat left out of it (an older
            // app waking up after the first closing ran out) would otherwise end the round for the rest.
            "abort" -> if (!done && s.phase != Phase.BROADCAST && !(v3 && !fromCreator && s.closeSeq > 0)) {
                // v6: once we signed, nobody's word ends the round: a peer may still hold every signature.
                if (terms.v6 && signedAll()) { s.reason = o.optString("reason", "aborted"); save(); return }
                s.phase = Phase.ABORTED; s.reason = o.optString("reason", "aborted"); save()
                // A member's abort can reach the creator before its own deadline does: the listing must
                // still say the pool is over (it stayed "closing" until the pool bot gave up on it).
                if (s.creator) announce()
                env.event(Event.Aborted(s.poolId, s.reason))
            }
        }
    }

    /** A vote on closing now, asked by the seat [th]: v1/v2 from its close request, v3 from the creator. */
    private fun openVote(th: String, voteId: String, at: Long) {
        if (s.phase != Phase.OPEN || s.seats.size < terms.minPeers) return
        if (s.seats.none { it.tokenHash == th }) return
        // The creator does not call a vote that could only close a round of too few people (its own
        // seats are one): v1/v2 members, who heard the request too, are told to go on waiting.
        if (s.creator && people(s.seats) < terms.minPeers) {
            if (!terms.v3) channel(JSONObject().put("type", "reopen").put("vote_id", voteId))
            return
        }
        s.phase = Phase.VOTING; s.voteId = voteId; s.voteBy = th
        s.voteDeadline = at + Protocol.VOTE_SECONDS
        s.accepts.clear(); s.accepts.add(th)
        save()
        env.event(Event.CloseRequested(s.poolId, people(s.seats), th == myTokenHash))
        if (autoKeys != null && th != myTokenHash && fakeSince.isEmpty() && people(s.seats) >= terms.minPeers) {
            runCatching { vote(true) }.onSuccess { env.event(Event.DidAlone(s.poolId, Alone.ACCEPTED)) }
        }
        if (s.creator && s.seats.all { it.tokenHash in s.accepts }) callClosing(s.seats.toList(), "agreed")
    }

    private fun scriptOk(script: String) =
        script.matches(Regex(if (terms.script == com.kilombino.pyblockwatch.crypto.ScriptType.P2TR) "5120[0-9a-f]{64}" else "0014[0-9a-f]{40}"))

    /**
     * v3: a listed seat's coins are signed for its join key and unspent on the chain; null when
     * the chain can't be asked right now.
     */
    private fun seatIsReal(seat: Protocol.Seat, fresh: Boolean = false): Boolean? {
        if (seat.tokenHash == myTokenHash) return true
        if (!fresh && seat.tokenHash in verifiedSeats) return true
        val jp = seat.joinPub ?: return false
        if (seat.proofs.size != seat.coins.size) return false
        if (!seat.coins.indices.all { CoinjoinTx.checkOwnership(terms.id, jp, seat.coins[it], Hashes.hexToBytes(seat.proofs[it])) }) return false
        for (c in seat.coins) {
            val unspent = runCatching { env.coinUnspent(c) }.getOrElse { return null }
            if (!unspent) { verifiedSeats.remove(seat.tokenHash); return false }
        }
        verifiedSeats.add(seat.tokenHash)
        return true
    }

    /**
     * Member, v3: notes the listed seats that are not real and, once one has stayed listed for
     * [FAKE_GRACE], gives up our seat. A creator on this version takes a spent coin's seat out
     * within [SEAT_CHECK_SECONDS]; one that doesn't is not a pool to stay in. True when we left.
     */
    private fun checkFakeSeats(seats: List<Protocol.Seat>): Boolean {
        val now = env.now()
        lastSeatCheck = now
        fakeSince.keys.retainAll(seats.map { it.tokenHash }.toSet())
        for (st in seats) when (seatIsReal(st, fresh = st.tokenHash in fakeSince)) {
            false -> fakeSince.putIfAbsent(st.tokenHash, now)
            true -> fakeSince.remove(st.tokenHash)
            null -> {}
        }
        if (fakeSince.values.none { now - it >= FAKE_GRACE }) return false
        sendLeave()
        s.phase = Phase.ABORTED; s.reason = "the pool listed a seat whose coin is not real"; save()
        env.event(Event.Aborted(s.poolId, s.reason))
        return true
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
    private fun onOutputHandedIn(o: JSONObject, from: String) {
        if (o.optString("type") != "output" || s.phase != Phase.CLOSING || o.optString("round_id") != s.roundId) {
            if (terms.v6) env.log("output handed in for another round or stage: ignored")
            return
        }
        val script = o.getString("script")
        if (!scriptOk(script)) return
        val prefix = Hashes.hexToBytes(o.getString("prefix"))
        if (prefix.size != BlindRsa.PREFIX_LEN) return
        val pk = blindPk ?: return
        if (!BlindRsa.verify(pk, prefix + Hashes.hexToBytes(script), Hashes.hexToBytes(o.getString("sig")))) return
        s.regOutputs.add(script); save()
        // v6: kept on disk first, then acknowledged to the output's own fresh key (nothing in it points at a seat).
        if (terms.v6) creatorKey?.let { direct(from, JSONObject().put("type", "output_ack").put("round_id", s.roundId).put("script", script), it) }
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
        if (creatorChannel(o) && terms.v6) keepLast(o)
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
            val delay = OUTPUT_DELAY.first.toLong() + rnd.nextInt(OUTPUT_DELAY.last - OUTPUT_DELAY.first + 1)
            timer?.schedule({
                if (terms.v6) handInV6(i)
                else synchronized(this) {
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
        // A circuit of its own too, never the seat's: the relay can't tie the output to the seat by it.
        val r = RelayClient(Protocol.RELAY, { _, _ -> }, {}, "kj-out-" + randomHex(8))
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
        s.phase = Phase.SIGNING; s.phaseDeadline = if (terms.v6) capped(env.now() + terms.signSeconds) else env.now() + Protocol.SIGN_SECONDS; save()
        publishState(force = true)
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
        if (terms.v6) {
            // Kept before anything goes out: from here on our coins are reserved until the round resolves.
            s.seat.coins.forEachIndexed { i, c ->
                val mine = plan.coins.first { it.outpoint == c.outpoint }
                s.sigs[mine.outpoint] = CoinjoinTx.sign(plan, mine, privateKeys[i]).toHex()
            }
            s.signedAt = env.now(); save()
            // The relay's OK is the receipt; what it did not take goes again from the timer (and on a snapshot without it).
            postOwnSigs()
            maybeBroadcast(plan)
            return
        }
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
        sent(tx.txid)
    }

    /** The round's transaction went out from here: everyone is told. */
    private fun sent(txid: String) {
        s.txid = txid; s.phase = Phase.BROADCAST; s.phaseDeadline = env.now(); save()
        channel(JSONObject().put("type", "tx").put("txid", txid))
        announce()
        publishState(force = true)
        env.event(Event.Broadcast(s.poolId, txid))
    }

    /**
     * v3 creator, the outputs' time over: the seats of the round that never even asked for their blind
     * signatures (a phone asleep, an app closed) are taken out and the round closes again with the rest,
     * if they are still enough people. Nothing of theirs was ever handed in, so the outputs of the round
     * closed again tell the creator nothing new. A seat that got its signature but handed nothing in can't
     * be told apart (that is the point of blinding): then the round is cancelled, as before. Wallets
     * before 1.3.6 don't take the second closing and cancel at their own deadline. True when it closed again.
     */
    private fun closeAgain(): Boolean {
        if (s.closeSeq >= MAX_RECLOSES) return false
        val missing = s.round.filter { it.tokenHash != myTokenHash && !(terms.v5 && isCreatorSeat(it)) && it.tokenHash !in s.blindSigs }
        if (missing.isEmpty()) return false
        val gone = missing.map { it.tokenHash }.toSet()
        val rest = s.round.filter { it.tokenHash !in gone }
        if (rest.size < Protocol.MIN_PEERS || people(rest) < terms.minPeers) return false
        if (terms.v5 && rest.count { isCreatorSeat(it) } != 1) return false
        if (spentSeats(rest).isNotEmpty()) return false
        s.seats.removeAll { it.tokenHash in gone }; gone.forEach { s.accepts.remove(it) }
        s.closeSeq++
        s.round = rest.toMutableList(); s.roundId = randomHex(8); s.regOutputs.clear()
        // The blind signatures given stay: the same requests get the same answers in the new round.
        if (!terms.v5) s.mixScripts.forEach { s.regOutputs.add(it.toHex()) }
        s.postedOutput = true
        s.phaseDeadline = env.now() + Protocol.OUTPUTS_SECONDS
        save()
        env.log("closing again without ${gone.size} seat(s) that never asked for their signature")
        creatorChannel(JSONObject().put("type", "closing").put("why", "again").put("seats", Protocol.seatsJson(rest))
            .put("deadline", s.phaseDeadline).put("round_id", s.roundId).put("again", s.closeSeq))
        announce()
        env.event(Event.Closing(s.poolId, people(rest)))
        listOutputsIfAll()
        return true
    }

    private fun abort(reason: String) {
        if (done) return
        // v6: after signing, no deadline or failure frees our coins: only the round resolving does (see [resolveSigned]).
        if (terms.v6 && signedAll()) { s.reason = reason; save(); if (s.creator) publishState(force = true); return }
        s.phase = Phase.ABORTED; s.reason = reason; save()
        // v3: the creator's from its own key, which every v3 wallet takes as the creator's.
        if (s.creator && terms.v3) creatorChannel(JSONObject().put("type", "abort").put("reason", reason)
            .also { if (terms.v6) keepLast(it) })
        else channel(JSONObject().put("type", "abort").put("reason", reason))
        if (s.creator) announce()
        env.event(Event.Aborted(s.poolId, reason))
    }

    // ------------------------------------------------------------------------------- user actions

    /** Ask everyone to close the pool now (two or more people in). */
    @Synchronized
    fun requestClose() {
        check(s.phase == Phase.OPEN) { "the pool is not open" }
        check(s.seats.size >= terms.minPeers) { "at least ${terms.minPeers} people are needed" }
        check(people(s.seats) >= terms.minPeers) { OWN_SEATS_ONE.format(terms.minPeers) }
        val id = randomHex(8)
        when {
            !terms.v3 -> channel(JSONObject().put("type", "close_request").put("token", s.token).put("vote_id", id))
            s.creator && terms.v6 -> sendVoteOpen(myTokenHash!!, id)
            s.creator -> creatorChannel(JSONObject().put("type", "vote_open").put("by", myTokenHash).put("vote_id", id))
            else -> channel(JSONObject().put("type", "close_request").put("token_hash", myTokenHash).put("vote_id", id)
                .put("mac", Protocol.mac(s.token ?: return, "close", id)))
        }
    }

    @Synchronized
    fun vote(accept: Boolean) {
        val id = s.voteId ?: return
        if (s.phase != Phase.VOTING || s.votedOn == id) return
        // A yes to a round whose other seats are only ours: not a coinjoin with anyone.
        if (accept) check(people(s.seats) >= terms.minPeers) { OWN_SEATS_ONE.format(terms.minPeers) }
        if (terms.v3) channel(JSONObject().put("type", "vote").put("token_hash", myTokenHash).put("vote_id", id).put("accept", accept)
            .put("mac", Protocol.mac(s.token ?: return, "vote", id, accept)))
        else channel(JSONObject().put("type", "vote").put("token", s.token).put("vote_id", id).put("accept", accept))
        s.votedOn = id
        lastVoteSent = env.now()
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
        sendLeave()
        s.phase = Phase.ABORTED; s.reason = LEFT_BY_CHOICE; save()
    }

    /** Tells the creator we give up our seat, so the coin is free to join again. */
    private fun sendLeave() {
        if (terms.v3) channel(JSONObject().put("type", "leave").put("token_hash", myTokenHash ?: "")
            .put("mac", s.token?.let { Protocol.mac(it, "leave") } ?: ""))
        else channel(JSONObject().put("type", "leave").put("token", s.token ?: ""))
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
            // The seats' coins, again from time to time: the creator takes out the spent ones, a
            // member looks again at those it found fake (and leaves once their grace is over).
            if (s.phase in setOf(Phase.OPEN, Phase.VOTING) && now - lastSeatCheck >= SEAT_CHECK_SECONDS) {
                if (s.creator) evictSpent()
                else if (terms.v3 && fakeSince.isNotEmpty() && checkFakeSeats(s.seats)) return@runCatching
                else lastSeatCheck = now
            }
            // A creator that stopped re-announcing (every HEARTBEAT while open) is gone and will
            // not take the round anywhere: let it go instead of showing it until it expires.
            if (!s.creator && s.phase in setOf(Phase.JOINING, Phase.OPEN, Phase.VOTING) && listedAt > 0 &&
                now - listedAt > Protocol.STALE_AFTER) {
                s.phase = Phase.ABORTED
                s.reason = "the pool's creator stopped answering"
                save(); env.event(Event.Aborted(s.poolId, s.reason))
                return@runCatching
            }
            // The random wait of another seat (or of a perpetual coin's pool) is over: join, or open the pool.
            if (s.phase == Phase.JOINING && !joinSent && now >= s.joinAfter && now <= terms.expiresAt) {
                if (s.creator) creatorStart() else sendJoin()
                return@runCatching
            }
            // v6: its own stages (the open one is as before).
            if (terms.v6 && s.phase in setOf(Phase.VOTING, Phase.CLOSING, Phase.SIGNING, Phase.BROADCAST)) { tickV6(now); return@runCatching }
            when (s.phase) {
                Phase.OPEN, Phase.JOINING -> if (s.phase == Phase.OPEN && !s.warnedExpiry && now <= terms.expiresAt &&
                    terms.expiresAt - now <= EXPIRY_WARN_SECONDS && people(s.seats) >= terms.minPeers && s.seats.size < terms.maxPeers) {
                    // A last call: enough people to mix, and the pool is about to expire for nothing.
                    s.warnedExpiry = true; save()
                    if (autoKeys != null && runCatching { requestClose() }.isSuccess) env.event(Event.DidAlone(s.poolId, Alone.ASKED_TO_CLOSE))
                    else env.event(Event.ExpiringSoon(s.poolId, people(s.seats), ((terms.expiresAt - now) / 60).toInt().coerceAtLeast(1)))
                } else if (now > terms.expiresAt) {
                    if (s.creator) abort("the pool expired") else { s.phase = Phase.ABORTED; s.reason = "the pool expired"; save(); env.event(Event.Aborted(s.poolId, s.reason)) }
                } else if (s.phase == Phase.JOINING && !s.creator && now - maxOf(s.created, s.joinAfter) > JOIN_TIMEOUT) {
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
                    // Our own seats saying yes are one person, however many they are.
                    if (people(yes) >= terms.minPeers) callClosing(yes, "agreed")
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
                Phase.CLOSING -> if (terms.v3 && s.creator && now > s.phaseDeadline + RECLOSE_AFTER && closeAgain()) {}
                else if (now > s.phaseDeadline + 60) abort("not everyone sent their output in time")
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


    // ------------------------------------------------------------------------------- v6: asynchronous rounds

    /** v6: a deadline no later than the pool's final time. */
    private fun capped(t: Long): Long = minOf(t, terms.async?.final ?: t)

    /** v6: a whole round (a vote, the outputs, the signing, each its full window) still fits before `final` from [now]. */
    private fun roundFits(now: Long): Boolean = now + terms.voteSeconds + terms.outputsSeconds + terms.signSeconds <= terms.async!!.final

    /** v6: every one of our inputs is signed: our coins are reserved until the round resolves. */
    private fun signedAll(): Boolean = s.seat.coins.isNotEmpty() && s.seat.coins.all { it.outpoint in s.sigs }

    /** Our coins held by a v6 round we signed whose time is over: the transaction may still go out. */
    val reserved: Boolean get() = terms.v6 && s.phase == Phase.SIGNING && signedAll() && env.now() > s.phaseDeadline

    /** Every one of our inputs is signed (v6: kept before it is sent, so this can be true while the relay has not taken it yet). */
    val signed: Boolean get() = signedAll()

    /** v6 creator: the newest message of its type, for the snapshot. */
    private fun keepLast(o: JSONObject) {
        if (!terms.v6 || !s.creator) return
        s.lastMsgs[o.getString("type")] = JSONObject().put("at", env.now()).put("msg", o).toString()
    }

    /** v6 creator: opens vote [id] asked by seat [by], numbered, over the current roster, and applies it here at once. */
    private fun sendVoteOpen(by: String, id: String) {
        if (id in s.usedVotes) return
        val n = ++s.votesSent
        s.usedVotes += id
        val o = JSONObject().put("type", "vote_open").put("by", by).put("vote_id", id).put("n", n).put("seq", s.rosterSeq)
            .put("deadline", capped(env.now() + terms.voteSeconds))
        save()
        if (!creatorChannel(o)) { env.log("vote_open not taken by the relay"); return }
        keepLast(o)
        openVoteV6(o, env.now())
        publishState(force = true)
    }

    /**
     * v6: a vote over roster `seq`, numbered `n`. Votes are applied in their number's order whatever order the
     * relay gives them in; one that comes before its roster waits for it; one over an older roster is stale.
     */
    private fun openVoteV6(o: JSONObject, at: Long) {
        val n = o.optInt("n"); val seq = o.optInt("seq"); val id = o.getString("vote_id"); val th = o.getString("by")
        if (n <= s.voteN || n <= s.voteEndedN) return
        if (s.phase !in setOf(Phase.OPEN, Phase.VOTING, Phase.CLOSING)) return
        if (!s.creator && seq > s.rosterSeq) { pendingVoteOpen = o to at; return }
        if (seq != s.rosterSeq) return
        if (s.seats.none { it.tokenHash == th } || s.seats.size < terms.minPeers) return
        if (s.creator && people(s.seats) < terms.minPeers) return
        // A round called off for a new vote: nothing of it was signed. Our blind signatures stay (one set per seat).
        if (s.phase == Phase.CLOSING) { if (signedAll()) return; clearRound() }
        val wasVoting = s.phase == Phase.VOTING
        s.phase = Phase.VOTING; s.voteId = id; s.voteBy = th; s.voteN = n; s.voteSeq = seq
        s.voteDeadline = minOf(o.optLong("deadline", env.now() + terms.voteSeconds), env.now() + terms.voteSeconds, terms.async!!.final)
        s.accepts.clear(); s.accepts.add(th)
        // Whoever asked has said yes: the creator's vote_open is its record of that.
        if (th == myTokenHash) { s.votedOn = id; s.voteAcked = id }
        save()
        if (wasVoting) env.log("vote $n replaces the one before")
        env.event(Event.CloseRequested(s.poolId, people(s.seats), th == myTokenHash))
        if (autoKeys != null && th != myTokenHash && fakeSince.isEmpty() && people(s.seats) >= terms.minPeers) {
            runCatching { vote(true) }.onSuccess { env.event(Event.DidAlone(s.poolId, Alone.ACCEPTED)) }
        }
        if (s.creator && s.seats.all { it.tokenHash in s.accepts }) callClosing(s.seats.toList(), "agreed")
    }

    /** v6 creator: ends the vote under way (or its round, before anyone signed) with an explicit [outcome]; applied here at once. */
    private fun sendReopen(outcome: String) {
        val id = s.voteId ?: return
        val o = JSONObject().put("type", "reopen").put("vote_id", id).put("n", s.voteN).put("outcome", outcome)
        if (s.phase == Phase.CLOSING) s.roundId?.let { o.put("round_id", it) }
        if (!creatorChannel(o)) env.log("reopen not taken by the relay")
        keepLast(o)
        onReopenV6(o)
        publishState(force = true)
    }

    private fun onReopenV6(o: JSONObject) {
        val n = o.optInt("n")
        if (n < s.voteN) return
        if (n > s.voteEndedN) { s.voteEndedN = n; save() }
        if (o.optString("vote_id") != s.voteId) return
        val ok = s.phase == Phase.VOTING || (s.phase == Phase.CLOSING && o.optString("round_id") == s.roundId && !signedAll())
        if (!ok) return
        if (s.phase == Phase.CLOSING) clearRound()
        s.phase = Phase.OPEN; s.voteId = null; s.accepts.clear()
        s.reason = when (o.optString("outcome")) {
            "declined" -> "someone said not yet"
            "withdrawn" -> "someone left the pool"
            "timeout" -> "not everyone answered in time"
            "outputs_timeout" -> "not everyone handed in their outputs in time"
            "roster_changed" -> "the people in the pool changed"
            else -> ""
        }
        save()
        env.event(Event.CloseRefused(s.poolId))
    }

    /** Back from a round called off before anyone signed: the pool's state, ready for another vote. Our blinding stays. */
    private fun clearRound() {
        s.round.clear(); s.outputs.clear(); s.handedIn.clear(); s.postedOutput = false; s.regOutputs.clear()
        s.payOutputs.clear(); s.roundId = null; s.phaseDeadline = 0; s.closedVote = null
        outputScheduled.clear()
    }

    /** v6: a closing is taken only for the vote under way, over its roster as voted, with our own yes on record. */
    private fun closingOkV6(o: JSONObject): Boolean {
        val n = o.optInt("n"); val id = o.optString("vote_id")
        if (s.phase != Phase.VOTING || n != s.voteN || n <= s.voteEndedN || id != s.voteId || o.optInt("seq") != s.voteSeq) return false
        if (s.rosterSeq != s.voteSeq) return false
        val seats = runCatching { Protocol.parseSeats(o.getJSONArray("seats"), terms.script) }.getOrNull() ?: return false
        if (seats.map { it.tokenHash }.toSet() != s.seats.map { it.tokenHash }.toSet()) {
            env.log("a closing that is not the roster voted: ignored"); return false
        }
        if (!s.creator && s.votedOn != id) { env.log("a closing for a vote we never said yes to: ignored"); return false }
        return true
    }

    /** v6 creator: one answer per seat and vote, kept on disk before it is acknowledged; a repeat is acknowledged again, never counted. */
    private fun creatorOnVoteV6(th: String, voteId: String, accept: Boolean, at: Long) {
        fun ack(status: String) {
            // A replay of old votes (after a restart) is not answered; every vote of now is, a repeat too: a member
            // repeats its vote only while it has no acknowledgment, every [RESEND_SECONDS].
            if (status != "recorded" && at > 0 && env.now() - at > 600) return
            creatorChannel(JSONObject().put("type", "vote_ack").put("vote_id", voteId).put("token_hash", th).put("accept", accept)
                .put("status", status).put("n", if (voteId == s.voteId) s.voteN else 0)
                .put("yes", s.accepts.count { a -> s.seats.any { it.tokenHash == a } }).put("seats", s.seats.size))
        }
        if (s.seats.none { it.tokenHash == th }) return ack("not_in_roster")
        if (voteId != s.voteId) return ack("superseded")
        if (s.phase != Phase.VOTING) return ack(if (s.closedVote == voteId) "closed" else "superseded")
        if (!accept) {
            // A no is that seat leaving: recorded, acknowledged, and the vote ends with it (no smaller round from it).
            if (th != myTokenHash) {
                s.seats.removeAll { it.tokenHash == th }; s.accepts.remove(th); save()
                ack("recorded")
                creatorChannel(roster()); announce()
            } else ack("recorded")
            sendReopen("declined")
            return
        }
        if (th in s.accepts) return ack("recorded")
        s.accepts.add(th); save()
        ack("recorded")
        if (s.seats.all { it.tokenHash in s.accepts }) callClosing(s.seats.toList(), "agreed")
        else publishState()
    }

    /** v6 member: the creator recorded (or not) our answer. */
    private fun onVoteAck(o: JSONObject) {
        if (o.optString("token_hash") != myTokenHash || o.optString("vote_id") != s.votedOn) return
        if (o.optString("status") == "recorded" && s.voteAcked != s.votedOn) {
            s.voteAcked = s.votedOn; save()
            env.log("our vote is recorded (${o.optInt("yes")}/${o.optInt("seats")} yes)")
        }
    }

    /** v6 member: asks the creator for a snapshot (after reconnecting, or when a stage seems stuck). */
    private fun sendSync() {
        val th = myTokenHash ?: return; val token = s.token ?: return
        val now = env.now()
        if (now - lastSync < 20) return
        lastSync = now
        channel(JSONObject().put("type", "sync").put("token_hash", th).put("at", now).put("mac", Protocol.mac(token, "sync", now)))
    }

    /** v6 creator: a member asks where the round stands. Only a seat's fresh request is answered (a replay is not). */
    private fun onSync(o: JSONObject) {
        val th = o.optString("token_hash"); val at = o.optLong("at")
        if (!Protocol.macOk(s.tokens[th], o.optString("mac"), "sync", at)) return
        if (Math.abs(env.now() - at) > 600) return
        publishState()
    }

    /**
     * v6 creator: the snapshot. Where the pool stands, with counts only for the outputs (never which seat handed
     * in what), and the newest message of each type that runs it, for a member that lost them to replay through
     * its usual checks. Rate-limited unless [force].
     */
    private fun publishState(force: Boolean = false) {
        if (!terms.v6 || !s.creator) return
        val now = env.now()
        if (!force && now - lastStatePub < 10) return
        lastStatePub = now
        val o = JSONObject().put("type", "state").put("n", ++s.stateN).put("at", now)
            .put("phase", s.phase.name.lowercase()).put("final", terms.async!!.final).put("roster_seq", s.rosterSeq)
            .put("reason", s.reason)
        if (s.phase == Phase.VOTING) o.put("vote", JSONObject().put("vote_id", s.voteId).put("n", s.voteN).put("seq", s.voteSeq)
            .put("deadline", s.voteDeadline).put("seats", s.seats.size)
            .put("accepted", org.json.JSONArray(s.accepts.filter { a -> s.seats.any { it.tokenHash == a } })))
        if (s.phase == Phase.CLOSING) o.put("round", JSONObject().put("round_id", s.roundId).put("deadline", s.phaseDeadline)
            .put("seats", s.round.size).put("blind_given", s.round.count { it.tokenHash in s.blindSigs })
            .put("outputs_in", s.regOutputs.size).put("outputs_wanted", handingIn * perPerson))
        if (s.phase in setOf(Phase.SIGNING, Phase.BROADCAST, Phase.CONFIRMED))
            o.put("sign", JSONObject().put("deadline", s.phaseDeadline).put("sigs", JSONObject(s.sigs as Map<*, *>)))
        s.txid?.let { o.put("txid", it) }
        o.put("replay", org.json.JSONArray(listOf("roster", "vote_open", "closing", "outputs", "reopen", "abort")
            .mapNotNull { s.lastMsgs[it]?.let { j -> JSONObject(j) } }))
        save()
        creatorChannel(o)
    }

    /** v6 member: a snapshot from the creator, newer than the last one: everything in it goes through the usual handlers. */
    private fun applyState(o: JSONObject) {
        val n = o.optInt("n")
        if (n <= s.stateN) return
        s.stateN = n; save()
        o.optJSONArray("replay")?.let { a -> for (i in 0 until a.length()) {
            val e = a.getJSONObject(i)
            runCatching { onChannel(e.getJSONObject("msg"), e.optLong("at"), fromCreator = true) }
        } }
        o.optJSONObject("vote")?.let { v ->
            val acc = v.optJSONArray("accepted")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            if (v.optString("vote_id") == s.votedOn && myTokenHash in acc && s.voteAcked != s.votedOn) { s.voteAcked = s.votedOn; save() }
        }
        o.optJSONObject("sign")?.optJSONObject("sigs")?.let { sg ->
            for (k in sg.keys()) runCatching { onChannel(JSONObject().put("type", "sig").put("outpoint", k).put("sig", sg.getString(k)), 0, true) }
            // Ours missing from it: the relay lost it; it goes again.
            if (signedAll() && s.seat.coins.any { !sg.has(it.outpoint) }) { s.postedSig = false; lastSigSent = 0 }
        }
        o.optString("txid").takeIf { it.isNotEmpty() }?.let { runCatching { onChannel(JSONObject().put("type", "tx").put("txid", it), 0, true) } }
    }

    /** v6: our signatures on the channel; [State.postedSig] once the relay took every one. */
    private fun postOwnSigs() {
        lastSigSent = env.now()
        var all = true
        for (c in s.seat.coins) {
            val sig = s.sigs[c.outpoint] ?: continue
            if (!channel(JSONObject().put("type", "sig").put("outpoint", c.outpoint).put("sig", sig))) all = false
        }
        s.postedSig = all; save()
    }

    /**
     * v6: our output [i] handed in as in v3 (a fresh key, a connection of its own), and kept going until the
     * creator acknowledges it to that fresh key: a resend is the same output again, which the creator counts once.
     */
    private fun handInV6(i: Int) {
        // What to hand in, read under the session's lock; the wait for the acknowledgment happens outside it,
        // so the session goes on reading the channel meanwhile.
        val job = synchronized(this) {
            if (s.phase != Phase.CLOSING || s.postedOutput || i in s.handedIn) { outputScheduled -= i; return }
            val sig = s.outSigs.getOrNull(i); val round = s.roundId; val prefix = s.blindPrefixes.getOrNull(i)
            if (sig == null || round == null || prefix == null) { outputScheduled -= i; return }
            listOf(round, s.mixScripts[i].toHex(), prefix, sig)
        }
        val ok = handInOutputV6(job[0], job[1], job[2], job[3])
        synchronized(this) {
            outputScheduled -= i
            if (ok && s.phase == Phase.CLOSING && s.roundId == job[0] && i !in s.handedIn) {
                s.handedIn += i
                if (s.handedIn.size == s.mixScripts.size) s.postedOutput = true
                save()
            }
        }
    }

    private fun handInOutputV6(round: String, script: String, prefix: String, sig: String): Boolean {
        val fresh = NostrEvent.newSecret()
        val freshPub = NostrEvent.pubOf(fresh)
        val o = JSONObject().put("type", "output").put("round_id", round).put("script", script)
            .put("prefix", prefix).put("sig", sig)
        val conv = Nip44.conversationKey(fresh, Hashes.hexToBytes(terms.creatorPub!!))
        val ev = NostrEvent.sign(fresh, Protocol.KIND_MSG, listOf(listOf("p", terms.creatorPub!!)), Nip44.encrypt(o.toString(), conv))
        val acked = java.util.concurrent.CompletableFuture<Boolean>()
        val r = RelayClient(Protocol.RELAY, { _, e ->
            if (e.pubkey != terms.creatorPub || e.tag("p") != freshPub) return@RelayClient
            val m = Nip44.decrypt(e.content, conv)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@RelayClient
            if (m.optString("type") == "output_ack" && m.optString("round_id") == round && m.optString("script") == script) acked.complete(true)
        }, {}, "kj-out-" + randomHex(8))
        return try {
            r.connect()
            r.subscribe("ack", listOf(JSONObject().put("kinds", org.json.JSONArray().put(Protocol.KIND_MSG))
                .put("#p", org.json.JSONArray().put(freshPub)).put("authors", org.json.JSONArray().put(terms.creatorPub))), waitEose = false)
            val res = r.publish(ev)
            env.log("hand in output -> ${res.first} ${res.second}")
            res.first && runCatching { acked.get(OUTPUT_ACK_WAIT, TimeUnit.SECONDS) }.getOrDefault(false)
                .also { env.log(if (it) "output acknowledged" else "output not acknowledged yet") }
        } catch (e: Exception) { env.log("hand in output failed: ${e.message}"); false }
        finally { runCatching { r.close() } }
    }

    /**
     * v6, a round we signed past its time: it resolves only on the chain. The transaction seen (by anyone's
     * broadcast) is the round going on; a coin of it spent by anything else, [DEPTH] blocks deep, means it never
     * can, and frees ours. A spend less deep may still be replaced or reorged away: the round stays reserved, and
     * whoever holds every signature keeps sending it.
     */
    private fun resolveSigned(now: Long) {
        if (now - lastResolve < RESOLVE_EVERY) return
        lastResolve = now
        val plan = buildPlan() ?: return
        val txid = CoinjoinTx.txid(plan)
        val c = runCatching { env.confirmations(txid) }.getOrNull()
        if (c != null) {
            s.txid = txid; s.phase = if (c >= DEPTH) Phase.CONFIRMED else Phase.BROADCAST; if (s.sentAt == 0L) s.sentAt = now
            s.confs = c; s.phaseDeadline = now; save()
            env.event(Event.Broadcast(s.poolId, txid))
            if (c >= DEPTH) env.event(Event.Confirmed(s.poolId, txid))
            return
        }
        if (conflictBuried(plan, txid)) {
            s.phase = Phase.ABORTED
            s.reason = "a coin of the round was spent elsewhere: its transaction can never confirm, and your coins are free"
            save(); env.event(Event.Aborted(s.poolId, s.reason)); stop()
        } else if (s.sigs.size == plan.coins.size &&
            runCatching { env.broadcast(CoinjoinTx.assemble(plan, s.sigs.mapValues { Hashes.hexToBytes(it.value) }).rawHex) }.isSuccess)
            sent(txid)
    }

    /**
     * v6: a coin of the round spent by another transaction at least [DEPTH] blocks deep. Only then can the round
     * never confirm: a spend in the mempool, or in a block or two, may still be replaced, evicted or reorged away.
     */
    private fun conflictBuried(plan: CoinjoinTx.Plan, txid: String): Boolean {
        val unspent = runCatching { env.coinsUnspent(plan.coins) }.getOrNull() ?: return false
        if (unspent.size != plan.coins.size) return false
        return plan.coins.indices.any { i ->
            unspent[i] == false && (runCatching { env.conflictDepth(plan.coins[i], txid) }.getOrNull() ?: -1) >= DEPTH
        }
    }

    private fun tickV6(now: Long) {
        val final = terms.async!!.final
        when (s.phase) {
            Phase.VOTING -> when {
                now > final -> if (s.creator) abort("the pool reached its final time") else localEnd("the pool reached its final time")
                !s.creator -> {
                    // Our answer not recorded yet: the same answer again (the creator counts it once).
                    if (s.votedOn == s.voteId && s.voteAcked != s.voteId && now - lastVoteSent >= RESEND_SECONDS) resendVote()
                    if (now > s.voteDeadline + 60 && now - lastSync >= SYNC_EVERY) sendSync()
                }
                now > s.voteDeadline -> {
                    // Time over without every yes. Never a smaller round from it: the silent seats leave the roster,
                    // and the rest are asked again over the new one (each answers anew).
                    val silent = s.seats.filter { it.tokenHash !in s.accepts && it.tokenHash != myTokenHash }.map { it.tokenHash }.toSet()
                    val weSaidYes = myTokenHash in s.accepts
                    if (silent.isNotEmpty()) {
                        s.seats.removeAll { it.tokenHash in silent }; silent.forEach { s.accepts.remove(it) }; save()
                        creatorChannel(roster()); announce()
                    }
                    sendReopen("timeout")
                    if (weSaidYes && silent.isNotEmpty() && people(s.seats) >= terms.minPeers && s.revotes < MAX_REVOTES &&
                        roundFits(now)) {
                        s.revotes++; save()
                        sendVoteOpen(myTokenHash!!, randomHex(8))
                    }
                }
            }
            Phase.CLOSING -> when {
                now > final -> if (s.creator) abort("the pool reached its final time") else localEnd("the pool reached its final time")
                s.creator && now > s.phaseDeadline + RECLOSE_AFTER -> {
                    // Seats that never even asked for their blind signatures leave the roster and the rest vote again
                    // (never a smaller round without asking). Otherwise someone got signatures and handed nothing in.
                    val missing = s.round.filter { it.tokenHash != myTokenHash && !(terms.v5 && isCreatorSeat(it)) && it.tokenHash !in s.blindSigs }
                        .map { it.tokenHash }.toSet()
                    if (missing.isEmpty()) abort("not everyone sent their output in time")
                    else {
                        s.seats.removeAll { it.tokenHash in missing }; missing.forEach { s.accepts.remove(it) }; save()
                        creatorChannel(roster()); announce()
                        sendReopen("outputs_timeout")
                        if (people(s.seats) >= terms.minPeers && s.revotes < MAX_REVOTES && roundFits(now) &&
                            (!terms.v5 || s.seats.count { isCreatorSeat(it) } == 1)) {
                            s.revotes++; save(); sendVoteOpen(myTokenHash!!, randomHex(8))
                        }
                    }
                }
                s.creator -> listOutputsIfAll()
                // The creator calls a round off (or a new vote) shortly after the deadline; a member waits for that a while.
                now > s.phaseDeadline + MEMBER_GRACE -> localEnd("not everyone sent their output in time")
                else -> {
                    if (now > s.phaseDeadline && now - lastSync >= SYNC_EVERY) sendSync()
                    if (s.outSigs.isEmpty() && now - lastBlindSent > 30) sendBlind()
                    else if (s.outSigs.isNotEmpty() && !s.postedOutput) scheduleOutput()
                }
            }
            Phase.SIGNING -> {
                // Our signatures the relay did not take go again.
                if (signedAll() && !s.postedSig && now - lastSigSent >= RESEND_SECONDS) postOwnSigs()
                // Reserved: the same signatures again every day, and the creator's snapshot with all it holds, so a
                // relay that keeps events a week still has them for whoever comes back to complete the round.
                if (signedAll() && s.postedSig && now - lastSigSent >= REPOST_EVERY) postOwnSigs()
                if (s.creator && signedAll() && now - lastStatePub >= REPOST_EVERY) publishState(force = true)
                if (now > s.phaseDeadline + 60 || now > final) {
                    if (!signedAll()) {
                        // We never signed: nothing of ours can go out, the round is over for us.
                        if (s.creator) abort("not everyone signed in time") else localEnd("not everyone signed in time")
                    } else {
                        if (s.reason.isEmpty()) { s.reason = "signed, waiting: the others' signatures, or a coin moving"; save() }
                        resolveSigned(now)
                    }
                } else if (now - lastSync >= SYNC_EVERY && !s.creator && signedAll()) sendSync()
            }
            Phase.BROADCAST -> s.txid?.let { txid ->
                val c = runCatching { env.confirmations(txid) }.getOrNull()
                if (c != null && c != s.confs) { s.confs = c; save() }
                // Confirmed only [DEPTH] blocks deep: a block or two can still be reorged away.
                if (c != null && c >= DEPTH) { s.phase = Phase.CONFIRMED; save(); env.event(Event.Confirmed(s.poolId, txid)); stop() }
                else if (c == null && now - lastResolve >= RESOLVE_EVERY) {
                    // Not seen (never, or no more after a reorg): either it fell out of mempools and blocks (it goes again,
                    // we hold every signature) or a coin of it was spent elsewhere, [DEPTH] deep (then it never confirms,
                    // and our coins are free). Time alone decides nothing.
                    lastResolve = now
                    val plan = buildPlan() ?: return@let
                    if (conflictBuried(plan, txid)) {
                        s.phase = Phase.ABORTED; s.reason = "the transaction never appeared: a coin of the round was spent elsewhere"
                        save(); env.event(Event.Aborted(s.poolId, s.reason)); stop()
                    } else if (s.sigs.size == plan.coins.size)
                        runCatching { env.broadcast(CoinjoinTx.assemble(plan, s.sigs.mapValues { Hashes.hexToBytes(it.value) }).rawHex) }
                }
            }
            else -> {}
        }
    }

    /** v6 member: the round is over for us here (nothing signed); the others are not told (the creator calls it). */
    private fun localEnd(reason: String) {
        if (done || signedAll()) return
        s.phase = Phase.ABORTED; s.reason = reason; save(); env.event(Event.Aborted(s.poolId, reason))
    }

    /** v6 member: our yes again, the same message (the creator counts a seat once per vote). */
    private fun resendVote() {
        val id = s.voteId ?: return
        lastVoteSent = env.now()
        channel(JSONObject().put("type", "vote").put("token_hash", myTokenHash).put("vote_id", id).put("accept", true)
            .put("mac", Protocol.mac(s.token ?: return, "vote", id, true)))
    }

    private fun randomHex(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }.toHex()

    companion object {
        /** Reason of a round this wallet left on purpose: its card goes away by itself. */
        const val LEFT_BY_CHOICE = "you left this round"
        /** Seconds past the outputs' deadline when a v3 creator closes the round again without the silent seats (members wait 60). */
        @JvmField var RECLOSE_AFTER = 15L
        /** Times a round may close again so. */
        const val MAX_RECLOSES = 2
        /** Why this wallet won't ask to close or say yes: too few people once its own seats count as one. */
        const val OWN_SEATS_ONE = "at least %d different people are needed: your own seats count as one"

        /**
         * People among the seats [tokenHashes]: all but one of this wallet's own seats ([own]) are left
         * out, so a wallet with two seats is one person. The roster itself can't tell people apart.
         */
        fun distinctPeople(tokenHashes: Collection<String>, own: Set<String>): Int {
            val mine = tokenHashes.count { it in own }
            return tokenHashes.size - maxOf(0, mine - 1)
        }
        /** Seconds a broadcast transaction may stay unseen before the round is given up. */
        const val BROADCAST_GIVE_UP = 12 * 3600L
        /** Seconds a join request waits for the creator's answer. */
        const val JOIN_TIMEOUT = 5 * 60
        /** Seconds past a vote's deadline the others wait for the creator to close or reopen. */
        const val VOTE_GRACE = 3 * 60L
        /** Seconds before expiry when a pool that could close is called out. */
        const val EXPIRY_WARN_SECONDS = 3600
        /** Seconds between checks of the seats' coins while a pool is open (tests make it shorter). */
        @JvmField var SEAT_CHECK_SECONDS = 120L
        /** Seconds a member stays in a pool that lists a seat whose coin is not real. */
        @JvmField var FAKE_GRACE = 600L

        // v6 (asynchronous rounds); tests make them shorter.
        /** Seconds before an unacknowledged vote or signature is sent again. */
        @JvmField var RESEND_SECONDS = 30L
        /** Seconds between a member's requests for a snapshot while a stage looks stuck. */
        @JvmField var SYNC_EVERY = 120L
        /** Seconds a hand-in waits for the creator's acknowledgment before trying again later. */
        @JvmField var OUTPUT_ACK_WAIT = 20L
        /** Seconds between looks at the chain for a signed round past its time. */
        @JvmField var RESOLVE_EVERY = 120L
        /** Seconds past the outputs' deadline a member waits for the creator to call the round off or vote again. */
        @JvmField var MEMBER_GRACE = 600L
        /** New votes a creator opens by itself after one timed out with silent seats. */
        const val MAX_REVOTES = 5
        /**
         * v6: blocks deep the round's transaction is before the round counts as confirmed, and another spend of one
         * of its coins before the round is given up (and the coins it reserved are free).
         */
        const val DEPTH = 6
        /** v6: seconds between the reposts of a reserved round's signatures (and its creator's snapshot). */
        @JvmField var REPOST_EVERY = 86_400L
        /** Seconds of the random wait before each output is handed in (3 to 43 s). */
        @JvmField var OUTPUT_DELAY = 3..42
        /** Seconds between the session's looks at its deadlines. */
        @JvmField var TICK_SECONDS = 15L

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
            /** Another seat of this phone in the pool ([State.seatId]), waiting until [joinAfter] to join. */
            seatId: String = "", joinAfter: Long = 0, perpId: String? = null,
            /** A private pool's key from its password, in place of [password] (the creator taking another seat keeps only it). */
            passwordKey: ByteArray? = null,
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
                    Protocol.passwordProof(password?.let { Protocol.passwordKey(terms.id, it) } ?: passwordKey
                        ?: error("this pool needs its password"), joinPub) else null,
                creatorSecret = if (creator) creatorSecret else null, blindPriv = if (creator) blindPriv else null,
                seatId = seatId, joinAfter = joinAfter, perpId = perpId,
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
                    payments: List<Long> = emptyList(),
                    /**
                     * v6: an asynchronous pool with these windows (seconds; null for each = the longest) and its final
                     * time [asyncFinal] (null: room for two votes, two output stages and the signing after it closes).
                     */
                    async: Boolean = false, asyncVote: Long? = null, asyncOutputs: Long? = null, asyncSign: Long? = null,
                    asyncFinal: Long? = null): NewPool {
            val k = NostrEvent.newSecret()
            val id = ByteArray(8).also { SecureRandom().nextBytes(it) }.toHex()
            val now = NostrEvent.clock()
            require(minPeers in Protocol.MIN_PEERS..maxPeers) { "the minimum must be between 2 and the maximum" }
            require(maxInputs in 1..Protocol.MAX_INPUTS) { "1 to ${Protocol.MAX_INPUTS} coins per person" }
            val v1 = script == com.kilombino.pyblockwatch.crypto.ScriptType.P2WPKH && maxInputs == 1
            require(outputs in 1..Protocol.MAX_OUTPUTS) { "1 to ${Protocol.MAX_OUTPUTS} mixed outputs per person" }
            require(outputs == 1 || v3) { "several outputs each need a v4 pool" }
            require(payments.isEmpty() || (v3 && Protocol.paymentsOk(payments, amount, outputs))) {
                "the payments must add up to what each joiner gets"
            }
            require(!async || v3) { "an asynchronous pool is a v6 pool, with blind outputs" }
            if (v3) {
                val ck = NostrEvent.newSecret()
                val blind = BlindRsa.generate()
                val timeout = now + hours * 3600L
                val timing = if (!async) null else {
                    val l = Protocol.Async.longest(timeout)
                    val vote = asyncVote ?: l.vote; val outs = asyncOutputs ?: l.outputs; val sign = asyncSign ?: l.sign
                    Protocol.Async(vote, outs, sign, asyncFinal ?: minOf(timeout + 2 * vote + 2 * outs + sign, timeout + Protocol.ASYNC_TAIL_MAX))
                        .also { require(it.valid(timeout)) { "vote 10–60 min, outputs 5–30 min, signing 20–120 min, and the final time within a day after the pool's" } }
                }
                return NewPool(Protocol.Terms(id, NostrEvent.pubOf(k), amount, feeRate, maxPeers, timeout, "open", 1, now,
                    // One output each stays v3, which every v3 wallet can join; more need v4; asynchronous rounds v6.
                    minPeers, private, if (async) Protocol.V6 else if (payments.isNotEmpty()) Protocol.V5 else if (outputs > 1) Protocol.V4 else Protocol.V3,
                    script, maxInputs, NostrEvent.pubOf(ck), blind.public.encode(), outputs, payments, timing),
                    k.toString(16).padStart(64, '0'), ck.toString(16).padStart(64, '0'), blind.encodePrivate())
            }
            return NewPool(Protocol.Terms(id, NostrEvent.pubOf(k), amount, feeRate, maxPeers, now + hours * 3600L, "open", 1, now,
                minPeers, private, if (v1) Protocol.V1 else Protocol.VERSION, script, maxInputs), k.toString(16).padStart(64, '0'))
        }
    }
}
