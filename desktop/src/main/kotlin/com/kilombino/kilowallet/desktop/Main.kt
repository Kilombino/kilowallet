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
import com.kilombino.pyblockwatch.ui.MainActivity
import kotlinx.coroutines.delay

private val useTray = Platform.os != Platform.Os.LINUX && androidx.compose.ui.window.isTraySupported

/** The desktop app's Application: the shared code's `getApplication()`. */
object DesktopApp : Application()

fun main() {
    // Android's Keystore, emulated over a password (see DesktopKeyStore), before any vault is touched.
    DesktopKeyStore.install()
    // What the Android app runs first: the crash log.
    com.kilombino.pyblockwatch.data.KilowalletApp().onCreate()
    android.content.pm.DesktopVersion.name = BuildInfo.VERSION
    android.content.pm.DesktopVersion.code = BuildInfo.CODE

    // A second launch only brings the open copy's window up (and that copy's window may be hidden).
    val showRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    if (!SingleInstance.acquire(onShow = { showRequests.tryEmit(Unit) })) return

    application {
        var unlocked by remember { mutableStateOf(DesktopKeyStore.unlocked()) }
        // Any key or click in the app counts as use; locking after a while without use is a choice
        // made on the unlock screen (off by default).
        LaunchedEffect(Unit) {
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener({ DesktopKeyStore.touch() },
                java.awt.AWTEvent.KEY_EVENT_MASK or java.awt.AWTEvent.MOUSE_EVENT_MASK or java.awt.AWTEvent.MOUSE_WHEEL_EVENT_MASK)
            while (true) {
                delay(20_000)
                if (DesktopKeyStore.lockIfIdle()) unlocked = false
            }
        }
        var visible by remember { mutableStateOf(true) }
        val tray = rememberTrayState()
        val icon = painterResource(com.kilombino.pyblockwatch.R.mipmap.ic_launcher)
        val toast = remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            DesktopNotifications.popup = { title, text ->
                if (useTray) tray.sendNotification(androidx.compose.ui.window.Notification(I18n.tr(title), I18n.tr(text)))
                else Platform.notify(I18n.tr(title), I18n.tr(text))
            }
            DesktopToast.show = { javax.swing.SwingUtilities.invokeLater { toast.value = I18n.tr(it) } }
            DesktopIntents.onMessage = DesktopToast.show
        }

        // On Windows and macOS the wallet keeps watching in the background (like the phone's watcher)
        // while the window is closed: closing hides it to the tray; Quit there ends the app.
        // Not on Linux: Java's tray icon there is often dead (no menu, a click opens nothing), so a
        // hidden window could not be brought back nor the app quit. Closing the window quits.
        if (useTray) Tray(icon = icon, state = tray, tooltip = "Kilowallet", onAction = { visible = true }, menu = {
            Item(Tr.t("Open Kilowallet", "Abrir Kilowallet"), onClick = { visible = true })
            Item(Tr.t("Quit", "Salir"), onClick = { exitApplication() })
        })

        Window(
            onCloseRequest = { if (unlocked && useTray) visible = false else exitApplication() },
            visible = visible,
            title = "Kilowallet " + BuildInfo.VERSION,
            icon = icon,
            state = rememberWindowState(size = DpSize(480.dp, 900.dp)),
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
                    if (unlocked) TextButton(onClick = { DesktopKeyStore.lock(); unlocked = false },
                        modifier = Modifier.align(Alignment.TopStart)) { Text("🔒 " + Tr.t("Lock", "Bloquear"), fontSize = 11.sp, color = Color.Gray) }
                    toast.value?.let { msg ->
                        LaunchedEffect(msg) { delay(3500); toast.value = null }
                        Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp)) { Text(msg) }
                    }
                    PasswordDialog()
                }
            }
        }
    }
}

/** The Android app's main screen, run as on the phone: same activity, same screens. */
@Composable
private fun WalletWindow() {
    val activity = remember {
        MainActivity().also {
            it.onCreate(null); it.onStart(); it.onResume()
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
            error = Tr.t("Wrong password.", "Contraseña incorrecta.") + if (w > 0) " " + Tr.t("Wait $w s before trying again.", "Espera $w s antes de volver a probar.") else ""
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
