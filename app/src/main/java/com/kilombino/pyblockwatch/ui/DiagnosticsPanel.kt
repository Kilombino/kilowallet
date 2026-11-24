package com.kilombino.pyblockwatch.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.data.DiagLog
import com.kilombino.pyblockwatch.ui.i18n.I18n
import com.kilombino.pyblockwatch.ui.i18n.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the wallet talks to, for the log's header: kinds of backends, no host names or keys. */
internal fun diagBackends(vm: WalletViewModel, state: UiState): String {
    val e = vm.endpointFor(Chain.BLAKE2B)
    val btc = when {
        e.rpc != null -> "own node by RPC"
        e.isCustom -> "own Electrum server" + if (e.host.endsWith(".onion")) " (onion)" else ""
        else -> "default Electrum server"
    }
    val tor = when {
        com.kilombino.pyblockwatch.chain.Tor.running -> "on"
        com.kilombino.pyblockwatch.chain.Tor.alive -> "starting"
        com.kilombino.pyblockwatch.chain.Tor.available -> "off"
        else -> "not in this build"
    }
    return "wallet: ${if (state.isHot) "hot" else "watch-only"} · BTC: $btc · Tor: $tor · language: ${I18n.lang.value}"
}

/**
 * Settings › Diagnostics: the app's diagnostic log ([DiagLog]), to copy or share when something
 * went wrong, and to clear. Nothing is sent by the app itself.
 */
@Composable
internal fun DiagnosticsPanel(activity: FragmentActivity, accent: Color, vm: WalletViewModel, state: UiState) {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var askClear by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val small = MaterialTheme.typography.bodySmall
    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Diagnostics", style = MaterialTheme.typography.bodyMedium, color = TextMain)
        Explain("A log of what the wallet did lately (CoinJoin rounds, your node or server, Lightning, Ark, Tor, " +
            "updates and crashes), kept on this phone for a few days. It never holds your words, keys, invoices " +
            "or whole addresses. Copy or share it when you ask for help.")
        Row {
            TextButton(onClick = { scope.launch {
                // A clipboard takes a few hundred KB at most: the newest part of the log.
                val t = withContext(Dispatchers.IO) { DiagLog.export(activity, diagBackends(vm, state), maxChars = 200_000) }
                clip.setText(androidx.compose.ui.text.AnnotatedString(t)); copied = true
                android.widget.Toast.makeText(activity, I18n.tr("Log copied"), android.widget.Toast.LENGTH_SHORT).show()
            } }, contentPadding = PaddingValues(end = 12.dp)) {
                Text(if (copied) "LOG COPIED ✓" else "COPY LOG", color = if (copied) Good else accent, style = small)
            }
            TextButton(onClick = { scope.launch {
                val uri = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = java.io.File(activity.cacheDir, "diag-export").apply { mkdirs() }
                        dir.listFiles()?.forEach { it.delete() }
                        val f = java.io.File(dir, "kilowallet-log.txt")
                        f.writeText(DiagLog.export(activity, diagBackends(vm, state)))
                        androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.diag", f)
                    }.getOrNull()
                } ?: return@launch
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Kilowallet log")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { activity.startActivity(Intent.createChooser(send, I18n.tr("Share the log"))) }
            } }, contentPadding = PaddingValues(end = 12.dp)) { Text("SHARE LOG", color = accent, style = small) }
            TextButton(onClick = { askClear = true }, contentPadding = PaddingValues(0.dp)) {
                Text("CLEAR LOG", color = TextFaint, style = small)
            }
        }
    }
    if (askClear) AlertDialog(
        onDismissRequest = { askClear = false },
        title = { Text("Clear the log?") },
        text = { Text("Everything in the diagnostic log goes. Your wallet is not touched.") },
        confirmButton = { TextButton(onClick = { DiagLog.clear(); copied = false; askClear = false }) { Text("CLEAR", color = accent) } },
        dismissButton = { TextButton(onClick = { askClear = false }) { Text("CANCEL", color = TextFaint) } },
    )
}
