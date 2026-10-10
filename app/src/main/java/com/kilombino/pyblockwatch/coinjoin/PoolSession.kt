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

    sealed class Event(val pool: String) {
        class Joined(pool: String, val peers: Int) : Event(pool)
        class Welcomed(pool: String) : Event(pool)
        class Rejected(pool: String, val reason: String) : Event(pool)
        class CloseRequested(pool: String, val peers: Int, val byMe: Boolean) : Event(pool)
        class CloseRefused(pool: String) : Event(pool)
        class Closing(pool: String, val peers: Int) : Event(pool)
        class SignNeeded(pool: String, val peers: Int) : Event(pool)
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
        val seat: Protocol.Seat,          // our coin and change, as signed at join time
        val joinProof: String,            // hex DER, sent with the join request
        val changeSig: String,            // hex DER
        val mixScript: ByteArray,
        val coinPath: String,             // BIP-32 path of our coin's key, to sign at the end
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
    ) {
        val poolId: String get() = terms.id
        fun toJson(): JSONObject = JSONObject()
            .put("terms", terms.toJson().put("created_at", terms.createdAt))
            .put("creator", creator).put("pool_secret", poolSecret ?: "").put("join_secret", joinSecret)
            .put("token", token ?: "").put("seat", seat.toJson()).put("join_proof", joinProof)
            .put("change_sig", changeSig).put("mix_script", mixScript.toHex()).put("coin_path", coinPath)
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

        companion object {
            private fun JSONObject.str(k: String): String? = optString(k).ifEmpty { null }
            private fun JSONObject.map(k: String): MutableMap<String, String> {
                val o = optJSONObject(k) ?: return mutableMapOf()
                return o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
            }
            fun parse(o: JSONObject): State {
                val t = o.getJSONObject("terms")
                val terms = Protocol.Terms(t.getString("id"), t.getString("public_key"), t.getLong("denomination"),
                    t.getDouble("fee_rate"), t.getInt("max_peers"), t.getLong("timeout"), t.getString("state"),
                    t.optInt("peers"), t.optLong("created_at"), t.optInt("min_peers", Protocol.MIN_PEERS),
                    t.optBoolean("private", false))
                val outs = o.optJSONArray("outputs"); val acc = o.optJSONArray("accepts")
                return State(
                    terms, o.getBoolean("creator"), o.str("pool_secret"), o.getString("join_secret"), o.str("token"),
                    Protocol.Seat.parse(o.getJSONObject("seat")), o.getString("join_proof"), o.getString("change_sig"),
                    Hashes.hexToBytes(o.getString("mix_script")), o.getString("coin_path"),
                    o.str("pw_key"), o.str("pw_proof"),
                    Phase.valueOf(o.getString("phase")), o.optString("reason"),
                    Protocol.parseSeats(o.getJSONArray("seats")).toMutableList(),
                    Protocol.parseSeats(o.getJSONArray("round")).toMutableList(),
                    o.optInt("roster_seq"), o.str("vote_id"), o.str("vote_by"), o.optLong("vote_deadline"), o.optLong("phase_deadline"),
                    (0 until (outs?.length() ?: 0)).map { outs!!.getString(it) }.toMutableSet(),
                    o.map("sigs"), o.optBoolean("posted_output"), o.optBoolean("posted_sig"), o.str("voted_on"),
                    o.str("txid"), o.optLong("created"),
                    (0 until (acc?.length() ?: 0)).map { acc!!.getString(it) }.toMutableSet(),
                    o.map("join_pubs"), o.map("tokens"), o.optBoolean("warned_expiry"),
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

    val state: State get() = s
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
        s.pwProof?.let { o.put("pw", it) }
        direct(terms.poolPub, o, joinKey)
    }

    // ------------------------------------------------------------------------------- receiving

    private fun onEvent(ev: NostrEvent) {
        if (ev.kind != Protocol.KIND_MSG) return
        env.log("event from ${ev.pubkey.take(8)} p=${ev.tag("p")?.take(8)}")
        synchronized(this) {
            runCatching {
                when {
                    // Pool channel: author and recipient are the pool key.
                    ev.pubkey == terms.poolPub && ev.tag("p") == terms.poolPub -> {
                        val k = poolKey ?: return
                        val msg = Nip44.decrypt(ev.content, Nip44.conversationKey(k, Hashes.hexToBytes(terms.poolPub))) ?: return
                        onChannel(JSONObject(msg), ev.createdAt)
                    }
                    // A reply from the pool to our join key.
                    ev.pubkey == terms.poolPub && ev.tag("p") == joinPub -> {
                        val msg = Nip44.decrypt(ev.content, Nip44.conversationKey(joinKey, Hashes.hexToBytes(terms.poolPub))) ?: return
                        onDirect(JSONObject(msg))
                    }
                    // A join request to the pool (only the creator can read and answer it).
                    s.creator && ev.tag("p") == terms.poolPub && ev.pubkey != terms.poolPub -> {
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
        s.seats = mutableListOf(s.seat.copy(tokenHash = Protocol.tokenHash(token)))
        s.phase = Phase.OPEN
        save()
        announce()
        channel(roster())
    }

    /** The roster, numbered: a member replaying the channel ignores the ones from before it joined. */
    private fun roster(): JSONObject {
        s.rosterSeq++; save()
        return JSONObject().put("type", "roster").put("seq", s.rosterSeq).put("seats", Protocol.seatsJson(s.seats))
    }

    private fun reject(joinPub: String, reason: String) { env.log("reject: $reason"); poolKey?.let { direct(joinPub, JSONObject().put("type", "reject").put("reason", reason), it) } }

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
        val seat = runCatching { Protocol.Seat.parse(o.getJSONObject("seat")) }.getOrNull() ?: return reject(from, "malformed request")
        val coin = seat.coin
        if (s.seats.any { it.coin.outpoint == coin.outpoint }) return reject(from, "that coin already has a seat")
        if (!CoinjoinTx.checkOwnership(terms.id, from, coin, Hashes.hexToBytes(o.getString("proof"))))
            return reject(from, "the coin's signature is wrong")
        val expected = CoinjoinTx.change(coin.value, terms.amount, terms.feeRate) ?: return reject(from, "the coin is too small for this pool")
        if (seat.changeValue != expected) return reject(from, "the change does not match the pool's fee")
        if ((expected > 0) != (seat.changeScript != null)) return reject(from, "the change does not match the pool's fee")
        val changeOk = runCatching {
            Ecdsa.verify(Secp256k1.decompress(coin.pubkey), Protocol.changeMessage(terms.id, coin, seat.changeScript, seat.changeValue),
                CoinjoinTx.parseDer(Hashes.hexToBytes(o.getString("change_sig"))))
        }.getOrDefault(false)
        if (!changeOk) return reject(from, "the change's signature is wrong")
        if (!runCatching { env.coinUnspent(coin) }.getOrDefault(false)) return reject(from, "that coin is not unspent on the chain")
        val token = randomHex(16)
        val th = Protocol.tokenHash(token)
        val placed = seat.copy(tokenHash = th)
        s.tokens[th] = token; s.joinPubs[from] = th; s.seats.add(placed)
        val roster = roster()
        direct(from, JSONObject().put("type", "welcome").put("pool_secret", s.poolSecret).put("token", token)
            .put("seq", s.rosterSeq), poolKey!!)
        channel(roster)
        announce()
        env.event(Event.Joined(s.poolId, s.seats.size))
        // Full: ask everyone whether to close now (the creator's own seat counts as a yes).
        if (s.seats.size >= terms.maxPeers) channel(JSONObject().put("type", "close_request")
            .put("token", s.token).put("vote_id", randomHex(8)))
    }

    private fun callClosing(seats: List<Protocol.Seat>, why: String) {
        s.round = seats.toMutableList()
        channel(JSONObject().put("type", "closing").put("why", why).put("seats", Protocol.seatsJson(seats))
            .put("deadline", env.now() + Protocol.OUTPUTS_SECONDS))
    }

    private fun creatorOnVote(tokenHash: String, voteId: String, accept: Boolean) {
        if (s.phase != Phase.VOTING || voteId != s.voteId) return
        if (s.seats.none { it.tokenHash == tokenHash }) return
        if (!accept) {
            // Whoever says "not yet" leaves this round (their coin is free again); the pool
            // reopens for more people, or for another vote if enough are still in.
            if (tokenHash != myTokenHash) {
                s.seats.removeAll { it.tokenHash == tokenHash }; s.accepts.remove(tokenHash); save()
                channel(roster()); announce()
            }
            // Always reopen: the others said yes to closing with the people there were, not
            // with fewer. They are asked again (or more people join first).
            channel(JSONObject().put("type", "reopen").put("vote_id", voteId))
            return
        }
        s.accepts.add(tokenHash); save()
        if (s.seats.all { it.tokenHash in s.accepts }) callClosing(s.seats.toList(), "agreed")
    }

    // ------------------------------------------------------------------------------- channel

    private fun onChannel(o: JSONObject, at: Long) {
        when (o.optString("type")) {
            "roster" -> {
                if (s.phase !in setOf(Phase.OPEN, Phase.VOTING)) return
                val seq = o.optInt("seq")
                if (s.creator || seq <= s.rosterSeq) return
                s.rosterSeq = seq
                val seats = Protocol.parseSeats(o.getJSONArray("seats"))
                val before = s.seats.size
                s.seats = seats.toMutableList()
                // Removed from the roster (e.g. our coin was spent): nothing more to do here.
                if (myTokenHash != null && seats.none { it.tokenHash == myTokenHash }) {
                    s.phase = Phase.ABORTED; s.reason = "left the pool"
                }
                save()
                if (!s.creator && seats.size > before && before > 0) env.event(Event.Joined(s.poolId, seats.size))
            }
            "close_request" -> {
                if (s.phase != Phase.OPEN || s.seats.size < terms.minPeers) return
                val token = o.getString("token")
                val th = Protocol.tokenHash(token)
                if (s.seats.none { it.tokenHash == th }) return
                s.phase = Phase.VOTING; s.voteId = o.getString("vote_id"); s.voteBy = th
                s.voteDeadline = at + Protocol.VOTE_SECONDS
                s.accepts.clear(); s.accepts.add(th)
                save()
                env.event(Event.CloseRequested(s.poolId, s.seats.size, th == myTokenHash))
                if (s.creator && s.seats.all { it.tokenHash in s.accepts }) callClosing(s.seats.toList(), "agreed")
            }
            "vote" -> if (s.creator) {
                val th = Protocol.tokenHash(o.getString("token"))
                creatorOnVote(th, o.getString("vote_id"), o.getBoolean("accept"))
            }
            "reopen" -> {
                if (s.phase != Phase.VOTING || o.optString("vote_id") != s.voteId) return
                s.phase = Phase.OPEN; s.voteId = null; s.accepts.clear(); save()
                env.event(Event.CloseRefused(s.poolId))
            }
            "closing" -> {
                if (s.phase !in setOf(Phase.OPEN, Phase.VOTING)) return
                val seats = Protocol.parseSeats(o.getJSONArray("seats"))
                if (myTokenHash == null || seats.none { it.tokenHash == myTokenHash }) {
                    s.phase = Phase.ABORTED; s.reason = "the round closed without us"; save()
                    env.event(Event.Aborted(s.poolId, s.reason)); return
                }
                s.round = seats.toMutableList(); s.phase = Phase.CLOSING
                s.phaseDeadline = o.optLong("deadline", at + Protocol.OUTPUTS_SECONDS)
                save()
                env.event(Event.Closing(s.poolId, seats.size))
                scheduleOutput()
            }
            "output" -> {
                if (s.phase != Phase.CLOSING) return
                val script = o.getString("script")
                if (!script.matches(Regex("0014[0-9a-f]{40}"))) return
                s.outputs.add(script); save()
                if (s.outputs.size > s.round.size) return abort("more mixed outputs than people")
                if (s.outputs.size == s.round.size) toSigning()
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
                s.txid = txid; s.phase = Phase.BROADCAST; save()
                env.event(Event.Broadcast(s.poolId, txid))
            }
            "leave" -> if (s.creator && s.phase in setOf(Phase.OPEN, Phase.VOTING)) {
                val th = Protocol.tokenHash(o.optString("token"))
                if (s.seats.removeAll { it.tokenHash == th }) {
                    s.accepts.remove(th); save()
                    channel(roster()); announce()
                    if (s.phase == Phase.VOTING && s.seats.size < terms.minPeers)
                        channel(JSONObject().put("type", "reopen").put("vote_id", s.voteId))
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

    private fun scheduleOutput() {
        if (s.postedOutput) return
        // A random wait, so nobody can tie our output to us by when it shows up.
        val delay = 3L + rnd.nextInt(40)
        timer?.schedule({
            synchronized(this) {
                if (s.phase == Phase.CLOSING && !s.postedOutput) {
                    if (channel(JSONObject().put("type", "output").put("script", s.mixScript.toHex()))) {
                        s.postedOutput = true; save()
                    }
                }
            }
        }, delay, TimeUnit.SECONDS)
    }

    private fun buildPlan(): CoinjoinTx.Plan? {
        if (s.round.size < Protocol.MIN_PEERS || s.outputs.size != s.round.size) return null
        return CoinjoinTx.plan(
            s.round.map { it.coin },
            s.outputs.map { Hashes.hexToBytes(it) },
            s.round.filter { it.changeScript != null && it.changeValue > 0 }.map { TxBuilder.Output(it.changeScript!!, it.changeValue) },
            terms.amount,
        )
    }

    private fun toSigning() {
        val plan = runCatching { buildPlan() }.getOrElse { return abort("the transaction does not add up: ${it.message}") } ?: return
        val change = s.seat.changeScript?.let { TxBuilder.Output(it, s.seat.changeValue) }
        CoinjoinTx.checkOurs(plan, s.mixScript, change, terms.amount, terms.feeRate)?.let { return abort(it) }
        s.phase = Phase.SIGNING; s.phaseDeadline = env.now() + Protocol.SIGN_SECONDS; save()
        env.event(Event.SignNeeded(s.poolId, plan.coins.size))
    }

    /** The user approved: sign our input with [privateKey] (derived from [State.coinPath]). */
    @Synchronized
    fun sign(privateKey: BigInteger) {
        check(s.phase == Phase.SIGNING) { "nothing to sign right now" }
        val plan = buildPlan() ?: error("the transaction is not complete")
        val change = s.seat.changeScript?.let { TxBuilder.Output(it, s.seat.changeValue) }
        CoinjoinTx.checkOurs(plan, s.mixScript, change, terms.amount, terms.feeRate)?.let { error(it) }
        val mine = plan.coins.first { it.outpoint == s.seat.coin.outpoint }
        val sig = CoinjoinTx.sign(plan, mine, privateKey).toHex()
        s.sigs[mine.outpoint] = sig
        check(channel(JSONObject().put("type", "sig").put("outpoint", mine.outpoint).put("sig", sig))) { "the relay did not take our signature" }
        s.postedSig = true; save()
        maybeBroadcast(plan)
    }

    private fun maybeBroadcast(plan: CoinjoinTx.Plan) {
        if (s.sigs.size < plan.coins.size || s.txid != null) return
        val tx = CoinjoinTx.assemble(plan, s.sigs.mapValues { Hashes.hexToBytes(it.value) })
        val ok = runCatching { env.broadcast(tx.rawHex) }
        // Another member may have broadcast it already: the same txid is just as good.
        if (ok.isFailure && env.confirmations(tx.txid) == null) {
            val msg = ok.exceptionOrNull()?.message ?: "broadcast failed"
            if (!msg.contains("already", true) && !msg.contains("txn-mempool-conflict", true)) return abort("broadcast failed: $msg")
        }
        s.txid = tx.txid; s.phase = Phase.BROADCAST; save()
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
        channel(JSONObject().put("type", "close_request").put("token", s.token).put("vote_id", randomHex(8)))
    }

    @Synchronized
    fun vote(accept: Boolean) {
        val id = s.voteId ?: return
        if (s.phase != Phase.VOTING || s.votedOn == id) return
        channel(JSONObject().put("type", "vote").put("token", s.token).put("vote_id", id).put("accept", accept))
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
        channel(JSONObject().put("type", "leave").put("token", s.token ?: ""))
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
            when (s.phase) {
                Phase.OPEN, Phase.JOINING -> if (s.phase == Phase.OPEN && !s.warnedExpiry && now <= terms.expiresAt &&
                    terms.expiresAt - now <= EXPIRY_WARN_SECONDS && s.seats.size >= terms.minPeers && s.seats.size < terms.maxPeers) {
                    // A last call: enough people to mix, and the pool is about to expire for nothing.
                    s.warnedExpiry = true; save()
                    env.event(Event.ExpiringSoon(s.poolId, s.seats.size, ((terms.expiresAt - now) / 60).toInt().coerceAtLeast(1)))
                } else if (now > terms.expiresAt) {
                    if (s.creator) abort("the pool expired") else { s.phase = Phase.ABORTED; s.reason = "the pool expired"; save(); env.event(Event.Aborted(s.poolId, s.reason)) }
                } else if (s.phase == Phase.JOINING && !s.creator && now - s.created > JOIN_TIMEOUT) {
                    // Only the creator's wallet lets people in; if it is closed, or the pool ended
                    // meanwhile, nobody ever answers. Give up instead of waiting until it expires.
                    s.phase = Phase.ABORTED
                    s.reason = "the pool did not answer (it may have ended, or its creator's app is closed)"
                    save(); env.event(Event.Aborted(s.poolId, s.reason))
                }
                Phase.VOTING -> if (s.creator && now > s.voteDeadline) {
                    // Whoever did not answer is left out, if two or more said yes.
                    val yes = s.seats.filter { it.tokenHash in s.accepts }
                    if (yes.size >= terms.minPeers) callClosing(yes, "agreed")
                    else {
                        // Too few for now. Whoever did not answer gives up the seat too, so a
                        // silent wallet cannot hold the pool (and every later vote) hostage.
                        val silent = s.seats.filter { it.tokenHash !in s.accepts && it.tokenHash != myTokenHash }
                        if (silent.isNotEmpty()) {
                            s.seats.removeAll(silent.toSet()); save()
                            channel(roster()); announce()
                        }
                        channel(JSONObject().put("type", "reopen").put("vote_id", s.voteId))
                    }
                }
                Phase.CLOSING -> if (now > s.phaseDeadline + 60) abort("not everyone sent their output in time")
                Phase.SIGNING -> if (now > s.phaseDeadline + 60) abort("not everyone signed in time")
                Phase.BROADCAST -> s.txid?.let { txid ->
                    val c = env.confirmations(txid)
                    if (c != null && c >= 1) { s.phase = Phase.CONFIRMED; save(); env.event(Event.Confirmed(s.poolId, txid)); stop() }
                }
                else -> {}
            }
        }
    }

    private fun randomHex(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }.toHex()

    companion object {
        /** Reason of a round this wallet left on purpose: its card goes away by itself. */
        const val LEFT_BY_CHOICE = "you left this round"
        /** Seconds a join request waits for the creator's answer. */
        const val JOIN_TIMEOUT = 5 * 60
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
        ): State {
            val changeValue = CoinjoinTx.change(coin.value, terms.amount, terms.feeRate) ?: error("this coin is too small for the pool")
            val cs = if (changeValue > 0) changeScript ?: error("a change address is needed") else null
            val joinSecret = NostrEvent.newSecret()
            val joinPub = NostrEvent.pubOf(joinSecret)
            val proof = CoinjoinTx.proveOwnership(terms.id, if (creator) terms.poolPub else joinPub, coin, coinKey).toHex()
            val changeSig = Ecdsa.der(Ecdsa.sign(coinKey, Protocol.changeMessage(terms.id, coin, cs, changeValue))).toHex()
            return State(
                terms = terms, creator = creator, poolSecret = poolSecret, joinSecret = joinSecret.toString(16),
                token = null, seat = Protocol.Seat("", coin, cs, changeValue), joinProof = proof, changeSig = changeSig,
                mixScript = mixScript, coinPath = coinPath,
                pwKey = if (creator && terms.private) Protocol.passwordKey(terms.id, password ?: error("a private pool needs a password")).toHex() else null,
                pwProof = if (!creator && terms.private)
                    Protocol.passwordProof(Protocol.passwordKey(terms.id, password ?: error("this pool needs its password")), joinPub) else null,
            )
        }

        /** Terms for a new pool, with its own fresh key. Returns (terms, pool secret hex). */
        fun newPool(amount: Long, feeRate: Double, maxPeers: Int, hours: Int, minPeers: Int = Protocol.MIN_PEERS,
                    private: Boolean = false): Pair<Protocol.Terms, String> {
            val k = NostrEvent.newSecret()
            val id = ByteArray(8).also { SecureRandom().nextBytes(it) }.toHex()
            val now = System.currentTimeMillis() / 1000
            require(minPeers in Protocol.MIN_PEERS..maxPeers) { "the minimum must be between 2 and the maximum" }
            return Protocol.Terms(id, NostrEvent.pubOf(k), amount, feeRate, maxPeers, now + hours * 3600L, "open", 1, now,
                minPeers, private) to k.toString(16)
        }
    }
}
