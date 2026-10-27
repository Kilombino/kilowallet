package com.kilombino.pyblockwatch.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.kilombino.pyblockwatch.crypto.Ur
import com.kilombino.pyblockwatch.data.JoinMarket
import com.kilombino.pyblockwatch.ui.i18n.Text

/**
 * Spending an unlocked fidelity bond with SeedSigner: the PSBT goes out as an animated QR, the
 * signed one comes back the same way (or pasted), Kilowallet checks the signature and shows the
 * transaction before sending it. Only a SeedSigner that signs the BLAKE2b unified sighash (0x21)
 * can do it; any other signature is refused here, never broadcast.
 */
@Composable
fun BondSpendDialog(jvm: JamViewModel, accent: Color) {
    val js by jvm.state.collectAsState()
    val spend = js.bondSpend ?: return
    Dialog(onDismissRequest = { if (spend !is BondSpend.Broadcasting) jvm.cancelBondSpend() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(PanelBg)) {
            Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.height(16.dp))
                SectionLabel("Spend fidelity bond · SeedSigner", accent)
                val st = spend.state
                Text(street(groupSats(st.value ?: 0) + " sats"), style = MaterialTheme.typography.headlineSmall, color = TextMain)
                Text("Unlocked since ${JoinMarket.date(st.locktime)} · ${st.script.path}", style = MaterialTheme.typography.bodySmall, color = TextFaint)
                when (spend) {
                    is BondSpend.Draft -> DraftStep(jvm, spend, accent)
                    is BondSpend.AwaitingSignature -> SignStep(jvm, spend, accent)
                    is BondSpend.Review -> ReviewStep(jvm, spend, accent)
                    is BondSpend.Broadcasting -> Text("Sending…", color = TextSoft)
                    is BondSpend.Sent -> SentStep(jvm, spend, accent)
                }
            }
        }
    }
}

@Composable
private fun DraftStep(jvm: JamViewModel, d: BondSpend.Draft, accent: Color) {
    var dest by remember(d.state) { mutableStateOf(d.destination) }
    var rate by remember(d.state, d.feeRate) { mutableStateOf(if (d.feeRate % 1.0 == 0.0) d.feeRate.toLong().toString() else d.feeRate.toString()) }
    LaunchedEffect(d.destination) { if (dest.isEmpty()) dest = d.destination }
    Explain("The whole bond goes to one address, less the network fee. By default a new address of your Jam wallet's " +
        "first jar (Apricot), so the coins stay in Jam.")
    OutlinedTextField(value = dest, onValueChange = { dest = it; jvm.editBondDraft(it, rate.toDoubleOrNull() ?: 0.0) },
        label = { Text("Destination address", style = MaterialTheme.typography.bodySmall) },
        textStyle = MaterialTheme.typography.bodySmall, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = rate, onValueChange = { rate = it; jvm.editBondDraft(dest, it.toDoubleOrNull() ?: 0.0) },
        label = { Text("Fee rate (sat/vB)", style = MaterialTheme.typography.bodySmall) },
        textStyle = MaterialTheme.typography.bodySmall, singleLine = true, modifier = Modifier.fillMaxWidth())
    d.error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
    Row {
        TextButton(onClick = { jvm.cancelBondSpend() }) { Text("CANCEL", color = TextSoft) }
        Spacer(Modifier.weight(1f))
        TextButton(enabled = !d.working, onClick = { jvm.editBondDraft(dest, rate.toDoubleOrNull() ?: 0.0); jvm.makeBondPsbt() }) {
            Text(if (d.working) "MAKING THE PSBT…" else "MAKE THE PSBT", color = accent)
        }
    }
}

@Composable
private fun SignStep(jvm: JamViewModel, a: BondSpend.AwaitingSignature, accent: Color) {
    val ctx = LocalContext.current
    var scan by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    val decoder = remember(a.psbt.contentHashCode(), scan) { Ur.Decoder() }
    var frames by remember(scan) { mutableStateOf(0) }
    var frameError by remember(scan) { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) scan = true }

    // The animated QR: a new fountain part about every quarter second, for as long as it is shown.
    val encoder = remember(a.psbt.contentHashCode()) { Ur.psbtEncoder(a.psbt, 100) }
    var part by remember(a.psbt.contentHashCode()) { mutableStateOf(encoder.nextPart()) }
    LaunchedEffect(encoder, scan) {
        if (encoder.isSinglePart || scan) return@LaunchedEffect
        while (true) { kotlinx.coroutines.delay(250); part = encoder.nextPart() }
    }

    if (scan) QrScannerDialog(
        onResult = { scan = false; runCatching { decoder.psbt() }.onSuccess { jvm.bondSigned(it) } },
        onDismiss = { scan = false },
        onFrame = { text ->
            frames++
            runCatching { decoder.receive(text) }.onFailure { frameError = it.message }.getOrDefault(false)
        },
        progress = if (frames == 0) "Point the camera at SeedSigner's signed QR" else
            "Reading the signed PSBT: ${(decoder.progress * 100).toInt()}%",
    )

    Text("1. On SeedSigner: Scan, then hold it up to this code. Check the amount, the unlock date and the " +
        "destination on its screen, and approve.", style = MaterialTheme.typography.bodySmall, color = TextSoft)
    Box(Modifier.fillMaxWidth().background(Color.White).padding(10.dp), contentAlignment = Alignment.Center) {
        QrImage(part, 300)
    }
    Text("Destination: ${a.destination}", style = MaterialTheme.typography.bodySmall, color = TextMain)
    Text(street("You receive ${groupSats(a.outputs[0].value)} sats · fee ${groupSats(a.fee)} sats"),
        style = MaterialTheme.typography.bodySmall, color = TextSoft)
    Text("2. SeedSigner then shows the signed transaction as a QR: scan it here.",
        style = MaterialTheme.typography.bodySmall, color = TextSoft)
    Explain("SeedSigner must sign it with the BTC replay protection (0x21). A signature without it would also be " +
        "valid on the SHA-256 chain, so Kilowallet refuses it and sends nothing.")
    (a.error ?: frameError)?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
    Row {
        TextButton(onClick = {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scan = true
            else camera.launch(Manifest.permission.CAMERA)
        }) { Text("📷  SCAN THE SIGNED PSBT", color = accent) }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { paste = !paste }) { Text("PASTE IT", color = TextSoft) }
    }
    if (paste) {
        OutlinedTextField(value = pasted, onValueChange = { pasted = it },
            label = { Text("Signed PSBT (base64 or hex)", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = pasted.isNotBlank(), onClick = { jvm.bondSignedText(pasted) }) { Text("USE IT", color = accent) }
    }
    TextButton(onClick = { jvm.cancelBondSpend() }) { Text("CANCEL", color = TextSoft) }
}

@Composable
private fun ReviewStep(jvm: JamViewModel, r: BondSpend.Review, accent: Color) {
    Text("Signed and checked. Nothing has been sent yet.", style = MaterialTheme.typography.bodyMedium, color = Good)
    Panel(accent = accent) {
        Text("To: ${r.destination}", style = MaterialTheme.typography.bodySmall, color = TextMain)
        Text(street("Amount: ${groupSats(r.amount)} sats"), style = MaterialTheme.typography.bodySmall, color = TextMain)
        Text(street("Fee: ${groupSats(r.fee)} sats (${r.signed.vbytes} vB)"), style = MaterialTheme.typography.bodySmall, color = TextSoft)
        Text("Transaction ${r.signed.txid}", style = MaterialTheme.typography.bodySmall, color = TextFaint)
        Text("Signed with the BTC replay protection (0x21): it cannot be replayed on the SHA-256 chain.",
            style = MaterialTheme.typography.bodySmall, color = TextFaint)
    }
    r.error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
    Row {
        TextButton(onClick = { jvm.bondBackToQr() }) { Text("BACK", color = TextSoft) }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { jvm.broadcastBond() }) { Text("SEND IT", color = accent) }
    }
}

@Composable
private fun SentStep(jvm: JamViewModel, s: BondSpend.Sent, accent: Color) {
    val copier = rememberCopier()
    Text("Sent. The bond's coins are on their way.", style = MaterialTheme.typography.bodyMedium, color = Good)
    Text("Transaction ${s.txid}", style = MaterialTheme.typography.bodySmall, color = TextSoft)
    Row {
        TextButton(onClick = { copier.copy(s.txid) }) { Text(if (copier.copied) "COPIED ✓" else "COPY", color = accent) }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { jvm.cancelBondSpend() }) { Text("DONE", color = accent) }
    }
}
