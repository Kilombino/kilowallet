package com.kilombino.pyblockwatch.ui

import com.kilombino.pyblockwatch.data.Scanner
import com.kilombino.pyblockwatch.data.AppUpdater

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.kilombino.pyblockwatch.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.data.AddressRow
import com.kilombino.pyblockwatch.data.TxConf
import com.kilombino.pyblockwatch.data.WatchService
import androidx.lifecycle.withResumed
import androidx.lifecycle.lifecycleScope

class MainActivity : FragmentActivity() {

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result handled by the switch state itself */ }

    // Leaving the screen (home, another app, the phone locking) puts street mode back on.
    override fun onStart() {
        super.onStart()
        AppVisible.value = true
    }

    override fun onStop() {
        super.onStop()
        AppVisible.value = false
        StreetMode.lock()
    }

    // A notification asks to open its tab (BTC, SPAMCOIN, ARK, COINJOIN), refreshed.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        com.kilombino.pyblockwatch.data.OpenTab.from(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A debuggable build (never a published one) also watches JoinMarket wallets of the test
        // networks, so a fidelity bond can be spent end to end against a regtest node.
        com.kilombino.pyblockwatch.data.JoinMarket.testNetworksAllowed =
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        com.kilombino.pyblockwatch.data.OpenTab.from(intent)
        // A coinjoin round left running (app killed, updated, phone restarted) picks up again.
        // Looked for off the main thread: it opens each round's key from the Keystore.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.hasActive(this@MainActivity))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    com.kilombino.pyblockwatch.coinjoin.CoinjoinService.start(this@MainActivity)
                }
        }
        enableEdgeToEdge()
        StreetMode.init(this)
        com.kilombino.pyblockwatch.chain.Tor.install(this)
        setContent {
            PyBlockWatchTheme {
                val vm: WalletViewModel = viewModel()
                val state by vm.state.collectAsState()
                // Notifications are on by default: start the watcher (and ask for the
                // POST_NOTIFICATIONS permission on Android 13+) as soon as there is a wallet.
                // With background checks off (⏻ or settings) the watcher, and its persistent
                // notification, stop. One already running is left alone: restarting it on every
                // opening repeated all its network work right next to the app's own first look.
                // It reads a changed interval itself, within a minute.
                LaunchedEffect(state.hasWallet, state.jamView, state.notificationsEnabled, state.refreshBackgroundMinutes) {
                    if ((state.hasWallet || vm.hasJamWallet()) && state.notificationsEnabled) {
                        if (state.refreshBackgroundMinutes > 0) toggleNotifications(vm, true)
                        else stopService(Intent(this@MainActivity, WatchService::class.java))
                    }
                }
                // ARK and COINJOIN live in advanced mode: a notification about them switches to it.
                val openTab by com.kilombino.pyblockwatch.data.OpenTab.flow.collectAsState()
                LaunchedEffect(openTab) {
                    if (state.uiMode == "simple" && (openTab == com.kilombino.pyblockwatch.data.OpenTab.ARK ||
                            openTab == com.kilombino.pyblockwatch.data.OpenTab.COINJOIN)) vm.setUiMode("advanced")
                }
                Box(Modifier.fillMaxSize().background(Ink)) {
                    // A newer release on GitHub: looked for every 5 minutes while the app is open
                    // (the watcher also looks in the background and notifies), offered once per
                    // version, and installed from inside the app (AppUpdater).
                    var update by remember { mutableStateOf<com.kilombino.pyblockwatch.data.UpdateCheck.Release?>(null) }
                    var forceAsk by remember { mutableStateOf(0) }
                    // Asked for with CHECK NOW: offered even during a coinjoin round, saying so.
                    var asked by remember { mutableStateOf(false) }
                    LaunchedEffect(openTab) {
                        if (openTab == com.kilombino.pyblockwatch.data.OpenTab.UPDATE) {
                            com.kilombino.pyblockwatch.data.OpenTab.flow.value = null; forceAsk++
                        }
                    }
                    LaunchedEffect(forceAsk) {
                        val prefs = getSharedPreferences("pyblockwatch", MODE_PRIVATE)
                        while (true) {
                            // CHECK NOW asks even with the periodic check switched off.
                            val now = com.kilombino.pyblockwatch.data.OpenTab.checkNow
                            // Not while in the background: the watcher looks (and notifies) there.
                            if (now || (AppVisible.value && prefs.getBoolean("check_updates", true))) {
                                com.kilombino.pyblockwatch.data.OpenTab.checkNow = false
                                val current = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
                                val o = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.kilombino.pyblockwatch.data.UpdateCheck.outcome(current)
                                }
                                val r = (o as? com.kilombino.pyblockwatch.data.UpdateCheck.Outcome.Newer)?.release
                                if (r != null && (forceAsk > 0 || prefs.getString("update_dismissed", null) != r.version)) {
                                    update = r; asked = now
                                }
                                if (now) when (o) {
                                    is com.kilombino.pyblockwatch.data.UpdateCheck.Outcome.Newer -> {}
                                    com.kilombino.pyblockwatch.data.UpdateCheck.Outcome.UpToDate -> android.widget.Toast.makeText(this@MainActivity,
                                        com.kilombino.pyblockwatch.ui.i18n.I18n.tr("You have the latest Kilowallet ($current)."), android.widget.Toast.LENGTH_SHORT).show()
                                    com.kilombino.pyblockwatch.data.UpdateCheck.Outcome.Unreachable -> android.widget.Toast.makeText(this@MainActivity,
                                        com.kilombino.pyblockwatch.ui.i18n.I18n.tr("Could not reach GitHub to check. Try again in a moment."), android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                            kotlinx.coroutines.delay(5 * 60 * 1000L)
                        }
                    }
                    val upd by com.kilombino.pyblockwatch.data.AppUpdater.state.collectAsState()
                    val uri = androidx.compose.ui.platform.LocalUriHandler.current
                    // Never on top of something half done: a send being reviewed or signed, or a
                    // coinjoin round past "waiting for people". It shows once that is over.
                    val cjVersion by com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.version.collectAsState()
                    val busy = openFlows.intValue > 0 ||
                        remember(cjVersion) { com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.inRound() }
                    update?.takeIf { !busy || asked }?.let { r ->
                        if (upd is com.kilombino.pyblockwatch.data.AppUpdater.State.Idle) AlertDialog(
                            onDismissRequest = { update = null },
                            title = { Text("Kilowallet ${r.version} is out") },
                            text = { Text("Update now: it downloads here, is checked against the published hash and this " +
                                "app's certificate, and installs over this one keeping everything." +
                                (if (r.apkUrl == null) "\n\nThis release can't be installed from here (no APK, or no published hash to check it against): open its page instead." else "") +
                                (if (busy) "\n\nA coinjoin round of yours is under way: updating now leaves it (your coin " +
                                    "stays where it is), or choose NOT NOW and update once it is over." else ""),
                                style = MaterialTheme.typography.bodySmall) },
                            confirmButton = { TextButton(onClick = {
                                if (r.apkUrl != null) com.kilombino.pyblockwatch.data.AppUpdater.start(this@MainActivity, r)
                                else { runCatching { uri.openUri(r.url) }; update = null }
                            }) { Text(if (r.apkUrl != null) "UPDATE NOW" else "OPEN", color = Purple) } },
                            dismissButton = { TextButton(onClick = {
                                getSharedPreferences("pyblockwatch", MODE_PRIVATE).edit().putString("update_dismissed", r.version).apply()
                                update = null
                            }) { Text("NOT NOW", color = TextSoft) } },
                            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
                        )
                    }
                    if (upd !is com.kilombino.pyblockwatch.data.AppUpdater.State.Idle) {
                        AlertDialog(
                            onDismissRequest = {},
                            title = { Text("Updating Kilowallet") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    when (val st = upd) {
                                        is AppUpdater.State.Downloading -> {
                                            Text("Downloading ${st.version}… ${st.percent}%", style = MaterialTheme.typography.bodyMedium)
                                            androidx.compose.material3.LinearProgressIndicator(progress = { st.percent / 100f },
                                                modifier = Modifier.fillMaxWidth(), color = Purple)
                                        }
                                        is AppUpdater.State.Verifying -> Text("Checking the hash and the certificate…", style = MaterialTheme.typography.bodyMedium)
                                        is AppUpdater.State.NeedsPermission -> Text("Android needs you to allow installs from Kilowallet once. " +
                                            "Turn it on, come back and tap CONTINUE.", style = MaterialTheme.typography.bodySmall)
                                        is AppUpdater.State.Installing -> Text("Installing… Android may ask you to confirm. Android closes the app to replace it: " +
                                            "a notification tells you when the new version is in, tap it to open it.", style = MaterialTheme.typography.bodySmall)
                                        is AppUpdater.State.Failed -> Text(st.message, style = MaterialTheme.typography.bodySmall, color = Bad)
                                        is AppUpdater.State.Saved -> {
                                            // Two commands to paste in a terminal, each copied with one tap: the
                                            // dialog's text can't be selected with the pointer.
                                            val path = "'" + st.path.replace("'", "'\\''") + "'"
                                            Text("Checked and saved:", style = MaterialTheme.typography.bodySmall)
                                            androidx.compose.material3.Text(st.path, style = MaterialTheme.typography.bodySmall,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = TextMain)
                                            Text("1. Check its hash in a terminal (it must say OK):", style = MaterialTheme.typography.bodySmall)
                                            CommandToCopy("echo '${st.sha256}  ${st.path.replace("'", "")}' | sha256sum -c")
                                            Text("2. Install it:", style = MaterialTheme.typography.bodySmall)
                                            CommandToCopy("sudo apt install $path")
                                            Text("Then open Kilowallet again.", style = MaterialTheme.typography.bodySmall)
                                        }
                                        else -> {}
                                    }
                                }
                            },
                            confirmButton = {
                                when (upd) {
                                    is AppUpdater.State.NeedsPermission -> Row {
                                        TextButton(onClick = {
                                            runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                android.net.Uri.parse("package:$packageName"))) }
                                        }) { Text("ALLOW", color = Purple) }
                                        TextButton(onClick = { AppUpdater.resume(this@MainActivity) }) { Text("CONTINUE", color = Purple) }
                                    }
                                    is AppUpdater.State.Failed, is AppUpdater.State.Saved -> Row {
                                        update?.let { r -> TextButton(onClick = { runCatching { uri.openUri(r.url) } }) { Text("GITHUB", color = TextSoft) } }
                                        TextButton(onClick = { AppUpdater.reset(); update = null }) { Text("CLOSE", color = Purple) }
                                    }
                                    else -> {}
                                }
                            },
                            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
                        )
                    }
                    // The last run ended on an error: offer to share what failed (nothing is sent by itself).
                    var crash by remember { mutableStateOf(com.kilombino.pyblockwatch.data.CrashLog.pending(this@MainActivity)) }
                    // A crash inside a native library (LND's Go, the Ark engine) never reaches the
                    // handler above: Android's own record of it is read instead, once.
                    LaunchedEffect(Unit) {
                        if (crash == null) crash = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { com.kilombino.pyblockwatch.data.CrashReport.fresh(this@MainActivity)?.text }.getOrNull()
                        }
                    }
                    crash?.let { report ->
                        AlertDialog(
                            onDismissRequest = {},
                            title = { Text("Kilowallet closed unexpectedly") },
                            text = {
                                Column {
                                    Text("The last time, the app closed on an error. The report says where in the code it " +
                                        "failed, the app version and the phone model; no words, keys or addresses. Share it with the " +
                                        "developer to get it fixed? Nothing is sent unless you do." +
                                        "\n\nServer names are blanked out. This is exactly what would be shared:",
                                        style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.height(8.dp))
                                    // The exact text that would be shared.
                                    Text(com.kilombino.pyblockwatch.data.CrashReport.redact(report),
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                        modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                                }
                            },
                            confirmButton = { TextButton(onClick = {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Kilowallet crash report")
                                    putExtra(android.content.Intent.EXTRA_TEXT, com.kilombino.pyblockwatch.data.CrashReport.redact(report))
                                }
                                runCatching { startActivity(android.content.Intent.createChooser(send, "Share the crash report")) }
                                com.kilombino.pyblockwatch.data.CrashLog.clear(this@MainActivity); crash = null
                            }) { Text("SHARE REPORT", color = Purple) } },
                            dismissButton = { TextButton(onClick = {
                                com.kilombino.pyblockwatch.data.CrashLog.clear(this@MainActivity); crash = null
                            }) { Text("DISMISS", color = TextSoft) } },
                            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
                        )
                    }
                    // A new spending wallet is asked once whether it wants coinjoins.
                    var askCoinjoin by remember { mutableStateOf(false) }
                    // After the simple/advanced choice, not on top of it.
                    LaunchedEffect(state.showWallet, state.isHot, state.uiMode) {
                        askCoinjoin = state.showWallet && state.isHot && state.uiMode != null && !vm.coinjoinAsked()
                    }
                    val askNotif = rememberNotificationPermission()
                    if (askCoinjoin) androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            CoinjoinIntro(Good,
                                onAccept = { vm.answerCoinjoin(true); askCoinjoin = false; askNotif() },
                                onDecline = { vm.answerCoinjoin(false); askCoinjoin = false },
                                onLookOnly = { vm.answerCoinjoin(true, notify = false); askCoinjoin = false })
                        }
                    }
                    if (state.jamView && !state.setupMode) {
                        // The JoinMarket wallet (Jam, read-only): its own screen, no simple mode.
                        val jvm: JamViewModel = viewModel()
                        JamScreen(vm, jvm, onToggleNotifications = { on -> toggleNotifications(vm, on) })
                    } else if (state.showWallet) {
                        // Simple / Advanced: chosen once, switchable from the top of either screen.
                        when (state.uiMode) {
                            null -> ModeChooser(vm)
                            "simple" -> SimpleScreen(state, vm, onToggleNotifications = { on -> toggleNotifications(vm, on) })
                            else -> WalletScreen(
                                state = state, vm = vm,
                                onToggleNotifications = { on -> toggleNotifications(vm, on) },
                            )
                        }
                    } else {
                        OnboardingScreen(state, vm)
                    }
                }
            }
        }
    }

    private fun toggleNotifications(vm: WalletViewModel, enable: Boolean) {
        if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            getSharedPreferences("pyblockwatch", MODE_PRIVATE).edit().putBoolean("notif_asked", true).apply()
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.setNotificationsEnabled(enable)
        val intent = Intent(this, WatchService::class.java)
        if (!enable) { stopService(intent); return }
        // Android only lets an app start a foreground service while it is visible: called from
        // onCreate (the first composition), Android 17 turns it down. Start it once the screen is
        // resumed, and never let a refusal crash the app; the watcher is started again next time.
        lifecycleScope.launch {
            lifecycle.withResumed { }
            runCatching { ContextCompat.startForegroundService(this@MainActivity, intent) }
                .onFailure { android.util.Log.w("Kilowallet", "watcher not started: ${it.message}") }
        }
    }
}

// ---------------------------------------------------------------- onboarding

@Composable
private fun OnboardingScreen(state: UiState, vm: WalletViewModel) {
    var mode by remember { mutableStateOf("home") }
    when (mode) {
        "dice" -> { DiceScreen(vm) { mode = "home" }; return }
        "restore" -> { RestoreScreen(vm) { mode = "home" }; return }
    }

    var xpub by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<ScriptType?>(null) }
    var label by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) showScanner = true }

    if (showScanner) {
        QrScannerDialog(
            onResult = { raw ->
                xpub = Regex("(?:[xyz]pub)[1-9A-HJ-NP-Za-km-z]+").find(raw)?.value ?: raw.trim()
                showScanner = false
                if (state.inputError != null) vm.clearError()
            },
            onDismiss = { showScanner = false },
        )
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        if (state.hasWallet) {
            TextButton(onClick = { vm.endSetup() }) {
                Text("← back to wallet", color = TextSoft, style = MaterialTheme.typography.bodySmall)
            }
        }
        // The wallet's coin, big, above its name.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AppLogo(160) }
        Text("Kilowallet", style = MaterialTheme.typography.displayLarge, color = Purple)
        Text("BITCOIN BLAKE2b (BTC) WALLET", style = MaterialTheme.typography.titleLarge, color = TextSoft)
        if (state.hasWallet) {
            Explain("Creating or importing a wallet here REPLACES the current one. Your coins are " +
                "safe on-chain; make sure you still have this wallet's backup before switching.")
        } else {
            Explain("Kilowallet holds two different wallets that live side by side: a HOT wallet you " +
                "spend from, and a WATCH-ONLY one that only looks at another wallet. Start with " +
                "whichever you want; you can add the other one later from the top of the screen.")
        }

        Panel(accent = Purple) {
            SectionLabel("Hot wallet")
            Spacer(Modifier.height(8.dp))
            Explain(
                "Create a wallet you can send from. Its seed is generated on THIS phone — roll " +
                    "real dice, SeedSigner-style — and stored encrypted, unlocked only by your " +
                    "fingerprint or PIN when you spend. Native SegWit (bc1q) by default."
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { mode = "dice" },
                    colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f),
                ) { Text("NEW (DICE)", style = MaterialTheme.typography.titleMedium) }
                Button(
                    onClick = { mode = "restore" },
                    colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = TextMain),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f),
                ) { Text("RESTORE", style = MaterialTheme.typography.titleMedium) }
            }
            // The whole wallet from a backup file: words, settings, contacts, Ark, coinjoins.
            RestoreFromFileButton(vm)
        }

        Panel(accent = Orange) {
            SectionLabel("Watch-only wallet", Orange)
            Spacer(Modifier.height(8.dp))
            Explain(
                "Paste an extended PUBLIC key (xpub/ypub/zpub) to watch balances. It holds no keys: to " +
                    "send, it hands a PSBT to a separate signer. Careful: an xpub reveals every address " +
                    "you will ever use — keep it like a bank statement."
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = xpub,
                onValueChange = { xpub = it; if (state.inputError != null) vm.clearError() },
                label = { Text("xpub / ypub / zpub", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodySmall,
                isError = state.inputError != null,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            state.inputError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Bad) }
            XpubTypeChoice(xpub, picked, Orange) { picked = it }

            TextButton(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                ) showScanner = true else cameraPermission.launch(Manifest.permission.CAMERA)
            }) { Text("📷  Scan a QR with the camera", color = Orange,
                      style = MaterialTheme.typography.bodySmall) }

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Name (optional)", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { vm.setXpub(xpub, label, picked.takeIf { xpubNeedsType(xpub) }) },
                enabled = xpub.isNotBlank() && (picked != null || !xpubNeedsType(xpub)),
                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("WATCH BOTH CHAINS", style = MaterialTheme.typography.titleMedium) }
        }

        // A Jam (JoinMarket) wallet, read-only: the third wallet.
        var importJam by remember { mutableStateOf(false) }
        val jvm: JamViewModel = viewModel()
        if (importJam) JamImportDialog(jvm, JamTeal, onImported = { importJam = false; vm.viewJam() }, onClose = { importJam = false })
        Panel(accent = JamTeal) {
            SectionLabel("JoinMarket wallet (Jam)", JamTeal)
            Spacer(Modifier.height(8.dp))
            Explain("Follow your Jam wallet from the phone, read-only: its five jars, their movements and your fidelity " +
                "bonds, with a warning before a bond unlocks. In Jam: Settings › View in Kilowallet (read-only).")
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { importJam = true },
                colors = ButtonDefaults.buttonColors(containerColor = JamTeal, contentColor = Ink),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
            ) { Text("WATCH A JAM WALLET", style = MaterialTheme.typography.titleMedium) }
        }

        // The home-screen widget, for those who only want the price.
        Panel(accent = Good) {
            SectionLabel("Widget", Good)
            Spacer(Modifier.height(8.dp))
            Explain("Only the price? Put the BTC widget on your home screen: price and Poolsats with their " +
                "24h change, network hashrate, mining figures and a converter. No wallet needed.")
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.kilombino.pyblockwatch.R.drawable.widget_preview),
                contentDescription = "The BTC widget", contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.height(8.dp))
            val ctx = LocalContext.current
            val mgr = ctx.getSystemService(android.appwidget.AppWidgetManager::class.java)
            Button(
                onClick = { runCatching { mgr?.requestPinAppWidget(
                    android.content.ComponentName(ctx, com.kilombino.pyblockwatch.widget.XbtWidget::class.java), null, null) } },
                enabled = mgr?.isRequestPinAppWidgetSupported == true,
                colors = ButtonDefaults.buttonColors(containerColor = Good, contentColor = Ink),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
            ) { Text("＋ ADD THE WIDGET TO MY HOME SCREEN", style = MaterialTheme.typography.titleSmall) }
            if (mgr?.isRequestPinAppWidgetSupported != true)
                Explain("Your launcher does not let apps add widgets: long-press the home screen → Widgets → Kilowallet.")
        }

        Explain(
            "Either way, the same keys exist on both forks: the BLAKE2b chain and the classic " +
                "SHA-256 one share a genesis and an address system, but balances diverged at the " +
                "fork. You will see the two figures separately."
        )
    }
}

// ---------------------------------------------------------------- wallet

@Composable
private fun WalletScreen(state: UiState, vm: WalletViewModel, onToggleNotifications: (Boolean) -> Unit) {
    val chain = state.selected
    val cs = state.current
    // Tabs: BTC (the BLAKE2b chain) and ARK; the spamchain opens from settings.
    var arkTab by remember { mutableStateOf(false) }
    var cjTab by remember { mutableStateOf(false) }
    var lnTab by remember { mutableStateOf(false) }
    var arkPull by remember { mutableStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val openTab by com.kilombino.pyblockwatch.data.OpenTab.flow.collectAsState()
    LaunchedEffect(openTab) {
        val t = openTab ?: return@LaunchedEffect
        if (t == com.kilombino.pyblockwatch.data.OpenTab.UPDATE) return@LaunchedEffect  // the update dialog takes it
        com.kilombino.pyblockwatch.data.OpenTab.flow.value = null
        // Settings cover everything: a tab asked for (LEARN MORE in settings, a notification) has to show.
        showSettings = false
        when (t) {
            com.kilombino.pyblockwatch.data.OpenTab.COINJOIN -> {
                cjTab = true; arkTab = false; lnTab = false
                if (state.selected != Chain.BLAKE2B) vm.select(Chain.BLAKE2B)
                com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.requestRefresh()
            }
            com.kilombino.pyblockwatch.data.OpenTab.ARK -> { arkTab = true; cjTab = false; lnTab = false; arkPull++ }
            com.kilombino.pyblockwatch.data.OpenTab.LIGHTNING -> {
                lnTab = true; arkTab = false; cjTab = false
                if (state.selected != Chain.BLAKE2B) vm.select(Chain.BLAKE2B)
            }
            com.kilombino.pyblockwatch.data.OpenTab.SPAMCOIN -> {
                arkTab = false; cjTab = false
                if (state.selected != Chain.SHA256) vm.acceptSpamchain() else vm.refresh(Chain.SHA256)
            }
            else -> {
                arkTab = false; cjTab = false
                if (state.selected != Chain.BLAKE2B) vm.select(Chain.BLAKE2B)
                vm.refresh(Chain.BLAKE2B)
            }
        }
    }
    // Advanced always opens on BTC | ARK: the spamchain only shows when opened from settings.
    LaunchedEffect(Unit) { if (state.selected == Chain.SHA256) vm.select(Chain.BLAKE2B) }
    val accent by animateColorAsState(if (arkTab) ArkRed else Color(chain.accent), tween(400), label = "accent")
    var showSend by remember { mutableStateOf(false) }
    var showReceive by remember { mutableStateOf(false) }
    // BACK from a payment's pool sheet: SEND again, on BTC, with what was typed (SendSheet takes it).
    val backToSend by com.kilombino.pyblockwatch.coinjoin.CoinjoinPayment.backToSend.collectAsState()
    LaunchedEffect(backToSend) {
        if (backToSend == null) return@LaunchedEffect
        cjTab = false; arkTab = false; lnTab = false
        if (state.selected != Chain.BLAKE2B) vm.select(Chain.BLAKE2B)
        vm.resetSend(); showSend = true
    }

    // One person, one node: remind when the app comes to the screen, and on a manual refresh,
    // while BTC is read from our server; on the spamchain, warn about the public servers.
    var remindNode by remember { mutableStateOf(false) }
    var warnSpamRefresh by remember { mutableStateOf(false) }
    LaunchedEffect(AppVisible.value) {
        if (AppVisible.value && !vm.hasOwnNode(Chain.BLAKE2B) && !vm.nodeReminderShown) { vm.nodeReminderShown = true; remindNode = true }
    }
    if (remindNode) OwnNodeReminder(vm) { remindNode = false }
    if (warnSpamRefresh) SpamchainWarning(vm, onContinue = { warnSpamRefresh = false; vm.refresh(Chain.SHA256) },
        onBack = { warnSpamRefresh = false; vm.select(Chain.BLAKE2B) })

    // Pull down to refresh the selected chain, as in simple mode.
    var refreshing by remember { mutableStateOf(false) }
    val pullScope = rememberCoroutineScope()
    val doRefresh: () -> Unit = {
        pullScope.launch {
            refreshing = true
            // The node reminder belongs to the BTC tab only, not to ARK or COINJOIN.
            if (!arkTab && !cjTab) {
                if (chain == Chain.SHA256 && !vm.hasOwnNode(Chain.SHA256)) warnSpamRefresh = true
                else if (chain == Chain.BLAKE2B && !vm.hasOwnNode(Chain.BLAKE2B)) remindNode = true
            }
            if (cjTab) com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.requestRefresh()
            else if (arkTab) arkPull++ else if (!warnSpamRefresh) vm.refresh(chain)
            kotlinx.coroutines.delay(1_500)
            // On the BTC tabs the spinner stays until the refresh ends (a minute at most).
            if (!cjTab && !arkTab) {
                var waited = 0
                while (vm.isRefreshing(chain) && waited < 60) { kotlinx.coroutines.delay(1_000); waited++ }
            }
            refreshing = false
        }
    }
    // Settings open in place of the screen, from its top (not after everything else); back closes them.
    val scroll = rememberScrollState()
    LaunchedEffect(showSettings) { scroll.scrollTo(0) }
    androidx.activity.compose.BackHandler(enabled = showSettings) { showSettings = false }
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = doRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
    androidx.compose.runtime.CompositionLocalProvider(LocalScreenScroll provides scroll) {
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(28.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // The switch gets what is left once ⚙, ⏻ and the logo are placed (it never pushes them out).
            Box(Modifier.weight(1f)) { WalletModeSwitch(state, vm, accent) }
            TextButton(onClick = { vm.setUiMode("simple") }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                Text("simple", style = MaterialTheme.typography.bodySmall, color = TextSoft)
            }
            TextButton(onClick = { showSettings = !showSettings }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                Text(if (showSettings) "✕" else "⚙",
                     style = MaterialTheme.typography.titleMedium, color = TextSoft)
            }
            QuitButton(accent)
            Spacer(Modifier.width(4.dp))
            AppLogo(34)
        }

        // A watch-only wallet stays that: no Ark, no coinjoin (it cannot sign).
        if (!state.isHot && (arkTab || cjTab || lnTab)) { arkTab = false; cjTab = false; lnTab = false }
        if (showSettings) {
            // On the LIGHTNING tab, Lightning's own settings; on the others, the wallet's.
            if (lnTab) LightningScreen(vm, LnGold, true)
            else {
                SettingsPanel(state, vm, accent, onToggleNotifications) { vm.forget() }
                // The recovery words live at the very end of settings.
                if (state.isHot) HotWordsPanel(vm, accent)
            }
            Spacer(Modifier.height(30.dp))
            return@Column
        }
        AdvancedTabs(chain, arkTab, cjTab, lnTab, state.isHot,
            onBtc = { arkTab = false; cjTab = false; lnTab = false; if (chain != Chain.BLAKE2B) vm.select(Chain.BLAKE2B) },
            onArk = { arkTab = true; cjTab = false; lnTab = false; showSend = false; showReceive = false },
            onCoinjoin = { cjTab = true; arkTab = false; lnTab = false; showSend = false; showReceive = false; if (chain != Chain.BLAKE2B) vm.select(Chain.BLAKE2B) },
            onLn = { lnTab = true; arkTab = false; cjTab = false; showSend = false; showReceive = false; if (chain != Chain.BLAKE2B) vm.select(Chain.BLAKE2B) })

        if (lnTab) {
            LightningScreen(vm, LnGold, false)
            Spacer(Modifier.height(30.dp))
            return@Column
        }

        if (cjTab) {
            CoinjoinScreen(vm, CoinjoinBlue)
            Spacer(Modifier.height(30.dp))
            return@Column
        }

        if (arkTab) {
            if (com.kilombino.pyblockwatch.ark.Ark.available) ArkScreen(vm, ArkRed, arkPull) else ArkComingSoon(ArkRed)
            Spacer(Modifier.height(30.dp))
            return@Column
        }

        // How the look is going, right under the tabs and above the balance it explains.
        ScanStatus(cs, accent, onRetry = { vm.scan(chain) })

        BalanceCard(vm, chain, cs, accent, if (state.isHot && state.receiveTaproot && state.taprootXpub != null) ScriptType.P2TR else state.scriptType, state.nextRefreshAt, state.market, state.fiat, vm::setFiat)

        when {
            showSend -> SendSheet(vm, accent) { showSend = false }
            showReceive -> ReceiveSheet(vm, accent) { showReceive = false }
            else -> {
                // Receive works for any wallet; Send needs a seed, or (watch-only) a signer via PSBT.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showReceive = true },
                        colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = accent),
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f),
                    ) { Text("RECEIVE", style = MaterialTheme.typography.titleMedium) }
                    if (state.isHot || vm.psbtSupported()) {
                        Button(
                            onClick = { vm.resetSend(); showSend = true },
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                            shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f),
                        ) { Text("SEND", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        }

        // The coins (by address, with their confirmations) first, then the movements.
        if (cs.rows.isNotEmpty()) AddressList(cs.rows, accent, vm, chain, cs.height)

        if (cs.transactions.isNotEmpty()) MovementsCard(cs.transactions, accent, vm.explorerFor(chain), vm, state.isHot,
            if (chain == Chain.BLAKE2B) "sats" else "poolsats", balance = cs.total)

        if (cs.fingerprintChanged) {
            Panel(accent = Bad) {
                SectionLabel("The server certificate has CHANGED", Bad)
                Spacer(Modifier.height(8.dp))
                Explain(
                    "The fingerprint does not match the one we first saw. It may be a legitimate " +
                        "change (a renewed certificate) or someone in the middle. " +
                        "Do not accept it without checking through another channel."
                )
                Spacer(Modifier.height(6.dp))
                cs.fingerprint?.let {
                    SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall, color = Bad) }
                }
                TextButton(onClick = { vm.trustCurrentCertificate(chain) }) {
                    Text("I have checked the fingerprint: trust", color = Warn,
                         style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (!state.isHot) WatchAddressesCard(state, vm, chain, accent)

        Panel(accent = accent) {
            SectionLabel("How your coins are found", accent)
            Spacer(Modifier.height(8.dp))
            Explain(
                "Your xpub does not store a list of addresses: it generates them. The app derives " +
                    "m/0/0, m/0/1, m/0/2… and asks the server about each. When it finds " +
                    "${state.gapLimit} empty in a row, it assumes there are no more and stops. That is " +
                    "the «gap limit», and it is what the ring above draws while it scans."
            )
        }
        Spacer(Modifier.height(30.dp))
    }
    }
    }
}

/** BTC | LIGHTNING | COINJOIN | ARK, plus SPAMCOIN while the spamchain (opened from settings) is shown. */
@Composable
private fun AdvancedTabs(chain: Chain, arkTab: Boolean, cjTab: Boolean, lnTab: Boolean, hot: Boolean, onBtc: () -> Unit, onArk: () -> Unit, onCoinjoin: () -> Unit, onLn: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelSoft).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Each tab: its symbol over its name (a chain, lightning, a swirl, a boat).
        val tabs = buildList {
            add(Triple("⛓\nBTC", !arkTab && !cjTab && !lnTab && chain == Chain.BLAKE2B, Color(Chain.BLAKE2B.accent)) to onBtc)
            if (hot) {
                add(Triple("⚡\nLIGHTNING", lnTab, LnGold) to onLn)
                add(Triple("🌀\nCOINJOIN", cjTab, CoinjoinBlue) to onCoinjoin)
                add(Triple("⛵\nARK", arkTab, ArkRed) to onArk)
            }
            if (!arkTab && !cjTab && !lnTab && chain == Chain.SHA256) add(Triple("☣\nSPAMCOIN", true, Color(Chain.SHA256.accent)) to {})
        }
        tabs.forEach { (t, onClick) ->
            val (label, active, color) = t
            val (icon, name) = label.split("\n")
            val bg by animateColorAsState(if (active) color.copy(alpha = 0.18f) else Color.Transparent, tween(300), label = "tabbg")
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(bg)
                    .clickable(onClick = onClick).padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(icon, style = MaterialTheme.typography.titleMedium, color = if (active) color else TextFaint, maxLines = 1)
                Text(name, style = if (tabs.size > 3) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleSmall,
                    color = if (active) color else TextFaint, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun BalanceCard(
    vm: WalletViewModel, chain: Chain, cs: ChainState, accent: Color, scriptType: ScriptType?, nextRefreshAt: Long,
    market: com.kilombino.pyblockwatch.data.MarketData?, fiat: String, onFiat: (String) -> Unit,
) {
    // The countdown ticks here, only while this card is shown, instead of the whole screen
    // being redrawn every second.
    var secondsUntilRefresh by remember { mutableStateOf(0) }
    // Not in the background either: nobody sees it there.
    LaunchedEffect(nextRefreshAt, AppVisible.value) {
        if (!AppVisible.value) return@LaunchedEffect
        while (true) {
            secondsUntilRefresh = ((nextRefreshAt - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
            delay(1_000)
        }
    }
    Panel(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(if (chain == Chain.BLAKE2B) "BTC balance" else "Spamcoin balance", accent)
            Spacer(Modifier.weight(1f))
            StreetEye(TextSoft)
            // A visible countdown to the next auto-refresh, so the wallet reads as live.
            if (cs.phase is ScanPhase.Complete) {
                Text(refreshCountdown(nextRefreshAt, secondsUntilRefresh), style = MaterialTheme.typography.bodySmall,
                     color = TextFaint)
                Spacer(Modifier.width(8.dp))
                StillDot(Good, 7)
            }
        }
        Spacer(Modifier.height(6.dp))
        // Sats is the primary figure — an exact integer count, never rounded to bitcoin.
        if (StreetMode.hidden) Text(StreetMode.MASK, style = MaterialTheme.typography.headlineMedium, color = accent)
        else Row(verticalAlignment = Alignment.Bottom) {
            Text(groupSats(cs.total), style = MaterialTheme.typography.displayLarge, color = accent)
            Spacer(Modifier.width(8.dp))
            Text(if (chain == Chain.BLAKE2B) "sats" else "poolsats", style = MaterialTheme.typography.titleLarge, color = accent.copy(alpha = 0.7f))
        }
        Text(street("%.8f ₿".format(cs.total / 100_000_000.0)),
             style = MaterialTheme.typography.bodySmall, color = TextFaint)

        // Value in the chosen currency, the same choice as in simple mode and Ark.
        Spacer(Modifier.height(8.dp))
        val value = fiatPerCoin(chain, market, fiat)?.let { it * cs.total / 1e8 }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(street("≈ " + (value?.let { com.kilombino.pyblockwatch.data.Fiat.format(it, fiat) } ?: com.kilombino.pyblockwatch.data.Fiat.missing(fiat))),
                 style = MaterialTheme.typography.headlineSmall, color = TextMain, modifier = Modifier.weight(1f))
            FiatPicker(fiat, market, accent, onFiat)
        }
        if (cs.unconfirmed != 0L) {
            Spacer(Modifier.height(4.dp))
            Text(street("unconfirmed: ${groupSats(cs.unconfirmed)} sats"),
                 style = MaterialTheme.typography.bodySmall, color = Warn)
        }
        ImmatureBalanceNote(vm, chain, cs)
        Spacer(Modifier.height(10.dp))
        Text(
            "${cs.usedAddresses} used addresses · height ${cs.height}",
            style = MaterialTheme.typography.bodySmall, color = TextSoft,
        )
        scriptType?.let {
            Text("${it.label} — ${it.explain}",
                 style = MaterialTheme.typography.bodySmall, color = TextFaint)
        }
        Spacer(Modifier.height(6.dp))
        Explain(chain.blurb)
    }
}

@Composable
internal fun ScanStatus(cs: ChainState, accent: Color, onRetry: () -> Unit) {
    Panel(accent = accent) {
        AnimatedContent(
            targetState = cs.phase,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
            label = "phase",
        ) { phase ->
            when (phase) {
                is ScanPhase.Idle -> Text("idle", style = MaterialTheme.typography.bodySmall, color = TextFaint)

                is ScanPhase.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(accent); Spacer(Modifier.width(10.dp))
                    Column {
                        Text("connecting to ${phase.endpoint}",
                             style = MaterialTheme.typography.bodyMedium, color = accent)
                        Explain("The first connection can take a little while.")
                    }
                }

                is ScanPhase.Scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
                    GapRing(phase.gapUsed, phase.gapLimit, accent)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        DerivationTicker(phase.path, accent)
                        Text(
                            if (phase.chainIndex == 0) "receive chain" else "change chain",
                            style = MaterialTheme.typography.bodySmall, color = TextFaint,
                        )
                        Text("${phase.gapUsed}/${phase.gapLimit} empty in a row",
                             style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    }
                }

                is ScanPhase.Complete -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StillDot(if (cs.stale) Warn else Good); Spacer(Modifier.width(10.dp))
                        Text(if (cs.stale) (if (cs.refreshing != null) "last known · checking every address again" else "last known · not checked yet")
                             else "scan complete", style = MaterialTheme.typography.bodyMedium, color = if (cs.stale) Warn else Good)
                    }
                    cs.refreshing?.let {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PulseDot(accent); Spacer(Modifier.width(10.dp))
                            Text("refreshing · $it", style = MaterialTheme.typography.bodySmall, color = accent)
                        }
                    }
                    cs.server?.let {
                        Spacer(Modifier.height(6.dp))
                        Text("server: $it", style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    }
                    cs.fingerprint?.let {
                        Text("pinned fingerprint: ${it.take(16)}…",
                             style = MaterialTheme.typography.bodySmall, color = TextFaint)
                    }
                }

                is ScanPhase.Error -> Column {
                    Text("could not complete", style = MaterialTheme.typography.bodyMedium, color = Bad)
                    Spacer(Modifier.height(4.dp))
                    Explain(phase.message)
                    TextButton(onClick = onRetry) {
                        Text("retry", color = accent, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AddressList(rows: List<AddressRow>, accent: Color, vm: WalletViewModel, chain: Chain, tip: Int) {
    // Only the addresses that hold something now; spent-out ones are history, not coins.
    // In derivation order: account, receive before change, then index.
    val held = rows.filter { it.total > 0 }.sortedWith(compareBy({ it.type?.ordinal ?: -1 }, { it.chainIndex }, { it.index }))
    if (held.isEmpty()) return
    // The coins in each address, with their height, for the confirmations.
    var coins by remember { mutableStateOf<Map<String, List<Scanner.SpendableUtxo>>?>(null) }
    // Looked up again when a coin confirms (its total stays the same but moves from unconfirmed
    // to confirmed) and on each new block, so the confirmations keep pace with the movements.
    LaunchedEffect(held.map { Triple(it.scriptHash, it.total, it.confirmed) }, tip) {
        coins = runCatching { vm.addressCoins(chain, held) }.getOrNull()
    }
    var open by remember { mutableStateOf<AddressRow?>(null) }
    // Mined rewards still locked, marked on their coins (the balance card looks them up).
    val allImmature by vm.immature.collectAsState()
    val im = vm.immatureFor(chain, allImmature)
    fun conf(u: Scanner.SpendableUtxo) = if (u.height <= 0 || tip <= 0) 0 else tip - u.height + 1
    fun confText(u: Scanner.SpendableUtxo) = conf(u).let { c -> if (c == 0) "in mempool · 0 conf" else "$c conf" + if (c >= 6) "  ✓" else "" }
    open?.let { row ->
        val cs = coins?.get(row.scriptHash).orEmpty()
        TxDetailDialog(row.address, accent, vm.explorerFor(chain),
            status = "${row.path} · ${if (row.chainIndex == 0) "receive" else "change"} · ${street("${groupSats(row.total)} sats")}",
            onSpeedUp = null, onClose = { open = null }, vm = vm, chain = chain, kind = "address",
            extra = {
                val seatedHere = vm.seatedInWaitingPools()
                cs.forEach { u ->
                    Text(street("${groupSats(u.value)} sats") + " · " + confText(u) + " · ${u.txid.take(8)}…:${u.vout}",
                        style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    if (chain == com.kilombino.pyblockwatch.chain.Chain.BLAKE2B && vm.isMixedCoin(u))
                        Text("🌀 mixed", style = MaterialTheme.typography.bodySmall, color = Good)
                    if ("${u.txid}:${u.vout}" in seatedHere)
                        Text("in a pool waiting for people: sending it leaves that pool", style = MaterialTheme.typography.bodySmall, color = Warn)
                    im?.of(u)?.let { Text(immatureLine(im, it), style = MaterialTheme.typography.bodySmall, color = Warn) }
                }
            })
    }
    Panel(accent = accent) {
        SectionLabel("addresses with balance · confirmations", accent)
        Spacer(Modifier.height(4.dp))
        Text("tap an address to copy it or open it on the explorer", style = MaterialTheme.typography.bodySmall, color = TextFaint)
        Spacer(Modifier.height(6.dp))
        // Mixed by a coinjoin, or seated in a pool still waiting: marked as in coin control.
        val seated = remember(coins) { vm.seatedInWaitingPools() }
        val btc = chain == com.kilombino.pyblockwatch.chain.Chain.BLAKE2B
        held.forEach { row ->
            val cs = coins?.get(row.scriptHash)
            Row(Modifier.fillMaxWidth().clickable { open = row }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(shortAddress(row.address),
                         style = MaterialTheme.typography.bodyMedium, color = TextMain)
                    Text(
                        "${row.path}  ·  ${if (row.chainIndex == 0) "receive" else "change"}",
                        style = MaterialTheme.typography.bodySmall, color = TextFaint,
                    )
                    val mixed = btc && cs.orEmpty().any { vm.isMixedCoin(it) }
                    val inPool = cs.orEmpty().any { "${it.txid}:${it.vout}" in seated }
                    if (mixed) Text("🌀 mixed", style = MaterialTheme.typography.bodySmall, color = Good)
                    if (inPool) Text("in a pool waiting for people: sending it leaves that pool",
                        style = MaterialTheme.typography.bodySmall, color = Warn)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(street("${groupSats(row.total)} sats"),
                        style = MaterialTheme.typography.bodyMedium, color = accent, fontWeight = FontWeight.Bold)
                    when {
                        cs == null -> Text("…", style = MaterialTheme.typography.bodySmall, color = TextFaint)
                        // The least-confirmed coin is the one that decides whether it can be spent.
                        cs.isNotEmpty() -> cs.minByOrNull { conf(it) }!!.let { u ->
                            val c = conf(u)
                            Text(confText(u) + if (cs.size > 1) " (${cs.size} coins)" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (c == 0) Warn else if (c >= 6) Good else TextSoft)
                        }
                        else -> Text("not yet spendable (mined reward maturing)", style = MaterialTheme.typography.bodySmall, color = Warn)
                    }
                }
            }
            // With mined rewards still locked, each coin of the address in its own row,
            // spaced as in the coin list: its amount, when it unlocks, its confirmations.
            if (cs.orEmpty().any { im?.of(it) != null }) cs.orEmpty().sortedBy { it.height }.forEach { u ->
                val lk = im?.of(u)
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(street("${groupSats(u.value)} sats"), style = MaterialTheme.typography.bodyMedium, color = TextMain)
                        if (lk != null) Text(immatureLine(im!!, lk), style = MaterialTheme.typography.bodySmall, color = Warn)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(confText(u), style = MaterialTheme.typography.bodySmall, color = if (lk != null) Warn else Good)
                }
            }
        }
    }
}

@Composable
internal fun SettingsPanel(
    state: UiState,
    vm: WalletViewModel,
    accent: Color,
    onToggleNotifications: (Boolean) -> Unit,
    onForget: () -> Unit,
) {
    val chain = state.selected
    var host by remember(chain) { mutableStateOf(vm.endpointFor(chain).let { if (it.isCustom) it.host else "" }) }
    var port by remember(chain) { mutableStateOf(vm.endpointFor(chain).let { if (it.isCustom) it.port.toString() else "" }) }

    Panel(accent = accent) {
        SectionLabel("settings", accent)
        Spacer(Modifier.height(10.dp))

        // The app's language: the phone's by default, or one fixed here (each named in its own language).
        Text("Language", style = MaterialTheme.typography.bodyMedium, color = TextMain)
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val ctx = LocalContext.current
            val i18n = com.kilombino.pyblockwatch.ui.i18n.I18n
            FiatChip("Automatic", i18n.choice.value == i18n.AUTO, accent) { i18n.set(ctx, i18n.AUTO) }
            i18n.LANGUAGES.forEach { (code, name) ->
                FiatChip(name, i18n.choice.value == code, accent) { i18n.set(ctx, code) }
            }
        }
        Spacer(Modifier.height(10.dp))

        // The spamchain is not a tab in advanced mode: it opens from here.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Spamcoin (SHA-256 chain)", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                Explain("The same keys on the spamchain: balance, addresses, send and receive.")
            }
            var warnSpam by remember { mutableStateOf(false) }
            if (warnSpam) SpamchainWarning(vm, onContinue = { warnSpam = false; vm.acceptSpamchain() }, onBack = { warnSpam = false })
            TextButton(onClick = {
                if (chain == Chain.SHA256) vm.select(Chain.BLAKE2B)
                else if (vm.hasOwnNode(Chain.SHA256)) vm.acceptSpamchain() else warnSpam = true
            }) {
                Text(if (chain == Chain.SHA256) "BACK TO BTC" else "OPEN", color = accent, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Tell me when the balance changes",
                     style = MaterialTheme.typography.bodyMedium, color = TextMain)
                Explain(if (state.refreshBackgroundMinutes > 0)
                    "With the app closed, a service checks the addresses already found every " +
                    "${state.refreshBackgroundMinutes} minutes. No push server, no Google: your phone does the lookup itself."
                    else "Background checks are off: alerts only while the app is open.")
            }
            Switch(
                checked = state.notificationsEnabled,
                onCheckedChange = onToggleNotifications,
                colors = switchColors(accent),
            )
        }

        // How often to look: on screen, and with the app closed (off = no watcher, no
        // persistent notification, no battery while closed).
        Spacer(Modifier.height(10.dp))
        Text("Refresh", style = MaterialTheme.typography.bodyMedium, color = TextMain)
        Explain("While the app is open (by hand: pull the screen down)")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(30 to "30 s", 60 to "1 min", 300 to "5 min", 0 to "by hand").forEach { (v, l) ->
                FiatChip(l, state.refreshOpenSeconds == v, accent) { vm.setRefreshOpen(v) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Explain("In the background, with the app closed")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "off", 5 to "5", 10 to "10", 15 to "15", 30 to "30", 60 to "60").forEach { (v, l) ->
                FiatChip(l, state.refreshBackgroundMinutes == v, accent) { vm.setRefreshBackground(v) }
            }
            Text("min", style = MaterialTheme.typography.bodySmall, color = TextFaint, modifier = Modifier.align(Alignment.CenterVertically))
        }

        // Optional features (spending wallet only), and the full backup file.
        if (state.isHot) {
            val ctx = LocalContext.current
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ark", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                    Explain(if (com.kilombino.pyblockwatch.ark.Ark.hasWords(ctx)) "Active: instant payments and Lightning through Ark."
                        else "Off. Instant payments and Lightning through an Ark server: read how it works first.")
                }
                TextButton(onClick = { com.kilombino.pyblockwatch.data.OpenTab.flow.value = com.kilombino.pyblockwatch.data.OpenTab.ARK }) {
                    Text(if (com.kilombino.pyblockwatch.ark.Ark.hasWords(ctx)) "OPEN" else "LEARN MORE", color = accent,
                         style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(10.dp))
            var cjOn by remember { mutableStateOf(vm.coinjoinEnabled()) }
            var cjNotify by remember { mutableStateOf(vm.coinjoinNotify()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Coinjoin", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                    Explain(if (cjOn) "On. Mix your coins with other people's in pools." else "Off. Read how it works first.")
                }
                TextButton(onClick = {
                    if (cjOn) { vm.disableCoinjoin(); cjOn = false; cjNotify = false }
                    else com.kilombino.pyblockwatch.data.OpenTab.flow.value = com.kilombino.pyblockwatch.data.OpenTab.COINJOIN
                }) { Text(if (cjOn) "TURN OFF" else "LEARN MORE", color = accent, style = MaterialTheme.typography.bodySmall) }
            }
            if (cjOn) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Notify open coinjoin pools", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                    Explain(if (state.refreshBackgroundMinutes > 0) "Checked every ${state.refreshBackgroundMinutes} minutes with the balance watcher. Your own rounds always notify." else "Only while the app is open (background checks are off). Your own rounds always notify.")
                }
                val askNotif = rememberNotificationPermission()
                Switch(checked = cjNotify, onCheckedChange = { cjNotify = it; vm.setCoinjoinNotify(it); if (it) askNotif() },
                    colors = switchColors(accent))
            }
            if (cjOn) { Spacer(Modifier.height(10.dp)); CoinjoinAutoSetting(accent) }
        }

        Spacer(Modifier.height(14.dp))
        Text(if (state.isHot) "Address type for new addresses" else "Address type (derivation)",
             style = MaterialTheme.typography.bodyMedium, color = TextMain)
        if (state.isHot) {
            // The hot wallet has both accounts: this only picks which one new addresses come from.
            Explain("Native SegWit (bc1q) by default. With Taproot (bc1p), new receive and change addresses are " +
                "Taproot: its coins are cheaper to spend, so a send with several of them pays less. " +
                "Both kinds are always scanned and spent, so switching never hides a coin. " +
                "Coinjoin pools use only your bc1q coins.")
            Spacer(Modifier.height(4.dp))
            val activity = LocalContext.current as FragmentActivity
            HotTypeSelector(state.receiveTaproot, accent) { taproot ->
                if (taproot && vm.taprootNeedsUnlock()) vm.seedCipherOrToast()?.let { cipher ->
                    Biometric.authenticate(activity, "Add Taproot addresses", "Unlock to work out your Taproot account", cipher,
                        onSuccess = { authed -> vm.setReceiveTaproot(true, authed) { vm.toast(it) } }, onError = { vm.toast(it) })
                } else vm.setReceiveTaproot(taproot) { vm.toast(it) }
            }
        } else {
            Explain("How the keys are read from your xpub. BIP84 (bc1q) by default. " +
                "Change it if your wallet uses another format; both chains are rescanned.")
            Spacer(Modifier.height(4.dp))
            DerivationSelector(state.scriptType, accent) { vm.setScriptType(it) }
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Gap limit: ${state.gapLimit}",
                     style = MaterialTheme.typography.bodyMedium, color = TextMain)
                Explain("How many empty addresses in a row are checked before stopping. " +
                    "Raise it if you use many addresses; lower it to scan faster.")
            }
            TextButton(onClick = { vm.setGapLimit(state.gapLimit - 5) }) {
                Text("−5", color = accent, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { vm.setGapLimit(state.gapLimit + 5) }) {
                Text("+5", color = accent, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Day to day the wallet leaves out addresses that are spent and long confirmed; this asks
        // about every one of them again (it also happens on its own once a day).
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Full rescan", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                Explain("To go faster, refreshes skip addresses that are already spent and confirmed. " +
                    "If someone pays to an old address again, this finds it (it also runs by itself once a day).")
            }
            var started by remember { mutableStateOf(false) }
            TextButton(onClick = { vm.fullLook(); started = true }, enabled = !started) {
                Text(if (started) "RUNNING…" else "RESCAN", color = accent, style = MaterialTheme.typography.bodySmall)
            }
            LaunchedEffect(started) { if (started) { delay(5_000); while (vm.isRefreshing(chain)) delay(1_000); started = false } }
        }

        Spacer(Modifier.height(14.dp))
        // Node and explorer, folded: they matter once and take a lot of room.
        var nodeOpen by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().clickable { nodeOpen = !nodeOpen }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("${if (chain == Chain.BLAKE2B) "BTC" else "Spamcoin"} node & explorer", style = MaterialTheme.typography.bodyMedium, color = TextMain,
                modifier = Modifier.weight(1f))
            Text(if (nodeOpen) "▾" else "▸", color = accent, style = MaterialTheme.typography.titleMedium)
        }
        if (nodeOpen) Column {
            if (chain.allowsCustomNode) {
                Text("Your own ${if (chain == Chain.BLAKE2B) "BTC" else "Spamcoin"} node",
                     style = MaterialTheme.typography.bodyMedium, color = TextMain)
                Explain(if (chain == Chain.BLAKE2B) "One person, one node: put your own here (a .onion address works too, through the app's Tor). Leave empty to use " +
                        "${chain.defaultHost}:${chain.defaultPort}, meant for emergencies."
                    else "Leave empty to use the public servers (companies and institutions, they see your addresses).")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host, onValueChange = { host = it },
                        label = { Text("host", style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true, modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("port", style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                }
                TextButton(onClick = {
                    vm.setCustomNode(chain, host.ifBlank { null }, port.toIntOrNull() ?: chain.defaultPort)
                }) { Text("apply and rescan", color = accent, style = MaterialTheme.typography.bodySmall) }
                // Every Electrum server through Tor (a .onion always goes through it).
                val ctxTor = LocalContext.current
                var viaTor by remember { mutableStateOf(com.kilombino.pyblockwatch.chain.ElectrumClient.throughTor) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Connect to the server through Tor", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                        Explain(if (!com.kilombino.pyblockwatch.chain.Tor.available)
                            "Tor runs on ARM phones (arm64) only: on this one a .onion server can't be reached, and this stays off."
                            else "The server (yours, the default one, and the spamchain's public ones) never sees this phone's address. " +
                                "TLS and the pinned certificate stay as they are. Slower; if Tor doesn't start, nothing is sent without it.")
                    }
                    androidx.compose.material3.Switch(checked = viaTor, enabled = com.kilombino.pyblockwatch.chain.Tor.available, onCheckedChange = {
                        viaTor = it; com.kilombino.pyblockwatch.chain.Tor.setElectrumThroughTor(ctxTor, it); vm.scan(chain)
                    }, colors = switchColors(accent))
                }
                if (chain == Chain.BLAKE2B) { Spacer(Modifier.height(10.dp)); RpcNodePanel(vm, accent) }
            } else {
                Explain(
                    "The SHA-256 chain is lookup-only: it finds your coins from the xpub, so it " +
                        "offers no custom node."
                )
            }

            Spacer(Modifier.height(14.dp))
            // Block explorer used to open a movement. Any mempool.space-style site works.
            var explorer by remember(chain) { mutableStateOf(vm.explorerFor(chain)) }
            var explorerMsg by remember(chain) { mutableStateOf<String?>(null) }
            Text("${if (chain == Chain.BLAKE2B) "BTC" else "Spamcoin"} block explorer",
                 style = MaterialTheme.typography.bodyMedium, color = TextMain)
            Explain("Where a movement opens when you tap it. Default: ${vm.defaultExplorerFor(chain)}.")
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = explorer, onValueChange = { explorer = it.trim(); explorerMsg = null },
                label = { Text("https://…", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row {
                TextButton(onClick = {
                    val u = explorer.trimEnd('/')
                    if (u.startsWith("https://") || (u.startsWith("http://") && u.contains(".onion"))) {
                        vm.setExplorer(chain, u); explorerMsg = "saved"
                    } else {
                        explorerMsg = "use an https:// address (http:// only for .onion)"
                    }
                }) { Text("save", color = accent, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = {
                    vm.setExplorer(chain, null); explorer = vm.defaultExplorerFor(chain); explorerMsg = "back to default"
                }) { Text("default", color = TextSoft, style = MaterialTheme.typography.bodySmall) }
            }
            explorerMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextFaint) }
        }

        Spacer(Modifier.height(10.dp))
        var rescueOpen by remember { mutableStateOf(false) }
        TextButton(onClick = { rescueOpen = !rescueOpen }) {
            Text((if (rescueOpen) "▾" else "▸") + " Rescue BTC from a pre-fork Lightning (LND) seed",
                 color = TextSoft, style = MaterialTheme.typography.bodySmall)
        }
        if (rescueOpen) LndRescuePanel(vm, accent)

        Spacer(Modifier.height(10.dp))
        SelectionContainer {
            Text(
                state.xpub?.let { "${it.take(24)}…${it.takeLast(10)}" } ?: "",
                style = MaterialTheme.typography.bodySmall, color = TextFaint,
            )
        }
        // The extended public key, behind the fingerprint: QR and text, a tap copies it.
        run {
            val activity = LocalContext.current as FragmentActivity
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            var showXpub by remember { mutableStateOf(false) }
            var copied by remember { mutableStateOf(false) }
            val key = state.xpub
            if (key != null) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = {
                    if (showXpub) showXpub = false
                    else Biometric.confirm(activity, "Show the public key", "It reveals every address of this wallet",
                        onSuccess = { showXpub = true }, onError = {})
                }) { Text(if (showXpub) "hide ${key.take(4)}" else "SHOW ${key.take(4).uppercase()} (QR)", color = accent,
                        style = MaterialTheme.typography.bodySmall) }
                if (showXpub) {
                    Explain("Anyone with it sees every address and payment of this wallet (but cannot spend). Share it only " +
                        "with software you trust, e.g. to watch this wallet from another phone.")
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrImage(key, 240) }
                    Text(key, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextMain,
                        modifier = Modifier.clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(key)); copied = true })
                    Text(if (copied) "copied ✓" else "tap the key to copy it", style = MaterialTheme.typography.bodySmall,
                        color = if (copied) Good else TextFaint)
                }
            }
            Spacer(Modifier.height(10.dp))
            var updates by remember { mutableStateOf(activity.getSharedPreferences("pyblockwatch", android.content.Context.MODE_PRIVATE).getBoolean("check_updates", true)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Check for new versions", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                    Explain("When the app opens it asks GitHub whether a newer Kilowallet is out (GitHub sees the request).")
                }
                Switch(checked = updates, onCheckedChange = {
                    updates = it
                    activity.getSharedPreferences("pyblockwatch", android.content.Context.MODE_PRIVATE).edit().putBoolean("check_updates", it).apply()
                }, colors = switchColors(accent))
            }
            // Asks now, even about a version once answered NOT NOW, and with the switch off.
            TextButton(onClick = {
                android.widget.Toast.makeText(activity, com.kilombino.pyblockwatch.ui.i18n.I18n.tr("Checking GitHub for a newer Kilowallet…"), android.widget.Toast.LENGTH_SHORT).show()
                com.kilombino.pyblockwatch.data.OpenTab.checkNow = true
                com.kilombino.pyblockwatch.data.OpenTab.flow.value = com.kilombino.pyblockwatch.data.OpenTab.UPDATE
            }) { Text("CHECK NOW", color = accent, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Street mode", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                    Explain("On: balances and amounts are hidden every time the app opens, until you unlock them " +
                        "with the eye. Off: they always show (turning it off asks for your fingerprint).")
                }
                Switch(checked = StreetMode.byDefault, onCheckedChange = { StreetMode.setByDefault(activity, it) },
                    colors = switchColors(accent))
            }
            com.kilombino.pyblockwatch.data.CrashReport.last(activity)?.let { report ->
                val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                val crashScope = rememberCoroutineScope()
                TextButton(onClick = { crashScope.launch {
                    val fresh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { com.kilombino.pyblockwatch.data.CrashReport.latest(activity) }.getOrNull() } ?: report
                    clip.setText(androidx.compose.ui.text.AnnotatedString(com.kilombino.pyblockwatch.data.CrashReport.redact(fresh)))
                    android.widget.Toast.makeText(activity, com.kilombino.pyblockwatch.ui.i18n.I18n.tr("Crash report copied"), android.widget.Toast.LENGTH_SHORT).show()
                } }) {
                    Text("COPY THE LAST CRASH REPORT", color = accent, style = MaterialTheme.typography.bodySmall)
                }
            }
            // What protects you right now, for checking: the native crypto and the verified headers.
            val secp = com.kilombino.pyblockwatch.crypto.NativeSecp
            val spvTip = com.kilombino.pyblockwatch.chain.HeaderChain.tip
            Text("Crypto: " + (if (secp.ecdhNative) "libsecp256k1 (native, constant time) ✓"
                    else if (secp.available) "libsecp256k1 for signing, Kotlin for ECDH" else "Kotlin (no native library on this CPU)") +
                "\nSPV: " + (if (spvTip > com.kilombino.pyblockwatch.chain.HeaderChain.CHECKPOINT_HEIGHT) "BTC headers verified up to block $spvTip ✓"
                    else "BTC headers not verified yet (after the next look)"),
                style = MaterialTheme.typography.bodySmall, color = TextFaint)
        }
        // The backup file sits next to switching and forgetting, where it is needed first.
        if (state.isHot) { Spacer(Modifier.height(14.dp)); FullBackupPanel(vm, accent) }
        Spacer(Modifier.height(6.dp))
        // Another hot wallet takes this one's place on the phone: asked first, like forgetting it.
        var askSwitch by remember { mutableStateOf(false) }
        var switchWords by remember { mutableStateOf(false) }
        var switchBlock by remember { mutableStateOf<String?>(null) }
        Button(
            onClick = { if (state.hasSeed) { switchWords = false; switchBlock = vm.walletBoundBlock(); askSwitch = true } else vm.startSetup() },
            colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = accent),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.isHot) "SWITCH / NEW WALLET" else "CREATE A DIFFERENT HOT WALLET",
                 style = MaterialTheme.typography.titleMedium) }
        if (askSwitch) AlertDialog(
            onDismissRequest = { askSwitch = false },
            title = { Text("Switch to another wallet?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    switchBlock?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Bad) }
                    Text("Creating or restoring another spending wallet REPLACES this one's seed on this phone. The coins stay " +
                        "on-chain, but the only way back to them is your recovery words (and passphrase, if it has one). " +
                        "Without them, the money is lost. A watch-only wallet next to it changes nothing.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Before that: have your recovery words written on paper, the safest backup there is. If you use Ark, also " +
                            "save a backup file (settings → Backup file): it keeps your Ark wallet, its on-chain coins and emergency " +
                            "exits, nodes, contacts and coinjoin rounds. The file is a help, not a replacement for the words on paper. " +
                            "This wallet's Lightning node and Ark wallet are removed from this phone too.", style = MaterialTheme.typography.bodySmall, color = Warn)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { switchWords = !switchWords }) {
                        Text(if (switchWords) "☑" else "☐", color = Bad, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Text("I have my recovery words written down", style = MaterialTheme.typography.bodySmall, color = TextMain)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = switchWords && switchBlock == null, onClick = { askSwitch = false; vm.wipeWalletBound(); vm.startSetup() }) {
                    Text("GO ON", color = if (switchWords && switchBlock == null) Bad else TextFaint)
                }
            },
            dismissButton = { TextButton(onClick = { askSwitch = false }) { Text("STAY", color = TextSoft) } },
            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
        )
        // Forgetting a hot wallet deletes its seed from this phone: never in one tap.
        val forgetActivity = LocalContext.current as androidx.fragment.app.FragmentActivity
        var askForget by remember { mutableStateOf(false) }
        var haveWords by remember { mutableStateOf(false) }
        var forgetBlock by remember { mutableStateOf<String?>(null) }
        TextButton(onClick = { haveWords = false; forgetBlock = if (state.isHot) vm.walletBoundBlock() else null; askForget = true }) {
            Text("forget this wallet", color = Bad, style = MaterialTheme.typography.bodySmall)
        }
        if (askForget) AlertDialog(
            onDismissRequest = { askForget = false },
            title = { Text("Forget this wallet?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    forgetBlock?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Bad) }
                    Text(if (state.isHot)
                        "This DELETES the wallet's seed from this phone. The coins stay on-chain, but the only way back to " +
                            "them is your recovery words (and passphrase, if it has one). Without them, the money is lost."
                    else "This removes the watch-only wallet from this phone. Nothing on-chain changes; you can add the xpub again.",
                        style = MaterialTheme.typography.bodySmall)
                    if (state.isHot) Text("Before that: have your recovery words written on paper, the safest backup there is. If you use Ark, also " +
                            "save a backup file (settings → Backup file): it keeps your Ark wallet, its on-chain coins and emergency " +
                            "exits, nodes, contacts and coinjoin rounds. The file is a help, not a replacement for the words on paper. " +
                            "This wallet's Lightning node and Ark wallet are removed from this phone too.", style = MaterialTheme.typography.bodySmall, color = Warn)
                    if (state.isHot) Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { haveWords = !haveWords }) {
                        Text(if (haveWords) "☑" else "☐", color = Bad, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Text("I have my recovery words written down", style = MaterialTheme.typography.bodySmall, color = TextMain)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = (!state.isHot || haveWords) && forgetBlock == null, onClick = {
                    askForget = false
                    if (state.isHot) Biometric.confirm(forgetActivity, "Forget this wallet", "Its seed is deleted from this phone",
                        onSuccess = onForget, onError = { })
                    else onForget()
                }) { Text("FORGET", color = if ((!state.isHot || haveWords) && forgetBlock == null) Bad else TextFaint) }
            },
            dismissButton = { TextButton(onClick = { askForget = false }) { Text("KEEP IT", color = TextSoft) } },
            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
        )
    }
}

/** Hot wallet: native SegWit (BIP-84) or Taproot (BIP-86) for the addresses it hands out next. */
@Composable
private fun HotTypeSelector(taproot: Boolean, accent: Color, onSelect: (Boolean) -> Unit) {
    Column {
        listOf(false to "BIP84 · Native SegWit (bc1q)  (m/84'/0'/0')", true to "BIP86 · Taproot (bc1p)  (m/86'/0'/0')").forEach { (t, label) ->
            val sel = t == taproot
            TextButton(onClick = { if (!sel) onSelect(t) }, modifier = Modifier.fillMaxWidth()) {
                Text((if (sel) "● " else "○ ") + label, color = if (sel) accent else TextMain,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DerivationSelector(current: ScriptType?, accent: Color, onSelect: (ScriptType) -> Unit) {
    fun purpose(t: ScriptType) = when (t) {
        ScriptType.P2PKH -> 44; ScriptType.P2SH_P2WPKH -> 49
        ScriptType.P2WPKH -> 84; ScriptType.P2TR -> 86
    }
    Column {
        ScriptType.entries.forEach { t ->
            val sel = t == current
            TextButton(onClick = { onSelect(t) }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    (if (sel) "● " else "○ ") + "BIP${purpose(t)} · ${t.label}  (m/${purpose(t)}'/0'/0')",
                    color = if (sel) accent else TextMain,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun MovementsCard(txs: List<TxConf>, accent: Color, explorer: String, vm: WalletViewModel, isHot: Boolean, unit: String = "sats",
                           balance: Long? = null) {
    val chain = if (unit == "sats") Chain.BLAKE2B else Chain.SHA256
    // Tapping a movement asks before leaving the app: opening it reveals the txid (and so
    // which addresses are yours) to whoever runs that explorer.
    var asking by remember { mutableStateOf<TxConf?>(null) }
    // A stuck send is replaced (RBF) or cancelled (RBF back to us); a stuck payment TO this wallet is pulled along (CPFP).
    var bumping by remember { mutableStateOf<Pair<String, BumpMode>?>(null) }
    bumping?.let { (txid, mode) -> BumpDialog(vm, txid, accent, explorer, mode) { bumping = null; vm.resetSend() } }
    val site = explorer.removePrefix("https://").removePrefix("http://")
    asking?.let { t ->
        TxDetailDialog(t.txid, accent, explorer,
            status = if (t.pending) "in mempool · 0 confirmations" else "${t.confirmations} confirmation" + if (t.confirmations == 1) "" else "s",
            onSpeedUp = if (t.pending && isHot) ({ asking = null; bumping = t.txid to (if ((t.amount ?: 0) > 0) BumpMode.CPFP else BumpMode.RBF) }) else null,
            speedUpLabel = if ((t.amount ?: 0) > 0) "⚡ SPEED UP (CPFP)" else "⚡ SPEED UP (RBF)",
            // Only a send of ours can be cancelled: a payment to us isn't ours to take back.
            onCancel = if (t.pending && isHot && (t.amount ?: 0) <= 0) ({ asking = null; bumping = t.txid to BumpMode.CANCEL }) else null,
            onClose = { asking = null }, vm = vm, chain = chain, pending = t.pending)
    }
    Panel(accent = accent) {
        SectionLabel("movements · confirmations", accent)
        Spacer(Modifier.height(8.dp))
        Text("tap a movement for its details: copy the txid or open it on $site", style = MaterialTheme.typography.bodySmall, color = TextFaint)
        Spacer(Modifier.height(4.dp))
        // What was left after each movement: today's balance (mempool included, as at the top),
        // walking back one movement at a time. Unknown from the first movement without an amount.
        val after = HashMap<String, Long>()
        if (balance != null) {
            var run: Long? = balance
            for (t in txs) { val r = run ?: break; after[t.txid] = r; run = t.amount?.let { r - it } }
        }
        txs.take(15).forEach { t ->
            Row(
                Modifier.fillMaxWidth().clickable { asking = t }.padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    // What it did to the wallet: + received, − sent (fee included).
                    t.amount?.let { a ->
                        Text(street((if (a >= 0) "+" else "−") + groupSats(kotlin.math.abs(a)) + " $unit"),
                             style = MaterialTheme.typography.bodyMedium, color = if (a >= 0) Good else TextMain)
                    }
                    // SPV: only a transaction the server could not prove into a verified block is marked.
                    if (t.spv == false) Text("⚠ not proven: the server's proof failed. Don't count on it.",
                        style = MaterialTheme.typography.bodySmall, color = Bad)
                    after[t.txid]?.let { left ->
                        Text(street("(left: ${groupSats(left)} $unit)"), style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    }
                    Text(
                        "${t.txid.take(8)}…${t.txid.takeLast(6)}",
                        style = MaterialTheme.typography.bodySmall, color = TextFaint,
                    )
                }
                if (t.pending) {
                    // Status above, actions below: side by side they squeezed the amount column.
                    Column(horizontalAlignment = Alignment.End) {
                        Text("in mempool · 0 conf",
                             style = MaterialTheme.typography.bodySmall, color = Warn)
                        if (isHot) Row {
                            TextButton(onClick = { bumping = t.txid to (if ((t.amount ?: 0) > 0) BumpMode.CPFP else BumpMode.RBF) }) {
                                Text("⚡ speed up", style = MaterialTheme.typography.bodySmall, color = accent)
                            }
                            if ((t.amount ?: 0) <= 0) TextButton(onClick = { bumping = t.txid to BumpMode.CANCEL }) {
                                Text("✕ cancel", style = MaterialTheme.typography.bodySmall, color = Warn)
                            }
                        }
                    }
                } else {
                    Text(
                        "${t.confirmations} conf" + if (t.confirmations >= 6) "  ✓" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (t.confirmations >= 6) Good else TextSoft,
                    )
                }
            }
        }
        if (txs.size > 15) {
            Text("… and ${txs.size - 15} more",
                 style = MaterialTheme.typography.bodySmall, color = TextFaint)
        }
    }
}

/** What [BumpDialog] does with an unconfirmed transaction. */
internal enum class BumpMode { RBF, CPFP, CANCEL }

/**
 * Speed up an unconfirmed send by replacing it (RBF) with the same payment at a higher fee,
 * paid from its change, or cancel it (RBF back to a fresh address of this wallet). Only sends
 * from this wallet qualify; anything else explains why it can't be replaced.
 */
@Composable
private fun BumpDialog(vm: WalletViewModel, txid: String, accent: Color, explorer: String, mode: BumpMode, onClose: () -> Unit) {
    val cpfp = mode == BumpMode.CPFP
    val cancel = mode == BumpMode.CANCEL
    FlowOpenWhileShown()
    val sent = (vm.state.collectAsState().value.sendPhase as? SendPhase.Sent)?.txid
    if (sent != null) {
        // With the wallet and its chain, so an explorer already chosen opens straight away.
        TxDetailDialog(sent, accent, explorer, status = (if (cpfp) "speed-up sent ✓" else if (cancel) "cancellation sent ✓" else "replacement sent ✓") + " · in mempool",
            onSpeedUp = null, onClose = onClose, vm = vm, chain = vm.state.collectAsState().value.selected, pending = true)
        return
    }
    val state by vm.state.collectAsState()
    val activity = LocalContext.current as androidx.fragment.app.FragmentActivity
    var rate by remember { mutableStateOf("3") }
    val reviewed = (state.sendPhase as? SendPhase.Review)?.draft
    val feeConcern = remember(reviewed) { reviewed?.let { vm.feeConcern(it) } }
    var feeOk by remember(reviewed) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (cancel) "Cancel ${txid.take(8)}…" else "Speed up ${txid.take(8)}…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (val p = state.sendPhase) {
                    is SendPhase.Review -> {
                        val d = p.draft
                        if (d.cancels) {
                            Text("Replaces the payment with one that sends everything back to a fresh address of yours. " +
                                "The recipients get nothing, unless the original confirms first: then this one is refused.",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Back to you: ${shortAddress(d.toAddress)} · ${groupSats(d.amount)} sats", style = MaterialTheme.typography.bodySmall)
                            Text("Fee: ${groupSats(d.replacedFee)} → ${groupSats(d.fee)} sats", style = MaterialTheme.typography.bodySmall, color = accent)
                        } else if (d.cpfpParent != null) {
                            Text("Pulls the stuck payment along: a payment to yourself that spends what it brought you, " +
                                "with a fee big enough for both. Miners take the two together.", style = MaterialTheme.typography.bodySmall)
                            Text("Back to you: ${groupSats(d.amount)} sats", style = MaterialTheme.typography.bodySmall)
                            Text("Fee: ${groupSats(d.fee)} sats · both together ${"%.1f".format(java.util.Locale.ROOT, d.packageRate)} sat/vB",
                                 style = MaterialTheme.typography.bodySmall, color = accent)
                        } else {
                            Text("Replaces the stuck send with the same payment and a higher fee, taken from your change.",
                                 style = MaterialTheme.typography.bodySmall)
                            Text("To: ${shortAddress(d.toAddress)} · ${groupSats(d.amount)} sats", style = MaterialTheme.typography.bodySmall)
                            Text("Fee: ${groupSats(d.replacedFee)} → ${groupSats(d.fee)} sats", style = MaterialTheme.typography.bodySmall, color = accent)
                            Text("Change: ${groupSats(d.change)} sats", style = MaterialTheme.typography.bodySmall)
                        }
                        // A payment that makes a perfect coin: say what happens to its pool.
                        if (com.kilombino.pyblockwatch.coinjoin.PerfectCoin.waiting.collectAsState().value.any { it.txid == txid })
                            Text(if (d.cancels) "This payment makes a perfect coin for a coinjoin pool. Cancelling it drops the coin, " +
                                "and the pool it was going to open or join with it." else "This payment makes a perfect coin for a coinjoin pool. Speeding it up keeps it: the pool follows the " +
                                "new transaction and opens (or joins) once it confirms.", style = MaterialTheme.typography.bodySmall, color = Warn)
                        feeConcern?.let { FeeGate(it, feeOk) { feeOk = !feeOk } }
                    }
                    is SendPhase.Sent -> Text((if (cpfp) "Speed-up sent ✓\n" else if (cancel) "Cancellation sent ✓\n" else "Replacement sent ✓\n") + p.txid,
                        style = MaterialTheme.typography.bodySmall, color = Good)
                    SendPhase.Preparing, SendPhase.Broadcasting -> Text("working…", style = MaterialTheme.typography.bodySmall, color = accent)
                    else -> {
                        if (p is SendPhase.Failed) Text(p.message, style = MaterialTheme.typography.bodySmall, color = Bad)
                        Text(if (cpfp) "Fee rate for the stuck payment and the speed-up together. Most pools only mine from 1 sat/vB." else
                            if (cancel) "Fee rate for the cancellation. It must beat the old fee by at least 1 sat/vB of its size, " +
                            "and the higher it is, the likelier it gets mined before the original." else
                            "New fee rate. It must beat the old fee by at least 1 sat/vB of the transaction's size; " +
                            "most pools only mine from 1 sat/vB.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = rate, onValueChange = { rate = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("sat/vB", style = MaterialTheme.typography.bodySmall) }, singleLine = true)
                    }
                }
            }
        },
        confirmButton = {
            when (state.sendPhase) {
                is SendPhase.Review -> TextButton(enabled = feeConcern == null || feeOk, onClick = {
                    vm.seedCipherOrToast()?.let { cipher ->
                        Biometric.authenticate(activity, if (cancel) "Cancel payment" else "Speed up payment",
                            if (cancel) "Unlock to sign the cancellation" else "Unlock to sign the speed-up", cipher,
                            onSuccess = { authed -> vm.confirmSend(authed) }, onError = { vm.toast(it) })
                    }
                }) { Text("CONFIRM & SIGN", color = accent) }
                is SendPhase.Sent -> TextButton(onClick = onClose) { Text("DONE", color = accent) }
                SendPhase.Preparing, SendPhase.Broadcasting -> {}
                else -> TextButton(onClick = {
                    when (mode) {
                        BumpMode.CPFP -> vm.prepareCpfp(txid, rate.toDoubleOrNull() ?: 3.0)
                        BumpMode.CANCEL -> vm.prepareCancel(txid, rate.toDoubleOrNull() ?: 3.0)
                        BumpMode.RBF -> vm.prepareBump(txid, rate.toDoubleOrNull() ?: 3.0)
                    }
                }) {
                    Text("REVIEW", color = accent)
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("CLOSE", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )
}

/**
 * A transaction's details: the full txid to copy with one tap, and opening it on the block
 * explorer after a warning (the site learns which transaction, and so which addresses, you
 * looked up).
 */
@Composable
internal fun TxDetailDialog(
    txid: String, accent: Color, explorer: String, status: String,
    onSpeedUp: (() -> Unit)?, onClose: () -> Unit,
    vm: WalletViewModel? = null, chain: Chain = Chain.BLAKE2B, speedUpLabel: String = "⚡ SPEED UP (RBF)",
    /** Cancel an unconfirmed send of ours (RBF back to the wallet); null hides it. */
    onCancel: (() -> Unit)? = null,
    /** "tx" for a transaction, "address" for one of the wallet's addresses (the explorer path). */
    kind: String = "tx",
    extra: (@Composable () -> Unit)? = null,
    /** An unconfirmed transaction: offers copying it raw, to broadcast it again elsewhere. */
    pending: Boolean = false,
) {
    // The first time, ask which explorer to use (and warn it is an outside site); once the user
    // keeps one, it opens straight away.
    // Empty until one is chosen: the point is that people put their own.
    var own by remember { mutableStateOf(if (vm?.explorerChosen(chain) == true) explorer else "") }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val site = explorer.removePrefix("https://").removePrefix("http://")
    var copied by remember { mutableStateOf(false) }
    var confirmOpen by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (kind == "address") "Address" else "Transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = accent)
                extra?.invoke()
                SelectionContainer {
                    Text(txid, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextMain)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(txid)); copied = true
                    }) { Text(if (copied) "COPIED ✓" else if (kind == "address") "📋 COPY ADDRESS" else "📋 COPY TXID", color = accent) }
                    TextButton(onClick = {
                        if (vm != null && vm.explorerChosen(chain)) runCatching { uri.openUri("$explorer/$kind/$txid") }
                        else confirmOpen = true
                    }) { Text("🔎 EXPLORER", color = accent) }
                }
                if (pending && kind == "tx" && vm != null) {
                    val scope = rememberCoroutineScope()
                    var raw by remember(txid) { mutableStateOf<String?>(null) }   // null idle, "" fetching, "!…" failed
                    TextButton(enabled = raw != "", onClick = {
                        raw = ""
                        scope.launch {
                            raw = runCatching { vm.rawTransaction(chain, txid) }
                                .onSuccess { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) }
                                .fold({ it }, { e -> "!" + (e.message ?: e.javaClass.simpleName) })
                        }
                    }) {
                        Text(when {
                            raw == "" -> "COPYING…"
                            raw?.startsWith("!") == false -> "RAW TX COPIED ✓"
                            else -> "📋 COPY RAW TX"
                        }, color = accent)
                    }
                    raw?.let { r ->
                        if (r.startsWith("!")) Text("Couldn't get it: ${r.drop(1)}", style = MaterialTheme.typography.bodySmall, color = Warn)
                        else if (r.isNotEmpty()) Text("Paste it into your node (sendrawtransaction) or any broadcast page to send it again.",
                            style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    }
                }
                if (confirmOpen) {
                    Text("This opens an EXTERNAL block explorer: the site sees which ${if (kind == "address") "address" else "transaction"} you look up " +
                        "(and so which addresses are yours). Use your own if you run one; it is saved and " +
                        "this will not be asked again. You can change it in settings.",
                         style = MaterialTheme.typography.bodySmall, color = Warn)
                    OutlinedTextField(value = own, onValueChange = { own = it.trim() },
                        label = { Text("your explorer (https://…)", style = MaterialTheme.typography.bodySmall) },
                        placeholder = { Text("https://mempool.example.com", style = MaterialTheme.typography.bodySmall, color = TextFaint) },
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true)
                    val u = own.trimEnd('/')
                    val valid = u.startsWith("https://") || (u.startsWith("http://") && u.contains(".onion"))
                    TextButton(enabled = valid, onClick = {
                        vm?.setExplorer(chain, u)
                        runCatching { uri.openUri("$u/$kind/$txid") }; confirmOpen = false
                    }) { Text("SAVE AND OPEN", color = if (valid) accent else TextFaint) }
                    vm?.let { v ->
                        val kilombino = v.defaultExplorerFor(chain)
                        TextButton(onClick = {
                            v.setExplorer(chain, kilombino)
                            runCatching { uri.openUri("$kilombino/$kind/$txid") }; confirmOpen = false
                        }) { Text("USE KILOMBINO'S (${kilombino.removePrefix("https://")})", color = TextSoft) }
                    }
                }
                onSpeedUp?.let {
                    TextButton(onClick = it) { Text(speedUpLabel, color = accent) }
                }
                onCancel?.let {
                    TextButton(onClick = it) { Text("✕ CANCEL (RBF)", color = Warn) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("CLOSE", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )
}


/** A command to paste in a terminal: shown as it is (selectable too) with a button that copies it. */
@Composable
private fun CommandToCopy(command: String) {
    val copier = rememberCopier()
    Column(Modifier.fillMaxWidth().background(Ink, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(10.dp)) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            androidx.compose.material3.Text(command, style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = TextMain)
        }
        TextButton(onClick = { copier.copy(command) }, modifier = Modifier.align(Alignment.End)) {
            Text(if (copier.copied) "COPIED ✓" else "COPY", color = if (copier.copied) Good else Purple)
        }
    }
}
