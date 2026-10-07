package com.kilombino.pyblockwatch.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.kilombino.pyblockwatch.ark.ArkBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Backup file: the WHOLE wallet in one file (words, settings, contacts, coinjoin
 * rounds and, when active, Ark with its on-chain coins and emergency exits). Restoring it on
 * a new phone brings everything back as it was.
 */
@Composable
fun FullBackupPanel(vm: WalletViewModel, accent: Color) {
    val ctx = LocalContext.current
    val activity = ctx as FragmentActivity
    val scope = rememberCoroutineScope()
    var askPassword by remember { mutableStateOf(false) }
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } }
            .onSuccess { message = "Backup file saved. Keep it somewhere safe, away from this phone." }
            .onFailure { message = "Error: ${it.message}" }
    }

    if (askPassword) {
        val mismatch = pw1 != pw2
        AlertDialog(
            onDismissRequest = { askPassword = false },
            title = { Text("Protect the file with a password?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Strongly recommended. The file contains your words: without a password, anyone who " +
                        "gets the file can take your money. Never put an unprotected file in cloud storage " +
                        "(Drive, iCloud…) or send it in a chat. If you forget the password, the file is useless.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = pw1, onValueChange = { pw1 = it },
                        label = { Text("password (optional)", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    if (pw1.isNotEmpty()) OutlinedTextField(value = pw2, onValueChange = { pw2 = it },
                        label = { Text("repeat password", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        isError = mismatch && pw2.isNotEmpty(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            },
            confirmButton = {
                TextButton(enabled = pw1.isEmpty() || !mismatch, onClick = {
                    val pw = pw1.ifEmpty { null }
                    askPassword = false; pw1 = ""; pw2 = ""
                    withHotWords(activity, vm, "Unlock to write the backup file",
                        onWords = { w ->
                            if (w == null) { message = "Only a spending wallet has a backup file."; return@withHotWords }
                            scope.launch {
                                message = "Preparing the backup file…"
                                val r = withContext(Dispatchers.IO) {
                                    runCatching { ArkBackup.encode(vm.fullSnapshot(w.words, w.passphrase), pw) }
                                }
                                r.onSuccess { bytes ->
                                    message = null; pending = bytes
                                    val day = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                                    saveFile.launch("kilowallet-$day.kab")
                                }.onFailure { message = "Error: ${it.message}" }
                            }
                        },
                        onError = { message = it })
                }) { Text(if (pw1.isEmpty()) "SAVE WITHOUT PASSWORD" else "SAVE", color = if (pw1.isEmpty()) Warn else accent) }
            },
            dismissButton = { TextButton(onClick = { askPassword = false; pw1 = ""; pw2 = "" }) { Text("CANCEL", color = TextSoft) } },
            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
        )
    }

    Text("Backup file", style = MaterialTheme.typography.bodyMedium, color = TextMain)
    Explain("Everything in one file: your words, settings, nodes, contacts, coinjoin rounds and, if " +
        "you use it, your Ark wallet with its on-chain coins and emergency exits. On a new phone, " +
        "\"Restore from a backup file\" brings it all back as it was. Save a new one after big changes.")
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Error")) Bad else Good) }
    Button(onClick = { message = null; askPassword = true },
        colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = accent),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text("SAVE A BACKUP FILE") }
}

/** Onboarding: restore the whole wallet from a .kab file (words, settings, Ark, coinjoins…). */
@Composable
fun RestoreFromFileButton(vm: WalletViewModel) {
    val ctx = LocalContext.current
    val activity = ctx as FragmentActivity
    val scope = rememberCoroutineScope()
    var fileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var warning by remember { mutableStateOf<String?>(null) }

    fun restore(s: ArkBackup.Snapshot) {
        runCatching { vm.seedEncryptCipher() }.onSuccess { cipher ->
            Biometric.authenticate(activity, "Restore your wallet", "Unlock to store your words on this phone", cipher,
                onSuccess = { authed ->
                    busy = "Restoring everything… (Ark can take a minute)"
                    vm.restoreFull(s, authed, onDone = { w -> busy = null; warning = w }, onError = { busy = null; error = it })
                },
                onError = { error = it })
        }.onFailure { error = it.message }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        error = null
        runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            .onSuccess { b ->
                runCatching { ArkBackup.isEncrypted(b) }
                    .onSuccess { enc ->
                        if (enc) { fileBytes = b; password = "" }
                        else runCatching { ArkBackup.decode(b, null) }.onSuccess(::restore).onFailure { error = it.message }
                    }
                    .onFailure { error = it.message }
            }
            .onFailure { error = it.message }
    }

    fileBytes?.let { bytes ->
        AlertDialog(
            onDismissRequest = { fileBytes = null },
            title = { Text("Backup password") },
            text = {
                OutlinedTextField(value = password, onValueChange = { password = it },
                    label = { Text("password", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            },
            confirmButton = {
                TextButton(onClick = {
                    val pw = password; fileBytes = null
                    scope.launch {
                        busy = "Opening the backup…"
                        val r = withContext(Dispatchers.Default) { runCatching { ArkBackup.decode(bytes, pw) } }
                        busy = null
                        r.onSuccess(::restore).onFailure { error = it.message }
                    }
                }) { Text("OPEN", color = Purple) }
            },
            dismissButton = { TextButton(onClick = { fileBytes = null }) { Text("CANCEL", color = TextSoft) } },
            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
        )
    }

    Spacer(Modifier.height(8.dp))
    Button(onClick = { openFile.launch(arrayOf("*/*")) }, enabled = busy == null,
        colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = TextMain),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
    ) { Text("RESTORE FROM A BACKUP FILE (.kab)", style = MaterialTheme.typography.titleSmall) }
    busy?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Purple) }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Bad) }
    warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Warn) }
}
