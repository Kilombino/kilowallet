package com.kilombino.pyblockwatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kilombino.pyblockwatch.chain.Chain

/**
 * Shown every time the spamchain tab is opened, before anything connects: that chain is read
 * from public third-party servers, which see the addresses the wallet looks up.
 */
@Composable
fun SpamchainWarning(onContinue: () -> Unit, onBack: () -> Unit) {
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("☣️  Third-party servers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Spamcoin is not read from your own node: the wallet connects to public " +
                    "Electrum servers run by companies and institutions. They see every address the " +
                    "wallet asks about and your IP address, and can link them to each other: your " +
                    "privacy may be at risk.", style = MaterialTheme.typography.bodySmall)
                Text("Servers, in this order:", style = MaterialTheme.typography.bodySmall, color = TextSoft)
                Chain.publicServers.forEach {
                    Text("•  ${it.host}", style = MaterialTheme.typography.bodySmall,
                         fontFamily = FontFamily.Monospace, color = TextMain)
                }
                Text("Nothing connects until you continue.", style = MaterialTheme.typography.bodySmall, color = TextFaint)
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text("CONTINUE", color = Bad) } },
        dismissButton = { TextButton(onClick = onBack) { Text("BACK TO BTC", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = Orange, textContentColor = TextSoft,
    )
}
