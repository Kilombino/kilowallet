package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.coinjoin.CoinjoinHub
import com.kilombino.pyblockwatch.coinjoin.CoinjoinTx
import com.kilombino.pyblockwatch.coinjoin.PoolSession
import com.kilombino.pyblockwatch.coinjoin.Protocol
import com.kilombino.pyblockwatch.coinjoin.RelayClient
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * A second participant for trying a real pool on mainnet against the app: finds the open test
 * pool on the relay, joins with the coin of a key file, says yes to closing and signs as soon
 * as the round is ready. Runs only with KILOJOIN_PEER=<key file> (hex private key) and
 * KILOJOIN_RPC=<user:pass@host:port> of a BLAKE2b node. Mixed output and change go to keys
 * derived from the same key file, so the test coins can be swept back.
 */
class CoinjoinMainnetPeer {
    private lateinit var rpcUrl: String
    private lateinit var auth: String

    private fun rpc(method: String, vararg params: Any): Any? {
        val c = URL(rpcUrl).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(auth.toByteArray()))
        c.outputStream.write(JSONObject().put("method", method).put("params", JSONArray(params.toList())).toString().toByteArray())
        val o = JSONObject((if (c.responseCode == 200) c.inputStream else c.errorStream).bufferedReader().readText())
        if (!o.isNull("error")) throw IllegalStateException(o.getJSONObject("error").getString("message"))
        return o.opt("result")
    }

    @Test fun joinTheTestPool() {
        val keyFile = System.getenv("KILOJOIN_PEER") ?: return
        val (cred, host) = System.getenv("KILOJOIN_RPC")!!.split("@")
        auth = cred; rpcUrl = "http://$host/"
        val key = BigInteger(java.io.File(keyFile).readText().trim(), 16)
        val pub = Secp256k1.compress(Secp256k1.multiply(key, Secp256k1.G))
        fun derived(n: Long) = key.add(BigInteger.valueOf(n)).mod(Secp256k1.N)
        val mix = Address.scriptPubKey(Secp256k1.compress(Secp256k1.multiply(derived(1), Secp256k1.G)), ScriptType.P2WPKH)
        val change = Address.scriptPubKey(Secp256k1.compress(Secp256k1.multiply(derived(2), Secp256k1.G)), ScriptType.P2WPKH)
        val addr = Address.encode(pub, ScriptType.P2WPKH)
        println("peer address $addr · mix ${Address.encode(Secp256k1.compress(Secp256k1.multiply(derived(1), Secp256k1.G)), ScriptType.P2WPKH)}")

        val scan = rpc("scantxoutset", "start", JSONArray().put("addr($addr)")) as JSONObject
        val u = scan.getJSONArray("unspents").getJSONObject(0)
        val coin = CoinjoinTx.Coin(u.getString("txid"), u.getInt("vout"), Math.round(u.getDouble("amount") * 1e8), pub)
        println("coin ${coin.outpoint} ${coin.value}")

        val wanted = System.getenv("KILOJOIN_AMOUNT")?.toLong() ?: 1000L
        var terms: Protocol.Terms? = null
        val end = System.currentTimeMillis() + 30 * 60_000
        while (terms == null) {
            require(System.currentTimeMillis() < end) { "no test pool showed up" }
            terms = CoinjoinHub.fetchPools(3600).firstOrNull { it.amount == wanted && CoinjoinTx.change(coin.value, it.amount, it.feeRate) != null }
            if (terms == null) { println("waiting for a pool of $wanted sats…"); Thread.sleep(15_000) }
        }
        println("joining pool ${terms.id}: ${terms.amount} sats, ${terms.feeRate} sat/vB")

        val env = object : PoolSession.Env {
            override fun coinUnspent(coin: CoinjoinTx.Coin): Boolean {
                val o = rpc("gettxout", coin.txid, coin.vout, true) as? JSONObject ?: return false
                return Math.round(o.getDouble("value") * 1e8) == coin.value
            }
            override fun broadcast(rawHex: String): String = rpc("sendrawtransaction", rawHex) as String
            override fun confirmations(txid: String): Int? {
                val o = rpc("gettxout", txid, 0, true) as? JSONObject ?: return null
                return o.getInt("confirmations")
            }
            override fun save(state: PoolSession.State) {}
            override fun log(msg: String) = println("  $msg")
            override fun event(e: PoolSession.Event) { if (e !is PoolSession.Event.Changed) println("EVENT ${e.javaClass.simpleName}") }
        }
        val s = PoolSession(env, PoolSession.newState(terms, false, null, coin, key, "file", mix, change))
        s.start()
        var voted: String? = null; var signed = false
        val stop = System.currentTimeMillis() + 60 * 60_000
        while (System.currentTimeMillis() < stop) {
            val st = s.state
            if (st.phase == PoolSession.Phase.VOTING && st.voteId != voted) { voted = st.voteId; s.vote(true); println("voted yes") }
            if (st.phase == PoolSession.Phase.SIGNING && !signed) {
                println("plan txid ${CoinjoinTx.txid(s.plan()!!)} fee ${s.plan()!!.fee}")
                s.sign(key); signed = true; println("signed")
            }
            if (st.phase == PoolSession.Phase.BROADCAST && st.txid != null) { println("BROADCAST ${st.txid}"); break }
            if (st.phase in setOf(PoolSession.Phase.ABORTED, PoolSession.Phase.REJECTED)) { println("ENDED ${st.phase} ${st.reason}"); break }
            Thread.sleep(2000)
        }
        s.stop()
    }
    /**
     * Sends everything the test peer holds (its key and the two derived ones: mixed output and
     * change) to KILOJOIN_SWEEP_TO, unified-signed, through the same node.
     */
    @Test fun sweepBack() {
        val keyFile = System.getenv("KILOJOIN_PEER") ?: return
        val to = System.getenv("KILOJOIN_SWEEP_TO") ?: return
        val (cred, host) = System.getenv("KILOJOIN_RPC")!!.split("@")
        auth = cred; rpcUrl = "http://$host/"
        val key = BigInteger(java.io.File(keyFile).readText().trim(), 16)
        val keys = (0L..2L).map { key.add(BigInteger.valueOf(it)).mod(Secp256k1.N) }
        val inputs = keys.flatMap { k ->
            val pub = Secp256k1.compress(Secp256k1.multiply(k, Secp256k1.G))
            val scan = rpc("scantxoutset", "start", JSONArray().put("addr(${Address.encode(pub, ScriptType.P2WPKH)})")) as JSONObject
            val u = scan.getJSONArray("unspents")
            (0 until u.length()).map { i -> u.getJSONObject(i).let {
                com.kilombino.pyblockwatch.crypto.TxBuilder.Input(it.getString("txid"), it.getInt("vout"),
                    Math.round(it.getDouble("amount") * 1e8), k, pub, 0xfffffffdL, ScriptType.P2WPKH)
            } }
        }
        require(inputs.isNotEmpty()) { "nothing to sweep" }
        val total = inputs.sumOf { it.value }
        val fee = kotlin.math.ceil(2.0 * (10.5 + 68.0 * inputs.size + 31.0)).toLong()
        val out = com.kilombino.pyblockwatch.crypto.TxBuilder.Output(Address.decodeToScriptPubKey(to), total - fee)
        val tx = com.kilombino.pyblockwatch.crypto.TxBuilder.build(inputs, listOf(out), unified = true, grindLowR = true)
        println("sweep ${inputs.size} coins, $total sats - $fee fee -> $to")
        println("SWEPT " + rpc("sendrawtransaction", tx.rawHex))
    }
}
