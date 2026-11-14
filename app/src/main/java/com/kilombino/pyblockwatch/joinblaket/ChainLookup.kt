package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.TxParse
import com.kilombino.pyblockwatch.data.JamWatch
import org.json.JSONObject

/**
 * What the taker must look up on the chain about coins it does not own: fidelity bonds and
 * makers' inputs (`get_utxo`, `get_block_height`, `get_block_time` in
 * `jmwallet/backends/base.py`). Blocking: call it from `Dispatchers.IO`.
 *
 * Every method throws when the backend cannot answer, so "unavailable" is never confused with
 * "spent" (`taker/coinjoin_session.py:1070-1158`).
 */
interface ChainLookup {
    /** The tip height. */
    fun height(): Int

    /**
     * The output [txid]:[vout] if it is unspent (mempool spends count as spent; an unconfirmed
     * output has 0 confirmations), else null. [scriptPubKey], when the caller knows it (a bond's
     * P2WSH, an extended UTXO reference), lets a script-indexed backend find the coin; the answer
     * carries the chain's own value and scriptPubKey either way.
     */
    fun utxo(txid: String, vout: Long, scriptPubKey: ByteArray? = null): IoauthCheck.ChainUtxo?

    /** The `time` field of the header of block [height]. */
    fun blockTime(height: Int): Long
}

/**
 * [ChainLookup] over an Electrum server (Shulcrum: BLAKE2b headers of 164 bytes). There is no
 * Python reference for this backend (spec 8): the coin's value and scriptPubKey come from the
 * transaction itself, which must hash to [txid] (so the server cannot change them); that it is
 * unspent and its height come from `blockchain.scripthash.listunspent` on that scriptPubKey. The
 * height is the server's word unless [prove] checks it (e.g. `HeaderChain.proves` with SPV), in
 * which case a coin it cannot prove is treated as unconfirmed.
 */
class ElectrumChainLookup(
    private val client: ElectrumClient,
    private val prove: ((txid: String, height: Int) -> Boolean?)? = null,
) : ChainLookup {
    init { require(!client.isOwnNode) { "a node by RPC: use RpcChainLookup" } }

    override fun height(): Int = client.blockHeight()

    override fun utxo(txid: String, vout: Long, scriptPubKey: ByteArray?): IoauthCheck.ChainUtxo? {
        fun unspent(spk: ByteArray) = client.listUnspent(Hashes.sha256(spk).reversedArray().toHex())
            .firstOrNull { it.txid.equals(txid, true) && it.vout.toLong() == vout }
        // With the script known, a coin the server does not list is simply not there (no tx fetch).
        val listed = scriptPubKey?.let { unspent(it) ?: return null }
        val out = TxParse.parse(client.transaction(txid)).outputs.getOrNull(vout.toInt()) ?: return null
        if (scriptPubKey != null && !scriptPubKey.contentEquals(out.scriptPubKey)) return null
        val u = listed ?: unspent(out.scriptPubKey) ?: return null
        val proven = u.height > 0 && (prove == null || prove.invoke(txid, u.height) == true)
        val confirmations = if (proven) height() - u.height + 1 else 0
        return IoauthCheck.ChainUtxo(out.value, out.scriptPubKey, confirmations)
    }

    override fun blockTime(height: Int): Long = JamWatch.blockTime(client.blockHeader(height))
}

/**
 * [ChainLookup] over a node's JSON-RPC, as the Python taker does it (`gettxout` with the mempool,
 * `getblockcount`, `getblockhash` + `getblockheader`). [call] is one RPC call (method, params) returning
 * its `result`, e.g. [com.kilombino.pyblockwatch.chain.NodeRpcBackend.rpc].
 */
class RpcChainLookup(private val call: (method: String, params: List<Any?>) -> Any?) : ChainLookup {
    override fun height(): Int = (call("getblockcount", emptyList()) as Number).toInt()

    override fun utxo(txid: String, vout: Long, scriptPubKey: ByteArray?): IoauthCheck.ChainUtxo? {
        val o = call("gettxout", listOf(txid, vout, true)) as? JSONObject ?: return null
        val spk = Wire.hex(o.getJSONObject("scriptPubKey").getString("hex"))
        // Amounts are decimal BTC; read them as text so 8 decimals never go through a double.
        val value = java.math.BigDecimal(o.get("value").toString()).movePointRight(8).longValueExact()
        return IoauthCheck.ChainUtxo(value, spk, o.optInt("confirmations", 0))
    }

    override fun blockTime(height: Int): Long {
        val hash = call("getblockhash", listOf(height)) as String
        return (call("getblockheader", listOf(hash, true)) as JSONObject).getLong("time")
    }
}
