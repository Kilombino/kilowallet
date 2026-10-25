package com.kilombino.pyblockwatch.data

import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.chain.ScriptHashBalance
import com.kilombino.pyblockwatch.chain.NodeEndpoint
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bip32
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.TxParse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** One derived address and what the chain says about it. */
data class AddressRow(
    val chainIndex: Int,       // 0 = receive, 1 = change
    val index: Int,
    val address: String,
    val path: String,          // e.g. "m/0/3", shown so the user can follow along
    val scriptHash: String,
    val confirmed: Long = 0,
    val unconfirmed: Long = 0,
    val txCount: Int = 0,
    /** Set for an address of the hot wallet's second account (Taproot); null = the wallet's own type. */
    val type: ScriptType? = null,
    /** A JoinMarket wallet's jar (mixdepth 0–4); null for every other wallet. */
    val jar: Int? = null,
) {
    val total: Long get() = confirmed + unconfirmed
    val isUsed: Boolean get() = txCount > 0 || total > 0
}

/**
 * Progress events, emitted one at a time so the UI can narrate the scan instead of
 * showing an opaque spinner. Someone watching should be able to see that a wallet
 * is a sequence of derived keys, each asked about independently.
 */
sealed interface ScanEvent {
    data class Connecting(val endpoint: NodeEndpoint) : ScanEvent
    data class Connected(val server: String, val height: Int, val fingerprint: String?,
                         val fingerprintChanged: Boolean) : ScanEvent
    data class Deriving(val chainIndex: Int, val index: Int, val path: String) : ScanEvent
    data class Found(val row: AddressRow) : ScanEvent
    data class GapProgress(val chainIndex: Int, val consecutiveEmpty: Int, val gapLimit: Int) : ScanEvent
    data class Done(val rows: List<AddressRow>, val height: Int, val txs: List<TxConf>) : ScanEvent
    data class Failed(val message: String) : ScanEvent
    /** The server's certificate is not the pinned one: nothing was asked; the user must check [fingerprint]. */
    data class CertificateChanged(val fingerprint: String, val message: String) : ScanEvent
}

/** A wallet transaction and how deep it is: pending (in the mempool) or N confirmations. */
/** A wallet transaction: its confirmations and, when known, what it did to this wallet (+ in, − out incl. fee). */
/**
 * [spv]: for a confirmed incoming payment, whether its merkle proof checked out against a block
 * header with real proof of work (true), failed (false), or was not checked (null).
 */
data class TxConf(val txid: String, val confirmations: Int, val pending: Boolean, val amount: Long? = null,
                  val spv: Boolean? = null)

/**
 * Walks an xpub the way every wallet does: derive `chain/index`, ask the server
 * whether anything ever touched it, and stop after [gapLimit] consecutive unused
 * addresses.
 *
 * The gap limit is the whole reason a watch-only wallet can find your coins without
 * knowing how many addresses you used: BIP-44 promises you never skip more than 20
 * in a row, so 20 consecutive empties means the end.
 */
class Scanner(
    private val gapLimit: Int = 20,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** A second account walked after the main one; its rows carry [type]. */
    data class Extra(val xpub: String, val type: ScriptType)

    /** An account walked with the gap limit: [tag] is set on its rows, [jar] for a JoinMarket jar. */
    data class Account(val xpub: String, val type: ScriptType, val tag: ScriptType? = null,
                       val accountIndex: Int = 0, val jar: Int? = null)

    /** A script asked about as it is, with no gap walk (a JoinMarket fidelity bond). */
    data class Fixed(val address: String, val scriptHash: String, val path: String,
                     val chainIndex: Int, val index: Int, val jar: Int? = null)

    fun scan(
        xpub: String,
        chain: Chain,
        endpoint: NodeEndpoint,
        pinnedFingerprint: String?,
        scriptType: ScriptType,
        gap: Int = gapLimit,
        extra: Extra? = null,
        quick: Boolean = false,
    ): Flow<ScanEvent> = scanAccounts(
        // The main account, then (for a hot wallet with Taproot on) its BIP-86 account.
        listOf(Account(xpub, scriptType)) + listOfNotNull(extra?.let { Account(it.xpub, it.type, it.type) }),
        emptyList(), endpoint, pinnedFingerprint, gap, quick,
    )

    /**
     * Walks every account in [accounts] (receive then change, each to [gap] empty addresses),
     * then asks about each [fixed] script; the statuses go out in batches, as for one account.
     * Fixed scripts are always in the result, used or not.
     */
    fun scanAccounts(
        accounts: List<Account>,
        fixed: List<Fixed>,
        endpoint: NodeEndpoint,
        pinnedFingerprint: String?,
        gap: Int = gapLimit,
        quick: Boolean = false,
    ): Flow<ScanEvent> = flow {
        val txHeights = HashMap<String, Int>()
        val parsedAccounts = try {
            accounts.map { it to Bip32.parseExtendedPubKey(it.xpub).let { k -> if (it.jar != null) k.copy(scriptType = it.type) else k } }
        } catch (e: IllegalArgumentException) {
            emit(ScanEvent.Failed(e.message ?: "Invalid extended key")); return@flow
        }

        emit(ScanEvent.Connecting(endpoint))
        val t0 = System.currentTimeMillis()
        val client = ElectrumClient(endpoint, pinnedFingerprint)
        try {
            client.connect()
        } catch (e: com.kilombino.pyblockwatch.chain.CertificateChangedException) {
            emit(ScanEvent.CertificateChanged(e.fingerprint, e.message ?: "certificate changed")); return@flow
        } catch (e: Exception) {
            emit(ScanEvent.Failed(e.message ?: "Could not connect")); return@flow
        }

        val rows = mutableListOf<AddressRow>()
        try {
            val height = client.blockHeight()
            emit(ScanEvent.Connected(
                client.serverVersion ?: "unknown", height,
                client.serverFingerprint, client.fingerprintChanged,
            ))

            // Receive chain then change chain. Both are scanned: a wallet's balance
            // lives on both, and showing only the receive side under-reports funds.
            for ((acc, parsed) in parsedAccounts) for (chainIndex in 0..1) {
                val type = acc.type; val tag = acc.tag
                val purpose = purposeFor(type)
                val branch = Bip32.deriveChild(parsed, chainIndex)
                val accountTag = accountTag(parsed, type)
                var index = 0
                var consecutiveEmpty = 0
                while (consecutiveEmpty < gap) {
                    // The next addresses, never more than the walk could still need before it
                    // stops (so the server hears of no address a one-by-one walk would not ask
                    // about), with their statuses asked in one batch.
                    val batch = (index until index + (gap - consecutiveEmpty)).map { i ->
                        val (address, sh) = derived(accountTag, chainIndex, i) {
                            val child = Bip32.deriveChild(branch, i)
                            Address.encode(child.pubkey(), type) to Address.scriptHashFor(child.pubkey(), type)
                        }
                        Triple(i, address, sh)
                    }
                    val known = batch.map { (_, _, sh) -> if (quick) settled(endpoint, sh, height) else null }
                    val ask = batch.indices.filter { known[it] == null }
                    val asked = client.statuses(ask.map { batch[it].third })
                    val status = HashMap<Int, String?>().apply { ask.forEachIndexed { j, k -> put(k, asked[j]) } }

                    var stop = false
                    for ((k, entry) in batch.withIndex()) {
                        val (i, address, scriptHash) = entry
                        val path = "m/$purpose'/0'/${acc.accountIndex}'/$chainIndex/$i"
                        emit(ScanEvent.Deriving(chainIndex, i, path))
                        // Every address is asked about (one status: a hash of its whole history,
                        // null when unused), except, on a quick look, the spent and settled ones.
                        // History and balance are fetched only when that hash differs from the
                        // one remembered with them.
                        val (hist, bal) = known[k] ?: resolve(client, endpoint, scriptHash, status[k])
                        val row = AddressRow(
                            chainIndex = chainIndex, index = i, address = address,
                            path = path, scriptHash = scriptHash,
                            confirmed = bal.confirmed, unconfirmed = bal.unconfirmed, txCount = hist.size, type = tag,
                            jar = acc.jar,
                        )
                        index = i + 1
                        if (row.isUsed) {
                            rows += row
                            hist.forEach { txHeights[it.txid] = it.height }
                            consecutiveEmpty = 0
                            emit(ScanEvent.Found(row))
                        } else {
                            consecutiveEmpty++
                            emit(ScanEvent.GapProgress(chainIndex, consecutiveEmpty, gap))
                            if (consecutiveEmpty >= gap) { stop = true; break }
                        }
                    }
                    if (stop) break
                }
            }
            if (fixed.isNotEmpty()) {
                val known = fixed.map { f -> if (quick) settled(endpoint, f.scriptHash, height) else null }
                val ask = fixed.indices.filter { known[it] == null }
                val asked = client.statuses(ask.map { fixed[it].scriptHash })
                val status = HashMap<Int, String?>().apply { ask.forEachIndexed { j, k -> put(k, asked[j]) } }
                for ((k, f) in fixed.withIndex()) {
                    val (hist, bal) = known[k] ?: resolve(client, endpoint, f.scriptHash, status[k])
                    val row = AddressRow(chainIndex = f.chainIndex, index = f.index, address = f.address, path = f.path,
                        scriptHash = f.scriptHash, confirmed = bal.confirmed, unconfirmed = bal.unconfirmed,
                        txCount = hist.size, jar = f.jar)
                    rows += row
                    hist.forEach { txHeights[it.txid] = it.height }
                    if (row.isUsed) emit(ScanEvent.Found(row))
                }
            }
            val t1 = System.currentTimeMillis()
            // Confirmations from the tip: height <= 0 is still in the mempool (0 conf).
            val amounts = netAmounts(client, txHeights.keys, rows.map { it.scriptHash }.toSet())
            val t2 = System.currentTimeMillis()
            val proof = prover(client, height)
            // Newest first, so the proofs a look has room for go to the latest payments.
            val spv = txHeights.entries.sortedByDescending { if (it.value <= 0) Int.MAX_VALUE else it.value }
                .associate { (id, h) -> id to proof(id, h, amounts[id]) }
            val txs = txHeights.map { (id, h) ->
                val a = amounts[id]
                TxConf(id, if (h <= 0) 0 else height - h + 1, pending = h <= 0, amount = a, spv = spv[id])
            }.sortedWith(compareBy({ !it.pending }, { it.confirmations }))
            android.util.Log.i("Kilowallet", "scan timing: connect+walk ${t1 - t0} ms, amounts ${t2 - t1} ms, spv ${System.currentTimeMillis() - t2} ms")
            saveDisk()
            emit(ScanEvent.Done(rows, height, txs))
        } catch (e: Exception) {
            emit(ScanEvent.Failed(e.message ?: "The scan failed"))
        } finally {
            client.close()
        }
    }.flowOn(dispatcher)

    /**
     * Cheap refresh of already-discovered addresses for the notification service.
     * Returns confirmed and unconfirmed apart so the watcher can distinguish a mempool
     * arrival, a confirmation and a spend.
     */
    suspend fun refreshBalance(
        rows: List<AddressRow>, endpoint: NodeEndpoint, pinnedFingerprint: String?,
    ): ScriptHashBalance {
        val client = ElectrumClient(endpoint, pinnedFingerprint)
        return try {
            client.connect()
            var confirmed = 0L; var unconfirmed = 0L
            for (r in rows) {
                val b = client.balance(r.scriptHash)
                confirmed += b.confirmed; unconfirmed += b.unconfirmed
            }
            ScriptHashBalance(confirmed, unconfirmed)
        } finally {
            client.close()
        }
    }

    /**
     * Silent refresh of the addresses a scan already found: re-query balance and history
     * for each known scripthash and recompute confirmations against the tip. No gap walk,
     * so it's cheap enough to run on a foreground timer without flickering the UI.
     */
    suspend fun refreshDetails(
        rows: List<AddressRow>, endpoint: NodeEndpoint, pinnedFingerprint: String?, quick: Boolean = false,
    ): Triple<List<AddressRow>, List<TxConf>, Int> {
        val client = ElectrumClient(endpoint, pinnedFingerprint)
        return try {
            client.connect()
            val tip = client.blockHeight()
            val txHeights = HashMap<String, Int>()
            // One status call per address; history and balance only for those that changed
            // since the last refresh (or were never seen). Most refreshes find nothing new,
            // so this halves the round trips, and an unused address costs a single call.
            // Statuses in one batch, the settled addresses left out on a quick look.
            val known = rows.map { r -> if (quick) settled(endpoint, r.scriptHash, tip) else null }
            val ask = rows.indices.filter { known[it] == null }
            val asked = client.statuses(ask.map { rows[it].scriptHash })
            val status = HashMap<Int, String?>().apply { ask.forEachIndexed { j, k -> put(k, asked[j]) } }
            val updated = rows.mapIndexed { k, r ->
                val (hist, bal) = known[k] ?: resolve(client, endpoint, r.scriptHash, status[k])
                hist.forEach { txHeights[it.txid] = it.height }
                r.copy(confirmed = bal.confirmed, unconfirmed = bal.unconfirmed, txCount = hist.size)
            }
            val amounts = netAmounts(client, txHeights.keys, rows.map { it.scriptHash }.toSet())
            val proof = prover(client, tip)
            // Newest first, so the proofs a look has room for go to the latest payments.
            val spv = txHeights.entries.sortedByDescending { if (it.value <= 0) Int.MAX_VALUE else it.value }
                .associate { (id, h) -> id to proof(id, h, amounts[id]) }
            val txs = txHeights.map { (id, h) ->
                val a = amounts[id]
                TxConf(id, if (h <= 0) 0 else tip - h + 1, pending = h <= 0, amount = a, spv = spv[id])
            }.sortedWith(compareBy({ !it.pending }, { it.confirmations }))
            saveDisk()
            Triple(updated, txs, tip)
        } finally {
            client.close()
        }
    }

    /**
     * What each transaction did to the wallet: outputs to our scripts minus our outputs it
     * spends (so a send includes its fee). Every coin we spend was paid to us by a transaction
     * that is itself in our history, so the wallet's own transactions are enough. A
     * transaction never changes, so each is fetched once per app run and then kept; a
     * failure leaves that amount unknown rather than breaking the scan.
     */
    /**
     * History and balance of one address, from its status: what was remembered with the same
     * status, or fresh from the server when the status changed or was never seen.
     */
    /**
     * A quick look leaves out an address that is done with: it was used, holds nothing, and
     * everything that touched it is buried [SETTLED] blocks deep. Only a payment sent to an old
     * address again would be missed, and the full look (daily, and on demand in settings) asks
     * about every address.
     */
    private fun settled(endpoint: NodeEndpoint, scriptHash: String, tip: Int): Pair<List<ElectrumClient.HistoryItem>, ScriptHashBalance>? {
        val e = statusCache[endpoint.toString() + "|" + scriptHash] ?: return null
        val done = e.status != null && e.balance.confirmed == 0L && e.balance.unconfirmed == 0L && e.history.isNotEmpty() &&
            e.history.all { it.height > 0 && tip - it.height + 1 >= SETTLED }
        return if (done) e.history to e.balance else null
    }

    /** History and balance for a [status] just read: the remembered ones if it did not change. */
    private fun resolve(client: ElectrumClient, endpoint: NodeEndpoint, scriptHash: String, status: String?): Pair<List<ElectrumClient.HistoryItem>, ScriptHashBalance> {
        val key = endpoint.toString() + "|" + scriptHash
        if (status == null) { statusCache.remove(key); return emptyList<ElectrumClient.HistoryItem>() to ScriptHashBalance(0, 0) }
        statusCache[key]?.takeIf { it.status == status }?.let { return it.history to it.balance }
        val entry = CachedAddress(status, client.history(scriptHash), client.balance(scriptHash))
        statusCache[key] = entry; cacheDirty = true
        return entry.history to entry.balance
    }

    private fun netAmounts(client: ElectrumClient, txids: Collection<String>, ours: Set<String>): Map<String, Long> {
        val txs = txids.mapNotNull { id -> tx(client, id)?.let { id to it } }.toMap()
        fun mine(o: TxParse.Out?) = o != null && Address.electrumScriptHash(o.scriptPubKey) in ours
        return txs.mapValues { (_, tx) ->
            tx.outputs.filter { mine(it) }.sumOf { it.value } -
                tx.inputs.sumOf { i -> txs[i.txid]?.outputs?.getOrNull(i.vout)?.takeIf { mine(it) }?.value ?: 0L }
        }
    }

    /** A spendable output tagged with the derivation that unlocks it (chain/index of its address). */
    data class SpendableUtxo(
        val txid: String, val vout: Int, val value: Long,
        val chainIndex: Int, val index: Int, val height: Int,
        /** Taproot coin of a hot wallet's second account; null = the wallet's own type. */
        val type: ScriptType? = null,
    )

    /** Every unspent output in [rows], immature mined rewards included (for display). */
    suspend fun listCoins(
        rows: List<AddressRow>, endpoint: NodeEndpoint, pinnedFingerprint: String?,
    ): List<SpendableUtxo> {
        val client = ElectrumClient(endpoint, pinnedFingerprint)
        return try {
            client.connect()
            // One round trip for all of them, not one per address.
            client.listUnspentMany(rows.map { it.scriptHash }).zip(rows).flatMap { (us, r) ->
                us.map { SpendableUtxo(it.txid, it.vout, it.value, r.chainIndex, r.index, it.height, r.type) }
            }
        } finally {
            client.close()
        }
    }

    /** Every unspent output the wallet's discovered addresses hold — the coins a send can draw on. */
    suspend fun gatherUtxos(
        rows: List<AddressRow>, endpoint: NodeEndpoint, pinnedFingerprint: String?,
    ): List<SpendableUtxo> {
        val client = ElectrumClient(endpoint, pinnedFingerprint)
        return try {
            client.connect()
            val all = client.listUnspentMany(rows.map { it.scriptHash }).zip(rows).flatMap { (us, r) ->
                us.map { SpendableUtxo(it.txid, it.vout, it.value, r.chainIndex, r.index, it.height, r.type) }
            }
            // A mined reward (coinbase) can't be spent until it matures; a spend using one would
            // only fail at broadcast. Leave out the immature ones.
            val tip = runCatching { client.blockHeight() }.getOrNull() ?: return all
            all.filter { u ->
                if (!mayBeImmature(u.height, tip)) return@filter true
                val tx = tx(client, u.txid) ?: return@filter true
                !immature(isCoinbase(tx), u.height, tip)
            }
        } finally {
            client.close()
        }
    }

    /** A mined reward of this wallet not spendable yet: the coin, and the block from which it is. */
    data class ImmatureCoin(val coin: SpendableUtxo, val maturesAt: Int)

    /** The wallet's immature mined rewards at [tip], and how long blocks have been taking lately (seconds). */
    data class Immature(val tip: Int, val coins: List<ImmatureCoin>, val secondsPerBlock: Double) {
        val total: Long get() = coins.sumOf { it.coin.value }
        /** Roughly when block [height] comes (epoch seconds), from the recent pace. */
        fun eta(height: Int, now: Long = System.currentTimeMillis() / 1000): Long = now + ((height - tip).coerceAtLeast(0) * secondsPerBlock).toLong()
        fun of(u: SpendableUtxo): ImmatureCoin? = coins.firstOrNull { it.coin.txid == u.txid && it.coin.vout == u.vout }
    }

    /**
     * Every immature mined reward in [rows] (what [gatherUtxos] leaves out of spending), for showing:
     * the coins of the addresses, the transactions only of those young enough to be one (cached, as a
     * look caches them), and the pace of the last day's blocks for the dates.
     */
    suspend fun immature(rows: List<AddressRow>, endpoint: NodeEndpoint, pinnedFingerprint: String?): Immature {
        val client = ElectrumClient(endpoint, pinnedFingerprint)
        return try {
            client.connect()
            val all = client.listUnspentMany(rows.map { it.scriptHash }).zip(rows).flatMap { (us, r) ->
                us.map { SpendableUtxo(it.txid, it.vout, it.value, r.chainIndex, r.index, it.height, r.type) }
            }
            val tip = client.blockHeight()
            val coins = all.filter { mayBeImmature(it.height, tip) }.mapNotNull { u ->
                val tx = tx(client, u.txid) ?: return@mapNotNull null
                if (immature(isCoinbase(tx), u.height, tip)) ImmatureCoin(u, maturesAt(u.height)) else null
            }
            val pace = if (coins.isEmpty()) DEFAULT_BLOCK_SECONDS else runCatching {
                val span = minOf(144, tip - 1)
                val t1 = JamWatch.blockTime(client.blockHeader(tip)); val t0 = JamWatch.blockTime(client.blockHeader(tip - span))
                ((t1 - t0).toDouble() / span).takeIf { it in 30.0..86_400.0 }
            }.getOrNull() ?: DEFAULT_BLOCK_SECONDS
            Immature(tip, coins.sortedBy { it.maturesAt }, pace)
        } finally {
            client.close()
        }
    }

    /** Broadcast a signed raw transaction; returns the txid or throws the server's reason. */
    suspend fun broadcast(rawTxHex: String, endpoint: NodeEndpoint, pinnedFingerprint: String?): String {
        val client = ElectrumClient(endpoint, pinnedFingerprint)
        return try {
            client.connect()
            client.broadcast(rawTxHex)
        } finally {
            client.close()
        }
    }

    /** A rough sats/vByte estimate from the server, floored by the relay minimum. Null if it fails. */
    suspend fun suggestedFeeRate(endpoint: NodeEndpoint, pinnedFingerprint: String?): Double? {
        val client = ElectrumClient(endpoint, pinnedFingerprint)
        return try {
            client.connect()
            val perKb = client.estimateFeePerKb(3)
            val relayPerKb = client.relayFeePerKb()
            val rate = maxOf(perKb, relayPerKb) // BTC/kB
            if (rate <= 0) null else (rate * 100_000.0) // → sats/vByte
        } catch (e: Exception) {
            null
        } finally {
            client.close()
        }
    }

    /**
     * How each transaction of a look is proven (true, false, or null for unknown / unconfirmed).
     *
     * On the BTC chain (164-byte headers) the verified header chain is brought up to the
     * server's tip first, and every confirmed transaction, received or sent, is proven by its
     * merkle branch against it. A server whose headers do not verify has every confirmed
     * transaction marked unproven. On the spamchain, recent incoming payments are proven
     * against the header the server gives, as before.
     */
    private fun prover(client: ElectrumClient, tip: Int): (String, Int, Long?) -> Boolean? {
        if (client.isOwnNode) return { _, h, _ -> if (h > 0) true else null } // your node validated its blocks
        val blake = runCatching { client.tipHeaderHex().length == 328 }.getOrDefault(false)
        if (!blake) return { id, h, a -> if (h > 0 && a != null && a > 0 && tip - h < SPV_WINDOW) spvCheck(client, id, h) else null }
        val hc = com.kilombino.pyblockwatch.chain.HeaderChain
        // A network failure is not a lie: it leaves what is not yet proven unknown, and what
        // was proven before stays proven. Only headers that do not verify mark everything.
        val sync = runCatching { hc.sync(client) }
        val lying = sync.isSuccess && sync.getOrNull() == null
        val unreachable = sync.isFailure
        // Proofs already made cost nothing; new ones take a round trip each, so a look makes at
        // most [SPV_PER_LOOK] of them and leaves the rest unknown (not failed) for the next looks.
        var budget = SPV_PER_LOOK
        return { id, h, _ ->
            when {
                h <= 0 -> null
                hc.isProven(id, h) -> true
                lying -> false
                unreachable -> null
                // Below the checkpoint a proof is one header. A wallet that already looked before
                // has seen every old payment then: one that first turns up in a block that old is
                // what a lying server would show. It stays "not proven" here (paying from it still
                // proves it on its own).
                h < hc.CHECKPOINT_HEIGHT && !hc.knownBelow(id) -> false
                budget <= 0 -> null
                else -> { budget--; runCatching { hc.proves(client, id, h, SPV_FLOOR_BITS) }.getOrNull() }
            }
        }
    }

    /**
     * SPV for one incoming payment: the server's merkle branch must lead from [txid] to the
     * merkle root of block [height]'s header, and that header must carry real proof of work at
     * no less than [SPV_FLOOR_BITS]. A lying server would have to mine a block to fake one.
     * Results are kept: a confirmed transaction's block does not change.
     */
    private fun spvCheck(client: ElectrumClient, txid: String, height: Int): Boolean? {
        if (client.isOwnNode) return true // your own node validated the block itself
        spvCache[txid]?.let { return it }
        val ok = runCatching {
            val header = com.kilombino.pyblockwatch.crypto.BlockHeader.parse(client.blockHeader(height))
            val (branch, pos) = client.merkle(txid, height)
            com.kilombino.pyblockwatch.crypto.BlockHeader.inBlock(txid, branch, pos, header) &&
                com.kilombino.pyblockwatch.crypto.BlockHeader.meetsItsTarget(header) &&
                com.kilombino.pyblockwatch.crypto.BlockHeader.target(header.bits) <=
                    com.kilombino.pyblockwatch.crypto.BlockHeader.target(SPV_FLOOR_BITS)
        }.getOrNull() ?: return null      // the server could not answer: unknown, not "false"
        spvCache[txid] = ok
        return ok
    }

    companion object {
        /**
         * The easiest block a payment proof is accepted from: about 1/32 of the difficulty in
         * October 2026 (bits 0x1900d82a). Faking a proof costs mining a block at this level.
         */
        const val SPV_FLOOR_BITS = 0x191b0540L
        /** Payments are proven while they are this young (a day); older ones are long settled. */
        const val SPV_WINDOW = 144
        /** New merkle proofs a single look makes on BTC; the rest wait for the next look. */
        const val SPV_PER_LOOK = 15
        private val spvCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        /**
         * Coinbase maturity on the BLAKE2b chain: the `long_coinbase_maturity` flag day makes
         * rewards mined in blocks 973 440–979 919 wait 6 480 confirmations; any other, 100.
         */
        const val LONG_MATURITY = 6480

        /** Depth after which a spent address is left out of quick looks. */
        const val SETTLED = 6
        fun maturityFor(height: Int): Int = if (height in 973_440..979_919) LONG_MATURITY else 100

        /** A mined reward: one input, spending nothing (all-zero txid, index 0xffffffff). */
        fun isCoinbase(tx: TxParse.Tx): Boolean = tx.inputs.size == 1 && tx.inputs[0].txid.all { it == '0' } && tx.inputs[0].vout == -1

        /** The first tip at which a reward mined at [height] can be spent (as [gatherUtxos] counts it). */
        fun maturesAt(height: Int): Int = height + maturityFor(height) - 1

        /** A coin at [height] is young enough at [tip] that, if it is a mined reward, it can't be spent yet. */
        fun mayBeImmature(height: Int, tip: Int): Boolean = height > 0 && tip - height + 1 < LONG_MATURITY

        /** Whether a coin at [height] (a mined reward when [coinbase]) is still locked at [tip]. */
        fun immature(coinbase: Boolean, height: Int, tip: Int): Boolean = coinbase && height > 0 && tip - height + 1 < maturityFor(height)

        /** Ten minutes a block, when the chain's own pace can't be read. */
        const val DEFAULT_BLOCK_SECONDS = 600.0

        /** What an address looked like at its last status, so an unchanged one needs no more calls. */
        private class CachedAddress(val status: String?, val history: List<ElectrumClient.HistoryItem>, val balance: ScriptHashBalance)
        private val statusCache = java.util.concurrent.ConcurrentHashMap<String, CachedAddress>()
        @Volatile private var cacheDirty = false

        /** Parsed wallet transactions by txid; a transaction never changes, so it is fetched once. */
        private val txCache = java.util.concurrent.ConcurrentHashMap<String, TxParse.Tx>()

        /**
         * Where the caches above survive the app being closed: the statuses with what was
         * seen under them, and each raw transaction once (it never changes). Only ever a
         * shortcut: a status is still asked for every address on every look, and anything
         * unreadable here is simply fetched again.
         */
        @Volatile private var disk: java.io.File? = null

        fun useDisk(dir: java.io.File): Unit = synchronized(this) {
            if (disk != null) return
            dir.mkdirs(); java.io.File(dir, "tx").mkdirs()
            disk = dir
            com.kilombino.pyblockwatch.chain.HeaderChain.useDir(dir)
            runCatching {
                val o = org.json.JSONObject(java.io.File(dir, "derived.json").readText())
                for (k in o.keys()) { val a = o.getJSONArray(k); derivedCache.putIfAbsent(k, a.getString(0) to a.getString(1)) }
            }
            runCatching {
                val o = org.json.JSONObject(java.io.File(dir, "status.json").readText())
                for (k in o.keys()) {
                    val e = o.getJSONObject(k)
                    val h = e.getJSONArray("h")
                    // putIfAbsent: a look that started before the disk was read keeps what it found.
                    statusCache.putIfAbsent(k, CachedAddress(e.getString("s"),
                        (0 until h.length()).map { val x = h.getJSONArray(it); ElectrumClient.HistoryItem(x.getString(0), x.getInt(1)) },
                        ScriptHashBalance(e.getLong("c"), e.getLong("u"))))
                }
            }
        }

        private fun saveDisk(): Unit = synchronized(this) {
            val dir = disk ?: return
            if (derivedDirty) runCatching {
                val o = org.json.JSONObject()
                for ((k, v) in derivedCache) o.put(k, org.json.JSONArray().put(v.first).put(v.second))
                val tmp = java.io.File(dir, "derived.json.tmp"); tmp.writeText(o.toString())
                tmp.renameTo(java.io.File(dir, "derived.json")); derivedDirty = false
            }
            if (!cacheDirty) return
            runCatching {
                val o = org.json.JSONObject()
                for ((k, e) in statusCache) {
                    if (e.status == null) continue
                    val h = org.json.JSONArray(); e.history.forEach { h.put(org.json.JSONArray().put(it.txid).put(it.height)) }
                    o.put(k, org.json.JSONObject().put("s", e.status).put("h", h).put("c", e.balance.confirmed).put("u", e.balance.unconfirmed))
                }
                val tmp = java.io.File(dir, "status.json.tmp"); tmp.writeText(o.toString())
                tmp.renameTo(java.io.File(dir, "status.json"))
                cacheDirty = false
            }
        }

        /**
         * Addresses already worked out, by account, branch and index: deriving them is the slow
         * part of a look on a phone, and the result never changes. Kept with the statuses.
         */
        private val derivedCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
        @Volatile private var derivedDirty = false

        private fun accountTag(parsed: Bip32.ExtendedPubKey, type: ScriptType): String =
            com.kilombino.pyblockwatch.crypto.Hashes.sha256(parsed.pubkey() + parsed.chainCode + type.name.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

        private inline fun derived(tag: String, chainIndex: Int, index: Int, make: () -> Pair<String, String>): Pair<String, String> {
            val key = "$tag/$chainIndex/$index"
            derivedCache[key]?.let { return it }
            val v = make(); derivedCache[key] = v; derivedDirty = true
            return v
        }

        /**
         * The first [count] addresses (and script hashes) of [account]'s branch [chainIndex] as
         * [type], from the same cache a look uses: the watcher asks for them every cycle.
         */
        fun derivedRange(account: Bip32.ExtendedPubKey, type: ScriptType, chainIndex: Int, count: Int): List<Pair<String, String>> {
            val tag = accountTag(account, type)
            val branch by lazy { Bip32.deriveChild(account, chainIndex) }
            return (0 until count).map { i ->
                derived(tag, chainIndex, i) {
                    val child = Bip32.deriveChild(branch, i); val pub = child.pubkey()
                    Address.encode(pub, type) to Address.scriptHashFor(pub, type)
                }
            }
        }

        /**
         * What [txid] did to the scripts in [ours] (+ in, − out), from the transactions a look
         * already fetched; null when it is not at hand. Splits a wallet's movements by jar.
         */
        fun cachedNet(txid: String, ours: Set<String>): Long? {
            val tx = txCache[txid] ?: return null
            fun mine(o: TxParse.Out?) = o != null && Address.electrumScriptHash(o.scriptPubKey) in ours
            return tx.outputs.filter { mine(it) }.sumOf { it.value } -
                tx.inputs.sumOf { i -> txCache[i.txid]?.outputs?.getOrNull(i.vout)?.takeIf { mine(it) }?.value ?: 0L }
        }

        /** Read the remembered statuses again (a backup file just put them back). */
        fun reloadDisk(dir: java.io.File): Unit = synchronized(this) {
            statusCache.clear(); disk = null
            useDisk(dir)
        }

        /** Forget everything remembered (a wallet was forgotten or switched). */
        fun clearDisk(): Unit = synchronized(this) {
            statusCache.clear(); txCache.clear(); derivedCache.clear()
            disk?.let { d -> java.io.File(d, "status.json").delete(); java.io.File(d, "derived.json").delete(); java.io.File(d, "tx").listFiles()?.forEach { it.delete() } }
            Unit
        }

        /** A wallet transaction: from memory, from the phone, or from the server (then kept). */
        private fun tx(client: ElectrumClient, txid: String): TxParse.Tx? {
            txCache[txid]?.let { return it }
            val file = disk?.let { java.io.File(java.io.File(it, "tx"), txid.filter { c -> c.isLetterOrDigit() }) }
            val fromDisk = file?.takeIf { it.exists() }?.let { f -> runCatching { f.readText() }.getOrNull() }
                ?.let { hex -> runCatching { TxParse.parse(hex) }.getOrNull()?.takeIf { TxParse.txid(it) == txid } }
            if (fromDisk != null) { txCache[txid] = fromDisk; return fromDisk }
            val hex = runCatching { client.transaction(txid) }.getOrNull() ?: return null
            val parsed = runCatching { TxParse.parse(hex) }.getOrNull() ?: return null
            txCache[txid] = parsed
            if (file != null && TxParse.txid(parsed) == txid) runCatching { file.writeText(hex) }
            return parsed
        }

        /** The script type the xpub prefix implies (SLIP-132), or null for a plain xpub. */
        fun scriptTypeOf(xpub: String): ScriptType? =
            runCatching { Bip32.parseExtendedPubKey(xpub).scriptType }.getOrNull()

        /** BIP purpose for a script type: 44 legacy, 49 nested, 84 native segwit, 86 taproot. */
        fun purposeFor(type: ScriptType): Int = when (type) {
            ScriptType.P2PKH -> 44
            ScriptType.P2SH_P2WPKH -> 49
            ScriptType.P2WPKH -> 84
            ScriptType.P2TR -> 86
        }
    }
}
