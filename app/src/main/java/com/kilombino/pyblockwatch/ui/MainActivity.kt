package com.kilombino.pyblockwatch.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.data.AddressRow
import com.kilombino.pyblockwatch.data.TxConf
import com.kilombino.pyblockwatch.data.WatchService

class MainActivity : FragmentActivity() {

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result handled by the switch state itself */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PyBlockWatchTheme {
                val vm: WalletViewModel = viewModel()
                val state by vm.state.collectAsState()
                // Notifications are on by default: start the watcher (and ask for the
                // POST_NOTIFICATIONS permission on Android 13+) as soon as there is a wallet.
                LaunchedEffect(state.hasWallet, state.notificationsEnabled) {
                    if (state.hasWallet && state.notificationsEnabled) toggleNotifications(vm, true)
                }
                Box(Modifier.fillMaxSize().background(Ink)) {
                    if (state.showWallet) {
                        // Simple / Advanced: chosen once, switchable from the top of either screen.
                        when (state.uiMode) {
                            null -> ModeChooser(vm)
                            "simple" -> SimpleScreen(state, vm)
                            else -> WalletScreen(
                                state = state, vm = vm,
                                onToggleNotifications = { on -> toggleNotifications(vm, on) },
                            )
                        }
                    } else {
                        OnboardingScreen(state, vm)
                    }
                }
            }
        }
    }

    private fun toggleNotifications(vm: WalletViewModel, enable: Boolean) {
        if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.setNotificationsEnabled(enable)
        val intent = Intent(this, WatchService::class.java)
        if (enable) ContextCompat.startForegroundService(this, intent) else stopService(intent)
    }
}

// ---------------------------------------------------------------- onboarding

@Composable
private fun OnboardingScreen(state: UiState, vm: WalletViewModel) {
    var mode by remember { mutableStateOf("home") }
    when (mode) {
        "dice" -> { DiceScreen(vm) { mode = "home" }; return }
        "restore" -> { RestoreScreen(vm) { mode = "home" }; return }
    }

    var xpub by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) showScanner = true }

    if (showScanner) {
        QrScannerDialog(
            onResult = { raw ->
                xpub = Regex("(?:[xyz]pub)[1-9A-HJ-NP-Za-km-z]+").find(raw)?.value ?: raw.trim()
                showScanner = false
                if (state.inputError != null) vm.clearError()
            },
            onDismiss = { showScanner = false },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        if (state.hasWallet) {
            TextButton(onClick = { vm.endSetup() }) {
                Text("← back to wallet", color = TextSoft, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Kilombino", style = MaterialTheme.typography.displayLarge, color = Purple)
        Text("BITCOIN-BLAKE2b WALLET", style = MaterialTheme.typography.titleLarge, color = TextSoft)
        if (state.hasWallet) {
            Explain("Creating or importing a wallet here REPLACES the current one. Your coins are " +
                "safe on-chain; make sure you still have this wallet's backup before switching.")
        }

        Panel(accent = Purple) {
            SectionLabel("A spending wallet")
            Spacer(Modifier.height(8.dp))
            Explain(
                "Create a wallet you can send from. Its seed is generated on THIS phone — roll " +
                    "real dice, SeedSigner-style — and stored encrypted, unlocked only by your " +
                    "fingerprint or PIN when you spend. Native SegWit (bc1q) by default."
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { mode = "dice" },
                    colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f),
                ) { Text("NEW (DICE)", style = MaterialTheme.typography.titleMedium) }
                Button(
                    onClick = { mode = "restore" },
                    colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = TextMain),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f),
                ) { Text("RESTORE", style = MaterialTheme.typography.titleMedium) }
            }
        }

        Panel(accent = Orange) {
            SectionLabel("Or watch only", Orange)
            Spacer(Modifier.height(8.dp))
            Explain(
                "Paste an extended PUBLIC key (xpub/ypub/zpub) to watch balances without any way " +
                    "to spend. Careful: an xpub reveals every address you will ever use — keep it " +
                    "like a bank statement."
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = xpub,
                onValueChange = { xpub = it; if (state.inputError != null) vm.clearError() },
                label = { Text("xpub / ypub / zpub", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodySmall,
                isError = state.inputError != null,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            state.inputError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Bad) }

            TextButton(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                ) showScanner = true else cameraPermission.launch(Manifest.permission.CAMERA)
            }) { Text("📷  Scan a QR with the camera", color = Orange,
                      style = MaterialTheme.typography.bodySmall) }

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Name (optional)", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { vm.setXpub(xpub, label) },
                enabled = xpub.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("WATCH BOTH CHAINS", style = MaterialTheme.typography.titleMedium) }
        }

        Explain(
            "Either way, the same keys exist on both forks: the BLAKE2b chain and the classic " +
                "SHA-256 one share a genesis and an address system, but balances diverged at the " +
                "fork. You will see the two figures separately."
        )
    }
}

// ---------------------------------------------------------------- wallet

@Composable
private fun WalletScreen(state: UiState, vm: WalletViewModel, onToggleNotifications: (Boolean) -> Unit) {
    val chain = state.selected
    val cs = state.current
    val accent by animateColorAsState(Color(chain.accent), tween(400), label = "accent")
    var showSettings by remember { mutableStateOf(false) }
    var showSend by remember { mutableStateOf(false) }
    var showReceive by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(28.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("KILOMBINO", style = MaterialTheme.typography.titleMedium, color = accent)
                Text(
                    state.label.ifBlank { if (state.isHot) "spending wallet" else "watch-only wallet" },
                    style = MaterialTheme.typography.bodySmall, color = TextFaint,
                )
            }
            TextButton(onClick = { vm.setUiMode("simple") }) {
                Text("simple", style = MaterialTheme.typography.bodySmall, color = TextSoft)
            }
            TextButton(onClick = { showSettings = !showSettings }) {
                Text(if (showSettings) "close" else "settings",
                     style = MaterialTheme.typography.bodySmall, color = TextSoft)
            }
        }

        ChainSwitcher(state.selected, state.chains, vm::select)

        BalanceCard(chain, cs, accent, state.scriptType, state.secondsUntilRefresh)

        when {
            showSend -> SendSheet(vm, accent) { showSend = false }
            showReceive -> ReceiveSheet(vm, accent) { showReceive = false }
            else -> {
                // Receive works for any wallet; Send only when a seed is present.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showReceive = true },
                        colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = accent),
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f),
                    ) { Text("RECEIVE", style = MaterialTheme.typography.titleMedium) }
                    if (state.isHot) {
                        Button(
                            onClick = { vm.resetSend(); showSend = true },
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                            shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f),
                        ) { Text("SEND", style = MaterialTheme.typography.titleMedium) }
                    }
                }
                if (!state.isHot) {
                    Button(
                        onClick = { vm.startSetup() },
                        colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = accent),
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                    ) { Text("＋  CREATE A SPENDING WALLET", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }

        ScanStatus(cs, accent, onRetry = { vm.scan(chain) })

        if (cs.transactions.isNotEmpty()) MovementsCard(cs.transactions, accent)

        if (cs.fingerprintChanged) {
            Panel(accent = Bad) {
                SectionLabel("The server certificate has CHANGED", Bad)
                Spacer(Modifier.height(8.dp))
                Explain(
                    "The fingerprint does not match the one we first saw. It may be a legitimate " +
                        "change (a renewed certificate) or someone in the middle. " +
                        "Do not accept it without checking through another channel."
                )
                Spacer(Modifier.height(6.dp))
                cs.fingerprint?.let {
                    SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall, color = Bad) }
                }
                TextButton(onClick = { vm.trustCurrentCertificate(chain) }) {
                    Text("I have checked the fingerprint: trust", color = Warn,
                         style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Reveal(showSettings) {
            SettingsPanel(state, vm, accent, onToggleNotifications) { vm.forget() }
        }

        if (cs.rows.isNotEmpty()) AddressList(cs.rows, accent)

        Panel(accent = accent) {
            SectionLabel("How your coins are found", accent)
            Spacer(Modifier.height(8.dp))
            Explain(
                "Your xpub does not store a list of addresses: it generates them. The app derives " +
                    "m/0/0, m/0/1, m/0/2… and asks the server about each. When it finds " +
                    "${'$'}{20} empty in a row, it assumes there are no more and stops. That is " +
                    "the «gap limit», and it is what the ring above draws while it scans."
            )
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun ChainSwitcher(selected: Chain, chains: Map<Chain, ChainState>, onSelect: (Chain) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelSoft).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Chain.entries.forEach { c ->
            val active = c == selected
            val bg by animateColorAsState(
                if (active) Color(c.accent).copy(alpha = 0.18f) else Color.Transparent,
                tween(300), label = "tabbg",
            )
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(bg)
                    .clickable { onSelect(c) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    c.display,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (active) Color(c.accent) else TextFaint,
                )
                val st = chains[c]
                val sats = st?.total ?: 0L
                Text(
                    if (st?.phase is ScanPhase.Complete) "${groupSats(sats)} sats" else "…",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active) TextSoft else TextFaint,
                )
            }
        }
    }
}

@Composable
private fun BalanceCard(
    chain: Chain, cs: ChainState, accent: Color, scriptType: ScriptType?, secondsUntilRefresh: Int,
) {
    Panel(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("${chain.display} balance", accent)
            Spacer(Modifier.weight(1f))
            // A visible countdown to the next auto-refresh, so the wallet reads as live.
            if (cs.phase is ScanPhase.Complete) {
                Text("↻ ${secondsUntilRefresh}s", style = MaterialTheme.typography.bodySmall,
                     color = TextFaint)
                Spacer(Modifier.width(8.dp))
                PulseDot(Good, 7)
            }
        }
        Spacer(Modifier.height(6.dp))
        // Sats is the primary figure — an exact integer count, never rounded to bitcoin.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(groupSats(cs.total), style = MaterialTheme.typography.displayLarge, color = accent)
            Spacer(Modifier.width(8.dp))
            Text("sats", style = MaterialTheme.typography.titleLarge, color = accent.copy(alpha = 0.7f))
        }
        Text("%.8f ₿".format(cs.total / 100_000_000.0),
             style = MaterialTheme.typography.bodySmall, color = TextFaint)

        if (cs.unconfirmed != 0L) {
            Spacer(Modifier.height(4.dp))
            Text("unconfirmed: ${groupSats(cs.unconfirmed)} sats",
                 style = MaterialTheme.typography.bodySmall, color = Warn)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "${cs.usedAddresses} used addresses · height ${cs.height}",
            style = MaterialTheme.typography.bodySmall, color = TextSoft,
        )
        scriptType?.let {
            Text("${it.label} — ${it.explain}",
                 style = MaterialTheme.typography.bodySmall, color = TextFaint)
        }
        Spacer(Modifier.height(6.dp))
        Explain(chain.blurb)
    }
}

@Composable
private fun ScanStatus(cs: ChainState, accent: Color, onRetry: () -> Unit) {
    Panel(accent = accent) {
        AnimatedContent(
            targetState = cs.phase,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
            label = "phase",
        ) { phase ->
            when (phase) {
                is ScanPhase.Idle -> Text("idle", style = MaterialTheme.typography.bodySmall, color = TextFaint)

                is ScanPhase.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(accent); Spacer(Modifier.width(10.dp))
                    Column {
                        Text("connecting to ${phase.endpoint}",
                             style = MaterialTheme.typography.bodyMedium, color = accent)
                        Explain("The first TLS handshake with Frigate can take up to 40s.")
                    }
                }

                is ScanPhase.Scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
                    GapRing(phase.gapUsed, phase.gapLimit, accent)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        DerivationTicker(phase.path, accent)
                        Text(
                            if (phase.chainIndex == 0) "receive chain" else "change chain",
                            style = MaterialTheme.typography.bodySmall, color = TextFaint,
                        )
                        Text("${phase.gapUsed}/${phase.gapLimit} empty in a row",
                             style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    }
                }

                is ScanPhase.Complete -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot(Good); Spacer(Modifier.width(10.dp))
                        Text("scan complete", style = MaterialTheme.typography.bodyMedium, color = Good)
                    }
                    cs.server?.let {
                        Spacer(Modifier.height(6.dp))
                        Text("server: $it", style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    }
                    cs.fingerprint?.let {
                        Text("pinned fingerprint: ${it.take(16)}…",
                             style = MaterialTheme.typography.bodySmall, color = TextFaint)
                    }
                }

                is ScanPhase.Error -> Column {
                    Text("could not complete", style = MaterialTheme.typography.bodyMedium, color = Bad)
                    Spacer(Modifier.height(4.dp))
                    Explain(phase.message)
                    TextButton(onClick = onRetry) {
                        Text("retry", color = accent, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressList(rows: List<AddressRow>, accent: Color) {
    Panel(accent = accent) {
        SectionLabel("addresses with activity", accent)
        Spacer(Modifier.height(10.dp))
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(shortAddress(row.address),
                         style = MaterialTheme.typography.bodyMedium, color = TextMain)
                    Text(
                        "${row.path}  ·  ${if (row.chainIndex == 0) "receive" else "change"}",
                        style = MaterialTheme.typography.bodySmall, color = TextFaint,
                    )
                }
                Text(
                    if (row.total > 0) "${groupSats(row.total)} sats" else "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.total > 0) accent else TextFaint,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    state: UiState,
    vm: WalletViewModel,
    accent: Color,
    onToggleNotifications: (Boolean) -> Unit,
    onForget: () -> Unit,
) {
    val chain = state.selected
    var host by remember(chain) { mutableStateOf(vm.endpointFor(chain).let { if (it.isCustom) it.host else "" }) }
    var port by remember(chain) { mutableStateOf(vm.endpointFor(chain).port.toString()) }

    Panel(accent = accent) {
        SectionLabel("settings", accent)
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Tell me when the balance changes",
                     style = MaterialTheme.typography.bodyMedium, color = TextMain)
                Explain("A service checks the already-discovered addresses every 15 minutes. " +
                    "No push server, no Google: your phone does the lookup itself.")
            }
            Switch(
                checked = state.notificationsEnabled,
                onCheckedChange = onToggleNotifications,
                colors = SwitchDefaults.colors(checkedThumbColor = accent),
            )
        }

        Spacer(Modifier.height(14.dp))
        Text("Address type (derivation)",
             style = MaterialTheme.typography.bodyMedium, color = TextMain)
        Explain("How the keys are read from your xpub. BIP84 (bc1q) by default. " +
            "Change it if your wallet uses another format; both chains are rescanned.")
        Spacer(Modifier.height(4.dp))
        DerivationSelector(state.scriptType, accent) { vm.setScriptType(it) }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Gap limit: ${state.gapLimit}",
                     style = MaterialTheme.typography.bodyMedium, color = TextMain)
                Explain("How many empty addresses in a row are checked before stopping. " +
                    "Raise it if you use many addresses; lower it to scan faster.")
            }
            TextButton(onClick = { vm.setGapLimit(state.gapLimit - 5) }) {
                Text("−5", color = accent, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { vm.setGapLimit(state.gapLimit + 5) }) {
                Text("+5", color = accent, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(14.dp))
        if (chain.allowsCustomNode) {
            Text("Your own ${chain.display} node",
                 style = MaterialTheme.typography.bodyMedium, color = TextMain)
            Explain("Leave empty to use ${chain.defaultHost}:${chain.defaultPort}.")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("host", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true, modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("port", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true, modifier = Modifier.weight(1f),
                )
            }
            TextButton(onClick = {
                vm.setCustomNode(chain, host.ifBlank { null }, port.toIntOrNull() ?: chain.defaultPort)
            }) { Text("apply and rescan", color = accent, style = MaterialTheme.typography.bodySmall) }
        } else {
            Explain(
                "The SHA-256 chain is lookup-only: it finds your coins from the xpub, so it " +
                    "offers no custom node."
            )
        }

        Spacer(Modifier.height(10.dp))
        SelectionContainer {
            Text(
                state.xpub?.let { "${it.take(24)}…${it.takeLast(10)}" } ?: "",
                style = MaterialTheme.typography.bodySmall, color = TextFaint,
            )
        }
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = { vm.startSetup() },
            colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = accent),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.isHot) "SWITCH / NEW WALLET" else "CREATE A SPENDING WALLET",
                 style = MaterialTheme.typography.titleMedium) }
        TextButton(onClick = onForget) {
            Text("forget this wallet", color = Bad, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DerivationSelector(current: ScriptType?, accent: Color, onSelect: (ScriptType) -> Unit) {
    fun purpose(t: ScriptType) = when (t) {
        ScriptType.P2PKH -> 44; ScriptType.P2SH_P2WPKH -> 49
        ScriptType.P2WPKH -> 84; ScriptType.P2TR -> 86
    }
    Column {
        ScriptType.entries.forEach { t ->
            val sel = t == current
            TextButton(onClick = { onSelect(t) }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    (if (sel) "● " else "○ ") + "BIP${purpose(t)} · ${t.label}  (m/${purpose(t)}'/0'/0')",
                    color = if (sel) accent else TextMain,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun MovementsCard(txs: List<TxConf>, accent: Color) {
    Panel(accent = accent) {
        SectionLabel("movements · confirmations", accent)
        Spacer(Modifier.height(8.dp))
        txs.take(15).forEach { t ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${t.txid.take(8)}…${t.txid.takeLast(6)}",
                    style = MaterialTheme.typography.bodySmall, color = TextSoft,
                    modifier = Modifier.weight(1f),
                )
                if (t.pending) {
                    Text("in mempool · 0 conf",
                         style = MaterialTheme.typography.bodySmall, color = Warn)
                } else {
                    Text(
                        "${t.confirmations} conf" + if (t.confirmations >= 6) "  ✓" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (t.confirmations >= 6) Good else TextSoft,
                    )
                }
            }
        }
        if (txs.size > 15) {
            Text("… and ${txs.size - 15} more",
                 style = MaterialTheme.typography.bodySmall, color = TextFaint)
        }
    }
}
