package com.kilombino.pyblockwatch.data

import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.chain.NodeEndpoint
import com.kilombino.pyblockwatch.crypto.ScriptType

/**
 * The JoinMarket wallet's moving parts, shared by the screen and the background watcher: which
 * accounts and scripts to look at, what each fidelity bond is doing, and the bond alerts.
 * Read-only throughout: nothing here can sign.
 */
object JamWatch {

    /** The imported export, or null (none, or unreadable). */
    fun export(store: Store): JoinMarket.Export? = store.jmExport?.let { runCatching { JoinMarket.parse(it) }.getOrNull() }

    /** The five jars, walked like any BIP-84 account (receive and change). */
    fun accounts(e: JoinMarket.Export): List<Scanner.Account> =
        e.xpubs.mapIndexed { md, x -> Scanner.Account(normalized(x), ScriptType.P2WPKH, accountIndex = md, jar = md) }

    /** The bonds: fixed P2WSH scripts on mixdepth 0's branch 2. */
    fun fixed(e: JoinMarket.Export): List<Scanner.Fixed> = JoinMarket.bondScripts(e).map {
        Scanner.Fixed(it.address, it.scriptHash, it.path, JoinMarket.BOND_BRANCH, it.bond.timenumber, jar = 0)
    }

    /** The key as the scanner reads it (a test network's vpub as a zpub; mainnet keys unchanged). */
    private fun normalized(x: String): String = if (x.startsWith("zpub") || x.startsWith("xpub")) x else
        com.kilombino.pyblockwatch.crypto.Base58.encodeChecked(
            byteArrayOf(0x04, 0xb2.toByte(), 0x47, 0x46) + com.kilombino.pyblockwatch.crypto.Base58.decodeChecked(x).copyOfRange(4, 78))

    /**
     * The addresses a look already found worth watching, without a gap walk: per jar and branch,
     * every one used so far plus the gap, and every bond. For the background watcher.
     */
    fun knownRows(e: JoinMarket.Export, store: Store, gap: Int): List<AddressRow> {
        val out = mutableListOf<AddressRow>()
        accounts(e).forEach { a ->
            val key = JoinMarket.parseKey(a.xpub)
            for (chainIndex in 0..1) {
                val depth = store.usedTop(a.xpub, chainIndex, ScriptType.P2WPKH) + gap
                Scanner.derivedRange(key, ScriptType.P2WPKH, chainIndex, depth).forEachIndexed { i, (address, sh) ->
                    out += AddressRow(chainIndex, i, address, "m/84'/0'/${a.accountIndex}'/$chainIndex/$i", sh, jar = a.jar)
                }
            }
        }
        fixed(e).forEach { f -> out += AddressRow(f.chainIndex, f.index, f.address, f.path, f.scriptHash, jar = f.jar) }
        return out
    }

    /** Remembers how far each jar's branches are used, so the watcher follows new addresses. */
    fun noteUsed(e: JoinMarket.Export, store: Store, rows: List<AddressRow>) {
        val keys = accounts(e)
        rows.filter { it.isUsed && it.jar != null && it.chainIndex in 0..1 }.groupBy { it.jar!! to it.chainIndex }
            .forEach { (k, rs) -> store.noteUsedTop(keys[k.first].xpub, k.second, rs.maxOf { it.index } + 1, ScriptType.P2WPKH) }
    }

    /**
     * What each bond is doing: its UTXO (the largest at the address counts as the bond, as in
     * JoinMarket), when it confirmed (block time, kept once known) and whether the address was
     * ever used. One connection, the unspent lists in one batch.
     */
    fun bondStates(e: JoinMarket.Export, rows: List<AddressRow>, endpoint: NodeEndpoint, pin: String?, store: Store): List<JoinMarket.BondState> =
        bondStates(e, rows, endpoint, pin, store::confTime, store::setConfTime)

    /** The same, with the confirmation-time cache passed in (tests have no [Store]). */
    fun bondStates(e: JoinMarket.Export, rows: List<AddressRow>, endpoint: NodeEndpoint, pin: String?,
                   confTime: (String) -> Long?, setConfTime: (String, Long) -> Unit): List<JoinMarket.BondState> {
        val scripts = JoinMarket.bondScripts(e)
        if (scripts.isEmpty()) return emptyList()
        val used = rows.filter { it.chainIndex == JoinMarket.BOND_BRANCH && it.isUsed }.map { it.scriptHash }.toSet()
        val client = ElectrumClient(endpoint, pin)
        return try {
            client.connect()
            client.listUnspentMany(scripts.map { it.scriptHash }).zip(scripts).map { (utxos, s) ->
                val best = utxos.maxByOrNull { it.value }
                val time = best?.takeIf { it.height > 0 }?.let { u ->
                    confTime(u.txid) ?: runCatching { blockTime(client.blockHeader(u.height)) }.getOrNull()?.also { setConfTime(u.txid, it) }
                }
                JoinMarket.BondState(s, value = best?.value, txid = best?.let { "${it.txid}:${it.vout}" }, height = best?.height ?: 0,
                    confirmationTime = time, extraValue = utxos.sumOf { it.value } - (best?.value ?: 0),
                    everUsed = s.scriptHash in used || utxos.isNotEmpty())
            }
        } finally {
            client.close()
        }
    }

    /**
     * The spend of bond [s] that Kilowallet hands to SeedSigner: the whole bond, with the
     * transaction that created it ([prevTxHex], checked against the bond), to [destination] less
     * the fee at [feeRate] sat/vB. Nothing here holds a key.
     */
    fun bondSpend(e: JoinMarket.Export, s: JoinMarket.BondState, prevTxHex: String, destination: String, feeRate: Double):
        Pair<com.kilombino.pyblockwatch.crypto.BondPsbt.Bond, List<com.kilombino.pyblockwatch.crypto.TxBuilder.Output>> {
        val value = s.value ?: throw IllegalArgumentException("This bond holds no coins.")
        val (txid, vout) = (s.txid ?: throw IllegalArgumentException("This bond holds no coins.")).split(":").let { it[0] to it[1].toInt() }
        val out = runCatching { JoinMarket.scriptPubKey(e, destination) }
            .getOrElse { throw IllegalArgumentException("That destination address is not valid.") }
        val tn = s.bond.timenumber
        val pub = JoinMarket.bondPubkey(JoinMarket.parseKey(e.xpubs[0]), tn)
        val bond = com.kilombino.pyblockwatch.crypto.BondPsbt.Bond(txid, vout, value, pub, s.locktime, JoinMarket.bondScript(pub, s.locktime),
            JoinMarket.bondOrigin(e, tn), com.kilombino.pyblockwatch.crypto.Hashes.hexToBytes(prevTxHex))
        val probe = listOf(com.kilombino.pyblockwatch.crypto.TxBuilder.Output(out, value))
        val fee = kotlin.math.ceil(com.kilombino.pyblockwatch.crypto.BondPsbt.vbytes(bond, probe) * feeRate).toLong()
        require(value - fee >= 1000) { "The fee would take the whole bond." }
        return bond to listOf(com.kilombino.pyblockwatch.crypto.TxBuilder.Output(out, value - fee))
    }

    /** A block header's time (bytes 68–71, little-endian), 80- or 164-byte headers alike. */
    fun blockTime(headerHex: String): Long {
        val b = com.kilombino.pyblockwatch.crypto.Hashes.hexToBytes(headerHex)
        return (0 until 4).fold(0L) { a, i -> a or ((b[68 + i].toLong() and 0xff) shl (8 * i)) }
    }

    /** Bond alerts: each kind once per bond UTXO (a renewed bond is a new UTXO and is told about again). */
    fun alerts(store: Store, notifier: Notifier, states: List<JoinMarket.BondState>, now: Long = System.currentTimeMillis() / 1000) {
        for (s in states) {
            val alert = JoinMarket.alertFor(s, now) ?: continue
            val key = "${s.txid}_${alert.name}"
            if (store.alerted(key)) continue
            val amount = s.value ?: continue
            when (alert) {
                JoinMarket.Alert.UNLOCKS_SOON -> notifier.bondUnlocksSoon(amount, s.locktime, ((s.locktime - now + 86_399) / 86_400).toInt(), key)
                JoinMarket.Alert.UNLOCKED -> notifier.bondUnlocked(amount, s.locktime, key)
                JoinMarket.Alert.VALUE_ZERO -> notifier.bondValueZero(amount, key)
            }
            store.setAlerted(key)
        }
    }

    /** Each movement split by jar (bonds count in Apricot), from the transactions a look fetched. */
    fun jarNets(txs: List<TxConf>, rows: List<AddressRow>): Map<String, Map<Int, Long>> {
        val byJar = rows.filter { it.jar != null }.groupBy { it.jar!! }.mapValues { (_, rs) -> rs.map { it.scriptHash }.toSet() }
        return txs.associate { t ->
            t.txid to byJar.mapNotNull { (jar, scripts) -> Scanner.cachedNet(t.txid, scripts)?.takeIf { it != 0L }?.let { jar to it } }.toMap()
        }
    }
}
