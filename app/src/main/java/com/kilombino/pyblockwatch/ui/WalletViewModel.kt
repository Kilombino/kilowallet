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
)

/** Where the send flow is, so the UI can move from editing → review → broadcast → done. */
sealed interface SendPhase {
    data object Editing : SendPhase
    data object Preparing : SendPhase
    data class Review(val draft: SendDraft) : SendPhase
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
    val gapLimit: Int = 20,
    val secondsUntilRefresh: Int = 30,
    val inputError: String? = null,
    val isHot: Boolean = false,
    val sendPhase: SendPhase = SendPhase.Editing,
    val setupMode: Boolean = false,
    val utxos: List<com.kilombino.pyblockwatch.data.Scanner.SpendableUtxo>? = null, // null = not loaded
    val utxosLoading: Boolean = false,
    // The chain the cached [utxos] were gathered for; null when none. Guards against a stale
    // in-flight fetch repopulating the coin picker with another chain's coins.
    val utxosChain: Chain? = null,
) {
    val current: ChainState get() = chains[selected] ?: ChainState()
    val hasWallet: Boolean get() = !xpub.isNullOrBlank()
    /** Show the wallet only when one exists AND the user is not in the middle of setup. */
    val showWallet: Boolean get() = hasWallet && !setupMode
}

class WalletViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)
    private val scanner = Scanner()
    private val notifier = Notifier(app)
    private val seedVault = com.kilombino.pyblockwatch.data.SeedVault(app)
    private val jobs = mutableMapOf<Chain, Job>()
    private var refreshJob: Job? = null

    /** True when this wallet holds an encrypted seed and can therefore sign/spend. */
    fun hasSeed(): Boolean = seedVault.hasSeed()
    /** A Keystore cipher to encrypt the seed; authorise it with BiometricPrompt first. */
    fun seedEncryptCipher() = seedVault.encryptCipher()
    /** A Keystore cipher to decrypt the seed; authorise it with BiometricPrompt first. */
    fun seedDecryptCipher() = seedVault.decryptCipher()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val xpub = store.xpub
        _state.update {
            it.copy(
                xpub = xpub,
                label = store.label,
                scriptType = store.scriptType,
                selected = store.lastChain,
                notificationsEnabled = store.notificationsEnabled,
                gapLimit = store.gapLimit,
                isHot = seedVault.hasSeed(),
            )
        }
        if (xpub != null) Chain.entries.forEach { scan(it) }
        startRefreshLoop()
    }

    /**
     * While the app is open, refresh the SELECTED chain every [REFRESH_SECONDS] seconds
     * with a visible countdown, so the user can see the wallet is live rather than wonder
     * whether it is stuck. Each refresh runs the same notification engine as the background
     * watcher, so a movement seen here fires the same alerts — just far sooner.
     */
    private fun startRefreshLoop() {
        refreshJob?.cancel()
        _state.update { it.copy(secondsUntilRefresh = REFRESH_SECONDS) }
        refreshJob = viewModelScope.launch {
            var remaining = REFRESH_SECONDS
            while (true) {
                delay(1_000)
                if (_state.value.xpub.isNullOrBlank()) { remaining = REFRESH_SECONDS; continue }
                remaining--
                if (remaining <= 0) {
                    refresh(_state.value.selected)
                    remaining = REFRESH_SECONDS
                }
                _state.update { it.copy(secondsUntilRefresh = remaining.coerceAtLeast(0)) }
            }
        }
    }

    /**
     * Silent balance refresh of the addresses already found for [chain] — no gap walk,
     * no "scanning" flicker. Falls back to a full scan if nothing has been found yet.
     * On success it feeds the fresh figures to [BalanceWatch], which decides whether the
     * change deserves a notification (mempool arrival, first confirmation, …).
     */
    fun refresh(chain: Chain) {
        val xpub = _state.value.xpub ?: return
        val cs = _state.value.chains[chain] ?: return
        if (cs.rows.isEmpty() || cs.phase !is ScanPhase.Complete) { scan(chain); return }
        viewModelScope.launch {
            runCatching {
                val endpoint = store.endpoint(chain)
                val pin = store.pinnedFingerprint(endpoint)
                // A SILENT gap-walk (no "scanning" flicker): unlike a plain balance refresh it
                // re-derives the branches, so a payment to a freshly handed-out receive address,
                // and the change address a spend just created, are DISCOVERED while the app is
                // open — the reason a restart used to be needed. Confirmations refresh too.
                var doneRows: List<AddressRow>? = null
                var doneTxs: List<TxConf> = emptyList()
                var tip = cs.height
                scanner.scan(xpub, chain, endpoint, pin, store.scriptType, store.gapLimit).collect { ev ->
                    when (ev) {
                        is ScanEvent.Connected ->
                            if (pin == null && ev.fingerprint != null) store.pinFingerprint(endpoint, ev.fingerprint)
                        is ScanEvent.Done -> { doneRows = ev.rows; doneTxs = ev.txs; tip = ev.height }
                        else -> {}
                    }
                }
                val rows = doneRows ?: return@runCatching
                val conf = rows.sumOf { it.confirmed }
                val unconf = rows.sumOf { it.unconfirmed }
                store.setLastBalance(chain, conf, unconf)
                if (store.notificationsEnabled) {
                    BalanceWatch.evaluate(store, notifier, chain, conf, unconf, doneTxs)
                }
                update(chain) {
                    it.copy(rows = rows, transactions = doneTxs, height = tip, phase = ScanPhase.Complete)
                }
            }
        }
    }

    /** Validate and store a pasted extended public key, then scan both chains. */
    fun setXpub(raw: String, label: String) {
        val trimmed = raw.trim().replace("\\s".toRegex(), "")
        val type = Scanner.scriptTypeOf(trimmed)
        if (type == null) {
            // Re-derive the real reason so the user sees something actionable.
            val why = runCatching { com.kilombino.pyblockwatch.crypto.Bip32.parseExtendedPubKey(trimmed) }
                .exceptionOrNull()?.message ?: "Not recognised as an xpub, ypub or zpub."
            _state.update { it.copy(inputError = why) }
            return
        }
        store.xpub = trimmed
        store.label = label
        // Default derivation: honour a specific prefix (ypub → nested, zpub → native),
        // but a plain xpub defaults to BIP-84 native segwit rather than legacy. The user
        // can still switch it afterwards with the type selector.
        val chosen = when (type) {
            ScriptType.P2SH_P2WPKH, ScriptType.P2WPKH -> type
            else -> ScriptType.P2WPKH
        }
        store.scriptType = chosen
        _state.update {
            it.copy(
                xpub = trimmed, label = label, scriptType = chosen, inputError = null,
                isHot = false, setupMode = false,
                chains = Chain.entries.associateWith { ChainState() },
            )
        }
        Chain.entries.forEach { scan(it) }
    }

    /** Change the gap limit (how deep the scan looks) and re-scan both chains. */
    fun setGapLimit(limit: Int) {
        val clamped = limit.coerceIn(5, 100)
        if (clamped == store.gapLimit) return
        store.gapLimit = clamped
        _state.update {
            it.copy(gapLimit = clamped, chains = Chain.entries.associateWith { ChainState() })
        }
        if (!_state.value.xpub.isNullOrBlank()) Chain.entries.forEach { scan(it) }
    }

    /** Change the address type (BIP-84/49/44/86) and re-scan both chains. */
    fun setScriptType(type: ScriptType) {
        if (type == store.scriptType) return
        store.scriptType = type
        _state.update {
            it.copy(scriptType = type, chains = Chain.entries.associateWith { ChainState() })
        }
        if (!_state.value.xpub.isNullOrBlank()) Chain.entries.forEach { scan(it) }
    }

    fun clearError() = _state.update { it.copy(inputError = null) }

    /** Open the wallet chooser (dice / restore / watch-only) even when a wallet already exists. */
    fun startSetup() = _state.update { it.copy(setupMode = true, inputError = null) }
    /** Leave the chooser without changing anything, back to the existing wallet. */
    fun endSetup() = _state.update { it.copy(setupMode = false) }

    fun select(chain: Chain) {
        val changed = chain != _state.value.selected
        store.lastChain = chain
        _state.update { it.copy(selected = chain) }
        if (changed) resetSend() // a real switch must not leave a stale review draft or coins on screen
        refresh(chain)          // fresh figures the moment you switch to a chain
        startRefreshLoop()      // restart the timer so it tracks the newly selected chain
    }

    fun forget() {
        jobs.values.forEach(Job::cancel); jobs.clear()
        store.clearWallet()
        seedVault.clear()
        _state.value = UiState(notificationsEnabled = store.notificationsEnabled)
    }

    fun endpointFor(chain: Chain): NodeEndpoint = store.endpoint(chain)

    fun setCustomNode(chain: Chain, host: String?, port: Int) {
        store.setCustomEndpoint(chain, host, port)
        scan(chain)
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        store.notificationsEnabled = enabled
        _state.update { it.copy(notificationsEnabled = enabled) }
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
        val xpub = _state.value.xpub ?: return
        jobs[chain]?.cancel()
        val endpoint = store.endpoint(chain)
        val pin = store.pinnedFingerprint(endpoint)

        jobs[chain] = viewModelScope.launch {
            val found = mutableListOf<AddressRow>()
            scanner.scan(xpub, chain, endpoint, pin, store.scriptType, store.gapLimit).collect { ev ->
                update(chain) { st ->
                    when (ev) {
                        is ScanEvent.Connecting ->
                            st.copy(phase = ScanPhase.Connecting(ev.endpoint), endpoint = ev.endpoint,
                                    rows = emptyList())
                        is ScanEvent.Connected -> {
                            // First sight of this server's certificate: pin it silently.
                            // A CHANGE is never auto-accepted — the UI asks.
                            if (pin == null && ev.fingerprint != null) store.pinFingerprint(endpoint, ev.fingerprint)
                            st.copy(server = ev.server, height = ev.height, fingerprint = ev.fingerprint,
                                    fingerprintChanged = ev.fingerprintChanged)
                        }
                        is ScanEvent.Deriving ->
                            st.copy(phase = ScanPhase.Scanning(ev.path, ev.chainIndex, 0, 20))
                        is ScanEvent.GapProgress ->
                            st.copy(phase = (st.phase as? ScanPhase.Scanning)
                                ?.copy(gapUsed = ev.consecutiveEmpty, gapLimit = ev.gapLimit) ?: st.phase)
                        is ScanEvent.Found -> {
                            found += ev.row
                            st.copy(rows = found.toList())
                        }
                        is ScanEvent.Done -> {
                            // Reset the notification baseline to what the user is now looking at,
                            // so the watcher only fires on genuinely new movement.
                            store.setLastBalance(
                                chain,
                                ev.rows.sumOf { it.confirmed },
                                ev.rows.sumOf { it.unconfirmed },
                            )
                            st.copy(
                                phase = ScanPhase.Complete, rows = ev.rows,
                                height = ev.height, transactions = ev.txs,
                            )
                        }
                        is ScanEvent.Failed -> st.copy(phase = ScanPhase.Error(ev.message))
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ hot wallet: create

    /**
     * Turn a freshly generated (or restored) mnemonic into a wallet: persist it encrypted
     * behind the just-authorised [encryptCipher], derive the account zpub, and hand that to
     * the same scanner a watch-only xpub uses. Native SegWit (BIP-84) by default.
     */
    fun createHotWallet(mnemonic: List<String>, encryptCipher: javax.crypto.Cipher, onError: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                seedVault.store(encryptCipher, mnemonic)
                val zpub = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val master = com.kilombino.pyblockwatch.crypto.Bip32Priv
                        .fromSeed(com.kilombino.pyblockwatch.crypto.Bip39.toSeed(mnemonic))
                    com.kilombino.pyblockwatch.crypto.Bip32Priv.accountXpub(master, purpose = 84, account = 0)
                }
                store.xpub = zpub
                store.label = "Hot wallet"
                store.scriptType = ScriptType.P2WPKH
                _state.update {
                    it.copy(
                        xpub = zpub, label = "Hot wallet", scriptType = ScriptType.P2WPKH,
                        isHot = true, inputError = null, setupMode = false,
                        chains = Chain.entries.associateWith { ChainState() },
                    )
                }
                Chain.entries.forEach { scan(it) }
            }.onFailure { e ->
                seedVault.clear()
                onError(e.message ?: "Could not create the wallet.")
            }
        }
    }

    // ------------------------------------------------------------------ hot wallet: send

    fun resetSend() = _state.update { it.copy(sendPhase = SendPhase.Editing, utxos = null, utxosChain = null) }

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
                }
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
    ) {
        val chain = _state.value.selected
        val cs = _state.value.chains[chain] ?: return
        val xpub = _state.value.xpub ?: return
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
                    else com.kilombino.pyblockwatch.crypto.Address.decodeToScriptPubKey(effectiveTo)
                require(amountSats > 0) { "Enter an amount." }
                val rate = feeRatePerVb.coerceIn(0.1, 1000.0)

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
                    }
                    require(utxos.isNotEmpty()) { "No spendable coins on this chain yet." }
                    val acc = mutableListOf<com.kilombino.pyblockwatch.data.Scanner.SpendableUtxo>()
                    var s = 0L
                    for (u in utxos.sortedByDescending { it.value }) {
                        acc += u; s += u.value
                        if (s >= amountSats + estimateFee(acc.size, 2, rate)) break
                    }
                    chosen = acc; sum = s
                }

                var fee = estimateFee(chosen.size, 2, rate)
                require(sum >= amountSats + fee) {
                    if (selected.isNotEmpty()) "The chosen coins don't cover the amount plus fee."
                    else "Not enough funds for the amount plus fee."
                }
                var change = sum - amountSats - fee

                val outputs = mutableListOf(
                    com.kilombino.pyblockwatch.crypto.TxBuilder.Output(toScript, amountSats),
                )
                if (change > DUST_SATS) {
                    val changeScript = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        changeScriptPubKey(xpub, nextChangeIndex(cs.rows))
                    }
                    outputs += com.kilombino.pyblockwatch.crypto.TxBuilder.Output(changeScript, change)
                } else {
                    fee = sum - amountSats // dust change folded into the fee
                    change = 0
                }
                val draft = SendDraft(toAddress, amountSats, fee, change, chosen, outputs, chain, silentRecipient)
                _state.update { it.copy(sendPhase = SendPhase.Review(draft)) }
            }.onFailure { e ->
                _state.update { it.copy(sendPhase = SendPhase.Failed(e.message ?: "Could not prepare the send.")) }
            }
        }
    }

    /** The most a sweep can send from [selected] (or all loaded UTXOs): their value minus a
     *  one-output fee. Used by the MAX button; 0 if the UTXOs aren't loaded yet. */
    fun maxSendable(feeRatePerVb: Double, selected: List<com.kilombino.pyblockwatch.data.Scanner.SpendableUtxo>): Long {
        val u = if (selected.isNotEmpty()) selected else _state.value.utxos ?: emptyList()
        if (u.isEmpty()) return 0
        val fee = estimateFee(u.size, 1, feeRatePerVb.coerceIn(0.1, 1000.0))
        return (u.sumOf { it.value } - fee).coerceAtLeast(0)
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
            val url = java.net.URL("https://cloudflare-dns.com/dns-query?name=$name&type=TXT")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                setRequestProperty("Accept", "application/dns-json")
                connectTimeout = 8000; readTimeout = 8000
            }
            val body = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                throw IllegalArgumentException("Could not look up $h.")
            } finally { conn.disconnect() }
            val answers = org.json.JSONObject(body).optJSONArray("Answer")
                ?: throw IllegalArgumentException("No payment record for $h.")
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

    /** The next unused receive index for the current chain — one past the highest used. */
    fun nextReceiveIndex(): Int {
        val rows = _state.value.current.rows
        return (rows.filter { it.chainIndex == 0 }.maxOfOrNull { it.index }?.plus(1)) ?: 0
    }

    /** Derive receive address [index] and its BIP-32 path, publicly from the xpub (no seed). */
    fun receiveAddress(index: Int): Pair<String, String>? {
        val xpub = _state.value.xpub ?: return null
        return runCatching {
            val parsed = com.kilombino.pyblockwatch.crypto.Bip32.parseExtendedPubKey(xpub)
            val pub = com.kilombino.pyblockwatch.crypto.Bip32.derivePath(parsed, 0, index).pubkey()
            val addr = com.kilombino.pyblockwatch.crypto.Address.encode(pub, store.scriptType)
            addr to "m/${Scanner.purposeFor(store.scriptType)}'/0'/0'/0/$index"
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
        viewModelScope.launch {
            runCatching {
                val purpose = Scanner.purposeFor(store.scriptType)
                val signed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val mnemonic = seedVault.reveal(decryptCipher)
                    val master = com.kilombino.pyblockwatch.crypto.Bip32Priv
                        .fromSeed(com.kilombino.pyblockwatch.crypto.Bip39.toSeed(mnemonic))
                    val inputs = draft.inputs.map { u ->
                        val node = com.kilombino.pyblockwatch.crypto.Bip32Priv
                            .derivePath(master, "m/$purpose'/0'/0'/${u.chainIndex}/${u.index}")
                        com.kilombino.pyblockwatch.crypto.TxBuilder.Input(
                            u.txid, u.vout, u.value, node.key, node.publicKey(),
                        )
                    }
                    // Silent payment: now that the input keys are known, compute the real Taproot
                    // output and swap it in for the placeholder at index 0.
                    val outputs = draft.silentRecipient?.let { sp ->
                        val spInputs = inputs.map {
                            com.kilombino.pyblockwatch.crypto.SilentPayment.Input(it.privateKey, it.txid, it.vout)
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
                refresh(chain)
            }.onFailure { e ->
                _state.update { it.copy(sendPhase = SendPhase.Failed(e.message ?: "The broadcast failed.")) }
            }
        }
    }

    /** Roughly a P2WPKH transaction's vbytes → fee in sats, rounded up. */
    private fun estimateFee(nIn: Int, nOut: Int, ratePerVb: Double): Long {
        val vbytes = 11.0 + 68.0 * nIn + 31.0 * nOut // overhead + inputs + outputs (segwit)
        return kotlin.math.ceil(vbytes * ratePerVb).toLong()
    }

    /** The next unused change index, so a spend's change goes to a fresh address. */
    private fun nextChangeIndex(rows: List<AddressRow>): Int =
        (rows.filter { it.chainIndex == 1 }.maxOfOrNull { it.index }?.plus(1)) ?: 0

    /** The change output's scriptPubKey, derived publicly from the account xpub (no seed needed). */
    private fun changeScriptPubKey(xpub: String, index: Int): ByteArray {
        val parsed = com.kilombino.pyblockwatch.crypto.Bip32.parseExtendedPubKey(xpub)
        val pub = com.kilombino.pyblockwatch.crypto.Bip32.derivePath(parsed, 1, index).pubkey()
        return com.kilombino.pyblockwatch.crypto.Address.scriptPubKey(pub, store.scriptType)
    }

    private fun update(chain: Chain, f: (ChainState) -> ChainState) {
        _state.update { s ->
            s.copy(chains = s.chains + (chain to f(s.chains[chain] ?: ChainState())))
        }
    }

    private companion object {
        /** Foreground auto-refresh cadence, and the countdown the UI shows. */
        const val REFRESH_SECONDS = 30
        /** Below this, a change output costs more to spend later than it is worth — fold it into fee. */
        const val DUST_SATS = 294L
    }
}
