package com.kilombino.pyblockwatch.roblo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kilombino.kilowallet.desktop.PasswordPrompt
import com.kilombino.pyblockwatch.ui.AppLogo
import com.kilombino.pyblockwatch.ui.QuitButton
import com.kilombino.pyblockwatch.ui.TextMain
import com.kilombino.pyblockwatch.ui.TextSoft
import com.kilombino.pyblockwatch.ui.UiState
import com.kilombino.pyblockwatch.ui.WalletModeSwitch
import com.kilombino.pyblockwatch.ui.WalletViewModel
import com.kilombino.kilowallet.desktop.i18n.Text

/** Roblosats' colour for the tab (the ROBLO segment of the switch). */
val RobloAccent = Color(0xFF8E7CF0)

/**
 * 🤖 ROBLO on the desktop: the Roblosats client (robots, offers, orders, chat, disputes) under the
 * same bar as the wallets. The page is [RobloWeb]'s Chromium panel, kept between tabs.
 *
 * Chromium's panel is a native window inside ours, drawn above anything the app paints: while a
 * question of the app is open over it (the password, a payment sheet, an outside link, the close
 * guard), the panel is hidden, not closed, and comes back as it was.
 */
@Composable
fun RobloScreen(state: UiState, vm: WalletViewModel) {
    DisposableEffect(Unit) {
        RobloWeb.onScreen()
        onDispose { RobloWeb.offScreen() }
    }
    val req by RobloWallet.request.collectAsState()
    val outside = RobloWeb.outsideLink.value
    val failure = RobloWeb.failure.value
    val covered = req != null || outside != null || PasswordPrompt.pending.value != null || RobloOverlay.covers.value > 0
    // What the page asked of the wallet: its sheet (pay, receive, Lightning), with the password.
    RobloWalletSheets(state, vm)
    if (outside != null) AlertDialog(
        onDismissRequest = { RobloWeb.answerOutside(false) },
        title = { Text("Open outside Kilowallet?") },
        text = {
            Column {
                Text("This link leaves Roblosats and opens in another app (your browser does not go through Kilowallet's Tor):", color = TextSoft)
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Text(outside.take(300), color = TextMain, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { RobloWeb.answerOutside(true) }) { Text("OPEN") } },
        dismissButton = { TextButton(onClick = { RobloWeb.answerOutside(false) }) { Text("CANCEL") } },
    )
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { WalletModeSwitch(state, vm, RobloAccent) }
            QuitButton(RobloAccent)
            Spacer(Modifier.width(4.dp))
            AppLogo(34)
        }
        if (failure != null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Text(com.kilombino.kilowallet.desktop.i18n.I18n.tr("Roblosats could not start on this computer: ") + failure, color = TextSoft)
            }
        }
        SwingPanel(
            background = Color(0xFF0B0B10),
            factory = { RobloWeb.obtain() },
            modifier = if (failure == null) Modifier.fillMaxSize() else Modifier.height(0.dp),
            update = { it.isVisible = !covered },
        )
    }
}

/** Something of the app's own drawn over the ROBLO page (a dialog of the window): the page hides meanwhile. */
object RobloOverlay {
    val covers = androidx.compose.runtime.mutableIntStateOf(0)
}
