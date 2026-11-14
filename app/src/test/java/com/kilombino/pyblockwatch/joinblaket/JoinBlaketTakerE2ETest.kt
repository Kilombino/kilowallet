package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.math.BigDecimal
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Base64

/**
 * End to end on the regtest lab (`/mnt/datos2/jm-043-regtest`): the real [TakerSession] against
 * the lab's directory (nick authentication on) and its bonded maker1, chain lookups and broadcast
 * through the lab node's RPC. Skipped unless `JB_E2E_DIRECTORY` (`127.0.0.1:15322`),
 * `JB_E2E_DIRECTORY_ID` (the same) and `JB_E2E_RPC` (`http://cj:cj@127.0.0.1:19443`, with a
 * loaded `miner` wallet that has coins) are set; `/mnt/datos3/reconstruible/jb-taker-c/run-e2e.sh`
 * starts and stops the lab around it.
 *
 * Each test funds a fresh taker key from the miner wallet and mines 6 blocks (makers want the
 * PoDLE coin 5 deep). `a`: a full CoinJoin, mined, every input signed with 0x21, the recipient
 * paid exactly, the change and fees as computed. `b`: the maker's signature altered on arrival:
 * the round fails and nothing spends our coin.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class JoinBlaketTakerE2ETest {
    private fun env(k: String): String? = System.getenv(k)?.takeIf { it.isNotBlank() }
    private val dir = env("JB_E2E_DIRECTORY")
    private val dirId = env("JB_E2E_DIRECTORY_ID")
    private val node = env("JB_E2E_RPC")
    private val net = JbNetwork.REGTEST

    private fun report(line: String) {
        println(line)
        env("JB_REPORT")?.let { java.io.File(it).appendText(line + "\n") }
    }

    private fun rpc(method: String, vararg params: Any?, wallet: String? = null): Any? {
        val u = URL(node!!)
        val c = URL("http://${u.host}:${u.port}/" + (wallet?.let { "wallet/$it" } ?: "")).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(u.userInfo.toByteArray()))
        val body = JSONObject().put("jsonrpc", "1.0").put("id", "jb").put("method", method)
            .put("params", JSONArray().also { a -> params.forEach { a.put(it ?: JSONObject.NULL) } })
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val text = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        val o = JSONObject(text)
        if (!o.isNull("error")) throw IllegalStateException("$method: ${o.get("error")}")
        return o.opt("result").takeIf { it != JSONObject.NULL }
    }

    private fun sats(btc: Any): Long = BigDecimal(btc.toString()).movePointRight(8).longValueExact()
    private fun mine(n: Int) { rpc("generatetoaddress", n, rpc("getnewaddress", wallet = "miner")) }
    private fun newKey(): BigInteger = BigInteger(256, SecureRandom()).mod(com.kilombino.pyblockwatch.crypto.Secp256k1.N.subtract(BigInteger.ONE)).add(BigInteger.ONE)
    private fun addressOf(key: BigInteger) = net.p2wpkhAddress(TakerTx.p2wpkh(TakerCoin("00".repeat(32), 0, 0, key, 0).pubkey))

    /** A coin of [btc] to a fresh key, 6 blocks deep. */
    private fun fund(btc: String): TakerCoin {
        val key = newKey()
        val txid = rpc("sendtoaddress", addressOf(key), btc, wallet = "miner") as String
        mine(6)
        val spk = TakerTx.p2wpkh(TakerCoin(txid, 0, 0, key, 0).pubkey)
        for (vout in 0..1) {
            val o = rpc("gettxout", txid, vout, true) as? JSONObject ?: continue
            if (o.getJSONObject("scriptPubKey").getString("hex") != spk.toHex()) continue
            val height = (rpc("getblockcount") as Number).toInt() - o.getInt("confirmations") + 1
            return TakerCoin(txid, vout.toLong(), sats(o.get("value")), key, height)
        }
        error("funding output not found")
    }

    private fun session(store: CommitmentStore, tamper: ((ByteArray) -> ByteArray)? = null, log: MutableList<String>) = TakerSession(object : TakerEnv {
        override fun directory(identity: NickIdentity): DirectoryClient {
            val (h, p) = dir!!.split(":")
            return DirectoryClient(h, p.toInt(), identity, net.wire, dirId, requireVerified = true)
        }
        override val chain: ChainLookup = RpcChainLookup { m, p -> rpc(m, *p.toTypedArray()) }
        override fun broadcast(rawHex: String): String = rpc("sendrawtransaction", rawHex) as String
        override val commitments = store
        override suspend fun confirm(plan: TakerPlan): Boolean {
            report("[e2e] plan: ${plan.makers.size} maker(s), maker fees ${plan.makerFees}, mining fee ~${plan.miningFeeEstimate} (max ${plan.miningFeeMax}) at ${"%.3f".format(plan.feeRate)} sat/vB")
            return true
        }
        override fun progress(step: TakerStep) { log += step::class.simpleName!! }
        override fun log(line: String) { println("[taker] $line") }
    }, TakerConfig(network = net, orderbook = Orderbook.Timing(minWaitMs = 10_000, quietMs = 5_000, maxWaitMs = 60_000)), tamper)

    private fun request(coin: TakerCoin, recipient: String, change: String, amount: Long) =
        TakerRequest(recipient, amount, feeRate = 2.0, coins = listOf(coin), changeAddress = change, makers = 9, minimumMakers = 1)

    @Test fun a_fullCoinJoinWithMaker1() {
        assumeTrue(dir != null && dirId != null && node != null)
        val coin = fund("0.5")
        val recipientKey = newKey(); val changeKey = newKey()
        val recipient = addressOf(recipientKey); val change = addressOf(changeKey)
        val amount = 10_000_000L
        val store = FileCommitmentStore(java.io.File.createTempFile("commitments", ".json").apply { delete(); deleteOnExit() })
        val steps = ArrayList<String>()
        val result = runBlocking { session(store, log = steps).run(request(coin, recipient, change, amount)) }
        report("[e2e] result: $result".take(400))
        assertTrue("CoinJoin not sent: $result", result is TakerResult.Sent)
        val sent = result as TakerResult.Sent
        assertEquals("one commitment burned", 1, store.count())
        assertTrue("txid seen by the node", sent.seen)
        // The whole mining fee as the node counts it, before mining.
        val mempoolFee = sats((rpc("getmempoolentry", sent.txid) as JSONObject).getJSONObject("fees").get("base"))
        val decoded = rpc("decoderawtransaction", sent.rawHex) as JSONObject
        mine(1)

        val vin = decoded.getJSONArray("vin"); val vout = decoded.getJSONArray("vout")
        val witnessEnds = (0 until vin.length()).map { vin.getJSONObject(it).getJSONArray("txinwitness").getString(0).takeLast(2) }
        val outs = (0 until vout.length()).map { vout.getJSONObject(it) }
        fun outTo(addr: String) = outs.filter { it.getJSONObject("scriptPubKey").optString("address") == addr }
        val pay = outTo(recipient); val back = outTo(change)
        val confirmations = (rpc("gettxout", sent.txid, pay.single().getInt("n"), false) as JSONObject).getInt("confirmations")
        val expectedChange = coin.value - amount - sent.makerFees - sent.txFee
        report("[e2e] txid ${sent.txid}: ${vin.length()} inputs, ${vout.length()} outputs, confirmations $confirmations, " +
            "witness hash types $witnessEnds, makers ${sent.makers}, maker fees ${sent.makerFees}, our tx fee ${sent.txFee}, " +
            "miner fee ${sent.minerFee} (node: $mempoolFee), change ${sent.change} (expected $expectedChange), pushed by a maker: ${sent.viaMaker}")
        assertTrue(confirmations >= 1)
        assertTrue("every witness signature ends in 0x21", witnessEnds.all { it == "21" })
        assertEquals(amount, sats(pay.single().get("value")))
        assertEquals(expectedChange, sats(back.single().get("value")))
        assertEquals(expectedChange, sent.change)
        assertEquals(mempoolFee, sent.minerFee)
        // maker1 offers sw0reloffer 0.001 with txfee 0: 0.1 % of the amount, and the mining fee is ours alone.
        assertEquals(amount / 1000, sent.makerFees)
        assertEquals(sent.txFee, sent.minerFee)
        assertTrue("confirmed before anything was sent", steps.contains("Confirm") && steps.indexOf("Confirm") < steps.indexOf("WaitingForMakers"))
        assertEquals("Broadcasting", steps.last())
        mine(2) // maker1's change confirms, so it can offer again for test b
    }

    @Test fun b_tamperedMakerSignatureBroadcastsNothing() {
        assumeTrue(dir != null && dirId != null && node != null)
        Thread.sleep(15_000) // maker1 re-announces its offer after the blocks of test a
        val coin = fund("0.3")
        val store = FileCommitmentStore(java.io.File.createTempFile("commitments", ".json").apply { delete(); deleteOnExit() })
        val flip: (ByteArray) -> ByteArray = { sig -> sig.copyOf().also { it[10] = (it[10].toInt() xor 0x01).toByte() } }
        val result = runBlocking { session(store, flip, ArrayList()).run(request(coin, addressOf(newKey()), addressOf(newKey()), 5_000_000L)) }
        report("[e2e] tampered signature: $result")
        assertTrue(result is TakerResult.Failed)
        assertEquals(TakerText.BAD_SIGNATURE, (result as TakerResult.Failed).reason)
        // Our coin is still unspent, counting the mempool: nothing went out.
        assertNotNull(rpc("gettxout", coin.txid, coin.vout, true))
        report("[e2e] tampered signature: coin still unspent (mempool included), nothing broadcast")
    }
}
