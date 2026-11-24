package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.chain.NodeEndpoint
import com.kilombino.pyblockwatch.chain.NodeRpcBackend
import com.kilombino.pyblockwatch.chain.RpcConn
import com.kilombino.pyblockwatch.crypto.Bech32
import com.kilombino.pyblockwatch.crypto.Bip32
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.data.AddressRow
import com.kilombino.pyblockwatch.data.JamWatch
import com.kilombino.pyblockwatch.data.JoinMarket
import com.kilombino.pyblockwatch.data.ScanEvent
import com.kilombino.pyblockwatch.data.Scanner
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * The JoinMarket wallet read from a node by RPC, end to end against a BLAKE2b regtest node with a
 * funded wallet named "faucet" (the Kilojoin lab: /mnt/datos2/kj-lab). Skipped unless
 * KW_JM_RPC_E2E is the node's RPC (http://user:pass@127.0.0.1:19443).
 *
 * A fresh JoinMarket wallet (random keys, regtest export with one fidelity bond) gets coins in a
 * jar and in its bond, a block is mined, and the JoinMarket screen's look (Scanner.scanAccounts
 * over the jars and the bond, through NodeRpcBackend) must find them; the bond's state (value,
 * confirmation time from the node's header) too. Then a payment to another jar's change, left in
 * the mempool, must show on the next look from the same node wallet.
 */
class JamRpcRegtestE2E {
    private val url = System.getenv("KW_JM_RPC_E2E")

    private fun rpc(method: String, vararg params: Any?, wallet: String? = null): Any? {
        val c = RpcConn.parse(url!!)
        val target = c.url.trimEnd('/') + (wallet?.let { "/wallet/$it" } ?: "")
        val h = URL(target).openConnection() as HttpURLConnection
        h.requestMethod = "POST"; h.doOutput = true
        h.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("${c.user}:${c.pass}".toByteArray()))
        val body = JSONObject().put("jsonrpc", "1.0").put("id", "t").put("method", method)
            .put("params", JSONArray().also { a -> params.forEach { a.put(it ?: JSONObject.NULL) } })
        h.outputStream.use { it.write(body.toString().toByteArray()) }
        val text = (if (h.responseCode < 400) h.inputStream else h.errorStream).bufferedReader().use { it.readText() }
        val o = JSONObject(text)
        if (!o.isNull("error")) throw IllegalStateException("$method: ${o.getJSONObject("error").optString("message")}")
        return o.opt("result")
    }

    private fun look(e: JoinMarket.Export, endpoint: NodeEndpoint): ScanEvent.Done = runBlocking {
        val events = Scanner().scanAccounts(JamWatch.accounts(e), JamWatch.fixed(e), endpoint, null, 20).toList()
        events.filterIsInstance<ScanEvent.Failed>().firstOrNull()?.let { throw AssertionError("the look failed: ${it.message}") }
        events.filterIsInstance<ScanEvent.Done>().single()
    }

    private fun jarAddress(e: JoinMarket.Export, jar: Int, branch: Int, index: Int): String =
        Bech32.encodeSegwit(e.hrp, 0, Hashes.hash160(Bip32.derivePath(JoinMarket.parseKey(e.xpubs[jar]), branch, index).pubkey()))

    private fun row(rows: List<AddressRow>, jar: Int, branch: Int, index: Int) =
        rows.single { it.jar == jar && it.chainIndex == branch && it.index == index }

    @Test fun jamWalletFromTheNodeByRpc() {
        assumeTrue(url != null)
        // A fresh wallet each run, so the node makes (and imports) its watch-only wallet now.
        val master = Bip32Priv.fromSeed(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        val zpubs = (0 until 5).map { Bip32Priv.accountXpub(master, 84, it) }
        val tn = 100 // a bond locked until 2028-05-01
        val e = JoinMarket.parse(JoinMarket.PREFIX + JSONObject().put("n", "regtest").put("x", JSONArray(zpubs))
            .put("b", JSONArray().put(JSONArray().put(tn).put(JoinMarket.lockTime(tn)))).toString(), allowTestNetworks = true)
        val bondAddress = JoinMarket.bondScripts(e).single().address
        assertTrue(bondAddress.startsWith("bcrt1q") && bondAddress.length == 64)

        // Coins: 0.01 to jar 1 (receive #3), 0.02 into the bond; one block.
        val toJar = jarAddress(e, 1, 0, 3)
        val t1 = rpc("sendtoaddress", toJar, 0.01, wallet = "faucet") as String
        val t2 = rpc("sendtoaddress", bondAddress, 0.02, wallet = "faucet") as String
        rpc("generatetoaddress", 1, rpc("getnewaddress", wallet = "faucet"), wallet = "faucet")

        val endpoint = NodeEndpoint("node", 0, isCustom = true, rpc = JamWatch.rpcNode(e, listOf(RpcConn.parse(url!!))))
        val walletName = NodeRpcBackend(endpoint.rpc!!).walletName
        assertTrue(walletName.startsWith("kilowallet-jm-"))

        val first = look(e, endpoint)
        val loaded = rpc("listwallets") as JSONArray
        assertTrue("the node made $walletName", (0 until loaded.length()).any { loaded.getString(it) == walletName })
        val jarRow = row(first.rows, 1, 0, 3)
        assertEquals(1_000_000L, jarRow.confirmed)
        assertEquals(1, jarRow.txCount)
        val bondRow = first.rows.single { it.chainIndex == JoinMarket.BOND_BRANCH }
        assertEquals(2_000_000L, bondRow.confirmed)
        assertEquals(3_000_000L, first.rows.sumOf { it.confirmed })
        assertTrue(first.txs.map { it.txid }.containsAll(listOf(t1, t2)))

        // The bond, as the screen and the watcher read it: its coins and when they confirmed (the node's header).
        val bond = JamWatch.bondStates(e, first.rows, endpoint, null, { null }, { _, _ -> }).single()
        assertEquals(2_000_000L, bond.value)
        assertEquals(t2, bond.txid!!.substringBefore(":"))
        assertTrue(bond.height > 0)
        assertNotNull("the confirmation time comes from the node's block header", bond.confirmationTime)
        val tipTime = (rpc("getblockheader", rpc("getbestblockhash")) as JSONObject).getLong("time")
        assertTrue(bond.confirmationTime!! in (tipTime - 86_400 * 7)..tipTime)
        assertEquals(JoinMarket.Status.LOCKED, bond.status(System.currentTimeMillis() / 1000))

        // Later, a payment to jar 2's change (#0), still in the mempool (the lab's miner may take it in a block).
        val t3 = rpc("sendtoaddress", jarAddress(e, 2, 1, 0), 0.005, wallet = "faucet") as String
        val second = look(e, endpoint)
        assertEquals(500_000L, row(second.rows, 2, 1, 0).total)
        assertTrue(second.txs.any { it.txid == t3 })
        assertEquals(3_500_000L, second.rows.sumOf { it.total })
        println("JM by RPC: node wallet $walletName, ${second.rows.size} rows, height ${second.height}")

        runCatching { rpc("unloadwallet", walletName) }
    }
}
