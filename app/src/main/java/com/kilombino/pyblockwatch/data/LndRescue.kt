package com.kilombino.pyblockwatch.data

import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Aezeed
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.TxParse
import java.math.BigInteger

/**
 * Rescue the XBT of a Lightning (LND) wallet that existed before the fork, without touching
 * the same wallet on the SHA-256 chain.
 *
 * From the aezeed it derives every key LND's on-chain wallet uses (BIP-84, BIP-49 and BIP-86,
 * receive and change) plus the channel payment keys (m/1017'/0'/3'/0/i, where a peer's force
 * close pays us), walks each branch until [gap] unused keys in a row, and collects:
 *  - the coins those keys hold on the BLAKE2b chain, to be swept with the unified sighash, which
 *    is not valid on the SHA-256 chain;
 *  - the cooperative channel closes the same wallet made on the SHA-256 chain after the fork.
 *    They spend a channel's 2-of-2 funding output, which on BLAKE2b is still unspent, with
 *    legacy signatures valid on both chains: replaying them on BLAKE2b closes the channel there
 *    too and pays our share to our own address. Nothing else is ever replayed: an ordinary send
 *    replayed would pay the same recipient on BLAKE2b.
 */
class LndRescue(seed: Aezeed.Seed) {

    class Key(val path: String, val type: ScriptType, val privateKey: BigInteger, val pubkey: ByteArray) {
        val address: String get() = Address.encode(pubkey, type)
        val script: ByteArray get() = Address.scriptPubKey(pubkey, type)
        val scriptHash: String get() = Address.electrumScriptHash(script)
    }

    class Coin(val key: Key, val txid: String, val vout: Int, val value: Long, val height: Int)

    /** A post-fork SHA-256 cooperative close that is not on BLAKE2b yet. */
    class Replay(val txid: String, val rawHex: String, val outputsToUs: List<Coin>) {
        val toUsSats: Long get() = outputsToUs.sumOf { it.value }
    }

    class Result(
        val coins: List<Coin>,
        val replays: List<Replay>,
        /** Post-fork SHA-256 force closes found: their delayed outputs need channel data. */
        val forceCloses: Int,
        val keysUsed: Int,
        val keysScanned: Int,
    )

    private val root = Bip32Priv.fromSeed(seed.entropy)

    private val branches = listOf(
        "m/84'/0'/0'/0" to ScriptType.P2WPKH, "m/84'/0'/0'/1" to ScriptType.P2WPKH,
        "m/49'/0'/0'/0" to ScriptType.P2SH_P2WPKH, "m/49'/0'/0'/1" to ScriptType.P2SH_P2WPKH,
        "m/86'/0'/0'/0" to ScriptType.P2TR, "m/86'/0'/0'/1" to ScriptType.P2TR,
        "m/1017'/0'/3'/0" to ScriptType.P2WPKH,
    )

    fun scan(xbt: ElectrumClient, sha: ElectrumClient?, gap: Int, progress: (String) -> Unit): Result {
        val coins = ArrayList<Coin>()
        val ours = HashMap<String, Key>()            // scriptPubKey hex → key
        val shaOnly = LinkedHashSet<String>()         // txids seen on SHA-256 but not on BLAKE2b
        var used = 0; var scanned = 0
        for ((base, type) in branches) {
            val node = Bip32Priv.derivePath(root, base)
            var i = 0; var empty = 0
            while (empty < gap) {
                val child = Bip32Priv.deriveChild(node, i)
                val key = Key("$base/$i", type, child.key, child.publicKey())
                with(com.kilombino.pyblockwatch.crypto.Hashes) { ours[key.script.toHex()] = key }
                val xh = xbt.history(key.scriptHash)
                val sh = sha?.let { runCatching { it.history(key.scriptHash) }.getOrDefault(emptyList()) } ?: emptyList()
                scanned++
                if (xh.isEmpty() && sh.isEmpty()) empty++ else {
                    empty = 0; used++
                    if (xh.isNotEmpty()) xbt.listUnspent(key.scriptHash).forEach {
                        coins += Coin(key, it.txid, it.vout, it.value, it.height)
                    }
                    val onXbt = xh.map { it.txid }.toSet()
                    sh.forEach { if (it.txid !in onXbt) shaOnly += it.txid }
                }
                if (scanned % 10 == 0) progress("checked $scanned addresses, $used used…")
                i++
            }
        }
        val replays = ArrayList<Replay>()
        var forceCloses = 0
        if (sha != null) for (txid in shaOnly) {
            progress("checking ${replays.size + forceCloses + 1} of ${shaOnly.size} SHA-256 transactions…")
            val raw = runCatching { sha.transaction(txid) }.getOrNull() ?: continue
            val tx = runCatching { TxParse.parse(raw) }.getOrNull() ?: continue
            if (!TxParse.spendsChannelFunding(tx)) continue
            if (TxParse.isCommitment(tx)) { forceCloses++; continue }
            // Already on BLAKE2b (seen through another address)? Then its outputs were scanned.
            if (runCatching { xbt.transaction(txid) }.isSuccess) continue
            val toUs = tx.outputs.mapIndexedNotNull { vout, o ->
                val key = with(com.kilombino.pyblockwatch.crypto.Hashes) { ours[o.scriptPubKey.toHex()] }
                key?.let { Coin(it, txid, vout, o.value, 0) }
            }
            if (toUs.isNotEmpty()) replays += Replay(txid, raw, toUs)
        }
        return Result(coins, replays, forceCloses, used, scanned)
    }
}
