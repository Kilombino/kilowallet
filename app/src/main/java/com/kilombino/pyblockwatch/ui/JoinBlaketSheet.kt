package com.kilombino.pyblockwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kilombino.pyblockwatch.coinjoin.JoinBlaketPay
import com.kilombino.pyblockwatch.coinjoin.JoinBlaketService
import com.kilombino.pyblockwatch.joinblaket.TakerPlan
import com.kilombino.pyblockwatch.joinblaket.TakerResult
import com.kilombino.pyblockwatch.ui.i18n.Text

/**
 * PAY THROUGH JOINBLAKET's sheet, over whatever is on screen while a round goes: where it is, the
 * plan to confirm (each maker's fee, the mining fee, the total; nothing about the coins has left
 * the phone yet), and how it ended. HIDE leaves it running under its notification.
 */
@Composable
fun JoinBlaketSheet(accent: Color) {
    val v by JoinBlaketPay.state.collectAsState()
    val visible by JoinBlaketPay.sheetVisible.collectAsState()
    val view = v ?: return
    if (!visible) return
    val ctx = LocalContext.current
    Dialog(onDismissRequest = { if (view.finished) JoinBlaketPay.dismiss() else JoinBlaketPay.sheetVisible.value = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().background(PanelBg, RoundedCornerShape(16.dp)).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val small = MaterialTheme.typography.bodySmall
            Text("🔀 Pay through JoinBlaket", color = accent, style = MaterialTheme.typography.titleMedium)
            Text("Paying ${groupSats(view.amount)} sats to ${shortAddress(view.recipient)}", color = TextMain, style = MaterialTheme.typography.bodyMedium)
            val plan = view.plan
            val result = view.result
            when {
                plan != null -> PlanDetails(plan, accent)
                result != null -> ResultDetails(result)
                else -> {
                    Text(JoinBlaketService.line(view), color = TextSoft, style = small)
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent)
                }
            }
            Spacer(Modifier.height(4.dp))
            when {
                plan != null -> {
                    Button(onClick = { JoinBlaketPay.answer(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                    ) { Text("CONFIRM & SIGN", style = MaterialTheme.typography.titleMedium) }
                    TextButton(onClick = { JoinBlaketPay.cancel() }, modifier = Modifier.fillMaxWidth()) {
                        Text("CANCEL", color = TextFaint, style = small)
                    }
                }
                view.finished -> Button(onClick = { JoinBlaketPay.dismiss(); JoinBlaketService.refresh(ctx) },
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                ) { Text("CLOSE", style = MaterialTheme.typography.titleMedium) }
                else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (!view.pastReturn) TextButton(onClick = { JoinBlaketPay.cancel() }) { Text("CANCEL", color = Bad, style = small) }
                    else Spacer(Modifier)
                    TextButton(onClick = { JoinBlaketPay.sheetVisible.value = false }) { Text("HIDE", color = TextFaint, style = small) }
                }
            }
        }
    }
}

@Composable
private fun PlanDetails(p: TakerPlan, accent: Color) {
    val small = MaterialTheme.typography.bodySmall
    Text("Confirm the CoinJoin", color = TextMain, style = MaterialTheme.typography.bodyMedium)
    Text(if (p.makers.size == 1) "1 maker with a fidelity bond" else "${p.makers.size} makers with a fidelity bond", color = TextSoft, style = small)
    p.makers.forEachIndexed { i, m -> Text("maker ${i + 1}: fee ${groupSats(m.fee)} sats", color = TextFaint, style = small) }
    Text("Makers' fees: ${groupSats(p.makerFees)} sats", color = TextSoft, style = small)
    Text("Mining fee: about ${groupSats(p.miningFeeEstimate)} sats, at most ${groupSats(p.miningFeeMax)} sats (${"%.2f".format(p.feeRate)} sat/vB)",
        color = TextSoft, style = small)
    Text("Total cost: about ${groupSats(p.totalEstimate)} sats, at most ${groupSats(p.totalMax)} sats", color = accent, style = MaterialTheme.typography.bodyMedium)
    Text("From ${p.inputs} coin(s) of yours (${groupSats(p.inputTotal)} sats); the change comes back to this wallet.", color = TextFaint, style = small)
    if (p.makers.size == 1) Text("With a single maker, that maker can link your coin to your payment.", color = Warn, style = small)
    Text("Nothing about your coins has left the phone yet. If a maker drops out, another may take its place within these fees.",
        color = TextFaint, style = small)
}

@Composable
private fun ResultDetails(r: TakerResult) {
    val small = MaterialTheme.typography.bodySmall
    when (r) {
        is TakerResult.Sent -> {
            Text(if (r.seen) "Done: the CoinJoin was broadcast." else "Sent to the makers; your server doesn't show it yet.",
                color = Good, style = MaterialTheme.typography.bodyMedium)
            Text("${r.makers} maker(s) · makers' fees ${groupSats(r.makerFees)} sats · mining fee ${groupSats(r.txFee)} sats", color = TextSoft, style = small)
            if (r.change > 0) Text("Change back to you: ${groupSats(r.change)} sats", color = TextSoft, style = small)
            androidx.compose.foundation.text.selection.SelectionContainer {
                androidx.compose.material3.Text(r.txid, color = TextFaint, style = small,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
        is TakerResult.Failed -> {
            Text(r.reason, color = Bad, style = MaterialTheme.typography.bodyMedium)
            r.detail?.let { Text(it, color = TextFaint, style = small) }
        }
        is TakerResult.Cancelled -> Text(r.reason, color = Warn, style = MaterialTheme.typography.bodyMedium)
    }
}
