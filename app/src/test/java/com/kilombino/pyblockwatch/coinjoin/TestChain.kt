package com.kilombino.pyblockwatch.coinjoin

import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.TxParse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A chain in memory for the round tests: an unspent set, a mempool and blocks. A broadcast is taken only
 * if every coin it spends is unspent (a conflict is refused, as a node would); the same transaction again
 * answers "already". Signatures are not checked here: every wallet checks each one against the plan before
 * keeping it, and the regtest run (AsyncRoundsE2ETest with KILOJOIN_REGTEST) has a node check them too.
 */
class TestChain {
    class Utxo(val value: Long, val script: String)
    private val utxos = ConcurrentHashMap<String, Utxo>()
    /** txid → confirmations (0 = in the mempool). */
    val txs = ConcurrentHashMap<String, Int>()
    /** outpoint → the txid that spends it (mempool or block). */
    val spentBy = ConcurrentHashMap<String, String>()
    /** txid → raw, for a transaction this chain took (to undo it on a reorg). */
    private val raws = ConcurrentHashMap<String, String>()
    /** Every transaction taken, raw, in order, with who sent it. */
    val broadcasts = CopyOnWriteArrayList<Pair<String, String>>()
    @Volatile var down = false
    /** Every broadcast refused, as a node with a full mempool or a fee floor would (the chain itself still answers). */
    @Volatile var refuse = false
    /** Broadcasts asked for, taken or not. */
    val attempts = java.util.concurrent.atomic.AtomicInteger()

    fun fund(c: CoinjoinTx.Coin) { utxos[c.outpoint] = Utxo(c.value, c.script.toHex()) }

    /** Spends [outpoint] with some other transaction (the owner moved the coin), with [confs] confirmations (0: in the mempool). */
    fun spendElsewhere(outpoint: String, confs: Int = 1) {
        utxos.remove(outpoint)
        val txid = "f".repeat(48) + "%016x".format(outpoint.hashCode().toLong() and 0xffffffffL)
        txs[txid] = confs
        spentBy[outpoint] = txid
    }

    /** Confirmations of the transaction spending [outpoint], when that is not [except]; null if unspent. */
    fun conflictDepth(outpoint: String, except: String): Int? {
        if (down) throw java.io.IOException("server out of reach")
        val by = spentBy[outpoint] ?: return null
        return if (by == except) null else txs[by]
    }

    /** [txid] reorged out and dropped from every mempool: gone, its coins unspent again, its outputs never were. */
    @Synchronized
    fun reorgOut(txid: String) {
        txs.remove(txid)
        val raw = raws.remove(txid) ?: return
        val tx = TxParse.parse(raw)
        tx.outputs.indices.forEach { utxos.remove("$txid:$it") }
        for (i in tx.inputs) {
            val op = "${i.txid}:${i.vout}"
            spentBy.remove(op)
            prevs.remove(op)?.let { utxos[op] = it }
        }
    }
    private val prevs = ConcurrentHashMap<String, Utxo>()

    fun mine() { for (k in txs.keys) txs[k] = (txs[k] ?: 0) + 1 }

    fun unspent(c: CoinjoinTx.Coin): Boolean {
        if (down) throw java.io.IOException("server out of reach")
        val u = utxos[c.outpoint] ?: return false
        return u.value == c.value && u.script == c.script.toHex()
    }

    @Synchronized
    fun broadcast(raw: String, who: String): String {
        if (down) throw java.io.IOException("server out of reach")
        attempts.incrementAndGet()
        if (refuse) throw IllegalStateException("mempool full")
        val tx = TxParse.parse(raw)
        val txid = TxParse.txid(tx)
        if (txs.containsKey(txid)) throw IllegalStateException("txn-already-in-mempool")
        for (i in tx.inputs) if (!utxos.containsKey("${i.txid}:${i.vout}")) throw IllegalStateException("bad-txns-inputs-missingorspent")
        for (i in tx.inputs) { val op = "${i.txid}:${i.vout}"; utxos.remove(op)?.let { prevs[op] = it }; spentBy[op] = txid }
        raws[txid] = raw
        tx.outputs.forEachIndexed { n, o -> utxos["$txid:$n"] = Utxo(o.value, o.scriptPubKey.toHex()) }
        txs[txid] = 0
        broadcasts.add(who to raw)
        return txid
    }

    fun confirmations(txid: String): Int? {
        if (down) throw java.io.IOException("server out of reach")
        return txs[txid]
    }
}
