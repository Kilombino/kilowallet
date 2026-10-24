package com.kilombino.pyblockwatch.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.kilombino.pyblockwatch.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.kilombino.pyblockwatch.crypto.ScriptType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.kilombino.pyblockwatch.chain.Chain

/**
 * HOT | WATCH | JM, top left: three different wallets on one phone (JM: a Jam wallet, read-only). The hot one is the
 * seed's; the watch-only one is a separate xpub, watched with its own notifications (one at
 * most). Going either way asks for the fingerprint; to hot with no seed it leads to
 * create/restore instead, and to watch-only the first time it asks for the xpub.
 */
@Composable
fun WalletModeSwitch(state: UiState, vm: WalletViewModel, accent: Color, modifier: Modifier = Modifier) {
    val activity = LocalContext.current as FragmentActivity
    val jvm: JamViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var setupWatch by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var setupJam by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (setupWatch) WatchWalletSetup(vm, accent) { setupWatch = false }
    if (setupJam) JamImportDialog(jvm, JamTeal, onImported = { setupJam = false; vm.viewJam() }, onClose = { setupJam = false })
    val hotOn = state.isHot && !state.jamView
    val watchOn = !state.isHot && !state.jamView
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(PanelSoft).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        // Short, so the bar keeps room for ⚙, ⏻ and the logo in every language.
        // Three wallets: the one on screen says its name, the other two only their emoji.
        @Composable fun seg(label: String, on: Boolean, onClick: () -> Unit) = Text(if (on) label else label.substringBefore(' '),
            style = MaterialTheme.typography.labelSmall,
            color = if (on) Ink else TextFaint,
            maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .background(if (on) accent else Color.Transparent)
                .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp))
        seg("🔥 HOT", hotOn) {
            when {
                hotOn -> {}
                state.hasSeed -> Biometric.confirm(activity, "Back to the hot wallet",
                    "Unlock to see and spend your hot wallet", onSuccess = { vm.viewAsHot() }, onError = {})
                else -> { vm.leaveJam(); vm.startSetup() }   // no hot wallet yet: create or restore one
            }
        }
        seg("👁 WATCH", watchOn) {
            // The watched wallet's balances and addresses are as private as the hot one's: the
            // fingerprint first, as going back to hot asks.
            if (!watchOn) Biometric.confirm(activity, "Open the watch-only wallet",
                "Unlock to see the watched wallet", onSuccess = { if (vm.hasWatchWallet()) vm.viewAsWatchOnly() else setupWatch = true },
                onError = {})
        }
        // The JoinMarket wallet (Jam, read-only): the fingerprint too, then its import the first time.
        seg("🫙 JM", state.jamView) {
            if (!state.jamView) Biometric.confirm(activity, "Open the JoinMarket wallet",
                "Unlock to see the JoinMarket wallet", onSuccess = { if (vm.hasJamWallet()) vm.viewJam() else setupJam = true },
                onError = {})
        }
    }
}

/** First time on WATCH-ONLY: what it is, and the xpub of the wallet to watch. */
@Composable
fun WatchWalletSetup(vm: WalletViewModel, accent: Color, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var xpub by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var picked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ScriptType?>(null) }
    var label by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var scan by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val state by vm.state.collectAsState()
    val askNotif = rememberNotificationPermission()
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) scan = true }
    if (scan) QrScannerDialog(onResult = { raw ->
        xpub = Regex("(?:[xyz]pub)[1-9A-HJ-NP-Za-km-z]+").find(raw)?.value ?: raw.trim(); scan = false
    }, onDismiss = { scan = false })
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Watch another wallet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Watch-only is a SECOND wallet, apart from your hot wallet: paste the extended public " +
                    "key (xpub / ypub / zpub) of another wallet to see its balance, get fresh addresses to " +
                    "receive, and be notified of its movements. It holds no keys: to send (BTC), it hands a PSBT to a separate signer such as Bitcoin Knots.",
                    style = MaterialTheme.typography.bodySmall)
                Text("One watch-only wallet at most, to keep the phone light. Your hot wallet stays as it is: " +
                    "switch back any time with your fingerprint.", style = MaterialTheme.typography.bodySmall, color = TextFaint)
                androidx.compose.material3.OutlinedTextField(value = xpub, onValueChange = { xpub = it; vm.clearError() },
                    label = { Text("xpub / ypub / zpub", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall, minLines = 2, isError = state.inputError != null)
                state.inputError?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
                XpubTypeChoice(xpub, picked, accent) { picked = it }
                androidx.compose.material3.TextButton(onClick = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scan = true
                    else camera.launch(Manifest.permission.CAMERA)
                }) { Text("📷  Scan a QR", color = accent, style = MaterialTheme.typography.bodySmall) }
                androidx.compose.material3.OutlinedTextField(value = label, onValueChange = { label = it },
                    label = { Text("Name (optional)", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall, singleLine = true)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = xpub.isNotBlank() && (picked != null || !xpubNeedsType(xpub)), onClick = {
                vm.setXpub(xpub, label.ifBlank { "Watch-only" }, picked.takeIf { xpubNeedsType(xpub) })
                if (vm.state.value.inputError == null) { askNotif(); onClose() }
            }) { Text("WATCH IT", color = accent) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )
}

/**
 * Watch-only wallet: its addresses, used ones (with their balance) and the next unused ones
 * to receive on, in derivation order.
 */
@Composable
fun WatchAddressesCard(state: UiState, vm: WalletViewModel, chain: Chain, accent: Color) {
    val cs = state.chains[chain] ?: return
    // Unused first (to receive on), then the used ones in derivation order: ten, or all on request.
    val used = cs.rows.filter { it.chainIndex == 0 }.sortedWith(compareBy({ it.type?.ordinal ?: -1 }, { it.index }))
    var open by remember { mutableStateOf<Pair<String, String>?>(null) } // address, its status line
    open?.let { (addr, status) ->
        TxDetailDialog(addr, accent, vm.explorerFor(chain), status = status,
            onSpeedUp = null, onClose = { open = null }, vm = vm, chain = chain, kind = "address")
    }
    // From just past the last used address: the ones a coinjoin round holds show as such, not skipped.
    val unused = remember(cs.rows) { vm.receiveSlots() }
    var all by remember { mutableStateOf(false) }
    Panel(accent = accent) {
        SectionLabel("Addresses · unused and used", accent)
        Spacer(Modifier.height(8.dp))
        unused.forEach { slot ->
            val what = if (slot.reserved) "reserved for a coinjoin" else "unused"
            Row(Modifier.fillMaxWidth().clickable { open = slot.address to "${slot.path} · receive · $what" }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(shortAddress(slot.address), style = MaterialTheme.typography.bodyMedium, color = if (slot.reserved) TextSoft else TextMain)
                    Text("#${slot.index} · $what", style = MaterialTheme.typography.bodySmall, color = if (slot.reserved) Warn else Good)
                }
            }
        }
        (if (all) used else used.take(10)).forEach { r ->
            Row(Modifier.fillMaxWidth().clickable { open = r.address to "${r.path} · receive · " + street("${groupSats(r.total)} sats") }
                    .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(shortAddress(r.address), style = MaterialTheme.typography.bodyMedium, color = TextSoft)
                    Text("#${r.index} · used", style = MaterialTheme.typography.bodySmall, color = TextFaint)
                }
                Text(if (r.total > 0) street(groupSats(r.total)) else "—", style = MaterialTheme.typography.bodySmall,
                    color = if (r.total > 0) accent else TextFaint)
            }
        }
        if (used.size > 10) androidx.compose.material3.TextButton(onClick = { all = !all }) {
            Text(if (all) "show fewer" else "show all ${used.size} used", color = accent, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A function that asks for the notification permission (Android 13+) when it is missing. */
@Composable
fun rememberNotificationPermission(): () -> Unit {
    val ctx = LocalContext.current
    val activity = ctx as FragmentActivity
    val prefs = ctx.getSharedPreferences("pyblockwatch", android.content.Context.MODE_PRIVATE)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // After two refusals Android no longer shows the question: the only way left is the
            // app's notification settings, so the user is taken there (they asked for it).
            val asked = prefs.getBoolean("notif_asked", false)
            if (asked && !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }
            } else {
                prefs.edit().putBoolean("notif_asked", true).apply()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

/**
 * The coins (UTXOs) of the selected chain with their confirmations, biggest first, as the
 * first thing under the balance in simple mode. Amounts follow street mode.
 */
@Composable
fun CoinsCard(state: UiState, vm: WalletViewModel, chain: Chain, accent: Color) {
    val cs = state.chains[chain] ?: return
    // Reload whenever the chain's movements change (a payment in or out, a confirmation).
    LaunchedEffect(chain, cs.transactions.map { it.txid to it.confirmations }) {
        if (cs.rows.isNotEmpty()) vm.loadUtxos()
    }
    val coins = state.utxos?.takeIf { state.utxosChain == chain } ?: return
    if (coins.isEmpty()) return
    var open by remember { mutableStateOf<com.kilombino.pyblockwatch.data.Scanner.SpendableUtxo?>(null) }
    open?.let { u ->
        val conf = if (u.height <= 0 || cs.height <= 0) 0 else cs.height - u.height + 1
        // A coin is looked at by its address: copy it, or open the address on the explorer.
        val addr = vm.coinAddress(u)
        val confText = if (conf == 0) "in mempool · 0 confirmations" else "$conf confirmation" + if (conf == 1) "" else "s"
        if (addr != null) TxDetailDialog(addr, accent, vm.explorerFor(chain),
            status = "${vm.coinPath(u)} · ${if (u.chainIndex == 0) "receive" else "change"} · $confText",
            onSpeedUp = null, onClose = { open = null }, vm = vm, chain = chain, kind = "address",
            extra = {
                Text(street("${groupSats(u.value)} sats") + " · ${u.txid.take(8)}…:${u.vout}",
                    style = MaterialTheme.typography.bodySmall, color = TextSoft)
            })
        else TxDetailDialog(u.txid, accent, vm.explorerFor(chain),
            status = "output ${u.vout} · $confText",
            onSpeedUp = null, onClose = { open = null }, vm = vm, chain = chain)
    }
    Panel(accent = accent) {
        SectionLabel("Coins · confirmations", accent)
        Spacer(Modifier.height(8.dp))
        coins.sortedByDescending { it.value }.forEach { u ->
            val conf = if (u.height <= 0 || cs.height <= 0) 0 else cs.height - u.height + 1
            Row(Modifier.clickable { open = u }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(street("${groupSats(u.value)} ${if (chain == Chain.BLAKE2B) "sats" else "poolsats"}"),
                        style = MaterialTheme.typography.bodyMedium, color = TextMain)
                    Text(vm.coinAddress(u)?.let { shortAddress(it) } ?: "${u.txid.take(8)}…:${u.vout}",
                        style = MaterialTheme.typography.bodySmall, color = TextFaint)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (conf == 0) "0 conf" else "$conf conf" + if (conf >= 3) "  ✓" else "",
                    style = MaterialTheme.typography.bodySmall, color = if (conf >= 3) Good else if (conf == 0) Warn else TextSoft)
            }
        }
    }
}

/**
 * Under a pasted extended public key: a plain `xpub` doesn't say which addresses it makes, so the
 * type is asked for (null until picked); a ypub/zpub says it and nothing is asked. A key that is not
 * an account key (m/purpose'/0'/0', depth 3) is pointed out: its addresses won't be the wallet's.
 */
@Composable
internal fun XpubTypeChoice(xpub: String, picked: ScriptType?, accent: Color, onPick: (ScriptType) -> Unit) {
    val key = remember(xpub) {
        runCatching { com.kilombino.pyblockwatch.crypto.Bip32.parseExtendedPubKey(xpub.trim().replace("\\s".toRegex(), "")) }.getOrNull()
    } ?: return
    if (key.depth != 3) Text("This key is at depth ${key.depth}, not an account key (m/84'/0'/0', m/86'/0'/0'…): " +
        "its addresses won't match your wallet's. Export the account xpub instead.", color = Bad, style = MaterialTheme.typography.bodySmall)
    if (key.scriptType != ScriptType.P2PKH) return
    Text("A plain xpub doesn't say which addresses it makes: pick the type your wallet uses.",
        color = TextSoft, style = MaterialTheme.typography.bodySmall)
    listOf(
        ScriptType.P2WPKH to "Native SegWit · bc1q… (BIP84)",
        ScriptType.P2TR to "Taproot · bc1p… (BIP86)",
        ScriptType.P2SH_P2WPKH to "Nested SegWit · 3… (BIP49)",
        ScriptType.P2PKH to "Legacy · 1… (BIP44)",
    ).forEach { (t, name) ->
        androidx.compose.material3.TextButton(onClick = { onPick(t) }, modifier = Modifier.fillMaxWidth()) {
            Text((if (t == picked) "● " else "○ ") + name, color = if (t == picked) accent else TextMain,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Whether [xpub] still needs its type picked before it can be watched (see [XpubTypeChoice]). */
internal fun xpubNeedsType(xpub: String): Boolean =
    com.kilombino.pyblockwatch.data.Scanner.scriptTypeOf(xpub.trim().replace("\\s".toRegex(), "")) == ScriptType.P2PKH
