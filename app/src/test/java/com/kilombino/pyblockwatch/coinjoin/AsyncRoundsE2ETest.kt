package com.kilombino.pyblockwatch.coinjoin

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bech32
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import com.kilombino.pyblockwatch.coinjoin.PoolSession.Phase

/**
 * Kilojoin v6, asynchronous rounds, end to end: three wallets (the creator an always-on Kilojoin, and two
 * phones) on a relay in this process ([TestRelay], ws://127.0.0.1, so no test pool is ever seen by a real
 * wallet), with a clock the test moves forward so hours of a pool run in seconds, and a chain in memory
 * ([TestChain]) or, with KILOJOIN_REGTEST=1, a BLAKE2b regtest node on 127.0.0.1:18999 (cj/cj, wallet w),
 * so a node checks every signature of the round it mines.
 *
 * These are the interoperability cases of the RedWallet proposal (Kilombino/kilojoin#1).
 */
class AsyncRoundsE2ETest {
    private val offset = AtomicLong(0)
    private fun now() = System.currentTimeMillis() / 1000 + offset.get()
    private lateinit var relay: TestRelay
    private lateinit var chain: Chain
    private val wallets = CopyOnWriteArrayList<W>()
    private val saved = mutableMapOf<String, Any>()
    /** Saved states that did not come back the same (see [W.env]). */
    private val roundTrip = CopyOnWriteArrayList<String>()

    // ------------------------------------------------------------------ the chain: in memory, or a regtest node

    interface Chain {
        fun fund(key: BigInteger, value: Long): CoinjoinTx.Coin
        fun unspent(c: CoinjoinTx.Coin): Boolean
        fun broadcast(raw: String, who: String): String
        fun confirmations(txid: String): Int?
        fun mine()
        /** The coin spent by some other transaction: in a block when [mine], else only in the mempool. */
        fun spendElsewhere(c: CoinjoinTx.Coin, key: BigInteger, mine: Boolean = true)
        /** Confirmations of the transaction spending [c], unless that is [except]; null when unspent. */
        fun conflictDepth(c: CoinjoinTx.Coin, except: String): Int?
        /** Who broadcast, and what (the raw transactions taken). */
        val sent: List<Pair<String, String>>
    }

    private inner class MemChain : Chain {
        val c = TestChain()
        override fun fund(key: BigInteger, value: Long) = CoinjoinTx.Coin(randomHex(32), 0, value, pub(key)).also { c.fund(it) }
        override fun unspent(c: CoinjoinTx.Coin) = this.c.unspent(c)
        override fun broadcast(raw: String, who: String) = c.broadcast(raw, who)
        override fun confirmations(txid: String) = c.confirmations(txid)
        override fun mine() = c.mine()
        override fun spendElsewhere(c: CoinjoinTx.Coin, key: BigInteger, mine: Boolean) = this.c.spendElsewhere(c.outpoint, if (mine) 1 else 0)
        override fun conflictDepth(c: CoinjoinTx.Coin, except: String) = this.c.conflictDepth(c.outpoint, except)
        override val sent: List<Pair<String, String>> get() = c.broadcasts
    }

    /** A BLAKE2b regtest node (Knots with -testactivationheight=blake2b@…): it checks every signature. */
    private inner class RegtestChain : Chain {
        override val sent = CopyOnWriteArrayList<Pair<String, String>>()
        /** outpoint → the transaction that spends it, for those sent from here. */
        private val spentBy = java.util.concurrent.ConcurrentHashMap<String, String>()
        private fun noteSpends(raw: String) {
            val tx = com.kilombino.pyblockwatch.crypto.TxParse.parse(raw)
            val id = com.kilombino.pyblockwatch.crypto.TxParse.txid(tx)
            tx.inputs.forEach { spentBy["${it.txid}:${it.vout}"] = id }
        }
        fun rpc(method: String, vararg params: Any): Any? {
            val c = URL("http://127.0.0.1:18999/wallet/w").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("cj:cj".toByteArray()))
            c.outputStream.write(JSONObject().put("method", method).put("params", JSONArray(params.toList())).toString().toByteArray())
            val body = (if (c.responseCode == 200) c.inputStream else c.errorStream).bufferedReader().readText()
            val o = JSONObject(body)
            if (!o.isNull("error")) throw IllegalStateException(o.getJSONObject("error").getString("message"))
            return o.opt("result")
        }
        override fun fund(key: BigInteger, value: Long): CoinjoinTx.Coin {
            val addr = Bech32.encodeSegwit("bcrt", 0, Hashes.hash160(pub(key)))
            val txid = rpc("sendtoaddress", addr, "%.8f".format(java.util.Locale.ROOT, value / 1e8)) as String
            mine()
            val tx = rpc("getrawtransaction", txid, true) as JSONObject
            val outs = tx.getJSONArray("vout")
            val vout = (0 until outs.length()).first { outs.getJSONObject(it).getJSONObject("scriptPubKey").optString("address") == addr }
            return CoinjoinTx.Coin(txid, vout, value, pub(key))
        }
        override fun unspent(c: CoinjoinTx.Coin): Boolean {
            val o = rpc("gettxout", c.txid, c.vout, true) as? JSONObject ?: return false
            return Math.round(o.getDouble("value") * 1e8) == c.value && o.getJSONObject("scriptPubKey").getString("hex") == c.script.toHex()
        }
        // A node takes the same transaction again without complaint: each one is noted once, with who sent it first.
        override fun broadcast(raw: String, who: String): String = (rpc("sendrawtransaction", raw) as String)
            .also { noteSpends(raw); synchronized(sent) { if (sent.none { it.second == raw }) sent.add(who to raw) } }
        override fun confirmations(txid: String): Int? =
            runCatching { (rpc("getrawtransaction", txid, true) as JSONObject).optInt("confirmations", 0) }.getOrNull()
        override fun mine() { rpc("generatetoaddress", 1, rpc("getnewaddress") as String) }
        override fun conflictDepth(c: CoinjoinTx.Coin, except: String): Int? {
            if (unspent(c)) return null
            val by = spentBy[c.outpoint] ?: return null
            return if (by == except) null else confirmations(by)
        }
        override fun spendElsewhere(c: CoinjoinTx.Coin, key: BigInteger, mine: Boolean) {
            // A plain spend of the coin back to the node's wallet, signed with the unified sighash (BLAKE2b chain).
            val to = Address.scriptPubKey(pub(key.add(BigInteger.valueOf(77))), ScriptType.P2WPKH)
            val tb = com.kilombino.pyblockwatch.crypto.TxBuilder
            val ins = listOf(com.kilombino.pyblockwatch.crypto.TxBuilder.Input(c.txid, c.vout, c.value, key, c.pubkey, 0xfffffffdL, ScriptType.P2WPKH))
            val outs = listOf(com.kilombino.pyblockwatch.crypto.TxBuilder.Output(to, c.value - 500))
            val raw = tb.assemble(ins, outs, listOf(tb.witnessSignature(2L, ins, outs, 0, 0L, unified = true, grindLowR = true)), 2L, 0L).rawHex
            rpc("sendrawtransaction", raw); noteSpends(raw); if (mine) mine()
        }
    }

    // ------------------------------------------------------------------ wallets

    private fun pub(k: BigInteger) = Secp256k1.compress(Secp256k1.multiply(k, Secp256k1.G))
    private fun script(k: BigInteger) = Address.scriptPubKey(pub(k), ScriptType.P2WPKH)
    private fun randomHex(n: Int) = ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }.toHex()

    /** One wallet: its coin and key, its session, and what it last saved (to come back from, as after a restart). */
    private inner class W(val name: String, value: Long = 25_000) {
        val key: BigInteger = BigInteger(1, ByteArray(31).also { java.security.SecureRandom().nextBytes(it) }).add(BigInteger.TEN)
        val coin = chain.fund(key, value)
        val mix = script(key.add(BigInteger.ONE)); val change = script(key.add(BigInteger.TWO))
        @Volatile var savedJson: String? = null
        lateinit var session: PoolSession
        val log = CopyOnWriteArrayList<String>()
        val env = object : PoolSession.Env {
            override fun coinUnspent(coin: CoinjoinTx.Coin) = chain.unspent(coin)
            override fun broadcast(rawHex: String): String = chain.broadcast(rawHex, name)
            override fun confirmations(txid: String): Int? = chain.confirmations(txid)
            override fun conflictDepth(coin: CoinjoinTx.Coin, roundTxid: String): Int? = chain.conflictDepth(coin, roundTxid)
            override fun now(): Long = this@AsyncRoundsE2ETest.now()
            override fun save(state: PoolSession.State) {
                val j = state.toJson().toString()
                // What is saved must come back as it was (the restart tests depend on it). Noted, not thrown: the
                // session would swallow it. The test fails on it at the end.
                val back = runCatching { PoolSession.State.parse(JSONObject(j)).toJson().toString() }.getOrElse { it.toString() }
                if (runCatching { canon(JSONObject(back)) != canon(JSONObject(j)) }.getOrDefault(true)) roundTrip.add("$name: $j\n -> $back")
                savedJson = j
            }
            override fun event(e: PoolSession.Event) { if (e !is PoolSession.Event.Changed) log.add(e.javaClass.simpleName) }
            override fun log(msg: String) { log.add(msg); if (System.getenv("KILOJOIN_VERBOSE") != null) println("[$name] $msg") }
        }
        val st: PoolSession.State get() = session.state

        fun open(minPeers: Int = 2, maxPeers: Int = 5, async: Boolean = true): Protocol.Terms {
            val np = PoolSession.newPool(10_000, 1.0, maxPeers, 2, minPeers, v3 = true, async = async)
            session = PoolSession(env, PoolSession.newState(np.terms, true, np.secret, listOf(coin), listOf(key), listOf("m/$name"),
                listOf(mix), change, creatorSecret = np.creatorSecret, blindPriv = np.blindPriv))
            wallets += this
            session.start()
            waitFor("$name opens") { st.phase == Phase.OPEN }
            return np.terms
        }

        fun join(t: Protocol.Terms) {
            session = PoolSession(env, PoolSession.newState(t, false, null, listOf(coin), listOf(key), listOf("m/$name"), listOf(mix), change))
            wallets += this
            session.start()
            waitFor("$name welcomed") { st.phase == Phase.OPEN && st.token != null }
        }

        /** Offline: the process is gone, only what it saved stays. */
        fun stop() { session.stop() }

        /** Back: a new process from what was saved, as Kilojoin and the app load their rounds. */
        fun restart() {
            session.stop()
            session = PoolSession(env, PoolSession.State.parse(JSONObject(savedJson!!)))
            session.start()
        }

        fun sign() = session.sign(listOf(key))
    }

    // ------------------------------------------------------------------ helpers

    private fun waitFor(what: String, seconds: Int = 60, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < until) { if (runCatching(cond).getOrDefault(false)) return; Thread.sleep(100) }
        wallets.forEach { w -> println("--- ${w.name}: ${runCatching { w.st.phase }.getOrNull()} ${runCatching { w.st.reason }.getOrNull()}"); w.log.takeLast(25).forEach { println("   $it") } }
        throw AssertionError("timed out waiting for: $what")
    }

    /**
     * The clock forward by [seconds], in steps of at most 9 minutes with the sessions' timers between them,
     * as time would pass for real: the creator keeps re-announcing its pool every 10 minutes as it does.
     */
    private fun advance(seconds: Long, stepMax: Long = 540L) {
        var left = seconds
        while (left > 0) { val step = minOf(left, stepMax); offset.addAndGet(step); left -= step; Thread.sleep(1300) }
    }

    /** The open pools on the relay, as a wallet lists them. */
    private fun listPools(): List<Protocol.Terms> {
        val found = java.util.concurrent.ConcurrentHashMap<String, Protocol.Terms>()
        val r = RelayClient(Protocol.RELAY, { _, ev -> Protocol.Terms.parse(ev)?.let { t -> if ((found[t.id]?.createdAt ?: 0) <= t.createdAt) found[t.id] = t } })
        r.connect()
        r.subscribe("pools", listOf(JSONObject().put("kinds", JSONArray().put(Protocol.KIND_POOL)).put("#t", JSONArray().put(Protocol.TAG))))
        r.close()
        val n = now()
        return found.values.filter { it.state == "open" && it.expiresAt > n && it.peers < it.maxPeers && !Protocol.isTest(it) }
    }

    /** A channel message as the pool's members read it (the test holds the pool's secret through its creator). */
    private fun read(a: W, ev: JSONObject): JSONObject? {
        val t = a.st.terms
        val p = (0 until ev.getJSONArray("tags").length()).map { ev.getJSONArray("tags").getJSONArray(it) }
            .firstOrNull { it.getString(0) == "p" }?.getString(1) ?: return null
        if (p != t.poolPub) return null
        val k = BigInteger(a.st.poolSecret!!, 16)
        val author = ev.getString("pubkey")
        if (author != t.poolPub && author != t.creatorPub) return null
        val m = Nip44.decrypt(ev.getString("content"), Nip44.conversationKey(k, Hashes.hexToBytes(author))) ?: return null
        return runCatching { JSONObject(m) }.getOrNull()
    }

    /** A JSON value with its objects' keys in order, to compare two serialisations whatever order their keys come in. */
    private fun canon(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().toList().sorted().associateWith { canon(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { canon(v.get(it)) }
        else -> v
    }

    private fun msgs(a: W, type: String) = relay.published.mapNotNull { read(a, it) }.filter { it.optString("type") == type }
    private fun th(w: W) = Protocol.tokenHash(w.st.token!!)

    @Before fun setUp() {
        relay = TestRelay()
        chain = if (System.getenv("KILOJOIN_REGTEST") != null) RegtestChain() else MemChain()
        saved["relay"] = Protocol.RELAY; saved["net"] = Protocol.NETWORK; saved["clock"] = NostrEvent.clock
        Protocol.RELAY = relay.url
        Protocol.NETWORK = "blake2b-localtest"
        NostrEvent.clock = { now() }
        PoolSession.TICK_SECONDS = 1; PoolSession.OUTPUT_DELAY = 0..1; PoolSession.RESEND_SECONDS = 3; PoolSession.OUTPUT_ACK_WAIT = 3
        PoolSession.RESOLVE_EVERY = 2; PoolSession.SYNC_EVERY = 5; PoolSession.RECLOSE_AFTER = 2
        PoolSession.REPOST_EVERY = 86_400
    }

    @Suppress("UNCHECKED_CAST")
    @After fun tearDown() {
        wallets.forEach { runCatching { it.session.stop() } }
        relay.close()
        Protocol.RELAY = saved["relay"] as String; Protocol.NETWORK = saved["net"] as String; NostrEvent.clock = saved["clock"] as () -> Long
        PoolSession.TICK_SECONDS = 15; PoolSession.OUTPUT_DELAY = 3..42; PoolSession.RESEND_SECONDS = 30; PoolSession.OUTPUT_ACK_WAIT = 20
        PoolSession.RESOLVE_EVERY = 120; PoolSession.SYNC_EVERY = 120; PoolSession.RECLOSE_AFTER = 15
        PoolSession.REPOST_EVERY = 86_400
        assertTrue("states that did not survive a restart: ${roundTrip.take(2)}", roundTrip.isEmpty())
    }

    /** A creator and two members in a v6 pool, found on the relay's listing, everyone seated. */
    private fun threeInAPool(): Triple<W, W, W> {
        val a = W("A"); val b = W("B"); val c = W("C")
        val t = a.open()
        val listed = listPools().single { it.id == t.id }
        assertTrue(listed.v6); assertEquals(t.async, listed.async)
        b.join(listed); c.join(listed)
        waitFor("everyone sees three seats") { listOf(a, b, c).all { it.st.seats.size == 3 } }
        return Triple(a, b, c)
    }

    private fun allSigning(vararg ws: W) = waitFor("signing", 90) { ws.all { it.st.phase == Phase.SIGNING } }

    // ------------------------------------------------------------------ the cases

    /**
     * Three people approve at different times (10 and 35 minutes apart, past v5's 10-minute vote), the first
     * signer goes offline once the relay took its signature, the others sign 40 and 70 minutes later (past
     * v5's 20-minute signing), and the last one broadcasts the one transaction everyone planned.
     */
    @Test fun approveAtDifferentTimesFirstSignerOfflineLastBroadcasts() {
        val (a, b, c) = threeInAPool()
        b.session.requestClose()
        waitFor("vote open everywhere") { listOf(a, b, c).all { it.st.phase == Phase.VOTING } }
        assertEquals(b.st.voteId, b.st.voteAcked) // the creator's vote_open is its record of B's yes
        val deadline = a.st.voteDeadline
        assertTrue("the vote window is the announced one", deadline - now() > Protocol.VOTE_SECONDS)

        advance(10 * 60)
        c.session.vote(true)
        waitFor("C's vote recorded by the creator") { c.st.voteAcked == c.st.voteId }
        assertEquals(Phase.VOTING, a.st.phase)

        advance(25 * 60)
        a.session.vote(true)
        // Unanimous: the round closes at once, not at the deadline.
        waitFor("closing") { a.st.phase != Phase.VOTING }
        assertTrue(now() < deadline)
        allSigning(a, b, c)
        // Every output acknowledged by the creator before signing (each to its own fresh key).
        listOf(b, c).forEach { assertEquals(1, it.st.handedIn.size); assertTrue(it.st.postedOutput) }

        b.sign()
        assertTrue("the relay took B's signature", b.st.postedSig)
        b.stop()

        advance(40 * 60)
        a.sign()
        advance(30 * 60)
        assertEquals("not broadcast before the last signature", 0, chain.sent.size)
        c.sign()
        waitFor("broadcast") { chain.sent.isNotEmpty() && a.st.txid != null && c.st.txid != null }
        assertEquals("C, the last to sign, broadcast it", "C", chain.sent.first().first)
        assertEquals(1, chain.sent.size)
        val planned = a.session.plannedTxid()
        assertEquals(planned, c.st.txid); assertEquals(planned, a.st.txid)
        assertEquals(c.session.plan()!!.let { CoinjoinTx.assemble(it, a.st.sigs.mapValues { e -> Hashes.hexToBytes(e.value) }).rawHex },
            chain.sent.first().second)

        repeat(PoolSession.DEPTH) { chain.mine() }
        waitFor("confirmed") { a.st.phase == Phase.CONFIRMED && c.st.phase == Phase.CONFIRMED }
        // B, offline all along since signing, comes back and finds its round done.
        b.restart()
        waitFor("B sees it", 60) { b.st.phase == Phase.CONFIRMED }
        assertEquals(planned, b.st.txid)
    }

    /**
     * Lost acknowledgments: the creator's first vote_ack to C is lost (and every snapshot of the vote), the first
     * output handed in never reaches the creator (so it never acknowledges it), and B never hears the relay's OK
     * for its first signature. Each is sent again and counted once.
     */
    @Test fun lostAcknowledgments() {
        val (a, b, c) = threeInAPool()
        val lostAck = java.util.concurrent.atomic.AtomicBoolean(false)
        val lostOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val noOkSig = java.util.concurrent.atomic.AtomicBoolean(false)
        val cTh = th(c); val bTh = th(b)
        relay.lose = { ev ->
            val m = read(a, ev)
            when {
                m != null && m.optString("type") == "vote_ack" && m.optString("token_hash") == cTh && lostAck.compareAndSet(false, true) -> true
                // Snapshots during the vote too, so C learns nothing of its yes but by an acknowledgment.
                m != null && m.optString("type") == "state" && m.optString("phase") == "voting" -> true
                // The first output handed in (from its fresh key to the creator): taken by the relay, never delivered,
                // so no acknowledgment comes back and it is handed in again.
                m == null && ev.getString("pubkey") !in setOf(a.st.terms.poolPub, a.st.terms.creatorPub) &&
                    ev.toString().contains(a.st.terms.creatorPub!!) && lostOut.compareAndSet(false, true) -> true
                else -> false
            }
        }
        relay.noOk = { ev -> val m = read(a, ev); m != null && m.optString("type") == "sig" && b.st.seat.coin.outpoint == m.optString("outpoint") && noOkSig.compareAndSet(false, true) }

        b.session.requestClose()
        waitFor("voting") { listOf(a, b, c).all { it.st.phase == Phase.VOTING } }
        c.session.vote(true)
        waitFor("C's yes recorded by the creator after its first acknowledgment was lost") { c.st.voteAcked == c.st.voteId }
        assertTrue(lostAck.get())
        val cVotes = msgs(a, "vote").count { it.optString("token_hash") == cTh }
        assertTrue("C sent its yes again: $cVotes", cVotes >= 2)
        assertEquals("counted once", 2, a.st.accepts.size)
        a.session.vote(true)
        allSigning(a, b, c)
        assertTrue(lostOut.get())
        // Counted once: the round closed over three seats, three yes.
        assertEquals(3, a.st.round.size)
        val handedIn = relay.published.count { it.getString("pubkey") !in setOf(a.st.terms.poolPub, a.st.terms.creatorPub) &&
            it.getInt("kind") == Protocol.KIND_MSG && it.toString().contains(a.st.terms.creatorPub!!) }
        assertTrue("an output was handed in again after its lost acknowledgment ($handedIn)", handedIn >= 3)
        assertEquals("the creator lists each output once", 3, a.st.outputs.size)

        b.sign()
        assertFalse("B never heard the OK", b.st.postedSig)
        waitFor("B's signature sent again and taken") { b.st.postedSig }
        assertTrue(msgs(a, "sig").count { it.optString("outpoint") == b.st.seat.coin.outpoint } >= 2)
        a.sign(); c.sign()
        waitFor("broadcast") { listOf(a, b, c).all { it.st.txid != null } }
        assertEquals(1, chain.sent.map { it.second }.toSet().size)
        assertEquals(bTh, th(b))
    }

    /**
     * Every event delivered twice, and every replay in random order: votes are counted once, rosters and votes
     * applied in their own order, and a member restarted mid-round converges on the same round.
     */
    @Test fun duplicateAndReorderedEvents() {
        relay.duplicate = true; relay.shuffle = true
        val (a, b, c) = threeInAPool()
        b.session.requestClose()
        waitFor("voting") { listOf(a, b, c).all { it.st.phase == Phase.VOTING } }
        c.session.vote(true)
        waitFor("C recorded") { c.st.voteAcked == c.st.voteId }
        // The same vote event again, straight to the relay (a replay).
        val again = relay.published.first { read(a, it)?.optString("type") == "vote" }
        val r = RelayClient(Protocol.RELAY, { _, _ -> }); r.connect(); r.publish(NostrEvent.parse(again)); r.close()
        assertEquals(2, a.st.accepts.size)
        // C restarts: its replay comes shuffled and doubled.
        c.restart()
        Thread.sleep(2000)
        assertEquals(Phase.VOTING, c.st.phase); assertEquals(b.st.voteId, c.st.voteId)
        a.session.vote(true)
        allSigning(a, b, c)
        assertEquals(3, a.st.round.size)
        listOf(a, b, c).forEach { it.sign() }
        waitFor("broadcast") { listOf(a, b, c).all { it.st.txid != null } }
        assertEquals(1, chain.sent.size)
    }

    /**
     * A vote expires with C silent: no round of two comes out of it. C leaves the roster and a new vote starts
     * over the new one, where B's earlier yes doesn't count: B answers again, and only then does it close.
     * C, back later, finds itself out of the pool, its coin free.
     */
    @Test fun expiredVoteBecomesANewVoteOverTheNewRoster() {
        val (a, b, c) = threeInAPool()
        val cTh = th(c)
        c.stop()
        b.session.requestClose()
        waitFor("voting") { a.st.phase == Phase.VOTING && b.st.phase == Phase.VOTING }
        val first = a.st.voteId
        a.session.vote(true)
        waitFor("A recorded") { th(a) in a.st.accepts }
        advance(61 * 60)
        waitFor("a new vote") { a.st.voteId != null && a.st.voteId != first && b.st.voteId == a.st.voteId }
        assertEquals(2, a.st.seats.size); assertTrue(a.st.seats.none { it.tokenHash == cTh })
        assertEquals(listOf(Phase.VOTING, Phase.VOTING), listOf(a.st.phase, b.st.phase))
        assertTrue("no round came out of the expired vote", msgs(a, "closing").isEmpty())
        assertEquals("timeout", msgs(a, "reopen").single().optString("outcome"))
        Thread.sleep(3000)
        assertEquals("still waiting for B's new answer", Phase.VOTING, a.st.phase)
        b.session.vote(true)
        allSigning(a, b)
        assertEquals(2, a.st.round.size)
        a.sign(); b.sign()
        waitFor("broadcast") { a.st.txid != null && b.st.txid != null }
        c.restart()
        waitFor("C out of the pool") { c.st.phase == Phase.ABORTED }
        assertTrue(c.st.reason, c.st.reason.contains("left the pool"))
    }

    /**
     * The signing window and the final time pass with C's signature missing: A and B signed, so their coins
     * stay reserved (not cancelled, not free); C, which never signed, is out. Then C's coin is spent elsewhere,
     * first only in the mempool (it could still be replaced or evicted): still reserved, and through five blocks
     * too. At six confirmations of that spend the round can never go out, and A's and B's coins are free.
     */
    @Test fun signedInputsStayReservedUntilResolved() {
        val (a, b, c) = threeInAPool()
        b.session.requestClose(); waitFor("voting") { listOf(a, b, c).all { it.st.phase == Phase.VOTING } }
        a.session.vote(true); c.session.vote(true)
        allSigning(a, b, c)
        a.sign(); b.sign()
        advance(a.st.terms.signSeconds + 120)
        Thread.sleep(3000)
        assertEquals(Phase.ABORTED, c.st.phase)
        assertEquals(Phase.SIGNING, a.st.phase); assertEquals(Phase.SIGNING, b.st.phase)
        assertTrue(a.session.reserved && b.session.reserved)
        // Past the pool's final time too: still reserved.
        advance(a.st.terms.async!!.final - now() + 600)
        Thread.sleep(3000)
        assertTrue(a.session.reserved && b.session.reserved)
        chain.spendElsewhere(c.st.seat.coin, c.key, mine = false)
        Thread.sleep(5000)
        assertTrue("an unconfirmed conflict frees nothing", a.session.reserved && b.session.reserved)
        repeat(PoolSession.DEPTH - 1) { chain.mine() }
        Thread.sleep(5000)
        assertEquals(PoolSession.DEPTH - 1, chain.conflictDepth(c.st.seat.coin, ""))
        assertTrue("a conflict ${PoolSession.DEPTH - 1} deep frees nothing", a.session.reserved && b.session.reserved)
        chain.mine()
        waitFor("resolved") { a.st.phase == Phase.ABORTED && b.st.phase == Phase.ABORTED }
        assertTrue(a.st.reason, a.st.reason.contains("spent elsewhere"))
    }

    /**
     * C signs but its signature never reaches anyone (lost), and it goes offline. The window passes: A and B,
     * having signed, wait reserved. C comes back hours later and sends it again: the round goes out.
     */
    @Test fun aLateSignatureStillCompletesAReservedRound() {
        val (a, b, c) = threeInAPool()
        b.session.requestClose(); waitFor("voting") { listOf(a, b, c).all { it.st.phase == Phase.VOTING } }
        a.session.vote(true); c.session.vote(true)
        allSigning(a, b, c)
        val cOut = c.st.seat.coin.outpoint
        relay.lose = { ev -> read(a, ev)?.let { it.optString("type") == "sig" && it.optString("outpoint") == cOut } ?: false }
        // C signs first, its signature is lost on the way, and it goes offline before the others sign.
        c.sign()
        c.stop()
        a.sign(); b.sign()
        advance(a.st.terms.signSeconds + 3600)
        Thread.sleep(3000)
        assertTrue("A ${a.st.phase} ${a.st.sigs.size} ${a.st.reason} / B ${b.st.phase} ${b.st.sigs.size} ${b.st.reason}",
            a.session.reserved && b.session.reserved)
        assertEquals(0, chain.sent.size)
        relay.lose = { false }
        // C comes back: it has its own signature and finds the others' on the relay, so it broadcasts; or, the
        // creator's snapshot missing its signature, it sends it again and A or B broadcast. Either way, one transaction.
        c.restart()
        waitFor("broadcast", 90) { chain.sent.isNotEmpty() }
        assertEquals(1, chain.sent.size)
        waitFor("everyone has it") { listOf(a, b, c).all { it.st.txid != null } }
    }

    /**
     * The round's transaction one block deep, then reorged out and gone from every mempool: nobody takes it as
     * confirmed, the wallets holding every signature send it again, and it counts as confirmed only six blocks deep.
     */
    @Test fun aRoundReorgedOutIsSentAgainAndConfirmedOnlySixDeep() {
        org.junit.Assume.assumeTrue("a reorg that also empties the mempool: the chain in memory only", chain is MemChain)
        val mem = (chain as MemChain).c
        val (a, b, c) = threeInAPool()
        b.session.requestClose(); waitFor("voting") { listOf(a, b, c).all { it.st.phase == Phase.VOTING } }
        a.session.vote(true); c.session.vote(true)
        allSigning(a, b, c)
        a.sign(); b.sign(); c.sign()
        waitFor("broadcast") { listOf(a, b, c).all { it.st.txid != null && it.st.sigs.size == 3 } }
        val txid = a.st.txid!!
        assertEquals(1, chain.sent.size)
        chain.mine()
        waitFor("one confirmation seen") { listOf(a, b, c).all { it.st.confs == 1 } }
        Thread.sleep(3000)
        assertTrue("one block deep is not confirmed", listOf(a, b, c).all { it.st.phase == Phase.BROADCAST })
        mem.reorgOut(txid)
        assertNull(chain.confirmations(txid))
        waitFor("sent again") { chain.sent.size == 2 && chain.confirmations(txid) == 0 }
        assertEquals(txid, com.kilombino.pyblockwatch.crypto.TxParse.txid(com.kilombino.pyblockwatch.crypto.TxParse.parse(chain.sent[1].second)))
        assertTrue(listOf(a, b, c).none { it.st.phase == Phase.CONFIRMED })
        repeat(PoolSession.DEPTH - 1) { chain.mine() }
        waitFor("five confirmations seen") { listOf(a, b, c).all { it.st.confs == PoolSession.DEPTH - 1 } }
        Thread.sleep(3000)
        assertTrue(listOf(a, b, c).all { it.st.phase == Phase.BROADCAST })
        chain.mine()
        waitFor("confirmed six deep") { listOf(a, b, c).all { it.st.phase == Phase.CONFIRMED } }
        assertEquals(2, chain.sent.size)
    }

    /**
     * The creator opens a new vote by itself only if a whole round (vote, outputs, signing, each its full window) still
     * fits before `final`. The first vote opens ten minutes before the pool's open time ends, the latest it can: D says
     * nothing, and the default `final` leaves room for the second vote. C says nothing to that one: a third would end
     * past `final`, so there is none (the rule before, the outputs window only, would have opened it).
     */
    @Test fun aNewVoteOnlyWhenAWholeRoundStillFits() {
        val (a, b, c) = threeInAPool()
        val d = W("D"); d.join(listPools().single { it.id == a.st.poolId })
        waitFor("four seats") { listOf(a, b, c, d).all { it.st.seats.size == 4 } }
        val t = a.st.terms; val w = t.async!!
        assertEquals("the default final", t.expiresAt + 2 * w.vote + 2 * w.outputs + w.sign, w.final)
        advance(t.expiresAt - now() - 600)
        b.session.requestClose(); waitFor("voting") { listOf(a, b, c, d).all { it.st.phase == Phase.VOTING } }
        a.session.vote(true); c.session.vote(true)
        waitFor("A and C recorded") { th(a) in a.st.accepts && th(c) in a.st.accepts }
        advance(w.vote + 60)
        waitFor("a second vote over A, B and C") { listOf(a, b, c).all { it.st.voteN == 2 && it.st.phase == Phase.VOTING } }
        assertEquals(3, a.st.seats.size)
        assertTrue(now() + w.vote + w.outputs + w.sign <= w.final)
        a.session.vote(true); b.session.vote(true)
        waitFor("A and B recorded") { th(a) in a.st.accepts && th(b) in a.st.accepts }
        advance(w.vote + 60)
        waitFor("the second vote over") { a.st.phase != Phase.VOTING }
        Thread.sleep(3000)
        assertTrue("a whole round no longer fits", now() + w.vote + w.outputs + w.sign > w.final)
        assertTrue("the outputs window alone would", now() + w.outputs < w.final)
        assertEquals(2, a.st.voteN); assertEquals(1, a.st.revotes)
        assertEquals(2, msgs(a, "vote_open").size)
        assertEquals(listOf("timeout", "timeout"), msgs(a, "reopen").map { it.optString("outcome") })
        assertTrue(listOf(a, b).none { it.st.phase == Phase.VOTING })
    }

    /**
     * A reserved round outlives a relay that keeps events a week (relay.kilombino.com). C signs, its signature is
     * lost, and it goes offline; A (the creator) and B sign and wait reserved. Eight days later the relay has
     * dropped everything older than seven days, and C's requests for a snapshot are lost too: what C finds is A's
     * and B's daily reposts of their signatures (and A's snapshot), so it holds every signature and broadcasts.
     */
    @Test fun aReservedRoundOutlivesAWeekOfRelayRetention() {
        val (a, b, c) = threeInAPool()
        b.session.requestClose(); waitFor("voting") { listOf(a, b, c).all { it.st.phase == Phase.VOTING } }
        a.session.vote(true); c.session.vote(true)
        allSigning(a, b, c)
        val cOut = c.st.seat.coin.outpoint
        relay.lose = { ev -> read(a, ev)?.let { it.optString("type") == "sig" && it.optString("outpoint") == cOut } ?: false }
        c.sign()
        c.stop()
        a.sign(); b.sign()
        val firstSigs = relay.stored.filter { read(a, it)?.optString("type") == "sig" }.map { it.getString("id") }.toSet()
        assertEquals(2, firstSigs.size)
        val week = 7 * 86_400L
        advance(8 * 86_400L, stepMax = 4 * 3600L)
        assertTrue(a.session.reserved && b.session.reserved)
        assertEquals(0, chain.sent.size)
        val dropped = relay.forget { it.getLong("created_at") < now() - week }
        assertTrue(dropped > 0)
        val sigsLeft = relay.stored.mapNotNull { read(a, it) }.filter { it.optString("type") == "sig" }
        assertTrue("only reposts are left", relay.stored.none { it.getString("id") in firstSigs })
        assertEquals(setOf(a.st.seat.coin.outpoint, b.st.seat.coin.outpoint), sigsLeft.map { it.getString("outpoint") }.toSet())
        assertTrue("A's snapshots go on", relay.stored.mapNotNull { read(a, it) }.any { it.optString("type") == "state" })
        relay.lose = { ev -> read(a, ev)?.optString("type") == "sync" }
        c.restart()
        waitFor("broadcast", 90) { chain.sent.isNotEmpty() }
        assertEquals(1, chain.sent.size)
        waitFor("everyone has it") { listOf(a, b, c).all { it.st.txid != null } }
        assertEquals(3, c.st.sigs.size)
    }

    /**
     * The roster changes during a vote (D leaves): the vote ends ("withdrawn"), it never closes with the three
     * left although all of them said yes, and a late yes to the old vote is answered "superseded" and not
     * counted. A new vote over the new roster closes with three.
     */
    @Test fun changedRosterEndsTheVote() {
        val (a, b, c) = threeInAPool()
        val d = W("D")
        d.join(listPools().single { it.id == a.st.poolId })
        waitFor("four seats") { listOf(a, b, c, d).all { it.st.seats.size == 4 } }
        b.session.requestClose()
        waitFor("voting") { listOf(a, b, c, d).all { it.st.phase == Phase.VOTING } }
        val old = a.st.voteId!!
        a.session.vote(true); c.session.vote(true)
        waitFor("A and C recorded") { a.st.accepts.size == 3 }
        d.session.leave()
        waitFor("vote over") { listOf(a, b, c).all { it.st.phase == Phase.OPEN && it.st.seats.size == 3 } }
        Thread.sleep(2000)
        assertTrue("no round of the three left", msgs(a, "closing").isEmpty())
        assertEquals("withdrawn", msgs(a, "reopen").last().optString("outcome"))
        // A yes to the old vote, now: superseded, not counted.
        val tok = c.st.token!!
        val cKey = BigInteger(c.st.poolSecret!!, 16)
        val late = JSONObject().put("type", "vote").put("token_hash", th(c)).put("vote_id", old).put("accept", true).put("mac", Protocol.mac(tok, "vote", old, true))
        val ev = NostrEvent.sign(cKey, Protocol.KIND_MSG, listOf(listOf("p", a.st.terms.poolPub)),
            Nip44.encrypt(late.toString(), Nip44.conversationKey(cKey, Hashes.hexToBytes(a.st.terms.poolPub))))
        val r = RelayClient(Protocol.RELAY, { _, _ -> }); r.connect(); r.publish(ev); r.close()
        waitFor("superseded") { msgs(a, "vote_ack").any { it.optString("vote_id") == old && it.optString("status") == "superseded" } }
        assertEquals(Phase.OPEN, a.st.phase); assertTrue(a.st.accepts.isEmpty())
        c.session.requestClose()
        waitFor("new vote") { listOf(a, b, c).all { it.st.phase == Phase.VOTING && it.st.voteSeq == a.st.rosterSeq } }
        a.session.vote(true); b.session.vote(true)
        allSigning(a, b, c)
        assertEquals(3, a.st.round.size)
    }

    /**
     * The creator restarts three times, from what it saved: during the vote (C answers while it is down), while
     * outputs come in, and while people sign. Nothing is counted twice, the round keeps its id, and it completes.
     */
    @Test fun restartedCreator() {
        val (a, b, c) = threeInAPool()
        b.session.requestClose(); waitFor("voting") { listOf(a, b, c).all { it.st.phase == Phase.VOTING } }
        a.session.vote(true)
        waitFor("A recorded") { a.st.accepts.size == 2 }
        a.stop()
        c.session.vote(true)
        Thread.sleep(2000)
        assertTrue("no acknowledgment while the creator is down", c.st.voteAcked != c.st.voteId)
        advance(20 * 60)
        a.restart()
        waitFor("C recorded after the restart") { c.st.voteAcked == c.st.voteId }
        // Down again as soon as its round is called: the outputs come in while it's away.
        waitFor("closing") { a.st.phase == Phase.CLOSING }
        val round = a.st.roundId
        a.stop()
        advance(5 * 60)
        a.restart()
        allSigning(a, b, c)
        assertEquals(round, a.st.roundId)
        assertEquals(3, a.st.outputs.size)
        b.sign()
        a.stop()
        advance(30 * 60)
        a.restart()
        a.sign(); c.sign()
        waitFor("broadcast") { listOf(a, b, c).all { it.st.txid != null } }
        assertEquals(1, chain.sent.size)
    }

    /**
     * Missing history never authorizes signing: C never answered the vote, and a closing that includes it
     * (here forged with the creator's own key, as a lying creator would) is ignored; so is a snapshot's
     * closing. C never reaches signing, never signs.
     */
    @Test fun missingHistoryNeverAuthorizesSigning() {
        val (a, b, c) = threeInAPool()
        b.session.requestClose(); waitFor("voting") { listOf(a, b, c).all { it.st.phase == Phase.VOTING } }
        val t = a.st.terms
        val ck = BigInteger(a.st.creatorSecret!!, 16)
        val forged = JSONObject().put("type", "closing").put("why", "agreed").put("seats", Protocol.seatsJson(a.st.seats))
            .put("deadline", now() + 600).put("round_id", "00".repeat(8)).put("vote_id", a.st.voteId).put("n", a.st.voteN).put("seq", a.st.voteSeq)
        fun send(o: JSONObject) {
            val ev = NostrEvent.sign(ck, Protocol.KIND_MSG, listOf(listOf("p", t.poolPub)),
                Nip44.encrypt(o.toString(), Nip44.conversationKey(ck, Hashes.hexToBytes(t.poolPub))))
            val r = RelayClient(Protocol.RELAY, { _, _ -> }); r.connect(); r.publish(ev); r.close()
        }
        send(forged)
        send(JSONObject().put("type", "state").put("n", 999).put("replay", JSONArray().put(JSONObject().put("at", now()).put("msg", forged))))
        Thread.sleep(3000)
        assertEquals("C never said yes: no round for it", Phase.VOTING, c.st.phase)
        assertNull(c.st.roundId)
        assertTrue(c.log.any { it.contains("never said yes") })
        assertTrue(c.st.sigs.isEmpty())
    }

    /** A v5 wallet doesn't list a v6 pool; a v6 wallet lists both, and a v5 pool's round runs as it always has. */
    @Test fun v5PoolsUnchanged() {
        val a = W("A"); val b = W("B")
        val t = a.open(async = false)
        assertEquals(Protocol.V3, t.version); assertNull(t.async)
        val listed = listPools().single { it.id == t.id }
        assertFalse(listed.v6)
        b.join(listed)
        waitFor("two seats") { a.st.seats.size == 2 && b.st.seats.size == 2 }
        b.session.requestClose(); waitFor("voting") { a.st.phase == Phase.VOTING }
        assertEquals(Protocol.VOTE_SECONDS, a.st.voteDeadline - now(), 5.0)
        a.session.vote(true)
        allSigning(a, b)
        assertTrue(relay.published.none { read(a, it)?.optString("type") in setOf("vote_ack", "state") })
        a.sign(); b.sign()
        waitFor("broadcast") { a.st.txid != null && b.st.txid != null }
    }

    private fun assertEquals(expected: Long, actual: Long, delta: Double) = org.junit.Assert.assertEquals(expected.toDouble(), actual.toDouble(), delta)
}
