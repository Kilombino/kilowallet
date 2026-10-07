package com.kilombino.pyblockwatch.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.kilombino.pyblockwatch.chain.Chain

/**
 * HOT WALLET | WATCH-ONLY, top left. A phone with a seed can show it as watch-only (nothing can
 * be signed or revealed); going back asks for the fingerprint. A phone with only an xpub just
 * says "watch-only".
 */
@Composable
fun WalletModeSwitch(state: UiState, vm: WalletViewModel, accent: Color, modifier: Modifier = Modifier) {
    val activity = LocalContext.current as FragmentActivity
    if (!state.hasSeed) {
        Text(state.label.ifBlank { "watch-only wallet" }, style = MaterialTheme.typography.bodySmall,
            color = TextFaint, modifier = modifier)
        return
    }
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(PanelSoft).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        @Composable fun seg(label: String, on: Boolean, onClick: () -> Unit) = Text(label,
            style = MaterialTheme.typography.labelSmall,
            color = if (on) Ink else TextFaint,
            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .background(if (on) accent else Color.Transparent)
                .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp))
        seg("HOT WALLET", state.isHot) {
            if (!state.isHot) Biometric.confirm(activity, "Back to the hot wallet",
                "Unlock to spend and sign again", onSuccess = { vm.viewAsHot() }, onError = {})
        }
        seg("WATCH-ONLY", !state.isHot) { if (state.isHot) vm.viewAsWatchOnly() }
    }
}

/** A function that asks for the notification permission (Android 13+) when it is missing. */
@Composable
fun rememberNotificationPermission(): () -> Unit {
    val ctx = LocalContext.current
    val activity = ctx as FragmentActivity
    val prefs = ctx.getSharedPreferences("pyblockwatch", android.content.Context.MODE_PRIVATE)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // After two refusals Android no longer shows the question: the only way left is the
            // app's notification settings, so the user is taken there (they asked for it).
            val asked = prefs.getBoolean("notif_asked", false)
            if (asked && !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }
            } else {
                prefs.edit().putBoolean("notif_asked", true).apply()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

/**
 * The coins (UTXOs) of the selected chain with their confirmations, biggest first, as the
 * first thing under the balance in simple mode. Amounts follow street mode.
 */
@Composable
fun CoinsCard(state: UiState, vm: WalletViewModel, chain: Chain, accent: Color) {
    val cs = state.chains[chain] ?: return
    // Reload whenever the chain's movements change (a payment in or out, a confirmation).
    LaunchedEffect(chain, cs.transactions.map { it.txid to it.confirmations }) {
        if (cs.rows.isNotEmpty()) vm.loadUtxos()
    }
    val coins = state.utxos?.takeIf { state.utxosChain == chain } ?: return
    if (coins.isEmpty()) return
    Panel(accent = accent) {
        SectionLabel("Coins · confirmations", accent)
        Spacer(Modifier.height(8.dp))
        coins.sortedByDescending { it.value }.forEach { u ->
            val conf = if (u.height <= 0 || cs.height <= 0) 0 else cs.height - u.height + 1
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(street("${groupSats(u.value)} ${if (chain == Chain.BLAKE2B) "sats" else "poolsats"}"),
                        style = MaterialTheme.typography.bodyMedium, color = TextMain)
                    Text("${u.txid.take(8)}…:${u.vout}", style = MaterialTheme.typography.bodySmall, color = TextFaint)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (conf == 0) "0 conf" else "$conf conf" + if (conf >= 3) "  ✓" else "",
                    style = MaterialTheme.typography.bodySmall, color = if (conf >= 3) Good else if (conf == 0) Warn else TextSoft)
            }
        }
    }
}
