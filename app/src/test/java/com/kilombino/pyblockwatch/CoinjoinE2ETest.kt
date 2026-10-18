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
        override fun coinUnspent(coin: CoinjoinTx.Coin): Boolean {
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
        assertTrue(b.state.outSig != null && c.state.outSig != null)
        val txid = CoinjoinTx.txid(a.plan()!!)
        assertEquals(txid, CoinjoinTx.txid(b.plan()!!)); assertEquals(txid, CoinjoinTx.txid(c.plan()!!))

        a.sign(key(coins[0].first)); b.sign(key(coins[1].first)); c.sign(key(coins[2].first))
        waitFor("broadcast") { listOf(a, b, c).all { it.state.txid != null } }
        rpc("generatetoaddress", 1, rpc("getnewaddress") as String)
        waitFor("confirmed", 60) { listOf(a, b, c).all { it.state.phase == PoolSession.Phase.CONFIRMED } }
        println("v3 txid ${a.state.txid}")
        a.stop(); b.stop(); c.stop()
    }
}
