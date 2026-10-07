package com.kilombino.pyblockwatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kilombino.pyblockwatch.coinjoin.Protocol

/**
 * What coinjoin is, before using it: shown once to a new spending wallet, and whenever the
 * COINJOIN tab is opened without having accepted it. Accepting turns on the tab and the
 * notifications of open pools (both can be switched off in settings).
 */
@Composable
fun CoinjoinIntro(accent: Color, onAccept: () -> Unit, onDecline: () -> Unit, declineLabel: String = "NO THANKS") {
    Panel(accent = accent) {
        SectionLabel("Coinjoin · mix your coins", accent)
        Spacer(Modifier.height(8.dp))
        Text("Are you interested in coinjoins?", style = MaterialTheme.typography.titleMedium, color = TextMain)
        Spacer(Modifier.height(8.dp))
        Explain("A coinjoin puts your coin in one transaction with other people's. Everyone gets back an " +
            "output of exactly the same amount, so nobody watching the chain can tell whose is whose.")
        Spacer(Modifier.height(6.dp))
        Explain("• Someone opens a pool (from ${"%,d".format(Protocol.MIN_AMOUNT).replace(',', ' ')} sats to 1 BTC) and " +
            "others join with one coin.\n" +
            "• With enough people in, anyone asks to close and the rest accept.\n" +
            "• Your wallet checks the transaction and you sign with your fingerprint. Nobody holds your " +
            "coins: the relay only passes encrypted messages.\n" +
            "• Each person pays their own share of the fee.\n" +
            "• Signed so it is valid only on BTC, never on the spamchain.")
        Spacer(Modifier.height(6.dp))
        Explain("If you accept, the COINJOIN tab opens up and you will be notified of open pools — and " +
            "nothing else. You can turn the notifications off any time in settings.")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDecline, colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = TextSoft),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text(declineLabel) }
            Button(onClick = onAccept, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text("YES, NOTIFY ME") }
        }
    }
}
