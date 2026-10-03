package com.kilombino.pyblockwatch.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.kilombino.pyblockwatch.ark.Ark
import com.kilombino.pyblockwatch.ark.ArkBackup
import com.kilombino.pyblockwatch.crypto.Bip39
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * The Ark wallet's words and backup file: activating Ark (with the XBT wallet's words when
 * there is a spending wallet), restoring from words or from a file, showing the words
 * behind a warning, and saving the backup file with an optional password.
 */

@Composable
fun WordsGrid(words: List<String>) {
    Panel(accent = Purple) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            words.chunked(2).forEachIndexed { rowIdx, pair ->
                Row(Modifier.fillMaxWidth()) {
                    pair.forEachIndexed { colIdx, w ->
                        Text("${rowIdx * 2 + colIdx + 1}. $w", style = MaterialTheme.typography.bodyMedium,
                             fontFamily = FontFamily.Monospace, color = TextMain, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Unlocks the spending wallet and hands its words and passphrase to [onWords]; null when
 * there is none.
 */
private fun withHotWords(
    activity: FragmentActivity, vm: WalletViewModel, why: String,
    onWords: (com.kilombino.pyblockwatch.data.SeedVault.Secret?) -> Unit, onError: (String) -> Unit,
) {
    if (!vm.hasSeed()) { onWords(null); return }
    runCatching { vm.seedDecryptCipher() }
        .onSuccess { cipher ->
            Biometric.authenticate(activity, "Unlock your wallet", why, cipher,
                onSuccess = { authed -> runCatching { vm.revealSecret(authed) }.onSuccess(onWords).onFailure { onError(it.message ?: "$it") } },
                onError = onError)
        }
        .onFailure { onError(it.message ?: "$it") }
}

/** The "no Ark wallet yet" panel: activate, or restore from words or from a backup file. */
@Composable
fun ArkActivate(vm: WalletViewModel, accent: Color, onReady: (newWords: List<String>?) -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as FragmentActivity
    val app = ctx.applicationContext
    val scope = rememberCoroutineScope()
    var understood by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf("activate") }      // activate | words
    var phrase by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var fileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var password by remember { mutableStateOf("") }
    // Restoring something whose words differ from the spending wallet's asks first.
    var mismatch by remember { mutableStateOf<(() -> Unit)?>(null) }
    val hot = vm.hasSeed()

    fun create(words: List<String>, passphrase: String, shared: Boolean, isNew: Boolean) {
        scope.launch {
            busy = if (isNew) "Creating your Ark wallet…" else "Restoring your Ark wallet… (this can take a minute)"
            error = null
            try {
                withContext(Dispatchers.IO) { Ark.createWallet(app, words, passphrase) }
                Ark.setWordsShared(app, shared)
                onReady(if (isNew && !shared) words else null)
            } catch (e: Exception) { error = e.message ?: e.toString() }
            busy = null
        }
    }

    fun restoreFile(s: ArkBackup.Snapshot, shared: Boolean) {
        scope.launch {
            busy = "Restoring from the backup file…"; error = null
            try {
                withContext(Dispatchers.IO) { Ark.restore(app, s) }
                Ark.setWordsShared(app, shared)
                onReady(null)
            } catch (e: Exception) { error = e.message ?: e.toString() }
            busy = null
        }
    }

    /**
     * Runs [go] with whether [words] and [passphrase] match the spending wallet's; asks
     * first when they do not.
     */
    fun checkAgainstHot(words: List<String>, passphrase: String, go: (shared: Boolean) -> Unit) {
        withHotWords(activity, vm, "Compare with your XBT wallet's words",
            onWords = { hot ->
                when {
                    hot == null -> go(false)
                    hot.words == words && hot.passphrase == passphrase -> go(true)
                    else -> mismatch = { go(false) }
                }
            },
            onError = { error = it })
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        error = null
        runCatching { app.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            .onSuccess { b ->
                runCatching { ArkBackup.isEncrypted(b) }
                    .onSuccess { enc ->
                        if (enc) { fileBytes = b; password = "" }
                        else runCatching { ArkBackup.decode(b, null) }
                            .onSuccess { s -> checkAgainstHot(s.words, s.passphrase) { shared -> restoreFile(s, shared) } }
                            .onFailure { error = it.message }
                    }
                    .onFailure { error = it.message }
            }
            .onFailure { error = it.message }
    }

    mismatch?.let { proceed ->
        AlertDialog(
            onDismissRequest = { mismatch = null },
            title = { Text("Different words") },
            text = { Text("These Ark words (or their passphrase) are not those of your XBT spending " +
                "wallet. You will have two sets of words to keep safe. Continue?") },
            confirmButton = { TextButton(onClick = { mismatch = null; proceed() }) { Text("CONTINUE", color = accent) } },
            dismissButton = { TextButton(onClick = { mismatch = null }) { Text("CANCEL", color = TextSoft) } },
            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
        )
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
                    val pw = password
                    fileBytes = null
                    scope.launch {
                        busy = "Opening the backup…"
                        val r = withContext(Dispatchers.Default) { runCatching { ArkBackup.decode(bytes, pw) } }
                        busy = null
                        r.onSuccess { s -> checkAgainstHot(s.words, s.passphrase) { shared -> restoreFile(s, shared) } }
                            .onFailure { error = it.message }
                    }
                }) { Text("OPEN", color = accent) }
            },
            dismissButton = { TextButton(onClick = { fileBytes = null }) { Text("CANCEL", color = TextSoft) } },
            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
        )
    }

    Panel(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { understood = !understood }) {
            Text(if (understood) "☑" else "☐", style = MaterialTheme.typography.titleLarge, color = accent)
            Spacer(Modifier.width(10.dp))
            Text("I have read the warnings above. Ark is beta software and I will start with a small amount.",
                 style = MaterialTheme.typography.bodySmall, color = TextSoft)
        }
        Spacer(Modifier.height(10.dp))
        Explain(if (hot) "Ark will use the same words as your XBT spending wallet, so one set of words " +
                    "backs up both. You will be asked to unlock."
                else "Ark gets new words. Write them down when they appear. If you create an XBT " +
                    "spending wallet later, it can use these same words.")
        Spacer(Modifier.height(12.dp))
        if (mode == "activate") {
            Button(
                enabled = understood && busy == null,
                onClick = {
                    if (hot) {
                        withHotWords(activity, vm, "Use the same words for Ark",
                            onWords = { w -> if (w != null) create(w.words, w.passphrase, shared = true, isNew = true) },
                            onError = { error = it })
                    } else {
                        val words = Bip39.fromEntropy(ByteArray(16).also { SecureRandom().nextBytes(it) })
                        create(words, "", shared = false, isNew = true)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
            ) { Text("ACTIVATE ARK", style = MaterialTheme.typography.titleMedium) }
            Row {
                TextButton(enabled = understood && busy == null, onClick = { mode = "words" }) {
                    Text("restore from words", color = TextSoft, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = understood && busy == null, onClick = { openFile.launch(arrayOf("*/*")) }) {
                    Text("restore from backup file", color = TextSoft, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Explain("Type the Ark wallet's 12 or 24 words. The Ark server hands back the coins it " +
                "holds for them; history is not recovered. A backup file does not depend on the server.")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = phrase, onValueChange = { phrase = it; error = null },
                label = { Text("Ark words", style = MaterialTheme.typography.bodySmall) },
                minLines = 3, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            PassphraseFields(phrase.trim().lowercase().split(Regex("\\s+")), passphrase, { passphrase = it }, null, null)
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = busy == null,
                onClick = {
                    val words = phrase.trim().lowercase().split(Regex("\\s+"))
                    if (!Bip39.isValid(words)) { error = "Those words are not a valid BIP-39 phrase." }
                    else checkAgainstHot(words, passphrase) { shared -> create(words, passphrase, shared, isNew = false) }
                },
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
            ) { Text("RESTORE ARK", style = MaterialTheme.typography.titleMedium) }
            TextButton(onClick = { mode = "activate" }) {
                Text("back", color = TextSoft, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    busy?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Warn) }
    error?.let { Text("Error: $it", style = MaterialTheme.typography.bodySmall, color = Bad) }
}

/** Shows freshly created Ark words once, to write down. */
@Composable
fun ArkNewWords(words: List<String>, accent: Color, onDone: () -> Unit) {
    Panel(accent = Bad) {
        SectionLabel("Write down your ${words.size} Ark words", Bad)
        Spacer(Modifier.height(6.dp))
        Explain("With these words the Ark server can hand your coins back on a new phone. Anyone " +
            "who sees them can take your money. Write them on paper — never a photo or the cloud. " +
            "You can see them again later under RECOVERY WORDS.")
    }
    WordsGrid(words)
    Button(onClick = onDone,
        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
    ) { Text("I'VE WRITTEN THEM DOWN", style = MaterialTheme.typography.titleMedium) }
}

/** Recovery words and backup file, under the Ark balance. */
@Composable
fun ArkBackupPanel(fingerprint: String?, accent: Color, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as FragmentActivity
    val app = ctx.applicationContext
    val scope = rememberCoroutineScope()
    var warnWords by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<List<String>?>(null) }
    var shownPassphrase by remember { mutableStateOf("") }
    var askPassword by remember { mutableStateOf(false) }
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<ByteArray, String?>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var backedUp by remember { mutableStateOf(Ark.backupFingerprint(app)) }
    val shared = Ark.wordsShared(app)

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val p = pending
        pending = null
        if (uri == null || p == null) return@rememberLauncherForActivityResult
        runCatching { app.contentResolver.openOutputStream(uri, "w")!!.use { it.write(p.first) } }
            .onSuccess { Ark.markBackedUp(app, p.second); backedUp = p.second; onMessage("Backup file saved.") }
            .onFailure { onMessage("Error: could not save the file: ${it.message}") }
    }

    if (warnWords) {
        AlertDialog(
            onDismissRequest = { warnWords = false },
            title = { Text("Show recovery words?") },
            text = { Text("Anyone who sees these words can take your money" +
                (if (shared) " — in Ark and in your XBT spending wallet, which uses the same words" else "") +
                ". Make sure no one is looking and nothing is recording the screen. " +
                "Never type them into a website or share them with anyone, including support.") },
            confirmButton = {
                TextButton(onClick = {
                    warnWords = false
                    Biometric.confirm(activity, "Show recovery words", "Confirm it is you",
                        onSuccess = { shown = Ark.words(app); shownPassphrase = Ark.passphrase(app) },
                        onError = { onMessage("Error: $it") })
                }) { Text("SHOW", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { warnWords = false }) { Text("CANCEL", color = TextSoft) } },
            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
        )
    }

    if (askPassword) {
        val mismatch = pw1 != pw2
        AlertDialog(
            onDismissRequest = { askPassword = false },
            title = { Text("Protect the file with a password?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Optional. The file contains your words: without a password, anyone who gets " +
                        "the file can take your money. If you forget the password, the file is useless.",
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
                    scope.launch {
                        busy = "Preparing the backup file…"
                        val r = withContext(Dispatchers.IO) {
                            runCatching { Ark.snapshot(app).let { s -> ArkBackup.encode(s, pw) to Ark.lastSnapshotFingerprint } }
                        }
                        busy = null
                        r.onSuccess { p ->
                            pending = p
                            val day = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                            saveFile.launch("kilombino-ark-$day.kab")
                        }.onFailure { onMessage("Error: ${it.message}") }
                    }
                }) { Text(if (pw1.isEmpty()) "SAVE WITHOUT PASSWORD" else "SAVE", color = accent) }
            },
            dismissButton = { TextButton(onClick = { askPassword = false; pw1 = ""; pw2 = "" }) { Text("CANCEL", color = TextSoft) } },
            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
        )
    }

    Panel(accent = accent) {
        SectionLabel("Backup", accent)
        Spacer(Modifier.height(6.dp))
        val (status, color) = when {
            backedUp == null -> "No backup file saved yet." to Warn
            fingerprint != null && backedUp != fingerprint ->
                "Your Ark wallet changed since the last backup file. Save a new one." to Warn
            else -> "Backup file up to date." to Good
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = color)
        Spacer(Modifier.height(4.dp))
        Explain("Words: the Ark server hands your coins back on a new phone. Backup file: also works " +
            "if the server disappears (emergency withdrawal on-chain), and keeps your history. Save " +
            "it again after each movement or renewal." +
            (if (shared) " Your Ark and XBT spending wallets share the same words." else ""))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArkSmallButton(if (shown == null) "RECOVERY WORDS" else "HIDE WORDS", accent, Modifier.weight(1f)) {
                if (shown == null) warnWords = true else shown = null
            }
            ArkSmallButton("SAVE BACKUP FILE", accent, Modifier.weight(1f)) { if (busy == null) askPassword = true }
        }
        busy?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = Warn) }
    }
    shown?.let {
        WordsGrid(it)
        if (shownPassphrase.isNotEmpty()) Panel(accent = Orange) {
            SectionLabel("+ passphrase", Orange)
            Spacer(Modifier.height(4.dp))
            Text(shownPassphrase, style = MaterialTheme.typography.bodyMedium,
                 fontFamily = FontFamily.Monospace, color = TextMain)
            Spacer(Modifier.height(4.dp))
            Explain("The words alone open a different, empty wallet: keep the passphrase too.")
        }
    }
}

@Composable
private fun ArkSmallButton(label: String, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = accent),
        shape = RoundedCornerShape(12.dp), modifier = modifier,
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}
