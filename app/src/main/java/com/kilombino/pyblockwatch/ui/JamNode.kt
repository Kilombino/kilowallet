package com.kilombino.pyblockwatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.ui.i18n.Text

/**
 * The JoinMarket wallet's node, to fix from its own tab: shown with the current one filled in,
 * from the error ("CHANGE THE NODE" next to retry) and from the tab's settings. It is the app's
 * node setting, shared with the BTC wallet, and the dialog says so: an Electrum server (host and
 * port; saving looks again at once, removing it goes back to the "one person, one node" choice)
 * or the user's own node by RPC, which then answers for the jars and the bonds too.
 */
@Composable
fun JamNodeDialog(vm: WalletViewModel, jvm: JamViewModel, accent: Color, onRemoved: () -> Unit, onClose: () -> Unit) {
    // Turning RPC on or off, or changing its connections, below: read again, and look again on closing.
    var changes by remember { mutableStateOf(0) }
    val now = remember(changes) { jvm.endpoint() }
    val byRpc = now.rpc != null
    var host by remember(byRpc) { mutableStateOf(if (now.isCustom && !byRpc) now.host else "") }
    var port by remember(byRpc) { mutableStateOf(if (now.isCustom && !byRpc) now.port.toString() else "") }
    var error by remember { mutableStateOf<String?>(null) }
    // After a change below: look again, or (RPC turned off, no node left) back to the "one person, one node" choice.
    val close = { if (changes > 0) onRemoved() else onClose() }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("JoinMarket node") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (byRpc) Text("The JoinMarket wallet reads BTC from your own node by RPC, like the BTC wallet: the node keeps a " +
                    "watch-only wallet with the jars' public keys and the bonds' addresses (no private key). The first time it " +
                    "looks for their coins, which can take a few minutes. It is the app's node setting: changing it here changes " +
                    "it for the BTC wallet too.", style = MaterialTheme.typography.bodySmall)
                else Text("The JoinMarket wallet reads BTC through an Electrum server (Fulcrum, Frigate…). It is the same " +
                    "node setting as the BTC wallet's: changing it here changes it there too.",
                    style = MaterialTheme.typography.bodySmall)
                Text(when {
                    byRpc -> "Now: $now"
                    now.isCustom -> "Now: ${now.host}:${now.port}"
                    else -> "Now: ${now.host}:${now.port} (the default server)"
                }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextMain)
                if (!byRpc) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = host, onValueChange = { host = it.trim(); error = null },
                            label = { Text("host", style = MaterialTheme.typography.bodySmall) },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true, modifier = Modifier.weight(2f))
                        OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5); error = null },
                            label = { Text("port", style = MaterialTheme.typography.bodySmall) },
                            textStyle = MaterialTheme.typography.bodySmall, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Warn) }
                    if (now.isCustom) TextButton(onClick = {
                        vm.setCustomNode(Chain.BLAKE2B, null, 0)
                        onRemoved()
                    }) { Text("REMOVE MY NODE", color = Bad) }
                }
                // The node by RPC, as in the BTC wallet's settings (the same connections).
                RpcNodePanel(vm, accent, onSaved = { changes++ })
            }
        },
        confirmButton = { TextButton(onClick = {
            if (byRpc) { jvm.scan(); onClose(); return@TextButton }
            val p = port.toIntOrNull()
            when {
                host.isBlank() || host.contains(' ') -> error = "Type the server's host name or IP."
                p == null || p !in 1..65535 -> error = "Port 1 to 65535 (Fulcrum: 50002 with TLS, 50001 plain on your own network)."
                else -> { vm.setCustomNode(Chain.BLAKE2B, host, p); jvm.scan(); onClose() }
            }
        }) { Text(if (byRpc) "RETRY" else "SAVE AND RETRY", color = Good) } },
        dismissButton = { TextButton(onClick = close) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = accent, textContentColor = TextSoft,
    )
}
