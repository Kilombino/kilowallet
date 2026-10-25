package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.coinjoin.CoinjoinHub
import com.kilombino.pyblockwatch.coinjoin.CoinjoinTx
import com.kilombino.pyblockwatch.coinjoin.PoolSession
import com.kilombino.pyblockwatch.coinjoin.Protocol
import com.kilombino.pyblockwatch.coinjoin.RelayClient
import com.kilombino.pyblockwatch.coinjoin.Remix
import com.kilombino.pyblockwatch.coinjoin.Remixer
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Several seats of one wallet in a pool, and the perpetual coinjoin, for real: on relay.kilombino.com
 * and a BLAKE2b regtest node at 127.0.0.1:18999 (user/pass cj, wallet `w` funded), as the other
 * Kilojoin end-to-end tests. KILOJOIN_E2E_SEATS=1 runs the seats, KILOJOIN_E2E_PERP=1 the perpetual
 * coinjoin; KILOJOIN_E2E_TOR=host:port sends every relay connection through that Tor SOCKS port
 * (each seat on a circuit of its own).
 */
class CoinjoinSeatsE2ETest {
    private fun rpc(method: String, vararg params: Any): Any? {
        val c = URL("http://127.0.0.1:18999/wallet/w").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("cj:cj".toByteArray()))
        c.outputStream.write(JSONObject().put("method", method).put("params", JSONArray(params.toList())).toString().toByteArray())
        val body = (if (c.responseCode == 200) c.inputStream else c.errorStream).bufferedReader().readText()
        val o = JSONObject(body)
        if (!o.isNull("error")) throw IllegalStateException(o.getJSONObject("error").getString("message"))
        return o.opt("result")
    }

    private inner class Env(val name: String, val saved: MutableMap<String, String>? = null) : PoolSession.Env {
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun coinUnspent(coin: CoinjoinTx.Coin): Boolean {
            val o = rpc("gettxout", coin.txid, coin.vout, true) as? JSONObject ?: return false
            return Math.round(o.getDouble("value") * 1e8) == coin.value &&
                o.getJSONObject("scriptPubKey").getString("hex") == hex(coin.script)
        }
        override fun broadcast(rawHex: String): String = rpc("sendrawtransaction", rawHex) as String
        override fun confirmations(txid: String): Int? {
            val o = runCatching { rpc("getrawtransaction", txid, true) as JSONObject }.getOrNull() ?: return null
            return o.optInt("confirmations", 0)
        }
        override fun log(msg: String) = println("[$name] $msg")
        override fun save(state: PoolSession.State) {
            // As the app keeps it: the JSON must read back, and the last one is what a restart starts from.
            val j = state.toJson().toString(); PoolSession.State.parse(JSONObject(j)); saved?.put(state.key, j)
        }
        override fun event(e: PoolSession.Event) {
            if (e is PoolSession.Event.Changed) return
            events.add(e.javaClass.simpleName); println("[$name] ${e.javaClass.simpleName}")
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun key(seed: Int) = BigInteger.valueOf(1000L + seed)
    private fun pub(k: BigInteger) = Secp256k1.compress(Secp256k1.multiply(k, Secp256k1.G))
    private fun script(n: Long) = Address.scriptPubKey(pub(BigInteger.valueOf(n)), ScriptType.P2WPKH)

    private fun waitFor(what: String, seconds: Int = 180, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (!cond()) { require(System.currentTimeMillis() < end) { "timed out waiting for $what" }; Thread.sleep(500) }
        println("ok: $what")
    }

    private fun mine() { rpc("generatetoaddress", 1, rpc("getnewaddress") as String) }

    /** Pays [sats] to [spk] and gives back that coin (unconfirmed until the next block). */
    private fun fundScript(spk: ByteArray, pubkey: ByteArray, sats: Long): CoinjoinTx.Coin {
        val addr = com.kilombino.pyblockwatch.crypto.Bech32.encodeSegwit("bcrt", 0, spk.copyOfRange(2, spk.size))
        val txid = rpc("sendtoaddress", addr, sats / 1e8) as String
        val tx = rpc("getrawtransaction", txid, true) as JSONObject
        val vout = tx.getJSONArray("vout").let { v -> (0 until v.length()).first { v.getJSONObject(it).getJSONObject("scriptPubKey").getString("address") == addr } }
        return CoinjoinTx.Coin(txid, vout, sats, pubkey, ScriptType.P2WPKH)
    }
    private fun fund(seed: Int, sats: Long) = fundScript(Address.scriptPubKey(pub(key(seed)), ScriptType.P2WPKH), pub(key(seed)), sats)

    /** The pool as anyone sees it on the relay. */
    private fun listed(id: String): Protocol.Terms {
        val found = java.util.concurrent.CompletableFuture<Protocol.Terms>()
        val r = RelayClient(Protocol.RELAY, { _, ev -> Protocol.Terms.parse(ev)?.let { if (it.id == id && it.state == "open") found.complete(it) } })
        r.connect(); r.subscribe("p", listOf(JSONObject().put("kinds", JSONArray().put(Protocol.KIND_POOL)).put("#d", JSONArray().put(id))))
        try { return found.get(60, java.util.concurrent.TimeUnit.SECONDS) } finally { r.close() }
    }

    private fun setup(): Long {
        Protocol.NETWORK = "blake2b-regtest"
        System.getenv("KILOJOIN_E2E_TOR")?.let { RelayClient.socksOverride = java.net.InetSocketAddress(it.substringBefore(':'), it.substringAfter(':').toInt()) }
        return System.nanoTime() % 1_000_000
    }

    /** A member seat of [coin] (key of [seed]) in [t], running on its own. */
    private fun member(name: String, t: Protocol.Terms, coin: CoinjoinTx.Coin, seed: Int, salt: Long, seatId: String = "", joinAfter: Long = 0,
                       env: Env = Env(name)): PoolSession {
        val st = PoolSession.newState(t, false, null, listOf(coin), listOf(key(seed)), listOf(name),
            listOf(script(52_000L + seed + salt)), script(53_000L + seed + salt), seatId = seatId, joinAfter = joinAfter)
        env.save(st) // kept as soon as it is made, as the app does
        return PoolSession(env, st).also { it.autoKeys = listOf(key(seed)) }
    }

    // ------------------------------------------------------------------------------- seats

    /**
     * One wallet (W) takes two seats in a pool, each with its own coin and join key, the second after
     * its random wait; A (creator) and B see four ordinary members; the round is mined and W gets both
     * outputs and both changes.
     */
    @Test fun twoSeatsOfOneWallet() {
        System.getenv("KILOJOIN_E2E_SEATS") ?: return
        val salt = setup()
        val seed = (salt % 50_000).toInt() * 10 + 1
        val ca = fund(seed, 150_000); val cw1 = fund(seed + 1, 140_000); val cw2 = fund(seed + 2, 130_000); val cb = fund(seed + 3, 160_000)
        mine()
        val np = PoolSession.newPool(100_000, 2.0, 5, 3, minPeers = 4, v3 = true)
        val envA = Env("A")
        val a = PoolSession(envA, PoolSession.newState(np.terms, true, np.secret, listOf(ca), listOf(key(seed)), listOf("a"),
            listOf(script(50_000 + salt)), script(51_000 + salt), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv)).also { it.autoKeys = listOf(key(seed)) }
        a.start(); waitFor("A open") { a.state.phase == PoolSession.Phase.OPEN }
        val seen = listed(np.terms.id)
        val w1 = member("W1", seen, cw1, seed + 1, salt); w1.start()
        waitFor("W1 in") { w1.state.phase == PoolSession.Phase.OPEN && a.state.seats.size == 2 }
        // W's second seat: its own key, its own wait before the join goes out.
        val taken = setOf(w1.state.key)
        val seatId = CoinjoinHub.newSeatId(seen.id, taken)
        assertTrue(seatId.isNotEmpty())
        val w2 = member("W2", seen, cw2, seed + 2, salt, seatId, System.currentTimeMillis() / 1000 + 25)
        assertNotEquals(w1.state.key, w2.state.key)
        w2.start()
        val envB = Env("B")
        val b = member("B", seen, cb, seed + 3, salt, env = envB); b.start()
        waitFor("B in") { b.state.phase == PoolSession.Phase.OPEN && a.state.seats.size == 3 }
        // Still waiting its random moment.
        Thread.sleep(5_000)
        assertEquals(PoolSession.Phase.JOINING, w2.state.phase); assertEquals(3, a.state.seats.size)
        waitFor("W2 in after its wait", 90) { w2.state.phase == PoolSession.Phase.OPEN && a.state.seats.size == 4 }
        waitFor("all see four") { listOf(w1, w2, b).all { it.state.seats.size == 4 } }
        // To everyone else they are two members like any other: own join key, own proofs, real coins.
        val roster = b.state.seats
        assertEquals(4, roster.map { it.joinPub }.toSet().size)
        assertTrue(roster.all { it.proofs.size == it.coins.size })
        b.requestClose()
        waitFor("broadcast", 300) { listOf(a, w1, w2, b).all { it.state.txid != null } }
        val txid = a.state.txid!!
        val tx = rpc("getrawtransaction", txid, true) as JSONObject
        val outs = tx.getJSONArray("vout").let { v -> (0 until v.length()).map { v.getJSONObject(it) } }
            .map { it.getJSONObject("scriptPubKey").getString("hex") to Math.round(it.getDouble("value") * 1e8) }
        for (w in listOf(w1, w2)) {
            assertEquals(1, outs.count { it.first == hex(w.state.mixScript) && it.second == 100_000L })
            assertEquals(1, outs.count { it.first == hex(w.state.seat.changeScript!!) && it.second == w.state.seat.changeValue })
        }
        assertEquals(4, tx.getJSONArray("vin").length())
        mine()
        waitFor("confirmed", 90) { listOf(a, w1, w2, b).all { it.state.phase == PoolSession.Phase.CONFIRMED } }
        // B saw an ordinary round: nobody left, nothing cancelled, nothing found fake.
        assertFalse(envB.events.contains("Aborted")); assertFalse(envA.events.contains("Aborted"))
        assertEquals(4, b.state.round.size)
        println("two seats txid $txid")
        listOf(a, w1, w2, b).forEach { it.stop() }
    }

    /** One of W's two seats leaves; the other stays and the round goes on without the first. */
    @Test fun leavingOneSeatKeepsTheOther() {
        System.getenv("KILOJOIN_E2E_SEATS") ?: return
        val salt = setup()
        val seed = (salt % 50_000).toInt() * 10 + 2
        val ca = fund(seed, 150_000); val cw1 = fund(seed + 1, 140_000); val cw2 = fund(seed + 2, 130_000); val cb = fund(seed + 3, 160_000)
        mine()
        val np = PoolSession.newPool(100_000, 2.0, 5, 3, minPeers = 3, v3 = true)
        val a = PoolSession(Env("A"), PoolSession.newState(np.terms, true, np.secret, listOf(ca), listOf(key(seed)), listOf("a"),
            listOf(script(50_000 + salt)), script(51_000 + salt), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv)).also { it.autoKeys = listOf(key(seed)) }
        a.start(); waitFor("A open") { a.state.phase == PoolSession.Phase.OPEN }
        val seen = listed(np.terms.id)
        val w1 = member("W1", seen, cw1, seed + 1, salt); w1.start()
        val w2 = member("W2", seen, cw2, seed + 2, salt, CoinjoinHub.newSeatId(seen.id, setOf(seen.id)), System.currentTimeMillis() / 1000 + 20)
        w2.start()
        val b = member("B", seen, cb, seed + 3, salt); b.start()
        waitFor("four in", 120) { a.state.seats.size == 4 && listOf(w1, w2, b).all { it.state.phase == PoolSession.Phase.OPEN } }
        w2.leave()
        assertEquals(PoolSession.LEFT_BY_CHOICE, w2.state.reason)
        waitFor("W2 out, W1 still in") { a.state.seats.size == 3 && w1.state.seats.size == 3 && b.state.seats.size == 3 }
        assertEquals(PoolSession.Phase.OPEN, w1.state.phase)
        assertTrue(a.state.seats.none { s -> s.coins.any { it.outpoint == cw2.outpoint } })
        b.requestClose()
        waitFor("broadcast", 300) { listOf(a, w1, b).all { it.state.txid != null } }
        mine()
        waitFor("confirmed", 90) { listOf(a, w1, b).all { it.state.phase == PoolSession.Phase.CONFIRMED } }
        // The seat that left kept its coin.
        assertTrue(rpc("gettxout", cw2.txid, cw2.vout, true) is JSONObject)
        println("left one seat txid ${a.state.txid}")
        listOf(a, w1, w2, b).forEach { it.stop() }
    }

    /**
     * The app closes with W's two seats in a pool (the second still in its wait) and starts again from
     * what it saved: both seats come back under their own keys and the round completes.
     */
    @Test fun twoSeatsSurviveARestart() {
        System.getenv("KILOJOIN_E2E_SEATS") ?: return
        val salt = setup()
        val seed = (salt % 50_000).toInt() * 10 + 3
        val ca = fund(seed, 150_000); val cw1 = fund(seed + 1, 140_000); val cw2 = fund(seed + 2, 130_000); val cb = fund(seed + 3, 160_000)
        mine()
        val np = PoolSession.newPool(100_000, 2.0, 5, 3, minPeers = 4, v3 = true)
        val a = PoolSession(Env("A"), PoolSession.newState(np.terms, true, np.secret, listOf(ca), listOf(key(seed)), listOf("a"),
            listOf(script(50_000 + salt)), script(51_000 + salt), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv)).also { it.autoKeys = listOf(key(seed)) }
        a.start(); waitFor("A open") { a.state.phase == PoolSession.Phase.OPEN }
        val seen = listed(np.terms.id)
        val saved = ConcurrentHashMap<String, String>()
        val w1 = member("W1", seen, cw1, seed + 1, salt, env = Env("W1", saved)); w1.start()
        waitFor("W1 in") { w1.state.phase == PoolSession.Phase.OPEN }
        val w2 = member("W2", seen, cw2, seed + 2, salt, CoinjoinHub.newSeatId(seen.id, setOf(seen.id)),
            System.currentTimeMillis() / 1000 + 30, env = Env("W2", saved))
        w2.start()
        Thread.sleep(3_000)
        // Killed: both seats stop where they are, W2 still waiting to join.
        w1.stop(); w2.stop()
        val k2 = w2.state.key
        assertEquals(PoolSession.Phase.JOINING, PoolSession.State.parse(JSONObject(saved[k2]!!)).phase)
        val back = saved.values.map { PoolSession.State.parse(JSONObject(it)) }.associateBy { it.key }
        assertEquals(setOf(w1.state.key, k2), back.keys)
        val r1 = PoolSession(Env("W1'", saved), back[w1.state.key]!!).also { it.autoKeys = listOf(key(seed + 1)) }
        val r2 = PoolSession(Env("W2'", saved), back[k2]!!).also { it.autoKeys = listOf(key(seed + 2)) }
        r1.start(); r2.start()
        val b = member("B", seen, cb, seed + 3, salt); b.start()
        waitFor("four in after the restart", 120) { a.state.seats.size == 4 && listOf(r1, r2, b).all { it.state.phase == PoolSession.Phase.OPEN } }
        b.requestClose()
        waitFor("broadcast", 300) { listOf(a, r1, r2, b).all { it.state.txid != null } }
        mine()
        waitFor("confirmed", 90) { listOf(a, r1, r2, b).all { it.state.phase == PoolSession.Phase.CONFIRMED } }
        println("restart with two seats txid ${a.state.txid}")
        listOf(a, r1, r2, b).forEach { it.stop() }
    }

    // ------------------------------------------------------------------------------- perpetual

    /** The perpetual coinjoin's side, as [com.kilombino.pyblockwatch.coinjoin.Perpetual] runs it in the app, over the regtest node. */
    private inner class PerpEnv(val branch: Bip32Priv.ExtendedPrivKey, val saved: MutableMap<String, String>) : Remixer.Env {
        val sessions = ConcurrentHashMap<String, PoolSession>()
        @Volatile var estimate: Double? = 1.0
        @Volatile var next = 10
        /** Only the pools of this test: the relay may still list others for a while. */
        val allowed: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val opened = java.util.concurrent.CopyOnWriteArrayList<Protocol.Terms>()
        override fun delay() = 3L
        override fun seat(key: String) = sessions[key]?.state
        override fun outputOf(txid: String, script: ByteArray): Pair<Int, Long>? = runCatching {
            val v = (rpc("getrawtransaction", txid, true) as JSONObject).getJSONArray("vout")
            (0 until v.length()).map { v.getJSONObject(it) }.firstOrNull { it.getJSONObject("scriptPubKey").getString("hex") == hex(script) }
                ?.let { it.getInt("n") to Math.round(it.getDouble("value") * 1e8) }
        }.getOrNull()
        override fun unspent(r: Remix): Boolean? = runCatching { rpc("gettxout", r.txid!!, r.vout, true) is JSONObject }.getOrNull()
        override fun feeEstimate() = estimate
        override fun openPools() = CoinjoinHub.fetchPools().filter { it.id in allowed }
        override fun busyPools() = sessions.values.filter { !it.done }.map { it.state.poolId }.toSet()
        override fun log(msg: String) = println("[P] $msg")
        fun coinKey(i: Int) = Bip32Priv.deriveChild(branch, i)
        fun spk(i: Int) = Address.scriptPubKey(coinKey(i).publicKey(), ScriptType.P2WPKH)
        private fun coin(r: Remix) = CoinjoinTx.Coin(r.txid!!, r.vout, r.value, coinKey(r.index).publicKey())
        fun run(st: PoolSession.State, k: BigInteger): PoolSession =
            PoolSession(Env("P", saved).also { it.save(st) }, st).also { it.autoKeys = listOf(k); sessions[st.key] = it; it.start() }
        override fun join(r: Remix, t: Protocol.Terms): Remixer.NewSeat {
            val i = next++
            val st = PoolSession.newState(t, false, null, listOf(coin(r)), listOf(coinKey(r.index).key), listOf("p${r.index}"),
                listOf(spk(i)), spk(1000 + i), perpId = r.id)
            run(st, coinKey(r.index).key)
            return Remixer.NewSeat(st.key, i, spk(i))
        }
        override fun create(r: Remix, amount: Long, rate: Double): Remixer.NewSeat {
            val i = next++
            val np = PoolSession.newPool(amount, rate, r.maxPeers, r.hours, r.minPeers, v3 = true)
            val st = PoolSession.newState(np.terms, true, np.secret, listOf(coin(r)), listOf(coinKey(r.index).key), listOf("p${r.index}"),
                listOf(spk(i)), spk(1000 + i), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv, perpId = r.id)
            opened += np.terms
            run(st, coinKey(r.index).key)
            return Remixer.NewSeat(st.key, i, spk(i))
        }
    }

    /** Moves [r] on every second, as the coinjoin service does every minute. */
    private class Driver(@Volatile var remixer: Remixer, val r: Remix) {
        @Volatile var on = true
        val t = Thread { while (on) { synchronized(r) { runCatching { remixer.step(r) }.onFailure { it.printStackTrace() } }; Thread.sleep(1_000) } }.apply { isDaemon = true; start() }
        fun stop() { on = false; t.join(5_000) }
    }

    /**
     * The perpetual coinjoin over several rounds: it joins a pool (with change, which is left alone),
     * opens its own when none fits and opens another when that one is cancelled, joins a pool that fits
     * with no change in preference to one that doesn't, carries on after the app is closed and opened
     * mid-round, and stops when fees rise above its cap, when its coin is spent elsewhere, and below a
     * pool's minimum.
     */
    @Test fun perpetualRounds() {
        System.getenv("KILOJOIN_E2E_PERP") ?: return
        val salt = setup()
        val seed = (salt % 50_000).toInt() * 10 + 5
        val branch = Bip32Priv.derivePath(Bip32Priv.fromSeed(ByteArray(32) { (seed + it).toByte() }), "m/84'/0'/0'/0")
        val saved = ConcurrentHashMap<String, String>()
        var env = PerpEnv(branch, saved)
        val start = fundScript(env.spk(0), env.coinKey(0).publicKey(), 150_000)
        // Peers' coins.
        val peers = (1..12).associateWith { fund(seed + it, 200_000) }
        mine()
        val r = Remix("perp-$salt", ScriptType.P2WPKH, txid = start.txid, vout = start.vout, value = 150_000, index = 0, cap = 2.0, rate = 2.0,
            minPeers = 3, maxPeers = 5, hours = 3, state = Remix.State.WAITING, nextAt = Long.MAX_VALUE).also { it.coinScript = env.spk(0); it.startValue = 150_000 }
        var drive = Driver(Remixer(env), r)
        val others = mutableListOf<PoolSession>()
        fun creator(name: String, n: Int, amount: Long, rate: Double = 2.0): PoolSession {
            val np = PoolSession.newPool(amount, rate, 5, 3, minPeers = 3, v3 = true)
            return PoolSession(Env(name), PoolSession.newState(np.terms, true, np.secret, listOf(peers[n]!!), listOf(key(seed + n)), listOf(name),
                listOf(script(70_000L + n + salt)), script(71_000L + n + salt), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv))
                .also { it.autoKeys = listOf(key(seed + n)); it.start(); others += it }
        }
        fun join(name: String, n: Int, t: Protocol.Terms) = member(name, t, peers[n]!!, seed + n, salt).also { it.start(); others += it }
        fun seatOf() = env.sessions[r.seat ?: ""]
        fun round(closer: PoolSession, n: Int) {
            waitFor("round $n broadcast", 300) { seatOf()?.state?.txid != null }
            mine()
            waitFor("round $n confirmed, next coin", 120) { synchronized(r) { r.rounds == n && r.state == Remix.State.WAITING } }
            println("round $n: coin ${r.value} sats")
        }

        // 1. It joins A1's pool: its coin is bigger than the pool, so it gets change, not remixed.
        val a1 = creator("A1", 1, 100_000); waitFor("A1 open") { a1.state.phase == PoolSession.Phase.OPEN }
        env.allowed += a1.state.poolId
        synchronized(r) { r.nextAt = 0 } // the first look, now that A1's pool is listed
        waitFor("perpetual in A1's pool", 120) { seatOf()?.state?.let { it.poolId == a1.state.poolId && it.phase == PoolSession.Phase.OPEN } == true }
        val firstSeat = seatOf()!!.state
        assertTrue(firstSeat.seat.changeValue > 0)
        val b1 = join("B1", 2, listed(a1.state.poolId))
        waitFor("three in A1") { a1.state.seats.size == 3 && b1.state.phase == PoolSession.Phase.OPEN && b1.state.seats.size == 3 }
        b1.requestClose()
        round(b1, 1)
        assertEquals(100_000L, r.value)
        // Its change is there, unspent, and not what it goes on with.
        val ch = env.outputOf(a1.state.txid!!, firstSeat.seat.changeScript!!)!!
        assertEquals(firstSeat.seat.changeValue, ch.second)
        assertTrue(rpc("gettxout", a1.state.txid!!, ch.first, true) is JSONObject)
        assertNotEquals(ch.first, r.vout)

        // 2. No pool fits: it opens one with the whole coin; that one is cancelled, and it opens another.
        waitFor("its own pool", 120) { seatOf()?.state?.let { it.creator && it.phase == PoolSession.Phase.OPEN } == true }
        val x = seatOf()!!
        assertEquals(0L, x.state.seat.changeValue)
        assertEquals(CoinjoinTx.noChangeAmount(100_000, 1, 2.0, ScriptType.P2WPKH, 1), x.state.terms.amount)
        x.leave() // cancelled (as when it expires with nobody in)
        waitFor("another pool of its own", 120) { seatOf()?.state?.let { it.creator && it.phase == PoolSession.Phase.OPEN && it.poolId != x.state.poolId } == true }
        val y = seatOf()!!
        assertEquals(x.state.terms.amount, y.state.terms.amount)
        val yt = listed(y.state.poolId)
        val b2 = join("B2", 3, yt); val c2 = join("C2", 4, yt)
        waitFor("three in its pool") { y.state.seats.size == 3 && b2.state.phase == PoolSession.Phase.OPEN && c2.state.phase == PoolSession.Phase.OPEN && c2.state.seats.size == 3 }
        c2.requestClose()
        round(c2, 2)
        assertEquals(y.state.terms.amount, r.value)

        // 3. Two pools open: one that takes the whole coin with no change, one that would leave change: it picks the first.
        val exact = CoinjoinTx.noChangeAmount(r.value, 1, 2.0, ScriptType.P2WPKH, 1)!!
        val a3 = creator("A3", 5, exact); val d3 = creator("D3", 6, 50_000)
        waitFor("A3 and D3 open") { a3.state.phase == PoolSession.Phase.OPEN && d3.state.phase == PoolSession.Phase.OPEN }
        env.allowed += a3.state.poolId; env.allowed += d3.state.poolId
        waitFor("perpetual in A3's pool", 120) { seatOf()?.state?.let { it.poolId == a3.state.poolId && it.phase == PoolSession.Phase.OPEN } == true }
        assertEquals(0L, seatOf()!!.state.seat.changeValue)
        val b3 = join("B3", 7, listed(a3.state.poolId))
        waitFor("three in A3") { a3.state.seats.size == 3 && b3.state.phase == PoolSession.Phase.OPEN && b3.state.seats.size == 3 }
        b3.requestClose()
        round(b3, 3)
        d3.leave()
        env.allowed.clear()

        // 4. Closed and opened again mid-round: from what was saved, it carries on.
        waitFor("its pool for round 4", 120) { seatOf()?.state?.let { it.creator && it.phase == PoolSession.Phase.OPEN } == true }
        val z = seatOf()!!
        val zt = listed(z.state.poolId)
        val b4 = join("B4", 8, zt)
        waitFor("B4 in") { z.state.seats.size == 2 && b4.state.phase == PoolSession.Phase.OPEN }
        drive.stop(); env.sessions.values.forEach { it.stop() }
        val remixSaved = Remix.listJson(listOf(r))
        Thread.sleep(3_000)
        val r2 = Remix.parseList(remixSaved).single()
        env = PerpEnv(branch, saved).also { it.next = 100 }
        val zBack = PoolSession.State.parse(JSONObject(saved[r2.seat!!]!!))
        env.run(zBack, env.coinKey(r2.index).key)
        synchronized(r) { r.state = r2.state; r.seat = r2.seat }
        drive = Driver(Remixer(env), r)
        val c4 = join("C4", 9, zt)
        waitFor("three in after the restart") { env.sessions[r.seat!!]!!.state.seats.size == 3 && c4.state.phase == PoolSession.Phase.OPEN && c4.state.seats.size == 3 }
        c4.requestClose()
        round(c4, 4)

        // 5. Fees above the cap: it stops. Raised cap: on again, at the rate the chain asks now; then off mid-round.
        env.estimate = 5.0
        waitFor("stopped over the cap") { synchronized(r) { r.state == Remix.State.STOPPED } }
        assertEquals(Remix.overCap(2.0), r.reason)
        synchronized(r) { r.cap = 6.0; r.state = Remix.State.WAITING; r.nextAt = 0; r.reason = "" }
        waitFor("a pool at 5 sat/vB", 120) { seatOf()?.state?.let { it.creator && it.phase == PoolSession.Phase.OPEN } == true }
        assertEquals(5.0, seatOf()!!.state.terms.feeRate, 0.0)
        synchronized(r) { r.state = Remix.State.STOPPED; r.reason = Remix.OFF }
        seatOf()!!.leave()
        waitFor("off, seat gone") { synchronized(r) { r.seat == null } }
        assertEquals(Remix.State.STOPPED, r.state); assertEquals(4, r.rounds)

        // 6. On again, but its coin is spent some other way first: it stops.
        val spendTo = script(99_000 + salt)
        val input = com.kilombino.pyblockwatch.crypto.TxBuilder.Input(r.txid!!, r.vout, r.value, env.coinKey(r.index).key,
            env.coinKey(r.index).publicKey(), 0xfffffffdL, ScriptType.P2WPKH)
        rpc("sendrawtransaction", com.kilombino.pyblockwatch.crypto.TxBuilder.build(listOf(input),
            listOf(com.kilombino.pyblockwatch.crypto.TxBuilder.Output(spendTo, r.value - 500)), unified = true, grindLowR = true).rawHex)
        env.estimate = 1.0
        synchronized(r) { r.state = Remix.State.WAITING; r.nextAt = 0; r.reason = "" }
        waitFor("stopped, coin moved") { synchronized(r) { r.state == Remix.State.STOPPED } }
        assertEquals(Remix.MOVED, r.reason)
        drive.stop()

        // 7. A coin too small for a pool of 10 000: it stops at once.
        val small = fundScript(env.spk(500), env.coinKey(500).publicKey(), 10_150); mine()
        val tiny = Remix("tiny-$salt", ScriptType.P2WPKH, txid = small.txid, vout = small.vout, value = 10_150, index = 500, cap = 2.0, rate = 2.0,
            minPeers = 3, maxPeers = 5, hours = 3, state = Remix.State.WAITING).also { it.coinScript = env.spk(500) }
        Remixer(env).step(tiny)
        assertEquals(Remix.State.STOPPED, tiny.state); assertEquals(Remix.BELOW_MINIMUM, tiny.reason)
        mine()
        others.forEach { runCatching { it.stop() } }; env.sessions.values.forEach { it.stop() }
        println("perpetual: 4 rounds, 150000 -> ${r.value - 0} sats before it was spent")
    }
}
