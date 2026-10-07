package com.kilombino.pyblockwatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kilombino.pyblockwatch.ark.Ark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The emergency exit: taking Ark coins on-chain WITHOUT the Ark server, by broadcasting the
 * signed recovery transactions every coin carries. It is the last resort when the server has
 * disappeared or refuses to renew, so it sits folded away at the bottom, behind a warning.
 * Tested on XBT mainnet on 1–2 Oct 2026: 5,648 sats out, 5,518 claimed after ~145 blocks.
 */

@Composable
fun ArkEmergencyPanel(accent: androidx.compose.ui.graphics.Color, activity: androidx.fragment.app.FragmentActivity,
                      onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var exits by remember { mutableStateOf<List<Ark.ExitState>>(emptyList()) }
    var quote by remember { mutableStateOf<Ark.Estimate?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var dest by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }

    var lost by remember { mutableStateOf<Set<String>>(emptySet()) }
    suspend fun reload() { exits = withContext(Dispatchers.IO) { Ark.exits() }; lost = withContext(Dispatchers.IO) { Ark.lostExits() } }
    LaunchedEffect(open) { if (open) reload() }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Start the emergency exit?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This takes ALL your Ark coins on-chain without the Ark server, using the recovery " +
                        "transactions each coin carries. Only do it if the server has disappeared or will " +
                        "not let you renew. While the server works, a normal withdrawal (SEND to an XBT " +
                        "address) is far cheaper and faster.", style = MaterialTheme.typography.bodySmall)
                    Text("It cannot be undone once the last transaction is broadcast. It takes about 144 " +
                        "blocks (~1 day) plus confirmations, and the wallet must stay running to push it " +
                        "forward. Then you claim the XBT to an address of yours.",
                        style = MaterialTheme.typography.bodySmall, color = Warn)
                    quote?.let { q ->
                        if (q.problem != null) Text(q.problem, style = MaterialTheme.typography.bodySmall, color = Bad)
                        else Text("Estimated cost: ${groupSats(q.fee)} sats (${q.note}). You get about " +
                            "${groupSats(q.amount)} of ${groupSats(q.total)} sats.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Type EXIT to confirm.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = typed, onValueChange = { typed = it.uppercase() }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = typed == "EXIT" && busy == null, onClick = {
                    confirm = false; typed = ""
                    Biometric.confirm(activity, "Emergency exit", "Confirm it is you", onSuccess = {
                        scope.launch {
                            busy = "Starting the emergency exit…"
                            val r = withContext(Dispatchers.IO) { runCatching { Ark.startExitAll() } }
                            busy = null
                            onMessage(r.fold({ "Emergency exit started: $it" }, { "Error: ${it.message}" }))
                            reload()
                        }
                    }, onError = { onMessage("Error: $it") })
                }) { Text("START EXIT", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { confirm = false; typed = "" }) { Text("CANCEL", color = TextSoft) } },
            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
        )
    }

    Panel(accent = Bad) {
        TextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
            Text((if (open) "▾ " else "▸ ") + "EMERGENCY EXIT (without the Ark server)", color = Bad,
                 style = MaterialTheme.typography.labelLarge)
        }
        if (!open) return@Panel
        Text("Last resort, if ark.paperclippool.xyz disappears or stops renewing your coins: your coins " +
            "carry signed transactions that let you take them on-chain alone. It is slow (~1 day) and " +
            "costs on-chain fees. While the server works, withdraw normally instead.",
            style = MaterialTheme.typography.bodySmall, color = TextSoft)
        Spacer(Modifier.height(8.dp))
        if (exits.isEmpty()) {
            Button(
                enabled = busy == null,
                onClick = {
                    scope.launch {
                        busy = "Calculating the exit cost…"
                        quote = withContext(Dispatchers.IO) { Ark.estimateExit() }
                        busy = null; confirm = true
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Bad, contentColor = Ink),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
            ) { Text("START EMERGENCY EXIT…", style = MaterialTheme.typography.titleMedium) }
        } else {
            exits.forEach { e ->
                val left = if (e.claimableHeight != null && e.tip != null) e.claimableHeight - e.tip else null
                val state = if (e.vtxo in lost) "lost: the coin expired and the server swept it" else when (e.type) {
                    "claimable" -> "ready to claim"
                    "awaiting-delta" -> "waiting the safety delay" + (left?.let { " · claimable in $it blocks (~${it * 10 / 60} h)" } ?: "")
                    "processing" -> "broadcasting its transactions"
                    "claimed", "claim-in-progress" -> "claimed"
                    else -> e.type
                }
                Text("${e.vtxo.take(12)}… · $state", style = MaterialTheme.typography.bodySmall, color = TextMain)
            }
            if (exits.any { it.type == "claimable" }) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = dest, onValueChange = { dest = it.trim() },
                    label = { Text("your XBT address to receive it") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Button(
                    enabled = dest.isNotBlank() && busy == null,
                    onClick = {
                        Biometric.confirm(activity, "Claim the exit", "Confirm it is you", onSuccess = {
                            scope.launch {
                                busy = "Claiming…"
                                val r = withContext(Dispatchers.IO) { runCatching { Ark.claimExits(dest) } }
                                busy = null
                                onMessage(r.fold({ it }, { "Error: ${it.message}" }))
                                reload()
                            }
                        }, onError = { onMessage("Error: $it") })
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                ) { Text("CLAIM TO THIS ADDRESS", style = MaterialTheme.typography.titleMedium) }
            }
            TextButton(onClick = { scope.launch { reload() } }) { Text("refresh", color = TextSoft) }
        }
        busy?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Warn) }
    }
}
