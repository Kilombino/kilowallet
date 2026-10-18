package com.kilombino.pyblockwatch.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.kilombino.pyblockwatch.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kilombino.pyblockwatch.chain.RpcConn
import kotlinx.coroutines.launch

/**
 * Your own Bitcoin node (Knots with BLAKE2b) by RPC, instead of an Electrum server: one or more
 * ways to reach it (at home, a .onion from outside), tried in order. The node gets a watch-only
 * wallet for this account (public key only) and answers everything; the keys stay on the phone.
 */
@Composable
fun RpcNodePanel(vm: WalletViewModel, accent: androidx.compose.ui.graphics.Color, onSaved: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val conns = remember { mutableStateListOf<RpcConn>().apply { addAll(vm.rpcConns()) } }
    var on by remember { mutableStateOf(vm.useRpc()) }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    // An https node's certificate, shown for the user to compare before any password goes out.
    var toTrust by remember { mutableStateOf<Pair<RpcConn, String>?>(null) }
    var addAfterTrust by remember { mutableStateOf(false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) scanning = true }
    fun fill(text: String) {
        runCatching { RpcConn.parse(text) }.onSuccess { c ->
            url = c.url; if (c.user.isNotEmpty()) user = c.user; if (c.pass.isNotEmpty()) pass = c.pass; msg = null
        }.onFailure { msg = "Not a node address: ${it.message}" }
    }
    if (scanning) QrScannerDialog(onResult = { scanning = false; fill(it) }, onDismiss = { scanning = false })
    fun save() { vm.setRpcConns(conns.toList()); onSaved() }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Or your own node by RPC", style = MaterialTheme.typography.bodyMedium, color = TextMain)
        Explain("Connect straight to your Bitcoin node (Knots with BLAKE2b), no Electrum server needed. It gets a " +
            "watch-only wallet for this account (public key only); your keys never leave the phone. Add more than one " +
            "way to reach it (at home, a .onion from outside): they are tried in order. The node's wallet feature must be on. " +
            "On StartOS, copy the address from the node's RPC interface (https://… at home, http://….onion from outside) and " +
            "make a user with its Generate RPC User Credentials action.")
        conns.forEachIndexed { i, c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ${c.url}" + if (c.user.isNotEmpty()) " (${c.user})" else "", style = MaterialTheme.typography.bodySmall,
                    color = TextSoft, modifier = Modifier.weight(1f))
                if (i > 0) TextButton(onClick = { conns.add(i - 1, conns.removeAt(i)); save() }) { Text("▲", color = accent) }
                TextButton(onClick = { conns.removeAt(i); save(); if (conns.isEmpty()) on = false }) { Text("✕", color = Bad) }
            }
        }
        if (conns.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Read BTC from my node (RPC)", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                Explain(if (on) "On: your node answers everything. The first time it looks for this wallet's coins, which can take a few minutes."
                    else "Off: the Electrum server above is used.")
            }
            Switch(checked = on, onCheckedChange = { on = it; vm.setUseRpc(it); onSaved() },
                colors = switchColors(accent))
        }
        OutlinedTextField(value = url, onValueChange = { url = it.trim(); msg = null },
            label = { Text("the RPC address, port included", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = user, onValueChange = { user = it.trim() },
                label = { Text("RPC user", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodySmall, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = pass, onValueChange = { pass = it },
                label = { Text("password", style = MaterialTheme.typography.bodySmall) }, visualTransformation = PasswordVisualTransformation(),
                textStyle = MaterialTheme.typography.bodySmall, singleLine = true, modifier = Modifier.weight(1f))
        }
        // Wraps onto a second line when the words are longer (Spanish, Chinese).
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow {
            TextButton(onClick = {
                if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) scanning = true
                else camera.launch(android.Manifest.permission.CAMERA)
            }) { Text("📷 SCAN QR", color = accent, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.primaryClip?.getItemAt(0)?.text?.toString()?.let { fill(it) }
            }) { Text("PASTE", color = accent, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = {
                val c = runCatching { RpcConn.parse(url).copy(user = user, pass = pass) }.getOrElse { msg = it.message; return@TextButton }
                if (c.isTls) {
                    // First the certificate alone, for the user to check; the login only after.
                    msg = "reading the certificate…"; addAfterTrust = false
                    scope.launch { vm.probeRpcCertificate(c).onSuccess { msg = null; toTrust = c to it }.onFailure { msg = "✗ " + (it.message ?: "no answer") } }
                    return@TextButton
                }
                msg = "testing…"
                scope.launch { msg = vm.testRpc(c).first }
            }) { Text("TEST", color = accent, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = {
                val c = runCatching { RpcConn.parse(url).copy(user = user, pass = pass) }.getOrElse { msg = it.message; return@TextButton }
                if (!c.isTls) { conns.add(c); url = ""; user = ""; pass = ""; msg = "added"; save(); return@TextButton }
                // https (a StartOS node): show its certificate; it is trusted only once the user says it matches.
                msg = "reading the certificate…"; addAfterTrust = true
                scope.launch { vm.probeRpcCertificate(c).onSuccess { msg = null; toTrust = c to it }.onFailure { msg = "✗ " + (it.message ?: "no answer") } }
            }) { Text("ADD", color = Good, style = MaterialTheme.typography.bodySmall) }
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("✗")) Bad else TextFaint) }
        Spacer(Modifier.height(4.dp))
    }
    toTrust?.let { (c, fp) ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { toTrust = null },
            title = { Text("Is this your node's certificate?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${c.url} shows this certificate (SHA-256):", style = MaterialTheme.typography.bodySmall)
                    Text(com.kilombino.pyblockwatch.chain.NodeRpcBackend.spaced(fp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = TextMain)
                    Text("Compare it with the one your node shows (on StartOS, the RPC interface's certificate). Only if it is the same, " +
                        "trust it: the password is then sent to this certificate only. Someone on your network could show another one.",
                        style = MaterialTheme.typography.bodySmall, color = Warn)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    toTrust = null
                    val pinned = c.copy(pin = fp)
                    msg = "testing…"
                    scope.launch {
                        val (t, _) = vm.testRpc(pinned)
                        if (t.startsWith("✗") || !addAfterTrust) { msg = t; return@launch }
                        conns.add(pinned); url = ""; user = ""; pass = ""
                        msg = "added · $t"; save()
                    }
                }) { Text("IT MATCHES: TRUST IT", color = Good) }
            },
            dismissButton = { TextButton(onClick = { toTrust = null; msg = "not trusted: nothing was sent" }) { Text("CANCEL") } })
    }
}
