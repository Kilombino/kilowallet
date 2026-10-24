package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.coinjoin.CoinjoinHub
import com.kilombino.pyblockwatch.coinjoin.CoinjoinTx
import com.kilombino.pyblockwatch.coinjoin.PoolSession
import com.kilombino.pyblockwatch.coinjoin.Protocol
import com.kilombino.pyblockwatch.coinjoin.RelayClient
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
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
 * derived from the same key file, so the test coins can be swept back. KILOJOIN_PASSWORD joins a
 * private pool; every coin at the key's address goes in (up to the pool's coins per person), so
 * two small coins can make up one seat. A v5 payment pool is joined like any other: its amount is
 * the payments' total (one output each).
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
        // KILOJOIN_KEY_OFFSET picks a coin left by an earlier round (base key + offset).
        val key = BigInteger(java.io.File(keyFile).readText().trim(), 16)
            .add(BigInteger.valueOf(System.getenv("KILOJOIN_KEY_OFFSET")?.toLong() ?: 0L)).mod(Secp256k1.N)
        val pub = Secp256k1.compress(Secp256k1.multiply(key, Secp256k1.G))
        fun derived(n: Long) = key.add(BigInteger.valueOf(n)).mod(Secp256k1.N)
        // KILOJOIN_OUTPUTS mixed outputs per person (1 by default): keys +1…+n, change on the next one.
        val outs = System.getenv("KILOJOIN_OUTPUTS")?.toInt() ?: 1
        val mixes = (1..outs).map { Address.scriptPubKey(Secp256k1.compress(Secp256k1.multiply(derived(it.toLong()), Secp256k1.G)), ScriptType.P2WPKH) }
        val change = Address.scriptPubKey(Secp256k1.compress(Secp256k1.multiply(derived(outs + 1L), Secp256k1.G)), ScriptType.P2WPKH)
        val addr = Address.encode(pub, ScriptType.P2WPKH)
        println("peer address $addr · mix ${Address.encode(Secp256k1.compress(Secp256k1.multiply(derived(1), Secp256k1.G)), ScriptType.P2WPKH)}")

        val scan = rpc("scantxoutset", "start", JSONArray().put("addr($addr)")) as JSONObject
        val un = scan.getJSONArray("unspents")
        val all = (0 until un.length()).map { un.getJSONObject(it) }
            .map { u -> CoinjoinTx.Coin(u.getString("txid"), u.getInt("vout"), Math.round(u.getDouble("amount") * 1e8), pub) }
            .sortedByDescending { it.value }
        require(all.isNotEmpty()) { "no coin at $addr" }
        all.forEach { println("coin ${it.outpoint} ${it.value}") }

        val wanted = System.getenv("KILOJOIN_AMOUNT")?.toLong() ?: 1000L
        var terms: Protocol.Terms? = null
        val end = System.currentTimeMillis() + 30 * 60_000
        while (terms == null) {
            require(System.currentTimeMillis() < end) { "no test pool showed up" }
            terms = CoinjoinHub.fetchPools(3600).firstOrNull { t ->
                val c = all.take(t.maxInputs)
                t.amount == wanted && t.outputs == outs && CoinjoinTx.change(c.sumOf { it.value }, t.amount, t.feeRate, c.size, t.script, outs) != null
            }
            if (terms == null) { println("waiting for a pool of $outs × $wanted sats…"); Thread.sleep(15_000) }
        }
        println("joining pool ${terms.id}: v${terms.version} ${terms.amount} sats, ${terms.feeRate} sat/vB, payments ${terms.payments}")
        val coins = all.take(terms.maxInputs)

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
        val s = PoolSession(env, PoolSession.newState(terms, false, null, coins, coins.map { key }, coins.map { "file" }, mixes, change,
            password = System.getenv("KILOJOIN_PASSWORD")))
        s.start()
        var voted: String? = null; var signed = false
        val stop = System.currentTimeMillis() + 60 * 60_000
        while (System.currentTimeMillis() < stop) {
            val st = s.state
            if (st.phase == PoolSession.Phase.VOTING && st.voteId != voted) { voted = st.voteId; s.vote(true); println("voted yes") }
            if (st.phase == PoolSession.Phase.SIGNING && !signed) {
                println("plan txid ${CoinjoinTx.txid(s.plan()!!)} fee ${s.plan()!!.fee}")
                s.sign(coins.map { key }); signed = true; println("signed")
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
        val keys = (0L..30L).map { key.add(BigInteger.valueOf(it)).mod(Secp256k1.N) }
        // One scan for every key: each scantxoutset walks the whole UTXO set, so one per key is slow.
        val byAddr = keys.associateBy { k -> Address.encode(Secp256k1.compress(Secp256k1.multiply(k, Secp256k1.G)), ScriptType.P2WPKH) }
        val scan = rpc("scantxoutset", "start", JSONArray().also { a -> byAddr.keys.forEach { a.put("addr($it)") } }) as JSONObject
        val u = scan.getJSONArray("unspents")
        val inputs = (0 until u.length()).map { i -> u.getJSONObject(i).let {
            val addr = Address.encode(
                Secp256k1.compress(Secp256k1.multiply(byAddr.entries.first { e -> Address.scriptPubKey(
                    Secp256k1.compress(Secp256k1.multiply(e.value, Secp256k1.G)), ScriptType.P2WPKH).toHex() == it.getString("scriptPubKey") }.value, Secp256k1.G)),
                ScriptType.P2WPKH)
            val k = byAddr.getValue(addr)
            com.kilombino.pyblockwatch.crypto.TxBuilder.Input(it.getString("txid"), it.getInt("vout"),
                Math.round(it.getDouble("amount") * 1e8), k, Secp256k1.compress(Secp256k1.multiply(k, Secp256k1.G)), 0xfffffffdL, ScriptType.P2WPKH)
        } }
        require(inputs.isNotEmpty()) { "nothing to sweep" }
        val total = inputs.sumOf { it.value }
        val fee = kotlin.math.ceil(2.0 * (10.5 + 68.0 * inputs.size + 31.0)).toLong()
        val out = com.kilombino.pyblockwatch.crypto.TxBuilder.Output(Address.decodeToScriptPubKey(to), total - fee)
        val tx = com.kilombino.pyblockwatch.crypto.TxBuilder.build(inputs, listOf(out), unified = true, grindLowR = true)
        println("sweep ${inputs.size} coins, $total sats - $fee fee -> $to")
        println("SWEPT " + rpc("sendrawtransaction", tx.rawHex))
    }
}
