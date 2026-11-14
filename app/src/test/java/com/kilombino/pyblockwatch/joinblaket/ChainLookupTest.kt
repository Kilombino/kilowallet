package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.chain.NodeEndpoint
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.TxParse
import com.kilombino.pyblockwatch.joinblaket.FakeDirectory.Companion.offerVec
import com.kilombino.pyblockwatch.joinblaket.FakeDirectory.Companion.proofFor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/** [ElectrumChainLookup] against an in-process plain-TCP Electrum server, and [RpcChainLookup] on canned answers. */
class ChainLookupTest {
    private val taker = offerVec.getString("taker_nick")
    private val maker = offerVec.getString("maker_nick")
    private val vecBond = BondProof.decode(offerVec.getString("proof_b64"))!!
    private val inputs = offerVec.getJSONObject("bond_value_inputs")

    /** A transaction paying the bond's P2WSH at vout 0 (and something else at vout 1). */
    private val rawTx = "02000000" + "01" + "11".repeat(32) + "00000000" + "00" + "ffffffff" + "02" +
        Wire.le(inputs.getLong("utxo_value"), 8).toHex() + "22" + vecBond.scriptPubKey.toHex() +
        Wire.le(5_000, 8).toHex() + "160014" + "22".repeat(20) + "00000000"
    private val txid = TxParse.txid(TxParse.parse(rawTx))
    private val bond = BondProof.decode(proofFor(maker, taker, txid))!!
    private val tip = 962_000
    private val confHeight = 961_991

    /** Answers the few calls the lookup makes; a TLS hello is hung up on, so the client falls back to plain TCP. */
    private fun fakeElectrum(): ServerSocket {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    s.use {
                        val inp = s.getInputStream().buffered()
                        inp.mark(1); if (inp.read() == 0x16) return@Thread; inp.reset()
                        val out = s.getOutputStream()
                        for (line in inp.bufferedReader().lineSequence()) {
                            val req = JSONObject(line)
                            val p = req.getJSONArray("params")
                            val result: Any = when (req.getString("method")) {
                                "server.version" -> JSONArray().put("Fake 1.0").put("1.4")
                                "blockchain.headers.subscribe" -> JSONObject().put("height", tip).put("hex", "00".repeat(80))
                                "blockchain.scripthash.listunspent" ->
                                    if (p.getString(0) == Hashes.sha256(bond.scriptPubKey).reversedArray().toHex())
                                        JSONArray().put(JSONObject().put("tx_hash", txid).put("tx_pos", 0).put("height", confHeight).put("value", inputs.getLong("utxo_value")))
                                    else JSONArray()
                                "blockchain.transaction.get" -> if (p.getString(0) == txid) rawTx else JSONObject.NULL
                                "blockchain.block.header" -> "00".repeat(68) + Wire.le(inputs.getLong("confirmation_time"), 4).toHex() + "00".repeat(8)
                                else -> JSONObject.NULL
                            }
                            val reply = if (result == JSONObject.NULL && req.getString("method") == "blockchain.transaction.get")
                                JSONObject().put("id", req.get("id")).put("error", JSONObject().put("message", "unknown tx"))
                            else JSONObject().put("id", req.get("id")).put("result", result)
                            out.write((reply.toString() + "\n").toByteArray()); out.flush()
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return server
    }

    @Test fun electrumLookup() {
        fakeElectrum().use { server ->
            val client = ElectrumClient(NodeEndpoint("127.0.0.1", server.localPort, isCustom = true), null)
            client.connect()
            try {
                val chain = ElectrumChainLookup(client)
                assertEquals(tip, chain.height())
                val u = chain.utxo(txid, 0, bond.scriptPubKey)!!
                assertEquals(inputs.getLong("utxo_value"), u.value)
                assertTrue(u.scriptPubKey.contentEquals(bond.scriptPubKey))
                assertEquals(tip - confHeight + 1, u.confirmations)
                // Without the script: read from the (hash-checked) transaction, then listed by it.
                assertEquals(u.confirmations, chain.utxo(txid, 0)!!.confirmations)
                // The other output is not listed as unspent; a vout past the end does not exist.
                assertNull(chain.utxo(txid, 1)); assertNull(chain.utxo(txid, 2))
                // A script the coin is not locked to: not listed there, so not found.
                assertNull(chain.utxo(txid, 0, ByteArray(34)))
                // A transaction the server does not know, script unknown: unavailable, not "spent".
                assertTrue(runCatching { chain.utxo("ab".repeat(32), 0) }.isFailure)
                assertEquals(inputs.getLong("confirmation_time"), chain.blockTime(confHeight))
                // A height the SPV check cannot prove counts as unconfirmed.
                assertEquals(0, ElectrumChainLookup(client) { _, _ -> null }.utxo(txid, 0, bond.scriptPubKey)!!.confirmations)

                // The whole bond valuation through Electrum.
                val offer = Offers.parse("sw0reloffer 0 27300 2500000000 0 0.0002", maker)!!
                val v = BondValues.value(listOf(BondedOffer(offer, bond)), chain, inputs.getLong("current_time")).single()
                assertEquals(offerVec.getLong("bond_value"), v.value)
            } finally { client.close() }
        }
    }

    @Test fun rpcLookup() {
        val calls = ArrayList<String>()
        val chain = RpcChainLookup { method, params ->
            calls += "$method $params"
            when (method) {
                "getblockcount" -> 962_000
                "gettxout" -> if (params[0] == txid) JSONObject("{\"confirmations\": 10, \"value\": 1.00000000, " +
                    "\"scriptPubKey\": {\"hex\": \"${bond.scriptPubKey.toHex()}\"}}") else null
                "getblockhash" -> "00".repeat(32)
                "getblockheader" -> JSONObject().put("time", inputs.getLong("confirmation_time"))
                else -> error(method)
            }
        }
        val u = chain.utxo(txid, 0)!!
        assertEquals(100_000_000L, u.value); assertEquals(10, u.confirmations)
        assertNull(chain.utxo("ab".repeat(32), 0))
        assertEquals(inputs.getLong("confirmation_time"), chain.blockTime(961_991))
        assertEquals("gettxout [$txid, 0, true]", calls[0])
    }
}
