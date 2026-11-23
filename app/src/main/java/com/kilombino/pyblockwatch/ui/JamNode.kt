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
 * The JoinMarket wallet's node, to fix from its own tab: shown with the current host and port
 * filled in, from the error ("CHANGE THE NODE" next to retry) and from the tab's settings.
 * Saving looks again at once; removing the node goes back to the "one person, one node" choice.
 * It is the app's node setting, shared with the BTC wallet, and the dialog says so.
 */
@Composable
fun JamNodeDialog(vm: WalletViewModel, jvm: JamViewModel, accent: Color, onRemoved: () -> Unit, onClose: () -> Unit) {
    val now = jvm.endpoint()
    var host by remember { mutableStateOf(if (now.isCustom) now.host else "") }
    var port by remember { mutableStateOf(if (now.isCustom) now.port.toString() else "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("JoinMarket node") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The JoinMarket wallet reads BTC through an Electrum server (Fulcrum, Frigate…). It is the same " +
                    "node setting as the BTC wallet's: changing it here changes it there too.",
                    style = MaterialTheme.typography.bodySmall)
                if (jvm.btcUsesRpc()) Text("Your node by RPC is for the BTC wallet only: the JoinMarket wallet needs an Electrum server.",
                    style = MaterialTheme.typography.bodySmall, color = Warn)
                Text(if (now.isCustom) "Now: ${now.host}:${now.port}" else "Now: ${now.host}:${now.port} (the default server)",
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextMain)
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
        },
        confirmButton = { TextButton(onClick = {
            val p = port.toIntOrNull()
            when {
                host.isBlank() || host.contains(' ') -> error = "Type the server's host name or IP."
                p == null || p !in 1..65535 -> error = "Port 1 to 65535 (Fulcrum: 50002 with TLS, 50001 plain on your own network)."
                else -> { vm.setCustomNode(Chain.BLAKE2B, host, p); jvm.scan(); onClose() }
            }
        }) { Text("SAVE AND RETRY", color = Good) } },
        dismissButton = { TextButton(onClick = onClose) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = accent, textContentColor = TextSoft,
    )
}
