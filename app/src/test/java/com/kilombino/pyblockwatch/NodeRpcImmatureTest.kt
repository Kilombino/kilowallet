package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.chain.NodeRpcBackend
import com.kilombino.pyblockwatch.chain.RpcConn
import com.kilombino.pyblockwatch.chain.RpcNode
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bip32
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.InetSocketAddress

/**
 * A mined reward still maturing, read from the user's own node by RPC: listunspent leaves it
 * out, so a miner's balance read 0 and the quick look stopped asking about the address. It has
 * to count as an Electrum server counts it. A fake node answers the RPC calls.
 */
class NodeRpcImmatureTest {
    private val xpub = "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteo" +
        "GVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ"

    @Test fun aMaturingRewardCountsInTheBalance() {
        val spk = Address.scriptPubKey(Bip32.derivePath(Bip32.parseExtendedPubKey(xpub), 0, 0).pubkey(), ScriptType.P2WPKH)
        val sh = Address.electrumScriptHash(spk)
        val reward = "ab".repeat(32); val older = "cd".repeat(32)
        fun coinbase(conf: Int) = JSONObject().put("confirmations", conf).put("blockheight", 1000 - conf + 1)
            .put("decoded", JSONObject()
                .put("vin", JSONArray().put(JSONObject().put("coinbase", "03e803")))
                .put("vout", JSONArray()
                    .put(JSONObject().put("n", 0).put("value", 3.125).put("scriptPubKey", JSONObject().put("hex", "0014" + "11".repeat(20))))
                    .put(JSONObject().put("n", 1).put("value", 0.0164).put("scriptPubKey", JSONObject().put("hex", spk.toHex())))))
        val name = NodeRpcBackend(RpcNode(emptyList(), xpub, ScriptType.P2WPKH)).walletName
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val req = JSONObject(ex.requestBody.readBytes().decodeToString())
            val p = req.getJSONArray("params")
            val result: Any = when (req.getString("method")) {
                "getdeploymentinfo" -> JSONObject().put("blake2b", JSONObject().put("active", true))
                "getnetworkinfo" -> JSONObject().put("subversion", "/Satoshi:29.5.0/")
                "listwallets" -> JSONArray().put(name)
                "getblockcount" -> 1000
                // The reward 2 blocks deep is still maturing; the older one has matured and is in listunspent.
                "listtransactions" -> JSONArray()
                    .put(JSONObject().put("txid", reward).put("category", "immature").put("vout", 1).put("amount", 0.0164).put("confirmations", 2))
                    .put(JSONObject().put("txid", older).put("category", "generate").put("vout", 1).put("amount", 0.0164).put("confirmations", 150))
                "listunspent" -> JSONArray()
                    .put(JSONObject().put("txid", older).put("vout", 1).put("amount", 0.0164).put("confirmations", 150).put("scriptPubKey", spk.toHex()))
                "gettransaction" -> when (p.getString(0)) { reward -> coinbase(2); older -> coinbase(150); else -> JSONObject() }
                else -> JSONObject.NULL
            }
            val body = JSONObject().put("result", result).put("error", JSONObject.NULL).put("id", "kw").toString().toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val node = NodeRpcBackend(RpcNode(listOf(RpcConn("http://127.0.0.1:${server.address.port}/", "u", "p")), xpub, ScriptType.P2WPKH))
            node.connect()
            val coins = node.listUnspent(sh)
            assertEquals(2, coins.size)
            val young = coins.firstOrNull { it.txid == reward }
            assertNotNull("the maturing reward is listed", young)
            assertEquals(1, young!!.vout); assertEquals(1_640_000L, young.value); assertEquals(999, young.height)
            assertEquals(3_280_000L, node.balance(sh).confirmed)
            assertEquals(setOf(reward, older), node.history(sh).map { it.txid }.toSet())
        } finally { server.stop(0) }
    }
}
