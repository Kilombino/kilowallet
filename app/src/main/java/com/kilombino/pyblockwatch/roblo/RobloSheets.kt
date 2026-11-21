package com.kilombino.pyblockwatch.roblo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.FragmentActivity
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.ui.Bad
import com.kilombino.pyblockwatch.ui.Biometric
import com.kilombino.pyblockwatch.ui.Good
import com.kilombino.pyblockwatch.ui.PanelBg
import com.kilombino.pyblockwatch.ui.ReceiveSheet
import com.kilombino.pyblockwatch.ui.SendPhase
import com.kilombino.pyblockwatch.ui.TextFaint
import com.kilombino.pyblockwatch.ui.TextMain
import com.kilombino.pyblockwatch.ui.TextSoft
import com.kilombino.pyblockwatch.ui.UiState
import com.kilombino.pyblockwatch.ui.WalletViewModel
import com.kilombino.pyblockwatch.ui.i18n.Text
import kotlinx.coroutines.launch

/** The sheet for whatever the client asked of the wallet ([RobloWallet.request]). */
@Composable
fun RobloWalletSheets(state: UiState, vm: WalletViewModel) {
    val req by RobloWallet.request.collectAsState()
    when (val r = req) {
        is RobloWallet.Request.Pay -> PaySheet(r, state, vm)
        is RobloWallet.Request.Address -> AddressSheet(r, vm)
        is RobloWallet.Request.PayInvoice -> LnPaySheet(r)
        is RobloWallet.Request.Invoice -> LnInvoiceSheet(r)
        null -> {}
    }
}

private fun groups(a: String) = a.chunked(4).joinToString(" ")

/** "kilobot/24" → "order #24 · Kilobot". */
private fun orderText(ref: String) = if (ref.isEmpty()) "new order" else ref.split('/').let { "order #${it.getOrElse(1) { "?" }} · ${it[0].replaceFirstChar { c -> c.uppercase() }}" }

@Composable
private fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit, buttons: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(color = PanelBg, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(0.94f)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = TextMain)
                content()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { buttons() }
            }
        }
    }
}


/**
 * On-chain payment from the hot wallet: the coordinator's address and amount fixed, coins and fee
 * as in SEND, the review checked against them, signed with the fingerprint; the txid goes back.
 */
@Composable
private fun PaySheet(r: RobloWallet.Request.Pay, state: UiState, vm: WalletViewModel) {
    val activity = LocalContext.current as FragmentActivity
    var fee by remember(r) { mutableStateOf(String.format(java.util.Locale.US, "%.1f", maxOf(r.feeHint, 1.0))) }
    var chooseCoins by remember(r) { mutableStateOf(false) }
    var picked by remember(r) { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember(r) { mutableStateOf<String?>(null) }
    val target = RobloWallet.labMainnet(r.address)
    LaunchedEffect(r) {
        if (state.sendPhase !is SendPhase.Broadcasting) vm.resetSend()
        if (state.isHot && state.selected != Chain.BLAKE2B) vm.select(Chain.BLAKE2B)
    }
    fun close(ok: Boolean, value: String) { RobloWallet.finish(r, ok, value); vm.resetSend() }
    val phase = state.sendPhase
    LaunchedEffect(phase) { if (phase is SendPhase.Sent) close(true, phase.txid) }
    Sheet("Pay from Kilowallet · " + orderText(r.orderRef), onDismiss = { if (phase !is SendPhase.Broadcasting) close(false, "cancelled") }, content = {
        Text("To (the coordinator's address, fixed)", style = MaterialTheme.typography.bodySmall, color = TextFaint)
        Text(groups(r.address), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = TextMain)
        Text("Amount: ${"%,d".format(r.sats)} sats (fixed)", style = MaterialTheme.typography.bodyMedium, color = TextMain)
        if (!state.isHot || state.jamView) {
            Text("It is paid from your hot wallet.", style = MaterialTheme.typography.bodySmall, color = TextSoft)
            TextButton(onClick = {
                Biometric.confirm(activity, "Use the hot wallet", "Unlock to pay from your hot wallet", onSuccess = { vm.viewAsHotInRoblo() }, onError = {})
            }) { Text("USE THE HOT WALLET", color = RobloAccent) }
            return@Sheet
        }
        when (phase) {
            is SendPhase.Review -> {
                val d = phase.draft
                val ok = RobloWallet.draftMatches(r, d.toAddress, d.amount, d.extra.size)
                if (!ok) Text("The prepared payment does not match the order: nothing will be signed.", color = Bad, style = MaterialTheme.typography.bodySmall)
                Text("Fee: ${"%,d".format(d.fee)} sats · coins: ${d.inputs.size}" + (if (d.change > 0) " · change ${"%,d".format(d.change)} sats back to you" else ""),
                    style = MaterialTheme.typography.bodySmall, color = TextSoft)
                d.changeAddress?.let { Text("Change: $it", style = MaterialTheme.typography.bodySmall, color = TextFaint, fontFamily = FontFamily.Monospace) }
            }
            SendPhase.Preparing -> Text("Preparing…", color = TextSoft, style = MaterialTheme.typography.bodySmall)
            SendPhase.Broadcasting -> Text("Signing and sending…", color = TextSoft, style = MaterialTheme.typography.bodySmall)
            is SendPhase.Failed -> Text(phase.message, color = Bad, style = MaterialTheme.typography.bodySmall)
            else -> {
                OutlinedTextField(value = fee, onValueChange = { fee = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("fee rate, sat/vB", style = MaterialTheme.typography.bodySmall) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = chooseCoins, onCheckedChange = { chooseCoins = it; if (it) vm.loadUtxos() })
                    Text("Choose the coins (as in SEND)", style = MaterialTheme.typography.bodySmall, color = TextSoft)
                }
                if (chooseCoins) Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    val coins = state.utxos.orEmpty()
                    if (state.utxosLoading) Text("Reading your coins…", color = TextFaint, style = MaterialTheme.typography.bodySmall)
                    coins.forEach { u ->
                        val key = "${u.txid}:${u.vout}"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = key in picked, onCheckedChange = { on -> picked = if (on) picked + key else picked - key })
                            Text("${"%,d".format(u.value)} sats · ${u.txid.take(8)}…:${u.vout}", style = MaterialTheme.typography.bodySmall, color = TextMain)
                        }
                    }
                }
            }
        }
        error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
    }, buttons = {
        if (phase !is SendPhase.Broadcasting) TextButton(onClick = { close(false, "cancelled") }) { Text("CANCEL", color = TextSoft) }
        if (!state.isHot || state.jamView) return@Sheet
        when (phase) {
            is SendPhase.Review -> TextButton(enabled = RobloWallet.draftMatches(r, phase.draft.toAddress, phase.draft.amount, phase.draft.extra.size), onClick = {
                vm.seedCipherOrToast()?.let { cipher ->
                    Biometric.authenticate(activity, "Pay " + orderText(r.orderRef), "Unlock to sign the payment", cipher,
                        onSuccess = { authed -> vm.confirmSend(authed) }, onError = { error = it })
                }
            }) { Text("CONFIRM & SIGN", color = RobloAccent) }
            SendPhase.Preparing, SendPhase.Broadcasting, is SendPhase.Sent -> {}
            else -> TextButton(onClick = {
                error = null
                val rate = fee.toDoubleOrNull()?.takeIf { it >= 0.1 } ?: run { error = "Enter a fee rate."; return@TextButton }
                val coins = if (chooseCoins) state.utxos.orEmpty().filter { "${it.txid}:${it.vout}" in picked } else emptyList()
                if (chooseCoins && coins.isEmpty()) { error = "Pick at least one coin."; return@TextButton }
                vm.prepareSend(target, r.sats, rate, coins)
            }) { Text("REVIEW", color = RobloAccent) }
        }
    })
}

/**
 * Receiving: the RECEIVE menu of the wallet on screen; the address picked is fixed, and accepted in
 * a dialog that shows its derivation path, before it goes to the order.
 */
@Composable
private fun AddressSheet(r: RobloWallet.Request.Address, vm: WalletViewModel) {
    var chosen by remember(r) { mutableStateOf<Pair<String, String>?>(null) }
    val st by vm.state.collectAsState()
    Dialog(onDismissRequest = { RobloWallet.finish(r, false, "cancelled") }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.96f).verticalScroll(rememberScrollState())) {
            ReceiveSheet(vm, RobloAccent, onPick = { a, p -> chosen = a to p }) { RobloWallet.finish(r, false, "cancelled") }
        }
    }
    chosen?.let { (address, path) ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text("Use this address for Roblosats?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(orderText(r.orderRef) + " · " + when (r.purpose) { "payout" -> "where you receive the sats"; "refund" -> "your bond and refunds back"; else -> "your bond back" },
                        style = MaterialTheme.typography.bodySmall)
                    Text(groups(address), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = TextMain)
                    Text("Derivation: $path · " + (if (st.isHot) "hot wallet" else "watch-only wallet"), style = MaterialTheme.typography.bodySmall, color = TextSoft)
                }
            },
            confirmButton = { TextButton(onClick = { RobloWallet.finish(r, true, RobloWallet.labOut(address)); chosen = null }) { Text("ACCEPT", color = RobloAccent) } },
            dismissButton = { TextButton(onClick = { chosen = null }) { Text("BACK", color = TextSoft) } },
            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
        )
    }
}

/** Locking a hold invoice (bond, trade escrow) with Kilowallet's Lightning node. */
@Composable
private fun LnPaySheet(r: RobloWallet.Request.PayInvoice) {
    val ctx = LocalContext.current
    val activity = ctx as FragmentActivity
    var info by remember(r) { mutableStateOf<com.kilombino.pyblockwatch.ln.Lnd.PayReq?>(null) }
    var error by remember(r) { mutableStateOf<String?>(null) }
    var starting by remember(r) { mutableStateOf(true) }
    var busy by remember(r) { mutableStateOf(false) }
    LaunchedEffect(r) {
        error = RobloLn.awaitReady(ctx); starting = false
        if (error == null) runCatching { com.kilombino.pyblockwatch.ln.Lnd.decode(r.bolt11) }.onSuccess { info = it }.onFailure { error = it.message }
    }
    Sheet("Lock with Lightning · " + orderText(r.orderRef), onDismiss = { if (!busy) RobloWallet.finish(r, false, "cancelled") }, content = {
        info?.let {
            Text("${"%,d".format(it.sats)} sats from your Lightning channels", style = MaterialTheme.typography.bodyMedium, color = TextMain)
            Text("A hold invoice: it stays locked in your channel until the trade ends, and is charged only if you lose the bond " +
                "(or, for the seller, when the trade completes). Keep Kilowallet able to run: its background watcher brings the node back " +
                "while it is locked (up to ${it.cltv} blocks).", style = MaterialTheme.typography.bodySmall, color = TextSoft)
        }
        if (starting) Text("Starting your Lightning node…", color = TextSoft, style = MaterialTheme.typography.bodySmall)
        if (busy) Text("Sending the payment…", color = TextSoft, style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
    }, buttons = {
        if (!busy) TextButton(onClick = { RobloWallet.finish(r, false, "cancelled") }) { Text("CANCEL", color = TextSoft) }
        if (info != null && error == null && !busy) TextButton(onClick = {
            Biometric.confirm(activity, "Lock " + orderText(r.orderRef), "Confirm it is you", onSuccess = {
                busy = true
                RobloLn.pay(ctx, r.bolt11, r.orderRef,
                    onLocked = { activity.runOnUiThread { RobloWallet.finish(r, true, "locked") } },
                    onFailed = { why -> activity.runOnUiThread { busy = false; error = why } })
            }, onError = {})
        }) { Text("LOCK", color = RobloAccent) }
    })
}

/** The Lightning payout: an invoice from the node, for exactly what the coordinator will pay. */
@Composable
private fun LnInvoiceSheet(r: RobloWallet.Request.Invoice) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember(r) { mutableStateOf<String?>(null) }
    var starting by remember(r) { mutableStateOf(true) }
    LaunchedEffect(r) { error = RobloLn.awaitReady(ctx); starting = false }
    Sheet("Invoice from your Lightning node", onDismiss = { RobloWallet.finish(r, false, "cancelled") }, content = {
        Text("${"%,d".format(r.sats)} sats · ${r.memo}", style = MaterialTheme.typography.bodyMedium, color = TextMain)
        Text("Your channels need room to receive it (inbound liquidity).", style = MaterialTheme.typography.bodySmall, color = TextSoft)
        if (starting) Text("Starting your Lightning node…", color = TextSoft, style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
    }, buttons = {
        TextButton(onClick = { RobloWallet.finish(r, false, "cancelled") }) { Text("CANCEL", color = TextSoft) }
        if (error == null && !starting) TextButton(onClick = {
            scope.launch {
                runCatching { RobloLn.invoice(ctx, r.sats, r.memo) }
                    .onSuccess { RobloWallet.finish(r, true, it) }.onFailure { error = it.message ?: "No invoice" }
            }
        }) { Text("CREATE", color = Good) }
    })
}
