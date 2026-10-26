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
        /** This wallet's seats, as the app's hub tells a session ([PoolSession.Env.ownSeats]); none by default. */
        @Volatile var mine: (() -> Set<String>)? = null
        override fun ownSeats(poolId: String): Set<String> = mine?.invoke() ?: emptySet()
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
        /** Runs first on every event (a test can put the phone to sleep here). */
        @Volatile var hook: ((PoolSession.Event) -> Unit)? = null
        override fun event(e: PoolSession.Event) {
            hook?.invoke(e)
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

    /**
     * Another seat with a perfect coin (made for it, so it joins with no change): W's second seat, its
     * own key and its own wait, as the perfect coin's join does once it confirms; the round is mined.
     */
    @Test fun perfectCoinAsAnotherSeat() {
        System.getenv("KILOJOIN_E2E_SEATS") ?: return
        val salt = setup()
        val seed = (salt % 50_000).toInt() * 10 + 4
        val exact = CoinjoinTx.minimumCoin(100_000, 2.0)
        val ca = fund(seed, 150_000); val cw1 = fund(seed + 1, 140_000); val cw2 = fund(seed + 2, exact); val cb = fund(seed + 3, 160_000)
        mine()
        val np = PoolSession.newPool(100_000, 2.0, 5, 3, minPeers = 4, v3 = true)
        val a = PoolSession(Env("A"), PoolSession.newState(np.terms, true, np.secret, listOf(ca), listOf(key(seed)), listOf("a"),
            listOf(script(50_000 + salt)), script(51_000 + salt), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv)).also { it.autoKeys = listOf(key(seed)) }
        a.start(); waitFor("A open") { a.state.phase == PoolSession.Phase.OPEN }
        val seen = listed(np.terms.id)
        val w1 = member("W1", seen, cw1, seed + 1, salt); w1.start()
        val b = member("B", seen, cb, seed + 3, salt); b.start()
        waitFor("W1 and B in") { a.state.seats.size == 3 }
        val w2 = member("W2", seen, cw2, seed + 2, salt, CoinjoinHub.newSeatId(seen.id, setOf(seen.id)), System.currentTimeMillis() / 1000 + 20)
        assertEquals(0L, w2.state.seat.changeValue) // the perfect coin: no change
        w2.start()
        waitFor("W2 in after its wait", 90) { w2.state.phase == PoolSession.Phase.OPEN && listOf(a, w1, w2, b).all { it.state.seats.size == 4 } }
        b.requestClose()
        waitFor("broadcast", 300) { listOf(a, w1, w2, b).all { it.state.txid != null } }
        val tx = rpc("getrawtransaction", a.state.txid!!, true) as JSONObject
        val outs = tx.getJSONArray("vout").let { v -> (0 until v.length()).map { v.getJSONObject(it) } }
            .map { it.getJSONObject("scriptPubKey").getString("hex") to Math.round(it.getDouble("value") * 1e8) }
        assertEquals(1, outs.count { it.first == hex(w2.state.mixScript) && it.second == 100_000L })
        assertEquals(4 + 3, outs.size) // four mixed outputs and three changes: none for the perfect coin
        mine()
        waitFor("confirmed", 90) { listOf(a, w1, w2, b).all { it.state.phase == PoolSession.Phase.CONFIRMED } }
        println("perfect coin as another seat txid ${a.state.txid}")
        listOf(a, w1, w2, b).forEach { it.stop() }
    }

    /**
     * The creator and its own extra seat are one person. minPeers 2: alone with its two seats the pool
     * can't be asked to close; with another wallet's seat that stays silent in the vote, the two yes of
     * its own seats don't close it (the silent seat is left out and the pool reopens); with another
     * wallet's seat that says yes, it closes and is mined.
     */
    @Test fun ownSeatsAreOnePerson() {
        System.getenv("KILOJOIN_E2E_SEATS") ?: return
        val salt = setup()
        val seed = (salt % 50_000).toInt() * 10 + 5
        val ca = fund(seed, 150_000); val ca2 = fund(seed + 1, 140_000); val cb = fund(seed + 2, 130_000); val cc = fund(seed + 3, 160_000)
        mine()
        val np = PoolSession.newPool(100_000, 2.0, 5, 3, minPeers = 2, v3 = true)
        val wallet = java.util.concurrent.CopyOnWriteArrayList<PoolSession>()
        val own = { wallet.mapNotNull { it.state.token?.let { t -> Protocol.tokenHash(t) } }.toSet() }
        val envA = Env("A").also { it.mine = own }
        val a = PoolSession(envA, PoolSession.newState(np.terms, true, np.secret, listOf(ca), listOf(key(seed)), listOf("a"),
            listOf(script(50_000 + salt)), script(51_000 + salt), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv)).also { it.autoKeys = listOf(key(seed)) }
        wallet += a
        a.start(); waitFor("A open") { a.state.phase == PoolSession.Phase.OPEN }
        val seen = listed(np.terms.id)
        // The creator's own extra seat ("+ JOIN WITH ANOTHER UTXO"), with a destination of its own.
        val a2 = member("A2", seen, ca2, seed + 1, salt, CoinjoinHub.newSeatId(seen.id, setOf(seen.id)), System.currentTimeMillis() / 1000 + 3,
            env = Env("A2").also { it.mine = own })
        wallet += a2
        a2.start()
        waitFor("A2 in") { a2.state.phase == PoolSession.Phase.OPEN && a.state.seats.size == 2 && a2.state.seats.size == 2 }
        assertEquals(1, a.people()); assertEquals(1, a2.people())
        // Two seats, one person: nobody of this wallet can ask to close.
        assertTrue(runCatching { a.requestClose() }.isFailure)
        assertTrue(runCatching { a2.requestClose() }.isFailure)
        // B, another wallet, joins and stays silent in the vote.
        val envB = Env("B")
        val b = member("B", seen, cb, seed + 2, salt, env = envB).also { it.autoKeys = null }
        b.start()
        waitFor("B in") { b.state.phase == PoolSession.Phase.OPEN && a.state.seats.size == 3 && a2.state.seats.size == 3 }
        assertEquals(2, a.people())
        a2.requestClose()
        waitFor("vote open") { a.state.phase == PoolSession.Phase.VOTING && b.state.phase == PoolSession.Phase.VOTING }
        // The vote runs out with only this wallet's two yes: B is left out and the pool reopens, no round.
        waitFor("vote over without a round", (Protocol.VOTE_SECONDS + 120).toInt()) {
            a.state.phase == PoolSession.Phase.OPEN && a.state.seats.size == 2 && a2.state.phase == PoolSession.Phase.OPEN }
        assertTrue(listOf(a, a2).none { it.state.round.isNotEmpty() || it.state.txid != null })
        assertFalse(envA.events.contains("Closing"))
        waitFor("B out") { b.state.phase == PoolSession.Phase.ABORTED }
        assertTrue(rpc("gettxout", ca.txid, ca.vout, true) is JSONObject)
        // C, another wallet, joins and asks to close: two people, the round closes and is mined.
        val c = member("C", seen, cc, seed + 3, salt); c.start()
        waitFor("C in") { c.state.phase == PoolSession.Phase.OPEN && a.state.seats.size == 3 && a2.state.seats.size == 3 }
        c.requestClose()
        waitFor("broadcast", 300) { listOf(a, a2, c).all { it.state.txid != null } }
        val tx = rpc("getrawtransaction", a.state.txid!!, true) as JSONObject
        assertEquals(3, tx.getJSONArray("vin").length())
        mine()
        waitFor("confirmed", 90) { listOf(a, a2, c).all { it.state.phase == PoolSession.Phase.CONFIRMED } }
        println("own seats as one person txid ${a.state.txid}")
        listOf(a, a2, b, c).forEach { it.stop() }
    }

    /**
     * A member that falls asleep as the round closes (an app suspended by its phone) never asks for its
     * blind signature: when the outputs' time is over the creator closes the round again without it, the
     * others hand their outputs in again, and the round is mined. Its coin stays where it was.
     */
    @Test fun aSeatAsleepAtClosingIsLeftOut() {
        System.getenv("KILOJOIN_E2E_SEATS") ?: return
        val salt = setup()
        val seed = (salt % 50_000).toInt() * 10 + 6
        val ca = fund(seed, 150_000); val cb = fund(seed + 1, 140_000); val cc = fund(seed + 2, 130_000); val cd = fund(seed + 3, 160_000)
        mine()
        val np = PoolSession.newPool(100_000, 2.0, 5, 3, minPeers = 2, v3 = true)
        val envA = Env("A")
        val a = PoolSession(envA, PoolSession.newState(np.terms, true, np.secret, listOf(ca), listOf(key(seed)), listOf("a"),
            listOf(script(50_000 + salt)), script(51_000 + salt), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv)).also { it.autoKeys = listOf(key(seed)) }
        a.start(); waitFor("A open") { a.state.phase == PoolSession.Phase.OPEN }
        val seen = listed(np.terms.id)
        val b = member("B", seen, cb, seed + 1, salt); val c = member("C", seen, cc, seed + 2, salt)
        val envD = Env("D")
        val d = member("D", seen, cd, seed + 3, salt, env = envD)
        // D's phone puts the app to sleep the moment the round closes: no blind request ever goes out.
        envD.hook = { e -> if (e is PoolSession.Event.Closing) { d.stop(); throw IllegalStateException("asleep") } }
        listOf(b, c, d).forEach { it.start() }
        waitFor("four in", 120) { a.state.seats.size == 4 && listOf(b, c, d).all { it.state.phase == PoolSession.Phase.OPEN && it.state.seats.size == 4 } }
        b.requestClose()
        waitFor("closing") { listOf(a, b, c).all { it.state.phase == PoolSession.Phase.CLOSING } && d.state.phase == PoolSession.Phase.CLOSING }
        assertEquals(4, a.state.round.size)
        waitFor("closed again without D", (Protocol.OUTPUTS_SECONDS + 120).toInt()) { a.state.closeSeq == 1 && a.state.round.size == 3 }
        waitFor("broadcast", 300) { listOf(a, b, c).all { it.state.txid != null } }
        assertTrue(listOf(b, c).all { it.state.closeSeq == 1 })
        val tx = rpc("getrawtransaction", a.state.txid!!, true) as JSONObject
        assertEquals(3, tx.getJSONArray("vin").length())
        assertTrue(rpc("gettxout", cd.txid, cd.vout, true) is JSONObject)
        mine()
        waitFor("confirmed", 90) { listOf(a, b, c).all { it.state.phase == PoolSession.Phase.CONFIRMED } }
        assertFalse(envA.events.contains("Aborted"))
        // Saved and read back with its second closing.
        assertEquals(1, PoolSession.State.parse(JSONObject(a.state.toJson().toString())).closeSeq)
        println("asleep seat left out txid ${a.state.txid}")
        listOf(a, b, c).forEach { it.stop() }
    }

    // ------------------------------------------------------------------------------- perpetual

    /** The perpetual coinjoin's side, as [com.kilombino.pyblockwatch.coinjoin.Perpetual] runs it in the app, over the regtest node. */
    private inner class PerpEnv(val branch: Bip32Priv.ExtendedPrivKey, val saved: MutableMap<String, String>,
                                val changeBranch: Bip32Priv.ExtendedPrivKey? = null) : Remixer.Env {
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
        override fun firstUnspent(r: Remix): Boolean? = runCatching {
            r.first!!.coins.all { rpc("gettxout", it.txid, it.vout, true) is JSONObject }
        }.getOrNull()
        /** As the app does it: the seat rebuilt by [com.kilombino.pyblockwatch.coinjoin.Perpetual.firstPick], the same pool opened again. */
        override fun reopen(r: Remix): Remixer.NewSeat {
            val f = r.first!!
            val p = com.kilombino.pyblockwatch.coinjoin.Perpetual.firstPick(f, r.type) { if (it == 0) branch else changeBranch }
            val np = PoolSession.newPool(f.amount, f.rate, f.maxPeers, f.hours, f.minPeers, maxInputs = f.maxInputs, v3 = true)
            val st = PoolSession.newState(np.terms, true, np.secret, p.txCoins(), p.coins.map { it.coinKey.key }, p.coins.map { it.coinPath },
                p.mixScripts, p.changeScript, creatorSecret = np.creatorSecret, blindPriv = np.blindPriv, perpId = r.id)
            opened += np.terms
            PoolSession(Env("P", saved).also { it.save(st) }, st).also { it.autoKeys = p.coins.map { c -> c.coinKey.key }; sessions[st.key] = it; it.start() }
            return Remixer.NewSeat(st.key, r.seatIndex, f.mixScript)
        }
    }

    /**
     * The first pool of a perpetual coinjoin expires with nobody: the same pool opens again (same amount, fee
     * and people, same coins, same mixed-output and change addresses), someone joins it, the round is mined,
     * and the mixed coin goes on to its next pool.
     */
    @Test fun perpetualFirstPoolOpensAgain() {
        System.getenv("KILOJOIN_E2E_PERP") ?: return
        val salt = setup()
        val seed = (salt % 50_000).toInt() * 10 + 6
        val master = Bip32Priv.fromSeed(ByteArray(32) { (seed + it).toByte() })
        val branch = Bip32Priv.derivePath(master, "m/84'/0'/0'/0"); val changeBranch = Bip32Priv.derivePath(master, "m/84'/0'/0'/1")
        val saved = ConcurrentHashMap<String, String>()
        val env = PerpEnv(branch, saved, changeBranch)
        // Its coin is on a change address: the change branch's key opens the pool again.
        val ck = Bip32Priv.deriveChild(changeBranch, 3)
        val coin = fundScript(Address.scriptPubKey(ck.publicKey(), ScriptType.P2WPKH), ck.publicKey(), 150_000)
        val peer = fund(seed + 1, 200_000)
        mine()
        // The first pool, as the app opens it, expiring in about a minute.
        val np0 = PoolSession.newPool(100_000, 2.0, 5, 3, 2, maxInputs = 1, v3 = true)
        val terms0 = np0.terms.copy(expiresAt = System.currentTimeMillis() / 1000 + 50)
        val st0 = PoolSession.newState(terms0, true, np0.secret, listOf(coin), listOf(ck.key), listOf("m/84'/0'/0'/1/3"),
            listOf(env.spk(10)), Address.scriptPubKey(Bip32Priv.deriveChild(changeBranch, 4).publicKey(), ScriptType.P2WPKH),
            creatorSecret = np0.creatorSecret, blindPriv = np0.blindPriv, perpId = "perp1-$salt")
        val s0 = env.run(st0, ck.key)
        waitFor("first pool open") { s0.state.phase == PoolSession.Phase.OPEN }
        val first = com.kilombino.pyblockwatch.coinjoin.FirstPool.of(st0, 3)
        val r = Remix("perp1-$salt", ScriptType.P2WPKH, cap = 2.0, rate = 2.0, minPeers = 2, maxPeers = 5, hours = 3,
            seat = st0.key, seatIndex = 10, seatScript = env.spk(10), first = first)
        val drive = Driver(Remixer(env), r)
        fun seatOf() = env.sessions[r.seat ?: ""]
        // Nobody comes: it expires, and the very same pool opens again.
        waitFor("first pool expired", 120) { s0.state.phase == PoolSession.Phase.ABORTED }
        waitFor("the same pool opened again", 120) { seatOf()?.state?.let { it.poolId != s0.state.poolId && it.phase == PoolSession.Phase.OPEN } == true }
        val again = seatOf()!!.state
        assertEquals(terms0.amount, again.terms.amount); assertEquals(terms0.feeRate, again.terms.feeRate, 0.0)
        assertEquals(terms0.minPeers, again.terms.minPeers); assertEquals(terms0.maxPeers, again.terms.maxPeers)
        assertEquals(st0.seat.coins.map { it.outpoint }, again.seat.coins.map { it.outpoint })
        assertTrue(st0.mixScript.contentEquals(again.mixScript))
        assertTrue(st0.seat.changeScript!!.contentEquals(again.seat.changeScript!!)); assertEquals(st0.seat.changeValue, again.seat.changeValue)
        assertEquals(Remix.State.SEATED, r.state); assertTrue(r.first != null)
        // Someone joins it: the round is mined, and the mixed coin goes on.
        val b = member("B", listed(again.poolId), peer, seed + 1, salt).also { it.start() }
        waitFor("B in") { again.seats.size == 2 && b.state.phase == PoolSession.Phase.OPEN && b.state.seats.size == 2 }
        b.requestClose()
        waitFor("broadcast", 300) { seatOf()?.state?.txid != null }
        mine()
        waitFor("its first round done", 120) { synchronized(r) { r.rounds == 1 && r.state == Remix.State.WAITING } }
        assertEquals(null, r.first); assertEquals(100_000L, r.value); assertEquals(10, r.index)
        waitFor("its next pool", 120) { seatOf()?.state?.let { it.creator && it.phase == PoolSession.Phase.OPEN && it.poolId != again.poolId } == true }
        assertTrue(seatOf()!!.state.seat.coins.single().outpoint == "${r.txid}:${r.vout}")
        drive.stop(); seatOf()?.leave()
        mine()
        env.sessions.values.forEach { it.stop() }; b.stop()
        println("perpetual: first pool opened again after it expired, round mined, then its next pool")
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

        // 5. Fees up on the chain: it carries on at the fee chosen. The fee changed on its card: its next pool at
        // that fee, and a pool at another fee is not joined. Then turned off mid-round.
        env.estimate = 5.0
        waitFor("its pool at the fee chosen", 120) { seatOf()?.state?.let { it.creator && it.phase == PoolSession.Phase.OPEN } == true }
        assertEquals(2.0, seatOf()!!.state.terms.feeRate, 0.0)
        val e5 = creator("E5", 10, 50_000, rate = 4.0); waitFor("E5 open") { e5.state.phase == PoolSession.Phase.OPEN }
        env.allowed += e5.state.poolId
        val p5 = seatOf()!!
        synchronized(r) { r.cap = 5.0 }
        p5.leave()
        waitFor("a pool at 5 sat/vB", 120) { seatOf()?.state?.let { it.creator && it.phase == PoolSession.Phase.OPEN && it.poolId != p5.state.poolId } == true }
        assertEquals(5.0, seatOf()!!.state.terms.feeRate, 0.0)
        assertNotEquals(e5.state.poolId, seatOf()!!.state.poolId)
        synchronized(r) { r.state = Remix.State.STOPPED; r.reason = Remix.OFF }
        seatOf()!!.leave()
        waitFor("off, seat gone") { synchronized(r) { r.seat == null } }
        assertEquals(Remix.State.STOPPED, r.state); assertEquals(4, r.rounds)
        e5.leave(); env.allowed.clear()

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
