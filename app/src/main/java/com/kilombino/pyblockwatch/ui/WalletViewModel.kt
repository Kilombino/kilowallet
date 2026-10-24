package com.kilombino.pyblockwatch.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.chain.NodeEndpoint
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.data.AddressRow
import com.kilombino.pyblockwatch.data.BalanceWatch
import com.kilombino.pyblockwatch.data.Notifier
import com.kilombino.pyblockwatch.data.ScanEvent
import com.kilombino.pyblockwatch.data.Scanner
import com.kilombino.pyblockwatch.data.Store
import com.kilombino.pyblockwatch.data.TxConf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the scan is doing right now, so the UI can narrate rather than spin. */
sealed interface ScanPhase {
    data object Idle : ScanPhase
    data class Connecting(val endpoint: NodeEndpoint) : ScanPhase
    data class Scanning(val path: String, val chainIndex: Int, val gapUsed: Int, val gapLimit: Int) : ScanPhase
    data object Complete : ScanPhase
    data class Error(val message: String) : ScanPhase
}

/** A prepared, not-yet-signed spend: the coins chosen, the fee, and the outputs. */
data class SendDraft(
    val toAddress: String,
    val amount: Long,
    val fee: Long,
    val change: Long,
    val inputs: List<com.kilombino.pyblockwatch.data.Scanner.SpendableUtxo>,
    val outputs: List<com.kilombino.pyblockwatch.crypto.TxBuilder.Output>,
    // The chain this draft was prepared against, fixed at prepare time. confirmSend signs and
    // broadcasts under it, so switching chain during review cannot change which sighash (unified
    // on BLAKE2b, legacy on SHA-256) a reviewed spend is signed with.
    val chain: Chain,
    // Set for a silent payment: the recipient's keys. Output 0's real scriptPubKey depends on
    // the input private keys, so it is only computed at signing time and replaces the placeholder.
    val silentRecipient: com.kilombino.pyblockwatch.crypto.SilentPayment.Recipient? = null,
    /** Set when this draft replaces an unconfirmed send (RBF): its txid and the fee it paid. */
    val replaces: String? = null,
    val replacedFee: Long = 0,
    /** A cancellation (RBF): [replaces] is sent back to this wallet instead of to its recipients. */
    val cancels: Boolean = false,
    /** More recipients after the first, in output order (address as typed, sats). */
    val extra: List<Pair<String, Long>> = emptyList(),
    /** The BIP-353 handle (user@domain) the first recipient was resolved from, if any. */
    val handle: String? = null,
    /**
     * A spamchain draft only: its pre-fork coins that are still unspent on BTC, the ones a
     * rebroadcast of this spend there would move (empty: none, all already split; null:
     * BTC could not be asked).
     */
    val replayableOnBtc: List<String>? = emptyList(),
    /** Coinjoin pools (still waiting for people) one of this draft's coins is seated in: sending leaves them. */
    val leavesPools: List<String> = emptyList(),
    /** Set for a CPFP: the stuck transaction this child pulls along, and the pair's fee rate. */
    val cpfpParent: String? = null,
    /** Where the change goes (one of this wallet's addresses), shown in the review; null when there is none. */
    val changeAddress: String? = null,
    val packageRate: Double = 0.0,
) {
    /** Everything that leaves the wallet: the first recipient plus the extra ones. */
    val totalSent: Long get() = amount + extra.sumOf { it.second }
}

/** One coin found on a private key being swept, with the address form it sits in. */
data class SweepCoin(
    val type: com.kilombino.pyblockwatch.crypto.ScriptType,
    val address: String,
    val txid: String, val vout: Int, val value: Long, val height: Int,
)

/** A prepared sweep: every coin of the key, moved whole to this wallet's next address. */
data class SweepDraft(
    val key: com.kilombino.pyblockwatch.crypto.Wif.Key,
    val coins: List<SweepCoin>,
    val toAddress: String,
    val fee: Long,
    val chain: Chain,
    /** Coins the same key holds on the other chain, swept separately from that chain. */
    val otherChainSats: Long?,
) {
    val total: Long get() = coins.sumOf { it.value }
    val received: Long get() = total - fee
}

sealed interface SweepPhase {
    data object Idle : SweepPhase
    data object Scanning : SweepPhase
    data class Review(val draft: SweepDraft) : SweepPhase
    data object Broadcasting : SweepPhase
    data class Sent(val txid: String, val chain: Chain) : SweepPhase
    data class Failed(val message: String) : SweepPhase
}

sealed interface RescuePhase {
    data object Idle : RescuePhase
    data class Busy(val message: String) : RescuePhase
    data class Review(val result: com.kilombino.pyblockwatch.data.LndRescue.Result) : RescuePhase
    data class Done(
        val replayed: List<String>, val replayErrors: List<String>, val sweepTxid: String?, val swept: Long,
        /** Delayed channel outputs not spendable yet: sats and the most blocks still to wait. */
        val pendingSats: Long, val pendingBlocks: Int,
    ) : RescuePhase
    data class Failed(val message: String) : RescuePhase
}

/** Where the send flow is, so the UI can move from editing → review → broadcast → done. */
sealed interface SendPhase {
    data object Editing : SendPhase
    data object Preparing : SendPhase
    data class Review(val draft: SendDraft) : SendPhase
    /** Watch-only: the PSBT is out with the signer; only a signed copy of this exact one is taken back. */
    data class AwaitingSignature(val draft: SendDraft, val psbt: ByteArray) : SendPhase {
        val base64: String get() = com.kilombino.pyblockwatch.crypto.Psbt.base64(psbt)
    }
    data object Broadcasting : SendPhase
    data class Sent(val txid: String) : SendPhase
    data class Failed(val message: String) : SendPhase
}

data class ChainState(
    val phase: ScanPhase = ScanPhase.Idle,
    val rows: List<AddressRow> = emptyList(),
    val height: Int = 0,
    val server: String? = null,
    val fingerprint: String? = null,
    val fingerprintChanged: Boolean = false,
    val endpoint: NodeEndpoint? = null,
    val transactions: List<TxConf> = emptyList(),
    /** What a background refresh (pull-down or the 30-second one) is checking now; null when idle. */
    val refreshing: String? = null,
    /**
     * The rows and movements are the last ones this phone saw (shown at once on opening),
     * not yet checked against the server this run. Cleared by the first look that completes.
     */
    val stale: Boolean = false,
) {
    val confirmed: Long get() = rows.sumOf { it.confirmed }
    val unconfirmed: Long get() = rows.sumOf { it.unconfirmed }
    val total: Long get() = confirmed + unconfirmed
    val usedAddresses: Int get() = rows.size
}

data class UiState(
    val xpub: String? = null,
    val label: String = "",
    val scriptType: ScriptType? = null,
    val selected: Chain = Chain.BLAKE2B,
    val chains: Map<Chain, ChainState> = Chain.entries.associateWith { ChainState() },
    val notificationsEnabled: Boolean = false,
    /** Seconds between refreshes while open (0 = by hand) and minutes in the background (0 = off). */
    val refreshOpenSeconds: Int = 300,
    val refreshBackgroundMinutes: Int = 15,
    val gapLimit: Int = 20,
    /** When the next foreground refresh is due (ms since the epoch). */
    val nextRefreshAt: Long = 0,
    val inputError: String? = null,
    /** Can sign right now: a seed is stored AND the wallet is not shown as watch-only. */
    val isHot: Boolean = false,
    /** Why the last signed PSBT brought back was refused, shown while waiting for another. */
    val psbtError: String? = null,
    /** A seed is stored on this phone (it may be shown as watch-only). */
    val hasSeed: Boolean = false,
    val sendPhase: SendPhase = SendPhase.Editing,
    val setupMode: Boolean = false,
    val utxos: List<com.kilombino.pyblockwatch.data.Scanner.SpendableUtxo>? = null, // null = not loaded
    val utxosLoading: Boolean = false,
    // The chain the cached [utxos] were gathered for; null when none. Guards against a stale
    // in-flight fetch repopulating the coin picker with another chain's coins.
    val utxosChain: Chain? = null,
    /** "simple", "advanced", or null when the user has not chosen yet. */
    val uiMode: String? = null,
    /** "USD" or "EUR". */
    val fiat: String = "USD",
    val market: com.kilombino.pyblockwatch.data.MarketData? = null,
    /** Hot wallet: its Taproot account (BIP-86), null until turned on; always scanned once set. */
    val taprootXpub: String? = null,
    /** Hot wallet: new receive and change addresses are Taproot (bc1p). */
    val receiveTaproot: Boolean = false,
    /** The JoinMarket wallet (read-only, from Jam) is on screen instead of the hot/watch-only one. */
    val jamView: Boolean = false,
) {
    val current: ChainState get() = chains[selected] ?: ChainState()
    val hasWallet: Boolean get() = !xpub.isNullOrBlank()
    /** Show the wallet only when one exists AND the user is not in the middle of setup. */
    val showWallet: Boolean get() = hasWallet && !setupMode
}

class WalletViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * The wallet on screen: the hot one (from the seed) or the separate watch-only xpub.
     * Global settings (nodes, explorers, mode, coinjoin…) are shared; per-wallet ones are not.
     */
    private var store = Store(app, migrateWallets(app))
    private val scanner = Scanner()
    /** Notifications of the wallet on screen; the watch-only one says so in the title. */
    private val notifier: Notifier get() =
        Notifier(getApplication(), if (store.activeWallet == Store.WATCH) "Watch-only · " else "")
    private val seedVault = com.kilombino.pyblockwatch.data.SeedVault(app)
    private val jobs = mutableMapOf<Chain, Job>()
    /** One refresh per chain at a time: on a slow server they would pile up every 30 s. */
    private val refreshJobs = mutableMapOf<Chain, Job>()
    private var refreshJob: Job? = null

    /** True when this wallet holds an encrypted seed and can therefore sign/spend. */
    fun hasSeed(): Boolean = seedVault.hasSeed()
    /** A Keystore cipher to encrypt the seed; authorise it with BiometricPrompt first. */
    fun seedEncryptCipher() = seedVault.encryptCipher()
    /** A Keystore cipher to decrypt the seed; authorise it with BiometricPrompt first. */
    fun seedDecryptCipher() = seedVault.decryptCipher()

    fun toast(msg: String) = android.widget.Toast.makeText(getApplication(), com.kilombino.pyblockwatch.ui.i18n.I18n.tr(msg), android.widget.Toast.LENGTH_LONG).show()

    /** [seedDecryptCipher] for a confirm button: when it fails, say why instead of doing nothing. */
    fun seedCipherOrToast(): javax.crypto.Cipher? = runCatching { seedVault.decryptCipher() }.getOrElse { e ->
        android.widget.Toast.makeText(getApplication(), com.kilombino.pyblockwatch.ui.i18n.I18n.tr(e.message ?: "The wallet key could not be opened."), android.widget.Toast.LENGTH_LONG).show()
        null
    }

    /** The spending wallet's words, with a [seedDecryptCipher] the user has authorised. */
    fun revealSeed(decryptCipher: javax.crypto.Cipher): List<String> = seedVault.reveal(decryptCipher)

    /** The spending wallet's words and passphrase, with an authorised [decryptCipher]. */
    fun revealSecret(decryptCipher: javax.crypto.Cipher) = seedVault.revealSecret(decryptCipher)

    /** True when the spending wallet's seed has a BIP-39 passphrase. */
    fun hasPassphrase(): Boolean = seedVault.hasPassphrase()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val xpub = store.xpub
        // A hot wallet set to another address type in an older version: back to its own BIP84.
        if (seedVault.hasSeed() && store.activeWallet != Store.WATCH && store.scriptType != ScriptType.P2WPKH)
            store.scriptType = ScriptType.P2WPKH
        _state.update {
            it.copy(
                xpub = xpub,
                label = store.label,
                scriptType = store.scriptType,
                // Always open on BTC: the spamchain only after its privacy warning.
                selected = Chain.BLAKE2B,
                notificationsEnabled = store.notificationsEnabled,
                refreshOpenSeconds = store.refreshOpenSeconds,
                refreshBackgroundMinutes = store.refreshBackgroundMinutes,
                gapLimit = store.gapLimit,
                isHot = seedVault.hasSeed() && store.activeWallet != Store.WATCH,
                hasSeed = seedVault.hasSeed(),
                uiMode = store.uiMode,
                fiat = store.fiat,
                market = com.kilombino.pyblockwatch.data.MarketFeed.cached(app),
                taprootXpub = store.taprootXpub.takeIf { seedVault.hasSeed() && store.activeWallet != Store.WATCH },
                receiveTaproot = store.receiveTaproot,
                jamView = store.jamView && hasJamWallet(),
            )
        }
        // 0.19.0 had coinjoin without the opt-in: whoever already took part keeps it on.
        if (!store.coinjoinAsked) runCatching {
            if (app.getSharedPreferences("coinjoin", android.content.Context.MODE_PRIVATE).contains("sessions")) {
                store.coinjoinAsked = true; store.coinjoinEnabled = true; store.coinjoinNotify = true
            }
        }
        // The caches, verified headers and last snapshots come off the disk away from the
        // screen: read (and the headers re-hashed) on the main thread they held up the first
        // frame by 30–150 ms, more as the wallet grew. The looks start once they are in.
        viewModelScope.launch {
            val snaps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Scanner.useDisk(java.io.File(getApplication<Application>().noBackupFilesDir, "scan-cache"))
                if (xpub == null) emptyMap() else scannableChains().associateWith { readSnapshot(it) }
            }
            if (_state.value.xpub == xpub) snaps.forEach { (chain, snap) -> look(chain, snap) }
        }
        startRefreshLoop()
        refreshMarket()
    }

    /**
     * While the app is open, refresh the SELECTED chain every [Store.refreshOpenSeconds]
     * (5 minutes by default; never, when set to by hand) with a visible countdown, so the user can see the wallet is live rather than wonder
     * whether it is stuck. Each refresh runs the same notification engine as the background
     * watcher, so a movement seen here fires the same alerts — just far sooner.
     */
    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            var visibleBefore = true
            while (true) {
                val every = store.refreshOpenSeconds
                // By hand only: no countdown, no automatic look (the ↻ button and pulling down do it).
                if (every <= 0) { _state.update { it.copy(nextRefreshAt = 0) }; return@launch }
                _state.update { it.copy(nextRefreshAt = System.currentTimeMillis() + every * 1000L) }
                // Sleep in short steps so coming back to the app refreshes at once.
                var waited = 0
                while (waited < every * 1000) {
                    delay(1_000); waited += 1_000
                    val visible = com.kilombino.pyblockwatch.ui.AppVisible.value
                    if (visible && !visibleBefore) { visibleBefore = true; break }
                    visibleBefore = visible
                }
                // In the background the watcher service handles notifications; nobody is
                // looking at these figures, so don't touch the network for them.
                if (!com.kilombino.pyblockwatch.ui.AppVisible.value) continue
                if (_state.value.xpub.isNullOrBlank()) continue
                refresh(_state.value.selected)
                refreshMarket() // cheap: MarketFeed only calls out when the server allows it
            }
        }
    }

    /**
     * Silent balance refresh of the addresses already found for [chain] — no gap walk,
     * no "scanning" flicker. Falls back to a full scan if nothing has been found yet.
     * On success it feeds the fresh figures to [BalanceWatch], which decides whether the
     * change deserves a notification (mempool arrival, first confirmation, …).
     */
    fun refresh(chain: Chain, full: Boolean = false) {
        // This wallet's store and notifier, fixed now: a switch to the other wallet while this
        // runs must not file its figures (or its notifications) under the other one.
        val ws = store; val nf = notifier
        if (!mayContact(chain)) return
        val xpub = _state.value.xpub ?: return
        val cs = _state.value.chains[chain] ?: return
        if (cs.rows.isEmpty() || cs.phase !is ScanPhase.Complete) {
            // A scan still running is left alone: restarting it every 30 s meant a wallet with
            // many addresses on a slow server never finished, and kept the radio busy for nothing.
            if (jobs[chain]?.isActive == true) return
            scan(chain); return
        }
        if (refreshJobs[chain]?.isActive == true) return
        refreshJobs[chain] = viewModelScope.launch {
            updateIf(xpub, chain) { it.copy(refreshing = "connecting") }
            try { runCatching {
                val endpoint = ws.endpoint(chain)
                val pin = ws.pinnedFingerprint(endpoint)
                var checked = 0
                // A SILENT gap-walk (no "scanning" flicker): unlike a plain balance refresh it
                // re-derives the branches, so a payment to a freshly handed-out receive address,
                // and the change address a spend just created, are DISCOVERED while the app is
                // open — the reason a restart used to be needed. Confirmations refresh too.
                var doneRows: List<AddressRow>? = null
                var doneTxs: List<TxConf> = emptyList()
                var tip = cs.height
                // Quick unless asked for, or a day has gone by since every address was looked at.
                val quick = !full && !ws.fullLookDue(chain)
                val started = System.currentTimeMillis()
                var lastShown = 0L
                scanner.scan(xpub, chain, endpoint, pin, ws.scriptType, ws.gapLimit, extraAccount(), quick).collect { ev ->
                    when (ev) {
                        is ScanEvent.Connected ->
                            if (pin == null && ev.fingerprint != null) ws.pinFingerprint(endpoint, ev.fingerprint)
                        is ScanEvent.Done -> { doneRows = ev.rows; doneTxs = ev.txs; tip = ev.height }
                        // Progress, on one line under "scan complete": the balance stays as it is meanwhile.
                        // The progress line at most a few times a second: redrawing the screen for
                        // every address made the look wait on the screen.
                        is ScanEvent.Deriving -> { checked++; val now = System.currentTimeMillis()
                            if (now - lastShown > 300) { lastShown = now; updateIf(xpub, chain) {
                                it.copy(refreshing = "${if (ev.chainIndex == 0) "receive" else "change"} ${ev.path} · $checked checked")
                            } } }
                        is ScanEvent.Failed -> updateIf(xpub, chain) { it.copy(refreshing = null) }
                        // Shown even on a silent refresh: the user has to decide about it.
                        is ScanEvent.CertificateChanged -> updateIf(xpub, chain) {
                            it.copy(fingerprint = ev.fingerprint, fingerprintChanged = true, phase = ScanPhase.Error(ev.message))
                        }
                        else -> {}
                    }
                }
                val rows = doneRows ?: return@runCatching
                android.util.Log.i("Kilowallet", "look ${chain.name} ${if (quick) "quick" else "full"}: ${rows.size} used, ${System.currentTimeMillis() - started} ms")
                if (!quick) ws.setLastFullLook(chain)
                if (_state.value.xpub == xpub) noteUsed(rows)
                val conf = rows.sumOf { it.confirmed }
                val unconf = rows.sumOf { it.unconfirmed }
                ws.setLastBalance(chain, conf, unconf)
                if (ws.notificationsEnabled) {
                    BalanceWatch.evaluate(ws, nf, chain, conf, unconf, doneTxs)
                }
                updateIf(xpub, chain) {
                    it.copy(rows = rows, transactions = doneTxs, height = tip, phase = ScanPhase.Complete, stale = false)
                }
                saveSnapshot(chain)
            } } finally { updateIf(xpub, chain) { it.copy(refreshing = null) } }
        }
    }

    /** Settings → "check every address again": a full look now, spent addresses included. */
    fun fullLook() {
        scannableChains().forEach { chain ->
            refreshJobs[chain]?.cancel()
            refresh(chain, full = true)
        }
    }

    /** Whether a background refresh of [chain] is running (the pull-down spinner waits for it). */
    fun isRefreshing(chain: Chain): Boolean = refreshJobs[chain]?.isActive == true

    /** Validate and store a pasted extended public key, then scan both chains. */
    fun setXpub(raw: String, label: String, picked: ScriptType? = null) {
        val trimmed = raw.trim().replace("\\s".toRegex(), "")
        val type = Scanner.scriptTypeOf(trimmed)
        if (type == null) {
            // Re-derive the real reason so the user sees something actionable.
            val why = runCatching { com.kilombino.pyblockwatch.crypto.Bip32.parseExtendedPubKey(trimmed) }
                .exceptionOrNull()?.message ?: "Not recognised as an xpub, ypub or zpub."
            _state.update { it.copy(inputError = why) }
            return
        }
        // An xpub is always the watch-only wallet, next to (not instead of) a hot one.
        // Stop whatever the other wallet was scanning, or its results land on this one.
        jobs.values.forEach(Job::cancel); jobs.clear()
        store = Store(getApplication(), Store.WATCH)
        store.clearWallet() // a new watch-only wallet starts with no notification baseline
        store.activeWallet = Store.WATCH
        // Watch-only never asks simple/advanced: it starts simple.
        if (store.uiMode == null) store.uiMode = "simple"
        store.xpub = trimmed
        store.label = label
        // Default derivation: honour a specific prefix (ypub → nested, zpub → native),
        // but a plain xpub defaults to BIP-84 native segwit rather than legacy. The user
        // can still switch it afterwards with the type selector.
        val chosen = picked ?: when (type) {
            ScriptType.P2SH_P2WPKH, ScriptType.P2WPKH -> type
            else -> ScriptType.P2WPKH
        }
        store.scriptType = chosen
        store.typeChosen = picked != null
        _state.update {
            it.copy(
                xpub = trimmed, label = label, scriptType = chosen, inputError = null,
                taprootXpub = null, receiveTaproot = false,
                isHot = false, hasSeed = seedVault.hasSeed(), setupMode = false, uiMode = store.uiMode,
                chains = Chain.entries.associateWith { ChainState() },
            )
        }
        scannableChains().forEach { scan(it) }
    }

    /** Change the gap limit (how deep the scan looks) and re-scan both chains. */
    fun setGapLimit(limit: Int) {
        val clamped = limit.coerceIn(5, 100)
        if (clamped == store.gapLimit) return
        store.gapLimit = clamped
        _state.update {
            it.copy(gapLimit = clamped, chains = Chain.entries.associateWith { ChainState() })
        }
        if (!_state.value.xpub.isNullOrBlank()) scannableChains().forEach { scan(it) }
    }

    /** Change the address type (BIP-84/49/44/86) and re-scan both chains. */
    fun setScriptType(type: ScriptType) {
        // Picking it, even the one already shown, says it is the watch-only wallet's own type.
        if (!_state.value.isHot) store.typeChosen = true
        if (type == store.scriptType) return
        // The hot wallet's xpub is m/84'/0'/0': any other type would show addresses it can't sign for.
        if (_state.value.isHot && type != ScriptType.P2WPKH) return
        store.scriptType = type
        _state.update {
            it.copy(scriptType = type, chains = Chain.entries.associateWith { ChainState() })
        }
        if (!_state.value.xpub.isNullOrBlank()) scannableChains().forEach { scan(it) }
    }

    /**
     * Hot wallet: hand out Taproot (bc1p, BIP-86) or native SegWit (bc1q, BIP-84) addresses for
     * new payments and change. Both accounts are scanned and spent from once Taproot exists, so
     * switching back and forth never hides a coin. A wallet made before Taproot has no BIP-86
     * account yet: [decryptCipher] (authorised) is then needed once to derive it from the seed.
     */
    fun setReceiveTaproot(on: Boolean, decryptCipher: javax.crypto.Cipher? = null, onError: (String) -> Unit = {}) {
        if (!_state.value.isHot) return
        viewModelScope.launch {
            runCatching {
                val known = store.taprootXpub
                if (on && known == null) {
                    val cipher = decryptCipher ?: error("Unlock the wallet to add its Taproot addresses.")
                    val tpub = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        val secret = seedVault.revealSecret(cipher)
                        val master = com.kilombino.pyblockwatch.crypto.Bip32Priv
                            .fromSeed(com.kilombino.pyblockwatch.crypto.Bip39.toSeed(secret.words, secret.passphrase))
                        com.kilombino.pyblockwatch.crypto.Bip32Priv.accountXpub(master, purpose = 86, account = 0)
                    }
                    store.taprootXpub = tpub
                }
                store.receiveTaproot = on
                val firstTime = known == null && on
                _state.update {
                    it.copy(taprootXpub = store.taprootXpub, receiveTaproot = on,
                        chains = if (firstTime) Chain.entries.associateWith { ChainState() } else it.chains)
                }
                // Its Taproot account is new to the scan: look for coins on it straight away.
                if (firstTime) scannableChains().forEach { scan(it) }
            }.onFailure { onError(it.message ?: "Could not turn Taproot on.") }
        }
    }

    /** Whether turning Taproot on needs the seed (the BIP-86 account was never derived). */
    fun taprootNeedsUnlock(): Boolean = _state.value.isHot && store.taprootXpub == null

    fun clearError() = _state.update { it.copy(inputError = null) }

    /** Open the wallet chooser (dice / restore / watch-only) even when a wallet already exists. */
    /** Remember which home screen to show. */
    fun setUiMode(mode: String) {
        if (store.activeWallet != Store.WATCH) store.hotModeChosen = true
        store.uiMode = mode
        _state.update { it.copy(uiMode = mode) }
    }

    fun setFiat(fiat: String) {
        store.fiat = fiat
        _state.update { it.copy(fiat = fiat) }
        // The home-screen widget shows the same currency.
        runCatching { com.kilombino.pyblockwatch.widget.XbtWidget.renderAll(getApplication()) }
    }

    /** Price and mining figures; [force] is the user tapping refresh (still rate-limited). */
    fun refreshMarket(force: Boolean = false) {
        val app = getApplication<Application>()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val m = com.kilombino.pyblockwatch.data.MarketFeed.refresh(app, force)
            if (m != null) _state.update { it.copy(market = m) }
            com.kilombino.pyblockwatch.widget.XbtWidget.renderAll(app)
        }
    }

    fun startSetup() = _state.update { it.copy(setupMode = true, inputError = null) }
    /** Leave the chooser without changing anything, back to the existing wallet. */
    fun endSetup() = _state.update { it.copy(setupMode = false) }

    /** The chains the app may contact on its own: BTC, and the spamchain once accepted. */
    /** The user has accepted talking to SHA-256 (spamchain) servers. */
    val spamchainConsent: Boolean get() = store.spamchainAccepted

    private fun scannableChains() = Chain.entries.filter { it != Chain.SHA256 || store.spamchainAccepted }

    /** The user accepted the spamchain warning: remember it and look at that chain now. */
    fun acceptSpamchain() {
        store.spamchainAccepted = true
        select(Chain.SHA256)
    }

    fun select(chain: Chain) {
        val changed = chain != _state.value.selected
        store.lastChain = chain
        _state.update { it.copy(selected = chain) }
        if (changed) resetSend() // a real switch must not leave a stale review draft or coins on screen
        refresh(chain)          // fresh figures the moment you switch to a chain
        startRefreshLoop()      // restart the timer so it tracks the newly selected chain
    }

    /**
     * Forget the wallet on screen. The hot one takes its seed with it; the other wallet, if
     * there is one, then comes on screen.
     */
    fun forget() {
        jobs.values.forEach(Job::cancel); jobs.clear()
        clearSnapshots()
        val wasWatch = store.activeWallet == Store.WATCH
        store.clearWallet()
        if (!wasWatch) { seedVault.clear(); wipeWalletBound() }
        val other = if (wasWatch) Store.HOT else Store.WATCH
        if (Store(getApplication(), other).xpub != null) { switchTo(other); return }
        store = Store(getApplication(), Store.HOT); store.activeWallet = Store.HOT
        _state.value = UiState(notificationsEnabled = store.notificationsEnabled)
    }

    /**
     * The Lightning node and the Ark wallet belong to the spending wallet's words: when the wallet
     * is forgotten or replaced they go too, so restoring words later asks before turning either
     * on, and a node of other words never stays behind looking like this wallet's.
     */
    fun wipeWalletBound() {
        val ctx = getApplication<Application>()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { com.kilombino.pyblockwatch.ln.Lightning.wipe(ctx) }
            runCatching { if (com.kilombino.pyblockwatch.ark.Ark.available) com.kilombino.pyblockwatch.ark.Ark.wipe(ctx) }
        }
    }

    /**
     * Why the spending wallet can't be forgotten or replaced yet, or null: its Lightning node
     * must be running and checked, with no channel open or closing (a deleted node can't get
     * those back by itself), and a node of other words must have sent its coins away first.
     */
    fun walletBoundBlock(): String? {
        val ctx = getApplication<Application>()
        val ln = com.kilombino.pyblockwatch.ln.Lightning
        if (!ln.enabled(ctx) && !com.kilombino.pyblockwatch.ln.Lnd.dir(ctx).exists()) return null
        val st = ln.status.value as? com.kilombino.pyblockwatch.ln.Lightning.Status.Running
            ?: return "Your Lightning node is not running. Open the Lightning tab and wait until it runs, so it can be checked first."
        if (st.channels.isNotEmpty() || st.pending.isNotEmpty() || st.closing.isNotEmpty())
            return "Your Lightning node still has channels open, opening or closing. Close them and wait until their coins are back on-chain in the node: a deleted node can't get them back by itself."
        if (!st.info.syncedChain) return "Wait until the Lightning node has finished checking its coins."
        val onchain = (st.balances?.onchainConfirmed ?: 0) + (st.balances?.onchainUnconfirmed ?: 0)
        val expected = com.kilombino.pyblockwatch.ln.LnSeed.expectedPubkey(ctx)
        if (onchain > 0 && expected != null && !expected.equals(st.info.pubkey, ignoreCase = true))
            return "Your Lightning node is from other recovery words and holds ${groupSats(onchain)} sats on-chain that this wallet's words won't bring back. Send them to this wallet first (Lightning → PAY → on-chain)."
        return null
    }

    fun endpointFor(chain: Chain): NodeEndpoint = store.endpoint(chain)

    /** True once the user saved their own server for [chain] (here or in settings). */
    fun hasOwnNode(chain: Chain): Boolean = store.endpoint(chain).isCustom

    /** The "one person, one node" reminder: once when the app opens, then on manual refreshes. */
    var nodeReminderShown = false

    /**
     * Whether the user agreed, since the app opened, to read BTC from Kilombino's server.
     * Until they do (or save their own node) the app does not contact it at all. The
     * background watcher is separate and keeps notifying as before.
     */
    @Volatile private var btcConsent = false

    private fun mayContact(chain: Chain): Boolean = chain != Chain.BLAKE2B || btcConsent || hasOwnNode(Chain.BLAKE2B)

    /** "Use Kilombino's server for now": allowed until the app is closed; scans right away. */
    fun consentBtc() {
        if (btcConsent) return
        btcConsent = true
        look(Chain.BLAKE2B)
    }

    fun explorerFor(chain: Chain): String = store.explorer(chain)
    fun defaultExplorerFor(chain: Chain): String = store.defaultExplorer(chain)
    fun setExplorer(chain: Chain, url: String?) = store.setExplorer(chain, url)
    fun explorerChosen(chain: Chain): Boolean = store.explorerChosen(chain)

    // ------------------------------------------------------------------ your own node by RPC

    fun rpcConns(): List<com.kilombino.pyblockwatch.chain.RpcConn> = store.rpcConns
    fun useRpc(): Boolean = store.useRpc

    fun setRpcConns(list: List<com.kilombino.pyblockwatch.chain.RpcConn>) {
        store.rpcConns = list
        if (list.isEmpty()) store.useRpc = false
        if (store.useRpc) scan(Chain.BLAKE2B)
    }

    /** Read BTC from the node by RPC (on) or an Electrum server (off); rescans. */
    fun setUseRpc(on: Boolean) {
        store.useRpc = on && store.rpcConns.isNotEmpty()
        scan(Chain.BLAKE2B)
    }

    /** What is at [c]: node version, height and whether it is on BLAKE2b, or why it failed. */
    /** The certificate an https node shows, without logging in (null, with the reason, if it can't be reached). */
    suspend fun probeRpcCertificate(c: com.kilombino.pyblockwatch.chain.RpcConn): Result<String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.kilombino.pyblockwatch.chain.NodeRpcBackend.probeCertificate(c) }
        }

    suspend fun testRpc(c: com.kilombino.pyblockwatch.chain.RpcConn): Pair<String, String?> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.kilombino.pyblockwatch.chain.NodeRpcBackend.test(c) }
                .getOrElse { ("✗ " + (it.message ?: it.javaClass.simpleName)) to null }
        }

    fun setCustomNode(chain: Chain, host: String?, port: Int) {
        store.setCustomEndpoint(chain, host, port)
        scan(chain)
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        store.notificationsEnabled = enabled
        _state.update { it.copy(notificationsEnabled = enabled) }
    }

    /** How often to refresh while the app is open; 0 = only by hand. */
    fun setRefreshOpen(seconds: Int) {
        store.refreshOpenSeconds = seconds
        _state.update { it.copy(refreshOpenSeconds = seconds) }
        startRefreshLoop()
    }

    /** How often the background watcher looks; 0 = off (the screen stops or restarts it). */
    fun setRefreshBackground(minutes: Int) {
        store.refreshBackgroundMinutes = minutes
        _state.update { it.copy(refreshBackgroundMinutes = minutes) }
    }

    /** Accept a changed certificate — only ever from an explicit user action. */
    fun trustCurrentCertificate(chain: Chain) {
        val st = _state.value.chains[chain] ?: return
        val ep = st.endpoint ?: return
        val fp = st.fingerprint ?: return
        store.pinFingerprint(ep, fp)
        _state.update { s ->
            s.copy(chains = s.chains + (chain to st.copy(fingerprintChanged = false)))
        }
        scan(chain)
    }

    fun scan(chain: Chain) {
        // This wallet's store and notifier, fixed now: a switch to the other wallet while this
        // runs must not file its figures (or its notifications) under the other one.
        val ws = store; val nf = notifier
        if (!mayContact(chain)) return
        val xpub = _state.value.xpub ?: return
        jobs[chain]?.cancel()
        val endpoint = ws.endpoint(chain)
        val pin = ws.pinnedFingerprint(endpoint)

        jobs[chain] = viewModelScope.launch {
            val found = mutableListOf<AddressRow>()
            // Progress (each address derived, checked or found) is shown at most every 300 ms,
            // as refresh does: one state change per address redrew the whole screen hundreds
            // of times in a scan, and copied the growing list each time.
            var progress: ScanPhase.Scanning? = null
            var lastShown = 0L
            scanner.scan(xpub, chain, endpoint, pin, ws.scriptType, ws.gapLimit, extraAccount()).collect { ev ->
                when (ev) {
                    is ScanEvent.Deriving -> progress = ScanPhase.Scanning(ev.path, ev.chainIndex, 0, 20)
                    is ScanEvent.GapProgress -> progress = progress?.copy(gapUsed = ev.consecutiveEmpty, gapLimit = ev.gapLimit)
                    is ScanEvent.Found -> found += ev.row
                    else -> {}
                }
                if (ev is ScanEvent.Deriving || ev is ScanEvent.GapProgress || ev is ScanEvent.Found) {
                    val now = System.currentTimeMillis()
                    if (now - lastShown < 300) return@collect
                    lastShown = now
                    val p = progress
                    updateIf(xpub, chain) { st -> st.copy(phase = p ?: st.phase, rows = found.toList()) }
                    return@collect
                }
                updateIf(xpub, chain) { st ->
                    when (ev) {
                        is ScanEvent.Connecting ->
                            st.copy(phase = ScanPhase.Connecting(ev.endpoint), endpoint = ev.endpoint,
                                    rows = emptyList())
                        is ScanEvent.Connected -> {
                            // First sight of this server's certificate: pin it silently.
                            // A CHANGE is never auto-accepted — the UI asks.
                            if (pin == null && ev.fingerprint != null) ws.pinFingerprint(endpoint, ev.fingerprint)
                            st.copy(server = ev.server, height = ev.height, fingerprint = ev.fingerprint,
                                    fingerprintChanged = ev.fingerprintChanged)
                        }
                        is ScanEvent.Deriving, is ScanEvent.GapProgress, is ScanEvent.Found -> st
                        is ScanEvent.Done -> {
                            noteUsed(ev.rows)
                            // Reset the notification baseline to what the user is now looking at,
                            // so the watcher only fires on genuinely new movement.
                            ws.setLastBalance(
                                chain,
                                ev.rows.sumOf { it.confirmed },
                                ev.rows.sumOf { it.unconfirmed },
                            )
                            st.copy(
                                phase = ScanPhase.Complete, rows = ev.rows,
                                height = ev.height, transactions = ev.txs, stale = false,
                            )
                        }
                        is ScanEvent.Failed -> st.copy(phase = ScanPhase.Error(ev.message))
                        is ScanEvent.CertificateChanged -> st.copy(phase = ScanPhase.Error(ev.message),
                            fingerprint = ev.fingerprint, fingerprintChanged = true)
                    }
                }
                if (ev is ScanEvent.Done) { ws.setLastFullLook(chain); saveSnapshot(chain) }
            }
        }
    }

    // ------------------------------------------------------------------ remembered looks

    /**
     * Open on what this phone saw last time, then check: the remembered rows and movements are
     * shown at once (marked as not yet checked), and a silent look ([refresh]) walks every
     * address again behind them, so nothing is ever skipped. Without a snapshot, a normal scan.
     */
    private fun look(chain: Chain, snap: Snapshot? = readSnapshot(chain)) {
        val cs = _state.value.chains[chain]
        val shown = cs != null && cs.rows.isNotEmpty() && cs.phase is ScanPhase.Complete
        if (shown || restoreSnapshot(chain, snap)) refresh(chain) else scan(chain)
    }

    /** One file per wallet, chain and account set: another xpub or address type never reads it. */
    private fun snapshotFile(chain: Chain): java.io.File? {
        val xpub = _state.value.xpub ?: return null
        val id = com.kilombino.pyblockwatch.crypto.Hashes.sha256(
            "$xpub|${_state.value.scriptType}|${extraAccount()?.xpub ?: ""}|${chain.name}".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        return java.io.File(java.io.File(getApplication<Application>().noBackupFilesDir, "snapshots"), id)
    }

    private fun saveSnapshot(chain: Chain) {
        val f = snapshotFile(chain) ?: return
        val cs = _state.value.chains[chain] ?: return
        if (cs.phase !is ScanPhase.Complete || cs.stale) return
        runCatching {
            val rows = org.json.JSONArray()
            cs.rows.forEach { r ->
                rows.put(org.json.JSONObject().put("c", r.chainIndex).put("i", r.index).put("a", r.address).put("p", r.path)
                    .put("s", r.scriptHash).put("cf", r.confirmed).put("u", r.unconfirmed).put("n", r.txCount)
                    .put("t", r.type?.name ?: ""))
            }
            val txs = org.json.JSONArray()
            cs.transactions.forEach { t ->
                txs.put(org.json.JSONObject().put("id", t.txid).put("cf", t.confirmations).put("p", t.pending)
                    .put("a", t.amount ?: org.json.JSONObject.NULL).put("v", t.spv ?: org.json.JSONObject.NULL))
            }
            f.parentFile?.mkdirs()
            val tmp = java.io.File(f.path + ".tmp")
            tmp.writeText(org.json.JSONObject().put("rows", rows).put("txs", txs).put("h", cs.height).toString())
            tmp.renameTo(f)
        }.onFailure { android.util.Log.w("Kilowallet", "snapshot not saved: $it") }
    }

    /** What [saveSnapshot] wrote, read back (a file and its JSON: done off the main thread at start). */
    private class Snapshot(val xpub: String, val rows: List<AddressRow>, val txs: List<TxConf>, val height: Int)

    private fun readSnapshot(chain: Chain): Snapshot? {
        val f = snapshotFile(chain)?.takeIf { it.exists() } ?: return null
        val xpub = _state.value.xpub ?: return null
        return runCatching {
            val o = org.json.JSONObject(f.readText())
            val ra = o.getJSONArray("rows"); val ta = o.getJSONArray("txs")
            val rows = (0 until ra.length()).map { val r = ra.getJSONObject(it)
                AddressRow(r.getInt("c"), r.getInt("i"), r.getString("a"), r.getString("p"), r.getString("s"),
                    r.getLong("cf"), r.getLong("u"), r.getInt("n"), r.optString("t").takeIf { t -> t.isNotEmpty() }?.let { t -> ScriptType.valueOf(t) })
            }
            val txs = (0 until ta.length()).map { val t = ta.getJSONObject(it)
                TxConf(t.getString("id"), t.getInt("cf"), t.getBoolean("p"),
                    if (t.isNull("a")) null else t.getLong("a"), if (t.isNull("v")) null else t.getBoolean("v"))
            }
            Snapshot(xpub, rows, txs, o.optInt("h")).takeIf { rows.isNotEmpty() }
        }.getOrNull()
    }

    private fun restoreSnapshot(chain: Chain, snap: Snapshot?): Boolean {
        if (snap == null || snap.xpub != _state.value.xpub) return false
        updateIf(snap.xpub, chain) { it.copy(phase = ScanPhase.Complete, rows = snap.rows, transactions = snap.txs, height = snap.height, stale = true) }
        return true
    }

    /** Forget what this phone remembers about past looks (a wallet is forgotten). */
    private fun clearSnapshots() {
        runCatching { java.io.File(getApplication<Application>().noBackupFilesDir, "snapshots").listFiles()?.forEach { it.delete() } }
        Scanner.clearDisk()
    }

    // ------------------------------------------------------------------ hot wallet: create

    /**
     * Turn a freshly generated (or restored) mnemonic into a wallet: persist it encrypted
     * behind the just-authorised [encryptCipher], derive the account zpub, and hand that to
     * the same scanner a watch-only xpub uses. Native SegWit (BIP-84) by default.
     */
    fun createHotWallet(
        mnemonic: List<String>, passphrase: String, encryptCipher: javax.crypto.Cipher,
        onDone: () -> Unit = {}, onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching {
                seedVault.store(encryptCipher, mnemonic, passphrase)
                // Remember whether this spending wallet and the Ark wallet share their words.
                runCatching {
                    val ark = com.kilombino.pyblockwatch.ark.Ark
                    val ctx = getApplication<Application>()
                    if (ark.hasWords(ctx)) {
                        ark.setWordsShared(ctx, ark.words(ctx) == mnemonic && ark.passphrase(ctx) == passphrase)
                        ark.setWordsDerived(ctx, ark.passphrase(ctx).isEmpty() && ark.words(ctx) == ark.derivedWords(mnemonic, passphrase))
                    }
                }
                // The BIP-84 account, and the BIP-86 (Taproot) one so it can be turned on without the seed.
                val (zpub, tpub) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val seed = com.kilombino.pyblockwatch.crypto.Bip39.toSeed(mnemonic, passphrase)
                    // The pubkey a Lightning node from these words has, to tell a node of other words apart.
                    runCatching { com.kilombino.pyblockwatch.ln.LnSeed.rememberExpected(getApplication(), seed) }
                    val master = com.kilombino.pyblockwatch.crypto.Bip32Priv.fromSeed(seed)
                    com.kilombino.pyblockwatch.crypto.Bip32Priv.accountXpub(master, purpose = 84, account = 0) to
                        com.kilombino.pyblockwatch.crypto.Bip32Priv.accountXpub(master, purpose = 86, account = 0)
                }
                store = Store(getApplication(), Store.HOT)
                store.activeWallet = Store.HOT
                if (!store.hotModeChosen) store.uiMode = null
                store.xpub = zpub
                store.label = "Hot wallet"
                store.scriptType = ScriptType.P2WPKH
                store.taprootXpub = tpub
                _state.update {
                    it.copy(
                        xpub = zpub, label = "Hot wallet", scriptType = ScriptType.P2WPKH,
                        taprootXpub = tpub, receiveTaproot = store.receiveTaproot,
                        isHot = true, hasSeed = true, inputError = null, setupMode = false, uiMode = store.uiMode,
                        chains = Chain.entries.associateWith { ChainState() },
                    )
                }
                // A wallet restored from a backup file opens on what it remembered (if anything).
                scannableChains().forEach { look(it) }
                onDone()
            }.onFailure { e ->
                seedVault.clear()
                onError(e.message ?: "Could not create the wallet.")
            }
        }
    }

    // ------------------------------------------------------------------ optional features

    /** A separate watch-only wallet (an xpub) is set up on this phone. */
    fun hasWatchWallet(): Boolean = Store(getApplication(), Store.WATCH).xpub != null
    fun watchWalletLabel(): String = Store(getApplication(), Store.WATCH).label

    /** Show the watch-only wallet (it must exist: see [hasWatchWallet]). */
    fun viewAsWatchOnly() {
        if (!hasWatchWallet()) return
        switchTo(Store.WATCH)
    }

    /** Back to the hot wallet: call only after the fingerprint check passed. */
    fun viewAsHot() {
        if (!seedVault.hasSeed()) return
        switchTo(Store.HOT)
    }

    /** A JoinMarket wallet (Jam's read-only export) is imported on this phone. */
    fun hasJamWallet(): Boolean = Store(getApplication(), Store.JM).jmExport != null

    /** Show the JoinMarket wallet (import it first: see [hasJamWallet]). */
    fun viewJam() {
        if (!hasJamWallet()) return
        store.jamView = true
        _state.update { it.copy(jamView = true, sendPhase = SendPhase.Editing) }
    }

    /** The JoinMarket wallet was forgotten: back to the hot or watch-only one. */
    fun leaveJam() {
        store.jamView = false
        _state.update { it.copy(jamView = false) }
    }

    /** The user agreed, since the app opened, to read BTC from Kilombino's server. */
    fun btcConsented(): Boolean = btcConsent

    private fun switchTo(wallet: String) {
        store.jamView = false
        _state.update { it.copy(jamView = false) }
        jobs.values.forEach(Job::cancel); jobs.clear()
        store = Store(getApplication(), wallet)
        store.activeWallet = wallet
        // First time on the hot wallet: ask simple or advanced.
        if (wallet == Store.HOT && !store.hotModeChosen) store.uiMode = null
        _state.update { it.copy(sendPhase = SendPhase.Editing, utxos = null, utxosChain = null) }
        reloadFromStore()
    }

    /** Remove only the watch-only wallet; the hot one (if any) stays. */
    fun forgetWatchWallet() {
        Store(getApplication(), Store.WATCH).clearWallet()
        if (store.activeWallet == Store.WATCH) { if (seedVault.hasSeed()) switchTo(Store.HOT) else forget() }
    }

    fun coinjoinEnabled(): Boolean = store.coinjoinEnabled
    fun coinjoinNotify(): Boolean = store.coinjoinNotify
    fun coinjoinAsked(): Boolean = store.coinjoinAsked

    /**
     * The answer to the coinjoin explainer: yes turns the tab and pool notifications on; with
     * [notify] false the tab opens without those notifications.
     */
    fun answerCoinjoin(accept: Boolean, notify: Boolean = accept) {
        store.coinjoinAsked = true
        store.coinjoinEnabled = accept
        setCoinjoinNotify(accept && notify)
    }

    fun setCoinjoinNotify(on: Boolean) {
        store.coinjoinNotify = on
        if (on) viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.notifyOpenNow(getApplication()) }
        }
    }

    fun disableCoinjoin() { store.coinjoinEnabled = false; store.coinjoinNotify = false }

    // ------------------------------------------------------------------ full backup file

    /**
     * The whole wallet as a backup file: words, settings, coinjoin rounds, contacts and,
     * when Ark is active, the Ark wallet (taken from the engine with its own copy of the words).
     */
    fun fullSnapshot(words: List<String>, passphrase: String): com.kilombino.pyblockwatch.ark.ArkBackup.Snapshot {
        val ctx = getApplication<Application>()
        val ark = com.kilombino.pyblockwatch.ark.Ark
        if (ark.available && ark.hasWords(ctx) && java.io.File(ctx.filesDir, "ark/db.sqlite").exists()) {
            val s = ark.snapshot(ctx)
            // Ark may have its own words; the spending wallet's are what the file restores first.
            if (s.words == words && s.passphrase == passphrase) return s
            // Ark's words made from these (1.1.4 on): the file keeps the wallet's words and says so.
            if (s.passphrase.isEmpty() && s.words == ark.derivedWords(words, passphrase))
                return com.kilombino.pyblockwatch.ark.ArkBackup.Snapshot(words, passphrase, s.config, s.db, s.dbWal, s.movements,
                    s.created, s.contacts, s.app, arkDerived = true)
        }
        return com.kilombino.pyblockwatch.ark.ArkBackup.Snapshot(
            words = words, passphrase = passphrase, config = null, db = null, dbWal = null,
            movements = 0, created = System.currentTimeMillis(),
            contacts = com.kilombino.pyblockwatch.data.Contacts.export(ctx),
            app = com.kilombino.pyblockwatch.data.AppBackup.export(ctx),
        )
    }

    /**
     * Restores everything in [s]: the spending wallet from its words (stored with the
     * just-authorised [encryptCipher]), then the settings and coinjoin rounds, the contacts and
     * the Ark wallet. [onDone] gets a warning when something could not come back (Ark on a
     * phone that cannot run it).
     */
    fun restoreFull(
        s: com.kilombino.pyblockwatch.ark.ArkBackup.Snapshot, encryptCipher: javax.crypto.Cipher,
        onDone: (warning: String?) -> Unit, onError: (String) -> Unit,
    ) {
        val ctx = getApplication<Application>()
        createHotWallet(s.words, s.passphrase, encryptCipher, onError = onError, onDone = {
            viewModelScope.launch {
                runCatching {
                    var warning: String? = null
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        s.app?.let { com.kilombino.pyblockwatch.data.AppBackup.import(ctx, it) }
                        com.kilombino.pyblockwatch.data.Contacts.merge(ctx, s.contacts)
                        if (s.hasArk) {
                            val ark = com.kilombino.pyblockwatch.ark.Ark
                            if (ark.available) {
                                ark.restore(ctx, s)
                                ark.setWordsShared(ctx, !s.arkDerived); ark.setWordsDerived(ctx, s.arkDerived)
                                // It waits in the Ark tab until the user turns it on.
                                ark.setPaused(ctx, true)
                            }
                            else warning = "This phone cannot run Ark: the Ark part of the backup was not restored."
                        }
                    }
                    reloadFromStore()
                    warning
                }.onSuccess(onDone).onFailure { onError(it.message ?: "Could not restore the backup.") }
            }
        })
    }

    /** Re-read every setting after a restore and scan again. */
    private fun reloadFromStore() {
        _state.update {
            it.copy(
                xpub = store.xpub, label = store.label, scriptType = store.scriptType,
                notificationsEnabled = store.notificationsEnabled, gapLimit = store.gapLimit,
                refreshOpenSeconds = store.refreshOpenSeconds, refreshBackgroundMinutes = store.refreshBackgroundMinutes,
                isHot = seedVault.hasSeed() && store.activeWallet != Store.WATCH, hasSeed = seedVault.hasSeed(),
                uiMode = store.uiMode, fiat = store.fiat,
                taprootXpub = store.taprootXpub.takeIf { seedVault.hasSeed() && store.activeWallet != Store.WATCH },
                receiveTaproot = store.receiveTaproot,
                chains = Chain.entries.associateWith { ChainState() },
            )
        }
        if (store.xpub != null) scannableChains().forEach { look(it) }
    }

    // ------------------------------------------------------------------ sweep a private key

    private val _sweep = MutableStateFlow<SweepPhase>(SweepPhase.Idle)
    val sweep: StateFlow<SweepPhase> = _sweep.asStateFlow()

    fun resetSweep() { _sweep.value = SweepPhase.Idle }

    /**
     * Find every coin a WIF private key holds on the current chain, in each address form this
     * wallet can spend (native SegWit, Taproot, nested SegWit and legacy for a compressed key,
     * legacy for an uncompressed one), and draft one transaction moving them all to this wallet's next
     * receive address. Nothing is signed or sent until [confirmSweep].
     */
    fun prepareSweep(wif: String, feeRatePerVb: Double) {
        val chain = _state.value.selected
        _sweep.value = SweepPhase.Scanning
        viewModelScope.launch {
            runCatching {
                val key = com.kilombino.pyblockwatch.crypto.Wif.decode(wif)
                val (to, _) = receiveAddress(nextReceiveIndex()) ?: error("No wallet to sweep into.")
                val rate = feeRatePerVb.coerceIn(0.1, 1000.0)
                // [checked]: each coin's amount and script read from the transaction that made it
                // (fetched by txid, which the client checks), not taken from the server's list. A
                // legacy input's signature does not commit to its amount, so a server lying about
                // one could otherwise inflate the fee. Unconfirmed and immature mined coins, which
                // the network would refuse, are left out too.
                fun scanKey(c: Chain, types: List<com.kilombino.pyblockwatch.crypto.ScriptType>, checked: Boolean): List<SweepCoin> {
                    val endpoint = store.endpoint(c)
                    val client = com.kilombino.pyblockwatch.chain.ElectrumClient(endpoint, store.pinnedFingerprint(endpoint))
                    return try {
                        client.connect()
                        val listed = types.flatMap { t ->
                            val addr = com.kilombino.pyblockwatch.crypto.Address.encode(key.pubkey, t)
                            client.listUnspent(com.kilombino.pyblockwatch.crypto.Address.scriptHashFor(key.pubkey, t))
                                .map { SweepCoin(t, addr, it.txid, it.vout, it.value, it.height) }
                        }
                        if (!checked) return listed
                        val tip = client.blockHeight()
                        val spv = c == Chain.BLAKE2B && !client.isOwnNode
                        if (spv) requireNotNull(runCatching { com.kilombino.pyblockwatch.chain.HeaderChain.sync(client) }.getOrNull()) {
                            "The server's block headers don't verify, so its coins can't be proven. Nothing was signed; try another server."
                        }
                        val txs = HashMap<String, com.kilombino.pyblockwatch.crypto.TxParse.Tx>()
                        listed.filter { it.height > 0 }.filter { u ->
                            val tx = txs.getOrPut(u.txid) { com.kilombino.pyblockwatch.crypto.TxParse.parse(client.transaction(u.txid)) }
                            val out = tx.outputs.getOrNull(u.vout)
                                ?: error("The server listed a coin that does not exist (${u.txid.take(8)}…:${u.vout}). Nothing was signed.")
                            require(out.value == u.value &&
                                out.scriptPubKey.contentEquals(com.kilombino.pyblockwatch.crypto.Address.scriptPubKey(key.pubkey, u.type))) {
                                "The server's data about a coin (${u.txid.take(8)}…:${u.vout}) does not match the transaction itself. " +
                                    "Nothing was signed; try another server."
                            }
                            if (spv) require(com.kilombino.pyblockwatch.chain.HeaderChain.proves(client, u.txid, u.height, Scanner.SPV_FLOOR_BITS) == true) {
                                "A coin (${u.txid.take(8)}…:${u.vout}) could not be proven in its block. Nothing was signed; try another server."
                            }
                            val coinbase = tx.inputs.size == 1 && tx.inputs[0].txid.all { it == '0' } && tx.inputs[0].vout == -1
                            !coinbase || tip - u.height + 1 >= Scanner.maturityFor(u.height)
                        }
                    } finally { client.close() }
                }
                val coins = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    scanKey(chain, key.sweepableTypes, checked = true)
                }
                // The other chain is only asked about with consent: on the SHA-256 side that means
                // public servers learn this key's addresses.
                val other = Chain.entries.firstOrNull { it != chain }
                    ?.takeIf { it != Chain.SHA256 || store.spamchainAccepted }
                val otherSats = other?.let { c ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { scanKey(c, key.sweepableTypes, checked = false).sumOf { it.value } }.getOrNull()
                    }
                }
                require(coins.isNotEmpty()) {
                    "This key has no confirmed, spendable coins on this chain." +
                        (if ((otherSats ?: 0) > 0) " It has ${otherSats} sats on the other chain: switch chain and sweep again." else "")
                }
                val toScript = com.kilombino.pyblockwatch.crypto.Address.decodeToScriptPubKey(to)
                val total = coins.sumOf { it.value }
                // Size the fee from a real signature of the same shape, then rebuild at confirm.
                val probe = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    buildSweep(key, coins, toScript, total, chain)
                }
                val fee = kotlin.math.ceil(probe.vbytes * rate).toLong()
                require(total - fee > DUST_SATS) { "The coins on this key (${total} sats) do not cover the fee (${fee} sats)." }
                _sweep.value = SweepPhase.Review(SweepDraft(key, coins, to, fee, chain, otherSats))
            }.onFailure { e -> _sweep.value = SweepPhase.Failed(e.message ?: "Could not read that key.") }
        }
    }

    private fun buildSweep(
        key: com.kilombino.pyblockwatch.crypto.Wif.Key, coins: List<SweepCoin>, toScript: ByteArray,
        amount: Long, chain: Chain,
    ) = com.kilombino.pyblockwatch.crypto.TxBuilder.build(
        coins.map { c ->
            com.kilombino.pyblockwatch.crypto.TxBuilder.Input(
                c.txid, c.vout, c.value, key.privateKey, key.pubkey, 0xfffffffdL, c.type,
            )
        },
        listOf(com.kilombino.pyblockwatch.crypto.TxBuilder.Output(toScript, amount)),
        // Unified sighash on BLAKE2b, as for every spend there, so it cannot be replayed.
        unified = chain == Chain.BLAKE2B, grindLowR = true,
    )

    /** Sign the reviewed sweep with the key and broadcast it on the chain it was drafted for. */
    fun confirmSweep() {
        val draft = (_sweep.value as? SweepPhase.Review)?.draft ?: return
        _sweep.value = SweepPhase.Broadcasting
        viewModelScope.launch {
            runCatching {
                val toScript = com.kilombino.pyblockwatch.crypto.Address.decodeToScriptPubKey(draft.toAddress)
                val signed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    buildSweep(draft.key, draft.coins, toScript, draft.received, draft.chain)
                }
                val endpoint = store.endpoint(draft.chain)
                val txid = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    scanner.broadcast(signed.rawHex, endpoint, store.pinnedFingerprint(endpoint))
                }
                _sweep.value = SweepPhase.Sent(txid, draft.chain)
                scan(draft.chain)
            }.onFailure { e -> _sweep.value = SweepPhase.Failed(e.message ?: "The broadcast failed.") }
        }
    }

    // ------------------------------------------------------------------ rescue a pre-fork LND wallet

    private val _rescue = MutableStateFlow<RescuePhase>(RescuePhase.Idle)
    val rescue: StateFlow<RescuePhase> = _rescue.asStateFlow()

    fun resetRescue() { _rescue.value = RescuePhase.Idle }

    /** The wallet's next receive address, the default destination of a rescue. */
    fun defaultRescueAddress(): String? = receiveAddress(nextReceiveIndex())?.first

    private fun client(chain: Chain): com.kilombino.pyblockwatch.chain.ElectrumClient {
        val endpoint = store.endpoint(chain)
        return com.kilombino.pyblockwatch.chain.ElectrumClient(endpoint, store.pinnedFingerprint(endpoint))
    }

    /** Decipher the LND seed and look for its coins (BLAKE2b) and post-fork closes (SHA-256). */
    fun rescueScan(words: List<String>, passphrase: String, gap: Int, channelBackup: ByteArray? = null,
                   useSha256: Boolean = false) {
        _rescue.value = RescuePhase.Busy("deciphering the LND seed…")
        viewModelScope.launch {
            runCatching {
                val seed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.kilombino.pyblockwatch.crypto.Aezeed.decode(words, passphrase)
                }
                val channels = channelBackup?.let {
                    _rescue.value = RescuePhase.Busy("opening the channel.backup…")
                    com.kilombino.pyblockwatch.data.Scb.decode(it, com.kilombino.pyblockwatch.crypto.Bip32Priv.fromSeed(seed.entropy))
                } ?: emptyList()
                val r = com.kilombino.pyblockwatch.data.LndRescue(seed, channels)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val xbt = client(Chain.BLAKE2B)
                    val sha = client(Chain.SHA256)
                    try {
                        xbt.connect()
                        // Public SHA-256 servers see every LND address asked about: only with consent.
                        val shaOk = useSha256 && runCatching { sha.connect() }.isSuccess
                        r.scan(xbt, if (shaOk) sha else null, gap.coerceIn(10, 500)) { _rescue.value = RescuePhase.Busy(it) }
                    } finally { xbt.close(); sha.close() }
                }
            }.onSuccess { _rescue.value = RescuePhase.Review(it) }
             .onFailure { _rescue.value = RescuePhase.Failed(it.message ?: "Could not read that seed.") }
        }
    }

    private fun buildRescue(coins: List<com.kilombino.pyblockwatch.data.LndRescue.Coin>, toScript: ByteArray, amount: Long) =
        com.kilombino.pyblockwatch.crypto.TxBuilder.build(
            coins.map {
                com.kilombino.pyblockwatch.crypto.TxBuilder.Input(
                    it.txid, it.vout, it.value, it.key.privateKey, it.key.pubkey,
                    if (it.key.csv > 0) it.key.csv.toLong() else 0xfffffffdL, it.key.type,
                    witnessScript = it.key.witnessScript, witnessExtra = it.key.witnessExtra,
                )
            },
            listOf(com.kilombino.pyblockwatch.crypto.TxBuilder.Output(toScript, amount)),
            unified = true, grindLowR = true, // BLAKE2b only: never valid on the SHA-256 chain
        )

    /** Fee in sats for sweeping [coins] at [rate], from a real signature of the same shape. */
    fun rescueFee(coins: List<com.kilombino.pyblockwatch.data.LndRescue.Coin>, toAddress: String, rate: Double): Long? =
        runCatching {
            val probe = buildRescue(coins, com.kilombino.pyblockwatch.crypto.Address.decodeToScriptPubKey(toAddress), coins.sumOf { it.value })
            kotlin.math.ceil(probe.vbytes * rate.coerceIn(0.1, 1000.0)).toLong()
        }.getOrNull()

    /**
     * Replay the chosen closes on BLAKE2b, then sweep everything to [toAddress] in one
     * unified-sighash transaction. A replay the network refuses is reported and its outputs
     * are left out of the sweep.
     */
    fun rescueConfirm(toAddress: String, rate: Double, replay: Boolean) {
        val res = (_rescue.value as? RescuePhase.Review)?.result ?: return
        viewModelScope.launch {
            runCatching {
                val toScript = com.kilombino.pyblockwatch.crypto.Address.decodeToScriptPubKey(toAddress.trim())
                val ok = ArrayList<String>(); val errs = ArrayList<String>()
                // Only what can be spent now: delayed channel outputs wait for their CSV.
                val coins = ArrayList(res.spendable())
                val pending = ArrayList(res.coins.filter { it.waitBlocks(res.tip) > 0 })
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val xbt = client(Chain.BLAKE2B)
                    try {
                        xbt.connect()
                        if (replay) for (r in res.replays) {
                            _rescue.value = RescuePhase.Busy("replaying close ${r.txid.take(8)}… on BLAKE2b")
                            runCatching { xbt.broadcast(r.rawHex) }
                                .onSuccess {
                                    ok += r.txid
                                    coins += r.outputsToUs.filter { it.key.csv == 0 }
                                    pending += r.outputsToUs.filter { it.key.csv > 0 }
                                }
                                .onFailure { errs += "${r.txid.take(8)}…: ${it.message}" }
                        }
                        if (coins.isEmpty()) {
                            require(ok.isNotEmpty() || pending.isNotEmpty()) {
                                "Nothing to sweep." + if (errs.isNotEmpty()) " " + errs.joinToString("; ") else ""
                            }
                            _rescue.value = RescuePhase.Done(ok, errs, null, 0,
                                pending.sumOf { it.value }, pending.maxOfOrNull { it.waitBlocks(res.tip) } ?: 0)
                            return@withContext
                        }
                        _rescue.value = RescuePhase.Busy("signing and sending the sweep…")
                        val total = coins.sumOf { it.value }
                        val probe = buildRescue(coins, toScript, total)
                        val fee = kotlin.math.ceil(probe.vbytes * rate.coerceIn(0.1, 1000.0)).toLong()
                        require(total - fee > DUST_SATS) { "The coins found ($total sats) do not cover the fee ($fee sats)." }
                        val signed = buildRescue(coins, toScript, total - fee)
                        val txid = xbt.broadcast(signed.rawHex)
                        _rescue.value = RescuePhase.Done(ok, errs, txid, total - fee,
                            pending.sumOf { it.value }, pending.maxOfOrNull { it.waitBlocks(res.tip) } ?: 0)
                    } finally { xbt.close() }
                }
                scan(Chain.BLAKE2B)
            }.onFailure { _rescue.value = RescuePhase.Failed(it.message ?: "The rescue failed.") }
        }
    }

    /**
     * Check every coin about to be spent against the transaction that created it (fetched by its
     * txid, which the client checks is really that transaction): the amount and the address must
     * be what the server listed. A server that lied about a coin is caught here, before anything
     * is signed, instead of as a mysteriously invalid transaction.
     */
    private suspend fun verifyCoins(chain: Chain, coins: List<Scanner.SpendableUtxo>) {
        if (_state.value.xpub == null) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val c = client(chain)
            try {
                c.connect()
                // On BTC each confirmed coin must also be in a block of the verified header chain
                // (SPV): a coin a server made up has no such block.
                val spv = chain == Chain.BLAKE2B && !c.isOwnNode
                if (spv) requireNotNull(runCatching { com.kilombino.pyblockwatch.chain.HeaderChain.sync(c) }.getOrNull()) {
                    "The server's block headers don't verify, so its coins can't be proven. Nothing was signed; try another server."
                }
                val txs = HashMap<String, com.kilombino.pyblockwatch.crypto.TxParse.Tx>()
                for (u in coins) {
                    if (spv && u.height > 0) require(com.kilombino.pyblockwatch.chain.HeaderChain.proves(
                            c, u.txid, u.height, com.kilombino.pyblockwatch.data.Scanner.SPV_FLOOR_BITS) == true) {
                        "A coin (${u.txid.take(8)}…:${u.vout}) could not be proven in its block. Nothing was signed; try another server."
                    }
                    val tx = txs.getOrPut(u.txid) { com.kilombino.pyblockwatch.crypto.TxParse.parse(c.transaction(u.txid)) }
                    val out = tx.outputs.getOrNull(u.vout)
                        ?: error("The server listed a coin that does not exist (${u.txid.take(8)}…:${u.vout}). Nothing was signed.")
                    val script = coinScript(u)
                    require(out.value == u.value && out.scriptPubKey.contentEquals(script)) {
                        "The server's data about a coin (${u.txid.take(8)}…:${u.vout}) does not match the transaction itself. " +
                            "Nothing was signed; try another server."
                    }
                }
            } finally { c.close() }
        }
    }

    /**
     * Whether the reviewed fee looks like a slip: above 100 sat/vB, above 100 000 sats, or more
     * than 5% of what is sent. The review then asks for a second, explicit confirmation.
     */
    fun feeConcern(d: SendDraft): String? {
        val vbytes = estimateFee(d.inputs.size, d.outputs.map { it.scriptPubKey }, 1.0, taprootIn(d.inputs)).coerceAtLeast(1)
        val rate = d.fee.toDouble() / vbytes
        val sent = if (d.cpfpParent != null) d.amount + d.fee else d.totalSent
        val reasons = buildList {
            if (rate > 100) add("%.0f sat/vB".format(java.util.Locale.ROOT, rate))
            if (d.fee > 100_000) add("${"%,d".format(d.fee).replace(',', ' ')} sats")
            if (sent > 0 && d.fee * 100 > sent * 5) add("${d.fee * 100 / sent}% of the amount")
        }
        return if (reasons.isEmpty()) null else "This fee is high: " + reasons.joinToString(", ") + "."
    }

    // ------------------------------------------------------------------ hot wallet: speed up (CPFP)

    /**
     * Pull a stuck unconfirmed transaction that paid this wallet along (child pays for parent):
     * spend our outputs of it back to ourselves with a fee big enough that parent and child
     * together pay [ratePerVb]. Works for any transaction that pays us, including ones we did
     * not send (where RBF is impossible). Miners take the pair as one package.
     */
    fun prepareCpfp(txid: String, ratePerVb: Double) {
        val chain = _state.value.selected
        val cs = _state.value.chains[chain] ?: return
        if (_state.value.xpub == null) return
        _state.update { it.copy(sendPhase = SendPhase.Preparing) }
        viewModelScope.launch {
            runCatching {
                val rate = ratePerVb.coerceIn(1.0, 1000.0)
                val endpoint = store.endpoint(chain)
                val client = com.kilombino.pyblockwatch.chain.ElectrumClient(endpoint, store.pinnedFingerprint(endpoint))
                val draft = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        client.connect()
                        val raw = client.transaction(txid)
                        val tx = com.kilombino.pyblockwatch.crypto.TxParse.parse(raw)
                        val prevs = tx.inputs.map { i -> i to com.kilombino.pyblockwatch.crypto.TxParse.parse(client.transaction(i.txid)).outputs[i.vout] }
                        val parentFee = prevs.sumOf { it.second.value } - tx.outputs.sumOf { it.value }
                        // The txid covers the parent without its witness only: a server could pad the
                        // witness and make this child pay for bytes that aren't there. So the witness is
                        // counted from what each input's type needs (P2WPKH 108 WU, Taproot with the
                        // unified sighash 67), and the served size is used only if smaller.
                        val served = com.kilombino.pyblockwatch.crypto.TxParse.vsize(raw)
                        val witnessWu = prevs.map { (_, o) ->
                            val sc = o.scriptPubKey
                            when {
                                sc.size == 22 && sc[0] == 0.toByte() -> 108            // P2WPKH
                                sc.size == 34 && sc[0] == 0x51.toByte() -> 67          // P2TR key path + 0x21
                                sc.size == 23 && sc[0] == 0xa9.toByte() -> 108         // P2SH-P2WPKH (the usual case)
                                sc.size == 25 && sc[0] == 0x76.toByte() -> 0           // P2PKH: no witness
                                else -> -1                                              // can't tell
                            }
                        }
                        val parentVsize = if (witnessWu.any { it < 0 }) served else {
                            val base = com.kilombino.pyblockwatch.crypto.TxParse.baseSize(raw)
                            val wu = base * 4 + witnessWu.sum() + if (witnessWu.any { it > 0 }) 2 else 0
                            minOf(served, (wu + 3) / 4)
                        }
                        // The child pays for its parent only: if the parent spends coins that are
                        // themselves unconfirmed, miners weigh those too and the package rate shown
                        // would be too high. Speed up the oldest one first.
                        val unconfirmedAncestor = prevs.any { (i, o) ->
                            client.history(com.kilombino.pyblockwatch.crypto.Address.electrumScriptHash(o.scriptPubKey))
                                .firstOrNull { it.txid == i.txid }?.let { it.height <= 0 } ?: false
                        }
                        require(!unconfirmedAncestor) {
                            "This transaction spends coins that are not confirmed yet either: speed up the earlier one first."
                        }
                        val byHash = cs.rows.associateBy { it.scriptHash }
                        val unspent = cs.rows.filter { it.isUsed }.flatMap { r -> client.listUnspent(r.scriptHash) }
                            .map { "${it.txid}:${it.vout}" }.toSet()
                        val ours = tx.outputs.mapIndexedNotNull { n, o ->
                            val row = byHash[com.kilombino.pyblockwatch.crypto.Address.electrumScriptHash(o.scriptPubKey)] ?: return@mapIndexedNotNull null
                            if ("$txid:$n" !in unspent) return@mapIndexedNotNull null
                            Scanner.SpendableUtxo(txid, n, o.value, row.chainIndex, row.index, 0, row.type)
                        }
                        require(ours.isNotEmpty()) { "This transaction has no unspent output of this wallet to speed it up with." }
                        val to = changeScriptPubKey(nextChangeIndex(cs.rows))
                        val childVsize = estimateFee(ours.size, listOf(to), 1.0, taprootIn(ours))
                        val need = kotlin.math.ceil(rate * (parentVsize + childVsize)).toLong() - parentFee
                        require(need > childVsize) {
                            "It already pays ${"%.1f".format(java.util.Locale.ROOT, parentFee.toDouble() / parentVsize)} sat/vB: " +
                                "choose a higher rate than that."
                        }
                        val sum = ours.sumOf { it.value }
                        require(sum - need > DUST_SATS) { "Your part of it ($sum sats) can't cover a $need-sat fee." }
                        SendDraft(scriptToAddress(to), sum - need, need, 0, ours,
                            listOf(com.kilombino.pyblockwatch.crypto.TxBuilder.Output(to, sum - need)), chain,
                            cpfpParent = txid, packageRate = (parentFee + need).toDouble() / (parentVsize + childVsize))
                    } finally { client.close() }
                }
                _state.update { it.copy(sendPhase = SendPhase.Review(draft)) }
            }.onFailure { e ->
                _state.update { it.copy(sendPhase = SendPhase.Failed(e.message ?: "Could not prepare the speed-up.")) }
            }
        }
    }

    // ------------------------------------------------------------------ hot wallet: speed up (RBF)

    /**
     * Draft a replacement of unconfirmed send [txid] that pays [ratePerVb]: the same inputs and
     * recipients, the extra fee taken from the change. Full RBF is on by default in Knots and in
     * Bitcoin Core 28+, so even a send that did not signal it can be replaced. The replacement
     * must pay at least the old fee plus 1 sat/vB of its own size (and more if something already
     * spends it). The review and signing are the normal send's.
     */
    fun prepareBump(txid: String, ratePerVb: Double) {
        val chain = _state.value.selected
        val cs = _state.value.chains[chain] ?: return
        _state.update { it.copy(sendPhase = SendPhase.Preparing) }
        viewModelScope.launch {
            runCatching {
                val endpoint = store.endpoint(chain)
                val client = com.kilombino.pyblockwatch.chain.ElectrumClient(endpoint, store.pinnedFingerprint(endpoint))
                val draft = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        client.connect()
                        val tx = com.kilombino.pyblockwatch.crypto.TxParse.parse(client.transaction(txid))
                        val byHash = cs.rows.associateBy { it.scriptHash }
                        val inputs = tx.inputs.map { i ->
                            val prev = com.kilombino.pyblockwatch.crypto.TxParse.parse(client.transaction(i.txid)).outputs[i.vout]
                            val row = byHash[com.kilombino.pyblockwatch.crypto.Address.electrumScriptHash(prev.scriptPubKey)]
                                ?: error("This transaction spends coins that are not in this wallet: it can't be replaced from here.")
                            com.kilombino.pyblockwatch.data.Scanner.SpendableUtxo(i.txid, i.vout, prev.value, row.chainIndex, row.index, 0, row.type)
                        }
                        val outs = tx.outputs.map { com.kilombino.pyblockwatch.crypto.TxBuilder.Output(it.scriptPubKey, it.value) }
                        fun ours(o: com.kilombino.pyblockwatch.crypto.TxBuilder.Output) =
                            byHash[com.kilombino.pyblockwatch.crypto.Address.electrumScriptHash(o.scriptPubKey)]
                        val changeIdx = outs.indices.filter { ours(outs[it])?.chainIndex == 1 }.maxByOrNull { outs[it].value }
                            ?: error("This send has no change output to pay a higher fee from.")
                        val oldFee = inputs.sumOf { it.value } - outs.sumOf { it.value }
                        val newFee = maxOf(
                            estimateFee(inputs.size, outs.map { it.scriptPubKey }, ratePerVb.coerceIn(0.1, 1000.0), taprootIn(inputs)),
                            oldFee + estimateFee(inputs.size, outs.map { it.scriptPubKey }, 1.0, taprootIn(inputs)) + 1,
                        )
                        val change = outs[changeIdx].value - (newFee - oldFee)
                        require(change >= dustFor(outs[changeIdx].scriptPubKey)) {
                            "The change (${outs[changeIdx].value} sats) can't cover the new fee of $newFee sats."
                        }
                        val newOuts = outs.mapIndexed { n, o -> if (n == changeIdx) o.copy(value = change) else o }
                        val sent = newOuts.filterIndexed { n, _ -> n != changeIdx }
                        val to = sent.firstOrNull()?.let { runCatching { scriptToAddress(it.scriptPubKey) }.getOrNull() } ?: "(replacement)"
                        SendDraft(to, sent.sumOf { it.value }, newFee, change, inputs, newOuts, chain,
                            replaces = txid, replacedFee = oldFee,
                            changeAddress = runCatching { scriptToAddress(outs[changeIdx].scriptPubKey) }.getOrNull())
                    } finally { client.close() }
                }
                _state.update { it.copy(sendPhase = SendPhase.Review(draft)) }
            }.onFailure { e ->
                _state.update { it.copy(sendPhase = SendPhase.Failed(e.message ?: "Could not prepare the replacement.")) }
            }
        }
    }

    // ------------------------------------------------------------------ hot wallet: cancel (RBF)

    /**
     * Draft a cancellation of unconfirmed send [txid]: a replacement that spends the same coins
     * into one fresh address of this wallet (of the account they came from), so the recipients
     * get nothing. Like a speed-up it must pay the old fee plus 1 sat/vB of its own size, and at
     * least [ratePerVb]. If the original confirms first, the cancellation is simply refused.
     */
    fun prepareCancel(txid: String, ratePerVb: Double) {
        val chain = _state.value.selected
        val cs = _state.value.chains[chain] ?: return
        _state.update { it.copy(sendPhase = SendPhase.Preparing) }
        viewModelScope.launch {
            runCatching {
                val endpoint = store.endpoint(chain)
                val client = com.kilombino.pyblockwatch.chain.ElectrumClient(endpoint, store.pinnedFingerprint(endpoint))
                val draft = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        client.connect()
                        val tx = com.kilombino.pyblockwatch.crypto.TxParse.parse(client.transaction(txid))
                        val byHash = cs.rows.associateBy { it.scriptHash }
                        val inputs = tx.inputs.map { i ->
                            val prev = com.kilombino.pyblockwatch.crypto.TxParse.parse(client.transaction(i.txid)).outputs[i.vout]
                            val row = byHash[com.kilombino.pyblockwatch.crypto.Address.electrumScriptHash(prev.scriptPubKey)]
                                ?: error("This transaction spends coins that are not in this wallet: it can't be cancelled from here.")
                            com.kilombino.pyblockwatch.data.Scanner.SpendableUtxo(i.txid, i.vout, prev.value, row.chainIndex, row.index, 0, row.type)
                        }
                        val oldFee = inputs.sumOf { it.value } - tx.outputs.sumOf { it.value }
                        // Back to the account the coins came from (the first one's), at a fresh change address.
                        val tag = inputs.first().type
                        val to = changeScriptPubKey(nextUnused(1, tag), tag)
                        val newFee = maxOf(
                            estimateFee(inputs.size, listOf(to), ratePerVb.coerceIn(0.1, 1000.0), taprootIn(inputs)),
                            oldFee + estimateFee(inputs.size, listOf(to), 1.0, taprootIn(inputs)) + 1,
                        )
                        val back = inputs.sumOf { it.value } - newFee
                        require(back >= dustFor(to)) { "The coins (${inputs.sumOf { it.value }} sats) can't cover a $newFee-sat fee." }
                        SendDraft(scriptToAddress(to), back, newFee, 0, inputs,
                            listOf(com.kilombino.pyblockwatch.crypto.TxBuilder.Output(to, back)), chain,
                            replaces = txid, replacedFee = oldFee, cancels = true)
                    } finally { client.close() }
                }
                _state.update { it.copy(sendPhase = SendPhase.Review(draft)) }
            }.onFailure { e ->
                _state.update { it.copy(sendPhase = SendPhase.Failed(e.message ?: "Could not prepare the cancellation.")) }
            }
        }
    }

    /** A readable address for an output script (bc1q/bc1p/1/3), for the review screen. */
    private fun scriptToAddress(spk: ByteArray): String = when {
        spk.size == 22 && spk[0] == 0.toByte() -> com.kilombino.pyblockwatch.crypto.Bech32.encodeSegwit("bc", 0, spk.copyOfRange(2, 22))
        spk.size == 34 && spk[0] == 0.toByte() -> com.kilombino.pyblockwatch.crypto.Bech32.encodeSegwit("bc", 0, spk.copyOfRange(2, 34))
        spk.size == 34 && spk[0] == 0x51.toByte() -> com.kilombino.pyblockwatch.crypto.Bech32.encodeSegwit("bc", 1, spk.copyOfRange(2, 34))
        spk.size == 25 -> com.kilombino.pyblockwatch.crypto.Base58.encodeChecked(byteArrayOf(0) + spk.copyOfRange(3, 23))
        spk.size == 23 -> com.kilombino.pyblockwatch.crypto.Base58.encodeChecked(byteArrayOf(5) + spk.copyOfRange(2, 22))
        else -> error("unknown script")
    }

    // ------------------------------------------------------------------ hot wallet: send

    fun resetSend() {
        // A perfect coin left unsent takes its pool with it: a later send is just a send.
        exactFor = null; exactPending = null
        _state.update { it.copy(sendPhase = SendPhase.Editing, utxos = null, utxosChain = null) }
    }

    /** Load the wallet's spendable UTXOs for the coin-control picker. */
    fun loadUtxos() {
        val chain = _state.value.selected
        val cs = _state.value.chains[chain] ?: return
        if (_state.value.utxosLoading) return
        _state.update { it.copy(utxosLoading = true) }
        viewModelScope.launch {
            runCatching {
                val endpoint = store.endpoint(chain)
                val pin = store.pinnedFingerprint(endpoint)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    scanner.gatherUtxos(cs.rows.filter { it.isUsed }, endpoint, pin)
                }.let { notInCoinjoin(chain, it) }
            }.onSuccess { u -> applyUtxos(chain, u.sortedByDescending { x -> x.value }) }
             .onFailure { applyUtxos(chain, emptyList()) }
        }
    }

    /**
     * Record a coin fetch, but drop it if the selected chain moved while it was in flight, and
     * re-run for the chain now selected. The reload a chain switch triggers is otherwise swallowed
     * by the [UiState.utxosLoading] guard while a fetch is still outstanding.
     */
    private fun applyUtxos(chain: Chain, coins: List<com.kilombino.pyblockwatch.data.Scanner.SpendableUtxo>) {
        _state.update {
            if (it.selected == chain) it.copy(utxos = coins, utxosChain = chain, utxosLoading = false)
            else it.copy(utxosLoading = false)
        }
        if (_state.value.selected != chain) loadUtxos()
    }

    /**
     * Choose coins and compute the fee for a spend, WITHOUT touching the seed — this stage is
     * all public data, so it can be reviewed before any biometric prompt. If [selected] is
     * non-empty the user is doing coin control and exactly those inputs are used; otherwise the
     * wallet auto-selects largest-first. [feeRatePerVb] is honoured across 0.1–1000 sat/vB.
     */
    fun prepareSend(
        toAddress: String, amountSats: Long, feeRatePerVb: Double,
        selected: List<com.kilombino.pyblockwatch.data.Scanner.SpendableUtxo> = emptyList(),
        extra: List<Pair<String, Long>> = emptyList(),
    ) {
        val chain = _state.value.selected
        val cs = _state.value.chains[chain] ?: return
        if (_state.value.xpub == null) return
        _state.update { it.copy(sendPhase = SendPhase.Preparing) }
        viewModelScope.launch {
            runCatching {
                // Coin control only: the picked coins must belong to the chain being spent on, or a
                // chain switch mid-flow could carry another chain's coins into this draft.
                require(selected.isEmpty() || _state.value.utxosChain == chain) {
                    "The chosen coins are not from the current chain."
                }
                // A human-readable handle (user@domain, BIP-353) resolves via DNS to the real
                // address — which may itself be a silent payment.
                val effectiveTo = if (toAddress.contains("@")) resolveBip353(toAddress) else toAddress
                val isSilent = effectiveTo.trim().lowercase().startsWith("sp1")
                val silentRecipient = if (isSilent)
                    com.kilombino.pyblockwatch.crypto.SilentPayment.decodeAddress(effectiveTo) else null
                // For a silent payment the true output depends on the input keys (computed at
                // signing); a Taproot placeholder of the right size keeps fee/change correct.
                val toScript = if (isSilent) byteArrayOf(0x51, 0x20) + ByteArray(32)
                    else com.kilombino.pyblockwatch.crypto.Address.decodeToScriptPubKey(effectiveTo).also { requireSpendableDestination(it, "The address") }
                require(amountSats > 0) { "Enter an amount." }
                val rate = feeRatePerVb.coerceIn(0.1, 1000.0)
                // Extra recipients (BTC only): plain addresses or user@domain, each its own output
                // in the same transaction, so the whole batch is signed (and replay-protected) at once.
                require(extra.isEmpty() || chain == Chain.BLAKE2B) { "Several recipients are only for BTC." }
                val resolvedExtra = HashMap<Int, String>()
                val extraOuts = extra.mapIndexed { i, (addr, sats) ->
                    require(sats > DUST_SATS) { "Recipient ${i + 2}: enter an amount above ${DUST_SATS} sats." }
                    val a = if (addr.contains("@")) resolveBip353(addr).also { resolvedExtra[i] = "$addr → $it" } else addr
                    require(!a.trim().lowercase().startsWith("sp1")) {
                        "Recipient ${i + 2}: a silent payment can only be the first recipient."
                    }
                    com.kilombino.pyblockwatch.crypto.TxBuilder.Output(
                        com.kilombino.pyblockwatch.crypto.Address.decodeToScriptPubKey(a.trim())
                            .also { requireSpendableDestination(it, "Recipient ${i + 2}") }, sats)
                }
                val totalOut = amountSats + extraOuts.sumOf { it.value }
                // An output under its type's dust limit is refused by the network: say so here.
                dustFor(toScript).let { d -> require(amountSats >= d) { "Enter at least $d sats: less can't be sent to that kind of address." } }
                extraOuts.forEachIndexed { i, o ->
                    dustFor(o.scriptPubKey).let { d -> require(o.value >= d) { "Recipient ${i + 2}: enter at least $d sats for that kind of address." } }
                }

                // A perfect coin's change goes to the pool's account, like the coin itself: change
                // of another type would stand out as the change, and isn't where the user expects it.
                val exactTag = exactPending?.takeIf { exactFor != null }
                val changeTag = if (exactTag != null) exactTag.second else newTag
                val changeScript = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    changeScriptPubKey(nextUnused(1, changeTag), changeTag)
                }
                val withChange = listOf(toScript) + extraOuts.map { it.scriptPubKey } + changeScript
                val noChange = listOf(toScript) + extraOuts.map { it.scriptPubKey }
                val chosen: List<com.kilombino.pyblockwatch.data.Scanner.SpendableUtxo>
                var sum: Long
                if (selected.isNotEmpty()) {
                    chosen = selected
                    sum = selected.sumOf { it.value }
                } else {
                    val endpoint = store.endpoint(chain)
                    val pin = store.pinnedFingerprint(endpoint)
                    val utxos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        scanner.gatherUtxos(cs.rows.filter { it.isUsed }, endpoint, pin)
                    }.let { notInCoinjoin(chain, it) }
                    require(utxos.isNotEmpty()) { "No spendable coins on this chain yet." }
                    val acc = mutableListOf<com.kilombino.pyblockwatch.data.Scanner.SpendableUtxo>()
                    var s = 0L
                    for (u in utxos.sortedByDescending { it.value }) {
                        acc += u; s += u.value
                        if (s >= totalOut + estimateFee(acc.size, withChange, rate, taprootIn(acc))) break
                    }
                    chosen = acc; sum = s
                }

                verifyCoins(chain, chosen)
                var fee = estimateFee(chosen.size, withChange, rate, taprootIn(chosen))
                // Sending everything (MAX) has no change output: size the fee without it. Then there
                // must be no change output either, or the transaction would pay a fee sized for one
                // output less than it has.
                var noChangeOutput = false
                if (sum < totalOut + fee) { fee = estimateFee(chosen.size, noChange, rate, taprootIn(chosen)); noChangeOutput = true }
                require(sum >= totalOut + fee) {
                    if (selected.isNotEmpty()) "The chosen coins don't cover the amount plus fee."
                    else "Not enough funds for the amount plus fee."
                }
                var change = sum - totalOut - fee

                // The first recipient stays at index 0: a silent payment's real output replaces it there.
                val outputs = mutableListOf(
                    com.kilombino.pyblockwatch.crypto.TxBuilder.Output(toScript, amountSats),
                )
                outputs += extraOuts
                // Taproot change has a higher dust limit (330) than bc1q (294).
                if (change > maxOf(DUST_SATS, dustFor(changeScript) - 1) && !noChangeOutput) {
                    outputs += com.kilombino.pyblockwatch.crypto.TxBuilder.Output(changeScript, change)
                } else {
                    fee = sum - totalOut // dust (or unpriced) change folded into the fee
                    change = 0
                }
                // A spamchain spend is signed the legacy way, so its pre-fork coins could be moved on
                // BTC too by rebroadcasting it there, unless they have already moved on BTC.
                val replayable = if (chain == Chain.SHA256 && chosen.any { it.height in 1 until Chain.BLAKE2B_FORK_HEIGHT })
                    preForkOnBtc(chosen) else emptyList()
                // The review shows what will really be paid: the resolved address, next to the handle.
                val seated = if (chain == Chain.BLAKE2B) com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.waitingOutpoints(getApplication()) else emptyMap()
                val draft = SendDraft(effectiveTo, amountSats, fee, change, chosen, outputs, chain, silentRecipient,
                    replayableOnBtc = replayable,
                    leavesPools = chosen.mapNotNull { seated["${it.txid}:${it.vout}"] }.distinct(),
                    extra = extra.mapIndexed { i, (a, v) -> (resolvedExtra[i] ?: a) to v },
                    handle = if (toAddress.contains("@")) toAddress.trim() else null,
                    changeAddress = if (change > 0) runCatching { scriptToAddress(changeScript) }.getOrNull() else null)
                _state.update { it.copy(sendPhase = SendPhase.Review(draft)) }
            }.onFailure { e ->
                _state.update { it.copy(sendPhase = SendPhase.Failed(e.message ?: "Could not prepare the send.")) }
            }
        }
    }

    /** Which of [coins] (spamchain ones) were made before the fork and are still unspent on BTC; null if BTC could not be asked. */
    private suspend fun preForkOnBtc(coins: List<com.kilombino.pyblockwatch.data.Scanner.SpendableUtxo>): List<String>? {
        val rows = _state.value.chains[Chain.BLAKE2B]?.rows?.filter { it.isUsed } ?: return null
        val endpoint = store.endpoint(Chain.BLAKE2B)
        val onBtc = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                scanner.gatherUtxos(rows, endpoint, store.pinnedFingerprint(endpoint))
            }
        }.getOrNull() ?: return null
        val unspent = onBtc.map { "${it.txid}:${it.vout}" }.toSet()
        return coins.filter { it.height in 1 until Chain.BLAKE2B_FORK_HEIGHT }
            .map { "${it.txid}:${it.vout}" }.filter { it in unspent }
    }

    /**
     * Split pre-fork coins before spending them on the spamchain: drafts, on BTC, a send of
     * exactly those coins to a new address of this wallet, signed with the unified sighash as
     * every BTC spend is. Once it confirms they sit in a new output on BTC and the old one is
     * spent there, so nothing later done with the old coins on the spamchain can be replayed
     * onto BTC. No time rule is involved: it holds for good. The draft goes through the usual
     * review and fingerprint.
     */
    fun splitOnBtc(outpoints: List<String>, feeRatePerVb: Double = 1.0) {
        if (outpoints.isEmpty()) return
        select(Chain.BLAKE2B)
        _state.update { it.copy(sendPhase = SendPhase.Preparing) }
        viewModelScope.launch {
            runCatching {
                val cs = _state.value.chains[Chain.BLAKE2B] ?: error("No BTC wallet.")
                val endpoint = store.endpoint(Chain.BLAKE2B)
                val coins = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    scanner.gatherUtxos(cs.rows.filter { it.isUsed }, endpoint, store.pinnedFingerprint(endpoint))
                }.filter { "${it.txid}:${it.vout}" in outpoints }
                require(coins.isNotEmpty()) { "Those coins have already moved on BTC: they are split, and the spamchain spend can't touch them." }
                applyUtxos(Chain.BLAKE2B, coins)
                val (to, _) = receiveAddress(nextReceiveIndex()) ?: error("No address to split into.")
                val toScript = com.kilombino.pyblockwatch.crypto.Address.decodeToScriptPubKey(to)
                val fee = estimateFee(coins.size, listOf(toScript), feeRatePerVb, taprootIn(coins))
                val amount = coins.sumOf { it.value } - fee
                require(amount > DUST_SATS) { "Those coins don't cover the fee of the split." }
                Triple(to, amount, coins)
            }.onSuccess { (to, amount, coins) ->
                // Exactly these coins, never the loaded list a refresh may have replaced meanwhile.
                prepareSend(to, amount, feeRatePerVb, selected = coins)
            }
             .onFailure { e -> _state.update { it.copy(sendPhase = SendPhase.Failed(e.message ?: "Could not prepare the split.")) } }
        }
    }

    /** The most a sweep can send from [selected] (or all loaded UTXOs): their value minus a
     *  one-output fee. Used by the MAX button; 0 if the UTXOs aren't loaded yet. */
    fun maxSendable(feeRatePerVb: Double, selected: List<com.kilombino.pyblockwatch.data.Scanner.SpendableUtxo>): Long {
        val u = if (selected.isNotEmpty()) selected else _state.value.utxos ?: emptyList()
        if (u.isEmpty()) return 0
        // A P2TR output (the largest common one) so MAX never undershoots the rate for any address.
        val fee = estimateFee(u.size, listOf(ByteArray(34)), feeRatePerVb.coerceIn(0.1, 1000.0), taprootIn(u))
        return (u.sumOf { it.value } - fee).coerceAtLeast(0)
    }

    /**
     * Refuses outputs anyone could spend: witness v1 that is not a 32-byte Taproot key, and the
     * future witness versions 2–16. Those decode as valid addresses but have no rule guarding
     * them yet, so whatever is sent there can be taken by anybody.
     */
    private fun requireSpendableDestination(script: ByteArray, who: String) {
        if (script.size < 4) return
        val op = script[0].toInt() and 0xff
        if (op !in 0x51..0x60) return            // witness v0 and non-witness scripts are fine
        val version = op - 0x50
        val program = script.size - 2
        require(version == 1 && program == 32) {
            "$who is a witness version $version output with a $program-byte program: anyone could spend it. Not sending."
        }
    }

    /**
     * Resolve a BIP-353 human-readable handle (`user@domain`, optionally ₿-prefixed) to a payment
     * address. Reads the `user.user._bitcoin-payment.domain` TXT record (BIP-353) over DNS-over-HTTPS (Cloudflare)
     * and returns the silent-payment address if the URI carries `sp=`, otherwise the on-chain
     * address. Throws with a readable message when there is no record.
     */
    private suspend fun resolveBip353(handle: String): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val h = handle.trim().removePrefix("₿").removePrefix("₿")
            val at = h.indexOf('@')
            require(at > 0 && at < h.length - 1) { "Not a user@domain address." }
            val name = "${h.substring(0, at)}.user._bitcoin-payment.${h.substring(at + 1)}"
            val url = java.net.URL("https://cloudflare-dns.com/dns-query?name=$name&type=TXT&do=1")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                setRequestProperty("Accept", "application/dns-json")
                connectTimeout = 8000; readTimeout = 8000
            }
            val body = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                throw IllegalArgumentException("Could not look up $h.")
            } finally { conn.disconnect() }
            val json = org.json.JSONObject(body)
            val answers = json.optJSONArray("Answer")
                ?: throw IllegalArgumentException("No payment record for $h.")
            // BIP-353 requires DNSSEC: without a validated answer a hijacked DNS (or the resolver
            // itself) could swap the address. Refuse rather than pay an unauthenticated record.
            require(json.optBoolean("AD", false)) {
                "The payment record for $h is not DNSSEC-signed, so it can't be trusted. Ask for a plain address."
            }
            var uri: String? = null
            for (i in 0 until answers.length()) {
                val data = answers.getJSONObject(i).optString("data").trim().trim('"')
                if (data.lowercase().startsWith("bitcoin:")) { uri = data; break }
            }
            val u = uri ?: throw IllegalArgumentException("No bitcoin: instruction for $h.")
            val afterScheme = u.substring("bitcoin:".length)
            val addressPart = afterScheme.substringBefore("?")
            val sp = afterScheme.substringAfter("?", "").split("&")
                .map { it.split("=", limit = 2) }
                .firstOrNull { it.size == 2 && it[0].lowercase() == "sp" }?.get(1)
            (sp ?: addressPart).takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Empty payment instruction for $h.")
        }

    /**
     * The next unused receive index — one past the highest used on EITHER chain. The same
     * key gives the same addresses on BTC and on the spamchain, so an address used on one
     * must not be handed out again on the other.
     */
    fun nextReceiveIndex(): Int = nextUnused(0)

    /**
     * Receive indexes of the account new addresses come from that hold nothing and never did:
     * the ones skipped below the last used address (handed out and never paid) and [ahead] past
     * the next unused one, in order. A coinjoin seat's address is left out.
     */
    fun unusedReceiveIndexes(ahead: Int = 20): List<Int> {
        val used = _state.value.chains.values.flatMap { it.rows }
            .filter { it.chainIndex == 0 && it.type == newTag && it.isUsed }.map { it.index }.toSet()
        val next = nextReceiveIndex()
        val type = typeFor(newTag)
        runCatching { com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.load(getApplication()) }
        val seats = com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.states.value
            .filter { it.terms.script == type }.flatMap { st -> st.mixScripts.map { it.toList() } }.toSet()
        return (0 until next + ahead).filter { i ->
            if (i in used) return@filter false
            if (seats.isEmpty() || i >= next) return@filter true
            val addr = receiveAddress(i)?.first ?: return@filter false
            runCatching { com.kilombino.pyblockwatch.crypto.Address.decodeToScriptPubKey(addr).toList() !in seats }.getOrDefault(false)
        }
    }

    /**
     * Which account new addresses come from: [ScriptType.P2TR] for the hot wallet's Taproot one
     * when it is on, null for the wallet's own (BIP-84 native SegWit for a hot wallet).
     */
    private val newTag: ScriptType?
        get() = ScriptType.P2TR.takeIf { _state.value.isHot && _state.value.receiveTaproot && _state.value.taprootXpub != null }

    /** The account xpub behind a row/coin tag (see [AddressRow.type]). */
    private fun xpubFor(tag: ScriptType?): String? = if (tag == null) _state.value.xpub else _state.value.taprootXpub

    /** The address type behind a row/coin tag. */
    private fun typeFor(tag: ScriptType?): ScriptType = tag ?: store.scriptType

    /** The hot wallet's Taproot account, scanned next to the main one once it exists. */
    private fun extraAccount(): Scanner.Extra? =
        _state.value.taprootXpub?.takeIf { _state.value.isHot }?.let { Scanner.Extra(it, ScriptType.P2TR) }

    /** How many of [coins] are Taproot: they weigh less than the native SegWit ones. */
    private fun taprootIn(coins: List<Scanner.SpendableUtxo>): Int = coins.count { typeFor(it.type) == ScriptType.P2TR }

    /** The scriptPubKey a coin of this wallet is locked to, derived publicly from its account. */
    private fun coinScript(u: Scanner.SpendableUtxo): ByteArray {
        val parsed = com.kilombino.pyblockwatch.crypto.Bip32.parseExtendedPubKey(xpubFor(u.type) ?: error("No wallet."))
        return com.kilombino.pyblockwatch.crypto.Address.scriptPubKey(
            com.kilombino.pyblockwatch.crypto.Bip32.derivePath(parsed, u.chainIndex, u.index).pubkey(), typeFor(u.type))
    }

    private val addressCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The address a coin of this wallet sits on (derived publicly, cached), or null if unknown. */
    fun coinAddress(u: Scanner.SpendableUtxo): String? {
        val xpub = xpubFor(u.type) ?: return null
        val type = typeFor(u.type)
        val key = "$xpub/${u.chainIndex}/${u.index}/$type"
        addressCache[key]?.let { return it }
        return runCatching {
            com.kilombino.pyblockwatch.crypto.Address.encode(com.kilombino.pyblockwatch.crypto.Bip32.derivePath(
                com.kilombino.pyblockwatch.crypto.Bip32.parseExtendedPubKey(xpub), u.chainIndex, u.index).pubkey(), type)
        }.getOrNull()?.also { addressCache[key] = it }
    }

    /** The derivation path of a coin's address, e.g. m/86'/0'/0'/1/4. */
    fun coinPath(u: Scanner.SpendableUtxo): String =
        "m/${Scanner.purposeFor(typeFor(u.type))}'/0'/0'/${u.chainIndex}/${u.index}"

    /** Next unused index of [chainIndex] in the account [tag] picks (the one new addresses come from by default). */
    private fun nextUnused(chainIndex: Int, tag: ScriptType? = newTag): Int {
        val seen = _state.value.chains.values.flatMap { it.rows }.filter { it.chainIndex == chainIndex && it.type == tag }
            .maxOfOrNull { it.index }?.plus(1) ?: 0
        val top = maxOf(seen, xpubFor(tag)?.let { store.usedTop(it, chainIndex, typeFor(tag)) } ?: 0)
        // Addresses a coinjoin seat holds (native SegWit pools take bc1q ones, Taproot pools bc1p).
        return maxOf(top, coinjoinReservedTop(chainIndex, top, tag))
    }

    /**
     * One past the highest address a coinjoin seat holds on [chainIndex] (0: the mixed output,
     * 1: the change), counting only pools that still need them. A pool that ended before this
     * wallet posted its output never showed those addresses on chain or in the round, so they
     * go back to the queue: cancelled pools leave no gap in the address chain.
     */
    private fun coinjoinReservedTop(chainIndex: Int, from: Int, tag: ScriptType? = null): Int {
        val xpub = xpubFor(tag) ?: return 0
        val type = typeFor(tag)
        // The saved pools must be in memory before any address is handed out.
        runCatching { com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.load(getApplication()) }
        val scripts = com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.states.value
            .filter { st ->
                val ended = st.phase == com.kilombino.pyblockwatch.coinjoin.PoolSession.Phase.ABORTED ||
                    st.phase == com.kilombino.pyblockwatch.coinjoin.PoolSession.Phase.REJECTED
                !(ended && !st.postedOutput) && st.terms.script == type
            }
            .flatMap { if (chainIndex == 0) it.mixScripts else listOfNotNull(it.seat.changeScript) }
            .map { it.toList() }.toMutableSet()
        if (scripts.isEmpty()) return 0
        // A seat's addresses were handed out at or past what was used then, so only what lies
        // at or past `from` can still push the next index. Derived scripts are cached: this
        // runs on the main thread, from the screens that show an address.
        var top = 0
        for (i in from until from + 40) {
            if (scripts.isEmpty()) break
            val sp = derivedScript(xpub, chainIndex, i, type) ?: break
            if (scripts.remove(sp)) top = i + 1
        }
        return top
    }

    private val scriptCache = java.util.concurrent.ConcurrentHashMap<String, List<Byte>>()

    /** scriptPubKey of [xpub]/[chainIndex]/[index], derived once. */
    private fun derivedScript(xpub: String, chainIndex: Int, index: Int, type: ScriptType = store.scriptType): List<Byte>? =
        scriptCache.getOrPut("$xpub/$chainIndex/$index/$type") {
            val parsed = runCatching { com.kilombino.pyblockwatch.crypto.Bip32.parseExtendedPubKey(xpub) }.getOrNull()
                ?: return null
            com.kilombino.pyblockwatch.crypto.Address.scriptPubKey(
                com.kilombino.pyblockwatch.crypto.Bip32.derivePath(parsed, chainIndex, index).pubkey(), type).toList()
        }

    /** Remember how far each branch has been used, so the other chain knows before its own scan. */
    private fun noteUsed(rows: List<AddressRow>) {
        for (tag in listOf(null, ScriptType.P2TR)) {
            val xpub = xpubFor(tag) ?: continue
            for (ci in 0..1) rows.filter { it.chainIndex == ci && it.type == tag }.maxOfOrNull { it.index }
                ?.let { store.noteUsedTop(xpub, ci, it + 1, typeFor(tag)) }
        }
    }

    /** Derive receive address [index] and its BIP-32 path, publicly from the xpub (no seed). */
    fun receiveAddress(index: Int): Pair<String, String>? = receiveAddress(index, newTag)

    private fun receiveAddress(index: Int, tag: ScriptType?): Pair<String, String>? {
        val xpub = xpubFor(tag) ?: return null
        val type = typeFor(tag)
        return runCatching {
            val parsed = com.kilombino.pyblockwatch.crypto.Bip32.parseExtendedPubKey(xpub)
            val pub = com.kilombino.pyblockwatch.crypto.Bip32.derivePath(parsed, 0, index).pubkey()
            val addr = com.kilombino.pyblockwatch.crypto.Address.encode(pub, type)
            addr to "m/${Scanner.purposeFor(type)}'/0'/0'/0/$index"
        }.getOrNull()
    }

    /**
     * Sign the reviewed draft with keys derived from the seed (unlocked by the just-authorised
     * [decryptCipher]) and broadcast it. The seed is read, used and dropped inside this call.
     */
    fun confirmSend(decryptCipher: javax.crypto.Cipher) {
        val draft = (_state.value.sendPhase as? SendPhase.Review)?.draft ?: return
        val chain = draft.chain // the chain the draft was reviewed under, not whatever is selected now
        _state.update { it.copy(sendPhase = SendPhase.Broadcasting) }
        // A perfect coin for a pool: its key, from the same unlock, goes with it (see [ExactFor]).
        val exact = exactPending?.takeIf { exactFor != null }
        var exactKey: java.math.BigInteger? = null
        viewModelScope.launch {
            runCatching {
                val signed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val secret = seedVault.revealSecret(decryptCipher)
                    val master = com.kilombino.pyblockwatch.crypto.Bip32Priv
                        .fromSeed(com.kilombino.pyblockwatch.crypto.Bip39.toSeed(secret.words, secret.passphrase))
                    exact?.let { (i, tag, _) ->
                        exactKey = com.kilombino.pyblockwatch.crypto.Bip32Priv
                            .derivePath(master, "m/${Scanner.purposeFor(typeFor(tag))}'/0'/0'/0/$i").key
                    }
                    // A pool may have reached its closing or signing step while the review was open:
                    // its coins are taken now, so spending one here would break that round.
                    check(notInCoinjoin(chain, draft.inputs).size == draft.inputs.size) {
                        "A coin in this payment is now being signed in a coinjoin round. Nothing was signed."
                    }
                    val inputs = draft.inputs.map { u ->
                        // Each coin with its own account's key: BIP-84, or BIP-86 for a Taproot one.
                        val type = typeFor(u.type)
                        val node = com.kilombino.pyblockwatch.crypto.Bip32Priv
                            .derivePath(master, "m/${Scanner.purposeFor(type)}'/0'/0'/${u.chainIndex}/${u.index}")
                        // 0xfffffffd signals replace-by-fee (BIP-125), so a stuck send can be bumped.
                        com.kilombino.pyblockwatch.crypto.TxBuilder.Input(
                            u.txid, u.vout, u.value, node.key, node.publicKey(), 0xfffffffdL, type,
                        ).also {
                            check(it.spentScript.contentEquals(coinScript(u))) { "A coin's key does not match its address. Nothing was signed." }
                        }
                    }
                    // Silent payment: now that the input keys are known, compute the real Taproot
                    // output and swap it in for the placeholder at index 0.
                    val outputs = draft.silentRecipient?.let { sp ->
                        val spInputs = inputs.map {
                            com.kilombino.pyblockwatch.crypto.SilentPayment.Input(
                                com.kilombino.pyblockwatch.crypto.SilentPayment.inputKey(it.privateKey, it.type == ScriptType.P2TR), it.txid, it.vout)
                        }
                        val spScript = com.kilombino.pyblockwatch.crypto.SilentPayment.outputScript(sp, spInputs, 0)
                        draft.outputs.mapIndexed { i, o ->
                            if (i == 0) com.kilombino.pyblockwatch.crypto.TxBuilder.Output(spScript, o.value) else o
                        }
                    } ?: draft.outputs
                    // Opt into the unified sighash on the BLAKE2b chain, where the fork makes it
                    // valid, so the spend cannot be replayed onto the shared-history SHA-256 chain.
                    // A SHA-256 spend has no fork to validate it, so it stays legacy SIGHASH_ALL.
                    com.kilombino.pyblockwatch.crypto.TxBuilder.build(
                        inputs, outputs, unified = chain == com.kilombino.pyblockwatch.chain.Chain.BLAKE2B,
                    )
                }
                val endpoint = store.endpoint(chain)
                val pin = store.pinnedFingerprint(endpoint)
                val txid = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    scanner.broadcast(signed.rawHex, endpoint, pin)
                }
                _state.update { it.copy(sendPhase = SendPhase.Sent(txid)) }
                // A coin of a waiting pool went: give up that seat, so the pool doesn't wait on it.
                com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.leavePools(getApplication(), draft.leavesPools)
                // A cancelled payment that made a perfect coin: the coin is gone, and its pool with it.
                if (draft.cancels) draft.replaces?.let { com.kilombino.pyblockwatch.coinjoin.PerfectCoin.cancel(getApplication(), it) }
                exact?.let { (i, tag, value) -> exactKey?.let { k -> runCatching { expectPerfectCoin(txid, draft, i, tag, value, k) } } }
                exactFor = null; exactPending = null
                refresh(chain)
            }.onFailure { e ->
                _state.update { it.copy(sendPhase = SendPhase.Failed(e.message ?: "The broadcast failed.")) }
            }
        }
    }

    // ------------------------------------------------------------------ PSBT (watch-only)

    /** Whether this watch-only wallet can send through a PSBT on the current chain. */
    fun psbtSupported(): Boolean = !_state.value.isHot && _state.value.selected == Chain.BLAKE2B &&
        store.scriptType in setOf(ScriptType.P2WPKH, ScriptType.P2SH_P2WPKH, ScriptType.P2TR)

    val keyOrigin: String get() = store.keyOrigin

    private fun psbtCoins(draft: SendDraft, origin: com.kilombino.pyblockwatch.crypto.Psbt.Origin?): List<com.kilombino.pyblockwatch.crypto.Psbt.Coin> {
        val account = com.kilombino.pyblockwatch.crypto.Bip32.parseExtendedPubKey(_state.value.xpub ?: error("No watch-only key."))
        return draft.inputs.map { u ->
            com.kilombino.pyblockwatch.crypto.Psbt.Coin(u.txid, u.vout, u.value, store.scriptType,
                com.kilombino.pyblockwatch.crypto.Bip32.derivePath(account, u.chainIndex, u.index).pubkey(),
                origin?.child(u.chainIndex, u.index))
        }
    }

    /** Make the PSBT for the reviewed draft; [origin] is the optional `[fingerprint/path]`. */
    fun exportPsbt(origin: String) {
        val draft = (_state.value.sendPhase as? SendPhase.Review)?.draft ?: return
        runCatching {
            require(psbtSupported() && draft.chain == Chain.BLAKE2B) { "Signing elsewhere is for BTC (BLAKE2b) only." }
            require(draft.silentRecipient == null) { "A silent payment needs the private keys while building it: send it from a hot wallet." }
            val o = com.kilombino.pyblockwatch.crypto.Psbt.Origin.parse(origin)
            store.keyOrigin = origin.trim()
            val account = com.kilombino.pyblockwatch.crypto.Bip32.parseExtendedPubKey(_state.value.xpub!!)
            // Our change output, so the signer can tell it is not a payment.
            val change = if (draft.change > 0) {
                val i = draft.outputs.lastIndex
                val idx = nextChangeIndex(emptyList())
                val pub = com.kilombino.pyblockwatch.crypto.Bip32.derivePath(account, 1, idx).pubkey()
                check(com.kilombino.pyblockwatch.crypto.Address.scriptPubKey(pub, store.scriptType).contentEquals(draft.outputs[i].scriptPubKey)) {
                    "The change address moved since the review: prepare the send again."
                }
                mapOf(i to com.kilombino.pyblockwatch.crypto.Psbt.Change(pub, store.scriptType, o?.child(1, idx)))
            } else emptyMap()
            com.kilombino.pyblockwatch.crypto.Psbt.create(psbtCoins(draft, o), draft.outputs, change)
        }.onSuccess { b -> _state.update { it.copy(sendPhase = SendPhase.AwaitingSignature(draft, b)) } }
         .onFailure { e -> _state.update { it.copy(sendPhase = SendPhase.Failed(e.message ?: "Could not make the PSBT.")) } }
    }

    /** Take the signed PSBT back ([text]: base64 or hex), check every signature and broadcast. */
    fun importSignedPsbt(text: String) = importSignedPsbt { com.kilombino.pyblockwatch.crypto.Psbt.decodeText(text) }
    fun importSignedPsbtFile(bytes: ByteArray) = importSignedPsbt { com.kilombino.pyblockwatch.crypto.Psbt.decodeFile(bytes) }

    private fun importSignedPsbt(decode: () -> ByteArray) {
        val phase = _state.value.sendPhase as? SendPhase.AwaitingSignature ?: return
        val draft = phase.draft
        _state.update { it.copy(sendPhase = SendPhase.Broadcasting) }
        viewModelScope.launch {
            runCatching {
                val returned = runCatching { decode() }
                    .getOrElse { error("That is not a PSBT (base64 or hex).") }
                val signed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val o = com.kilombino.pyblockwatch.crypto.Psbt.Origin.parse(store.keyOrigin)
                    com.kilombino.pyblockwatch.crypto.Psbt.finish(phase.psbt, returned, psbtCoins(draft, o), draft.outputs)
                }
                val endpoint = store.endpoint(draft.chain)
                val pin = store.pinnedFingerprint(endpoint)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { scanner.broadcast(signed.rawHex, endpoint, pin) }
            }.onSuccess { txid ->
                _state.update { it.copy(sendPhase = SendPhase.Sent(txid)) }
                refresh(draft.chain)
            }.onFailure { e ->
                // Back to waiting: the same PSBT can still be signed properly and brought back.
                _state.update { it.copy(sendPhase = phase, psbtError = e.message ?: "Could not use that PSBT.") }
            }
        }
    }

    fun clearPsbtError() = _state.update { it.copy(psbtError = null) }

    /** Roughly a P2WPKH transaction's vbytes → fee in sats, rounded up. */
    private fun estimateFee(nIn: Int, nOut: Int, ratePerVb: Double): Long {
        val vbytes = 11.0 + 68.0 * nIn + 31.0 * nOut // overhead + inputs + outputs (segwit)
        return kotlin.math.ceil(vbytes * ratePerVb).toLong()
    }

    /**
     * Fee for P2WPKH inputs and these exact outputs: each output is 8 + 1 + script bytes, so a
     * bc1p (34-byte script) costs 43 vbytes, not the 31 of a bc1q. Sizing every output as bc1q
     * made a 1 sat/vB send to a Taproot address go out at 0.92 sat/vB, below most nodes' relay
     * minimum, and it never propagated.
     */
    private fun estimateFee(nIn: Int, outputScripts: List<ByteArray>, ratePerVb: Double, taprootIn: Int = 0): Long {
        // A watch-only wallet may spend nested SegWit or Taproot coins, which weigh differently.
        val perInput = when {
            _state.value.isHot -> 68.0
            store.scriptType == ScriptType.P2SH_P2WPKH -> 91.0
            // Key-path signature plus the unified sighash byte the BLAKE2b chain requires (65 bytes).
            store.scriptType == ScriptType.P2TR -> 57.75
            else -> 68.0
        }
        // A hot wallet's Taproot coins: key-path signature plus the unified sighash byte (57.75 vB).
        val tr = if (_state.value.isHot) taprootIn.coerceIn(0, nIn) else 0
        val vbytes = 10.5 + perInput * (nIn - tr) + 57.75 * tr + outputScripts.sumOf { 9.0 + it.size }
        return kotlin.math.ceil(vbytes * ratePerVb).toLong()
    }

    /** The next unused change index on either chain, so a spend's change goes to a fresh address. */
    @Suppress("UNUSED_PARAMETER")
    private fun nextChangeIndex(rows: List<AddressRow>): Int = nextUnused(1)

    /**
     * The change output's scriptPubKey, derived publicly from the account xpub (no seed needed):
     * by default from the account new addresses come from, so Taproot on means Taproot change.
     */
    private fun changeScriptPubKey(index: Int, tag: ScriptType? = newTag): ByteArray {
        val parsed = com.kilombino.pyblockwatch.crypto.Bip32.parseExtendedPubKey(xpubFor(tag) ?: error("No wallet."))
        val pub = com.kilombino.pyblockwatch.crypto.Bip32.derivePath(parsed, 1, index).pubkey()
        return com.kilombino.pyblockwatch.crypto.Address.scriptPubKey(pub, typeFor(tag))
    }

    // ------------------------------------------------------------------ coinjoin

    /** Coins waiting in a coinjoin round are not offered to a normal send. */
    private fun notInCoinjoin(chain: Chain, u: List<Scanner.SpendableUtxo>): List<Scanner.SpendableUtxo> {
        if (chain != Chain.BLAKE2B) return u
        val locked = com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.lockedOutpoints(getApplication())
        return u.filterNot { "${it.txid}:${it.vout}" in locked }
    }

    /** Can this wallet take part: a spending wallet with native SegWit (bc1q) addresses. */
    fun coinjoinSupported(): Boolean = _state.value.isHot && com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.supported(store)

    /** Confirmed BLAKE2b coins that can go into a pool, biggest first. */
    /** The node's fee estimate for the next blocks on BTC, in sat/vB (null if it has none). */
    suspend fun suggestedBtcFeeRate(): Double? {
        val endpoint = store.endpoint(Chain.BLAKE2B)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            scanner.suggestedFeeRate(endpoint, store.pinnedFingerprint(endpoint))
        }
    }

    /** Every coin in [rows] (immature mined rewards included), by scripthash: for showing, not spending. */
    suspend fun addressCoins(chain: Chain, rows: List<AddressRow>): Map<String, List<Scanner.SpendableUtxo>> {
        val endpoint = store.endpoint(chain)
        val u = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            scanner.listCoins(rows, endpoint, store.pinnedFingerprint(endpoint))
        }
        val byKey = rows.associateBy { Triple(it.type, it.chainIndex, it.index) }
        return u.groupBy { byKey[Triple(it.type, it.chainIndex, it.index)]?.scriptHash ?: "" }
    }

    /** Whether this wallet can open or join Taproot pools: a hot wallet with its Taproot account on. */
    fun taprootPools(): Boolean = _state.value.isHot && _state.value.taprootXpub != null

    /** Confirmed coins of one address type ([tag] null: bc1q; P2TR: bc1p) that can go into a pool, biggest first. */
    suspend fun coinjoinCoins(tag: ScriptType? = null): List<Scanner.SpendableUtxo> {
        val cs = _state.value.chains[Chain.BLAKE2B] ?: return emptyList()
        val endpoint = store.endpoint(Chain.BLAKE2B)
        val u = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            scanner.gatherUtxos(cs.rows.filter { it.isUsed }, endpoint, store.pinnedFingerprint(endpoint))
        }
        // A coin already seated in another pool, even one still waiting for people, is not offered:
        // taking it for this pool would spend it (or make a perfect coin from it) and drop that seat.
        // A normal send may still use it (notInCoinjoin), and leaves that pool.
        val seated = com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.waitingOutpoints(getApplication()).keys
        // One type per pool: every input and output of a round must look the same.
        val coins = notInCoinjoin(Chain.BLAKE2B, u).filter { it.height > 0 && it.type == tag && "${it.txid}:${it.vout}" !in seated }
            .sortedByDescending { it.value }
        checkMixed(coins) // so the picker can say which ones came out of a round
        return coins
    }

    /**
     * Everything a seat needs, from the seed unlocked by [decryptCipher]: the coin's key and
     * two fresh addresses (mixed output and change), reserved at once so no other payment
     * hands them out — on either chain, as the same key gives the same address on both.
     */
    fun coinjoinPick(decryptCipher: javax.crypto.Cipher, u: Scanner.SpendableUtxo, outputs: Int = 1): com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.Pick =
        coinjoinPick(decryptCipher, listOf(u), outputs, null)

    /**
     * Everything a seat needs, from the seed unlocked by [decryptCipher]: the keys of [coins] (all of
     * one address type) and fresh addresses of that same type: [outputs] for the mixed outputs
     * (one before Kilojoin v4) and one for the change. [mixScripts]: the mixed outputs' scripts when
     * they go to another wallet ([com.kilombino.pyblockwatch.coinjoin.Destination]); the change stays here.
     */
    fun coinjoinPick(decryptCipher: javax.crypto.Cipher, coins: List<Scanner.SpendableUtxo>, outputs: Int = 1,
                     mixScripts: List<ByteArray>? = null): com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.Pick {
        require(coins.isNotEmpty()) { "pick a coin" }
        val tag = coins.first().type
        require(coins.all { it.type == tag }) { "all the coins of a seat must be of one address type" }
        val xpub = xpubFor(tag) ?: error("no wallet")
        val type = typeFor(tag)
        val secret = seedVault.revealSecret(decryptCipher)
        val master = com.kilombino.pyblockwatch.crypto.Bip32Priv
            .fromSeed(com.kilombino.pyblockwatch.crypto.Bip39.toSeed(secret.words, secret.passphrase))
        val picked = coins.map { u ->
            val path = coinPath(u)
            com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.PickCoin(u.txid, u.vout, u.value,
                com.kilombino.pyblockwatch.crypto.Bip32Priv.derivePath(master, path), path)
        }
        // Held by the pool's saved state (coinjoinReservedTop), not marked used: if the pool is
        // cancelled before anything is posted, the next payment or pool takes them again.
        val mixIndex = nextUnused(0, tag)
        val changeIndex = nextUnused(1, tag)
        val parsed = com.kilombino.pyblockwatch.crypto.Bip32.parseExtendedPubKey(xpub)
        val mixes = mixScripts?.also { m ->
            require(m.size == outputs) { "this pool gives $outputs mixed outputs each" }
            require(m.all { com.kilombino.pyblockwatch.coinjoin.CoinjoinTx.isType(it, type) }) { "the mixed outputs are not of the pool's address type" }
        } ?: (0 until outputs).map { j ->
            com.kilombino.pyblockwatch.crypto.Address.scriptPubKey(
                com.kilombino.pyblockwatch.crypto.Bip32.derivePath(parsed, 0, mixIndex + j).pubkey(), type)
        }
        return com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.Pick(picked, type, mixes, changeScriptPubKey(changeIndex, tag))
    }

    /**
     * A coin of exactly [target] sats for a pool, sent to a fresh address of this wallet
     * (reserved on both chains). It goes through the normal send review: the draft is left
     * in sendPhase for the screen to show and confirm with the fingerprint.
     */
    /** A coin that came out of a coinjoin: kept by this wallet, or recognised on the chain ([checkMixed]). */
    /** Outpoints seated in a coinjoin pool still waiting for people (spendable; sending leaves the pool). */
    fun seatedInWaitingPools(): Set<String> =
        if (_state.value.selected != Chain.BLAKE2B) emptySet()
        else com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.waitingOutpoints(getApplication()).keys

    fun isMixedCoin(u: Scanner.SpendableUtxo): Boolean {
        val ctx = getApplication<android.app.Application>()
        return with(com.kilombino.pyblockwatch.crypto.Hashes) { coinScript(u).toHex() } in com.kilombino.pyblockwatch.coinjoin.MixedCoins.scripts(ctx) ||
            com.kilombino.pyblockwatch.coinjoin.MixedCoins.known(ctx, "${u.txid}:${u.vout}") == true
    }

    /** Asks the chain about the coins this wallet does not remember as mixed (once per coin). */
    private suspend fun checkMixed(coins: List<Scanner.SpendableUtxo>) {
        val ctx = getApplication<android.app.Application>()
        val ask = coins.filter { !isMixedCoin(it) && com.kilombino.pyblockwatch.coinjoin.MixedCoins.known(ctx, "${it.txid}:${it.vout}") == null }
        if (ask.isEmpty()) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val e = store.endpoint(Chain.BLAKE2B)
                val c = com.kilombino.pyblockwatch.chain.ElectrumClient(e, store.pinnedFingerprint(e))
                try {
                    c.connect()
                    for (u in ask) runCatching {
                        com.kilombino.pyblockwatch.coinjoin.MixedCoins.remember(ctx, "${u.txid}:${u.vout}",
                            com.kilombino.pyblockwatch.coinjoin.MixedCoins.looksMixed(c.transaction(u.txid), u.vout))
                    }
                } finally { c.close() }
            }
        }
    }

    /**
     * Confirmed coins of either kind, not promised to a round: what an exact coin can be paid
     * with. Mixed ones are listed too ([isMixedCoin]); the screen warns before they are used and
     * only [mixedToo] = false leaves them out (the automatic pick never takes them).
     */
    suspend fun exactCoinSources(mixedToo: Boolean = true): List<Scanner.SpendableUtxo> {
        val mixed = com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.states.value.flatMap { st -> st.mixScripts.map { it.toList() } }.toSet()
        val cs = _state.value.chains[Chain.BLAKE2B] ?: return emptyList()
        val endpoint = store.endpoint(Chain.BLAKE2B)
        // One look at the server for every address, bc1q and bc1p alike.
        val u = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            scanner.gatherUtxos(cs.rows.filter { it.isUsed }, endpoint, store.pinnedFingerprint(endpoint))
        }
        // Nor a coin seated in a pool still waiting for people: paying the exact coin with it would drop that seat.
        val seated = com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.waitingOutpoints(getApplication()).keys
        val coins = notInCoinjoin(Chain.BLAKE2B, u).filter { it.height > 0 && "${it.txid}:${it.vout}" !in seated }
        checkMixed(coins)
        return coins.filter { mixedToo || (coinScript(it).toList() !in mixed && !isMixedCoin(it)) }.sortedByDescending { it.value }
    }

    /** "Taproot" or "SegWit": which kind of coin [u] is. */
    fun coinKind(u: Scanner.SpendableUtxo): String = if (typeFor(u.type) == ScriptType.P2TR) "Taproot" else "SegWit"

    /**
     * The pool a perfect coin is for, set by the coinjoin screen before [prepareExactCoin]: once the
     * payment to ourselves is signed, the coin's key goes to [com.kilombino.pyblockwatch.coinjoin.PerfectCoin],
     * which joins (or opens) the pool by itself at the first confirmation.
     */
    class ExactFor(val poolId: String?, val poolPassword: String?, val create: org.json.JSONObject?, val terms: org.json.JSONObject? = null,
                   /** Where the mixed outputs go when not to this wallet (watch-only, Jam): found before the payment. */
                   val destScripts: List<ByteArray>? = null) {
        fun withDest(scripts: List<ByteArray>?) = ExactFor(poolId, poolPassword, create, terms, scripts)
    }
    @Volatile var exactFor: ExactFor? = null
    /** The perfect coin in the review: its address index, account and amount. */
    private var exactPending: Triple<Int, ScriptType?, Long>? = null

    fun prepareExactCoin(target: Long, feeRate: Double, tag: ScriptType? = null, selected: List<Scanner.SpendableUtxo> = emptyList()) {
        val xpub = xpubFor(tag) ?: return
        // Switching chain resets the send (and with it the pool this coin is for): keep it.
        val forPool = exactFor
        if (_state.value.selected != Chain.BLAKE2B) select(Chain.BLAKE2B)
        exactFor = forPool
        viewModelScope.launch {
            runCatching {
                // Never pay for it with an output that came out of a coinjoin: spending it
                // together with other coins would undo the mix.
                val mixed = com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.states.value.flatMap { st -> st.mixScripts.map { it.toList() } }.toSet()
                // Paid with confirmed coins of either kind (the ones chosen, or the biggest first);
                // the exact coin itself is of the pool's type.
                // Picked by hand, a mixed coin may pay for it (the screen warned); picked for the user, never.
                val coins = exactCoinSources(mixedToo = false)
                // Every ticked coin goes in, as coin control says; with none ticked, the biggest
                // first until they cover it.
                val pick = mutableListOf<Scanner.SpendableUtxo>(); var sum = 0L
                if (selected.isNotEmpty()) { pick += selected; sum = selected.sumOf { it.value } }
                else for (u in coins) {
                    pick += u; sum += u.value
                    if (sum >= target + estimateFee(pick.size, listOf(ByteArray(34), ByteArray(34)), feeRate, taprootIn(pick))) break
                }
                require(sum >= target + estimateFee(pick.size, listOf(ByteArray(34)), feeRate, taprootIn(pick))) {
                    "Not enough coins that were never mixed to make ${target} sats (mixed outputs are kept apart)."
                }
                // The pool's coin is of the pool's type, whichever kind of address the wallet hands out.
                val i = nextUnused(0, tag); store.noteUsedTop(xpub, 0, i + 1, typeFor(tag))
                val to = receiveAddress(i, tag)?.first ?: error("no address")
                exactPending = Triple(i, tag, target)
                _state.update { it.copy(utxos = (coins + selected).distinctBy { c -> "${c.txid}:${c.vout}" }, utxosChain = Chain.BLAKE2B) }
                prepareSend(to, target, feeRate, pick)
            }.onFailure { e -> _state.update { it.copy(sendPhase = SendPhase.Failed(e.message ?: "Could not prepare the coin.")) } }
        }
    }

    /**
     * Hands the perfect coin just sent to [com.kilombino.pyblockwatch.coinjoin.PerfectCoin], with
     * the seat's mixed-output and change addresses reserved now (marked used, so nothing else
     * hands them out meanwhile).
     */
    private fun expectPerfectCoin(txid: String, draft: SendDraft, index: Int, tag: ScriptType?, value: Long, key: java.math.BigInteger) {
        val f = exactFor ?: return
        val xpub = xpubFor(tag) ?: return
        val type = typeFor(tag)
        val parsed = com.kilombino.pyblockwatch.crypto.Bip32.parseExtendedPubKey(xpub)
        val script = com.kilombino.pyblockwatch.crypto.Address.scriptPubKey(
            com.kilombino.pyblockwatch.crypto.Bip32.derivePath(parsed, 0, index).pubkey(), type)
        val vout = draft.outputs.indexOfFirst { it.scriptPubKey.contentEquals(script) && it.value == value }
        require(vout >= 0) { "the perfect coin is not in the transaction" }
        // One fresh address per mixed output the pool gives each person (v4: several).
        val outputs = f.create?.optInt("outputs", 1) ?: f.terms?.optInt("outputs", 1) ?: 1
        // To another wallet (found before the payment), or fresh addresses of this one.
        val mixes = f.destScripts?.takeIf { it.size == outputs } ?: run {
            val mixIndex = maxOf(nextUnused(0, tag), index + 1)
            store.noteUsedTop(xpub, 0, mixIndex + outputs, type)
            (0 until outputs).map { j ->
                com.kilombino.pyblockwatch.crypto.Address.scriptPubKey(
                    com.kilombino.pyblockwatch.crypto.Bip32.derivePath(parsed, 0, mixIndex + j).pubkey(), type)
            }
        }
        // One past what the payment itself may have used for its own change.
        val changeIndex = nextUnused(1, tag) + 1
        store.noteUsedTop(xpub, 1, changeIndex + 1, type)
        com.kilombino.pyblockwatch.coinjoin.PerfectCoin.expect(getApplication(),
            com.kilombino.pyblockwatch.coinjoin.PerfectCoin.Waiting(txid, vout, value, type,
                "m/${Scanner.purposeFor(type)}'/0'/0'/0/$index", script, mixes, changeScriptPubKey(changeIndex, tag),
                f.poolId, f.poolPassword, f.create, terms = f.terms), key)
    }

    /** True when [address] is one of this wallet's own (used so far, plus the gap). */
    fun isOwnAddress(address: String): Boolean {
        val a = address.trim()
        // The Ark wallet's own deposit address is ours too, not a contact.
        if (runCatching { com.kilombino.pyblockwatch.ark.Ark.isOwnAddress(getApplication(), a) }.getOrDefault(false)) return true
        if (_state.value.chains.values.any { cs -> cs.rows.any { it.address == a } }) return true
        return listOf(null, ScriptType.P2TR).any { tag ->
            val xpub = xpubFor(tag) ?: return@any false
            runCatching {
                val parsed = com.kilombino.pyblockwatch.crypto.Bip32.parseExtendedPubKey(xpub)
                (0..1).any { ci ->
                    val branch = com.kilombino.pyblockwatch.crypto.Bip32.deriveChild(parsed, ci)
                    (0 until store.usedTop(xpub, ci, typeFor(tag)) + store.gapLimit).any { i ->
                        com.kilombino.pyblockwatch.crypto.Address.encode(
                            com.kilombino.pyblockwatch.crypto.Bip32.deriveChild(branch, i).pubkey(), typeFor(tag)) == a
                    }
                }
            }.getOrDefault(false)
        }
    }

    /** The private keys of a seat's coins (paths in order), from one unlock of the seed. */
    fun coinjoinKeys(decryptCipher: javax.crypto.Cipher, coinPaths: List<String>): List<java.math.BigInteger> {
        val secret = seedVault.revealSecret(decryptCipher)
        val master = com.kilombino.pyblockwatch.crypto.Bip32Priv
            .fromSeed(com.kilombino.pyblockwatch.crypto.Bip39.toSeed(secret.words, secret.passphrase))
        return coinPaths.map { com.kilombino.pyblockwatch.crypto.Bip32Priv.derivePath(master, it).key }
    }

    /** The coin's private key again, for signing the round at the end. */
    fun coinjoinKey(decryptCipher: javax.crypto.Cipher, coinPath: String): java.math.BigInteger {
        val secret = seedVault.revealSecret(decryptCipher)
        val master = com.kilombino.pyblockwatch.crypto.Bip32Priv
            .fromSeed(com.kilombino.pyblockwatch.crypto.Bip39.toSeed(secret.words, secret.passphrase))
        return com.kilombino.pyblockwatch.crypto.Bip32Priv.derivePath(master, coinPath).key
    }

    /** [update], but only while [xpub] is still the wallet on screen (a switch drops late results). */
    private fun updateIf(xpub: String, chain: Chain, f: (ChainState) -> ChainState) {
        if (_state.value.xpub == xpub) update(chain, f)
    }

    private fun update(chain: Chain, f: (ChainState) -> ChainState) {
        _state.update { s ->
            s.copy(chains = s.chains + (chain to f(s.chains[chain] ?: ChainState())))
        }
    }

    private companion object {
        /**
         * Before 0.20 the one wallet lived in the "hot" slot even when it was only an xpub.
         * Such a wallet moves to the watch-only slot; returns the wallet to show.
         */
        fun migrateWallets(app: Application): String {
            val g = Store(app)
            val vault = com.kilombino.pyblockwatch.data.SeedVault(app)
            val watch = Store(app, Store.WATCH)
            if (!vault.hasSeed() && g.xpub != null && watch.xpub == null) {
                watch.xpub = g.xpub; watch.label = g.label; watch.scriptType = g.scriptType
                g.xpub = null
                g.activeWallet = Store.WATCH
            }
            val prefs = app.getSharedPreferences("pyblockwatch", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("watch_only_view", false)) { prefs.edit().remove("watch_only_view").apply() }
            return when {
                g.activeWallet == Store.WATCH && watch.xpub != null -> Store.WATCH
                vault.hasSeed() -> Store.HOT
                watch.xpub != null -> Store.WATCH
                else -> Store.HOT
            }
        }

        /** Foreground auto-refresh cadence, and the countdown the UI shows. */
        /** Below this, a change output costs more to spend later than it is worth — fold it into fee. */
        const val DUST_SATS = 294L

    }
}

/**
 * The smallest standard output for [script] (Bitcoin Core's dust limit at 3 sat/vB):
 * 294 for bc1q, 330 for bc1p and P2WSH, 540 for P2SH, 546 for P2PKH. An output under
 * it is refused by the network, so change below it goes to the fee instead.
 */
internal fun dustFor(script: ByteArray): Long = when {
    script.size == 22 && script[0] == 0.toByte() -> 294L               // P2WPKH
    script.size == 34 && (script[0] == 0x51.toByte() || script[0] == 0.toByte()) -> 330L // P2TR, P2WSH
    script.size == 23 && script[0] == 0xa9.toByte() -> 540L            // P2SH
    else -> 546L
}
