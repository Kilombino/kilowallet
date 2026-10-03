package com.kilombino.pyblockwatch.data

import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Aezeed
import com.kilombino.pyblockwatch.crypto.Bech32
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.LnKeys
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.TxParse
import java.math.BigInteger

/**
 * Rescue the XBT of a Lightning (LND) wallet that existed before the fork, without touching
 * the same wallet on the SHA-256 chain.
 *
 * From the aezeed it derives every key LND's on-chain wallet uses (BIP-84, BIP-49 and BIP-86,
 * receive and change) plus the channel payment keys (m/1017'/0'/3'/0/i: where a peer's force
 * close pays us, as P2WPKH or as an anchor-channel P2WSH), walks each branch until [gap] unused
 * keys in a row, and collects the coins those keys hold on the BLAKE2b chain. They are swept
 * with the unified sighash, which is not valid on the SHA-256 chain.
 *
 * Channel closes the same wallet made on the SHA-256 chain after the fork spend a funding
 * output that on BLAKE2b is still unspent, with legacy signatures valid on both chains:
 * replaying them on BLAKE2b closes the channel there too and pays our share to our keys.
 * Nothing but a transaction spending a 2-of-2 channel funding output is ever replayed.
 *
 * With the node's channel.backup ([channels]) every channel is listed with its state on both
 * chains, and our delayed to_local output of a force close we made is found and spent after its
 * CSV delay: its key comes from the seed, the backup and the state number hidden in the close.
 */
class LndRescue(seed: Aezeed.Seed, private val channels: List<Scb.Channel> = emptyList()) {

    /** A key and how to spend what it holds: a plain address, or a P2WSH script with a CSV. */
    class Key(
        val path: String, val type: ScriptType, val privateKey: BigInteger, val pubkey: ByteArray,
        val witnessScript: ByteArray? = null, val witnessExtra: List<ByteArray> = emptyList(), val csv: Int = 0,
    ) {
        val script: ByteArray get() = witnessScript?.let { LnKeys.p2wsh(it) } ?: Address.scriptPubKey(pubkey, type)
        val address: String get() = witnessScript?.let { Bech32.encodeSegwit("bc", 0, script.copyOfRange(2, 34)) }
            ?: Address.encode(pubkey, type)
        val scriptHash: String get() = Address.electrumScriptHash(script)
        val label: String get() = when {
            witnessScript != null && csv > 1 -> "Channel (delayed)"
            witnessScript != null -> "Channel (anchor)"
            path.startsWith("m/1017") -> "Channel"
            else -> type.label
        }
    }

    class Coin(val key: Key, val txid: String, val vout: Int, val value: Long, val height: Int) {
        /** Blocks still to wait for the key's CSV, given the chain [tip]; 0 when spendable now. */
        fun waitBlocks(tip: Int): Int = if (key.csv <= 0) 0
            else if (height <= 0) key.csv else maxOf(0, key.csv - (tip - height + 1))
    }

    /** A post-fork SHA-256 channel close that is not on BLAKE2b yet. */
    class Replay(val txid: String, val rawHex: String, val forced: Boolean, val outputsToUs: List<Coin>) {
        val toUsSats: Long get() = outputsToUs.sumOf { it.value }
    }

    enum class ChanState { OPEN_BOTH, CLOSED_SHA_ONLY, CLOSED_XBT, TAPROOT_UNCHECKED, UNKNOWN }

    class ChannelStatus(val channel: Scb.Channel, val state: ChanState, val closeTxid: String?, val forced: Boolean?)

    class Result(
        val coins: List<Coin>,
        val replays: List<Replay>,
        val channels: List<ChannelStatus>,
        /** Post-fork SHA-256 force closes whose share to us could not be found (no channel.backup). */
        val unmatchedForceCloses: Int,
        val keysUsed: Int,
        val keysScanned: Int,
        val tip: Int,
    ) {
        fun spendable(): List<Coin> = coins.filter { it.waitBlocks(tip) == 0 }
    }

    private val root = Bip32Priv.fromSeed(seed.entropy)
    private fun node(path: String) = Bip32Priv.derivePath(root, path)

    private val branches = listOf(
        "m/84'/0'/0'/0" to ScriptType.P2WPKH, "m/84'/0'/0'/1" to ScriptType.P2WPKH,
        "m/49'/0'/0'/0" to ScriptType.P2SH_P2WPKH, "m/49'/0'/0'/1" to ScriptType.P2SH_P2WPKH,
        "m/86'/0'/0'/0" to ScriptType.P2TR, "m/86'/0'/0'/1" to ScriptType.P2TR,
        "m/1017'/0'/3'/0" to ScriptType.P2WPKH,
    )

    /** Keys at index [i] of a branch: the plain one, and for payment keys the anchor P2WSH too. */
    private fun keysAt(base: String, type: ScriptType, i: Int): List<Key> {
        val n = Bip32Priv.deriveChild(node(base), i)
        val plain = Key("$base/$i", type, n.key, n.publicKey())
        if (!base.startsWith("m/1017'/0'/3'")) return listOf(plain)
        return listOf(plain, Key("$base/$i", type, n.key, n.publicKey(),
            witnessScript = LnKeys.toRemoteAnchorScript(n.publicKey()), csv = 1))
    }

    fun scan(xbt: ElectrumClient, sha: ElectrumClient?, gap: Int, progress: (String) -> Unit): Result {
        val tip = xbt.blockHeight()
        val coins = LinkedHashMap<String, Coin>()        // "txid:vout" → coin
        val ours = HashMap<String, Key>()                 // scriptPubKey hex → key
        val shaOnly = LinkedHashSet<String>()             // txids seen on SHA-256 but not on BLAKE2b
        var used = 0; var scanned = 0

        fun look(key: Key): Boolean {
            ours[key.script.toHex()] = key
            val xh = xbt.history(key.scriptHash)
            val sh = sha?.let { runCatching { it.history(key.scriptHash) }.getOrDefault(emptyList()) } ?: emptyList()
            scanned++
            if (xh.isEmpty() && sh.isEmpty()) return false
            if (xh.isNotEmpty()) xbt.listUnspent(key.scriptHash).forEach {
                coins["${it.txid}:${it.vout}"] = Coin(key, it.txid, it.vout, it.value, it.height)
            }
            val onXbt = xh.map { it.txid }.toSet()
            sh.forEach { if (it.txid !in onXbt) shaOnly += it.txid }
            return true
        }

        for ((base, type) in branches) {
            var i = 0; var empty = 0
            while (empty < gap) {
                val hit = keysAt(base, type, i).map { look(it) }.any { it }
                if (hit) { empty = 0; used++ } else empty++
                if (scanned % 10 == 0) progress("checked $scanned addresses, $used used…")
                i++
            }
        }
        // Payment keys a backed-up channel uses, wherever their index is.
        for (c in channels) keysAt("m/1017'/0'/3'/0", ScriptType.P2WPKH, c.localKeyIndex[3] ?: continue).forEach { look(it) }

        val replays = ArrayList<Replay>()
        val statuses = ArrayList<ChannelStatus>()
        val handled = HashSet<String>()
        var unmatchedForce = 0

        /** Our outputs in a close: plain/anchor keys, plus our to_local when we forced it. */
        fun ourOutputs(txid: String, tx: TxParse.Tx): List<Coin> {
            val found = ArrayList<Coin>()
            tx.outputs.forEachIndexed { vout, o -> ours[o.scriptPubKey.toHex()]?.let { found += Coin(it, txid, vout, o.value, 0) } }
            if (TxParse.isCommitment(tx)) for (c in channels) {
                if (c.isTaproot || tx.inputs.none { it.txid == c.fundingTxid && it.vout == c.fundingVout }) continue
                toLocalKey(c, tx)?.let { k ->
                    val spk = k.script.toHex()
                    tx.outputs.forEachIndexed { vout, o -> if (o.scriptPubKey.toHex() == spk) { ours[spk] = k; found += Coin(k, txid, vout, o.value, 0) } }
                }
            }
            return found
        }

        fun consider(txid: String) {
            if (!handled.add(txid) || sha == null) return
            val raw = runCatching { sha.transaction(txid) }.getOrNull() ?: return
            val tx = runCatching { TxParse.parse(raw) }.getOrNull() ?: return
            if (!TxParse.spendsChannelFunding(tx)) return
            if (runCatching { xbt.transaction(txid) }.isSuccess) return // already on BLAKE2b
            val forced = TxParse.isCommitment(tx)
            val toUs = ourOutputs(txid, tx)
            if (toUs.isNotEmpty()) replays += Replay(txid, raw, forced, toUs)
            else if (forced) unmatchedForce++
        }

        // Every channel in the backup: where its funding output stands on each chain.
        for ((n, c) in channels.withIndex()) {
            progress("checking channel ${n + 1} of ${channels.size}…")
            if (c.isTaproot) { statuses += ChannelStatus(c, ChanState.TAPROOT_UNCHECKED, null, null); continue }
            val multisig = node("m/1017'/0'/0'/0/${c.localKeyIndex[0] ?: 0}").publicKey()
            val sh = Address.electrumScriptHash(c.fundingScript(multisig))
            val xbtSpend = xbt.history(sh).map { it.txid }.firstOrNull { it != c.fundingTxid }
            if (xbtSpend != null) {
                // Closed on BLAKE2b already: pick up our delayed output if we forced it.
                runCatching { TxParse.parse(xbt.transaction(xbtSpend)) }.getOrNull()?.let { tx ->
                    ourOutputs(xbtSpend, tx).forEach { coin ->
                        xbt.listUnspent(coin.key.scriptHash).filter { it.txid == xbtSpend && it.vout == coin.vout }
                            .forEach { coins["${it.txid}:${it.vout}"] = Coin(coin.key, it.txid, it.vout, it.value, it.height) }
                    }
                    statuses += ChannelStatus(c, ChanState.CLOSED_XBT, xbtSpend, TxParse.isCommitment(tx))
                } ?: run { statuses += ChannelStatus(c, ChanState.CLOSED_XBT, xbtSpend, null) }
                continue
            }
            val shaSpend = sha?.let { s -> runCatching { s.history(sh) }.getOrDefault(emptyList()).map { it.txid }.firstOrNull { it != c.fundingTxid } }
            if (shaSpend == null) {
                val known = xbt.listUnspent(sh).any { it.txid == c.fundingTxid && it.vout == c.fundingVout }
                statuses += ChannelStatus(c, if (known) ChanState.OPEN_BOTH else ChanState.UNKNOWN, null, null)
            } else {
                consider(shaSpend)
                val forced = replays.firstOrNull { it.txid == shaSpend }?.forced
                statuses += ChannelStatus(c, ChanState.CLOSED_SHA_ONLY, shaSpend, forced)
            }
        }
        // Closes found through the wallet's addresses alone.
        shaOnly.forEachIndexed { n, txid ->
            progress("checking SHA-256 transaction ${n + 1} of ${shaOnly.size}…")
            consider(txid)
        }
        return Result(coins.values.toList(), replays, statuses, unmatchedForce, used, scanned, tip)
    }

    /** Our to_local key in the commitment [tx] of channel [c], if it is our commitment. */
    private fun toLocalKey(c: Scb.Channel, tx: TxParse.Tx): Key? = runCatching {
        val pay = node("m/1017'/0'/3'/0/${c.localKeyIndex[3]}").publicKey()
        val obf = if (c.isInitiator) LnKeys.obfuscator(pay, c.remotePayment) else LnKeys.obfuscator(c.remotePayment, pay)
        val state = LnKeys.stateNumber(tx.locktime, tx.inputs[0].sequence, obf)
        val multisig = node("m/1017'/0'/0'/0/${c.localKeyIndex[0]}").publicKey()
        val revRoot = LnKeys.ecdh(node("m/1017'/0'/5'/0/${c.shaChainIndex}").key, multisig)
        val cp = LnKeys.commitmentPoint(LnKeys.commitmentSecret(revRoot, state))
        val delay = node("m/1017'/0'/4'/0/${c.localKeyIndex[4]}")
        val delayed = LnKeys.tweakPub(delay.publicKey(), cp)
        val script = LnKeys.toLocalScript(c.localCsvDelay, delayed, LnKeys.revocationPub(c.remoteRevocation, cp))
        Key("to_local ${c.shortChannelId} state $state", ScriptType.P2WPKH,
            LnKeys.tweakPriv(delay.key, delay.publicKey(), cp), delayed,
            witnessScript = script, witnessExtra = listOf(ByteArray(0)), csv = c.localCsvDelay)
    }.getOrNull()
}
