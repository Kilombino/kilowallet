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

@Composable
fun ArkScreen(accent: Color) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<ArkView>(ArkView.Starting) }
    var balance by remember { mutableStateOf<Ark.Balance?>(null) }
    var expiry by remember { mutableStateOf<Int?>(null) }
    var history by remember { mutableStateOf<List<Ark.Movement>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<String?>(null) }   // "receive" | "send" | "board"

    suspend fun reload() {
        val b = withContext(Dispatchers.IO) { runCatching { Ark.balance() }.getOrNull() }
        balance = b
        expiry = withContext(Dispatchers.IO) { Ark.blocksToNearestExpiry() }
        history = withContext(Dispatchers.IO) { runCatching { Ark.history() }.getOrDefault(emptyList()) }
    }

    fun run(label: String, block: suspend () -> String?) {
        scope.launch {
            busy = label; message = null
            message = try { block() } catch (e: Exception) { "Error: " + (e.message ?: e.toString()) }
            busy = null
            runCatching { reload() }
        }
    }

    LaunchedEffect(Unit) {
        view = try {
            withContext(Dispatchers.IO) {
                Ark.ensureStarted(ctx)
                if (Ark.hasWallet()) ArkView.Ready else ArkView.NoWallet
            }
        } catch (e: Exception) { ArkView.Failed(e.message ?: e.toString()) }
        if (view == ArkView.Ready) reload()
        // Keep the figures live while the tab is open; boards and rounds settle on their own.
        while (true) { delay(30_000); if (view == ArkView.Ready) runCatching { reload() } }
    }

    when (val v = view) {
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
            var understood by remember { mutableStateOf(false) }
            Panel(accent = accent) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { understood = !understood }) {
                    Text(if (understood) "☑" else "☐", style = MaterialTheme.typography.titleLarge, color = accent)
                    Spacer(Modifier.width(10.dp))
                    Text("I have read the warnings above. Ark is beta software and I will start with a small amount.",
                         style = MaterialTheme.typography.bodySmall, color = TextSoft)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = understood && busy == null,
                    onClick = {
                        scope.launch {
                            busy = "Creating your Ark wallet…"; message = null
                            try {
                                withContext(Dispatchers.IO) { Ark.createWallet() }
                                view = ArkView.Ready; reload()
                            } catch (e: Exception) { message = "Error: " + (e.message ?: e.toString()) }
                            busy = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                ) { Text("ACTIVATE ARK", style = MaterialTheme.typography.titleMedium) }
            }
        }
        ArkView.Ready -> {
            ArkBalancePanel(balance, expiry, accent)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArkButton("RECEIVE", sheet == "receive", accent, Modifier.weight(1f)) { sheet = if (sheet == "receive") null else "receive" }
                ArkButton("SEND", sheet == "send", accent, Modifier.weight(1f)) { sheet = if (sheet == "send") null else "send" }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArkButton("MOVE INTO ARK", sheet == "board", accent, Modifier.weight(1f)) { sheet = if (sheet == "board") null else "board" }
                ArkButton("RENEW", false, accent, Modifier.weight(1f)) {
                    run("Renewing coins…") { withContext(Dispatchers.IO) { Ark.refreshAll() }; "Renewal requested; it completes in the next round." }
                }
            }
            when (sheet) {
                "receive" -> ArkReceiveSheet(accent, busy) { action, done ->
                    run(action.first) { val r = withContext(Dispatchers.IO) { action.second() }; done(r); null }
                }
                "send" -> ArkSendSheet(accent) { dest, sats ->
                    run("Sending…") { withContext(Dispatchers.IO) { Ark.send(dest, sats) }.ifBlank { "Sent." } }
                    sheet = null
                }
                "board" -> ArkBoardSheet(balance, accent) { sats ->
                    run("Moving funds into Ark…") {
                        withContext(Dispatchers.IO) { if (sats == null) Ark.boardAll() else Ark.board(sats) }
                        "Moving into Ark. It becomes spendable after 3 confirmations."
                    }
                    sheet = null
                }
                else -> {}
            }
            if (history.isNotEmpty()) ArkHistory(history, accent)
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
private fun ArkBalancePanel(b: Ark.Balance?, expiry: Int?, accent: Color) {
    Panel(accent = accent) {
        SectionLabel("Your Ark balance", accent)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(groupSats(b?.spendable ?: 0), style = MaterialTheme.typography.displayLarge, color = accent)
            Spacer(Modifier.width(8.dp))
            Text("sats", style = MaterialTheme.typography.titleLarge, color = accent.copy(alpha = 0.7f))
        }
        if (b == null) { Text("Loading…", style = MaterialTheme.typography.bodySmall, color = TextFaint); return@Panel }
        if (b.pendingBoard > 0) Text("entering Ark: ${groupSats(b.pendingBoard)} sats (needs 3 confirmations)",
                                     style = MaterialTheme.typography.bodySmall, color = Warn)
        if (b.pendingRound > 0) Text("in the next round: ${groupSats(b.pendingRound)} sats",
                                     style = MaterialTheme.typography.bodySmall, color = Warn)
        if (b.onchainConfirmed + b.onchainPending > 0) Text(
            "deposit (on-chain, not in Ark yet): ${groupSats(b.onchainConfirmed + b.onchainPending)} sats",
            style = MaterialTheme.typography.bodySmall, color = TextSoft)
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
private fun ArkReceiveSheet(
    accent: Color, busy: String?,
    run: (Pair<String, () -> String>, (String) -> Unit) -> Unit,
) {
    val clip = LocalClipboardManager.current
    var shown by remember { mutableStateOf<Pair<String, String>?>(null) }  // label, text
    var amount by remember { mutableStateOf("") }
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
            ArkButton("⚡ INVOICE", false, accent, Modifier.width(130.dp)) {
                val sats = amount.toLongOrNull() ?: return@ArkButton
                run("Creating a Lightning invoice…" to { Ark.lightningInvoice(sats, null) }) { shown = "Lightning invoice ($sats sats)" to it }
            }
        }
        Text("Lightning: up to ${groupSats(Ark.MAX_LIGHTNING_SAT)} sats per payment. Deposits become Ark funds with MOVE INTO ARK (minimum ${groupSats(Ark.MIN_BOARD_SAT)} sats).",
             style = MaterialTheme.typography.bodySmall, color = TextFaint)
        shown?.let { (label, text) ->
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = accent)
            Spacer(Modifier.height(6.dp))
            QrImage(if (label.startsWith("Lightning")) text.uppercase() else text, 230)
            Spacer(Modifier.height(6.dp))
            SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall, color = TextMain) }
            TextButton(onClick = { clip.setText(AnnotatedString(text)) }) { Text("COPY", color = accent) }
        }
    }
}

@Composable
private fun ArkSendSheet(accent: Color, onSend: (String, Long?) -> Unit) {
    var dest by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    val clip = LocalClipboardManager.current
    Panel(accent = accent) {
        SectionLabel("Send from Ark", accent)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = dest, onValueChange = { dest = it.trim() },
            label = { Text("Ark address, Lightning invoice or XBT address") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { clip.getText()?.text?.let { dest = it.trim() } }) { Text("PASTE", color = accent) }
        OutlinedTextField(value = amount, onValueChange = { amount = it.filter(Char::isDigit) },
            label = { Text("sats (empty if the invoice has the amount)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = dest.isNotBlank(),
            onClick = { onSend(dest, amount.toLongOrNull()) },
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
        ) { Text("SEND", style = MaterialTheme.typography.titleMedium) }
        Text("Sending to an XBT address leaves Ark through the next round and pays on-chain fees.",
             style = MaterialTheme.typography.bodySmall, color = TextFaint)
    }
}

@Composable
private fun ArkBoardSheet(b: Ark.Balance?, accent: Color, onBoard: (Long?) -> Unit) {
    var amount by remember { mutableStateOf("") }
    val available = (b?.onchainConfirmed ?: 0)
    Panel(accent = accent) {
        SectionLabel("Move into Ark", accent)
        Spacer(Modifier.height(6.dp))
        Explain("Moves confirmed XBT from this wallet's deposit address into Ark. Minimum " +
            "${groupSats(Ark.MIN_BOARD_SAT)} sats; spendable after 3 confirmations. Available: ${groupSats(available)} sats.")
        OutlinedTextField(value = amount, onValueChange = { amount = it.filter(Char::isDigit) },
            label = { Text("sats (empty = everything)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        val sats = amount.toLongOrNull()
        Button(
            enabled = available >= Ark.MIN_BOARD_SAT && (sats == null || sats >= Ark.MIN_BOARD_SAT),
            onClick = { onBoard(sats) },
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
        ) { Text("MOVE INTO ARK", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun ArkHistory(items: List<Ark.Movement>, accent: Color) {
    Panel(accent = accent) {
        SectionLabel("Ark activity", accent)
        Spacer(Modifier.height(6.dp))
        items.take(20).forEach { m ->
            Row {
                Text((if (m.amount >= 0) "+" else "") + groupSats(m.amount) + " sats",
                     style = MaterialTheme.typography.bodySmall,
                     color = if (m.amount >= 0) Good else TextMain, modifier = Modifier.weight(1f))
                Text("${m.kind} · ${m.status}", style = MaterialTheme.typography.bodySmall, color = TextFaint)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** The real limits of the Paperclip Ark server, shown before any money goes in. */
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
            "Rounds every 60 seconds; moving funds in needs 3 confirmations.",
            "Your Ark wallet has its own keys, stored only on this phone. Uninstalling the app or clearing its data loses them: keep amounts small.",
            "Ark is beta software, through the Paperclip Ark server (ark.paperclippool.xyz).",
        ).forEach {
            Text("•  $it", style = MaterialTheme.typography.bodySmall, color = TextSoft)
            Spacer(Modifier.height(6.dp))
        }
    }
}
