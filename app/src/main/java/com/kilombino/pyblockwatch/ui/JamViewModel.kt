package com.kilombino.pyblockwatch.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.crypto.BondPsbt
import com.kilombino.pyblockwatch.crypto.TxBuilder
import com.kilombino.pyblockwatch.data.AddressRow
import com.kilombino.pyblockwatch.data.BalanceWatch
import com.kilombino.pyblockwatch.data.JamWatch
import com.kilombino.pyblockwatch.data.JoinMarket
import com.kilombino.pyblockwatch.data.Notifier
import com.kilombino.pyblockwatch.data.ScanEvent
import com.kilombino.pyblockwatch.data.Scanner
import com.kilombino.pyblockwatch.data.Store
import com.kilombino.pyblockwatch.data.TxConf
import com.kilombino.pyblockwatch.data.WatchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The JoinMarket wallet on screen: five jars, their movements and the fidelity bonds. */
data class JamUi(
    val export: JoinMarket.Export? = null,
    val phase: ScanPhase = ScanPhase.Idle,
    val rows: List<AddressRow> = emptyList(),
    val txs: List<TxConf> = emptyList(),
    val height: Int = 0,
    val bonds: List<JoinMarket.BondState> = emptyList(),
    /** Movement → jar → what it did to that jar (+ in, − out). */
    val jarNets: Map<String, Map<Int, Long>> = emptyMap(),
    /** Shown from the last look this phone saved, not yet checked again. */
    val stale: Boolean = false,
    val refreshing: Boolean = false,
    val fingerprintChanged: Boolean = false,
    val fingerprint: String? = null,
    /** A fidelity bond being spent through SeedSigner, step by step; null when none. */
    val bondSpend: BondSpend? = null,
) {
    val confirmed: Long get() = rows.sumOf { it.confirmed }
    val unconfirmed: Long get() = rows.sumOf { it.unconfirmed }
    val total: Long get() = confirmed + unconfirmed
    fun jarRows(jar: Int) = rows.filter { it.jar == jar && it.chainIndex != JoinMarket.BOND_BRANCH }
    fun jarTotal(jar: Int) = jarRows(jar).sumOf { it.total }
    val bondTotal: Long get() = rows.filter { it.chainIndex == JoinMarket.BOND_BRANCH }.sumOf { it.total }
}

/**
 * Spending an unlocked fidelity bond with a signer that holds Jam's seed (our SeedSigner fork),
 * by QR both ways: [Draft] (where to, what fee) → [AwaitingSignature] (the PSBT as an animated QR,
 * waiting for the signed one) → [Review] (checked and assembled, nothing sent yet) →
 * [Broadcasting] → [Sent]. This wallet still holds no key: the PSBT carries none.
 */
sealed class BondSpend {
    abstract val state: JoinMarket.BondState
    data class Draft(override val state: JoinMarket.BondState, val destination: String, val feeRate: Double,
                     val error: String? = null, val working: Boolean = false) : BondSpend()
    data class AwaitingSignature(override val state: JoinMarket.BondState, val bond: BondPsbt.Bond,
                                 val outputs: List<TxBuilder.Output>, val destination: String, val fee: Long,
                                 val psbt: ByteArray, val error: String? = null) : BondSpend()
    data class Review(override val state: JoinMarket.BondState, val destination: String, val amount: Long, val fee: Long,
                      val signed: TxBuilder.Signed, val back: AwaitingSignature, val error: String? = null) : BondSpend()
    data class Broadcasting(override val state: JoinMarket.BondState) : BondSpend()
    data class Sent(override val state: JoinMarket.BondState, val txid: String) : BondSpend()
}

/**
 * The third wallet: a JoinMarket (Jam) wallet watched from its read-only export. It has no keys
 * and no send: spending, bonds included, happens in Jam. Its own store profile ([Store.JM]), so
 * its figures and notifications never mix with the hot or watch-only wallet's.
 */
class JamViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app, Store.JM)
    private val scanner = Scanner()
    private val notifier get() = Notifier(getApplication(), WatchService.JAM_PREFIX)
    private var job: Job? = null

    private val _state = MutableStateFlow(JamUi(export = JamWatch.export(store)))
    val state: StateFlow<JamUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                Scanner.useDisk(java.io.File(getApplication<Application>().noBackupFilesDir, "scan-cache"))
            }
            readSnapshot()?.let { s -> _state.update { it.copy(rows = s.rows, txs = s.txs, height = s.height, bonds = s.bonds, phase = ScanPhase.Complete, stale = true,
                jarNets = JamWatch.jarNets(s.txs, s.rows)) } }
        }
    }

    fun hasImport(): Boolean = _state.value.export != null

    /** Reads Jam's export and makes it THE JoinMarket wallet (any previous import is replaced). */
    fun import(text: String): String? {
        val e = runCatching { JoinMarket.parse(text) }.getOrElse { return it.message ?: "Not a JoinMarket export." }
        val previous = _state.value.export
        if (previous?.id != e.id) {
            // Another wallet: start from nothing (its baseline, alerts and saved look go).
            store.clearWallet()
            snapshotFile().delete()
            _state.value = JamUi(export = e)
        } else _state.update { it.copy(export = e) }
        store.jmExport = e.encode()
        return null
    }

    /** Removes the JoinMarket wallet from this phone. */
    fun forget() {
        job?.cancel()
        store.clearWallet()
        snapshotFile().delete()
        _state.value = JamUi()
    }

    /** May read BTC: the user's own Electrum server for it, or they agreed to the default one. */
    fun hasOwnServer(): Boolean = store.endpoint(Chain.BLAKE2B).isCustom

    /** A full look: every jar walked with the gap limit, then the bonds. */
    fun scan() {
        val e = _state.value.export ?: return
        job?.cancel()
        val chain = Chain.BLAKE2B
        val endpoint = store.endpoint(chain)
        val pin = store.pinnedFingerprint(endpoint)
        job = viewModelScope.launch {
            val found = mutableListOf<AddressRow>()
            var last = 0L
            scanner.scanAccounts(JamWatch.accounts(e), JamWatch.fixed(e), endpoint, pin, store.gapLimit).collect { ev ->
                when (ev) {
                    is ScanEvent.Connecting -> _state.update { it.copy(phase = ScanPhase.Connecting(ev.endpoint), stale = it.stale) }
                    is ScanEvent.Connected -> {
                        if (pin == null && ev.fingerprint != null) store.pinFingerprint(endpoint, ev.fingerprint)
                        _state.update { it.copy(height = ev.height, fingerprint = ev.fingerprint, fingerprintChanged = ev.fingerprintChanged) }
                    }
                    is ScanEvent.Deriving -> {
                        val now = System.currentTimeMillis()
                        if (now - last > 300) { last = now; _state.update { it.copy(phase = ScanPhase.Scanning(ev.path, ev.chainIndex, 0, store.gapLimit)) } }
                    }
                    is ScanEvent.Found -> found += ev.row
                    is ScanEvent.GapProgress -> {}
                    is ScanEvent.Done -> {
                        JamWatch.noteUsed(e, store, ev.rows)
                        store.setLastBalance(chain, ev.rows.sumOf { it.confirmed }, ev.rows.sumOf { it.unconfirmed })
                        store.setLastFullLook(chain)
                        _state.update { it.copy(rows = ev.rows, txs = ev.txs, height = ev.height, phase = ScanPhase.Complete, stale = false,
                            jarNets = JamWatch.jarNets(ev.txs, ev.rows)) }
                        checkBonds(e, ev.rows)
                    }
                    is ScanEvent.Failed -> _state.update { it.copy(phase = ScanPhase.Error(ev.message)) }
                    is ScanEvent.CertificateChanged -> _state.update { it.copy(phase = ScanPhase.Error(ev.message), fingerprint = ev.fingerprint, fingerprintChanged = true) }
                }
            }
        }
    }

    /** A quiet look at what was already found (and the bonds); a full scan when nothing was. */
    fun refresh() {
        val e = _state.value.export ?: return
        val st = _state.value
        if (st.rows.isEmpty() || (st.phase !is ScanPhase.Complete)) { if (job?.isActive != true) scan(); return }
        if (job?.isActive == true) return
        val chain = Chain.BLAKE2B
        val endpoint = store.endpoint(chain)
        val pin = store.pinnedFingerprint(endpoint)
        job = viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            runCatching {
                val quick = !store.fullLookDue(chain)
                // Rows as found plus any new addresses up to the gap past the last used one.
                val rows = withContext(Dispatchers.IO) {
                    val known = JamWatch.knownRows(e, store, store.gapLimit)
                    val have = st.rows.associateBy { it.scriptHash }
                    known.map { have[it.scriptHash] ?: it }
                }
                val (updated, txs, tip) = withContext(Dispatchers.IO) { scanner.refreshDetails(rows, endpoint, pin, quick) }
                if (!quick) store.setLastFullLook(chain)
                val used = updated.filter { it.isUsed || it.chainIndex == JoinMarket.BOND_BRANCH }
                JamWatch.noteUsed(e, store, used)
                BalanceWatch.evaluate(store, notifier, chain, used.sumOf { it.confirmed }, used.sumOf { it.unconfirmed }, txs,
                    runCatching { com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.ownTxids(getApplication()) }.getOrDefault(emptySet()))
                _state.update { it.copy(rows = used, txs = txs, height = tip, stale = false, jarNets = JamWatch.jarNets(txs, used)) }
                // A newly used address may sit past the walked range: walk again.
                if (used.filter { it.chainIndex in 0..1 }.size > st.rows.filter { it.chainIndex in 0..1 }.size) {
                    _state.update { it.copy(refreshing = false) }; job = null; scan(); return@launch
                }
                checkBonds(e, used)
            }.onFailure { err -> _state.update { it.copy(phase = ScanPhase.Error(err.message ?: "The look failed")) } }
            _state.update { it.copy(refreshing = false) }
        }
    }

    private suspend fun checkBonds(e: JoinMarket.Export, rows: List<AddressRow>) {
        val endpoint = store.endpoint(Chain.BLAKE2B)
        val states = withContext(Dispatchers.IO) {
            runCatching { JamWatch.bondStates(e, rows, endpoint, store.pinnedFingerprint(endpoint), store) }.getOrNull()
        } ?: return
        _state.update { it.copy(bonds = states) }
        JamWatch.alerts(store, notifier, states)
        withContext(Dispatchers.IO) { saveSnapshot() }
    }

    // ------------------------------------------------------------------ spending a bond (SeedSigner, by QR)

    /** A bond can be spent once its locktime has passed and it holds coins. */
    fun canSpend(b: JoinMarket.BondState, now: Long = System.currentTimeMillis() / 1000): Boolean =
        b.value != null && b.txid != null && b.height > 0 && now >= b.locktime

    /** A fresh receive address of jar 0 (Apricot): by default the bond goes back into Jam. */
    fun freshJarAddress(): String? {
        val e = _state.value.export ?: return null
        val used = _state.value.rows.filter { it.jar == 0 && it.chainIndex == 0 && it.isUsed }.maxOfOrNull { it.index } ?: -1
        val key = com.kilombino.pyblockwatch.crypto.Bip32.derivePath(JoinMarket.parseKey(e.xpubs[0]), 0, used + 1).pubkey()
        return com.kilombino.pyblockwatch.crypto.Bech32.encodeSegwit(e.hrp, 0, com.kilombino.pyblockwatch.crypto.Hashes.hash160(key))
    }

    fun startBondSpend(b: JoinMarket.BondState) {
        _state.update { it.copy(bondSpend = BondSpend.Draft(b, freshJarAddress() ?: "", 1.0)) }
        viewModelScope.launch {
            val endpoint = store.endpoint(Chain.BLAKE2B)
            val rate = withContext(Dispatchers.IO) { scanner.suggestedFeeRate(endpoint, store.pinnedFingerprint(endpoint)) } ?: return@launch
            _state.update { st -> (st.bondSpend as? BondSpend.Draft)?.let { st.copy(bondSpend = it.copy(feeRate = kotlin.math.ceil(rate))) } ?: st }
        }
    }

    fun cancelBondSpend() = _state.update { it.copy(bondSpend = null) }

    fun editBondDraft(destination: String, feeRate: Double) = _state.update { st ->
        (st.bondSpend as? BondSpend.Draft)?.let { st.copy(bondSpend = it.copy(destination = destination, feeRate = feeRate, error = null)) } ?: st
    }

    /** Builds the PSBT: the bond (with its previous transaction, fetched and checked) to one address. */
    fun makeBondPsbt() {
        val d = _state.value.bondSpend as? BondSpend.Draft ?: return
        val e = _state.value.export ?: return
        _state.update { it.copy(bondSpend = d.copy(working = true, error = null)) }
        viewModelScope.launch {
            runCatching {
                require(canSpend(d.state)) { "This bond is still locked: it can be spent from ${JoinMarket.date(d.state.locktime)}." }
                require(d.feeRate >= 1.0 && d.feeRate <= 1000.0) { "The fee rate must be between 1 and 1000 sat/vB." }
                val txid = d.state.txid!!.substringBefore(":")
                val endpoint = store.endpoint(Chain.BLAKE2B)
                val prev = withContext(Dispatchers.IO) {
                    val c = ElectrumClient(endpoint, store.pinnedFingerprint(endpoint))
                    try { c.connect(); c.transaction(txid) } finally { c.close() }
                }
                val (bond, outputs) = JamWatch.bondSpend(e, d.state, prev, d.destination, d.feeRate)
                val fee = bond.value - outputs.sumOf { it.value }
                BondSpend.AwaitingSignature(d.state, bond, outputs, d.destination.trim(), fee, BondPsbt.create(bond, outputs))
            }.onSuccess { a -> _state.update { it.copy(bondSpend = a) } }
             .onFailure { err -> _state.update { it.copy(bondSpend = d.copy(working = false, error = err.message ?: "Could not make the PSBT.")) } }
        }
    }

    /** The signed PSBT is back (from the animated QR, or pasted): check it and assemble the transaction. */
    fun bondSigned(psbt: ByteArray) {
        val a = _state.value.bondSpend as? BondSpend.AwaitingSignature ?: return
        runCatching { BondPsbt.finish(a.psbt, psbt, a.bond, a.outputs) }
            .onSuccess { tx -> _state.update { it.copy(bondSpend = BondSpend.Review(a.state, a.destination, a.outputs[0].value, a.fee, tx, a)) } }
            .onFailure { err -> _state.update { it.copy(bondSpend = a.copy(error = err.message ?: "Could not use that PSBT.")) } }
    }

    fun bondSignedText(text: String) = runCatching { com.kilombino.pyblockwatch.crypto.Psbt.decodeText(text) }
        .onSuccess { bondSigned(it) }
        .onFailure { _ -> _state.update { st -> (st.bondSpend as? BondSpend.AwaitingSignature)?.let { st.copy(bondSpend = it.copy(error = "That is not a PSBT (base64 or hex).")) } ?: st } }

    fun bondBackToQr() = _state.update { st -> (st.bondSpend as? BondSpend.Review)?.let { st.copy(bondSpend = it.back.copy(error = null)) } ?: st }

    /** Sends the reviewed transaction. */
    fun broadcastBond() {
        val r = _state.value.bondSpend as? BondSpend.Review ?: return
        _state.update { it.copy(bondSpend = BondSpend.Broadcasting(r.state)) }
        viewModelScope.launch {
            val endpoint = store.endpoint(Chain.BLAKE2B)
            runCatching { withContext(Dispatchers.IO) { scanner.broadcast(r.signed.rawHex, endpoint, store.pinnedFingerprint(endpoint)) } }
                .onSuccess { txid -> _state.update { it.copy(bondSpend = BondSpend.Sent(r.state, txid)) }; refresh() }
                .onFailure { err -> _state.update { it.copy(bondSpend = r.copy(error = err.message ?: "The broadcast failed.")) } }
        }
    }

    // ------------------------------------------------------------------ the last look, kept

    private class Snap(val rows: List<AddressRow>, val txs: List<TxConf>, val height: Int, val bonds: List<JoinMarket.BondState>)

    private fun snapshotFile() = java.io.File(getApplication<Application>().noBackupFilesDir, "jam-snapshot.json")

    private fun saveSnapshot() {
        val s = _state.value
        val e = s.export ?: return
        runCatching {
            val rows = org.json.JSONArray()
            s.rows.forEach { r ->
                rows.put(org.json.JSONObject().put("c", r.chainIndex).put("i", r.index).put("a", r.address).put("p", r.path)
                    .put("s", r.scriptHash).put("cf", r.confirmed).put("u", r.unconfirmed).put("n", r.txCount).put("j", r.jar ?: -1))
            }
            val txs = org.json.JSONArray()
            s.txs.forEach { t ->
                txs.put(org.json.JSONObject().put("id", t.txid).put("cf", t.confirmations).put("p", t.pending)
                    .put("a", t.amount ?: org.json.JSONObject.NULL))
            }
            val bonds = org.json.JSONArray()
            s.bonds.forEach { b ->
                bonds.put(org.json.JSONObject().put("tn", b.bond.timenumber).put("v", b.value ?: -1).put("t", b.txid ?: "")
                    .put("h", b.height).put("ct", b.confirmationTime ?: -1).put("x", b.extraValue).put("u", b.everUsed))
            }
            val f = snapshotFile(); val tmp = java.io.File(f.path + ".tmp")
            tmp.writeText(org.json.JSONObject().put("id", e.id).put("rows", rows).put("txs", txs).put("bonds", bonds).put("h", s.height).toString())
            tmp.renameTo(f)
        }
    }

    private fun readSnapshot(): Snap? {
        val e = _state.value.export ?: return null
        return runCatching {
            val o = org.json.JSONObject(snapshotFile().readText())
            if (o.getString("id") != e.id) return null
            val ra = o.getJSONArray("rows"); val ta = o.getJSONArray("txs"); val ba = o.getJSONArray("bonds")
            val rows = (0 until ra.length()).map { val r = ra.getJSONObject(it)
                AddressRow(r.getInt("c"), r.getInt("i"), r.getString("a"), r.getString("p"), r.getString("s"),
                    r.getLong("cf"), r.getLong("u"), r.getInt("n"), jar = r.getInt("j").takeIf { j -> j >= 0 })
            }
            val txs = (0 until ta.length()).map { val t = ta.getJSONObject(it)
                TxConf(t.getString("id"), t.getInt("cf"), t.getBoolean("p"), if (t.isNull("a")) null else t.getLong("a"))
            }
            val scripts = JoinMarket.bondScripts(e).associateBy { it.bond.timenumber }
            val bonds = (0 until ba.length()).mapNotNull { val b = ba.getJSONObject(it)
                val s = scripts[b.getInt("tn")] ?: return@mapNotNull null
                JoinMarket.BondState(s, b.getLong("v").takeIf { v -> v >= 0 }, b.getString("t").ifEmpty { null }, b.getInt("h"),
                    b.getLong("ct").takeIf { t -> t > 0 }, b.getLong("x"), b.getBoolean("u"))
            }
            Snap(rows, txs, o.optInt("h"), bonds)
        }.getOrNull()
    }
}
