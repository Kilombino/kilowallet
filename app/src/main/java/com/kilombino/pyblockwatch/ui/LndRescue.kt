package com.kilombino.pyblockwatch.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.kilombino.pyblockwatch.crypto.Bip39
import java.security.SecureRandom

/**
 * Settings → rescue XBT from a pre-fork LND wallet: its on-chain coins on the BLAKE2b chain,
 * plus the cooperative channel closes it made on the SHA-256 chain after the fork, replayed on
 * BLAKE2b. Everything is swept with the unified sighash, which the SHA-256 chain rejects, so
 * the LND node still running there is not touched.
 */
@Composable
fun LndRescuePanel(vm: WalletViewModel, accent: Color) {
    val phase by vm.rescue.collectAsState()
    var words by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var gap by remember { mutableStateOf("50") }
    var dest by remember { mutableStateOf(vm.defaultRescueAddress() ?: "") }
    var feeRate by remember { mutableStateOf("2") }
    var replay by remember { mutableStateOf(true) }

    Panel(accent = Warn) {
        SectionLabel("rescue XBT from a pre-fork LND seed", Warn)
        Spacer(Modifier.height(6.dp))
        when (val p = phase) {
            is RescuePhase.Busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(Warn); Spacer(Modifier.width(10.dp))
                Text(p.message, color = Warn, style = MaterialTheme.typography.bodySmall)
            }
            is RescuePhase.Done -> {
                Text("Rescue sent ✓", color = Good, style = MaterialTheme.typography.titleMedium)
                if (p.replayed.isNotEmpty()) Text("Closes replayed on BLAKE2b: ${p.replayed.size}",
                    color = TextSoft, style = MaterialTheme.typography.bodySmall)
                p.replayErrors.forEach { Text("Not replayed: $it", color = Warn, style = MaterialTheme.typography.bodySmall) }
                p.sweepTxid?.let {
                    Text("${groupSats(p.swept)} sats on their way:", color = TextSoft, style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text(it, color = TextSoft, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace) }
                }
                TextButton(onClick = { vm.resetRescue(); words = ""; passphrase = "" }) {
                    Text("done", color = accent, style = MaterialTheme.typography.bodySmall)
                }
            }
            is RescuePhase.Review -> {
                val r = p.result
                Text("${r.keysUsed} used addresses found (${r.keysScanned} checked).",
                     color = TextSoft, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text("On the BLAKE2b chain", color = TextMain, style = MaterialTheme.typography.bodyMedium)
                if (r.coins.isEmpty()) Text("no coins", color = TextFaint, style = MaterialTheme.typography.bodySmall)
                r.coins.forEach { c ->
                    RowLine2("${c.key.type.label} · ${shortAddress(c.key.address)}" + (if (c.height <= 0) " · 0 conf" else ""),
                        "${groupSats(c.value)} sats", accent)
                }
                if (r.replays.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Channel closes made on SHA-256 after the fork", color = TextMain, style = MaterialTheme.typography.bodyMedium)
                    r.replays.forEach { rp -> RowLine2("close ${rp.txid.take(10)}…", "${groupSats(rp.toUsSats)} sats", accent) }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { replay = !replay }) {
                        Text(if (replay) "☑" else "☐", color = accent, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Text("Replay them on BLAKE2b (closes those channels there; nothing changes on SHA-256, " +
                            "where they are already closed)", color = TextSoft, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (r.forceCloses > 0) Text(
                    "${r.forceCloses} force close(s) found on SHA-256: not replayed. Their delayed output needs channel " +
                        "data the seed does not hold.", color = TextFaint, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = dest, onValueChange = { dest = it.trim() },
                    label = { Text("send to (XBT address)", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = feeRate, onValueChange = { feeRate = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("fee, sat/vB", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall, singleLine = true, modifier = Modifier.fillMaxWidth())
                val coins = r.coins + (if (replay) r.replays.flatMap { it.outputsToUs } else emptyList())
                val total = coins.sumOf { it.value }
                val fee = if (coins.isEmpty()) null else vm.rescueFee(coins, dest, feeRate.toDoubleOrNull() ?: 2.0)
                Spacer(Modifier.height(6.dp))
                RowLine2("Total", "${groupSats(total)} sats", accent)
                RowLine2("Network fee", fee?.let { "${groupSats(it)} sats" } ?: "—", accent)
                RowLine2("You receive", fee?.let { "${groupSats(total - it)} sats" } ?: "—", Good)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.rescueConfirm(dest, feeRate.toDoubleOrNull() ?: 2.0, replay) },
                    enabled = coins.isNotEmpty() && fee != null && total - fee > 294,
                    colors = ButtonDefaults.buttonColors(containerColor = Warn, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                ) { Text("RESCUE TO THIS ADDRESS", style = MaterialTheme.typography.titleMedium) }
                TextButton(onClick = { vm.resetRescue() }) {
                    Text("back", color = TextFaint, style = MaterialTheme.typography.bodySmall)
                }
            }
            else -> {
                Explain("For an LND (Lightning) wallet created before the fork: type its 24 aezeed words. " +
                    "Its coins on the BLAKE2b chain are swept with the unified signature, which the SHA-256 " +
                    "chain rejects, so the same LND node there keeps running untouched: no channel is closed there. " +
                    "Cooperative closes that wallet made on SHA-256 after the fork can be replayed on BLAKE2b to " +
                    "free your share. Channels still open on both chains cannot be rescued without the peer.")
                Spacer(Modifier.height(8.dp))
                if (p is RescuePhase.Failed) Text(p.message, color = Bad, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = words, onValueChange = { words = it },
                    label = { Text("24 LND seed words", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = passphrase, onValueChange = { passphrase = it },
                    label = { Text("seed passphrase (empty = none)", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = gap, onValueChange = { gap = it.filter(Char::isDigit) },
                    label = { Text("gap: unused addresses before stopping", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        vm.rescueScan(words.trim().lowercase().split(Regex("\\s+")), passphrase, gap.toIntOrNull() ?: 50)
                    },
                    enabled = words.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Warn, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                ) { Text("FIND XBT", style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

@Composable
private fun RowLine2(label: String, value: String, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = TextFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, color = accent, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}
