package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.coinjoin.CoinjoinTx
import com.kilombino.pyblockwatch.coinjoin.PoolSession
import com.kilombino.pyblockwatch.coinjoin.Protocol
import com.kilombino.pyblockwatch.coinjoin.RelayClient
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * The whole protocol, for real: two wallets meet on relay.kilombino.com, one opens a pool, the
 * other joins, asks to close, both sign, and the coinjoin is broadcast to and mined on a
 * BLAKE2b regtest node. Runs only with KILOJOIN_E2E=<coins file> (two "seed txid vout value"
 * lines funded on the regtest at 127.0.0.1:18999, user/pass cj).
 */
class CoinjoinE2ETest {
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

    private inner class Env(val name: String) : PoolSession.Env {
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        /** The chain server out of reach, as when a phone can't get to its Electrum server. */
        @Volatile var down = false
        /** Coins this wallet still takes for unspent, as a creator from before 1.3.1 would. */
        val stale: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        override fun coinUnspent(coin: CoinjoinTx.Coin): Boolean {
            if (down) throw java.io.IOException("server out of reach")
            if (coin.outpoint in stale) return true
            val o = rpc("gettxout", coin.txid, coin.vout, true) as? JSONObject ?: return false
            // The coin's own script, as the wallet's RPC path checks it: a coin claimed under the
            // wrong address type does not match.
            return Math.round(o.getDouble("value") * 1e8) == coin.value &&
                o.getJSONObject("scriptPubKey").getString("hex") == coin.script.joinToString("") { "%02x".format(it) }
        }
        override fun broadcast(rawHex: String): String = rpc("sendrawtransaction", rawHex) as String
        override fun confirmations(txid: String): Int? {
            val o = rpc("gettxout", txid, 0, true) as? JSONObject ?: return null
            return o.getInt("confirmations")
        }
        override fun log(msg: String) = println("[$name] $msg")
        override fun save(state: PoolSession.State) { PoolSession.State.parse(state.toJson()) } // must round-trip
        override fun event(e: PoolSession.Event) {
            if (e is PoolSession.Event.Changed) return
            events.add(e.javaClass.simpleName); println("[$name] ${e.javaClass.simpleName}")
        }
    }

    private fun key(seed: Int) = BigInteger.valueOf(1000L + seed)
    private fun pub(k: BigInteger) = Secp256k1.compress(Secp256k1.multiply(k, Secp256k1.G))
    private fun script(n: Long) = Address.scriptPubKey(pub(BigInteger.valueOf(n)), ScriptType.P2WPKH)
    private fun trScript(n: Long) = Address.scriptPubKey(pub(BigInteger.valueOf(n)), ScriptType.P2TR)

    /**
     * Kilojoin v2 for real: a Taproot pool where A brings two coins and B one; C tries with a
     * native SegWit coin and is refused; the round is signed (Schnorr, unified sighash), broadcast
     * and mined on the BLAKE2b regtest. KILOJOIN_E2E_V2=<coins file>: "seed txid vout value tr|wpkh"
     * lines, seeds 1 and 2 (A, tr), 3 (B, tr), 4 (C, wpkh).
     */
    @Test fun taprootSeveralCoins() {
        val path = System.getenv("KILOJOIN_E2E_V2") ?: return
        Protocol.NETWORK = "blake2b-regtest"
        val rows = java.io.File(path).readLines().filter { it.isNotBlank() }.map { it.trim().split(" ") }
        fun c(seed: Int): CoinjoinTx.Coin {
            val (s0, txid, vout, value, kind) = rows.first { it[0] == seed.toString() }
            return CoinjoinTx.Coin(txid, vout.toInt(), value.toLong(), pub(key(s0.toInt())),
                if (kind == "tr") ScriptType.P2TR else ScriptType.P2WPKH)
        }
        val salt = System.nanoTime() % 1_000_000
        val (terms, secret) = PoolSession.newPool(100_000, 2.0, 5, 1, script = ScriptType.P2TR, maxInputs = 3)
        val a = PoolSession(Env("A"), PoolSession.newState(terms, true, secret, listOf(c(1), c(2)), listOf(key(1), key(2)),
            listOf("a1", "a2"), trScript(90_000 + salt), trScript(91_000 + salt)))
        a.start(); waitFor("A open") { a.state.phase == PoolSession.Phase.OPEN }

        val found = java.util.concurrent.CompletableFuture<Protocol.Terms>()
        val r = RelayClient(Protocol.RELAY, { _, ev -> Protocol.Terms.parse(ev)?.let { if (it.id == terms.id) found.complete(it) } })
        r.connect(); r.subscribe("p", listOf(JSONObject().put("kinds", JSONArray().put(Protocol.KIND_POOL)).put("#d", JSONArray().put(terms.id))))
        val seen = found.get(30, java.util.concurrent.TimeUnit.SECONDS); r.close()
        assertEquals(ScriptType.P2TR, seen.script); assertEquals(3, seen.maxInputs)

        // C claims its SegWit coin as if it were Taproot: its script does not match the chain.
        val wrong = c(4).copy(type = ScriptType.P2TR)
        val cc = PoolSession(Env("C"), PoolSession.newState(seen, false, null, listOf(wrong), listOf(key(4)), listOf("c"),
            trScript(92_000 + salt), trScript(93_000 + salt)))
        cc.start(); waitFor("C refused") { cc.state.phase == PoolSession.Phase.REJECTED }
        println("C: ${cc.state.reason}"); cc.stop()

        val b = PoolSession(Env("B"), PoolSession.newState(seen, false, null, listOf(c(3)), listOf(key(3)), listOf("b"),
            trScript(94_000 + salt), trScript(95_000 + salt)))
        b.start(); waitFor("B welcomed") { b.state.phase == PoolSession.Phase.OPEN && b.state.seats.size == 2 }
        b.requestClose()
        waitFor("A asked") { a.state.phase == PoolSession.Phase.VOTING }
        a.vote(true)
        waitFor("both signing", 180) { a.state.phase == PoolSession.Phase.SIGNING && b.state.phase == PoolSession.Phase.SIGNING }
        assertEquals(3, a.plan()!!.coins.size)
        a.sign(listOf(key(1), key(2))); b.sign(key(3))
        waitFor("broadcast") { a.state.txid != null && b.state.txid != null }
        rpc("generatetoaddress", 1, rpc("getnewaddress") as String)
        waitFor("confirmed", 60) { a.state.phase == PoolSession.Phase.CONFIRMED && b.state.phase == PoolSession.Phase.CONFIRMED }
        println("v2 taproot txid ${a.state.txid}")
        a.stop(); b.stop()
    }

    /**
     * "On its own": B asks to close and from then on nobody touches anything: A accepts by
     * itself and both sign by themselves once their checks pass; the round is broadcast and
     * mined. KILOJOIN_E2E_AUTO=<coins file> with two funded native SegWit coins (seeds any).
     */
    @Test fun runsOnItsOwn() {
        val path = System.getenv("KILOJOIN_E2E_AUTO") ?: return
        Protocol.NETWORK = "blake2b-regtest"
        val rows = java.io.File(path).readLines().filter { it.isNotBlank() }.map { it.trim().split(" ") }
        val coins = rows.map { (seed, txid, vout, value) -> seed.toInt() to CoinjoinTx.Coin(txid, vout.toInt(), value.toLong(), pub(key(seed.toInt()))) }
        val (seedA, coinA) = coins[0]; val (seedB, coinB) = coins[1]
        val salt = System.nanoTime() % 1_000_000

        val (terms, secret) = PoolSession.newPool(100_000, 2.0, 5, 1)
        val envA = Env("A"); val envB = Env("B")
        val a = PoolSession(envA, PoolSession.newState(terms, true, secret, coinA, key(seedA), "m/test/a",
            script(72_000 + salt), script(82_000 + salt)))
        a.autoKeys = listOf(key(seedA))
        a.start(); waitFor("A open") { a.state.phase == PoolSession.Phase.OPEN }

        val found = java.util.concurrent.CompletableFuture<Protocol.Terms>()
        val r = RelayClient(Protocol.RELAY, { _, ev -> Protocol.Terms.parse(ev)?.let { if (it.id == terms.id) found.complete(it) } })
        r.connect(); r.subscribe("p", listOf(JSONObject().put("kinds", JSONArray().put(Protocol.KIND_POOL)).put("#d", JSONArray().put(terms.id))))
        val seen = found.get(30, java.util.concurrent.TimeUnit.SECONDS); r.close()

        val b = PoolSession(envB, PoolSession.newState(seen, false, null, coinB, key(seedB), "m/test/b",
            script(73_000 + salt), script(83_000 + salt)))
        b.autoKeys = listOf(key(seedB))
        b.start(); waitFor("B welcomed") { b.state.phase == PoolSession.Phase.OPEN && b.state.seats.size == 2 }
        waitFor("A sees two") { a.state.seats.size == 2 }

        b.requestClose()   // the one tap; everything after it happens alone
        waitFor("broadcast", 240) { a.state.txid != null && b.state.txid != null }
        assertEquals(a.state.txid, b.state.txid)
        org.junit.Assert.assertTrue(envA.events.contains("DidAlone") && envB.events.contains("DidAlone"))
        org.junit.Assert.assertFalse(envA.events.contains("SignNeeded") || envB.events.contains("SignNeeded"))
        rpc("generatetoaddress", 1, rpc("getnewaddress") as String)
        waitFor("confirmed", 60) { a.state.phase == PoolSession.Phase.CONFIRMED && b.state.phase == PoolSession.Phase.CONFIRMED }
        println("on its own txid ${a.state.txid}")
        a.stop(); b.stop()
    }

    private fun waitFor(what: String, seconds: Int = 120, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (!cond()) { require(System.currentTimeMillis() < end) { "timed out waiting for $what" }; Thread.sleep(500) }
        println("ok: $what")
    }

    @Test fun twoWalletsOverTheRelay() {
        val path = System.getenv("KILOJOIN_E2E") ?: return
        Protocol.NETWORK = "blake2b-regtest" // never shows up in a real wallet's pool list
        val lines = java.io.File(path).readLines().filter { it.isNotBlank() }.map { it.trim().split(" ") }
        val coins = lines.map { (seed, txid, vout, value) -> seed.toInt() to CoinjoinTx.Coin(txid, vout.toInt(), value.toLong(), pub(key(seed.toInt()))) }
        val (seedA, coinA) = coins[0]; val (seedB, coinB) = coins[1]
        val salt = System.nanoTime() % 1_000_000

        // A opens a pool.
        val (terms, secret) = PoolSession.newPool(Protocol.TEST_MIN_AMOUNT * 100, 3.0, 5, 1)
        val envA = Env("A")
        val a = PoolSession(envA, PoolSession.newState(terms, true, secret, coinA, key(seedA), "m/test/a",
            script(70_000 + salt), script(80_000 + salt)))
        a.start()
        waitFor("A open") { a.state.phase == PoolSession.Phase.OPEN }

        // B finds it on the relay, as the wallet's pool list would.
        val found = java.util.concurrent.CompletableFuture<Protocol.Terms>()
        val r = RelayClient(Protocol.RELAY, { _, ev -> Protocol.Terms.parse(ev)?.let { if (it.id == terms.id) found.complete(it) } })
        r.connect()
        r.subscribe("pools", listOf(JSONObject().put("kinds", JSONArray().put(Protocol.KIND_POOL))
            .put("#t", JSONArray().put(Protocol.TAG)).put("#d", JSONArray().put(terms.id))))
        val seen = found.get(30, java.util.concurrent.TimeUnit.SECONDS)
        r.close()
        assertEquals(terms.amount, seen.amount)

        val envB = Env("B")
        val b = PoolSession(envB, PoolSession.newState(seen, false, null, coinB, key(seedB), "m/test/b",
            script(71_000 + salt), script(81_000 + salt)))
        b.start()
        waitFor("B welcomed") { b.state.phase == PoolSession.Phase.OPEN && b.state.seats.size == 2 }
        waitFor("A sees two") { a.state.seats.size == 2 }

        b.requestClose()
        waitFor("A asked to vote") { a.state.phase == PoolSession.Phase.VOTING }
        a.vote(true)
        waitFor("both signing", 180) { a.state.phase == PoolSession.Phase.SIGNING && b.state.phase == PoolSession.Phase.SIGNING }
        assertEquals(CoinjoinTx.txid(a.plan()!!), CoinjoinTx.txid(b.plan()!!))

        a.sign(key(seedA)); b.sign(key(seedB))
        waitFor("broadcast") { a.state.txid != null && b.state.txid != null }
        assertEquals(a.state.txid, b.state.txid)
        val addr = rpc("getnewaddress") as String
        rpc("generatetoaddress", 1, addr)
        waitFor("confirmed", 60) { a.state.phase == PoolSession.Phase.CONFIRMED && b.state.phase == PoolSession.Phase.CONFIRMED }
        println("txid ${a.state.txid}")
        assertNotNull(a.state.txid)
        a.stop(); b.stop()
    }
    /**
     * A private pool on the live relay: a join with the wrong password is refused, one with
     * the right password gets in, and the round completes to a confirmed regtest transaction.
     * KILOJOIN_E2E_PRIVATE=<coins file> with three funded coins.
     */
    @Test fun privatePool() {
        val path = System.getenv("KILOJOIN_E2E_PRIVATE") ?: return
        Protocol.NETWORK = "blake2b-regtest"
        val coins = java.io.File(path).readLines().filter { it.isNotBlank() }.map { it.trim().split(" ") }
            .map { (seed, txid, vout, value) -> seed.toInt() to CoinjoinTx.Coin(txid, vout.toInt(), value.toLong(), pub(key(seed.toInt()))) }
        val salt = System.nanoTime() % 1_000_000
        val (terms, secret) = PoolSession.newPool(100_000, 3.0, 5, 1, private = true)
        assertEquals(true, terms.private)
        val a = PoolSession(Env("A"), PoolSession.newState(terms, true, secret, coins[0].second, key(coins[0].first), "a",
            script(60_000 + salt), script(61_000 + salt), password = "secreto"))
        a.start(); waitFor("A open") { a.state.phase == PoolSession.Phase.OPEN }

        // The terms as anyone sees them on the relay: marked private.
        val found = java.util.concurrent.CompletableFuture<Protocol.Terms>()
        val r = RelayClient(Protocol.RELAY, { _, ev -> Protocol.Terms.parse(ev)?.let { if (it.id == terms.id) found.complete(it) } })
        r.connect(); r.subscribe("p", listOf(JSONObject().put("kinds", JSONArray().put(Protocol.KIND_POOL)).put("#d", JSONArray().put(terms.id))))
        val seen = found.get(30, java.util.concurrent.TimeUnit.SECONDS); r.close()
        assertEquals(true, seen.private)

        val wrong = PoolSession(Env("B-wrong"), PoolSession.newState(seen, false, null, coins[1].second, key(coins[1].first), "b",
            script(62_000 + salt), script(63_000 + salt), password = "mal"))
        wrong.start()
        waitFor("wrong password refused") { wrong.state.phase == PoolSession.Phase.REJECTED }
        assertEquals("wrong password", wrong.state.reason)
        assertEquals(1, a.state.seats.size)
        wrong.stop()

        val right = PoolSession(Env("C-right"), PoolSession.newState(seen, false, null, coins[2].second, key(coins[2].first), "c",
            script(64_000 + salt), script(65_000 + salt), password = "secreto"))
        right.start()
        waitFor("right password welcomed") { right.state.phase == PoolSession.Phase.OPEN && right.state.seats.size == 2 }

        right.requestClose()
        waitFor("A asked") { a.state.phase == PoolSession.Phase.VOTING }
        a.vote(true)
        waitFor("signing", 180) { a.state.phase == PoolSession.Phase.SIGNING && right.state.phase == PoolSession.Phase.SIGNING }
        a.sign(key(coins[0].first)); right.sign(key(coins[2].first))
        waitFor("broadcast") { a.state.txid != null && right.state.txid != null }
        rpc("generatetoaddress", 1, rpc("getnewaddress") as String)
        waitFor("confirmed", 60) { a.state.phase == PoolSession.Phase.CONFIRMED && right.state.phase == PoolSession.Phase.CONFIRMED }
        println("private txid ${a.state.txid}")
        a.stop(); right.stop()
    }

    /**
     * Kilojoin v3 for real: A opens a v3 pool, B and C join; C asks to close; each output is
     * signed blindly by A and handed in from a fresh key; the three sign and the round is mined
     * on the BLAKE2b regtest. Checks that nothing on the pool channel says whose output is whose.
     * KILOJOIN_E2E_V3=<coins file> with three P2WPKH coins (seeds 1, 2, 3).
     */
    @Test fun v3BlindOutputs() {
        val path = System.getenv("KILOJOIN_E2E_V3") ?: return
        Protocol.NETWORK = "blake2b-regtest"
        val coins = java.io.File(path).readLines().filter { it.isNotBlank() }.map { it.trim().split(" ") }
            .map { (seed, txid, vout, value) -> seed.toInt() to CoinjoinTx.Coin(txid, vout.toInt(), value.toLong(), pub(key(seed.toInt()))) }
        val salt = System.nanoTime() % 1_000_000
        val np = PoolSession.newPool(100_000, 3.0, 5, 1, minPeers = 3, v3 = true)
        assertEquals(Protocol.V3, np.terms.version)
        val a = PoolSession(Env("A"), PoolSession.newState(np.terms, true, np.secret, listOf(coins[0].second), listOf(key(coins[0].first)),
            listOf("a"), script(90_000 + salt), script(91_000 + salt), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv))
        a.start(); waitFor("A open") { a.state.phase == PoolSession.Phase.OPEN }

        val found = java.util.concurrent.CompletableFuture<Protocol.Terms>()
        val r = RelayClient(Protocol.RELAY, { _, ev -> Protocol.Terms.parse(ev)?.let { if (it.id == np.terms.id) found.complete(it) } })
        r.connect(); r.subscribe("p", listOf(JSONObject().put("kinds", JSONArray().put(Protocol.KIND_POOL)).put("#d", JSONArray().put(np.terms.id))))
        val seen = found.get(30, java.util.concurrent.TimeUnit.SECONDS); r.close()
        assertEquals(true, seen.v3)
        assertEquals(np.terms.blindKey, seen.blindKey)

        val b = PoolSession(Env("B"), PoolSession.newState(seen, false, null, coins[1].second, key(coins[1].first), "b",
            script(92_000 + salt), script(93_000 + salt)))
        b.start(); waitFor("B welcomed") { b.state.phase == PoolSession.Phase.OPEN && b.state.seats.size >= 2 }
        val c = PoolSession(Env("C"), PoolSession.newState(seen, false, null, coins[2].second, key(coins[2].first), "c",
            script(94_000 + salt), script(95_000 + salt)))
        c.start(); waitFor("C welcomed") { c.state.phase == PoolSession.Phase.OPEN && c.state.seats.size == 3 }
        waitFor("everyone sees three") { a.state.seats.size == 3 && b.state.seats.size == 3 }
        // Every member checked every seat's coin proofs itself: the roster carries them.
        assertTrue(c.state.seats.all { it.joinPub != null && it.proofs.size == it.coins.size })

        c.requestClose()
        waitFor("vote opened by the creator") { a.state.phase == PoolSession.Phase.VOTING && b.state.phase == PoolSession.Phase.VOTING }
        a.vote(true); b.vote(true)
        waitFor("all signing", 240) { listOf(a, b, c).all { it.state.phase == PoolSession.Phase.SIGNING } }
        // Each member got exactly one blind signature, and the creator's list holds three outputs.
        assertEquals(2, a.state.blindSigs.size)
        assertEquals(3, a.state.regOutputs.size)
        assertTrue(b.state.outSigs.size == 1 && c.state.outSigs.size == 1)
        val txid = CoinjoinTx.txid(a.plan()!!)
        assertEquals(txid, CoinjoinTx.txid(b.plan()!!)); assertEquals(txid, CoinjoinTx.txid(c.plan()!!))

        a.sign(key(coins[0].first)); b.sign(key(coins[1].first)); c.sign(key(coins[2].first))
        waitFor("broadcast") { listOf(a, b, c).all { it.state.txid != null } }
        rpc("generatetoaddress", 1, rpc("getnewaddress") as String)
        waitFor("confirmed", 60) { listOf(a, b, c).all { it.state.phase == PoolSession.Phase.CONFIRMED } }
        println("v3 txid ${a.state.txid}")
        a.stop(); b.stop(); c.stop()
    }
    /**
     * Kilojoin v4 for real: a pool of 4 outputs of 100 000 each. A (the creator) brings its perfect
     * coin and gets no change, B one big coin with change, C two coins. Every member gets its four
     * outputs to its own four addresses and its change back; the transaction is mined on the
     * BLAKE2b regtest. KILOJOIN_E2E_V4=<coins file>: "seed txid vout value" for A, B, C1, C2.
     */
    @Test fun v4SeveralOutputsEach() {
        val path = System.getenv("KILOJOIN_E2E_V4") ?: return
        Protocol.NETWORK = "blake2b-regtest"
        val coins = java.io.File(path).readLines().filter { it.isNotBlank() }.map { it.trim().split(" ") }
            .map { (seed, txid, vout, value) -> seed.toInt() to CoinjoinTx.Coin(txid, vout.toInt(), value.toLong(), pub(key(seed.toInt()))) }
        val salt = System.nanoTime() % 1_000_000
        val k = 4
        val np = PoolSession.newPool(100_000, 3.0, 5, 1, minPeers = 3, maxInputs = 2, v3 = true, outputs = k)
        assertEquals(Protocol.V4, np.terms.version)
        fun mixes(base: Long) = (0 until k).map { script(base + salt + it) }
        val mixA = mixes(80_000); val mixB = mixes(81_000); val mixC = mixes(82_000)
        val chB = script(83_000 + salt); val chC = script(84_000 + salt)
        val a = PoolSession(Env("A"), PoolSession.newState(np.terms, true, np.secret, listOf(coins[0].second), listOf(key(coins[0].first)),
            listOf("a"), mixA, script(85_000 + salt), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv))
        assertEquals(0L, a.state.seat.changeValue) // the perfect coin
        a.start(); waitFor("A open") { a.state.phase == PoolSession.Phase.OPEN }

        val found = java.util.concurrent.CompletableFuture<Protocol.Terms>()
        val r = RelayClient(Protocol.RELAY, { _, ev -> Protocol.Terms.parse(ev)?.let { if (it.id == np.terms.id) found.complete(it) } })
        r.connect(); r.subscribe("p", listOf(JSONObject().put("kinds", JSONArray().put(Protocol.KIND_POOL)).put("#d", JSONArray().put(np.terms.id))))
        val seen = found.get(30, java.util.concurrent.TimeUnit.SECONDS); r.close()
        assertEquals(k, seen.outputs)

        val b = PoolSession(Env("B"), PoolSession.newState(seen, false, null, listOf(coins[1].second), listOf(key(coins[1].first)),
            listOf("b"), mixB, chB))
        b.start(); waitFor("B welcomed") { b.state.phase == PoolSession.Phase.OPEN && b.state.seats.size >= 2 }
        val c = PoolSession(Env("C"), PoolSession.newState(seen, false, null, listOf(coins[2].second, coins[3].second),
            listOf(key(coins[2].first), key(coins[3].first)), listOf("c1", "c2"), mixC, chC))
        c.start(); waitFor("C welcomed") { c.state.phase == PoolSession.Phase.OPEN && c.state.seats.size == 3 }
        waitFor("everyone sees three") { a.state.seats.size == 3 && b.state.seats.size == 3 }

        b.requestClose()
        waitFor("vote opened by the creator") { a.state.phase == PoolSession.Phase.VOTING && c.state.phase == PoolSession.Phase.VOTING }
        a.vote(true); c.vote(true)
        waitFor("all signing", 300) { listOf(a, b, c).all { it.state.phase == PoolSession.Phase.SIGNING } }
        // One blind request per member, four signatures in it; the creator's list holds 12 outputs.
        assertEquals(2, a.state.blindSigs.size)
        assertTrue(a.state.blindSigs.values.all { it.split(",").size == k })
        assertEquals(3 * k, a.state.regOutputs.size)
        assertTrue(b.state.outSigs.size == k && c.state.outSigs.size == k)
        val txid = CoinjoinTx.txid(a.plan()!!)
        assertEquals(txid, CoinjoinTx.txid(b.plan()!!)); assertEquals(txid, CoinjoinTx.txid(c.plan()!!))

        a.sign(key(coins[0].first)); b.sign(key(coins[1].first)); c.sign(listOf(key(coins[2].first), key(coins[3].first)))
        waitFor("broadcast") { listOf(a, b, c).all { it.state.txid != null } }
        // What went out, read back from the node: every output where it belongs.
        val tx = rpc("getrawtransaction", a.state.txid!!, true) as JSONObject
        val outs = tx.getJSONArray("vout").let { v -> (0 until v.length()).map { v.getJSONObject(it) } }
            .map { it.getJSONObject("scriptPubKey").getString("hex") to Math.round(it.getDouble("value") * 1e8) }
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        for ((who, mix) in listOf("A" to mixA, "B" to mixB, "C" to mixC))
            for (m in mix) assertEquals("$who's output", 1, outs.count { it.first == hex(m) && it.second == 100_000L })
        assertEquals(1, outs.count { it.first == hex(chB) && it.second == b.state.seat.changeValue })
        assertEquals(1, outs.count { it.first == hex(chC) && it.second == c.state.seat.changeValue })
        assertEquals(3 * k + 2, outs.size)
        assertEquals(4, tx.getJSONArray("vin").length())
        rpc("generatetoaddress", 1, rpc("getnewaddress") as String)
        waitFor("confirmed", 60) { listOf(a, b, c).all { it.state.phase == PoolSession.Phase.CONFIRMED } }
        println("v4 txid ${a.state.txid} vsize ${tx.getInt("vsize")}")
        a.stop(); b.stop(); c.stop()
    }

    /** Pays [sats] from the regtest wallet to [seed]'s key ([type]) and gives back that coin, mined. */
    private fun fund(seed: Int, sats: Long, type: ScriptType = ScriptType.P2WPKH): CoinjoinTx.Coin {
        val spk = Address.scriptPubKey(pub(key(seed)), type)
        // The regtest node only takes its own "bcrt" addresses.
        val addr = com.kilombino.pyblockwatch.crypto.Bech32.encodeSegwit("bcrt", if (type == ScriptType.P2TR) 1 else 0, spk.copyOfRange(2, spk.size))
        val txid = rpc("sendtoaddress", addr, sats / 1e8) as String
        val tx = rpc("getrawtransaction", txid, true) as JSONObject
        val vout = tx.getJSONArray("vout").let { v -> (0 until v.length()).first { v.getJSONObject(it).getJSONObject("scriptPubKey").getString("address") == addr } }
        return CoinjoinTx.Coin(txid, vout, sats, pub(key(seed)), type)
    }

    /**
     * Kilojoin v5 for real: A opens a payment pool paying two recipients of different amounts; B
     * and C join and get the payments' total back in [k] equal outputs each; the round is signed,
     * mined on the BLAKE2b regtest, and the recipients hold exactly what they were paid.
     * KILOJOIN_E2E_V5=1 with the regtest node's wallet `w` funded (it pays the members' coins).
     */
    @Test fun v5PaymentPool() {
        System.getenv("KILOJOIN_E2E_V5") ?: return
        Protocol.NETWORK = "blake2b-regtest"
        for (k in listOf(1, 2)) v5Round(k)
    }

    private fun v5Round(k: Int) {
        val salt = System.nanoTime() % 1_000_000
        val pays = listOf(37_123L, 162_877L)                       // S = 200 000
        val (d, outs) = Protocol.v5Split(pays.sum(), k)
        assertEquals(k, outs)
        val rate = 2.0
        val seed = (salt % 50_000).toInt() * 10 + k * 3
        val ca = fund(seed + 1, 300_000)                            // A: change back
        val cb = fund(seed + 2, CoinjoinTx.minimumCoin(d, rate, ScriptType.P2WPKH, k))  // B: a perfect coin
        val cc1 = fund(seed + 3, 120_000); val cc2 = fund(seed + 4, 110_000)           // C: two coins, change
        rpc("generatetoaddress", 1, rpc("getnewaddress") as String)

        val np = PoolSession.newPool(d, rate, 5, 1, minPeers = 3, maxInputs = 2, v3 = true, outputs = k, payments = pays)
        assertEquals(Protocol.V5, np.terms.version)
        val payTo = pays.indices.map { script(40_000 + salt + it) }
        fun mixes(base: Long) = (0 until k).map { script(base + salt + it) }
        val mixB = mixes(41_000); val mixC = mixes(42_000)
        val chA = script(43_000 + salt); val chC = script(44_000 + salt)
        val a = PoolSession(Env("A$k"), PoolSession.newState(np.terms, true, np.secret, listOf(ca), listOf(key(seed + 1)), listOf("a"),
            payTo, chA, creatorSecret = np.creatorSecret, blindPriv = np.blindPriv))
        a.start(); waitFor("A open") { a.state.phase == PoolSession.Phase.OPEN }

        val found = java.util.concurrent.CompletableFuture<Protocol.Terms>()
        val r = RelayClient(Protocol.RELAY, { _, ev -> Protocol.Terms.parse(ev)?.let { if (it.id == np.terms.id) found.complete(it) } })
        r.connect(); r.subscribe("p", listOf(JSONObject().put("kinds", JSONArray().put(Protocol.KIND_POOL)).put("#d", JSONArray().put(np.terms.id))))
        val seen = found.get(30, java.util.concurrent.TimeUnit.SECONDS); r.close()
        // The listing carries the payments' amounts, never their addresses.
        assertEquals(pays, seen.payments)
        assertTrue(payTo.none { p -> seen.toJson().toString().contains(p.joinToString("") { "%02x".format(it) }) })

        val b = PoolSession(Env("B$k"), PoolSession.newState(seen, false, null, listOf(cb), listOf(key(seed + 2)), listOf("b"), mixB, script(45_000 + salt)))
        assertEquals(0L, b.state.seat.changeValue)
        b.start(); waitFor("B welcomed") { b.state.phase == PoolSession.Phase.OPEN && b.state.seats.size >= 2 }
        val c = PoolSession(Env("C$k"), PoolSession.newState(seen, false, null, listOf(cc1, cc2), listOf(key(seed + 3), key(seed + 4)),
            listOf("c1", "c2"), mixC, chC))
        c.start(); waitFor("C welcomed") { c.state.phase == PoolSession.Phase.OPEN && c.state.seats.size == 3 }
        waitFor("everyone sees three") { a.state.seats.size == 3 && b.state.seats.size == 3 }

        c.requestClose()
        waitFor("vote opened") { a.state.phase == PoolSession.Phase.VOTING && b.state.phase == PoolSession.Phase.VOTING }
        a.vote(true); b.vote(true)
        waitFor("all signing", 300) { listOf(a, b, c).all { it.state.phase == PoolSession.Phase.SIGNING } }
        // Only the joiners asked for blind signatures; the creator listed its payments itself.
        assertEquals(2, a.state.blindSigs.size)
        assertEquals(2 * k, a.state.regOutputs.size)
        assertEquals(pays.size, b.state.payOutputs.size)
        val txid = CoinjoinTx.txid(a.plan()!!)
        assertEquals(txid, CoinjoinTx.txid(b.plan()!!)); assertEquals(txid, CoinjoinTx.txid(c.plan()!!))

        a.sign(key(seed + 1)); b.sign(key(seed + 2)); c.sign(listOf(key(seed + 3), key(seed + 4)))
        waitFor("broadcast") { listOf(a, b, c).all { it.state.txid != null } }
        rpc("generatetoaddress", 1, rpc("getnewaddress") as String)
        waitFor("confirmed", 60) { listOf(a, b, c).all { it.state.phase == PoolSession.Phase.CONFIRMED } }
        val tx = rpc("getrawtransaction", a.state.txid!!, true) as JSONObject
        val got = tx.getJSONArray("vout").let { v -> (0 until v.length()).map { v.getJSONObject(it) } }
            .map { it.getJSONObject("scriptPubKey").getString("hex") to Math.round(it.getDouble("value") * 1e8) }
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        // The recipients got exactly their payments; each joiner its k outputs of d.
        pays.indices.forEach { assertEquals(1, got.count { o -> o.first == hex(payTo[it]) && o.second == pays[it] }) }
        for (m in mixB + mixC) assertEquals(1, got.count { it.first == hex(m) && it.second == d })
        assertEquals(1, got.count { it.first == hex(chA) && it.second == a.state.seat.changeValue })
        assertEquals(1, got.count { it.first == hex(chC) && it.second == c.state.seat.changeValue })
        assertEquals(2 * k + pays.size + 2, got.size)
        println("v5 k=$k txid ${a.state.txid} vsize ${tx.getInt("vsize")} fee ${a.plan()!!.fee}")
        a.stop(); b.stop(); c.stop()
    }

    /**
     * A member that can't reach its chain server keeps its seat instead of calling the pool fake,
     * and a coin whose seat was lost (its app stopped without a word) can join again: the creator
     * gives it the new seat. KILOJOIN_E2E_REJOIN=1 with the regtest node's wallet `w` funded.
     */
    @Test fun seatSurvivesAndComesBack() {
        System.getenv("KILOJOIN_E2E_REJOIN") ?: return
        Protocol.NETWORK = "blake2b-regtest"
        val salt = System.nanoTime() % 1_000_000
        val seed = (salt % 50_000).toInt() * 10 + 7
        val ca = fund(seed + 1, 150_000); val cb = fund(seed + 2, 150_000); val cc = fund(seed + 3, 150_000)
        rpc("generatetoaddress", 1, rpc("getnewaddress") as String)
        val np = PoolSession.newPool(100_000, 2.0, 5, 1, minPeers = 4, maxInputs = 1, v3 = true, outputs = 1)
        val a = PoolSession(Env("A"), PoolSession.newState(np.terms, true, np.secret, listOf(ca), listOf(key(seed + 1)), listOf("a"),
            listOf(script(50_000 + salt)), script(51_000 + salt), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv))
        a.start(); waitFor("A open") { a.state.phase == PoolSession.Phase.OPEN }
        val found = java.util.concurrent.CompletableFuture<Protocol.Terms>()
        val r = RelayClient(Protocol.RELAY, { _, ev -> Protocol.Terms.parse(ev)?.let { if (it.id == np.terms.id) found.complete(it) } })
        r.connect(); r.subscribe("p", listOf(JSONObject().put("kinds", JSONArray().put(Protocol.KIND_POOL)).put("#d", JSONArray().put(np.terms.id))))
        val seen = found.get(30, java.util.concurrent.TimeUnit.SECONDS); r.close()

        // B can't reach the chain when the roster with C arrives: it stays in the pool.
        val envB = Env("B")
        val b = PoolSession(envB, PoolSession.newState(seen, false, null, listOf(cb), listOf(key(seed + 2)), listOf("b"),
            listOf(script(52_000 + salt)), script(53_000 + salt)))
        b.start(); waitFor("B welcomed") { b.state.phase == PoolSession.Phase.OPEN && b.state.seats.size == 2 }
        envB.down = true
        val c = PoolSession(Env("C"), PoolSession.newState(seen, false, null, listOf(cc), listOf(key(seed + 3)), listOf("c"),
            listOf(script(54_000 + salt)), script(55_000 + salt)))
        c.start(); waitFor("B sees C") { b.state.seats.size == 3 }
        Thread.sleep(3_000)
        assertEquals(PoolSession.Phase.OPEN, b.state.phase)
        envB.down = false

        // C's app stops without leaving; C's coin joins again from a fresh session and gets a seat.
        c.stop()
        val c2 = PoolSession(Env("C2"), PoolSession.newState(seen, false, null, listOf(cc), listOf(key(seed + 3)), listOf("c"),
            listOf(script(56_000 + salt)), script(57_000 + salt)))
        c2.start(); waitFor("C welcomed again") { c2.state.phase == PoolSession.Phase.OPEN }
        waitFor("still three seats") { a.state.seats.size == 3 && b.state.seats.size == 3 && c2.state.seats.size == 3 }
        assertEquals(PoolSession.Phase.OPEN, b.state.phase)

        // The creator's app comes back and replays the channel, with C's two join requests in it:
        // the seat stays with C2, and nobody is announced as joining.
        a.stop()
        val envA2 = Env("A2")
        val a2 = PoolSession(envA2, PoolSession.State.parse(a.state.toJson()))
        a2.start(); Thread.sleep(8_000)
        assertEquals(3, a2.state.seats.size)
        assertEquals(PoolSession.Phase.OPEN, c2.state.phase)
        assertTrue(c2.state.seats.any { s -> a2.state.seats.any { it.tokenHash == s.tokenHash } && s.coins.any { it.outpoint == cc.outpoint } })
        assertTrue("no one joined on replay: ${envA2.events}", envA2.events.none { it == "Joined" })
        a2.stop(); b.stop(); c2.stop()
    }

    /** Spends a test coin elsewhere (as a perfect coin made from it would), to a throwaway script. */
    private fun spendElsewhere(c: CoinjoinTx.Coin, k: BigInteger, salt: Long) {
        val input = com.kilombino.pyblockwatch.crypto.TxBuilder.Input(c.txid, c.vout, c.value, k, pub(k), 0xfffffffdL, ScriptType.P2WPKH)
        val out = com.kilombino.pyblockwatch.crypto.TxBuilder.Output(script(60_000 + salt), c.value - 500)
        rpc("sendrawtransaction", com.kilombino.pyblockwatch.crypto.TxBuilder.build(listOf(input), listOf(out), unified = true, grindLowR = true).rawHex)
    }

    /**
     * A seated coin spent while its pool is open: the creator takes the seat out (before letting
     * the next one in, and on its own every few minutes), so newcomers are not driven away by it;
     * a member shown such a seat waits for the creator instead of leaving at once.
     * KILOJOIN_E2E_SPENT=1 with the regtest node's wallet `w` funded.
     */
    @Test fun spentSeatIsTakenOut() {
        System.getenv("KILOJOIN_E2E_SPENT") ?: return
        Protocol.NETWORK = "blake2b-regtest"
        val check = PoolSession.SEAT_CHECK_SECONDS; val grace = PoolSession.FAKE_GRACE
        try {
            PoolSession.SEAT_CHECK_SECONDS = 3600; PoolSession.FAKE_GRACE = 90
            val salt = System.nanoTime() % 1_000_000
            val seed = (salt % 50_000).toInt() * 10 + 3
            val ca = fund(seed + 1, 150_000); val cb = fund(seed + 2, 150_000); val cc = fund(seed + 3, 150_000)
            val cd = fund(seed + 4, 150_000); val ce = fund(seed + 5, 150_000)
            rpc("generatetoaddress", 1, rpc("getnewaddress") as String)
            val np = PoolSession.newPool(100_000, 2.0, 5, 1, minPeers = 5, maxInputs = 1, v3 = true, outputs = 1)
            val envA = Env("A")
            val a = PoolSession(envA, PoolSession.newState(np.terms, true, np.secret, listOf(ca), listOf(key(seed + 1)), listOf("a"),
                listOf(script(50_000 + salt)), script(51_000 + salt), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv))
            a.start(); waitFor("A open") { a.state.phase == PoolSession.Phase.OPEN }
            val found = java.util.concurrent.CompletableFuture<Protocol.Terms>()
            val r = RelayClient(Protocol.RELAY, { _, ev -> Protocol.Terms.parse(ev)?.let { if (it.id == np.terms.id) found.complete(it) } })
            r.connect(); r.subscribe("p", listOf(JSONObject().put("kinds", JSONArray().put(Protocol.KIND_POOL)).put("#d", JSONArray().put(np.terms.id))))
            val seen = found.get(30, java.util.concurrent.TimeUnit.SECONDS); r.close()
            fun member(name: String, coin: CoinjoinTx.Coin, k: Int, env: Env = Env(name)) = PoolSession(env, PoolSession.newState(seen, false, null,
                listOf(coin), listOf(key(k)), listOf(name.lowercase()), listOf(script(52_000L + k + salt)), script(53_000L + k + salt)))
            val b = member("B", cb, seed + 2); b.start(); waitFor("B in") { b.state.phase == PoolSession.Phase.OPEN && a.state.seats.size == 2 }
            val c = member("C", cc, seed + 3); c.start(); waitFor("C in") { c.state.phase == PoolSession.Phase.OPEN && a.state.seats.size == 3 }

            // 1. C's coin is spent while seated; D asks to join: the creator takes C out first.
            spendElsewhere(cc, key(seed + 3), salt)
            val d = member("D", cd, seed + 4); d.start()
            waitFor("D in, C out") { d.state.phase == PoolSession.Phase.OPEN && a.state.seats.size == 3 &&
                a.state.seats.none { s -> s.coins.any { it.outpoint == cc.outpoint } } }
            waitFor("C told") { c.state.phase == PoolSession.Phase.ABORTED }
            waitFor("B and D see three seats") { b.state.seats.size == 3 && d.state.seats.size == 3 }
            assertEquals(PoolSession.Phase.OPEN, b.state.phase)

            // 2. B's coin is spent, and the creator (as one from before 1.3.1) still takes it for
            // unspent: E joins, finds B's seat fake and waits instead of leaving.
            envA.stale.add(cb.outpoint)
            spendElsewhere(cb, key(seed + 2), salt + 1)
            val e = member("E", ce, seed + 5); e.start()
            waitFor("E in") { e.state.phase == PoolSession.Phase.OPEN && e.state.seats.size == 4 }
            Thread.sleep(5_000)
            assertEquals(PoolSession.Phase.OPEN, e.state.phase)
            // The creator looks at the seats on its own and takes B out; E stays.
            envA.stale.clear(); PoolSession.SEAT_CHECK_SECONDS = 10
            waitFor("B out", 90) { a.state.seats.size == 3 && e.state.seats.size == 3 && d.state.seats.size == 3 }
            Thread.sleep(PoolSession.FAKE_GRACE * 1000 + 20_000 - 30_000)
            assertEquals(PoolSession.Phase.OPEN, e.state.phase)
            assertEquals(PoolSession.Phase.OPEN, d.state.phase)
            waitFor("B told") { b.state.phase == PoolSession.Phase.ABORTED }
            a.stop(); b.stop(); c.stop(); d.stop(); e.stop()
        } finally { PoolSession.SEAT_CHECK_SECONDS = check; PoolSession.FAKE_GRACE = grace }
    }
}
