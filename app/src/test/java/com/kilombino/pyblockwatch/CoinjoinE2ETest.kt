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
            return Math.round(o.getDouble("value") * 1e8) == coin.value
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
}
