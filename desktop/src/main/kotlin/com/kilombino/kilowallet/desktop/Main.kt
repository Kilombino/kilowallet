package com.kilombino.kilowallet.desktop

import android.app.Application
import android.app.DesktopNotifications
import android.content.DesktopIntents
import android.widget.DesktopToast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.kilombino.kilowallet.desktop.i18n.I18n
import com.kilombino.pyblockwatch.BuildConfig
import com.kilombino.pyblockwatch.ui.MainActivity
import kotlinx.coroutines.delay

private val useTray = Platform.os != Platform.Os.LINUX && androidx.compose.ui.window.isTraySupported

/** The desktop app's Application: the shared code's `getApplication()`. */
object DesktopApp : Application()

/** The window's activity, once created (a tapped notification hands it its intent). */
@Volatile private var mainActivity: MainActivity? = null

/**
 * A Roblosats payment held by this node (a hold invoice: an HTLC out until the trade ends), as
 * "order kilobot/24 · 3000 sats"; null when none. Quitting or sleeping then needs care.
 */
internal fun robloHeldPayment(): String? {
    if (!BuildConfig.ROBLO) return null
    val held = runCatching { com.kilombino.pyblockwatch.roblo.RobloLn.held(android.content.DesktopContext) }.getOrDefault(emptyList())
    if (held.isEmpty()) return null
    return held.joinToString(", ") { "Roblosats ${it.order} · ${it.sats} sats" }
}

fun main() {
    // ROBLO: the Roblosats page is a native Chromium window inside ours, drawn above whatever the
    // app paints on its own canvas. Dialogs and menus then go on layers of their own above it, or
    // they would open hidden behind the page (seen with ⏻'s question while ROBLO was open).
    if (BuildConfig.ROBLO && System.getProperty("compose.layers.type") == null) System.setProperty("compose.layers.type", "WINDOW")
    // Android's Keystore, emulated over a password (see DesktopKeyStore), before any vault is touched.
    DesktopKeyStore.install()
    // What the Android app runs first: the crash log.
    com.kilombino.pyblockwatch.data.KilowalletApp().onCreate()
    android.content.pm.DesktopVersion.name = BuildInfo.VERSION
    android.content.pm.DesktopVersion.code = BuildInfo.CODE

    // A second launch only brings the open copy's window up (and that copy's window may be hidden).
    val showRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    if (!SingleInstance.acquire(onShow = { showRequests.tryEmit(Unit) })) return
    // Test builds: what the screens show, for the lab's scenarios (never in a package); the lab's
    // Knots node is on this machine, not behind an emulator's host address.
    TestDriver.start()
    if (BuildConfig.DEBUG) com.kilombino.pyblockwatch.ln.Lnd.LAB_PEER = "127.0.0.1:29444"

    application {
        var unlocked by remember { mutableStateOf(DesktopKeyStore.unlocked()) }
        // Quitting with a Roblosats payment held by the node asks first (it would stop the node).
        var quitGuard by remember { mutableStateOf<String?>(null) }
        fun quitNow() { exitApplication() }
        fun requestQuit() { val held = robloHeldPayment(); if (held != null) quitGuard = held else quitNow() }
        LaunchedEffect(Unit) { android.app.DesktopActivities.onQuit = { javax.swing.SwingUtilities.invokeLater { requestQuit() } } }
        // The computer stays awake while such a payment is held; after a sleep the page reconnects.
        if (BuildConfig.ROBLO) LaunchedEffect(Unit) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SleepGuard.watch(heldPayment = ::robloHeldPayment, onWake = { gap ->
                    com.kilombino.pyblockwatch.roblo.RobloWeb.networkChanged("the computer woke up after ${gap / 1000} s")
                })
            }
        }
        // Any key or click in the app counts as use; locking after a while without use is a choice
        // made on the unlock screen (off by default).
        LaunchedEffect(Unit) {
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener({ DesktopKeyStore.touch() },
                java.awt.AWTEvent.KEY_EVENT_MASK or java.awt.AWTEvent.MOUSE_EVENT_MASK or java.awt.AWTEvent.MOUSE_WHEEL_EVENT_MASK)
            while (true) {
                delay(20_000)
                if (DesktopKeyStore.lockIfIdle()) { unlocked = false; if (BuildConfig.ROBLO) com.kilombino.pyblockwatch.roblo.RobloWeb.destroy("locked") }
            }
        }
        var visible by remember { mutableStateOf(true) }
        val tray = rememberTrayState()
        val icon = painterResource(com.kilombino.pyblockwatch.R.mipmap.ic_launcher)
        val toast = remember { mutableStateOf<String?>(null) }
        // The last tray notice that opens something when clicked (a Roblosats order).
        val trayClick = remember { java.util.concurrent.atomic.AtomicReference<android.app.PendingIntent?>(null) }
        LaunchedEffect(Unit) {
            DesktopNotifications.popup = { title, text ->
                if (useTray) tray.sendNotification(androidx.compose.ui.window.Notification(I18n.tr(title), I18n.tr(text)))
                else Platform.notify(I18n.tr(title), I18n.tr(text))
            }
            DesktopNotifications.popupWithIntent = { title, text, intent ->
                if (useTray) { trayClick.set(intent); tray.sendNotification(androidx.compose.ui.window.Notification(I18n.tr(title), I18n.tr(text))) }
                else Platform.notify(I18n.tr(title), I18n.tr(text), intent?.let { { it.send() } })
            }
            // A notice's click that opens the app's own screen: the window up, the activity told.
            android.content.DesktopIntents.onActivity = { i ->
                javax.swing.SwingUtilities.invokeLater { showRequests.tryEmit(Unit); mainActivity?.onNewIntent(i) }
            }
            DesktopToast.show = { javax.swing.SwingUtilities.invokeLater { toast.value = I18n.tr(it) } }
            DesktopIntents.onMessage = DesktopToast.show
        }

        // On Windows and macOS the wallet keeps watching in the background (like the phone's watcher)
        // while the window is closed: closing hides it to the tray; Quit there ends the app.
        // Not on Linux: Java's tray icon there is often dead (no menu, a click opens nothing), so a
        // hidden window could not be brought back nor the app quit. Closing the window quits.
        if (useTray) Tray(icon = icon, state = tray, tooltip = "Kilowallet", onAction = { visible = true; trayClick.getAndSet(null)?.send() }, menu = {
            Item(Tr.t("Open Kilowallet", "Abrir Kilowallet"), onClick = { visible = true })
            Item(Tr.t("Quit", "Salir"), onClick = { visible = true; requestQuit() })
        })

        Window(
            onCloseRequest = { if (unlocked && useTray) { visible = false; if (BuildConfig.ROBLO) com.kilombino.pyblockwatch.roblo.RobloWeb.appHidden() } else requestQuit() },
            visible = visible,
            title = "Kilowallet " + BuildInfo.VERSION,
            icon = icon,
            state = rememberWindowState(size = DpSize(480.dp, 900.dp)),
            // Escape is the phone's back: it closes the settings shown in place of a screen.
            onKeyEvent = {
                it.type == KeyEventType.KeyDown && it.key == Key.Escape && unlocked &&
                    androidx.activity.compose.DesktopBack.press()
            },
        ) {
            LaunchedEffect(Unit) {
                showRequests.collect { visible = true; window.isMinimized = false; window.toFront(); window.requestFocus() }
            }
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0B0B10))) {
                    // Once opened, the wallet's screens stay as they are; locking covers them with the
                    // unlock screen (building them again would start the phone's activity twice).
                    var opened by remember { mutableStateOf(unlocked) }
                    if (unlocked) opened = true
                    if (opened) WalletWindow()
                    if (!unlocked) Box(Modifier.fillMaxSize().background(Color(0xFF0B0B10))
                        // Takes every click, so nothing of the wallet underneath can be used while locked.
                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}) {
                        UnlockScreen(onUnlocked = { unlocked = true })
                    }
                    BetaBadge(Modifier.align(Alignment.TopEnd))
                    if (unlocked) TextButton(onClick = { DesktopKeyStore.lock(); unlocked = false; if (BuildConfig.ROBLO) com.kilombino.pyblockwatch.roblo.RobloWeb.destroy("locked") },
                        modifier = Modifier.align(Alignment.TopStart)) { Text("🔒 " + Tr.t("Lock", "Bloquear"), fontSize = 11.sp, color = Color.Gray) }
                    toast.value?.let { msg ->
                        LaunchedEffect(msg) { delay(3500); toast.value = null }
                        Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp)) { Text(msg) }
                    }
                    PasswordDialog()
                    quitGuard?.let { held -> QuitGuardDialog(held, onStay = { quitGuard = null }, onQuit = { quitGuard = null; quitNow() }) }
                }
            }
        }
    }
    // Chromium (the ROBLO page) ends with the app; left running it would keep the process alive.
    if (BuildConfig.ROBLO) runCatching { com.kilombino.pyblockwatch.roblo.RobloWeb.shutdown() }
    SleepGuard.release()
    kotlin.system.exitProcess(0)
}

/**
 * Quitting while this computer's node holds a Roblosats payment (a hold invoice): the node stops
 * with the app and can't settle or cancel it in time, so the channel may end up closed on chain.
 */
@Composable
private fun QuitGuardDialog(held: String, onStay: () -> Unit, onQuit: () -> Unit) {
    DisposableEffect(Unit) {
        com.kilombino.pyblockwatch.roblo.RobloOverlay.covers.intValue++
        onDispose { com.kilombino.pyblockwatch.roblo.RobloOverlay.covers.intValue-- }
    }
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text(I18n.tr("A Lightning payment of Roblosats is held")) },
        text = {
            Column {
                Text(I18n.tr("This computer's Lightning node holds a payment until the trade ends:"))
                Spacer(Modifier.height(6.dp))
                Text(held, color = Color(0xFFE0A100))
                Spacer(Modifier.height(10.dp))
                Text(I18n.tr("If Kilowallet closes, the node stops and can't settle or cancel it in time: the channel may be closed on chain and cost fees. Keep Kilowallet open (and the computer awake) until the trade ends."))
                if (!SleepGuard.holding.value) {
                    Spacer(Modifier.height(6.dp))
                    Text(I18n.tr("This computer may go to sleep: it could not be kept awake") + (SleepGuard.unavailable.value?.let { " ($it)" } ?: "") + ".",
                        color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { Button(onClick = onStay) { Text(I18n.tr("KEEP KILOWALLET OPEN")) } },
        dismissButton = { TextButton(onClick = onQuit) { Text(I18n.tr("Close anyway"), color = MaterialTheme.colorScheme.error) } },
    )
}


/** The Android app's main screen, run as on the phone: same activity, same screens. */
@Composable
private fun WalletWindow() {
    val activity = remember {
        MainActivity().also {
            it.onCreate(null); it.onStart(); it.onResume()
            mainActivity = it
        }
    }
    CompositionLocalProvider(LocalContext provides activity) {
        // Updates are offered by the shared screen itself (UpdateCheck/AppUpdater are the desktop's).
        activity.Screen()
    }
}

@Composable
private fun BetaBadge(modifier: Modifier) {
    Text("BETA", modifier.padding(6.dp).background(Color(0xFFE0A100)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

/**
 * First run: the password that locks this computer's copy of the wallet (what the phone's
 * screen lock and fingerprint do). Then, each start: that password.
 */
@Composable
private fun UnlockScreen(onUnlocked: () -> Unit) {
    val first = remember { !DesktopKeyStore.exists() }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var go by remember { mutableStateOf(0) }
    LaunchedEffect(go) {
        if (go == 0) return@LaunchedEffect
        busy = true
        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (first) { DesktopKeyStore.create(pw.toCharArray()); true } else DesktopKeyStore.unlock(pw.toCharArray())
        }
        busy = false
        if (ok) { pw = ""; pw2 = ""; onUnlocked() } else {
            val w = DesktopKeyStore.waitSeconds()
            error = Tr.t("Wrong password.", "Contraseña incorrecta.") + if (w > 0) " " + Tr.t("Wait before trying again:", "Espera antes de volver a probar:") + " $w s" else ""
        }
    }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Kilowallet", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(Tr.t("Desktop beta for ${Platform.displayName}", ""), color = Color(0xFFE0A100))
        Spacer(Modifier.height(18.dp))
        if (first) {
            Text(Tr.t(
                "This is a BETA. Try it with small amounts first.\n\nChoose a password for this computer. On disk it encrypts the recovery words, the wallet's keys and your node's RPC passwords, and it is asked before spending or showing the words. While the app is open and unlocked, the keys are in memory. If you forget it, restore from your written recovery words.",
                "Esto es una BETA. Pruébala primero con cantidades pequeñas.\n\nElige una contraseña para este ordenador. En el disco cifra las palabras de recuperación, las claves del monedero y las contraseñas RPC de tu nodo, y se pide antes de gastar o de enseñar las palabras. Con la app abierta y desbloqueada, las claves están en memoria. Si la olvidas, recupera con tus palabras apuntadas."),
                color = Color(0xFFCCCCCC))
            Spacer(Modifier.height(14.dp))
        }
        fun submit() {
            when {
                busy || pw.isEmpty() -> {}
                first && pw.length < 8 -> error = Tr.t("At least 8 characters.", "Al menos 8 caracteres.")
                first && pw != pw2 -> error = Tr.t("The two do not match.", "No coinciden.")
                else -> go++
            }
        }
        OutlinedTextField(pw, { pw = it; error = null }, label = { Text(Tr.t("Password", "Contraseña")) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            keyboardActions = KeyboardActions(onDone = { if (!first) submit() }),
            modifier = Modifier.fillMaxWidth().onEnter { if (!first) submit() })
        if (first) OutlinedTextField(pw2, { pw2 = it; error = null }, label = { Text(Tr.t("Repeat it", "Repítela")) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().onEnter { submit() })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(12.dp))
        Button(enabled = !busy && pw.isNotEmpty(), onClick = { submit() }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "…" else if (first) Tr.t("Create", "Crear") else Tr.t("Unlock", "Desbloquear")) }
        Spacer(Modifier.height(10.dp))
        // The password protects the disk; while the app is open and unlocked the keys are in memory.
        var autoLock by remember { mutableStateOf(DesktopKeyStore.autoLockMinutes) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = autoLock > 0, onCheckedChange = { autoLock = if (it) 15 else 0; DesktopKeyStore.autoLockMinutes = autoLock })
            Text(Tr.t("Lock after 15 minutes without use (Lightning, coinjoin on its own and Ark renewals wait until you unlock)",
                "Bloquear tras 15 minutos sin usar (Lightning, el coinjoin solo y las renovaciones de Ark esperan a que desbloquees)"),
                color = Color(0xFFAAAAAA), fontSize = 12.sp)
        }
        Spacer(Modifier.height(18.dp))
        // The app's language setting, the same one its settings change: the system's by default.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
            val app = com.kilombino.pyblockwatch.ui.i18n.I18n
            val auto = listOf(app.AUTO to Tr.t("Automatic", "Automático"))
            (auto + app.LANGUAGES).forEach { (code, name) ->
                TextButton(onClick = { app.set(android.content.DesktopContext, code) }) {
                    Text(name, color = if (app.choice.value == code) Color.White else Color.Gray)
                }
            }
        }
    }
}

/** What the phone's fingerprint prompt asks, on the desktop: the password (see PasswordPrompt). */
@Composable
private fun PasswordDialog() {
    val req = PasswordPrompt.pending.value ?: return
    var pw by remember(req) { mutableStateOf("") }
    var wrong by remember(req) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { PasswordPrompt.answer(null) },
        title = { Text(I18n.tr(req.title)) },
        text = {
            Column {
                if (req.subtitle.isNotBlank()) Text(I18n.tr(req.subtitle))
                val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(req) { runCatching { focus.requestFocus() } }
                OutlinedTextField(pw, { pw = it; wrong = false }, label = { Text(Tr.t("Password", "Contraseña")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), isError = wrong,
                    modifier = Modifier.focusRequester(focus).onEnter { if (!PasswordPrompt.answer(pw.toCharArray())) wrong = true })
                if (wrong) Text(Tr.t("Wrong password.", "Contraseña incorrecta."), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(onClick = { if (!PasswordPrompt.answer(pw.toCharArray())) wrong = true }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { PasswordPrompt.answer(null) }) { Text(Tr.t("Cancel", "Cancelar")) } },
    )
}

/** Enter (or keypad Enter) in a field runs [action]. */
private fun Modifier.onEnter(action: () -> Unit) = onPreviewKeyEvent {
    if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) { action(); true } else false
}
