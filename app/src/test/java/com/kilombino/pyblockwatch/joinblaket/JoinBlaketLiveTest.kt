package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.chain.NodeEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Base64

/**
 * Live runs against real directories, skipped unless their environment is set.
 *
 *  - Regtest lab (`/mnt/datos2/jm-043-regtest`, node + directory + one bonded maker started the way
 *    its `run-xbt2-e2e.sh` does): `JB_REGTEST_DIRECTORY=127.0.0.1:15322`,
 *    `JB_REGTEST_RPC=http://cj:cj@127.0.0.1:19443`, and `JB_REGTEST_DIRECTORY_ID=127.0.0.1:15322`
 *    when the directory runs with nick authentication (then it is required). Fetches the orderbook,
 *    values the bonds through the node's RPC and chooses makers.
 *  - Mainnet, read-only (`JB_MAINNET_ORDERBOOK=1`): the JoinBlaket directory through Tor
 *    (`JB_SOCKS`, default 127.0.0.1:9050), network `xbt`, nick auth required. It sends exactly
 *    what an orderbook watcher sends (handshake, nick proof, GETPEERLIST, `!orderbook`) and values
 *    the bonds through the app's default Electrum server.
 *
 * Only counts are reported (stdout and `JB_REPORT` if set): never nicks or bond outpoints.
 */
class JoinBlaketLiveTest {
    private fun env(k: String): String? = System.getenv(k)?.takeIf { it.isNotBlank() }

    private fun report(lines: List<String>) {
        val text = lines.joinToString("\n")
        println(text)
        env("JB_REPORT")?.let { java.io.File(it).appendText(text + "\n") }
    }

    private fun summary(label: String, ob: Orderbook.Result, valued: List<ValuedOffer>, amount: Long, pick: MakerSelection.Result): List<String> {
        val bonds = valued.groupBy { it.bond.txid to it.bond.vout }
        val types = valued.groupingBy { it.offer.type.wire }.eachCount()
        return listOf(
            "[$label] offer types: $types, on the fee grid: ${valued.count { Fees.isQuantized(it.offer) }}, " +
                "within the fee cap: ${valued.count { Fees.withinLimits(it.offer) }}, taking $amount sats: ${valued.count { amount in it.offer.minsize..it.offer.maxsize }}",
            "[$label] offer messages: ${ob.offerMessages}, with a valid bond proof: ${ob.offers.size}, refused or repeated: ${ob.dropped}",
            "[$label] distinct bonds: ${bonds.size}, verified on chain: ${bonds.count { (_, v) -> v.first().value > 0 }}",
            "[$label] problems: " + valued.mapNotNull { it.problem }.groupingBy { it.substringBefore(":") }.eachCount(),
            "[$label] selection of $amount sats (n=9, minimum 1): " + when (pick) {
                is MakerSelection.Result.Chosen -> "${pick.offers.size} makers, total fee ${pick.totalFee} sats"
                is MakerSelection.Result.Failed -> pick.reason
            },
        )
    }

    /** One JSON-RPC call to [url] (`http://user:pass@host:port`). */
    private fun rpc(url: String, method: String, params: List<Any?>): Any? {
        val u = URL(url)
        val c = URL("http://${u.host}:${u.port}/").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(u.userInfo.toByteArray()))
        val body = JSONObject().put("jsonrpc", "1.0").put("id", "jb").put("method", method)
            .put("params", JSONArray().also { a -> params.forEach { a.put(it ?: JSONObject.NULL) } })
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val text = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        val o = JSONObject(text)
        if (!o.isNull("error")) throw IllegalStateException(o.get("error").toString())
        return o.opt("result").takeIf { it != JSONObject.NULL }
    }

    @Test fun regtestLab() {
        val dir = env("JB_REGTEST_DIRECTORY"); val node = env("JB_REGTEST_RPC")
        assumeTrue(dir != null && node != null)
        val id = env("JB_REGTEST_DIRECTORY_ID")
        val (host, port) = dir!!.split(":")
        val c = DirectoryClient(host, port.toInt(), NickIdentity.random(), "xbt-regtest", id, requireVerified = id != null)
        runBlocking {
            c.connect()
            try {
                val ob = Orderbook.fetch(c, Orderbook.Timing(minWaitMs = 10_000, quietMs = 5_000, maxWaitMs = 60_000))
                val valued = withContext(Dispatchers.IO) { BondValues.value(ob.offers, RpcChainLookup { m, p -> rpc(node!!, m, p) }) }
                val pick = MakerSelection.choose(valued, 30_000_000, n = 9)
                report(listOf("[regtest] nick authenticated: ${c.authenticated}, peers listed: ${c.peers.size}") + summary("regtest", ob, valued, 30_000_000, pick))
                assertTrue(ob.offers.isNotEmpty())
                assertTrue(valued.any { it.value > 0 })
                assertTrue(pick is MakerSelection.Result.Chosen)
                if (id != null) assertTrue(c.authenticated)
            } finally { c.close() }
        }
    }

    @Test fun mainnetOrderbookReadOnly() {
        assumeTrue(env("JB_MAINNET_ORDERBOOK") == "1")
        val (sh, sp) = (env("JB_SOCKS") ?: "127.0.0.1:9050").split(":")
        fun client() = DirectoryClient(JoinBlaketDirectory.HOST, JoinBlaketDirectory.PORT, NickIdentity.random(),
            JoinBlaketDirectory.NETWORK.wire, JoinBlaketDirectory.ID,
            proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(sh, sp.toInt())))
        val ep = NodeEndpoint.default(Chain.BLAKE2B)
        runBlocking {
            // Onion circuits fail now and then ("TTL expired", timeouts): try a fresh one, as Python reconnects.
            var tries = 0
            var c = client()
            while (true) {
                try { c.connect(); break } catch (e: DirectoryException) {
                    println("[mainnet] connect attempt ${++tries} failed: ${e.message}")
                    if (tries >= 3) throw e
                    c = client()
                }
            }
            val ob = try { Orderbook.fetch(c) } finally { c.close() }
            val valued = withContext(Dispatchers.IO) {
                val e = ElectrumClient(ep, com.kilombino.pyblockwatch.data.Store.BUILT_IN_PINS["${ep.host}:${ep.port}"])
                e.connect()
                try { BondValues.value(ob.offers, ElectrumChainLookup(e)) } finally { e.close() }
            }
            // An amount the market takes: 30,000,000 sats clamped into the first offer's range.
            val amount = valued.firstOrNull()?.offer?.let { maxOf(it.minsize, minOf(30_000_000L, it.maxsize)) } ?: 30_000_000L
            val pick = MakerSelection.choose(valued, amount, n = 9)
            report(listOf("[mainnet] nick authenticated: ${c.authenticated}, directory features: ${c.directoryFeatures.sorted()}, peers listed: ${c.peers.size}") +
                summary("mainnet", ob, valued, amount, pick))
            assertTrue(c.authenticated)
        }
    }
}
