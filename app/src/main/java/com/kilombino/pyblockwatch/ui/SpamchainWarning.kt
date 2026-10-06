package com.kilombino.pyblockwatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kilombino.pyblockwatch.chain.Chain

/**
 * Host and port of the user's own Electrum server, saved as the chain's node (the same
 * setting as in settings). Returns true once saved.
 */
@Composable
private fun OwnNodeFields(vm: WalletViewModel, chain: Chain, onSaved: () -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Text("Your own ${if (chain == Chain.BLAKE2B) "BTC" else "spamchain"} Electrum server:",
        style = MaterialTheme.typography.bodySmall, color = TextSoft)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = host, onValueChange = { host = it.trim(); error = null },
            label = { Text("host", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall, singleLine = true, modifier = Modifier.weight(2f))
        OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text("port", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall, singleLine = true, modifier = Modifier.weight(1f))
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Warn) }
    TextButton(onClick = {
        val p = port.toIntOrNull()
        when {
            host.isBlank() || host.contains(' ') -> error = "Type the server's host name or IP."
            p == null || p !in 1..65535 -> error = "Port 1 to 65535 (Fulcrum: 50002 with TLS, 50001 plain on your own network)."
            else -> { vm.setCustomNode(chain, host, p); onSaved() }
        }
    }) { Text("SAVE MY NODE", color = Good) }
}

/**
 * Shown when the app opens and on a manual refresh, while BTC is still read from Kilombino's
 * server: one person, one node. Saving a node here is the same as in settings, and the
 * reminder stops for good.
 */
@Composable
fun OwnNodeReminder(vm: WalletViewModel, onClose: () -> Unit) {
    AlertDialog(
        // Nothing connects until a choice is made: tapping outside does not count as one.
        onDismissRequest = {},
        title = { Text("One person, one node") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You are reading BTC through ${Chain.BLAKE2B.defaultHost}. Whoever runs a " +
                    "server sees the addresses you ask about and your IP address. Run your own node and " +
                    "point the wallet at it; use ${Chain.BLAKE2B.defaultHost} only in an emergency.",
                    style = MaterialTheme.typography.bodySmall)
                OwnNodeFields(vm, Chain.BLAKE2B, onClose)
                Text("Until you choose, the wallet does not connect anywhere.",
                    style = MaterialTheme.typography.bodySmall, color = TextFaint)
                Text("Once your node is saved this reminder stops. You can change it any time in settings. " +
                    "Notifications keep working either way.", style = MaterialTheme.typography.bodySmall, color = TextFaint)
            }
        },
        confirmButton = { TextButton(onClick = { vm.consentBtc(); onClose() }) {
            Text("USE ${Chain.BLAKE2B.defaultHost.uppercase()} FOR NOW", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = Purple, textContentColor = TextSoft,
    )
}

/**
 * Shown every time the spamchain is opened or refreshed by hand, before anything connects,
 * unless the user has saved their own spamchain node: otherwise that chain is read from
 * public servers run by companies and institutions, which see the addresses looked up.
 */
@Composable
fun SpamchainWarning(vm: WalletViewModel, onContinue: () -> Unit, onBack: () -> Unit) {
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("☣️  Third-party servers") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Spamcoin is not read from your own node: the wallet connects to public " +
                    "Electrum servers run by companies and institutions. They see every address the " +
                    "wallet asks about and your IP address, and can link them to each other: your " +
                    "privacy may be at risk.", style = MaterialTheme.typography.bodySmall)
                Text("Servers, in this order:", style = MaterialTheme.typography.bodySmall, color = TextSoft)
                Chain.publicServers.forEach {
                    Text("•  ${it.host}", style = MaterialTheme.typography.bodySmall,
                         fontFamily = FontFamily.Monospace, color = TextMain)
                }
                OwnNodeFields(vm, Chain.SHA256, onContinue)
                Text("With your own node saved this warning stops. Without it, nothing connects until you continue.",
                    style = MaterialTheme.typography.bodySmall, color = TextFaint)
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text("CONTINUE WITH PUBLIC SERVERS", color = Bad) } },
        dismissButton = { TextButton(onClick = onBack) { Text("BACK TO BTC", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = Orange, textContentColor = TextSoft,
    )
}
