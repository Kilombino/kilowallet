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
import androidx.compose.foundation.border
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.data.MarketData
import com.kilombino.pyblockwatch.data.MarketFeed
import java.util.Locale

/*
 * Simple mode: the "Wallet of Satoshi" face of the wallet. One chain (BLAKE2b / XBT),
 * a big balance with its value in USD or EUR, and Send / Receive. Everything else lives
 * in Advanced mode, which is the wallet exactly as it was. Ark will plug into Simple mode
 * as a second tab once it is validated; until then the tab explains what to expect.
 */

/** Shown once, right after the wallet exists, until the user picks a mode. */
@Composable
fun ModeChooser(vm: WalletViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(36.dp))
        AppLogo(56)
        Text("How do you want to use the wallet?",
             style = MaterialTheme.typography.headlineSmall, color = TextMain)
        Text("You can change this at any time from the top of the wallet.",
             style = MaterialTheme.typography.bodySmall, color = TextFaint)
        ModeCard(
            title = "Simple",
            lines = listOf(
                "Your BTC and Spamcoin balances, with their value in dollars or euros",
                "Send and Receive, nothing else in the way",
            ),
            accent = Purple,
        ) { vm.select(Chain.BLAKE2B); vm.setUiMode("simple") }
        ModeCard(
            title = "Advanced",
            lines = listOf(
                if (com.kilombino.pyblockwatch.ark.Ark.available) "BTC and Ark (instant payments and Lightning), beta"
                else "BTC, and Ark (instant payments and Lightning) — coming soon",
                "Coin control, fees, derivation paths, Silent Payments",
                "Address list, server certificates, gap limit",
            ),
            accent = Orange,
        ) { vm.setUiMode("advanced") }
    }
}

@Composable
private fun ModeCard(title: String, lines: List<String>, accent: Color, onClick: () -> Unit) {
    Panel(accent = accent, modifier = Modifier.clickable(onClick = onClick)) {
        Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, color = accent)
        Spacer(Modifier.height(8.dp))
        lines.forEach {
            Text("•  $it", style = MaterialTheme.typography.bodyMedium, color = TextSoft)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
fun SimpleScreen(state: UiState, vm: WalletViewModel) {
    // Two tabs: BTC (the BLAKE2b chain) and Spamcoin (the SHA-256 spamchain). Ark lives
    // in advanced mode. Send/Receive work on the selected chain.
    val chain = state.selected
    val accent = if (chain == Chain.BLAKE2B) Purple else Orange
    val cs = state.chains[chain] ?: ChainState()
    var showSend by remember { mutableStateOf(false) }
    var showReceive by remember { mutableStateOf(false) }
    // The spamchain asks first, every time, before anything connects.
    var warnSpam by remember { mutableStateOf(false) }
    // A BTC or Spamcoin notification opens that tab, refreshed (Ark and Coinjoin switch to advanced).
    val openTab by com.kilombino.pyblockwatch.data.OpenTab.flow.collectAsState()
    androidx.compose.runtime.LaunchedEffect(openTab) {
        when (openTab) {
            com.kilombino.pyblockwatch.data.OpenTab.BTC -> {
                com.kilombino.pyblockwatch.data.OpenTab.flow.value = null
                if (chain != Chain.BLAKE2B) vm.select(Chain.BLAKE2B); vm.refresh(Chain.BLAKE2B)
            }
            com.kilombino.pyblockwatch.data.OpenTab.SPAMCOIN -> {
                com.kilombino.pyblockwatch.data.OpenTab.flow.value = null
                if (chain != Chain.SHA256) vm.acceptSpamchain() else vm.refresh(Chain.SHA256)
            }
        }
    }
    fun pick(c: Chain) {
        if (c == chain) return
        showSend = false; showReceive = false
        if (c == Chain.SHA256) { if (vm.hasOwnNode(Chain.SHA256)) vm.acceptSpamchain() else warnSpam = true } else vm.select(c)
    }
    if (warnSpam) SpamchainWarning(vm, onContinue = { warnSpam = false; vm.acceptSpamchain() },
        onBack = { warnSpam = false; vm.select(Chain.BLAKE2B) })
    // One person, one node: remind when the app comes to the screen while BTC uses our server.
    var remindNode by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(AppVisible.value) {
        if (AppVisible.value && !vm.hasOwnNode(Chain.BLAKE2B) && !vm.nodeReminderShown) { vm.nodeReminderShown = true; remindNode = true }
    }
    if (remindNode) OwnNodeReminder(vm) { remindNode = false }

    // Pull down to refresh the balance and the price.
    var refreshing by remember { mutableStateOf(false) }
    val pullScope = rememberCoroutineScope()
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            pullScope.launch {
                refreshing = true
                // A manual refresh is when to remind about using your own node.
                if (chain == Chain.SHA256 && !vm.hasOwnNode(Chain.SHA256)) warnSpam = true
                else if (chain == Chain.BLAKE2B && !vm.hasOwnNode(Chain.BLAKE2B)) remindNode = true
                if (!warnSpam) vm.refresh(chain)
                vm.refreshMarket(force = true)
                kotlinx.coroutines.delay(1_500)
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.label.ifBlank { if (state.isHot) "Hot wallet" else "watch-only wallet" },
                style = MaterialTheme.typography.bodySmall, color = TextFaint, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.setUiMode("advanced") }) {
                Text("advanced", style = MaterialTheme.typography.bodySmall, color = TextSoft)
            }
            AppLogo()
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabChip("BTC", chain == Chain.BLAKE2B, Purple, Modifier.weight(1f)) { pick(Chain.BLAKE2B) }
            TabChip("SPAMCOIN", chain == Chain.SHA256, Orange, Modifier.weight(1f)) { pick(Chain.SHA256) }
        }

        SimpleBalance(chain, cs, state.market, state.fiat, accent, state.nextRefreshAt, onFiat = vm::setFiat)

        when {
            showSend -> SendSheet(vm, accent) { showSend = false }
            showReceive -> ReceiveSheet(vm, accent) { showReceive = false }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showReceive = true },
                        colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = accent),
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).height(64.dp),
                    ) { Text("RECEIVE", style = MaterialTheme.typography.titleMedium) }
                    if (state.isHot) {
                        Button(
                            onClick = { vm.resetSend(); showSend = true },
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).height(64.dp),
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
                if (state.isHot) HotWordsPanel(vm, accent)
            }
        }

        if (!showSend && !showReceive && cs.transactions.isNotEmpty())
            MovementsCard(cs.transactions, accent, vm.explorerFor(chain), vm, state.isHot,
                if (chain == Chain.BLAKE2B) "sats" else "poolsats")

        AddWidgetButton(accent)
        Spacer(Modifier.height(30.dp))
    }
    }
}

/** What the balance check is doing, in the same terms as advanced mode. */
@Composable
private fun SimpleScanStatus(phase: ScanPhase, accent: Color) {
    when (phase) {
        is ScanPhase.Idle, is ScanPhase.Complete -> Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(accent); Spacer(Modifier.width(10.dp))
            Text("Updating your balance…", style = MaterialTheme.typography.bodySmall, color = TextSoft)
        }
        is ScanPhase.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(accent); Spacer(Modifier.width(10.dp))
            Text("Updating your balance: connecting to ${phase.endpoint}…",
                 style = MaterialTheme.typography.bodySmall, color = TextSoft)
        }
        is ScanPhase.Scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
            GapRing(phase.gapUsed, phase.gapLimit, accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Updating your balance: checking your addresses", style = MaterialTheme.typography.bodySmall, color = TextSoft)
                DerivationTicker(phase.path, accent)
                Text((if (phase.chainIndex == 0) "receive" else "change") + " addresses · " +
                     "${phase.gapUsed}/${phase.gapLimit} empty in a row",
                     style = MaterialTheme.typography.bodySmall, color = TextFaint)
            }
        }
        is ScanPhase.Error -> Text("Could not update the balance: ${phase.message}. Pull down to try again.",
                                   style = MaterialTheme.typography.bodySmall, color = Bad)
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent else PanelSoft)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (selected) Ink else TextSoft)
    }
}

@Composable
private fun SimpleBalance(
    chain: Chain, cs: ChainState, market: MarketData?, fiat: String, accent: Color, nextRefreshAt: Long,
    onFiat: (String) -> Unit,
) {
    val btc = chain == Chain.BLAKE2B
    val unit = if (btc) "BTC" else "Spamcoin"
    // The countdown to the next auto-refresh, as in advanced mode, so the wallet reads as live.
    var secondsUntilRefresh by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(nextRefreshAt) {
        while (true) {
            secondsUntilRefresh = ((nextRefreshAt - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
            kotlinx.coroutines.delay(1_000)
        }
    }
    Panel(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Your $unit", accent)
            Spacer(Modifier.weight(1f))
            StreetEye(TextSoft)
            if (cs.phase is ScanPhase.Complete) {
                Text("↻ ${secondsUntilRefresh}s", style = MaterialTheme.typography.bodySmall, color = TextFaint)
                Spacer(Modifier.width(8.dp))
                PulseDot(Good, 7)
            }
        }
        // Until the first check of this launch finishes, a 0 would look like an empty wallet:
        // show the last known figure dimmed, or "…" when there is none, and say what is going on.
        val checking = cs.phase !is ScanPhase.Complete
        if (StreetMode.hidden) Text(StreetMode.MASK, style = MaterialTheme.typography.headlineMedium, color = accent)
        else Row(verticalAlignment = Alignment.Bottom) {
            Text(if (checking && cs.total == 0L) "…" else groupSats(cs.total), style = MaterialTheme.typography.displayLarge,
                 color = if (checking) accent.copy(alpha = 0.45f) else accent)
            Spacer(Modifier.width(8.dp))
            Text(if (btc) "sats" else "poolsats", style = MaterialTheme.typography.titleLarge, color = accent.copy(alpha = 0.7f))
        }
        Text(street("%.8f $unit".format(Locale.US, cs.total / 100_000_000.0)),
             style = MaterialTheme.typography.bodySmall, color = TextFaint)
        if (checking) {
            Spacer(Modifier.height(8.dp))
            SimpleScanStatus(cs.phase, accent)
        }

        Spacer(Modifier.height(10.dp))
        val px = fiatPerCoin(chain, market, fiat)
        val value = px?.let { it * cs.total / 1e8 }
        val sym = if (fiat == "EUR") "€" else "$"
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                street(if (value == null) "≈ $sym –" else "≈ $sym" + String.format(Locale.US, "%,.2f", value)),
                style = MaterialTheme.typography.headlineSmall, color = TextMain,
                modifier = Modifier.weight(1f),
            )
            FiatChip("USD", fiat == "USD", accent) { onFiat("USD") }
            Spacer(Modifier.width(6.dp))
            FiatChip("EUR", fiat == "EUR", accent) { onFiat("EUR") }
        }
        if (cs.unconfirmed != 0L) {
            Spacer(Modifier.height(4.dp))
            Text(street("unconfirmed: ${groupSats(cs.unconfirmed)} " + if (btc) "sats" else "poolsats"),
                 style = MaterialTheme.typography.bodySmall, color = Warn)
        }
        Spacer(Modifier.height(8.dp))
        val ch = market?.changePct.takeIf { btc }
        Text(
            if (px == null) "Price not available yet"
            else "1 $unit = $sym" + String.format(Locale.US, if (btc) "%,.2f" else "%,.4f", px) +
                (ch?.let { (if (it >= 0) "  ▲ " else "  ▼ ") + String.format(Locale.US, "%.2f%% 24h", kotlin.math.abs(it)) } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = when { ch == null -> TextFaint; ch >= 0 -> Good; else -> Bad },
        )
        Text(
            "Price: ${MarketFeed.SOURCE}" + (if (market != null && MarketFeed.isStale(market)) " · not recent" else ""),
            style = MaterialTheme.typography.bodySmall, color = TextFaint,
        )
    }
}

@Composable
internal fun FiatChip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) accent.copy(alpha = 0.25f) else PanelSoft)
            .border(1.dp, if (selected) accent else Line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) { Text(label, style = MaterialTheme.typography.bodySmall, color = if (selected) accent else TextSoft) }
}

/**
 * Ark is not in the wallet yet. The real limits are the ones the Paperclip Ark server
 * advertises (GetArkInfo, Oct 2026); they belong in front of the user before any money
 * goes in, not in a footnote.
 */
@Composable
internal fun ArkComingSoon(accent: Color) {
    Panel(accent = accent) {
        SectionLabel("Ark — coming soon", accent)
        Spacer(Modifier.height(8.dp))
        Explain(
            "Ark will let you send XBT instantly between wallets and pay Lightning invoices " +
                "without opening channels, through the Paperclip Ark server " +
                "(ark.paperclippool.xyz). It is not available in this version yet."
        )
    }
    Panel(accent = Warn) {
        SectionLabel("Before you use Ark", Warn)
        Spacer(Modifier.height(8.dp))
        listOf(
            "Ark funds expire after 4,320 blocks (about 30 days). The wallet must go online before then to renew them, or they have to be withdrawn on-chain with fees and waiting times.",
            "Minimum to move funds into Ark: 20,000 sats.",
            "Maximum per Ark coin: 1,000,000 sats.",
            "Lightning: up to 250,000 sats per payment, no channels needed.",
            "Rounds every 60 seconds; moving funds in needs 3 confirmations.",
            "Your seed alone cannot recover Ark funds: the full wallet backup is needed.",
            "Ark is beta software. Start with small amounts.",
        ).forEach {
            Text("•  $it", style = MaterialTheme.typography.bodySmall, color = TextSoft)
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** Offers to pin the XBT price widget, when the launcher supports doing it from an app. */
@Composable
private fun AddWidgetButton(accent: Color) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val mgr = ctx.getSystemService(android.appwidget.AppWidgetManager::class.java)
    if (mgr == null || !mgr.isRequestPinAppWidgetSupported) return
    TextButton(onClick = {
        mgr.requestPinAppWidget(
            android.content.ComponentName(ctx, com.kilombino.pyblockwatch.widget.XbtWidget::class.java), null, null)
    }) {
        Text("＋ add the BTC price widget to your home screen",
             style = MaterialTheme.typography.bodySmall, color = accent)
    }
}
