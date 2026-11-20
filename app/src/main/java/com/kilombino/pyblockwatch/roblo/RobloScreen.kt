package com.kilombino.pyblockwatch.roblo

import android.net.Uri
import android.webkit.ValueCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import com.kilombino.pyblockwatch.ui.AppLogo
import com.kilombino.pyblockwatch.ui.QuitButton
import com.kilombino.pyblockwatch.ui.UiState
import com.kilombino.pyblockwatch.ui.WalletModeSwitch
import com.kilombino.pyblockwatch.ui.WalletViewModel

/** Roblosats' colour for the tab (the ROBLO segment of the switch). */
val RobloAccent = Color(0xFF8E7CF0)

/**
 * 🤖 ROBLO: the Roblosats client (robots, offers, orders, chat, disputes) inside Kilowallet, under
 * the same bar as the wallets. The page itself is [RobloWeb]'s retained WebView; Roblosats has its
 * own settings inside it (Tor or clearnet, language, notifications through Telegram).
 */
@Composable
fun RobloScreen(state: UiState, vm: WalletViewModel) {
    val activity = LocalContext.current as FragmentActivity
    // The chat's image button: the system photo picker (no storage permission), one image.
    val slot = androidx.compose.runtime.remember { PendingPick() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        slot.cb?.onReceiveValue(uri?.let { arrayOf(it) })
        slot.cb = null
    }
    DisposableEffect(Unit) {
        RobloWeb.pickImage = { cb ->
            slot.cb?.onReceiveValue(null)
            slot.cb = cb
            runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                .onFailure { slot.cb?.onReceiveValue(null); slot.cb = null }
        }
        RobloWeb.onScreen()
        onDispose {
            RobloWeb.pickImage = null
            slot.cb?.onReceiveValue(null); slot.cb = null
            RobloWeb.offScreen()
        }
    }
    // Edge to edge: the page's bottom bar (OFFERS · + · GARAGE) above the system's navigation bar.
    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { WalletModeSwitch(state, vm, RobloAccent) }
            QuitButton(RobloAccent)
            Spacer(Modifier.width(4.dp))
            AppLogo(34)
        }
        AndroidView(
            factory = { RobloWeb.obtain(activity) },
            modifier = Modifier.fillMaxSize(),
            // Kept for the next time the tab opens: RobloWeb ends it after a while off screen.
            onRelease = { w -> (w.parent as? android.view.ViewGroup)?.removeView(w) },
        )
    }
}

/** The page's file chooser callback while the photo picker is open (answered exactly once). */
private class PendingPick { var cb: ValueCallback<Array<Uri>>? = null }
