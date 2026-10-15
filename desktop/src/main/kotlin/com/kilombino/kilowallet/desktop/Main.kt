package com.kilombino.kilowallet.desktop

import android.app.Application
import android.app.DesktopNotifications
import android.content.DesktopIntents
import android.widget.DesktopToast
import androidx.compose.foundation.background
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

/** The desktop app's Application: the shared code's `getApplication()`. */
object DesktopApp : Application()

fun main() {
    // Android's Keystore, emulated over a password (see DesktopKeyStore), before any vault is touched.
    DesktopKeyStore.install()
    // What the Android app runs first: the crash log.
    com.kilombino.pyblockwatch.data.KilowalletApp().onCreate()
    android.content.pm.DesktopVersion.name = BuildInfo.VERSION
    android.content.pm.DesktopVersion.code = BuildInfo.CODE

    application {
        var unlocked by remember { mutableStateOf(DesktopKeyStore.unlocked()) }
        var visible by remember { mutableStateOf(true) }
        val tray = rememberTrayState()
        val icon = painterResource(com.kilombino.pyblockwatch.R.mipmap.ic_launcher)
        val toast = remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            DesktopNotifications.popup = { title, text -> tray.sendNotification(androidx.compose.ui.window.Notification(I18n.tr(title), I18n.tr(text))) }
            DesktopToast.show = { javax.swing.SwingUtilities.invokeLater { toast.value = I18n.tr(it) } }
            DesktopIntents.onMessage = DesktopToast.show
        }

        // The wallet keeps watching in the background (like the phone's watcher) while the window is
        // closed: closing hides it to the tray; Quit there ends the app.
        Tray(icon = icon, state = tray, tooltip = "Kilowallet", onAction = { visible = true }, menu = {
            Item(Tr.t("Open Kilowallet", "Abrir Kilowallet"), onClick = { visible = true })
            Item(Tr.t("Quit", "Salir"), onClick = { exitApplication() })
        })

        Window(
            onCloseRequest = { if (unlocked && androidx.compose.ui.window.isTraySupported) visible = false else exitApplication() },
            visible = visible,
            title = "Kilowallet " + BuildInfo.VERSION,
            icon = icon,
            state = rememberWindowState(size = DpSize(480.dp, 900.dp)),
        ) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0B0B10))) {
                    if (!unlocked) UnlockScreen(onUnlocked = { unlocked = true })
                    else WalletWindow()
                    BetaBadge(Modifier.align(Alignment.TopEnd))
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
        if (ok) { pw = ""; pw2 = ""; onUnlocked() } else error = Tr.t("Wrong password.", "Contraseña incorrecta.")
    }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Kilowallet", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(Tr.t("Desktop beta for Linux", "Beta de escritorio para Linux"), color = Color(0xFFE0A100))
        Spacer(Modifier.height(18.dp))
        if (first) {
            Text(Tr.t(
                "This is a BETA. Try it with small amounts first.\n\nChoose a password for this computer. It encrypts the recovery words and every key on disk, and it is asked before spending or showing the words. If you forget it, restore from your written recovery words.",
                "Esto es una BETA. Pruébala primero con cantidades pequeñas.\n\nElige una contraseña para este ordenador. Cifra en el disco las palabras de recuperación y todas las claves, y se pide antes de gastar o de enseñar las palabras. Si la olvidas, recupera con tus palabras apuntadas."),
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
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { Tr.set(false) }) { Text("English", color = if (!Tr.spanish.value) Color.White else Color.Gray) }
            TextButton(onClick = { Tr.set(true) }) { Text("Español", color = if (Tr.spanish.value) Color.White else Color.Gray) }
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
