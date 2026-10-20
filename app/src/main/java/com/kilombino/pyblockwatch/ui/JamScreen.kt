package com.kilombino.pyblockwatch.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.data.JoinMarket
import com.kilombino.pyblockwatch.ui.i18n.Text

/** The JoinMarket wallet's colour: Jam's jars, between the watch-only orange and the BTC purple. */
val JamTeal = Color(0xFF2EC4B6)

/**
 * The third wallet: a JoinMarket (Jam) wallet, watched from Jam's read-only export. Its five jars
 * with their balances and movements, and its fidelity bonds with their state. No SEND, on
 * purpose: this wallet has no keys, and spending (bonds included) happens in Jam.
 */
@Composable
fun JamScreen(vm: WalletViewModel, jvm: JamViewModel, onToggleNotifications: (Boolean) -> Unit) {
    val state by vm.state.collectAsState()
    val js by jvm.state.collectAsState()
    val accent = JamTeal
    var showSettings by remember { mutableStateOf(false) }
    var reimport by remember { mutableStateOf(false) }
    var askForget by remember { mutableStateOf(false) }
    var openJar by remember { mutableStateOf<Int?>(null) }

    // One person, one node: nothing connects to the default server until the user says so.
    val mayRead = jvm.hasOwnServer() || vm.btcConsented()
    var remindNode by remember { mutableStateOf(!mayRead) }
    if (remindNode) OwnNodeReminder(vm) { remindNode = false }
    LaunchedEffect(mayRead, js.export) { if (mayRead && js.export != null) jvm.refresh() }
    // While on screen, refresh like the other wallets.
    LaunchedEffect(mayRead, state.refreshOpenSeconds) {
        val every = state.refreshOpenSeconds
        if (!mayRead || every <= 0) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(every * 1000L)
            if (AppVisible.value) jvm.refresh()
        }
    }
    if (reimport) JamImportDialog(jvm, accent, onImported = { reimport = false }, onClose = { reimport = false })
    if (askForget) androidx.compose.material3.AlertDialog(
        onDismissRequest = { askForget = false },
        title = { Text("Forget the JoinMarket wallet?") },
        text = { Text("Only this phone stops watching it. Nothing changes in Jam, and the coins stay where they are. " +
            "You can import it again from Jam at any time.", style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = { askForget = false; jvm.forget(); vm.leaveJam() }) { Text("FORGET", color = Bad) } },
        dismissButton = { TextButton(onClick = { askForget = false }) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { WalletModeSwitch(state, vm, accent) }
            TextButton(onClick = { jvm.refresh() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                Text("↻", style = MaterialTheme.typography.titleMedium, color = TextSoft)
            }
            TextButton(onClick = { showSettings = !showSettings }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                Text(if (showSettings) "✕" else "⚙", style = MaterialTheme.typography.titleMedium, color = TextSoft)
            }
            QuitButton(accent)
            Spacer(Modifier.width(4.dp))
            AppLogo(34)
        }

        // Read-only, and obviously so.
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.14f)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("👁", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("JOINMARKET · READ-ONLY", style = MaterialTheme.typography.labelSmall, color = accent)
                Text("This wallet cannot send or sign: it has no keys. To spend, fidelity bonds included, use Jam.",
                    style = MaterialTheme.typography.bodySmall, color = TextSoft)
            }
        }

        ScanStatus(ChainState(phase = js.phase, rows = js.rows, height = js.height, fingerprint = js.fingerprint,
            fingerprintChanged = js.fingerprintChanged, stale = js.stale,
            refreshing = if (js.refreshing) "the jars and the bonds" else null), accent, onRetry = { jvm.scan() })

        Panel(accent = accent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("JoinMarket wallet · total", accent)
                Spacer(Modifier.weight(1f))
                StreetEye(TextSoft)
            }
            Spacer(Modifier.height(6.dp))
            Text(street(groupSats(js.total) + " sats"), style = MaterialTheme.typography.headlineMedium, color = TextMain)
            if (js.unconfirmed != 0L) Text(street("${groupSats(js.unconfirmed)} sats in the mempool (0 conf)"),
                style = MaterialTheme.typography.bodySmall, color = Warn)
            Text(street("in the jars: ${groupSats(js.total - js.bondTotal)} sats · in fidelity bonds: ${groupSats(js.bondTotal)} sats"),
                style = MaterialTheme.typography.bodySmall, color = TextSoft)
        }

        // The five jars.
        Panel(accent = accent) {
            SectionLabel("Jars", accent)
            Spacer(Modifier.height(6.dp))
            JoinMarket.JARS.forEachIndexed { jar, name ->
                val rows = js.jarRows(jar)
                Row(Modifier.fillMaxWidth().clickable { openJar = if (openJar == jar) null else jar }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (openJar == jar) "▾" else "▸", color = TextFaint, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        // JoinMarket's own names for the jars: never translated (Jam shows them as they are).
                        androidx.compose.material3.Text(name, style = MaterialTheme.typography.bodyLarge, color = TextMain)
                        Text("jar #$jar · m/84'/0'/$jar'", style = MaterialTheme.typography.bodySmall, color = TextFaint)
                    }
                    Text(street(groupSats(js.jarTotal(jar)) + " sats"), style = MaterialTheme.typography.bodyMedium,
                        color = if (js.jarTotal(jar) > 0) TextMain else TextFaint)
                }
                if (openJar == jar) Column(Modifier.padding(start = 20.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val moves = js.txs.mapNotNull { t -> js.jarNets[t.txid]?.get(jar)?.let { t to it } }
                    Text("${rows.size} addresses used", style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    if (moves.isEmpty()) Text("No movements yet.", style = MaterialTheme.typography.bodySmall, color = TextFaint)
                    moves.take(10).forEach { (t, a) ->
                        Row {
                            Text(street((if (a >= 0) "+" else "−") + groupSats(kotlin.math.abs(a)) + " sats"),
                                style = MaterialTheme.typography.bodySmall, color = if (a >= 0) Good else TextMain, modifier = Modifier.weight(1f))
                            Text(if (t.pending) "in mempool · 0 conf" else "${t.confirmations} conf",
                                style = MaterialTheme.typography.bodySmall, color = if (t.pending) Warn else TextFaint)
                        }
                    }
                    rows.filter { it.total > 0 }.take(8).forEach { r ->
                        Text("${shortAddress(r.address)}  ·  ${r.path}  ·  " + street(groupSats(r.total)),
                            style = MaterialTheme.typography.bodySmall, color = TextFaint)
                    }
                }
            }
        }

        BondsCard(js, accent)

        if (js.txs.isNotEmpty()) MovementsCard(js.txs, accent, vm.explorerFor(Chain.BLAKE2B), vm, isHot = false, balance = js.total)

        Reveal(showSettings) {
            Panel(accent = accent) {
                SectionLabel("JoinMarket wallet", accent)
                Spacer(Modifier.height(6.dp))
                Explain("Watched from Jam's export: the account keys of the five jars and your fidelity bonds, no private key. " +
                    "Created a new bond? In Jam open Settings › View in Kilowallet (read-only) again and import it here: " +
                    "it replaces this import with the full current set.")
                TextButton(onClick = { reimport = true }) { Text("IMPORT AGAIN FROM JAM", color = accent) }
                Explain("Notifications: movements like the watch-only wallet, plus a warning 7 days before a bond " +
                    "unlocks, when it unlocks and when it no longer counts. They follow the app's notification settings.")
                TextButton(onClick = { askForget = true }) { Text("FORGET THE JOINMARKET WALLET", color = Bad) }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

/** Every bond of the export: amount, unlock date and what to do about it. */
@Composable
private fun BondsCard(js: JamUi, accent: Color) {
    Panel(accent = accent) {
        SectionLabel("Fidelity bonds", accent)
        Spacer(Modifier.height(6.dp))
        if (js.export?.bonds.isNullOrEmpty()) {
            Explain("No fidelity bonds in this export. Bonds are created in Jam (Earn); import the export again afterwards.")
            return@Panel
        }
        val now = System.currentTimeMillis() / 1000
        val states = js.bonds.associateBy { it.bond.timenumber }
        JoinMarket.bondScripts(js.export!!).forEachIndexed { i, s ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            val st = states[s.bond.timenumber]
            val status = st?.status(now)
            val date = JoinMarket.date(s.bond.locktime)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(st?.value?.let { street(groupSats(it) + " sats") } ?: "—", style = MaterialTheme.typography.bodyLarge,
                    color = TextMain, modifier = Modifier.weight(1f))
                Text(date, style = MaterialTheme.typography.bodySmall, color = TextSoft)
            }
            val (text, color) = when (status) {
                null -> "Not checked yet" to TextFaint
                JoinMarket.Status.UNFUNDED -> "Not funded yet: no coins at this bond's address." to TextFaint
                JoinMarket.Status.PENDING -> "Waiting for its first confirmation; locked until $date" to Warn
                JoinMarket.Status.LOCKED -> {
                    val days = ((s.bond.locktime - now + 86_399) / 86_400)
                    "🔒 Locked until $date (in $days days)" to TextMain
                }
                JoinMarket.Status.UNLOCKED -> "🔓 Unlocked: spend or renew it from Jam." to Warn
                JoinMarket.Status.VALUE_ZERO -> "Its value is now zero: renew it from Jam to keep being picked as a maker." to Bad
                JoinMarket.Status.SPENT -> "Spent: these coins left the bond." to TextFaint
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)
            st?.zeroAt?.let { z ->
                if (status == JoinMarket.Status.LOCKED || status == JoinMarket.Status.UNLOCKED)
                    Text("Counts for makers until ${JoinMarket.date(z)}", style = MaterialTheme.typography.bodySmall, color = TextFaint)
            }
            st?.currentValue(now)?.let { v ->
                if (status == JoinMarket.Status.LOCKED || status == JoinMarket.Status.UNLOCKED)
                    Text("Bond value now: ${groupSats(v)}", style = MaterialTheme.typography.bodySmall, color = TextFaint)
            }
            if ((st?.extraValue ?: 0) > 0) Text(street("Plus ${groupSats(st!!.extraValue)} sats sent again to the same address: locked too, but they add nothing to the bond."),
                style = MaterialTheme.typography.bodySmall, color = Warn)
            Text("${shortAddress(s.address)}  ·  ${s.path}", style = MaterialTheme.typography.bodySmall, color = TextFaint)
        }
    }
}

/** Import (or import again) Jam's export: scan its QR or paste the text. */
@Composable
fun JamImportDialog(jvm: JamViewModel, accent: Color, onImported: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var scan by remember { mutableStateOf(false) }
    val askNotif = rememberNotificationPermission()
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) scan = true }
    if (scan) QrScannerDialog(onResult = { raw -> text = raw.trim(); error = null; scan = false }, onDismiss = { scan = false })
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Watch a JoinMarket wallet") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A THIRD wallet, apart from the hot and the watch-only ones: your Jam (JoinMarket) wallet, read-only. " +
                    "You see its five jars, their movements and your fidelity bonds, and you are told when a bond unlocks. " +
                    "It cannot send: spending, bonds included, stays in Jam.", style = MaterialTheme.typography.bodySmall)
                Text("In Jam: Settings › View in Kilowallet (read-only). Scan the QR code or paste the text. " +
                    "Importing again replaces the previous JoinMarket wallet.", style = MaterialTheme.typography.bodySmall, color = TextFaint)
                OutlinedTextField(value = text, onValueChange = { text = it; error = null },
                    label = { Text("kilowallet-jm:v1:…", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall, minLines = 3, maxLines = 6, isError = error != null)
                error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scan = true
                    else camera.launch(Manifest.permission.CAMERA)
                }) { Text("📷  Scan a QR", color = accent, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                error = jvm.import(text)
                if (error == null) { askNotif(); onImported() }
            }) { Text("WATCH IT", color = accent) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )
}
