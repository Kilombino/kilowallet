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

    fun scan(
        xpub: String,
        chain: Chain,
        endpoint: NodeEndpoint,
        pinnedFingerprint: String?,
        scriptType: ScriptType,
        gap: Int = gapLimit,
    ): Flow<ScanEvent> = flow {
        val purpose = purposeFor(scriptType)
        val txHeights = HashMap<String, Int>()
        val parsed = try {
            Bip32.parseExtendedPubKey(xpub)
        } catch (e: IllegalArgumentException) {
            emit(ScanEvent.Failed(e.message ?: "Invalid extended key")); return@flow
        }

        emit(ScanEvent.Connecting(endpoint))
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
            for (chainIndex in 0..1) {
                val branch = Bip32.deriveChild(parsed, chainIndex)
                var index = 0
                var consecutiveEmpty = 0
                while (consecutiveEmpty < gap) {
                    val path = "m/$purpose'/0'/0'/$chainIndex/$index"
                    emit(ScanEvent.Deriving(chainIndex, index, path))

                    val child = Bip32.deriveChild(branch, index)
                    val scriptHash = Address.scriptHashFor(child.pubkey(), scriptType)
                    val address = Address.encode(child.pubkey(), scriptType)

                    // History first, balance only when there IS history. Most addresses
                    // in a scan are unused, and asking for a balance we already know is
                    // zero doubles the round trips on the common path.
                    val hist = client.history(scriptHash)
                    val txs = hist.size
                    val bal = if (txs > 0) client.balance(scriptHash) else ScriptHashBalance(0, 0)

                    val row = AddressRow(
                        chainIndex = chainIndex, index = index, address = address,
                        path = path, scriptHash = scriptHash,
                        confirmed = bal.confirmed, unconfirmed = bal.unconfirmed, txCount = txs,
                    )
                    if (row.isUsed) {
                        rows += row
                        hist.forEach { txHeights[it.txid] = it.height }
                        consecutiveEmpty = 0
                        emit(ScanEvent.Found(row))
                    } else {
                        consecutiveEmpty++
                        emit(ScanEvent.GapProgress(chainIndex, consecutiveEmpty, gap))
                    }
                    index++
                }
            }
            // Confirmations from the tip: height <= 0 is still in the mempool (0 conf).
            val amounts = netAmounts(client, txHeights.keys, rows.map { it.scriptHash }.toSet())
            val txs = txHeights.map { (id, h) ->
                val a = amounts[id]
                TxConf(id, if (h <= 0) 0 else height - h + 1, pending = h <= 0, amount = a,
                    spv = if (h > 0 && a != null && a > 0 && height - h < SPV_WINDOW) spvCheck(client, id, h) else null)
            }.sortedWith(compareBy({ !it.pending }, { it.confirmations }))
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
        rows: List<AddressRow>, endpoint: NodeEndpoint, pinnedFingerprint: String?,
    ): Triple<List<AddressRow>, List<TxConf>, Int> {
        val client = ElectrumClient(endpoint, pinnedFingerprint)
        return try {
            client.connect()
            val tip = client.blockHeight()
            val txHeights = HashMap<String, Int>()
            // One status call per address; history and balance only for those that changed
            // since the last refresh (or were never seen). Most refreshes find nothing new,
            // so this halves the round trips, and an unused address costs a single call.
            val updated = rows.map { r ->
                val key = endpoint.toString() + "|" + r.scriptHash
                val status = client.status(r.scriptHash)
                val known = statusCache[key]?.takeIf { it.status == status }
                val entry = known ?: if (status == null) CachedAddress(null, emptyList(), ScriptHashBalance(0, 0))
                    else CachedAddress(status, client.history(r.scriptHash), client.balance(r.scriptHash))
                statusCache[key] = entry
                entry.history.forEach { txHeights[it.txid] = it.height }
                r.copy(confirmed = entry.balance.confirmed, unconfirmed = entry.balance.unconfirmed, txCount = entry.history.size)
            }
            val amounts = netAmounts(client, txHeights.keys, rows.map { it.scriptHash }.toSet())
            val txs = txHeights.map { (id, h) ->
                val a = amounts[id]
                TxConf(id, if (h <= 0) 0 else tip - h + 1, pending = h <= 0, amount = a,
                    spv = if (h > 0 && a != null && a > 0 && tip - h < SPV_WINDOW) spvCheck(client, id, h) else null)
            }.sortedWith(compareBy({ !it.pending }, { it.confirmations }))
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
    private fun netAmounts(client: ElectrumClient, txids: Collection<String>, ours: Set<String>): Map<String, Long> {
        val txs = txids.mapNotNull { id ->
            txCache[id]?.let { id to it }
                ?: runCatching { TxParse.parse(client.transaction(id)) }.getOrNull()?.also { txCache[id] = it }?.let { id to it }
        }.toMap()
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
    )

    /** Every unspent output in [rows], immature mined rewards included (for display). */
    suspend fun listCoins(
        rows: List<AddressRow>, endpoint: NodeEndpoint, pinnedFingerprint: String?,
    ): List<SpendableUtxo> {
        val client = ElectrumClient(endpoint, pinnedFingerprint)
        return try {
            client.connect()
            rows.flatMap { r ->
                client.listUnspent(r.scriptHash).map { SpendableUtxo(it.txid, it.vout, it.value, r.chainIndex, r.index, it.height) }
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
            val all = rows.flatMap { r ->
                client.listUnspent(r.scriptHash).map {
                    SpendableUtxo(it.txid, it.vout, it.value, r.chainIndex, r.index, it.height)
                }
            }
            // A mined reward (coinbase) can't be spent until it matures; a spend using one would
            // only fail at broadcast. Leave out the immature ones.
            val tip = runCatching { client.blockHeight() }.getOrNull() ?: return all
            all.filter { u ->
                if (u.height <= 0 || tip - u.height + 1 >= LONG_MATURITY) return@filter true
                val tx = txCache[u.txid] ?: runCatching { TxParse.parse(client.transaction(u.txid)) }.getOrNull()
                    ?.also { txCache[u.txid] = it } ?: return@filter true
                val coinbase = tx.inputs.size == 1 && tx.inputs[0].txid.all { it == '0' } && tx.inputs[0].vout == -1
                !coinbase || tip - u.height + 1 >= maturityFor(u.height)
            }
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
        private val spvCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        /**
         * Coinbase maturity on the BLAKE2b chain: the `long_coinbase_maturity` flag day makes
         * rewards mined in blocks 973 440–979 919 wait 6 480 confirmations; any other, 100.
         */
        const val LONG_MATURITY = 6480
        fun maturityFor(height: Int): Int = if (height in 973_440..979_919) LONG_MATURITY else 100

        /** What an address looked like at its last status, so an unchanged one needs no more calls. */
        private class CachedAddress(val status: String?, val history: List<ElectrumClient.HistoryItem>, val balance: ScriptHashBalance)
        private val statusCache = java.util.concurrent.ConcurrentHashMap<String, CachedAddress>()

        /** Parsed wallet transactions by txid; a transaction never changes, so it is fetched once. */
        private val txCache = java.util.concurrent.ConcurrentHashMap<String, TxParse.Tx>()

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
