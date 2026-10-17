package com.kilombino.pyblockwatch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.kilombino.pyblockwatch.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.kilombino.pyblockwatch.crypto.Bip39
import com.kilombino.pyblockwatch.ln.Lightning
import com.kilombino.pyblockwatch.ln.Lnd
import com.kilombino.pyblockwatch.ln.LnMove
import com.kilombino.pyblockwatch.ln.Inbound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The LN tab (advanced, hot wallet): a Lightning node on the phone, LND with the neutrino light
 * client, self-custodial. Off until the user turns it on; beta, and it says so.
 */
@Composable
fun LightningScreen(vm: WalletViewModel, accent: Color, settings: Boolean) {
    val activity = LocalContext.current as FragmentActivity
    val ctx = activity.applicationContext
    val ui by vm.state.collectAsState()
    val status by Lightning.status.collectAsState()
    Lightning.chainTip = ui.current.height

    LaunchedEffect(Unit) { Lightning.resume(ctx) }
    DisposableEffect(Unit) { Lightning.watch(true); onDispose { Lightning.watch(false) } }

    // Restore, migrate and backups show with the app's own settings (the button by ⏻):
    // closed, the tab shows only the node.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val s = status) {
            is Lightning.Status.Off -> {
                LnIntro(accent) { onError -> turnOn(activity, vm, onError) }
                if (settings) {
                    Panel(accent = accent) {
                        SectionLabel("Restore", accent)
                        Spacer(Modifier.height(6.dp))
                        Explain("The node's keys come from this wallet's recovery words: to restore a node, restore its words as this wallet first (the usual wallet restore), then choose one of the two ways below before turning Lightning on.")
                    }
                    MoveIn(accent)
                    RestoreChannels(accent)
                    LnLog(accent)
                }
            }
            is Lightning.Status.Starting -> Panel(accent = accent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(accent); Spacer(Modifier.width(8.dp))
                    Text(s.step.replaceFirstChar { it.uppercase() } + "…", color = TextSoft)
                }
                val tip = ui.current.height
                val h = s.height
                if (h != null && tip > 0) {
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { (h.toFloat() / tip).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(), color = accent, trackColor = accent.copy(alpha = 0.15f))
                    Spacer(Modifier.height(4.dp))
                    Text("block ${groupSats(minOf(h, tip).toLong())} of ${groupSats(tip.toLong())}",
                        style = MaterialTheme.typography.bodySmall, color = TextSoft)
                }
                Spacer(Modifier.height(6.dp))
                Explain("The first start downloads block headers and filters from the peers (a few hundred MB, a few minutes on Wi-Fi). Later starts only catch up. You can use the other tabs meanwhile: the node keeps going while the app is open.")
            }
            is Lightning.Status.Failed -> Panel(accent = Bad) {
                Text("Lightning did not start", color = Bad, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(s.message, style = MaterialTheme.typography.bodySmall, color = TextSoft)
                TextButton(onClick = { Lightning.resume(ctx) }) { Text("TRY AGAIN", color = accent) }
            }
            is Lightning.Status.Running -> LnNode(s, accent, settings, vm)
        }
    }
}

private fun turnOn(activity: FragmentActivity, vm: WalletViewModel, onError: (String) -> Unit) {
    val cipher = vm.seedCipherOrToast() ?: return
    Biometric.authenticate(activity, "Turn on Lightning", "The node's keys come from your recovery words", cipher,
        onSuccess = { authed ->
            activity.lifecycleScope.launch {
                runCatching {
                    val seed = withContext(Dispatchers.Default) {
                        val secret = vm.revealSecret(authed)
                        Bip39.toSeed(secret.words, secret.passphrase)
                    }
                    withContext(Dispatchers.IO) { Lightning.enable(activity.applicationContext, seed) }
                }.onFailure { onError(it.message ?: "Lightning could not be turned on") }
            }
        },
        onError = onError)
}

/**
 * Coming from another phone: its channel backup file (channel.backup, or the one inside the
 * backup file, which a restore already brings) is handed to the new node, which asks each peer
 * to close the channels and gets the money back on-chain.
 */
@Composable
private fun RestoreChannels(accent: Color) {
    val ctx = LocalContext.current
    var loaded by remember { mutableStateOf(Lightning.hasStagedScb(ctx)) }
    var msg by remember { mutableStateOf<String?>(null) }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            .onSuccess { b ->
                if (b.size < 32 || b.size > 5_000_000) msg = "That does not look like a channel backup."
                else { Lightning.stageScb(ctx, b); loaded = true; msg = null }
            }.onFailure { msg = it.message }
    }
    Panel(accent = accent) {
        SectionLabel("Restore from seed + SCB (channels close)", accent)
        Spacer(Modifier.height(6.dp))
        Explain("If your channels lived on a phone that is lost or wiped, load its channel backup (channel.backup, saved from the old LN tab) before turning on. A backup file (.kab) restored at setup already carries it. The node then asks each peer to close the channels and the money comes back on-chain, in your Lightning node, after their time lock.")
        Text("Only if the old phone no longer runs Lightning. If it is still alive, the peers may punish the old state and the money is lost.",
            style = MaterialTheme.typography.bodySmall, color = Warn)
        if (loaded) Text("✓ Channel backup loaded: it is used when you turn Lightning on.", style = MaterialTheme.typography.bodySmall, color = Good)
        Text("Every channel in the backup gets closed: the money comes back on-chain, but the channels are gone.", style = MaterialTheme.typography.bodySmall, color = Warn)
        TextButton(onClick = { open.launch(arrayOf("*/*")) }) { Text(if (loaded) "LOAD ANOTHER SCB" else "LOAD SCB", color = accent) }
        msg?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun LnIntro(accent: Color, onTurnOn: ((String) -> Unit) -> Unit) {
    var agreed by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    var alias by remember { mutableStateOf(Lightning.alias(ctx)) }
    var error by remember { mutableStateOf<String?>(null) }
    Panel(accent = accent) {
        SectionLabel("Lightning · beta", accent)
        Spacer(Modifier.height(8.dp))
        Text("Your own Lightning node, on this phone", style = MaterialTheme.typography.titleMedium, color = TextMain)
        Spacer(Modifier.height(6.dp))
        Explain("It runs LND for the BLAKE2b chain with neutrino: it follows the chain itself from compact block filters, checks the proof of work, and keeps the channels and their keys here. Nobody holds your money. The keys come from your recovery words.")
    }
    Panel(accent = Warn) {
        SectionLabel("Before you turn it on", Warn)
        Spacer(Modifier.height(6.dp))
        listOf(
            "BETA. Put in only what you can afford to lose.",
            "NEVER run Lightning with these words on two phones or profiles at once. Two nodes with the same keys break the channels and can lose their money. (Ark can be copied back; Lightning cannot.)",
            "The recovery words bring back the node's on-chain coins, but not the channels on their own. The backup file (Settings) carries the channel backup: save a new one after you open or close a channel. With it, a new phone asks each peer to close the channels and gets the money back on-chain.",
            "To receive, the phone must be online with the app open. Paying needs a channel with enough balance.",
            "Opening and closing a channel are on-chain transactions with their fees.",
            "A watchtower (Kilombino's, through Tor) watches your channels while the phone is off.",
            "The first start downloads a few hundred MB.",
        ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = TextSoft) }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = agreed, onCheckedChange = { agreed = it })
            Text("I understand, it is a beta", style = MaterialTheme.typography.bodySmall, color = TextMain)
        }
        if (Lnd.stoppedThisRun) Text("Close the app with the power button (top right) and open it again to turn Lightning on.", style = MaterialTheme.typography.bodySmall, color = Warn)
        OutlinedTextField(alias, { alias = it.take(32) }, label = { Text("Your node's name (peers see it)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Button(onClick = { Lightning.setAlias(ctx, alias); error = null; onTurnOn { error = it } }, enabled = agreed && !Lnd.stoppedThisRun, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("TURN ON LIGHTNING") }
        error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
    }

}

private enum class LnSheet { None, Fund, Open, Receive, Pay }

@Composable
private fun LnNode(s: Lightning.Status.Running, accent: Color, settings: Boolean, vm: WalletViewModel) {
    var closing by remember { mutableStateOf<List<Lnd.Channel>?>(null) }
    // Several at once: tick the channels, then close them together.
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showTx by remember { mutableStateOf<Pair<String, String>?>(null) }
    closing?.let { cs -> CloseChannelDialog(cs, accent, vm, onTx = { showTx = "Closing transaction" to it }) { closing = null; selecting = false; selected = emptySet() } }
    showTx?.let { (t, id) -> TxDialog(t, id, accent, vm) { showTx = null } }
    val book = remember(s) { ledger(s) }
    var sheet by remember { mutableStateOf(LnSheet.None) }
    val i = s.info
    val b = s.balances
    Panel(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(if (i.syncedChain) Good else Warn); Spacer(Modifier.width(8.dp))
            // At the tip but not yet "synced": the wallet is still looking through the chain's
            // filters for its own coins (a restored or moved node does this once).
            val atTip = s.tip > 0 && i.height >= s.tip
            val sync = when { i.syncedChain -> "Synced"; atTip -> "Checking the wallet's coins"; else -> "Syncing" }
            val tip = maxOf(s.tip, i.height)
            val h = if (tip > 0 && !i.syncedChain && !atTip) "block ${groupSats(i.height.toLong())} of ${groupSats(tip.toLong())}" else "block ${groupSats(i.height.toLong())}"
            Text("$sync · $h · ${i.peers} peer" + (if (i.peers == 1) "" else "s"), style = MaterialTheme.typography.bodySmall,
                color = TextSoft, modifier = Modifier.weight(1f))
            StreetEye(TextSoft)
        }
        Spacer(Modifier.height(10.dp))
        // The node holds two kinds of money: what is in channels (Lightning, spent with an
        // invoice) and its own on-chain coins (spent with PAY → on-chain or used to open channels).
        val ln = b?.channels ?: 0
        val chain = (b?.onchainConfirmed ?: 0) + (b?.onchainUnconfirmed ?: 0)
        val closingSats = s.closing.sumOf { it.limbo }
        Text(street("${groupSats(ln + chain + closingSats + (b?.pendingOpen ?: 0))} sats"), style = MaterialTheme.typography.headlineSmall, color = accent)
        Text("in the Lightning node", style = MaterialTheme.typography.bodySmall, color = TextFaint)
        Spacer(Modifier.height(8.dp))
        BalanceLine("⚡ Lightning (in channels)", ln, TextMain)
        BalanceLine("⛓ On-chain in the node", b?.onchainConfirmed ?: 0, TextSoft,
            (b?.onchainUnconfirmed ?: 0).takeIf { it > 0 }?.let { "+" + street(groupSats(it)) + " unconfirmed" })
        if ((b?.pendingOpen ?: 0) > 0) BalanceLine("⏳ Opening channels", b!!.pendingOpen, Warn)
        if (closingSats > 0) BalanceLine("⏳ Closing channels", closingSats, Warn)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(("⛓" to "FUND") to LnSheet.Fund, ("🤝" to "CHANNEL") to LnSheet.Open,
               ("⚡" to "INVOICE") to LnSheet.Receive, ("📤" to "PAY") to LnSheet.Pay).forEach { (t, v) ->
            val (icon, name) = t
            Button(onClick = { sheet = if (sheet == v) LnSheet.None else v }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = if (sheet == v) accent else accent.copy(alpha = 0.25f)),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(icon, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, color = if (sheet == v) Ink else TextMain)
                }
            }
        }
    }

    when (sheet) {
        LnSheet.Fund -> FundSheet(accent) { showTx = "On-chain transaction" to it }
        LnSheet.Open -> OpenSheet(accent, b?.onchainConfirmed ?: 0, onTx = { showTx = "Funding transaction" to it }) { sheet = LnSheet.None }
        LnSheet.Receive -> { ReceiveLn(accent, i.syncedChain && s.channels.any { it.active }); Movements(accent, book.filter { it.delta > 0 }, "Invoices paid to you") }
        LnSheet.Pay -> { PaySheet(accent, s.channels, onTx = { showTx = "On-chain payment" to it }) { sheet = LnSheet.None }; Movements(accent, book.filter { it.delta < 0 }, "Payments you made") }
        LnSheet.None -> {}
    }

    if (s.pending.isNotEmpty() || s.channels.isNotEmpty() || s.closing.isNotEmpty()) Panel(accent = accent) {
        SectionLabel("Channels (${s.channels.size}" + (if (s.pending.isNotEmpty()) " · ${s.pending.size} opening" else "") +
            (if (s.closing.isNotEmpty()) " · ${s.closing.size} closing" else "") + ")", accent)
        s.pending.forEach { p ->
            Spacer(Modifier.height(6.dp))
            Text(p.peer.take(16) + "…  " + street(groupSats(p.capacity)) + " sats", style = MaterialTheme.typography.bodySmall, color = TextMain)
            Text("opening" + (if (p.confirmationsLeft > 0) " · ${p.confirmationsLeft} confirmations left" else " · waiting for the first confirmation"),
                style = MaterialTheme.typography.bodySmall, color = Warn)
        }
        s.channels.forEach { c ->
            Spacer(Modifier.height(6.dp))
            val name = c.peerAlias.ifBlank { c.peer.take(16) + "…" }
            if (selecting) Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(checked = c.point in selected, onCheckedChange = { on ->
                    selected = if (on) selected + c.point else selected - c.point
                })
                Text(name + "  " + street(groupSats(c.capacity)) + " sats", style = MaterialTheme.typography.bodySmall, color = TextMain)
            } else
            Text(name + "  " + street(groupSats(c.capacity)) + " sats", style = MaterialTheme.typography.bodySmall, color = TextMain)
            Text((if (c.active) "active" else "offline") + " · yours " + street(groupSats(c.local)) + " · can receive " + street(groupSats(c.remote)),
                style = MaterialTheme.typography.bodySmall, color = if (c.active) TextSoft else Warn)
            if (!c.active && c.peer == Lightning.KILOMBINO_NODE && c == s.channels.first { it.peer == Lightning.KILOMBINO_NODE })
                Lightning.linkError?.let { e ->
                    // While LND finishes starting every attempt fails the same way: that is a wait, not an error.
                    val starting = e.lines().all { "still in the process of starting" in it || "starting" in it }
                    Text(if (starting) "The node is still starting: it connects by itself in a minute or two."
                         else "Cannot reach it yet; it keeps trying. " + e.lines().first().substringAfter("desc = "),
                        style = MaterialTheme.typography.labelSmall, color = TextFaint)
                }
            Row {
                TextButton(onClick = { showTx = "Funding transaction" to c.point.substringBefore(':') },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("VIEW FUNDING TX", color = accent, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(16.dp))
                TextButton(onClick = { closing = listOf(c) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("CLOSE CHANNEL", color = accent, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (s.channels.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
            if (!selecting) TextButton(onClick = { selecting = true }) { Text("CLOSE SEVERAL…", color = accent, style = MaterialTheme.typography.labelSmall) }
            else {
                TextButton(enabled = selected.isNotEmpty(), onClick = { closing = s.channels.filter { it.point in selected } }) {
                    Text("CLOSE ${selected.size} CHANNEL" + (if (selected.size == 1) "" else "S"), color = accent, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { selecting = false; selected = emptySet() }) { Text("CANCEL", color = TextFaint, style = MaterialTheme.typography.labelSmall) }
            }
        }
        s.closing.forEach { c ->
            Spacer(Modifier.height(6.dp))
            Text(c.peer.take(16) + "…  " + street(groupSats(c.capacity)) + " sats", style = MaterialTheme.typography.bodySmall, color = TextMain)
            Text(c.note + " · yours " + street(groupSats(c.limbo)), style = MaterialTheme.typography.bodySmall, color = Warn)
            if (c.txid.isNotEmpty()) TextButton(onClick = { showTx = "Closing transaction" to c.txid },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("VIEW CLOSING TX", color = accent, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    InboundPanel(accent, s, onTx = { showTx = "Funding transaction" to it })

    if (settings) {
        AliasPanel(accent, s.info.alias)
        FeeLimitPanel(accent)
        ConnectPeer(accent)
        LnLog(accent)
        SaveChannelBackup(accent)
        RestoreChannelsLive(accent)
        MoveOut(accent)
        DeleteEmptyNode(accent, s)
    }

    Panel(accent = Warn) {
        Explain("Beta. Never run Lightning with these words on another phone or profile at the same time. Node: ${i.pubkey.take(20)}…")
    }
}

@Composable
private fun FundSheet(accent: Color, onTx: (String) -> Unit) {
    val ctxF = LocalContext.current
    val clip = LocalClipboardManager.current
    var addr by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var coins by remember { mutableStateOf<List<Lnd.Utxo>>(emptyList()) }
    var txs by remember { mutableStateOf<List<Lnd.ChainTx>>(emptyList()) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { Lnd.newAddress() } }
            .onSuccess { addr = it; Lightning.rememberFundAddress(ctxF, it) }.onFailure { err = it.message }
        while (true) {
            coins = runCatching { withContext(Dispatchers.IO) { Lnd.utxos() } }.getOrDefault(coins)
            txs = runCatching {
                withContext(Dispatchers.IO) {
                    val list = Lnd.transactions()
                    // Those LND cannot follow (they pay elsewhere): ask the explorer.
                    val ext = Lnd.explorerConfirmations(list.filter { it.confirmations == 0 }.map { it.txid })
                    // A close paid to another address moves nothing in the node's wallet: show
                    // what left the channel, as a payment out.
                    val settled = runCatching { Lnd.closedSettled() }.getOrDefault(emptyMap())
                    val known = list.map { it.txid }.toSet()
                    // Payments the explorer sees and the node not yet (it learns of them only in a block).
                    Lightning.incoming.filter { it.txid !in known } + list.map { t ->
                        var u = ext[t.txid]?.takeIf { it > 0 }?.let { t.copy(confirmations = it) } ?: t
                        if (u.sats == 0L) settled[u.txid]?.takeIf { it > 0 }?.let { u = u.copy(label = "external-close", fee = -it) }
                        u
                    }
                }
            }.getOrDefault(txs)
            kotlinx.coroutines.delay(15_000)
        }
    }
    val fmt = remember { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()) }
    // Shown after the address panel below: the node's coins, then its on-chain movements.
    val lists: @Composable () -> Unit = {
        if (coins.isNotEmpty()) Panel(accent = accent) {
            SectionLabel("Coins in the node (${coins.size})", accent)
            coins.forEach { u ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(u.outpoint.take(14) + "…:" + u.outpoint.substringAfter(':'), style = MaterialTheme.typography.bodySmall, color = TextMain)
                        Text(if (u.confirmations > 0) "${u.confirmations} conf" else "unconfirmed",
                            style = MaterialTheme.typography.labelSmall, color = if (u.confirmations > 0) Good else Warn)
                    }
                    Text(street(groupSats(u.sats)), style = MaterialTheme.typography.bodyMedium, color = TextMain)
                }
            }
        }
        if (txs.isNotEmpty()) Panel(accent = accent) {
            SectionLabel("On-chain movements", accent)
            // What was left on-chain in the node after each movement, worked back from now.
            var running = coins.sumOf { it.sats } + txs.filter { it.label == "incoming" }.sumOf { it.sats }
            val lefts = txs.map { t -> running.also { running -= t.sats } }
            txs.take(30).forEachIndexed { idx, t ->
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().clickable { onTx(t.txid) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(txKind(t), style = MaterialTheme.typography.bodySmall, color = TextMain)
                        Text((if (t.time > 0) fmt.format(java.util.Date(t.time * 1000)) + " · " else "") +
                            (if (t.confirmations > 0) "${t.confirmations} conf" else "unconfirmed") + " · left " + street(groupSats(lefts[idx])),
                            style = MaterialTheme.typography.labelSmall, color = if (t.confirmations > 0) TextFaint else Warn)
                    }
                    val shown = if (t.label == "external-close") t.fee else t.sats
                    Text((if (shown >= 0 && t.label != "external-close") "+" else "−") + street(groupSats(kotlin.math.abs(shown))),
                        style = MaterialTheme.typography.bodyMedium, color = if (shown >= 0 && t.label != "external-close") Good else TextSoft)
                }
            }
        }
    }
    Panel(accent = accent) {
        SectionLabel("Fund the node", accent)
        Spacer(Modifier.height(6.dp))
        Explain("Channels are opened from the node's own on-chain coins. Send to this address from the BTC tab (or anywhere), then open a channel once it confirms.")
        Spacer(Modifier.height(8.dp))
        addr?.let { a ->
            val copier = rememberCopier()
            Column(Modifier.fillMaxWidth().clickable { copier.copy(a) }, horizontalAlignment = Alignment.CenterHorizontally) { QrImage("bitcoin:$a", 200) }
            Spacer(Modifier.height(6.dp))
            Text(a, style = MaterialTheme.typography.bodySmall, color = TextMain, modifier = Modifier.clickable { copier.copy(a) })
            TextButton(onClick = { copier.copy(a) }) { Text(if (copier.copied) "COPIED ✓" else "COPY", color = if (copier.copied) Good else accent) }
        }
        err?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
    }
    lists()
}

@Composable
private fun OpenSheet(accent: Color, onchain: Long, onTx: (String) -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var coins by remember { mutableStateOf<List<Lnd.Utxo>>(emptyList()) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) { coins = runCatching { withContext(Dispatchers.IO) { Lnd.utxos() } }.getOrDefault(emptyList()) }
    val chosen = coins.filter { it.outpoint in picked }
    var custom by remember { mutableStateOf(true) }
    var node by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("1") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    Panel(accent = accent) {
        SectionLabel("Open a channel", accent)
        Spacer(Modifier.height(6.dp))
        Explain("A channel locks some of the node's on-chain coins with one peer; what you pay goes out through it. Kilombino's node is suggested because it is well connected on this chain; any node works.")
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(selected = !custom, onClick = { custom = false }); Text("Kilombino", style = MaterialTheme.typography.bodySmall, color = TextMain)
            Spacer(Modifier.width(12.dp))
            androidx.compose.material3.RadioButton(selected = custom, onClick = { custom = true }); Text("Another node", style = MaterialTheme.typography.bodySmall, color = TextMain)
        }
        if (custom) {
            // Nodex lists this chain's Lightning nodes, with their channels and how to reach them.
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            Row(Modifier.clickable { runCatching { uri.openUri("https://nodex.kilombino.com") } }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.kilombino.pyblockwatch.R.drawable.nodex_logo),
                    contentDescription = "Nodex", modifier = Modifier.size(28.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(8.dp))
                Text("Find a node on Nodex ↗", style = MaterialTheme.typography.bodySmall, color = accent)
            }
            OutlinedTextField(node, { node = it.trim() }, label = { Text("pubkey@host:port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (coins.isNotEmpty()) {
            Text("Coins to use (none ticked: the node chooses). Every ticked coin is spent: with an amount, the channel gets it and the rest comes back to the node as one coin (they consolidate); with the amount blank, all of them go into the channel.",
                style = MaterialTheme.typography.bodySmall, color = TextSoft)
            coins.forEach { u ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = u.outpoint in picked, onCheckedChange = { on ->
                        picked = if (on) picked + u.outpoint else picked - u.outpoint
                    })
                    Column {
                        Text(street(groupSats(u.sats)) + " sats", style = MaterialTheme.typography.bodySmall, color = TextMain)
                        Text(u.outpoint.take(12) + "…:" + u.outpoint.substringAfter(':') + " · " +
                            (if (u.confirmations > 0) "${u.confirmations} conf" else "unconfirmed"),
                            style = MaterialTheme.typography.labelSmall, color = TextFaint)
                    }
                }
            }
        }
        OutlinedTextField(amount, { amount = it.filter(Char::isDigit) },
            label = { Text(if (chosen.isNotEmpty()) "Sats (blank = all ticked: " + street(groupSats(chosen.sumOf { it.sats })) + ")"
                           else "Sats (available " + street(groupSats(onchain)) + ")") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(fee, { fee = it.filter(Char::isDigit) }, label = { Text("Fee rate (sat/vB)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Explain("Minimum 20,000 sats. Leave some sats on-chain: closing and fee bumps need them. The channel is private: it is not announced and nobody routes other people's payments through your phone.")
        Spacer(Modifier.height(6.dp))
        val useAll = chosen.isNotEmpty() && amount.isBlank()
        Button(enabled = !busy && ((amount.toLongOrNull() ?: 0) >= 20_000 || (useAll && chosen.sumOf { it.sats } > 20_000)), modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = accent), onClick = {
                busy = true; msg = null
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val (pk, host) = if (custom) node.substringBefore('@') to node.substringAfter('@', "")
                                             else Lightning.KILOMBINO_NODE to Lightning.KILOMBINO_HOST
                            require(pk.length == 66) { "The node must be pubkey@host:port" }
                            if (host.isNotEmpty()) {
                                var last: Throwable? = null; var ok = false
                                for (t in Lnd.resolved(listOf(host))) { val r = runCatching { Lnd.connect(pk, t) }; if (r.isSuccess) { ok = true; break }; last = r.exceptionOrNull() }
                                if (!ok) throw last ?: IllegalStateException("could not reach the node")
                            }
                            val rate = (fee.toLongOrNull() ?: 1).coerceAtLeast(1)
                            val amt = amount.toLongOrNull() ?: 0
                            // Several coins ticked with an amount: every one of them is spent,
                            // the channel gets the amount and the rest comes back as one coin.
                            if (amt > 0 && chosen.size > 1) Lnd.openChannelAllCoins(pk, amt, rate, chosen.map { it.outpoint })
                            else Lnd.openChannel(pk, amt, rate, coins = chosen.map { it.outpoint })
                        }
                    }.onSuccess {
                        msg = "Opening: usable once it confirms (the peer decides how many blocks, usually 1 to 3). Save a new backup file (Settings) so it includes this channel." to true
                        Lightning.refresh(); onTx(it)
                        // The coins it spent are gone and the change is new: show the list as it is now.
                        picked = emptySet(); amount = ""
                        coins = runCatching { withContext(Dispatchers.IO) { Lnd.utxos() } }.getOrDefault(coins)
                    }
                     .onFailure { msg = (it.message ?: "could not open the channel") to false }
                    busy = false
                }
            }) { Text(if (busy) "OPENING…" else "OPEN CHANNEL") }
        msg?.let { (t, ok) -> Text(t, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ReceiveLn(accent: Color, ready: Boolean) {
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var amount by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var invoice by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var hash by remember { mutableStateOf<String?>(null) }
    var paid by remember { mutableStateOf<Long?>(null) }
    // Follow the invoice until it is paid, then celebrate and clear it.
    LaunchedEffect(hash) {
        val h = hash ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(2_000)
            val st = runCatching { withContext(Dispatchers.IO) { Lnd.invoiceState(h) } }.getOrNull()
            if (st == 1) { paid = amount.toLongOrNull(); invoice = null; hash = null; Lightning.refresh(); break }
            if (st == 2) { invoice = null; hash = null; break }
        }
    }
    LaunchedEffect(paid) { if (paid != null) { kotlinx.coroutines.delay(4_000); paid = null; amount = ""; memo = "" } }
    Panel(accent = accent) {
        SectionLabel("Invoice", accent)
        androidx.compose.animation.AnimatedVisibility(visible = paid != null,
            enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⚡✅", style = MaterialTheme.typography.displayLarge)
                Text("Paid! " + street(groupSats(paid ?: 0)) + " sats", style = MaterialTheme.typography.titleLarge, color = Good)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (!ready) Explain("You need an active channel, and can receive only up to what the other side holds in it (\"can receive\" under Channels). A new channel you opened starts with everything on your side.")
        OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Sats") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(memo, { memo = it }, label = { Text("Note (optional, the payer sees it)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && (amount.toLongOrNull() ?: 0) > 0, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = accent), onClick = {
                err = null; invoice = null; busy = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { Lnd.addInvoiceWithHash(amount.toLong(), memo) } }
                        .onSuccess { invoice = it.first; hash = it.second; paid = null }
                        .onFailure { err = "Could not create it: " + (it.message ?: "the node refused") }
                    busy = false
                }
            }) { Text(if (busy) "CREATING…" else "CREATE INVOICE") }
        err?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
        invoice?.let { inv ->
            Spacer(Modifier.height(8.dp))
            val copier = rememberCopier()
            Column(Modifier.fillMaxWidth().clickable { copier.copy(inv) }, horizontalAlignment = Alignment.CenterHorizontally) { QrImage(inv.uppercase(), 240) }
            Text(inv.take(40) + "…", style = MaterialTheme.typography.bodySmall, color = TextSoft, modifier = Modifier.clickable { copier.copy(inv) })
            TextButton(onClick = { copier.copy(inv) }) { Text(if (copier.copied) "COPIED ✓" else "COPY", color = if (copier.copied) Good else accent) }
            Explain("Keep the app open until it is paid. Valid for one hour.")
        }
    }
}

@Composable
private fun PayLn(accent: Color, scanned: String?, channels: List<Lnd.Channel>, onDone: () -> Unit) {
    // Which channel the payment leaves through; null lets the node choose.
    var via by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    LaunchedEffect(scanned) { if (scanned != null) text = scanned }
    var req by remember { mutableStateOf<Lnd.PayReq?>(null) }
    // A SHA-256 invoice the bridge offered to pay, with its price, until accepted or cancelled.
    var viaBridge by remember { mutableStateOf<Pair<Lnd.PayReq, com.kilombino.pyblockwatch.ln.Bridge.Estimate>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var paidSats by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(paidSats) { if (paidSats != null) { kotlinx.coroutines.delay(4_000); paidSats = null } }
    val ctx = LocalContext.current
    Panel(accent = accent) {
        SectionLabel("Pay a Lightning invoice", accent)
        androidx.compose.animation.AnimatedVisibility(visible = paidSats != null,
            enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⚡📤", style = MaterialTheme.typography.displayLarge)
                Text("Paid! " + street(groupSats(paidSats ?: 0)) + " sats", style = MaterialTheme.typography.titleLarge, color = Good)
            }
        }
        val clip = LocalClipboardManager.current
        OutlinedTextField(text, { text = it.trim(); req = null; msg = null }, label = { Text("Lightning invoice (lnbc…)") },
            modifier = Modifier.fillMaxWidth(), maxLines = 4)
        TextButton(onClick = { clip.getText()?.text?.trim()?.let { text = it; req = null; msg = null } }) { Text("PASTE", color = accent) }
        Button(enabled = text.length > 20 && !busy, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = accent), onClick = {
                scope.launch {
                    val inv = text.removePrefix("lightning:")
                    runCatching { withContext(Dispatchers.IO) { Lnd.decode(inv) } }
                        .onSuccess { r ->
                            if (r.blake2b) { req = r; return@onSuccess }
                            // A SHA-256 invoice: offer Kilombino's bridge, with its price first.
                            if (r.sats <= 0L) { msg = com.kilombino.pyblockwatch.ln.Bridge.text("no_amount") to false; return@onSuccess }
                            busy = true; msg = "Asking Kilombino's bridge for the price…" to true
                            runCatching { withContext(Dispatchers.IO) { com.kilombino.pyblockwatch.ln.Bridge.check(ctx, inv) } }
                                .onSuccess { e -> msg = null; viaBridge = r to e }
                                .onFailure { e -> msg = (e.message ?: "the bridge did not answer") to false }
                            busy = false
                        }
                        .onFailure { msg = (it.message ?: "not a valid invoice") to false }
                }
            }) { Text(if (busy && req == null && viaBridge == null) "READING…" else "READ INVOICE") }
        msg?.let { (t, ok) -> Text(t, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodySmall) }
    }
    viaBridge?.let { (sha, est) ->
        BridgePay(accent, text.removePrefix("lightning:"), sha, est,
            onPaid = { s -> viaBridge = null; paidSats = s; text = ""; scope.launch { Lightning.refresh() } },
            onFailed = { m -> viaBridge = null; msg = m to false },
            onCancel = { viaBridge = null })
    }
    req?.let { r ->
        val feeLimit = Lightning.feeLimit(ctx, r.sats)
        AlertDialog(onDismissRequest = { if (!busy) req = null },
            title = { Text("Pay ${groupSats(r.sats)} sats?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (r.description.isNotBlank()) Text("“${r.description}”", style = MaterialTheme.typography.bodySmall)
                    Text("To ${r.destination.take(20)}…", style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    Text("Routing fee at most ${groupSats(feeLimit)} sats (change it in settings).", style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    val usable = channels.filter { it.active && it.local > r.sats }
                    if (usable.size > 1) {
                        Text("Pay through:", style = MaterialTheme.typography.bodySmall, color = TextSoft)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(selected = via == null, onClick = { via = null })
                            Text("any channel (the node picks)", style = MaterialTheme.typography.bodySmall)
                        }
                        usable.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.RadioButton(selected = via == c.chanId, onClick = { via = c.chanId })
                                Text(c.peerAlias.ifBlank { c.peer.take(12) + "…" } + " · " + groupSats(c.local) + " yours",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (r.sats == 0L) Text("This invoice has no amount: it is not supported yet.", color = Bad, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && r.sats > 0, onClick = {
                    busy = true
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { Lnd.pay(text.removePrefix("lightning:"), feeLimit, outgoingChanId = via) } }
                            .onSuccess { paidSats = r.sats; msg = null; text = ""; Lightning.refresh() }
                            .onFailure { msg = Lnd.payError(it) to false }
                        busy = false; req = null
                    }
                }) { Text(if (busy) "PAYING…" else "PAY", color = accent) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { req = null }) { Text("CANCEL") } })
    }
}

/**
 * Pays a SHA-256 invoice with BTC through Kilombino's bridge, once the user has accepted its
 * price. The hold invoice the bridge hands back is checked on this phone's own node before a
 * sat leaves (same payment hash, Kilombino's node, option_blake2b, no more than accepted,
 * time left), and the preimage that comes back is checked against the invoice's hash.
 */
@Composable
private fun BridgePay(accent: Color, invoice: String, sha: Lnd.PayReq, est: com.kilombino.pyblockwatch.ln.Bridge.Estimate,
                      onPaid: (Long) -> Unit, onFailed: (String) -> Unit, onCancel: () -> Unit) {
    val B = com.kilombino.pyblockwatch.ln.Bridge
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    // The bridge asks to wait before re-quoting this invoice (see its web page): count it down.
    var wait by remember { mutableStateOf(est.waitSeconds) }
    LaunchedEffect(Unit) { while (wait > 0) { kotlinx.coroutines.delay(1_000); wait-- } }
    val feeLimit = Lightning.feeLimit(ctx, est.maxPaySat)
    AlertDialog(onDismissRequest = { if (!busy) onCancel() },
        title = { Text("Pay this SHA-256 invoice with BTC?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("This invoice is for Lightning on the SHA-256 chain. Kilombino's bridge can pay it for you: " +
                    "you pay in BTC, and it pays the invoice.", style = MaterialTheme.typography.bodySmall)
                if (sha.description.isNotBlank()) Text("“${sha.description}”", style = MaterialTheme.typography.bodySmall, color = TextSoft)
                Text("Invoice: ${groupSats(sha.sats)} sats SHA-256", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                Text("You pay about ${groupSats(est.paySat)} sats BTC", style = MaterialTheme.typography.titleMedium, color = accent)
                Text("Bridge fee ${"%.2f".format(est.spread * 100)} %, at the market price (Neoxa, checked against a second market). " +
                    "If the price moves before paying, at most ${groupSats(est.maxPaySat)} sats; above that nothing is paid. " +
                    "Plus the routing fee to the bridge, at most ${groupSats(feeLimit)} sats.",
                    style = MaterialTheme.typography.bodySmall, color = TextSoft)
                Text("Nobody holds your money: your payment is held and the bridge can only collect it by paying this invoice. " +
                    "If it can't, your BTC comes back by itself." +
                    (if (com.kilombino.pyblockwatch.chain.Tor.available) " The bridge is asked over Tor." else ""),
                    style = MaterialTheme.typography.bodySmall, color = TextSoft)
                if (wait > 0) Text(B.text("wait", org.json.JSONObject().put("wait_s", wait)), color = Bad, style = MaterialTheme.typography.bodySmall)
                if (step.isNotBlank()) Text(step, style = MaterialTheme.typography.bodySmall, color = accent)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && wait == 0, onClick = {
                busy = true
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            step = "Getting the final price…"
                            val q = B.quote(ctx, invoice, sha.hash)
                            // The payer API's checks, on this phone's own node, before a sat leaves.
                            val hold = Lnd.decode(q.holdInvoice)
                            B.mismatch(hold, hold.msat, sha, q, est.maxPaySat, System.currentTimeMillis() / 1000)
                                ?.let { throw IllegalStateException("Not paid: $it.") }
                            step = "Paying ${groupSats(B.ceilSat(hold.msat))} sats BTC… the bridge pays the invoice and then collects."
                            val preimage = try {
                                Lnd.pay(q.holdInvoice, Lightning.feeLimit(ctx, B.ceilSat(hold.msat)), timeoutSeconds = 60)
                            } catch (e: Exception) {
                                // What the bridge says about it, when it has something to say.
                                throw IllegalStateException(when (B.state(ctx, q.hash)) {
                                    "aborted" -> "Not paid: the price moved against the bridge before it paid, so it gave your BTC back. Nothing was charged."
                                    "failed" -> "Not paid: the bridge could not pay the SHA-256 invoice (no route there, or the invoice was refused). Your BTC came back."
                                    "expired" -> "Not paid: the price expired before your payment reached the bridge. Nothing was charged."
                                    else -> Lnd.payError(e) + " Nothing was charged."
                                })
                            }
                            if (!B.proves(preimage, sha.hash)) throw IllegalStateException(
                                "Paid, but the proof does not match the invoice: tell Kilombino, with the payment hash ${sha.hash}.")
                            B.ceilSat(hold.msat)
                        }
                    }
                    busy = false
                    result.onSuccess { onPaid(it) }.onFailure { onFailed(it.message ?: "Not paid.") }
                }
            }) { Text(if (busy) "PAYING…" else "PAY ${groupSats(est.paySat)} SATS", color = accent) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onCancel) { Text("CANCEL") } })
}

/** Saves LND's channel backup on its own, for a restore without the full backup file. */
@Composable
private fun SaveChannelBackup(accent: Color) {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val b = Lightning.channelBackupFile(ctx).readBytes()
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(b) }
        }.onSuccess { msg = "Channel backup saved. Save a new one after each channel you open or close." }
         .onFailure { msg = "Error: ${it.message}" }
    }
    Panel(accent = accent) {
        SectionLabel("Export channel backup (SCB)", accent)
        Spacer(Modifier.height(6.dp))
        Explain("The static channel backup: a small file, encrypted with the node's keys, that lists your channels and their peers. It does NOT keep the channels open. If this phone is lost and you have no up-to-date move file, your recovery words plus this file get the money back: the new node asks each peer to close the channel and the funds come back on-chain after their time lock. Save a new one after each channel you open or close; the backup file in Settings carries the latest one too.")
        TextButton(onClick = { save.launch("kilowallet-channel.backup") }) { Text("EXPORT SCB", color = accent) }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Error")) Bad else Good) }
    }
}

/**
 * Pays from the node's on-chain coins, with coin control: to empty the node once done with
 * Lightning (after closing the channels), or to move a coin out. Every coin is ticked to
 * start; with no amount, everything ticked goes (minus the fee).
 */
@Composable
private fun PayOnchain(accent: Color, scanned: String?, onTx: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var coins by remember { mutableStateOf<List<Lnd.Utxo>?>(null) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var address by remember { mutableStateOf("") }
    var amountFromQr by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scanned) {
        if (scanned != null) {
            address = scanned.substringBefore('?')
            // bitcoin:addr?amount=0.0001 (BTC) → sats
            scanned.substringAfter('?', "").split('&').firstOrNull { it.startsWith("amount=") }
                ?.substringAfter('=')?.toBigDecimalOrNull()?.let { amountFromQr = it.movePointRight(8).toLong().toString() }
        }
    }
    var amount by remember { mutableStateOf("") }
    LaunchedEffect(amountFromQr) { amountFromQr?.let { amount = it } }
    var fee by remember { mutableStateOf("1") }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(Unit) {
        coins = runCatching { withContext(Dispatchers.IO) { Lnd.utxos() } }.getOrDefault(emptyList())
        picked = coins.orEmpty().map { it.outpoint }.toSet()
    }
    val chosen = coins.orEmpty().filter { it.outpoint in picked }
    val total = chosen.sumOf { it.sats }
    val all = amount.isBlank()
    Panel(accent = accent) {
        SectionLabel("Pay on-chain from the node", accent)
        Spacer(Modifier.height(6.dp))
        Explain("To empty the node when you are done with Lightning (close the channels first), or to move a coin out. Tick the coins to spend; with no amount, all of them go.")
        when {
            coins == null -> Text("Loading the node's coins…", style = MaterialTheme.typography.bodySmall, color = TextFaint)
            coins!!.isEmpty() -> Text("The node has no on-chain coins.", style = MaterialTheme.typography.bodySmall, color = TextFaint)
            else -> coins!!.forEach { u ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = u.outpoint in picked, onCheckedChange = { on ->
                        picked = if (on) picked + u.outpoint else picked - u.outpoint
                    })
                    Column {
                        Text(street(groupSats(u.sats)) + " sats", style = MaterialTheme.typography.bodySmall, color = TextMain)
                        Text(u.outpoint.take(12) + "…:" + u.outpoint.substringAfter(':') + " · " +
                            (if (u.confirmations > 0) "${u.confirmations} conf" else "unconfirmed"),
                            style = MaterialTheme.typography.labelSmall, color = TextFaint)
                    }
                }
            }
        }
        OutlinedTextField(address, { address = it.trim() }, label = { Text("Address (bc1…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { clip.getText()?.text?.trim()?.removePrefix("bitcoin:")?.substringBefore('?')?.let { address = it } }) { Text("PASTE", color = accent) }
        OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Sats (blank = all ticked: " + street(groupSats(total)) + ")") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(fee, { fee = it.filter(Char::isDigit) }, label = { Text("Fee rate (sat/vB)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Explain("With open anchor channels, LND keeps a reserve on-chain for fee bumping, so it may not let you send everything.")
        Button(enabled = !busy && chosen.isNotEmpty() && address.length > 20 && (all || (amount.toLongOrNull() ?: 0) > 0),
            modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = accent),
            onClick = { confirm = true }) { Text(if (busy) "SENDING…" else "SEND") }
        msg?.let { (t, ok) -> Text(t, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(if (all) "Send everything ticked?" else "Send ${groupSats(amount.toLongOrNull() ?: 0)} sats?") },
        text = {
            Text("From ${chosen.size} coin(s) (${groupSats(total)} sats) of the Lightning node to ${address.take(14)}…${address.takeLast(6)} at " +
                "${fee.ifBlank { "1" }} sat/vB." + (if (all) " The fee comes out of the amount." else ""),
                style = MaterialTheme.typography.bodySmall)
        },
        confirmButton = {
            TextButton(onClick = {
                confirm = false; busy = true; msg = null
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            Lnd.sendCoins(address.removePrefix("bitcoin:"), amount.toLongOrNull() ?: 0,
                                (fee.toLongOrNull() ?: 1).coerceAtLeast(1), all, chosen.map { it.outpoint })
                        }
                    }.onSuccess { msg = "Sent." to true; onTx(it); Lightning.refresh()
                        coins = runCatching { withContext(Dispatchers.IO) { Lnd.utxos() } }.getOrNull() }
                     .onFailure { msg = (it.message ?: "could not send") to false }
                    busy = false
                }
            }) { Text("SEND", color = accent) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("CANCEL") } })
}

@Composable
private fun PasswordFields(pw1: String, pw2: String?, on1: (String) -> Unit, on2: ((String) -> Unit)?) {
    OutlinedTextField(pw1, on1, label = { Text("password") }, singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
    if (pw2 != null && on2 != null) OutlinedTextField(pw2, on2, label = { Text("repeat password") }, singleLine = true,
        isError = pw2.isNotEmpty() && pw1 != pw2,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
}

/**
 * Moves the node, channels open, to another phone: a password-protected file with its whole
 * state. Once the file is saved the node is deleted here, so it can never run twice.
 */
@Composable
private fun MoveOut(accent: Color) {
    val activity = LocalContext.current as FragmentActivity
    val ctx = activity.applicationContext
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf(false) }
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var pack by remember { mutableStateOf<ByteArray?>(null) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val b = pack ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            // Not saved: the node stays here, stopped; it starts again with the app.
            pack = null; msg = "Not saved. Lightning stays on this phone (it starts again when you reopen the app)." to false
            return@rememberLauncherForActivityResult
        }
        runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(b) }
            val back = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            check(back.contentEquals(b)) { "the saved file does not match" }
        }.onSuccess { pack = null; LnMove.forgetHere(ctx) }
         .onFailure { msg = "Error saving: ${it.message}. Lightning stays on this phone." to false }
    }
    Panel(accent = accent) {
        SectionLabel("Move Lightning to another phone", accent)
        Spacer(Modifier.height(6.dp))
        Explain("Takes the node with its channels OPEN to a new phone: a file protected by a password, with the whole node. Once it is saved, Lightning is deleted from this phone. On the new phone, before turning Lightning on, choose MOVE IN FROM ANOTHER PHONE.")
        Text("Never use a copy of this file twice, and never turn Lightning on again here with these words while the other phone runs it: an old channel state is punished by losing the whole channel.",
            style = MaterialTheme.typography.bodySmall, color = Warn)
        TextButton(onClick = { ask = true; msg = null }) { Text("MOVE TO ANOTHER PHONE", color = accent) }
        msg?.let { (t, ok) -> Text(t, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodySmall) }
    }
    if (ask) AlertDialog(onDismissRequest = { ask = false },
        title = { Text("Move Lightning out of this phone?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The node stops, its whole state goes into a file protected by this password, and Lightning is deleted here once the file is saved. Keep the file and the password: they are the only copy of the open channels until the other phone takes it in.",
                    style = MaterialTheme.typography.bodySmall)
                PasswordFields(pw1, pw2, { pw1 = it }, { pw2 = it })
            }
        },
        confirmButton = {
            TextButton(enabled = pw1.length >= 8 && pw1 == pw2, onClick = {
                val pw = pw1; ask = false; pw1 = ""; pw2 = ""
                Biometric.confirm(activity, "Move Lightning out", "Confirm it is you",
                    onSuccess = {
                        msg = "Stopping the node and packing it…" to true
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { LnMove.export(ctx, pw) } }
                                .onSuccess { pack = it; msg = null; save.launch("kilowallet-lightning.move") }
                                .onFailure { msg = "Error: ${it.message}" to false }
                        }
                    },
                    onError = { msg = it to false })
            }) { Text("STOP AND PACK", color = Warn) }
        },
        dismissButton = { TextButton(onClick = { ask = false; pw1 = ""; pw2 = "" }) { Text("CANCEL") } })
}

/** Takes in a node moved from another phone ([MoveOut]), before Lightning is turned on here. */
@Composable
private fun MoveIn(accent: Color) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<ByteArray?>(null) }
    var pw by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            .onSuccess { file = it; msg = null }.onFailure { msg = it.message }
    }
    Panel(accent = accent) {
        SectionLabel("Migrate to this phone (channels stay open)", accent)
        Spacer(Modifier.height(6.dp))
        Explain("If the old phone saved its node with MOVE TO ANOTHER PHONE, load that file here: the node starts with its channels open, as they were.")
        if (Lightning.movedAway(ctx)) Text("This phone's own node was moved out. Turn Lightning on here again only if the other phone no longer runs it.",
            style = MaterialTheme.typography.bodySmall, color = Warn)
        if (Lnd.stoppedThisRun) Text("Close the app with the power button (top right) and open it again first: the old node has to let go of its files.",
            style = MaterialTheme.typography.bodySmall, color = Warn)
        else if (file == null) TextButton(onClick = { open.launch(arrayOf("*/*")) }) { Text("MOVE IN FROM ANOTHER PHONE", color = accent) }
        else {
            PasswordFields(pw, null, { pw = it }, null)
            TextButton(enabled = pw.isNotEmpty(), onClick = {
                val b = file!!; val p = pw
                msg = "Unpacking…"
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { LnMove.import(ctx, b, p) } }
                        .onSuccess { file = null; pw = ""; msg = null; Lightning.resume(ctx) }
                        .onFailure { msg = it.message }
                }
            }) { Text("OPEN AND START", color = accent) }
        }
        msg?.let { Text(it, color = if (it == "Unpacking…") TextSoft else Bad, style = MaterialTheme.typography.bodySmall) }
    }
}

/** With the node running: hand it a channel backup, so the channels in it get closed and paid back. */
@Composable
private fun RestoreChannelsLive(accent: Color) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<ByteArray?>(null) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            .onSuccess { file = it }.onFailure { msg = (it.message ?: "could not read the file") to false }
    }
    Panel(accent = accent) {
        SectionLabel("Restore channels from an SCB", accent)
        Spacer(Modifier.height(6.dp))
        Explain("For channels of an old phone that is gone (same recovery words): this node asks each of their peers to close them, and the money comes back on-chain after the time lock.")
        Text("Every channel in that file gets closed. Only if the old phone no longer runs Lightning.", style = MaterialTheme.typography.bodySmall, color = Warn)
        TextButton(onClick = { msg = null; open.launch(arrayOf("*/*")) }) { Text("LOAD SCB AND CLOSE ITS CHANNELS", color = accent) }
        msg?.let { (t, ok) -> Text(t, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodySmall) }
    }
    file?.let { b ->
        AlertDialog(onDismissRequest = { file = null },
            title = { Text("Close the channels in this backup?") },
            text = { Text("The node asks each peer to close them and the money comes back on-chain. This cannot be undone.", style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    file = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { Lnd.restoreChannelBackups(b) } }
                            .onSuccess { msg = "Done: the peers are being asked to close. The money shows on-chain in the node after their time lock." to true; Lightning.refresh() }
                            .onFailure { msg = (it.message ?: "the node refused the backup") to false }
                    }
                }) { Text("CLOSE THEM", color = Warn) }
            },
            dismissButton = { TextButton(onClick = { file = null }) { Text("CANCEL") } })
    }
}

/**
 * An empty node (no channels, nothing on-chain) can be deleted, so another phone's node can
 * be moved in here. One that holds money cannot: close the channels and send the coins out first.
 */
@Composable
private fun DeleteEmptyNode(accent: Color, s: Lightning.Status.Running) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf(false) }
    var closeNow by remember { mutableStateOf(false) }
    val activity = LocalContext.current as FragmentActivity
    val b = s.balances
    val empty = s.channels.isEmpty() && s.pending.isEmpty() && b != null &&
        b.onchainConfirmed == 0L && b.onchainUnconfirmed == 0L && b.channels == 0L && b.pendingOpen == 0L
    Panel(accent = accent) {
        SectionLabel("Delete this node", accent)
        Spacer(Modifier.height(6.dp))
        Explain(if (empty) "This node holds nothing. Delete it to move another phone's node in here (MIGRATE TO THIS PHONE, then shown with Lightning off)."
                else "Only an empty node can be deleted: close its channels and send its on-chain coins out (PAY → on-chain) first.")
        TextButton(enabled = empty, onClick = { ask = true }) { Text("DELETE THIS NODE", color = if (empty) Warn else TextFaint) }
    }
    // The engine cannot start again in the same session: the app closes, and the next open
    // can turn Lightning on or move another node in.
    if (closeNow) AlertDialog(onDismissRequest = {},
        title = { Text("Node deleted") },
        text = { Text("The app closes now. Open it again to turn Lightning on or to move another phone's node in.", style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = { quitApp(activity) }) { Text("CLOSE THE APP", color = accent) } })
    if (ask) AlertDialog(onDismissRequest = { ask = false },
        title = { Text("Delete this Lightning node?") },
        text = { Text("It holds no money. Turning Lightning on again later makes a new node from your words.", style = MaterialTheme.typography.bodySmall) },
        confirmButton = {
            TextButton(onClick = {
                ask = false
                scope.launch { withContext(Dispatchers.IO) { Lightning.deleteNode(ctx) }; closeNow = true }
            }) { Text("DELETE", color = Warn) }
        },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("CANCEL") } })
}

/**
 * PAY: one camera button for both. A Lightning invoice (lnbc…, lightning:) goes to the
 * invoice field; a Bitcoin address or bitcoin: URI to the on-chain payment.
 */
@Composable
private fun PaySheet(accent: Color, channels: List<Lnd.Channel>, onTx: (String) -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var invoice by remember { mutableStateOf<String?>(null) }
    var onchain by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val camera = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> if (ok) scanning = true }
    if (scanning) QrScannerDialog(onResult = { raw ->
        scanning = false
        val t = raw.trim()
        val low = t.lowercase()
        when {
            low.startsWith("lightning:") -> { invoice = t.substring(10); msg = null }
            low.startsWith("lnbc") -> { invoice = t; msg = null }
            low.startsWith("bitcoin:") -> {
                // A unified QR carries both: pay over Lightning when it has an invoice.
                val ln = t.substringAfter('?', "").split('&').firstOrNull { it.lowercase().startsWith("lightning=") }?.substringAfter('=')
                if (ln != null) invoice = ln else onchain = t.substring(8)
                msg = null
            }
            low.startsWith("bc1") || low.startsWith("1") || low.startsWith("3") -> { onchain = t; msg = null }
            else -> msg = "That QR is neither a Lightning invoice nor a Bitcoin address."
        }
    }, onDismiss = { scanning = false })
    Button(onClick = {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) scanning = true
        else camera.launch(android.Manifest.permission.CAMERA)
    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.25f)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
        Text("📷 SCAN A QR (INVOICE OR ADDRESS)", color = TextMain, style = MaterialTheme.typography.labelSmall)
    }
    msg?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
    PayLn(accent, invoice, channels, onDone)
    PayOnchain(accent, onchain, onTx)
}

/** Connects to a node by hand (pubkey@host:port): for a peer the wallet does not reach by itself. */
@Composable
private fun ConnectPeer(accent: Color) {
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    Panel(accent = accent) {
        SectionLabel("Connect to a node", accent)
        Spacer(Modifier.height(6.dp))
        Explain("pubkey@host:port. A channel's peer is reconnected by itself; this is for when it is not, or to reach a new node.")
        OutlinedTextField(uri, { uri = it.trim() }, label = { Text("pubkey@host:port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = uri.length > 70 && '@' in uri, onClick = {
            msg = null
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val pk = uri.substringBefore('@'); val host = uri.substringAfter('@')
                        val tries = Lnd.resolved(listOf(host))
                        var last: Throwable? = null
                        for (t in tries) { val r = runCatching { Lnd.connect(pk, t) }; if (r.isSuccess) return@withContext t; last = r.exceptionOrNull() }
                        throw last ?: IllegalStateException("no address")
                    }
                }.onSuccess { msg = "Connected through $it." to true; Lightning.refresh() }
                 .onFailure { msg = (it.message ?: "could not connect") to false }
            }
        }) { Text("CONNECT", color = accent) }
        msg?.let { (t, ok) -> Text(t, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Shares the end of LND's log: when something fails, it says why. */
@Composable
private fun LnLog(accent: Color) {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    Panel(accent = accent) {
        SectionLabel("Node log", accent)
        Spacer(Modifier.height(6.dp))
        Explain("If something fails, share this with whoever helps you. It shows peers, channels and amounts, never your words or keys.")
        TextButton(onClick = {
            val log = Lnd.logTail(ctx)
            if (log.isNullOrBlank()) { msg = "The node has not written a log yet."; return@TextButton }
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Kilowallet Lightning log")
                putExtra(android.content.Intent.EXTRA_TEXT, log)
            }
            runCatching { ctx.startActivity(android.content.Intent.createChooser(send, "Share the Lightning log")) }
                .onFailure { msg = "Could not open the share menu." }
        }) { Text("SHARE NODE LOG", color = accent) }
        msg?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
    }
}

/** One movement in the channels: [delta] in sats (+ received, − paid with its fee), [left] after it. */
@Composable
private fun BalanceLine(label: String, sats: Long, color: Color, note: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f))
        Text(street(groupSats(sats)) + " sats", style = MaterialTheme.typography.bodySmall, color = color)
    }
    note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = TextFaint) }
}

private data class LnEntry(val time: Long, val delta: Long, val label: String, val left: Long)

/**
 * Received invoices and sent payments, newest first, each with what was left in the channels
 * after it: worked back from the balance now (channel opens and closes are not in it).
 */
private fun ledger(s: Lightning.Status.Running): List<LnEntry> {
    data class E(val time: Long, val delta: Long, val label: String)
    val events = s.invoices.filter { it.state == 1 && it.paid > 0 }.map { E(it.settled, it.paid, it.memo.ifBlank { "invoice" }) } +
        s.payments.filter { it.status == 2 }.map { E(it.timeSec, -(it.sats + it.fee), if (it.fee > 0) "payment · fee ${it.fee}" else "payment") }
    var running = s.balances?.channels ?: 0L
    return events.sortedByDescending { it.time }.map { e ->
        LnEntry(e.time, e.delta, e.label, running).also { running -= e.delta }
    }
}

@Composable
private fun Movements(accent: Color, moves: List<LnEntry>, title: String) {
    if (moves.isEmpty()) return
    val fmt = remember { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()) }
    Panel(accent = accent) {
        SectionLabel(title, accent)
        moves.take(20).forEach { m ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.label, style = MaterialTheme.typography.bodySmall, color = TextMain, maxLines = 1)
                    Text(fmt.format(java.util.Date(m.time * 1000)) + " · left " + street(groupSats(m.left)),
                        style = MaterialTheme.typography.labelSmall, color = TextFaint)
                }
                Text((if (m.delta > 0) "+" else "−") + street(groupSats(kotlin.math.abs(m.delta))),
                    style = MaterialTheme.typography.bodyMedium, color = if (m.delta > 0) Good else TextSoft)
            }
        }
    }
}

/**
 * Closing a channel: cooperatively to this wallet's BTC account, the node's on-chain coins or a
 * pasted address; or forced, for a peer that is gone (the money then waits the channel's delay).
 */
@Composable
private fun CloseChannelDialog(cs: List<Lnd.Channel>, accent: Color, vm: WalletViewModel, onTx: (String) -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val c = cs.first()
    // One fresh address of the BTC wallet per channel, so the closes do not share one.
    val btcAll = remember { runCatching { val i = vm.nextReceiveIndex(); cs.indices.mapNotNull { k -> vm.receiveAddress(i + k)?.first } }.getOrDefault(emptyList()) }
    val btc = btcAll.firstOrNull()
    var dest by remember { mutableStateOf(if (btc != null) 0 else 1) }   // 0 BTC wallet, 1 node, 2 pasted
    var pasted by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("1") }
    var force by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDone() },
        title = { Text(if (cs.size > 1) "Close ${cs.size} channels?" else "Close the channel with ${c.peerAlias.ifBlank { c.peer.take(12) + "…" }}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text(if (cs.size > 1) "Your ${groupSats(cs.sumOf { it.local })} sats come back on-chain, minus the closing fees (one transaction per channel)."
                    else "Your ${groupSats(cs.sumOf { it.local })} sats come back on-chain, minus the closing fee.",
                    style = MaterialTheme.typography.bodySmall)
                if (!force) {
                    Text("Send them to:", style = MaterialTheme.typography.bodySmall)
                    if (btc != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = dest == 0, onClick = { dest = 0 })
                        Text("My BTC wallet (" + btc.take(10) + "…)", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = dest == 1, onClick = { dest = 1 })
                        Text("The Lightning node's on-chain coins", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = dest == 2, onClick = { dest = 2 })
                        Text("Another address", style = MaterialTheme.typography.bodySmall)
                    }
                    if (dest == 2) OutlinedTextField(pasted, { pasted = it.trim().removePrefix("bitcoin:").substringBefore('?') },
                        label = { Text("bc1…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(fee, { fee = it.filter(Char::isDigit) }, label = { Text("Fee rate (sat/vB)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = force, onCheckedChange = { force = it })
                    Text("Force close (only if the other node is gone for good)", style = MaterialTheme.typography.bodySmall)
                }
                if (force) Text("A forced close needs no answer from the peer, but your sats stay locked for the channel's delay (often 1–2 weeks) and the fee is set by the channel, not by you. It goes to the node's on-chain coins.",
                    style = MaterialTheme.typography.bodySmall, color = Warn)
                msg?.let { (t, ok) -> Text(t, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (msg?.second == true) TextButton(onClick = onDone) { Text("OK", color = accent) }
            else TextButton(enabled = !busy && (force || dest != 2 || pasted.length > 20), onClick = {
                busy = true; msg = null
                val to = when { force -> null; dest == 0 -> btc; dest == 2 -> pasted; else -> null }
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val rate = (fee.toLongOrNull() ?: 1).coerceAtLeast(1)
                            var last = ""
                            val failed = ArrayList<String>()
                            cs.forEachIndexed { k, ch ->
                                val addr = if (!force && dest == 0) btcAll.getOrNull(k) ?: to else to
                                runCatching { Lnd.closeChannel(ch.point, force, addr, rate) }
                                    .onSuccess { last = it }.onFailure { failed += (ch.peerAlias.ifBlank { ch.peer.take(10) }) + ": " + it.message }
                            }
                            if (last.isEmpty()) throw IllegalStateException(failed.joinToString("; "))
                            if (failed.isNotEmpty()) msg = ("Closed some; not: " + failed.joinToString("; ")) to false
                            last
                        }
                    }
                        .onSuccess { Lightning.refresh(); onDone(); onTx(it) }
                        .onFailure { msg = (it.message ?: "could not close") to false }
                    busy = false
                }
            }) { Text(if (busy) "CLOSING…" else if (force) "FORCE CLOSE" else "CLOSE", color = if (force) Bad else accent) }
        },
        dismissButton = { if (msg?.second != true) TextButton(enabled = !busy, onClick = onDone) { Text("CANCEL") } })
}

/** A transaction of the node: its id to copy, and the explorer to look at it. */
@Composable
private fun TxDialog(title: String, txid: String, accent: Color, vm: WalletViewModel, onDone: () -> Unit) {
    val clip = LocalClipboardManager.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val explorer = vm.explorerFor(com.kilombino.pyblockwatch.chain.Chain.BLAKE2B)
    AlertDialog(onDismissRequest = onDone,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(txid, style = MaterialTheme.typography.bodySmall)
                }
                Text("It shows on the explorer while it waits for its confirmations.", style = MaterialTheme.typography.bodySmall, color = TextSoft)
            }
        },
        confirmButton = {
            TextButton(onClick = { runCatching { uri.openUri("$explorer/tx/$txid") } }) { Text("OPEN IN EXPLORER", color = accent) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { clip.setText(AnnotatedString(txid)) }) { Text("COPY", color = accent) }
                TextButton(onClick = onDone) { Text("CLOSE") }
            }
        })
}

/** How much a Lightning payment may cost in routing fees, at most. */
@Composable
private fun FeeLimitPanel(accent: Color) {
    val ctx = LocalContext.current
    var pct by remember { mutableStateOf(Lightning.feePercent(ctx)) }
    var min by remember { mutableStateOf(Lightning.feeMinSats(ctx).toString()) }
    Panel(accent = accent) {
        SectionLabel("Routing fee limit", accent)
        Spacer(Modifier.height(6.dp))
        Explain("The most a payment may cost in routing fees: a share of what you pay, never less than the minimum. A payment that would cost more is not sent. Example: " +
            groupSats(Lightning.feeLimit(ctx, 10_000)) + " sats on 10,000.")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.5f, 1f, 2f, 3f, 5f).forEach { v ->
                val on = kotlin.math.abs(pct - v) < 0.01f
                TextButton(onClick = { pct = v; Lightning.setFeeLimit(ctx, v, min.toLongOrNull() ?: 10L) }) {
                    Text((if (v % 1f == 0f) v.toInt().toString() else v.toString()) + "%", color = if (on) accent else TextFaint)
                }
            }
        }
        OutlinedTextField(min, { v -> min = v.filter(Char::isDigit); Lightning.setFeeLimit(ctx, pct, min.toLongOrNull() ?: 0L) },
            label = { Text("Minimum (sats)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    }
}

/** Renames the node: saved now, used from the next start (LND reads it from its configuration). */
@Composable
private fun AliasPanel(accent: Color, current: String) {
    val ctx = LocalContext.current
    var alias by remember { mutableStateOf(Lightning.alias(ctx)) }
    var saved by remember { mutableStateOf(false) }
    Panel(accent = accent) {
        SectionLabel("Node name", accent)
        Spacer(Modifier.height(6.dp))
        Explain("What peers see your node as. Now: " + current.ifBlank { "—" } + ". A new name takes effect the next time the app starts.")
        OutlinedTextField(alias, { alias = it.take(32); saved = false }, label = { Text("name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = alias.isNotBlank(), onClick = { Lightning.setAlias(ctx, alias); saved = true }) { Text("SAVE", color = accent) }
        if (saved) Text("Saved: close and reopen the app to use it.", style = MaterialTheme.typography.bodySmall, color = Good)
    }
}

/** What an on-chain movement of the node was, from LND's label and its amount. */
private fun txKind(t: Lnd.ChainTx): String {
    val l = t.label.lowercase()
    return when {
        "openchannel" in l -> "channel opened"
        l == "external-close" -> "channel closed · sent to another address (from the channel)"
        l == "incoming" -> "incoming · in the mempool"
        ("closechannel" in l || "close" in l) && t.sats == 0L -> "channel closed · sent to another address"
        "closechannel" in l || "close" in l -> "channel closed"
        "sweep" in l -> "swept back"
        t.sats >= 0 -> "received"
        else -> "sent"
    }
}

/**
 * Asking Kilombino's node for a channel towards this one (inbound liquidity), kept low-key at
 * the bottom: a private channel of whole millions, for 5,000 sats per million, kept open at
 * least the service's minimum days. The fee goes as a hold invoice, settled only once the
 * channel is out; if it cannot be opened the payment comes back by itself.
 */
@Composable
private fun InboundPanel(accent: Color, s: Lightning.Status.Running, onTx: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<Inbound.Info?>(null) }
    var millions by remember { mutableStateOf(1) }
    var quote by remember { mutableStateOf<Inbound.Quote?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var orderId by remember { mutableStateOf<String?>(null) }
    val canPay = s.channels.any { it.active && it.local > 0 }

    // Follow the request until the channel is out (or it fails and the fee comes back).
    LaunchedEffect(orderId) {
        val id = orderId ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(4_000)
            val o = runCatching { withContext(Dispatchers.IO) { Inbound.order(id) } }.getOrNull() ?: continue
            when (o.status) {
                "opening", "open" -> { status = "✅ Channel on its way: usable once it confirms."; o.fundingTxid?.let(onTx); Lightning.refresh(); orderId = null; break }
                "failed" -> { status = null; err = "Not opened: " + (o.reason ?: "unknown") + ". The fee comes back by itself."; orderId = null; break }
                "expired" -> { status = null; err = "The request expired without being paid."; orderId = null; break }
            }
        }
    }

    Panel(accent = accent) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text("Need to receive more? Inbound channel from Kilombino's node", style = MaterialTheme.typography.bodySmall,
                color = TextSoft, modifier = Modifier.weight(1f))
            Text(if (open) "▲" else "▼", color = TextFaint)
        }
        if (!open) return@Panel
        LaunchedEffect(Unit) {
            info = runCatching { withContext(Dispatchers.IO) { Inbound.info() } }
                .onFailure { err = "The service does not answer: " + it.message }.getOrNull()
        }
        Spacer(Modifier.height(6.dp))
        Explain("You can only receive up to what the other side holds in your channels. Kilombino's node can open a private channel towards yours, with the sats on its side, so you can receive that much. It is not a deposit: those sats are not yours until someone pays you.")
        val i = info
        if (i == null) { if (err == null) Text("Asking the service…", style = MaterialTheme.typography.bodySmall, color = TextFaint) }
        else {
            val maxM = (minOf(i.maxSats, i.availableSats) / 1_000_000).toInt()
            Text("Fee: " + groupSats(i.feePerMillion) + " sats per million · kept open at least ${i.minDays} days, longer while it is used · private",
                style = MaterialTheme.typography.bodySmall, color = TextSoft)
            if (maxM < 1) Text("No liquidity free right now: try again later.", style = MaterialTheme.typography.bodySmall, color = Warn)
            else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = millions > 1, onClick = { millions-- }) { Text("−", color = accent) }
                    Text("$millions million sats", style = MaterialTheme.typography.titleSmall, color = TextMain)
                    TextButton(enabled = millions < maxM, onClick = { millions++ }) { Text("+", color = accent) }
                }
                Text("Fee " + groupSats(i.feePerMillion * millions) + " sats · up to $maxM million now", style = MaterialTheme.typography.labelSmall, color = TextFaint)
                if (!canPay) Text("The fee is paid over Lightning: you need an active channel with some sats on your side first.",
                    style = MaterialTheme.typography.bodySmall, color = Warn)
                Button(enabled = !busy && orderId == null && canPay, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent), onClick = {
                        busy = true; err = null; status = null
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { Inbound.quote(s.info.pubkey, millions * 1_000_000L) } }
                                .onSuccess { quote = it }.onFailure { err = it.message }
                            busy = false
                        }
                    }) { Text(if (busy) "ASKING…" else "ASK FOR $millions MILLION") }
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Good) }
        err?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Bad) }
    }

    quote?.let { q ->
        val ctx = LocalContext.current
        AlertDialog(onDismissRequest = { quote = null },
            title = { Text("Pay ${groupSats(q.fee)} sats for an inbound channel of ${groupSats(q.sats)}?") },
            text = {
                Text("Kilombino's node opens a private channel of ${groupSats(q.sats)} sats towards yours, kept open at least ${info?.minDays ?: 90} days. " +
                    "The fee is held, not taken, until the channel's transaction is out; if it cannot be opened, it comes back by itself. " +
                    "This offer lasts 10 minutes.", style = MaterialTheme.typography.bodySmall)
            },
            confirmButton = {
                TextButton(onClick = {
                    quote = null; orderId = q.id; status = "Paying the fee and waiting for the channel…"
                    scope.launch {
                        // The payment only completes once the channel is out (hold invoice).
                        runCatching { withContext(Dispatchers.IO) { Lnd.pay(q.invoice, Lightning.feeLimit(ctx, q.fee), 120) } }
                            .onFailure { e -> if (orderId != null) { err = "The fee was not paid: " + (e.message ?: "failed"); status = null } }
                    }
                }) { Text("PAY AND OPEN", color = accent) }
            },
            dismissButton = { TextButton(onClick = { quote = null }) { Text("CANCEL") } })
    }
}
