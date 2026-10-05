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
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import com.kilombino.pyblockwatch.ark.Ark
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * Ark tab of Simple mode. The engine (Paperclip/bark, in Rust) runs in this process and
 * talks to the Paperclip Ark server; chain data comes from mempool.kilombino.com.
 */

private sealed interface ArkView {
    data object Starting : ArkView
    data class Failed(val message: String) : ArkView
    data object NoWallet : ArkView
    data object Ready : ArkView
}

/** The user's fiat choice and the latest price, for "≈ $" lines across the Ark tab. */
private data class FiatCtx(val market: com.kilombino.pyblockwatch.data.MarketData?, val code: String)
private val LocalFiat = androidx.compose.runtime.compositionLocalOf { FiatCtx(null, "USD") }

/** "≈ €1.23" for [sats] in the currency chosen in Settings, or null without a price. */
@Composable
private fun fiatOf(sats: Long): String? {
    val f = LocalFiat.current
    val v = f.market?.fiatValue(sats, f.code) ?: return null
    val sym = if (f.code == "EUR") "€" else "$"
    // Ark amounts are often cents: keep a third decimal below one unit.
    val abs = kotlin.math.abs(v)
    return (if (v < 0) "≈ −$sym" else "≈ $sym") + String.format(Locale.US, if (abs < 1) "%.3f" else "%,.2f", abs)
}

@Composable
fun ArkScreen(vm: WalletViewModel, accent: Color, pull: Int = 0) {
    val ui by vm.state.collectAsState()
    androidx.compose.runtime.CompositionLocalProvider(LocalFiat provides FiatCtx(ui.market, ui.fiat)) {
        ArkScreenBody(vm, accent, pull)
    }
}

@Composable
private fun ArkScreenBody(vm: WalletViewModel, accent: Color, pull: Int) {
    val activity = LocalContext.current as FragmentActivity
    val ctx = activity.applicationContext
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<ArkView>(ArkView.Starting) }
    var balance by remember { mutableStateOf<Ark.Balance?>(null) }
    var expiry by remember { mutableStateOf<Int?>(null) }
    var history by remember { mutableStateOf<List<Ark.Movement>>(emptyList()) }
    var fingerprint by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<String?>(null) }   // "receive" | "send" | "board"
    // The renewal quote, shown in a confirmation dialog before anything happens.
    var renew by remember { mutableStateOf<Ark.Estimate?>(null) }
    // New Ark words, shown once right after activation so they get written down.
    var newWords by remember { mutableStateOf<List<String>?>(null) }

    suspend fun reload() {
        val b = withContext(Dispatchers.IO) { runCatching { Ark.balance() }.getOrNull() }
        balance = b
        expiry = withContext(Dispatchers.IO) { Ark.blocksToNearestExpiry() }
        history = withContext(Dispatchers.IO) { runCatching { Ark.activity() }.getOrDefault(emptyList()) }
        fingerprint = withContext(Dispatchers.IO) { Ark.stateFingerprint() }
        // The same alerts as the background watcher, so a movement pops up while the app is open.
        withContext(Dispatchers.IO) {
            if (com.kilombino.pyblockwatch.data.Store(ctx).notificationsEnabled)
                runCatching { com.kilombino.pyblockwatch.data.ArkWatch.evaluate(ctx, com.kilombino.pyblockwatch.data.Notifier(ctx)) }
        }
    }

    fun run(label: String, block: suspend () -> String?) {
        scope.launch {
            busy = label; message = null
            message = try { block() } catch (e: Exception) { "Error: " + (e.message ?: e.toString()) }
            busy = null
            runCatching { reload() }
        }
    }

    // Anything that moves funds asks for the fingerprint (or screen lock) first, as on the
    // XBT side. A phone without any screen lock has nothing to ask and goes straight on.
    fun secured(what: String, label: String, block: suspend () -> String?) =
        Biometric.confirm(activity, what, "Confirm it is you",
            onSuccess = { run(label, block) },
            onError = { message = "Error: $it" })

    LaunchedEffect(Unit) {
        view = try {
            withContext(Dispatchers.IO) {
                Ark.ensureStarted(ctx)
                if (Ark.hasWallet()) { Ark.syncOnchain(); ArkView.Ready } else ArkView.NoWallet
            }
        } catch (e: Exception) { ArkView.Failed(e.message ?: e.toString()) }
        if (view == ArkView.Ready) reload()
        // Keep the figures live while the tab is open; boards and rounds settle on their own.
        var tick = 0
        while (true) {
            delay(30_000)
            if (view != ArkView.Ready) continue
            // A deposit should show up within a couple of minutes even between blocks.
            if (++tick % 4 == 0) withContext(Dispatchers.IO) { Ark.syncOnchain() }
            runCatching { reload() }
        }
    }

    // Pulled down in Simple mode: look at the chain now and reload the figures.
    LaunchedEffect(pull) {
        if (pull > 0 && view == ArkView.Ready) {
            withContext(Dispatchers.IO) { Ark.syncOnchain() }
            runCatching { reload() }
        }
    }

    val fresh = newWords
    if (fresh != null) ArkNewWords(fresh, accent) { newWords = null }
    else when (val v = view) {
        ArkView.Starting -> Panel(accent = accent) {
            SectionLabel("Ark", accent); Spacer(Modifier.height(8.dp))
            Text("Starting the Ark engine…", style = MaterialTheme.typography.bodyMedium, color = TextSoft)
        }
        is ArkView.Failed -> Panel(accent = Bad) {
            SectionLabel("Ark could not start", Bad); Spacer(Modifier.height(8.dp))
            Explain(v.message)
        }
        ArkView.NoWallet -> {
            ArkWarnings()
            ArkActivate(vm, accent) { words ->
                newWords = words
                view = ArkView.Ready
                scope.launch { reload() }
            }
        }
        ArkView.Ready -> {
            ArkBalancePanel(balance, expiry, history.firstOrNull { it.kind == "move into Ark" && it.confirmations != null }?.confirmations,
                            accent, onFiat = vm::setFiat)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArkButton("RECEIVE", sheet == "receive", accent, Modifier.weight(1f)) { sheet = if (sheet == "receive") null else "receive" }
                ArkButton("SEND", sheet == "send", accent, Modifier.weight(1f)) { sheet = if (sheet == "send") null else "send" }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArkButton("MOVE INTO ARK", sheet == "board", accent, Modifier.weight(1f)) { sheet = if (sheet == "board") null else "board" }
                ArkButton("RENEW", renew != null, accent, Modifier.weight(1f)) {
                    scope.launch {
                        busy = "Calculating the renewal cost…"
                        renew = withContext(Dispatchers.IO) { Ark.estimateRenew() }
                            ?: Ark.Estimate(0, 0, 0, false, "", "Could not calculate the cost. Try again in a moment.")
                        busy = null
                    }
                }
            }
            renew?.let { e ->
                ArkRenewDialog(e, accent,
                    onConfirm = { chosen ->
                        renew = null
                        secured("Renew Ark coins", "Renewing coins…") {
                            withContext(Dispatchers.IO) { if (chosen == null) Ark.refreshAll() else Ark.renewCoins(chosen) }
                            "Renewal requested; it completes in the next round."
                        }
                    },
                    onDismiss = { renew = null })
            }
            when (sheet) {
                "receive" -> ArkReceiveSheet(accent, busy) { action, done ->
                    run(action.first) { val r = withContext(Dispatchers.IO) { action.second() }; done(r); null }
                }
                "send" -> ArkSendSheet(accent, (balance?.onchainConfirmed ?: 0)) { dest, sats, approved, fromDeposit, coins ->
                    secured("Send from Ark", "Sending…") {
                        withContext(Dispatchers.IO) {
                            when {
                                coins.isNotEmpty() && fromDeposit -> Ark.sendDepositCoins(dest, sats, coins)
                                coins.isNotEmpty() && dest.startsWith("ark1", true) && sats != null && approved != null ->
                                    Ark.sendArkCoins(dest, sats, approved, coins)
                                coins.isNotEmpty() -> Ark.withdrawCoins(dest, coins)
                                fromDeposit -> Ark.sendFromDeposit(dest, sats)
                                else -> Ark.send(dest, sats, approved)
                            }
                        }.ifBlank { "Sent." }
                    }
                    sheet = null
                }
                "board" -> ArkBoardSheet(balance, accent) { sats, rate ->
                    secured("Move into Ark", "Moving funds into Ark…") {
                        withContext(Dispatchers.IO) { if (sats == null) Ark.boardAll(rate) else Ark.board(sats, rate) }
                        "Moving into Ark. It becomes spendable after ${Ark.BOARD_CONFIRMATIONS} confirmations."
                    }
                    sheet = null
                }
                else -> {}
            }
            if (history.isNotEmpty()) ArkHistory(history, accent, vm.explorerFor(com.kilombino.pyblockwatch.chain.Chain.BLAKE2B))
            ArkBackupPanel(fingerprint, accent) { message = it }
            ArkEmergencyPanel(accent, activity) { message = it }
            ArkWarnings()
        }
    }
    busy?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Warn) }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Error")) Bad else Good) }
}

@Composable
private fun ArkButton(label: String, selected: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = if (selected) accent else PanelSoft,
                                             contentColor = if (selected) Ink else accent),
        shape = RoundedCornerShape(12.dp), modifier = modifier.height(52.dp),
    ) { Text(label, style = MaterialTheme.typography.titleSmall) }
}

@Composable
private fun ArkBalancePanel(b: Ark.Balance?, expiry: Int?, boardConfs: Int?, accent: Color, onFiat: (String) -> Unit) {
    Panel(accent = accent) {
        SectionLabel("Your Ark wallet", accent)
        Spacer(Modifier.height(6.dp))
        val deposit = (b?.onchainConfirmed ?: 0) + (b?.onchainPending ?: 0)
        val total = (b?.arkTotal ?: 0) + deposit + (b?.lightningPending ?: 0)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(groupSats(total), style = MaterialTheme.typography.displayLarge, color = accent)
            Spacer(Modifier.width(8.dp))
            Text("sats", style = MaterialTheme.typography.titleLarge, color = accent.copy(alpha = 0.7f))
        }
        // The same USD/EUR choice as the XBT tab and the widget.
        val code = LocalFiat.current.code
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fiatOf(total) ?: "≈ –", style = MaterialTheme.typography.headlineSmall, color = TextMain,
                 modifier = Modifier.weight(1f))
            FiatChip("USD", code == "USD", accent) { onFiat("USD") }
            Spacer(Modifier.width(6.dp))
            FiatChip("EUR", code == "EUR", accent) { onFiat("EUR") }
        }
        if (b == null) { Text("Loading…", style = MaterialTheme.typography.bodySmall, color = TextFaint); return@Panel }
        if (b.pendingBoard > 0) Text("entering Ark: ${groupSats(b.pendingBoard)} sats · " +
                                     (boardConfs?.let { "$it of ${Ark.BOARD_CONFIRMATIONS} confirmations" }
                                         ?: "needs ${Ark.BOARD_CONFIRMATIONS} confirmations"),
                                     style = MaterialTheme.typography.bodySmall, color = Warn)
        if (b.pendingRound > 0) Text("in the next round: ${groupSats(b.pendingRound)} sats",
                                     style = MaterialTheme.typography.bodySmall, color = Warn)
        // Where it is: Ark coins, the on-chain deposit, and Lightning still settling.
        Spacer(Modifier.height(6.dp))
        PocketRow("In Ark", b.spendable, "spendable", Good)
        PocketRow("On-chain deposit", deposit,
            if (b.onchainPending > 0) "${groupSats(b.onchainPending)} unconfirmed · not in Ark yet" else "not in Ark yet", TextSoft)
        if (b.lightningPending > 0) PocketRow("Lightning", b.lightningPending, "settling", Warn)
        if (b.pendingExit > 0) Text("leaving Ark (emergency exit): ${groupSats(b.pendingExit)} sats",
                                    style = MaterialTheme.typography.bodySmall, color = Bad)
        expiry?.let {
            Spacer(Modifier.height(6.dp))
            val days = it * 10 / 1440.0
            Text(
                "Next coin expires in $it blocks (≈ " + String.format(Locale.US, "%.1f", days) + " days). " +
                    "The wallet renews it while it is open; tap RENEW to do it now.",
                style = MaterialTheme.typography.bodySmall,
                color = if (it < 1008) Bad else TextFaint,   // under ~7 days: red
            )
        }
    }
}

@Composable
private fun PocketRow(label: String, sats: Long, note: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSoft, modifier = Modifier.width(130.dp))
        Text(groupSats(sats) + " sats", style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
        Text(note, style = MaterialTheme.typography.bodySmall, color = TextFaint)
    }
}

@Composable
private fun ArkReceiveSheet(
    accent: Color, busy: String?,
    run: (Pair<String, () -> String>, (String) -> Unit) -> Unit,
) {
    val clip = LocalClipboardManager.current
    var shown by remember { mutableStateOf<Pair<String, String>?>(null) }  // label, text
    var amount by remember { mutableStateOf("") }
    // The invoice is made here rather than through run(): its progress and any error belong
    // next to the button. Through run() they went to the foot of the screen, out of sight,
    // while the previous invoice stayed up and looked like the "new" one.
    val scope = rememberCoroutineScope()
    var invoicing by remember { mutableStateOf(false) }
    var invoiceError by remember { mutableStateOf<String?>(null) }
    var lnState by remember { mutableStateOf<String?>(null) }
    Panel(accent = accent) {
        SectionLabel("Receive", accent)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ArkButton("ARK", false, accent, Modifier.weight(1f)) {
                run("Getting an Ark address…" to { Ark.arkAddress() }) { shown = "Ark address" to it }
            }
            ArkButton("DEPOSIT", false, accent, Modifier.weight(1f)) {
                run("Getting a deposit address…" to { Ark.onchainAddress() }) { shown = "On-chain deposit address" to it }
            }
            ArkButton("⚡ OFFER", false, accent, Modifier.weight(1f)) {
                val sats = amount.toLongOrNull()
                run("Getting your reusable offer…" to { Ark.reusableOffer("Kilombino wallet", sats) }) {
                    shown = "Reusable Lightning offer (BOLT12)" + (sats?.let { " for $it sats" } ?: ", any amount") to it
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = amount, onValueChange = { amount = it.filter(Char::isDigit) },
                label = { Text("sats for Lightning") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            ArkButton(if (invoicing) "…" else "⚡ INVOICE", false, accent, Modifier.width(130.dp)) {
                if (invoicing) return@ArkButton
                val sats = amount.toLongOrNull()
                if (sats == null || sats <= 0) { invoiceError = "Type the amount in sats first."; return@ArkButton }
                // Each press is a new invoice: the old one goes away at once, so it can't be
                // taken for the new one (or paid twice) while the engine is still working.
                shown = null; invoiceError = null; invoicing = true
                scope.launch {
                    try {
                        val inv = withContext(Dispatchers.IO) {
                            // The engine would make an invoice whose payment can never be claimed: the
                            // payer's HTLC is refused and the payment fails. Refuse it here instead.
                            val net = Ark.estimateLightningReceive(sats)?.amount
                            require(net == null || net >= Ark.MIN_OUTPUT_SAT) {
                                "An invoice of $sats sats leaves you ${net ?: 0} after the receive cost, below the " +
                                    "${Ark.MIN_OUTPUT_SAT}-sat minimum, so the payment would fail. Ask for at least about 5 500 sats."
                            }
                            Ark.lightningInvoice(sats, null)
                        }
                        lnState = null
                        shown = "Lightning invoice ($sats sats)" to inv
                    } catch (e: Exception) {
                        invoiceError = "Could not create the invoice: " + (e.message ?: e.toString())
                    } finally { invoicing = false }
                }
            }
        }
        if (invoicing) Text("Creating a new Lightning invoice… It can take a minute while the wallet " +
            "finishes other work, such as claiming an earlier payment.",
            style = MaterialTheme.typography.bodySmall, color = Warn)
        invoiceError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Bad) }
        Text("Lightning: up to ${groupSats(Ark.MAX_LIGHTNING_SAT)} sats per payment. Deposits become Ark funds with MOVE INTO ARK (minimum ${groupSats(Ark.MIN_BOARD_SAT)} sats).",
             style = MaterialTheme.typography.bodySmall, color = TextFaint)
        // Before creating a Lightning invoice, show what will actually arrive.
        val lnSats = amount.toLongOrNull()
        var lnEst by remember { mutableStateOf<Ark.Estimate?>(null) }
        LaunchedEffect(lnSats) {
            lnEst = if (lnSats != null && lnSats > 0) withContext(Dispatchers.IO) { runCatching { Ark.estimateLightningReceive(lnSats) }.getOrNull() } else null
        }
        lnEst?.let { e ->
            Spacer(Modifier.height(6.dp))
            CostBreakdown(Ark.Estimate(e.amount, e.fee, e.total, e.exact, e.note), "You receive", "Invoice amount", accent)
            if (e.amount < Ark.MIN_OUTPUT_SAT) Text("Too small: after the receive cost you would get ${groupSats(e.amount)} " +
                "sats, below the ${groupSats(Ark.MIN_OUTPUT_SAT)}-sat minimum, so the payer's payment would fail.",
                style = MaterialTheme.typography.bodySmall, color = Bad)
        }
        // Follow the invoice on screen until it is paid, so nobody pays it a second time:
        // a paid invoice can't be paid again and the payer only sees a failure.
        val isInvoice = shown?.first?.startsWith("Lightning invoice") == true
        LaunchedEffect(shown) {
            val inv = shown?.second
            if (!isInvoice || inv == null) return@LaunchedEffect
            while (lnState != "settled") {
                lnState = withContext(Dispatchers.IO) { Ark.lightningReceiveState(inv) } ?: lnState
                delay(4_000)
            }
        }
        shown?.let { (label, text) ->
            val paid = isInvoice && lnState == "settled"
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = accent)
            if (isInvoice) {
                val (st, col) = when (lnState) {
                    "settled" -> "✅ Paid: this invoice is done, it can't be paid again. Create a new one for another payment." to Good
                    "htlcs-ready", "preimage-revealed", "delivering" -> "⏳ Payment arriving…" to Warn
                    else -> "Waiting for payment. Valid for one payment only." to TextFaint
                }
                Text(st, style = MaterialTheme.typography.bodySmall, color = col)
            }
            Spacer(Modifier.height(6.dp))
            if (!paid) QrImage(if (label.contains("Lightning")) text.uppercase() else text, 230)
            Spacer(Modifier.height(6.dp))
            SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall, color = TextMain) }
            Row {
                TextButton(onClick = { clip.setText(AnnotatedString(text)) }) { Text("COPY", color = accent) }
                if (label.contains("BOLT12")) TextButton(onClick = {
                    run("Disabling the offer…" to { Ark.disableOffer(); "" }) { shown = null }
                }) { Text("DISABLE", color = TextSoft) }
            }
            if (label.contains("BOLT12")) Text(
                "One code you can share and be paid on many times; each payment lands in Ark. " +
                    "It only works while this wallet is running: keep notifications on so it stays up " +
                    "in the background. If the phone is off, the payer sees the payment fail and " +
                    "nothing is lost. Each payment pays the Lightning receive cost (about 4 100 sats: " +
                    "recovery reserve plus the server's fee). " +
                    "Payments under about 5 500 sats fail: after the receive cost too little would be left. " +
                    "Save a backup file after creating it: the file brings this same offer back on a new " +
                    "phone; the words alone give you a new one. " +
                    "Type an amount first to fix it, or leave it empty so payers choose.",
                style = MaterialTheme.typography.bodySmall, color = TextFaint)
        }
    }
}

@Composable
private fun ArkSendSheet(accent: Color, deposit: Long, onSend: (String, Long?, Long?, Boolean, List<String>) -> Unit) {
    var dest by remember { mutableStateOf("") }
    // Coin control: spend exactly the coins ticked here (deposit coins, or Ark coins to withdraw).
    var choose by remember { mutableStateOf(false) }
    var coins by remember { mutableStateOf<List<Ark.Coin>>(emptyList()) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    // Two pockets: Ark coins (Ark, Lightning or a withdrawal) or the plain on-chain deposit.
    var fromDeposit by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var review by remember { mutableStateOf<Ark.Estimate?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var reviewError by remember { mutableStateOf<String?>(null) }
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    Panel(accent = accent) {
        SectionLabel(if (fromDeposit) "Send from the on-chain deposit" else "Send from Ark", accent)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("From", style = MaterialTheme.typography.bodySmall, color = TextSoft, modifier = Modifier.weight(1f))
            FiatChip("ARK", !fromDeposit, accent) { fromDeposit = false; review = null; picked = emptySet(); choose = false }
            Spacer(Modifier.width(6.dp))
            FiatChip("DEPOSIT · " + groupSats(deposit), fromDeposit, accent) { fromDeposit = true; review = null; picked = emptySet(); choose = false }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
            choose = !choose; review = null; picked = emptySet()
            if (choose) scope.launch { coins = withContext(Dispatchers.IO) { if (fromDeposit) Ark.depositCoins() else Ark.arkCoins() } }
        }) {
            Text(if (choose) "☑" else "☐", color = accent, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Text("Choose coins" + if (!fromDeposit) " (Ark payments and withdrawals to an XBT address)" else "",
                 style = MaterialTheme.typography.bodySmall, color = TextSoft)
        }
        if (choose) CoinPicker(coins, picked, accent) { id -> picked = if (id in picked) picked - id else picked + id; review = null }
        if (fromDeposit) Text("Plain on-chain XBT to an XBT address. Only the network fee; no Ark reserves.",
                              style = MaterialTheme.typography.bodySmall, color = TextFaint)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(value = dest, onValueChange = { dest = it.trim(); review = null },
            label = { Text("Ark address, Lightning invoice or XBT address") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { clip.getText()?.text?.let { dest = it.trim(); review = null } }) { Text("PASTE", color = accent) }
        OutlinedTextField(value = amount, onValueChange = { amount = it.filter(Char::isDigit); review = null },
            label = { Text("sats (empty: the invoice's amount, or everything to an XBT address)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        val r = review
        if (r == null) {
            // Step 1: always show the real cost before anything is signed.
            Button(
                enabled = dest.isNotBlank() && !reviewing,
                onClick = {
                    reviewing = true; reviewError = null
                    scope.launch {
                        val e = withContext(Dispatchers.IO) {
                            runCatching {
                                val sats = amount.toLongOrNull()
                                when {
                                    choose && picked.isEmpty() -> Ark.Estimate(0, 0, 0, false, "", "Tick the coins to spend.")
                                    choose && fromDeposit -> Ark.estimateDepositCoins(dest, sats, picked.toList())
                                    choose && dest.startsWith("ark1", true) ->
                                        if (sats == null) Ark.Estimate(0, 0, 0, false, "", "Enter the amount to pay.")
                                        else Ark.estimateArkCoins(dest, sats, picked.toList())
                                    choose && dest.startsWith("ln", true) ->
                                        Ark.Estimate(0, 0, 0, false, "", "Choosing coins works for Ark payments and " +
                                            "withdrawals to an XBT address. Lightning payments pick their coins themselves.")
                                    choose && sats != null -> Ark.Estimate(0, 0, 0, false, "",
                                        "Chosen Ark coins leave whole: leave the amount empty.")
                                    choose -> Ark.estimateWithdrawCoins(dest, picked.toList())
                                    fromDeposit -> Ark.estimateDepositSend(dest, sats)
                                    else -> Ark.estimateSend(dest, sats)
                                }
                            }.getOrNull()
                        }
                        reviewing = false
                        if (e == null) reviewError = "Could not estimate the cost. Check the destination and the amount."
                        review = e
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
            ) { Text(if (reviewing) "CALCULATING…" else "REVIEW COST", style = MaterialTheme.typography.titleMedium) }
            reviewError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Bad) }
        } else {
            if (r.problem == null) CostBreakdown(r, "They receive", "You pay in total", accent)
            Spacer(Modifier.height(8.dp))
            r.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Warn) }
            if (r.problem == null) Button(
                onClick = { onSend(dest, amount.toLongOrNull(), r.total, fromDeposit, if (choose) picked.toList() else emptyList()) },
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
            ) { Text("CONFIRM AND SEND", style = MaterialTheme.typography.titleMedium) }
            TextButton(onClick = { review = null }) { Text("change", color = TextSoft) }
        }
    }
}

/** Tick boxes over coins, biggest first, with what each holds. */
@Composable
private fun CoinPicker(coins: List<Ark.Coin>, picked: Set<String>, accent: Color, onToggle: (String) -> Unit) {
    if (coins.isEmpty()) { Text("No coins here.", style = MaterialTheme.typography.bodySmall, color = TextFaint); return }
    Column(Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(10.dp)).padding(8.dp)) {
        coins.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onToggle(c.id) }.padding(vertical = 4.dp)) {
                Text(if (c.id in picked) "☑" else "☐", color = accent, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(groupSats(c.amount) + " sats", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                    Text(c.id.take(10) + "…" + c.id.takeLast(6) + "  " + c.note, style = MaterialTheme.typography.bodySmall, color = TextFaint)
                }
            }
        }
        Text("Chosen: " + groupSats(coins.filter { it.id in picked }.sumOf { it.amount }) + " sats in " + picked.size + " coin" +
             (if (picked.size == 1) "" else "s"), style = MaterialTheme.typography.bodySmall, color = TextSoft)
    }
}

/** Amount / cost / total, with the cost in warning colour when it is a big share. */
@Composable
private fun CostBreakdown(e: Ark.Estimate, amountLabel: String, totalLabel: String, accent: Color) {
    val share = if (e.amount > 0) e.fee * 100 / e.amount else 0
    Column(Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Row { Text(amountLabel, style = MaterialTheme.typography.bodySmall, color = TextSoft, modifier = Modifier.weight(1f))
              Text(groupSats(e.amount) + " sats", style = MaterialTheme.typography.bodySmall, color = TextMain) }
        Row { Text((if (e.exact) "Cost" else "Cost (approx.)"), style = MaterialTheme.typography.bodySmall, color = TextSoft, modifier = Modifier.weight(1f))
              Text(groupSats(e.fee) + " sats" + (if (share > 0) "  ($share%)" else ""), style = MaterialTheme.typography.bodySmall,
                   color = if (share >= 20) Warn else TextMain) }
        Row { Text(totalLabel, style = MaterialTheme.typography.bodySmall, color = TextSoft, modifier = Modifier.weight(1f))
              Text(groupSats(e.total) + " sats", style = MaterialTheme.typography.titleSmall, color = accent) }
        fiatOf(e.total)?.let { f ->
            Row { Spacer(Modifier.weight(1f))
                  Text("$f  (cost ${fiatOf(e.fee) ?: ""})", style = MaterialTheme.typography.bodySmall, color = TextFaint) }
        }
        if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall, color = TextFaint)
    }
}

@Composable
private fun ArkBoardSheet(b: Ark.Balance?, accent: Color, onBoard: (Long?, Double?) -> Unit) {
    var amount by remember { mutableStateOf("") }
    val available = (b?.onchainConfirmed ?: 0)
    // Network fee of the funding transaction: the wallet's estimate, or one chosen here.
    var rates by remember { mutableStateOf<Ark.FeeRates?>(null) }
    var rateText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        rates = withContext(Dispatchers.IO) { Ark.onchainFeeRates() }
        if (rateText.isEmpty()) rates?.let { rateText = it.regular.coerceAtLeast(1).toString() }
    }
    val rate = rateText.replace(',', '.').toDoubleOrNull()
    val rateOk = rate != null && rate >= 0.1 && rate <= 10_000
    Panel(accent = accent) {
        SectionLabel("Move into Ark", accent)
        Spacer(Modifier.height(6.dp))
        Explain("Moves confirmed XBT from this wallet's deposit address into Ark. Minimum " +
            "${groupSats(Ark.MIN_BOARD_SAT)} sats; spendable after ${Ark.BOARD_CONFIRMATIONS} confirmations. Available: ${groupSats(available)} sats.")
        OutlinedTextField(value = amount, onValueChange = { amount = it.filter(Char::isDigit) },
            label = { Text("sats (empty = everything)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Network fee", style = MaterialTheme.typography.bodySmall, color = TextSoft, modifier = Modifier.weight(1f))
            rates?.let { r ->
                listOf("slow" to r.slow, "normal" to r.regular, "fast" to r.fast).forEach { (name, v) ->
                    val value = v.coerceAtLeast(1).toString()
                    FiatChip(name, rateText == value, accent) { rateText = value }
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
        OutlinedTextField(value = rateText, onValueChange = { t -> rateText = t.filter { it.isDigit() || it == '.' || it == ',' } },
            label = { Text("sat/vB (decimals allowed, at least 0.1)") }, singleLine = true,
            isError = rateText.isNotEmpty() && !rateOk,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        if (rate != null && rate < 1.0 && rateOk) Text(
            "Below 1 sat/vB most XBT pools do not mine it yet, so it can take a long time to confirm. " +
                "The move only counts from its ${Ark.BOARD_CONFIRMATIONS}rd confirmation, and the new coin's " +
                "30 days start when you send it: a long wait shortens them.",
            style = MaterialTheme.typography.bodySmall, color = Warn)
        Spacer(Modifier.height(8.dp))
        val sats = amount.toLongOrNull()
        var est by remember { mutableStateOf<Ark.Estimate?>(null) }
        var netFee by remember { mutableStateOf<Long?>(null) }
        LaunchedEffect(sats, available, rate) {
            netFee = if (rateOk) withContext(Dispatchers.IO) { runCatching { Ark.estimateBoardNetworkFee(rate!!, sats == null) }.getOrNull() } else null
            // Moving everything: the network fee comes out of the deposit first.
            val target = sats ?: (available - (netFee ?: 0))
            est = if (target >= Ark.MIN_BOARD_SAT) withContext(Dispatchers.IO) { runCatching { Ark.estimateBoard(target) }.getOrNull() } else null
        }
        est?.let {
            CostBreakdown(it, "Added to Ark", "From your deposit", accent)
            netFee?.let { f ->
                Text("Plus the network fee of the move: about ${groupSats(f)} sats at $rateText sat/vB" +
                    (fiatOf(f)?.let { " ($it)" } ?: "") + ", paid from the deposit" + (if (sats == null) " before the rest moves in." else "."),
                    style = MaterialTheme.typography.bodySmall, color = TextSoft)
            }
            Text("Your Ark balance after: " + groupSats((b?.spendable ?: 0) + it.amount) + " sats " +
                "(now ${groupSats(b?.spendable ?: 0)} + ${groupSats(it.amount)}).",
                style = MaterialTheme.typography.bodySmall, color = TextSoft)
            Spacer(Modifier.height(8.dp))
        }
        Button(
            enabled = available >= Ark.MIN_BOARD_SAT && (sats == null || sats >= Ark.MIN_BOARD_SAT) && rateOk,
            onClick = { onBoard(sats, rate) },
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
        ) { Text("MOVE INTO ARK", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun ArkHistory(items: List<Ark.Movement>, accent: Color, explorer: String) {
    var open by remember { mutableStateOf<Ark.Movement?>(null) }
    open?.let { m -> ArkMovementDialog(m, accent, explorer) { open = null } }
    Panel(accent = accent) {
        SectionLabel("Ark activity", accent)
        Spacer(Modifier.height(4.dp))
        Text("tap a movement for its details", style = MaterialTheme.typography.bodySmall, color = TextFaint)
        Spacer(Modifier.height(6.dp))
        items.take(20).forEach { m ->
            Row(Modifier.fillMaxWidth().clickable { open = m }.padding(vertical = 2.dp)) {
                Text((if (m.amount >= 0) "+" else "") + groupSats(m.amount) + " sats",
                     style = MaterialTheme.typography.bodySmall,
                     color = if (m.amount >= 0) Good else TextMain, modifier = Modifier.weight(1f))
                Text("${m.kind} · ${statusOf(m)}", style = MaterialTheme.typography.bodySmall, color = TextFaint)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** The status, with the confirmations so far while a move into Ark or a deposit waits. */
private fun statusOf(m: Ark.Movement): String {
    val c = m.confirmations ?: return m.status
    return if (m.kind == "move into Ark") "${m.status} · $c/${Ark.BOARD_CONFIRMATIONS} conf" else "${m.status} · $c conf"
}

/** One movement in full, and its blockchain transaction when it has one. */
@Composable
private fun ArkMovementDialog(m: Ark.Movement, accent: Color, explorer: String, onClose: () -> Unit) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val site = explorer.removePrefix("https://").removePrefix("http://")
    val clip = LocalClipboardManager.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(m.kind.replaceFirstChar { it.uppercase() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                fun line(k: String, v: String) = "$k: $v"
                Text(line("Amount", (if (m.amount >= 0) "+" else "") + groupSats(m.amount) + " sats") +
                    (fiatOf(m.amount)?.let { "  $it" } ?: ""))
                if (m.fee > 0) Text(line("Cost", groupSats(m.fee) + " sats") + (fiatOf(m.fee)?.let { "  $it" } ?: ""))
                Text(line("Status", statusOf(m)))
                if (m.time.isNotEmpty()) Text(line("Date", m.time.substringBefore('.').replace('T', ' ') + " UTC"))
                m.destinations.forEach { d ->
                    Text(line("To", d.take(24) + "…" + d.takeLast(8)), style = MaterialTheme.typography.bodySmall)
                }
                if (m.onchainTxid != null) {
                    Text("On the blockchain: ${m.onchainTxid.take(12)}…${m.onchainTxid.takeLast(8)}",
                         style = MaterialTheme.typography.bodySmall)
                    Text("Opening it shows $site which transaction you look up.",
                         style = MaterialTheme.typography.bodySmall, color = TextFaint)
                } else {
                    Text("This movement is off-chain: an Ark coin signed with the server, with no " +
                        "blockchain transaction until the coin is renewed or withdrawn.",
                        style = MaterialTheme.typography.bodySmall, color = TextFaint)
                }
                m.vtxos.take(3).forEach { v ->
                    Text("Ark coin: ${v.take(12)}…${v.takeLast(10)}", style = MaterialTheme.typography.bodySmall,
                         color = TextSoft, modifier = Modifier.clickable { clip.setText(AnnotatedString(v)) })
                }
                m.paymentHash?.let { h ->
                    Text("Payment hash: ${h.take(16)}…", style = MaterialTheme.typography.bodySmall, color = TextSoft,
                         modifier = Modifier.clickable { clip.setText(AnnotatedString(h)) })
                }
                m.preimage?.let { p ->
                    Text("Preimage (proof of payment): ${p.take(16)}…", style = MaterialTheme.typography.bodySmall,
                         color = TextSoft, modifier = Modifier.clickable { clip.setText(AnnotatedString(p)) })
                }
                if (m.vtxos.isNotEmpty() || m.paymentHash != null)
                    Text("Tap an identifier to copy it.", style = MaterialTheme.typography.bodySmall, color = TextFaint)
            }
        },
        confirmButton = {
            if (m.onchainTxid != null) TextButton(onClick = { onClose(); runCatching { uri.openUri("$explorer/tx/${m.onchainTxid}") } }) {
                Text("OPEN ON ${site.uppercase()}", color = accent)
            } else TextButton(onClick = onClose) { Text("CLOSE", color = accent) }
        },
        dismissButton = { if (m.onchainTxid != null) TextButton(onClick = onClose) { Text("CLOSE", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )
}

/**
 * RENEW asks first: what a renewal does, what it costs now, and what is left. Every coin is
 * ticked by default; untick some to renew only the others (coin control), and the cost follows.
 */
@Composable
private fun ArkRenewDialog(e: Ark.Estimate, accent: Color, onConfirm: (List<String>?) -> Unit, onDismiss: () -> Unit) {
    var coins by remember { mutableStateOf<List<Ark.Coin>>(emptyList()) }
    var picked by remember { mutableStateOf<Set<String>?>(null) }
    var quote by remember { mutableStateOf(e) }
    LaunchedEffect(Unit) {
        coins = withContext(Dispatchers.IO) { Ark.arkCoins() }
        picked = coins.map { it.id }.toSet()
    }
    val sel = picked
    LaunchedEffect(sel) {
        if (sel != null && coins.isNotEmpty() && sel.size != coins.size) {
            quote = withContext(Dispatchers.IO) {
                if (sel.isEmpty()) Ark.Estimate(0, 0, 0, false, "", "Tick at least one coin.")
                else Ark.estimateRenewCoins(sel.toList()) ?: Ark.Estimate(0, 0, 0, false, "", "Could not calculate the cost.")
            }
        } else if (sel != null && sel.size == coins.size) quote = e
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renew your Ark coins?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Renewing takes your coins into the next round: they get a new expiry, about " +
                    "30 days from now, and are joined into one coin, which makes later payments " +
                    "cheaper. The wallet also renews by itself before coins expire, so you only " +
                    "need this to join coins or to renew early.", style = MaterialTheme.typography.bodySmall)
                if (coins.size > 1 && sel != null) CoinPicker(coins, sel, accent) { id ->
                    picked = if (id in sel) sel - id else sel + id
                }
                if (quote.problem != null) Text(quote.problem!!, style = MaterialTheme.typography.bodySmall, color = Warn)
                else CostBreakdown(Ark.Estimate(quote.amount, quote.fee, quote.total, true, quote.note),
                    "Your coins after", "Renewed now", accent)
            }
        },
        confirmButton = {
            if (quote.problem == null) TextButton(onClick = {
                val all = sel == null || sel.size == coins.size
                onConfirm(if (all) null else sel.toList())
            }) { Text("RENEW", color = accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )
}

@Composable
fun ArkWarnings() {
    Panel(accent = Warn) {
        SectionLabel("Before you use Ark", Warn)
        Spacer(Modifier.height(8.dp))
        listOf(
            "Ark funds expire after ${Ark.VTXO_LIFETIME_BLOCKS} blocks (about 30 days). The wallet must go online before then to renew them, or they have to be withdrawn on-chain with fees and waiting times.",
            "Minimum to move funds into Ark: ${groupSats(Ark.MIN_BOARD_SAT)} sats.",
            "Maximum per Ark coin: ${groupSats(Ark.MAX_VTXO_SAT)} sats.",
            "Lightning: up to ${groupSats(Ark.MAX_LIGHTNING_SAT)} sats per payment, no channels needed.",
            "Every Ark payment pre-pays recovery reserves for each coin it uses: about 2 700 sats for a coin spent whole and 4 000 for one with change (4 000 and 6 000 where the server has not enabled its smaller anchors); change must be at least 1 330 sats. Small balances can only leave Ark on-chain. The wallet shows the exact cost before you confirm.",
            "Rounds every 60 seconds; moving funds in needs 3 confirmations.",
            "Back up Ark twice: write down its words, and save a backup file after each movement (BACKUP panel). With neither, uninstalling the app or losing the phone loses the funds.",
            "Ark is beta software, through the Paperclip Ark server (ark.paperclippool.xyz).",
        ).forEach {
            Text("•  $it", style = MaterialTheme.typography.bodySmall, color = TextSoft)
            Spacer(Modifier.height(6.dp))
        }
    }
}
