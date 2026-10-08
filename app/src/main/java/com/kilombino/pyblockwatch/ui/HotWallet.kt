package com.kilombino.pyblockwatch.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.kilombino.pyblockwatch.crypto.Bip39
import java.security.SecureRandom

// ------------------------------------------------------------------- create with dice

/**
 * Generate a seed the SeedSigner way: roll a physical die and tap what it shows, 50 times for
 * 12 words or 99 for 24. The rolls become entropy by SHA-256 (so the same rolls reproduce the
 * same seed on air-gapped hardware); the app never sees a network or the device RNG unless the
 * user picks the shortcut. The mnemonic is shown once to write down, then encrypted behind the
 * biometric gate on "create".
 */
@Composable
fun DiceScreen(vm: WalletViewModel, onBack: () -> Unit) {
    val activity = LocalContext.current as FragmentActivity
    var strength by remember { mutableStateOf(256) }          // 24 words by default
    var rolls by remember { mutableStateOf("") }
    var mnemonic by remember { mutableStateOf<List<String>?>(null) }
    // The Ark wallet's passphrase when its words are reused, so both stay one wallet.
    var arkPassphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val needed = if (strength == 128) 50 else 99

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(30.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("New wallet", style = MaterialTheme.typography.titleLarge, color = Purple,
                 modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("back", color = TextSoft,
                style = MaterialTheme.typography.bodySmall) }
        }

        val words = mnemonic
        if (words == null) {
            Panel(accent = Purple) {
                SectionLabel("Roll dice for your seed")
                Spacer(Modifier.height(8.dp))
                Explain("Tap the number each roll of a real die shows. $needed rolls become your " +
                    "seed by SHA-256 — the same rolls give the same seed on a SeedSigner, so you " +
                    "can verify this offline. Nothing leaves the phone.")
            }

            val arkCtx = LocalContext.current.applicationContext
            if (com.kilombino.pyblockwatch.ark.Ark.hasWords(arkCtx)) {
                Panel(accent = Good) {
                    SectionLabel("You already have Ark words", Good)
                    Spacer(Modifier.height(6.dp))
                    Explain("Use the same words for this spending wallet, so one set of words backs up both.")
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            Biometric.confirm(activity, "Use your Ark words", "Confirm it is you",
                                onSuccess = {
                                    runCatching {
                                        val ark = com.kilombino.pyblockwatch.ark.Ark
                                        ark.words(arkCtx) to ark.passphrase(arkCtx)
                                    }.onSuccess { (w, p) -> arkPassphrase = p; mnemonic = w }
                                        .onFailure { error = it.message }
                                },
                                onError = { error = it })
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Good, contentColor = Ink),
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                    ) { Text("USE MY ARK WORDS", style = MaterialTheme.typography.titleMedium) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                WordCountChip("12 words", strength == 128, Modifier.weight(1f)) { strength = 128; rolls = "" }
                WordCountChip("24 words", strength == 256, Modifier.weight(1f)) { strength = 256; rolls = "" }
            }

            Panel(accent = Orange) {
                Text("${rolls.length} / $needed rolls",
                     style = MaterialTheme.typography.titleMedium, color = Orange)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    for (n in 1..6) {
                        DieButton(n, enabled = rolls.length < needed, modifier = Modifier.weight(1f)) {
                            if (rolls.length < needed) rolls += n.toString()
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = { if (rolls.isNotEmpty()) rolls = rolls.dropLast(1) }) {
                        Text("⌫ undo", color = TextSoft, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { rolls = "" }) {
                        Text("clear", color = TextFaint, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }

            Button(
                onClick = {
                    error = null
                    runCatching { Bip39.fromEntropy(Bip39.entropyFromDiceRolls(rolls, strength)) }
                        .onSuccess { mnemonic = it }
                        .onFailure { error = it.message }
                },
                enabled = rolls.length == needed,
                colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Ink),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("GENERATE SEED", style = MaterialTheme.typography.titleMedium) }

            TextButton(onClick = {
                error = null
                val bytes = ByteArray(strength / 8).also { SecureRandom().nextBytes(it) }
                runCatching { Bip39.fromEntropy(bytes) }
                    .onSuccess { mnemonic = it }.onFailure { error = it.message }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("or use the phone's secure randomness", color = TextFaint,
                     style = MaterialTheme.typography.bodySmall)
            }
        } else {
            SeedBackup(
                words = words,
                initialPassphrase = arkPassphrase,
                onDiscard = { mnemonic = null; rolls = ""; arkPassphrase = "" },
                onConfirm = { passphrase ->
                    error = null
                    runCatching { vm.seedEncryptCipher() }
                        .onSuccess { cipher ->
                            Biometric.authenticate(
                                activity, "Protect your seed",
                                "Unlock to encrypt and store it",
                                cipher,
                                onSuccess = { authed -> vm.createHotWallet(words, passphrase, authed) { error = it } },
                                onError = { error = it },
                            )
                        }
                        .onFailure { error = it.message }
                },
            )
            error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun WordCountChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(11.dp))
            .background(if (selected) Purple.copy(alpha = 0.18f) else PanelSoft)
            .clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = if (selected) Purple else TextSoft,
             style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DieButton(n: Int, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (enabled) Orange.copy(alpha = 0.16f) else PanelSoft)
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$n", color = if (enabled) Orange else TextFaint,
             style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * The optional BIP-39 passphrase ("25th word"), asked twice when creating a wallet and once
 * when restoring. Shows the master fingerprint of words + passphrase so the user can write
 * it down and later check they typed the same passphrase: a different one opens a different,
 * empty wallet without any error.
 */
@Composable
fun PassphraseFields(
    words: List<String>?, passphrase: String, onPassphrase: (String) -> Unit,
    repeat: String?, onRepeat: ((String) -> Unit)?,
) {
    var visible by remember { mutableStateOf(false) }
    Panel(accent = Orange) {
        SectionLabel("Passphrase (optional)", Orange)
        Spacer(Modifier.height(6.dp))
        Explain("Leave it EMPTY for a wallet without a passphrase, the usual choice. If you add one, read this first:")
        Spacer(Modifier.height(4.dp))
        listOf(
            "Without the passphrase, your words open a different wallet: an empty one. Words alone are no longer a backup.",
            "If you lose the passphrase, you lose the funds. Nobody can recover it, not even with the words.",
            "Write it down, and keep it apart from the words: whoever finds both has everything.",
            "Every character counts: upper and lower case, spaces (also at the start or the end) and accents.",
        ).forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall, color = TextSoft) }
        Spacer(Modifier.height(8.dp))
        val transform = if (visible) androidx.compose.ui.text.input.VisualTransformation.None
            else androidx.compose.ui.text.input.PasswordVisualTransformation()
        OutlinedTextField(
            value = passphrase, onValueChange = { onPassphrase(it.replace("\n", "")) },
            label = { Text("passphrase (empty = none)", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodyMedium, singleLine = true,
            visualTransformation = transform,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        if (repeat != null && onRepeat != null && passphrase.isNotEmpty()) {
            OutlinedTextField(
                value = repeat, onValueChange = { onRepeat(it.replace("\n", "")) },
                label = { Text("repeat passphrase", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodyMedium, singleLine = true,
                visualTransformation = transform, isError = repeat.isNotEmpty() && repeat != passphrase,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (repeat != null && passphrase.isNotEmpty()) Text(
            when {
                repeat.isEmpty() -> "Type it again to confirm it. The wallet is not created until both match."
                repeat != passphrase -> "The two passphrases do not match."
                else -> "Both match."
            },
            color = if (repeat.isNotEmpty() && repeat != passphrase) Bad else if (repeat == passphrase) Good else TextFaint,
            style = MaterialTheme.typography.bodySmall)
        if (passphrase.isNotEmpty() && passphrase.length < 12) Text(
            "Short passphrase (${passphrase.length} characters): someone who finds your words could guess it. " +
                "Use at least 12 characters, or several words.",
            color = Warn, style = MaterialTheme.typography.bodySmall)
        if (passphrase.isNotEmpty() && passphrase != passphrase.trim()) Text(
            "It starts or ends with a space. That space is part of the passphrase and you will have to type it every time.",
            color = Warn, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (passphrase.isEmpty()) "No passphrase." else "${passphrase.length} characters",
                 color = TextFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (passphrase.isNotEmpty()) TextButton(onClick = { visible = !visible }) {
                Text(if (visible) "hide" else "show", color = Orange, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (words != null && Bip39.isValid(words)) {
            var fp by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(words, passphrase) {
                fp = null
                kotlinx.coroutines.delay(300)
                fp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.kilombino.pyblockwatch.crypto.Bip32Priv.fromSeed(Bip39.toSeed(words, passphrase))
                        .fingerprint().joinToString("") { "%02x".format(it) }
                }
            }
            Text("Wallet fingerprint: ${fp ?: "…"}", color = TextSoft,
                 style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text("Write it down too: the same words and passphrase always give this fingerprint " +
                "(Sparrow and SeedSigner show the same one).",
                 color = TextFaint, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SeedBackup(
    words: List<String>, initialPassphrase: String, onDiscard: () -> Unit, onConfirm: (passphrase: String) -> Unit,
) {
    SecureWhileShown()
    var passphrase by remember { mutableStateOf(initialPassphrase) }
    var repeat by remember { mutableStateOf(initialPassphrase) }
    Panel(accent = Bad) {
        SectionLabel("Write these ${words.size} words down", Bad)
        Spacer(Modifier.height(6.dp))
        Explain("This is the ONLY backup of your money. Anyone who sees it can take your coins; " +
            "if you lose it, no one can recover them. Write it on paper — never a photo or the cloud.")
    }
    Spacer(Modifier.height(10.dp))
    Panel(accent = Purple) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                words.chunked(2).forEachIndexed { rowIdx, pair ->
                    Row(Modifier.fillMaxWidth()) {
                        pair.forEachIndexed { colIdx, w ->
                            val n = rowIdx * 2 + colIdx + 1
                            Text(
                                "$n. $w",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace, color = TextMain,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    PassphraseFields(words, passphrase, { passphrase = it }, repeat, { repeat = it })
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { onConfirm(passphrase) },
        enabled = passphrase.isEmpty() || repeat == passphrase,
        colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Ink),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
    ) { Text(if (passphrase.isEmpty()) "I'VE WRITTEN IT DOWN — CREATE"
             else "I'VE WRITTEN BOTH DOWN — CREATE", style = MaterialTheme.typography.titleMedium) }
    TextButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) {
        Text("start over", color = TextFaint, style = MaterialTheme.typography.bodySmall)
    }
}

// ------------------------------------------------------------------- restore from words

@Composable
fun RestoreScreen(vm: WalletViewModel, onBack: () -> Unit) {
    val activity = LocalContext.current as FragmentActivity
    // The words as 12 (or 24) boxes; see SeedWordsInput.
    val words = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { repeat(12) { add("") } } }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(30.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Restore wallet", style = MaterialTheme.typography.titleLarge, color = Purple,
                 modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("back", color = TextSoft,
                style = MaterialTheme.typography.bodySmall) }
        }
        Panel(accent = Purple) {
            SectionLabel("Enter your seed words")
            Spacer(Modifier.height(8.dp))
            Explain("Type your 12 or 24 BIP-39 words, one per box (or paste them all in the first). " +
                "Tap a suggestion to fill a word in. They are checked before anything is stored, and " +
                "then encrypted behind your biometric. If the wallet had a passphrase, type it below " +
                "exactly; if it had none, leave it empty.")
        }
        SeedWordsInput(words)
        PassphraseFields(words.toList(), passphrase, { passphrase = it }, null, null)
        error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = {
                val words = seedWordsOrNull(words) ?: run { error = "Fill in every word with a BIP-39 word."; return@Button }
                if (!Bip39.isValid(words)) { error = "These words are not a valid seed: one is wrong or out of order (the checksum fails)."; return@Button }
                runCatching { vm.seedEncryptCipher() }.onSuccess { cipher ->
                    Biometric.authenticate(
                        activity, "Protect your seed", "Unlock to encrypt and store it", cipher,
                        onSuccess = { authed -> vm.createHotWallet(words, passphrase, authed) { error = it } },
                        onError = { error = it },
                    )
                }.onFailure { error = it.message }
            },
            enabled = words.all { it.isNotBlank() },
            colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Ink),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
        ) { Text("RESTORE", style = MaterialTheme.typography.titleMedium) }
    }
}

// ------------------------------------------------------------------- send

@Composable
fun SendSheet(vm: WalletViewModel, accent: Color, onClose: () -> Unit) {
    FlowOpenWhileShown()
    val activity = LocalContext.current as FragmentActivity
    val state by vm.state.collectAsState()
    var to by remember { mutableStateOf("") }
    var askedSave by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var feeRate by remember { mutableStateOf("2") }
    var coinControl by remember { mutableStateOf(false) }
    val selectedOutpoints = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    // More recipients in the same transaction (BTC only): address and amount per row.
    val extraTo = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val extraAmount = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val multi = state.selected == com.kilombino.pyblockwatch.chain.Chain.BLAKE2B
    var showScanner by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) showScanner = true }
    LaunchedEffect(state.selected) { vm.loadUtxos() }
    // Each payment gets its own offer to save the destination.
    val sent = state.sendPhase is SendPhase.Sent
    LaunchedEffect(sent) { if (!sent) askedSave = false }
    if (showScanner) {
        QrScannerDialog(
            onResult = { raw ->
                var v = raw.trim()
                // Accept a plain address or a BIP-21 URI (bitcoin:ADDR?amount=…), any case.
                val scheme = v.indexOf(':')
                if (scheme in 1..10 && v.substring(0, scheme).lowercase() == "bitcoin") v = v.substring(scheme + 1)
                v = v.substringBefore("?").trim()
                to = v
                showScanner = false
            },
            onDismiss = { showScanner = false },
        )
    }

    Panel(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("send", accent)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.resetSend(); onClose() }) {
                Text("close", color = TextSoft, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))

        when (val phase = state.sendPhase) {
            is SendPhase.Sent -> {
                // A payment to yourself (an exact coin, a consolidation) is not a contact.
                if (!askedSave && !vm.isOwnAddress(to)) SaveContactPrompt(to.trim(), accent) { askedSave = true }
                Text("Broadcast ✓", color = Good, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                SelectionContainer { Text(phase.txid, color = TextSoft,
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.resetSend(); onClose() },
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("DONE", style = MaterialTheme.typography.titleMedium)
                }
            }

            is SendPhase.Review -> {
                val d = phase.draft
                if (d.handle != null) {
                    // A user@domain handle: what gets paid is the address it resolved to, in full.
                    RowLine("To", d.handle, accent)
                    Text("resolves to " + d.toAddress, color = TextSoft, style = MaterialTheme.typography.bodySmall)
                } else RowLine("To", shortAddress(d.toAddress), accent)
                if (d.silentRecipient != null) {
                    Text("→ silent payment (BIP-352)", color = Good,
                         style = MaterialTheme.typography.bodySmall)
                }
                RowLine("Amount", "${groupSats(d.amount)} sats", accent)
                d.extra.forEachIndexed { i, (addr, sats) ->
                    if (addr.contains(" → ")) {
                        RowLine("To ${i + 2}", addr.substringBefore(" → "), accent)
                        Text("resolves to " + addr.substringAfter(" → "), color = TextSoft, style = MaterialTheme.typography.bodySmall)
                    } else RowLine("To ${i + 2}", shortAddress(addr), accent)
                    RowLine("Amount ${i + 2}", "${groupSats(sats)} sats", accent)
                }
                if (d.extra.isNotEmpty()) RowLine("Total sent", "${groupSats(d.totalSent)} sats", accent)
                if (d.chain == com.kilombino.pyblockwatch.chain.Chain.BLAKE2B)
                    Text("Signed with the unified sighash: valid only on BTC, it can't be replayed on the spamchain.",
                         color = TextFaint, style = MaterialTheme.typography.bodySmall)
                // A spamchain spend is signed the legacy way (the SHA-256 chain knows nothing
                // else), so if its coins existed before the fork the same bytes are also valid on
                // BTC: anyone can rebroadcast them there.
                if (d.chain == com.kilombino.pyblockwatch.chain.Chain.SHA256 &&
                    d.inputs.any { it.height in 1 until com.kilombino.pyblockwatch.chain.Chain.BLAKE2B_FORK_HEIGHT })
                    Text("⚠️ Some of these coins are from before the fork (block ${com.kilombino.pyblockwatch.chain.Chain.BLAKE2B_FORK_HEIGHT}). " +
                        "This spend is then valid on BTC too: anyone can rebroadcast it there, moving the same coins on BTC " +
                        "to the same addresses. Move those coins on the BTC tab first (that splits them), or only send to " +
                        "an address you also control on BTC.",
                        color = Warn, style = MaterialTheme.typography.bodySmall)
                RowLine("Fee", "${groupSats(d.fee)} sats", accent)
                RowLine("Change", if (d.change > 0) "${groupSats(d.change)} sats" else "—", accent)
                RowLine("Inputs", "${d.inputs.size}", accent)
                val concern = remember(d) { vm.feeConcern(d) }
                var feeOk by remember(d) { mutableStateOf(false) }
                if (concern != null) { Spacer(Modifier.height(6.dp)); FeeGate(concern, feeOk) { feeOk = !feeOk } }
                val canSign = concern == null || feeOk
                Spacer(Modifier.height(10.dp))
                if (!state.isHot) {
                    // Watch-only: the keys are elsewhere. Hand the signer a PSBT.
                    var origin by remember { mutableStateOf(vm.keyOrigin) }
                    OutlinedTextField(value = origin, onValueChange = { origin = it },
                        label = { Text("key origin (optional), e.g. [d34db33f/84'/0'/0']", style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Some signers need it to find their key; Bitcoin Knots does not.",
                        color = TextFaint, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { vm.exportPsbt(origin) }, enabled = canSign,
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                    ) { Text("EXPORT PSBT", style = MaterialTheme.typography.titleMedium) }
                    TextButton(onClick = { vm.resetSend() }, modifier = Modifier.fillMaxWidth()) {
                        Text("edit", color = TextFaint, style = MaterialTheme.typography.bodySmall)
                    }
                    return@Panel
                }
                Button(
                    onClick = {
                        vm.seedCipherOrToast()?.let { cipher ->
                            Biometric.authenticate(
                                activity, "Confirm payment", "Unlock to sign and send", cipher,
                                onSuccess = { authed -> vm.confirmSend(authed) },
                                onError = { vm.toast(it) }, // stays on review; the user can retry
                            )
                        }
                    },
                    enabled = canSign,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                ) { Text("CONFIRM & SIGN", style = MaterialTheme.typography.titleMedium) }
                TextButton(onClick = { vm.resetSend() }, modifier = Modifier.fillMaxWidth()) {
                    Text("edit", color = TextFaint, style = MaterialTheme.typography.bodySmall)
                }
            }

            is SendPhase.AwaitingSignature -> PsbtExchange(vm, phase, accent)

            SendPhase.Preparing, SendPhase.Broadcasting -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(accent); Spacer(Modifier.width(10.dp))
                    Text(if (phase is SendPhase.Broadcasting) "signing & broadcasting…" else "choosing coins…",
                         color = accent, style = MaterialTheme.typography.bodyMedium)
                }
            }

            else -> { // Editing or Failed
                if (state.sendPhase is SendPhase.Failed) {
                    Text((state.sendPhase as SendPhase.Failed).message, color = Bad,
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = to, onValueChange = { to = it },
                        label = { Text("recipient address", style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val pasted = clipboard.getText()?.text?.trim()
                        if (!pasted.isNullOrBlank()) {
                            var v = pasted
                            val sc = v.indexOf(':')
                            if (sc in 1..10 && v.substring(0, sc).lowercase() == "bitcoin") v = v.substring(sc + 1)
                            to = v.substringBefore("?").trim()
                        }
                    }) { Text("PASTE", color = accent, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) showScanner = true else cameraPermission.launch(android.Manifest.permission.CAMERA)
                    }) { Text("📷", color = accent, style = MaterialTheme.typography.titleMedium) }
                }
                ContactName(to.trim(), accent)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("address · sp1… (silent payment) · user@domain",
                         style = MaterialTheme.typography.bodySmall, color = TextFaint, modifier = Modifier.weight(1f))
                    ContactsButton(accent, fits = { k ->
                        k == com.kilombino.pyblockwatch.data.Contacts.Kind.XBT || k == com.kilombino.pyblockwatch.data.Contacts.Kind.HANDLE
                    }) { to = it }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amount, onValueChange = { amount = it.filter(Char::isDigit) },
                        label = { Text("amount (sats)", style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = feeRate,
                        onValueChange = { feeRate = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("sat/vB", style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (extraTo.isEmpty()) "fee 0.1–1000 sat/vB · MAX sends everything minus fee" else "fee 0.1–1000 sat/vB",
                         style = MaterialTheme.typography.bodySmall, color = TextFaint,
                         modifier = Modifier.weight(1f))
                    if (extraTo.isEmpty()) TextButton(onClick = {
                        val sel = if (coinControl) {
                            (state.utxos ?: emptyList()).filter { "${it.txid}:${it.vout}" in selectedOutpoints }
                        } else emptyList()
                        amount = vm.maxSendable(feeRate.toDoubleOrNull() ?: 1.0, sel).toString()
                    }) { Text("MAX", color = accent, style = MaterialTheme.typography.bodyMedium) }
                }

                // More recipients, all paid by this one transaction.
                if (multi) {
                    extraTo.indices.forEach { i ->
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("recipient ${i + 2}", style = MaterialTheme.typography.bodySmall, color = TextSoft,
                                 modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                val p = clipboard.getText()?.text?.trim()
                                if (!p.isNullOrBlank()) {
                                    var v = p; val sc = v.indexOf(':')
                                    if (sc in 1..10 && v.substring(0, sc).lowercase() == "bitcoin") v = v.substring(sc + 1)
                                    extraTo[i] = v.substringBefore("?").trim()
                                }
                            }) { Text("PASTE", color = accent, style = MaterialTheme.typography.bodySmall) }
                            ContactsButton(accent, fits = { k ->
                                k == com.kilombino.pyblockwatch.data.Contacts.Kind.XBT || k == com.kilombino.pyblockwatch.data.Contacts.Kind.HANDLE
                            }) { extraTo[i] = it }
                            TextButton(onClick = { extraTo.removeAt(i); extraAmount.removeAt(i) }) {
                                Text("✕", color = Bad, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = extraTo[i], onValueChange = { extraTo[i] = it },
                                label = { Text("address · user@domain", style = MaterialTheme.typography.bodySmall) },
                                textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                                modifier = Modifier.weight(2f),
                            )
                            OutlinedTextField(
                                value = extraAmount[i], onValueChange = { extraAmount[i] = it.filter(Char::isDigit) },
                                label = { Text("sats", style = MaterialTheme.typography.bodySmall) },
                                textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        ContactName(extraTo[i].trim(), accent)
                    }
                    TextButton(onClick = { extraTo.add(""); extraAmount.add("") }) {
                        Text("＋ ADD RECIPIENT", color = accent, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // Coin control: pick exactly which UTXOs to spend, or leave off for auto-select.
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Coin control", color = TextMain, style = MaterialTheme.typography.bodyMedium,
                         modifier = Modifier.weight(1f))
                    Switch(
                        checked = coinControl,
                        onCheckedChange = { coinControl = it; if (it) vm.loadUtxos() },
                        colors = SwitchDefaults.colors(checkedThumbColor = accent),
                    )
                }
                if (coinControl) {
                    if (state.utxosLoading) {
                        Text("loading coins…", color = TextFaint, style = MaterialTheme.typography.bodySmall)
                    } else {
                        val utxos = state.utxos ?: emptyList()
                        if (utxos.isEmpty()) {
                            Text("No spendable coins on this chain.", color = TextFaint,
                                 style = MaterialTheme.typography.bodySmall)
                        }
                        utxos.forEach { u ->
                            val key = "${u.txid}:${u.vout}"
                            val sel = key in selectedOutpoints
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { if (sel) selectedOutpoints.remove(key) else selectedOutpoints.add(key) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (sel) "☑" else "☐", color = if (sel) accent else TextFaint,
                                     modifier = Modifier.width(26.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${groupSats(u.value)} sats",
                                         color = if (sel) accent else TextMain,
                                         style = MaterialTheme.typography.bodyMedium)
                                    Text("${u.txid.take(8)}…:${u.vout} · m/…/${u.chainIndex}/${u.index}" +
                                        (if (u.height <= 0) " · 0 conf" else ""),
                                         color = TextFaint, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (selectedOutpoints.isNotEmpty()) {
                            val selSum = utxos.filter { "${it.txid}:${it.vout}" in selectedOutpoints }.sumOf { it.value }
                            Text("selected: ${groupSats(selSum)} sats", color = accent,
                                 style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val sel = if (coinControl) {
                            (state.utxos ?: emptyList()).filter { "${it.txid}:${it.vout}" in selectedOutpoints }
                        } else emptyList()
                        vm.prepareSend(
                            to.trim(),
                            amount.toLongOrNull() ?: 0L,
                            feeRate.toDoubleOrNull() ?: 1.0,
                            sel,
                            if (multi) extraTo.indices.map { extraTo[it].trim() to (extraAmount[it].toLongOrNull() ?: 0L) } else emptyList(),
                        )
                    },
                    enabled = to.isNotBlank() && (amount.toLongOrNull() ?: 0L) > 0 &&
                        (!multi || extraTo.indices.all { extraTo[it].isNotBlank() && (extraAmount[it].toLongOrNull() ?: 0L) > 0 }) &&
                        (!coinControl || selectedOutpoints.isNotEmpty()),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                ) { Text("REVIEW", style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

@Composable
private fun RowLine(label: String, value: String, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = TextFaint, style = MaterialTheme.typography.bodySmall,
             modifier = Modifier.weight(1f))
        Text(value, color = accent, style = MaterialTheme.typography.bodyMedium,
             fontWeight = FontWeight.Bold)
    }
}

// ------------------------------------------------------------------- high fee

/**
 * A fee that looks like a slip (see WalletViewModel.feeConcern) needs a second, explicit yes:
 * the confirm button stays off until this box is ticked.
 */
@Composable
fun FeeGate(concern: String, checked: Boolean, onToggle: () -> Unit) {
    Text("⚠ $concern Check it before signing: a fee can't be taken back.", color = Bad,
         style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onToggle() }.padding(vertical = 4.dp)) {
        Text(if (checked) "☑" else "☐", color = Bad, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Text("Yes, I want to pay this fee", color = TextMain, style = MaterialTheme.typography.bodySmall)
    }
}

// ------------------------------------------------------------------- PSBT (watch-only)

/** Give the PSBT to the signer (QR, copy, file) and take the signed one back (paste, QR, file). */
@Composable
private fun PsbtExchange(vm: WalletViewModel, phase: SendPhase.AwaitingSignature, accent: Color) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var scanning by remember { mutableStateOf(false) }
    val b64 = phase.base64
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(phase.psbt) } }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            .onSuccess { vm.clearPsbtError(); vm.importSignedPsbtFile(it) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) scanning = true }
    if (scanning) QrScannerDialog(onResult = { scanning = false; vm.clearPsbtError(); vm.importSignedPsbt(it) }, onDismiss = { scanning = false })

    Text("1. Sign it elsewhere", color = accent, style = MaterialTheme.typography.titleSmall)
    Text("Take this PSBT to your signer, for example Bitcoin Knots (walletprocesspsbt). It must sign with the " +
        "BLAKE2b unified sighash (0x21): an old-style signature would also be valid on the spamchain, so it is refused.",
        color = TextSoft, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    if (b64.length <= 2500) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrImage(b64, 260) }
    else Text("Too big for one QR: copy it or save it as a file.", color = TextFaint, style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(b64)) }) { Text("COPY", color = accent) }
        TextButton(onClick = { save.launch("kilowallet-${phase.draft.amount}.psbt") }) { Text("SAVE FILE", color = accent) }
    }
    Spacer(Modifier.height(10.dp))
    Text("2. Bring the signed PSBT back", color = accent, style = MaterialTheme.typography.titleSmall)
    Text("Only this same transaction is accepted, and every signature is checked here before anything is sent.",
        color = TextSoft, style = MaterialTheme.typography.bodySmall)
    state.psbtError?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { clipboard.getText()?.text?.let { vm.clearPsbtError(); vm.importSignedPsbt(it) } }) { Text("PASTE", color = accent) }
        TextButton(onClick = {
            if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) scanning = true
            else camera.launch(android.Manifest.permission.CAMERA)
        }) { Text("SCAN QR", color = accent) }
        TextButton(onClick = { open.launch(arrayOf("*/*")) }) { Text("OPEN FILE", color = accent) }
    }
    TextButton(onClick = { vm.clearPsbtError(); vm.resetSend() }, modifier = Modifier.fillMaxWidth()) {
        Text("cancel", color = TextFaint, style = MaterialTheme.typography.bodySmall)
    }
}

// ------------------------------------------------------------------- receive + QR

/** A QR of [text], rendered from ZXing (already in the app for scanning) — no new dependency. */
@androidx.compose.runtime.Composable
fun QrImage(text: String, sizeDp: Int) {
    val bmp = androidx.compose.runtime.remember(text) {
        val size = 512
        val matrix = com.google.zxing.qrcode.QRCodeWriter()
            .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        for (x in 0 until size) for (y in 0 until size) {
            b.setPixel(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        b
    }
    androidx.compose.foundation.Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = "Address QR",
        modifier = Modifier.size(sizeDp.dp),
    )
}

/**
 * Show a fresh, unused receive address and the exact derivation it came from. Works for any
 * wallet (watch-only or hot) — the address is derived publicly from the xpub. "Next" walks
 * forward so a user who wants a new address per payment can get one.
 */
@androidx.compose.runtime.Composable
fun ReceiveSheet(vm: WalletViewModel, accent: Color, onClose: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var index by remember { mutableStateOf(vm.nextReceiveIndex()) }
    val pair = remember(index) { vm.receiveAddress(index) }
    var sweeping by remember { mutableStateOf(false) }
    if (sweeping) {
        SweepSheet(vm, accent) { sweeping = false; vm.resetSweep() }
        return
    }

    Panel(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("receive", accent)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) {
                Text("close", color = TextSoft, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (pair == null) {
            Text("No wallet.", color = Bad, style = MaterialTheme.typography.bodySmall)
        } else {
            val (address, path) = pair
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .background(Color.White).padding(10.dp),
                ) { QrImage(address, 200) }
                Spacer(Modifier.height(10.dp))
                SelectionContainer {
                    Text(address, style = MaterialTheme.typography.bodyMedium,
                         fontFamily = FontFamily.Monospace, color = TextMain)
                }
                Spacer(Modifier.height(4.dp))
                Text("unused · $path", style = MaterialTheme.typography.bodySmall, color = TextFaint)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(address)) },
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f),
                ) { Text("COPY", style = MaterialTheme.typography.titleMedium) }
                Button(
                    onClick = { index += 1 },
                    colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = accent),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f),
                ) { Text("NEXT ADDRESS", style = MaterialTheme.typography.titleMedium) }
            }
            if (index > vm.nextReceiveIndex()) {
                TextButton(onClick = { index = vm.nextReceiveIndex() }, modifier = Modifier.fillMaxWidth()) {
                    Text("back to first unused", color = TextFaint, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = { sweeping = true }, modifier = Modifier.fillMaxWidth()) {
                Text("🔑  sweep a private key into this wallet", color = accent,
                     style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ------------------------------------------------------------------- sweep a private key

/**
 * Sweep a private key (WIF: a paper wallet, an exported key) into this wallet: find its coins
 * on the current chain, show what arrives and the fee, then move them all in one transaction
 * to this wallet's next receive address. The key is used for this transaction only and is
 * not stored.
 */
@Composable
fun SweepSheet(vm: WalletViewModel, accent: Color, onClose: () -> Unit) {
    SecureWhileShown() // a private key (WIF) is typed or pasted here
    val state by vm.state.collectAsState()
    val phase by vm.sweep.collectAsState()
    var wif by remember { mutableStateOf("") }
    var feeRate by remember { mutableStateOf("2") }
    var showKey by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) showScanner = true }
    if (showScanner) {
        QrScannerDialog(
            onResult = { raw -> wif = raw.trim(); showScanner = false; vm.resetSweep() },
            onDismiss = { showScanner = false },
        )
    }

    Panel(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("sweep a private key", accent)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) {
                Text("close", color = TextSoft, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(6.dp))
        when (val p = phase) {
            is SweepPhase.Sent -> {
                Text("Swept ✓", color = Good, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                SelectionContainer { Text(p.txid, color = TextSoft,
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                Spacer(Modifier.height(6.dp))
                Explain("The coins arrive in this wallet when the transaction confirms. The old key " +
                    "is empty now: do not use it again.")
                Spacer(Modifier.height(8.dp))
                Button(onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                ) { Text("DONE", style = MaterialTheme.typography.titleMedium) }
            }
            is SweepPhase.Review -> {
                val d = p.draft
                Text("Found on the ${d.chain.display} chain:", color = TextSoft, style = MaterialTheme.typography.bodySmall)
                d.coins.forEach { c ->
                    RowLine("${c.type.label} · ${shortAddress(c.address)}" +
                        (if (c.height <= 0) " · 0 conf" else ""), "${groupSats(c.value)} sats", accent)
                }
                Spacer(Modifier.height(6.dp))
                RowLine("Total", "${groupSats(d.total)} sats", accent)
                RowLine("Network fee", "${groupSats(d.fee)} sats", accent)
                RowLine("You receive", "${groupSats(d.received)} sats", Good)
                RowLine("To", shortAddress(d.toAddress), accent)
                if ((d.otherChainSats ?: 0) > 0) Text(
                    "This key also has ${groupSats(d.otherChainSats!!)} sats on the other chain: switch chain " +
                        "and sweep again to move those.", color = Warn, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { vm.confirmSweep() },
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                ) { Text("SWEEP INTO THIS WALLET", style = MaterialTheme.typography.titleMedium) }
                TextButton(onClick = { vm.resetSweep() }, modifier = Modifier.fillMaxWidth()) {
                    Text("edit", color = TextFaint, style = MaterialTheme.typography.bodySmall)
                }
            }
            SweepPhase.Scanning, SweepPhase.Broadcasting -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(accent); Spacer(Modifier.width(10.dp))
                    Text(if (p is SweepPhase.Broadcasting) "signing & broadcasting…" else "looking for the key's coins…",
                         color = accent, style = MaterialTheme.typography.bodyMedium)
                }
            }
            else -> { // Idle or Failed
                Explain("Paste or scan a private key (WIF: starts with 5, K or L), from a paper wallet " +
                    "or another wallet's export. Its coins on the ${state.selected.display} chain move to " +
                    "this wallet in one transaction, after you see the fee. The key is not stored.")
                Spacer(Modifier.height(8.dp))
                if (p is SweepPhase.Failed) {
                    Text(p.message, color = Bad, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = wif, onValueChange = { wif = it.trim(); vm.resetSweep() },
                        label = { Text("private key (WIF)", style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                        visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                            else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        clipboard.getText()?.text?.trim()?.let { if (it.isNotBlank()) { wif = it; vm.resetSweep() } }
                        // A private key must not linger on the clipboard, where other apps can read it.
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(""))
                    }) { Text("PASTE", color = accent, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) showScanner = true else cameraPermission.launch(android.Manifest.permission.CAMERA)
                    }) { Text("📷", color = accent, style = MaterialTheme.typography.titleMedium) }
                }
                if (wif.isNotEmpty()) TextButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "hide key" else "show key", color = TextFaint, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = feeRate, onValueChange = { feeRate = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("fee, sat/vB", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = { vm.prepareSweep(wif, feeRate.toDoubleOrNull() ?: 2.0) },
                    enabled = wif.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                ) { Text("FIND COINS", style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}
