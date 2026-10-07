package com.kilombino.pyblockwatch.ui

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
import androidx.compose.material3.Text
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
        com.kilombino.pyblockwatch.data.OpenTab.from(intent)
        // A coinjoin round left running (app killed, updated, phone restarted) picks up again.
        if (com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.hasActive(this))
            com.kilombino.pyblockwatch.coinjoin.CoinjoinService.start(this)
        enableEdgeToEdge()
        setContent {
            PyBlockWatchTheme {
                val vm: WalletViewModel = viewModel()
                val state by vm.state.collectAsState()
                // Notifications are on by default: start the watcher (and ask for the
                // POST_NOTIFICATIONS permission on Android 13+) as soon as there is a wallet.
                LaunchedEffect(state.hasWallet, state.notificationsEnabled) {
                    if (state.hasWallet && state.notificationsEnabled) toggleNotifications(vm, true)
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
                    LaunchedEffect(openTab) {
                        if (openTab == com.kilombino.pyblockwatch.data.OpenTab.UPDATE) {
                            com.kilombino.pyblockwatch.data.OpenTab.flow.value = null; forceAsk++
                        }
                    }
                    LaunchedEffect(forceAsk) {
                        val prefs = getSharedPreferences("pyblockwatch", MODE_PRIVATE)
                        while (true) {
                            if (prefs.getBoolean("check_updates", true)) {
                                val current = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
                                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.kilombino.pyblockwatch.data.UpdateCheck.check(current)
                                }
                                if (r != null && (forceAsk > 0 || prefs.getString("update_dismissed", null) != r.version)) update = r
                            }
                            kotlinx.coroutines.delay(5 * 60 * 1000L)
                        }
                    }
                    val upd by com.kilombino.pyblockwatch.data.AppUpdater.state.collectAsState()
                    val uri = androidx.compose.ui.platform.LocalUriHandler.current
                    update?.let { r ->
                        if (upd is com.kilombino.pyblockwatch.data.AppUpdater.State.Idle) AlertDialog(
                            onDismissRequest = { update = null },
                            title = { Text("Kilowallet ${r.version} is out") },
                            text = { Text("Update now: it downloads here, is checked against the published hash and this " +
                                "app's certificate, and installs over this one keeping everything." +
                                (if (r.apkUrl == null) "\n\nThis release has no APK attached: open its page instead." else ""),
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
                                        is AppUpdater.State.Installing -> Text("Installing… Android may ask you to confirm; the app restarts " +
                                            "on its own when it is done.", style = MaterialTheme.typography.bodySmall)
                                        is AppUpdater.State.Failed -> Text(st.message, style = MaterialTheme.typography.bodySmall, color = Bad)
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
                                    is AppUpdater.State.Failed -> Row {
                                        update?.let { r -> TextButton(onClick = { runCatching { uri.openUri(r.url) } }) { Text("GITHUB", color = TextSoft) } }
                                        TextButton(onClick = { AppUpdater.reset(); update = null }) { Text("CLOSE", color = Purple) }
                                    }
                                    else -> {}
                                }
                            },
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
                                onDecline = { vm.answerCoinjoin(false); askCoinjoin = false })
                        }
                    }
                    if (state.showWallet) {
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
        if (enable) ContextCompat.startForegroundService(this, intent) else stopService(intent)
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
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
        Text("BITCOIN BLAKE2b (XBT) WALLET", style = MaterialTheme.typography.titleLarge, color = TextSoft)
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
                "Paste an extended PUBLIC key (xpub/ypub/zpub) to watch balances without any way " +
                    "to spend. Careful: an xpub reveals every address you will ever use — keep it " +
                    "like a bank statement."
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
                onClick = { vm.setXpub(xpub, label) },
                enabled = xpub.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("WATCH BOTH CHAINS", style = MaterialTheme.typography.titleMedium) }
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
    var arkPull by remember { mutableStateOf(0) }
    val openTab by com.kilombino.pyblockwatch.data.OpenTab.flow.collectAsState()
    LaunchedEffect(openTab) {
        val t = openTab ?: return@LaunchedEffect
        if (t == com.kilombino.pyblockwatch.data.OpenTab.UPDATE) return@LaunchedEffect  // the update dialog takes it
        com.kilombino.pyblockwatch.data.OpenTab.flow.value = null
        when (t) {
            com.kilombino.pyblockwatch.data.OpenTab.COINJOIN -> {
                cjTab = true; arkTab = false
                if (state.selected != Chain.BLAKE2B) vm.select(Chain.BLAKE2B)
                com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.requestRefresh()
            }
            com.kilombino.pyblockwatch.data.OpenTab.ARK -> { arkTab = true; cjTab = false; arkPull++ }
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
    val accent by animateColorAsState(if (arkTab) Purple else Color(chain.accent), tween(400), label = "accent")
    var showSettings by remember { mutableStateOf(false) }
    var showSend by remember { mutableStateOf(false) }
    var showReceive by remember { mutableStateOf(false) }

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
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
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
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(28.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            WalletModeSwitch(state, vm, accent)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.setUiMode("simple") }) {
                Text("simple", style = MaterialTheme.typography.bodySmall, color = TextSoft)
            }
            TextButton(onClick = { showSettings = !showSettings }) {
                Text(if (showSettings) "close" else "settings",
                     style = MaterialTheme.typography.bodySmall, color = TextSoft)
            }
            AppLogo()
        }

        // A watch-only wallet stays that: no Ark, no coinjoin (it cannot sign).
        if (!state.isHot && (arkTab || cjTab)) { arkTab = false; cjTab = false }
        AdvancedTabs(chain, arkTab, cjTab, state.isHot,
            onBtc = { arkTab = false; cjTab = false; if (chain != Chain.BLAKE2B) vm.select(Chain.BLAKE2B) },
            onArk = { arkTab = true; cjTab = false; showSend = false; showReceive = false },
            onCoinjoin = { cjTab = true; arkTab = false; showSend = false; showReceive = false; if (chain != Chain.BLAKE2B) vm.select(Chain.BLAKE2B) })

        if (cjTab) {
            CoinjoinScreen(vm, Color(Chain.BLAKE2B.accent))
            Spacer(Modifier.height(30.dp))
            return@Column
        }

        if (arkTab) {
            if (com.kilombino.pyblockwatch.ark.Ark.available) ArkScreen(vm, Purple, arkPull) else ArkComingSoon(Purple)
            Spacer(Modifier.height(30.dp))
            return@Column
        }

        BalanceCard(chain, cs, accent, state.scriptType, state.nextRefreshAt, state.market, state.fiat, vm::setFiat)

        when {
            showSend -> SendSheet(vm, accent) { showSend = false }
            showReceive -> ReceiveSheet(vm, accent) { showReceive = false }
            else -> {
                // Receive works for any wallet; Send only when a seed is present.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showReceive = true },
                        colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = accent),
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f),
                    ) { Text("RECEIVE", style = MaterialTheme.typography.titleMedium) }
                    if (state.isHot) {
                        Button(
                            onClick = { vm.resetSend(); showSend = true },
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                            shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f),
                        ) { Text("SEND", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        }

        ScanStatus(cs, accent, onRetry = { vm.scan(chain) })

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

        Reveal(showSettings) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SettingsPanel(state, vm, accent, onToggleNotifications) { vm.forget() }
                // The recovery words live at the very end of settings.
                if (state.isHot) HotWordsPanel(vm, accent)
            }
        }

        if (!state.isHot) WatchAddressesCard(state, vm, chain, accent)
        else if (cs.rows.isNotEmpty()) AddressList(cs.rows, accent)

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

/** BTC | ARK | COINJOIN, plus SPAMCOIN while the spamchain (opened from settings) is shown. */
@Composable
private fun AdvancedTabs(chain: Chain, arkTab: Boolean, cjTab: Boolean, hot: Boolean, onBtc: () -> Unit, onArk: () -> Unit, onCoinjoin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelSoft).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val tabs = buildList {
            add(Triple("BTC", !arkTab && !cjTab && chain == Chain.BLAKE2B, Color(Chain.BLAKE2B.accent)) to onBtc)
            if (hot) {
                add(Triple("ARK", arkTab, Purple) to onArk)
                add(Triple("COINJOIN", cjTab, Good) to onCoinjoin)
            }
            if (!arkTab && !cjTab && chain == Chain.SHA256) add(Triple("SPAMCOIN", true, Color(Chain.SHA256.accent)) to {})
        }
        tabs.forEach { (t, onClick) ->
            val (label, active, color) = t
            val bg by animateColorAsState(if (active) color.copy(alpha = 0.18f) else Color.Transparent, tween(300), label = "tabbg")
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(bg)
                    .clickable(onClick = onClick).padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = if (active) color else TextFaint)
            }
        }
    }
}

@Composable
private fun BalanceCard(
    chain: Chain, cs: ChainState, accent: Color, scriptType: ScriptType?, nextRefreshAt: Long,
    market: com.kilombino.pyblockwatch.data.MarketData?, fiat: String, onFiat: (String) -> Unit,
) {
    // The countdown ticks here, only while this card is shown, instead of the whole screen
    // being redrawn every second.
    var secondsUntilRefresh by remember { mutableStateOf(0) }
    LaunchedEffect(nextRefreshAt) {
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
                Text("↻ ${secondsUntilRefresh}s", style = MaterialTheme.typography.bodySmall,
                     color = TextFaint)
                Spacer(Modifier.width(8.dp))
                PulseDot(Good, 7)
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

        // Value in dollars or euros, the same choice as in simple mode and Ark.
        Spacer(Modifier.height(8.dp))
        val sym = if (fiat == "EUR") "€" else "$"
        val value = fiatPerCoin(chain, market, fiat)?.let { it * cs.total / 1e8 }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(street(if (value == null) "≈ $sym –" else "≈ $sym" + String.format(java.util.Locale.US, "%,.2f", value)),
                 style = MaterialTheme.typography.headlineSmall, color = TextMain, modifier = Modifier.weight(1f))
            FiatChip("USD", fiat == "USD", accent) { onFiat("USD") }
            Spacer(Modifier.width(6.dp))
            FiatChip("EUR", fiat == "EUR", accent) { onFiat("EUR") }
        }
        if (cs.unconfirmed != 0L) {
            Spacer(Modifier.height(4.dp))
            Text(street("unconfirmed: ${groupSats(cs.unconfirmed)} sats"),
                 style = MaterialTheme.typography.bodySmall, color = Warn)
        }
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
private fun ScanStatus(cs: ChainState, accent: Color, onRetry: () -> Unit) {
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
                        PulseDot(Good); Spacer(Modifier.width(10.dp))
                        Text("scan complete", style = MaterialTheme.typography.bodyMedium, color = Good)
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
private fun AddressList(rows: List<AddressRow>, accent: Color) {
    Panel(accent = accent) {
        SectionLabel("addresses with activity", accent)
        Spacer(Modifier.height(10.dp))
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(shortAddress(row.address),
                         style = MaterialTheme.typography.bodyMedium, color = TextMain)
                    Text(
                        "${row.path}  ·  ${if (row.chainIndex == 0) "receive" else "change"}",
                        style = MaterialTheme.typography.bodySmall, color = TextFaint,
                    )
                }
                Text(
                    if (row.total > 0) street("${groupSats(row.total)} sats") else "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.total > 0) accent else TextFaint,
                    fontWeight = FontWeight.Bold,
                )
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
                Explain("A service checks the already-discovered addresses every 15 minutes. " +
                    "No push server, no Google: your phone does the lookup itself.")
            }
            Switch(
                checked = state.notificationsEnabled,
                onCheckedChange = onToggleNotifications,
                colors = SwitchDefaults.colors(checkedThumbColor = accent),
            )
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
                    Explain("Checked every 5 minutes with the balance watcher. Your own rounds always notify.")
                }
                val askNotif = rememberNotificationPermission()
                Switch(checked = cjNotify, onCheckedChange = { cjNotify = it; vm.setCoinjoinNotify(it); if (it) askNotif() },
                    colors = SwitchDefaults.colors(checkedThumbColor = accent))
            }
            Spacer(Modifier.height(14.dp))
            FullBackupPanel(vm, accent)
        }

        Spacer(Modifier.height(14.dp))
        Text("Address type (derivation)",
             style = MaterialTheme.typography.bodyMedium, color = TextMain)
        Explain("How the keys are read from your xpub. BIP84 (bc1q) by default. " +
            "Change it if your wallet uses another format; both chains are rescanned.")
        Spacer(Modifier.height(4.dp))
        // The hot wallet's xpub is its BIP84 account: reading it as another type would give
        // addresses its keys do not sign for. Only a watch-only xpub may change type.
        if (state.isHot) Text("Hot wallet: BIP84 (bc1q), fixed — the keys it signs with.",
            style = MaterialTheme.typography.bodySmall, color = TextFaint)
        else DerivationSelector(state.scriptType, accent) { vm.setScriptType(it) }

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
                Explain(if (chain == Chain.BLAKE2B) "One person, one node: put your own here. Leave empty to use " +
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
            Text((if (rescueOpen) "▾" else "▸") + " Rescue XBT from a pre-fork Lightning (LND) seed",
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
                }, colors = SwitchDefaults.colors(checkedThumbColor = accent))
            }
        }
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = { vm.startSetup() },
            colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = accent),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.isHot) "SWITCH / NEW WALLET" else "CREATE A DIFFERENT HOT WALLET",
                 style = MaterialTheme.typography.titleMedium) }
        TextButton(onClick = onForget) {
            Text("forget this wallet", color = Bad, style = MaterialTheme.typography.bodySmall)
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
    var bumping by remember { mutableStateOf<String?>(null) }
    bumping?.let { txid -> BumpDialog(vm, txid, accent, explorer) { bumping = null; vm.resetSend() } }
    val site = explorer.removePrefix("https://").removePrefix("http://")
    asking?.let { t ->
        TxDetailDialog(t.txid, accent, explorer,
            status = if (t.pending) "in mempool · 0 confirmations" else "${t.confirmations} confirmations",
            onSpeedUp = if (t.pending && isHot) ({ asking = null; bumping = t.txid }) else null,
            onClose = { asking = null }, vm = vm, chain = chain)
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
                    after[t.txid]?.let { left ->
                        Text(street("(left: ${groupSats(left)} $unit)"), style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    }
                    Text(
                        "${t.txid.take(8)}…${t.txid.takeLast(6)}",
                        style = MaterialTheme.typography.bodySmall, color = TextFaint,
                    )
                }
                if (t.pending) {
                    Text("in mempool · 0 conf",
                         style = MaterialTheme.typography.bodySmall, color = Warn)
                    if (isHot) TextButton(onClick = { bumping = t.txid }) {
                        Text("⚡ speed up", style = MaterialTheme.typography.bodySmall, color = accent)
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

/**
 * Speed up an unconfirmed send by replacing it (RBF) with the same payment at a higher fee,
 * paid from its change. Only sends from this wallet with a change output qualify; anything
 * else explains why it can't be replaced.
 */
@Composable
private fun BumpDialog(vm: WalletViewModel, txid: String, accent: Color, explorer: String, onClose: () -> Unit) {
    val sent = (vm.state.collectAsState().value.sendPhase as? SendPhase.Sent)?.txid
    if (sent != null) {
        TxDetailDialog(sent, accent, explorer, status = "replacement sent ✓ · in mempool", onSpeedUp = null, onClose = onClose)
        return
    }
    val state by vm.state.collectAsState()
    val activity = LocalContext.current as androidx.fragment.app.FragmentActivity
    var rate by remember { mutableStateOf("3") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Speed up ${txid.take(8)}…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (val p = state.sendPhase) {
                    is SendPhase.Review -> {
                        val d = p.draft
                        Text("Replaces the stuck send with the same payment and a higher fee, taken from your change.",
                             style = MaterialTheme.typography.bodySmall)
                        Text("To: ${shortAddress(d.toAddress)} · ${groupSats(d.amount)} sats", style = MaterialTheme.typography.bodySmall)
                        Text("Fee: ${groupSats(d.replacedFee)} → ${groupSats(d.fee)} sats", style = MaterialTheme.typography.bodySmall, color = accent)
                        Text("Change: ${groupSats(d.change)} sats", style = MaterialTheme.typography.bodySmall)
                    }
                    is SendPhase.Sent -> Text("Replacement sent ✓\n${p.txid}", style = MaterialTheme.typography.bodySmall, color = Good)
                    SendPhase.Preparing, SendPhase.Broadcasting -> Text("working…", style = MaterialTheme.typography.bodySmall, color = accent)
                    else -> {
                        if (p is SendPhase.Failed) Text(p.message, style = MaterialTheme.typography.bodySmall, color = Bad)
                        Text("New fee rate. It must beat the old fee by at least 1 sat/vB of the transaction's size; " +
                            "most pools only mine from 1 sat/vB.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = rate, onValueChange = { rate = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("sat/vB", style = MaterialTheme.typography.bodySmall) }, singleLine = true)
                    }
                }
            }
        },
        confirmButton = {
            when (state.sendPhase) {
                is SendPhase.Review -> TextButton(onClick = {
                    runCatching { vm.seedDecryptCipher() }.onSuccess { cipher ->
                        Biometric.authenticate(activity, "Speed up payment", "Unlock to sign the replacement", cipher,
                            onSuccess = { authed -> vm.confirmSend(authed) }, onError = { })
                    }
                }) { Text("CONFIRM & SIGN", color = accent) }
                is SendPhase.Sent -> TextButton(onClick = onClose) { Text("DONE", color = accent) }
                SendPhase.Preparing, SendPhase.Broadcasting -> {}
                else -> TextButton(onClick = { vm.prepareBump(txid, rate.toDoubleOrNull() ?: 3.0) }) {
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
    vm: WalletViewModel? = null, chain: Chain = Chain.BLAKE2B,
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
        title = { Text("Transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = accent)
                SelectionContainer {
                    Text(txid, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextMain)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(txid)); copied = true
                    }) { Text(if (copied) "COPIED ✓" else "📋 COPY TXID", color = accent) }
                    TextButton(onClick = {
                        if (vm != null && vm.explorerChosen(chain)) runCatching { uri.openUri("$explorer/tx/$txid") }
                        else confirmOpen = true
                    }) { Text("🔎 EXPLORER", color = accent) }
                }
                if (confirmOpen) {
                    Text("This opens an EXTERNAL block explorer: the site sees which transaction you look up " +
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
                        runCatching { uri.openUri("$u/tx/$txid") }; confirmOpen = false
                    }) { Text("SAVE AND OPEN", color = if (valid) accent else TextFaint) }
                    vm?.let { v ->
                        val kilombino = v.defaultExplorerFor(chain)
                        TextButton(onClick = {
                            v.setExplorer(chain, kilombino)
                            runCatching { uri.openUri("$kilombino/tx/$txid") }; confirmOpen = false
                        }) { Text("USE KILOMBINO'S (${kilombino.removePrefix("https://")})", color = TextSoft) }
                    }
                }
                onSpeedUp?.let {
                    TextButton(onClick = it) { Text("⚡ SPEED UP (RBF)", color = accent) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("CLOSE", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )
}

