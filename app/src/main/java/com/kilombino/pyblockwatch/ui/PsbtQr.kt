package com.kilombino.pyblockwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kilombino.pyblockwatch.crypto.Ur

/**
 * A PSBT as SeedSigner reads it: an animated QR of `ur:crypto-psbt` fountain parts (one about every
 * quarter second, 100-byte fragments), as the bond spend shows it; a PSBT small enough is one still
 * frame. [paused] stops the animation (while the camera is open). FULL SCREEN shows it over the whole
 * screen at full brightness.
 */
@Composable
fun AnimatedPsbtQr(psbt: ByteArray, sizeDp: Int = 300, paused: Boolean = false, accent: Color = Color.Unspecified) {
    val encoder = remember(psbt.contentHashCode()) { Ur.psbtEncoder(psbt, 100) }
    var part by remember(psbt.contentHashCode()) { mutableStateOf(encoder.nextPart()) }
    LaunchedEffect(encoder, paused) {
        if (encoder.isSinglePart || paused) return@LaunchedEffect
        while (true) { kotlinx.coroutines.delay(250); part = encoder.nextPart() }
    }
    var full by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().background(Color.White).padding(10.dp), contentAlignment = Alignment.Center) {
        QrImage(part, sizeDp)
    }
    androidx.compose.material3.TextButton(onClick = { full = true }) {
        com.kilombino.pyblockwatch.ui.i18n.Text("⛶ FULL SCREEN", color = accent)
    }
    // The whole screen, white, at full brightness: a signer's camera reads it far better than a small QR on a dim phone.
    if (full) androidx.compose.ui.window.Dialog(onDismissRequest = { full = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        androidx.compose.runtime.DisposableEffect(window) {
            window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose {}
        }
        val side = androidx.compose.ui.platform.LocalConfiguration.current.let { minOf(it.screenWidthDp, it.screenHeightDp) } - 16
        Box(Modifier.fillMaxSize().background(Color.White).clickable { full = false }, contentAlignment = Alignment.Center) {
            QrImage(part, side)
            com.kilombino.pyblockwatch.ui.i18n.Text("tap to close", color = Color.Gray, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp))
        }
    }
}

/**
 * Scans a signed PSBT back: an animated UR (SeedSigner and other UR signers), put together frame by
 * frame, or a single plain QR of base64 or hex. [onPsbt] gets the bytes of a UR; [onText] the text of
 * a plain QR.
 */
@Composable
fun SignedPsbtScanner(onPsbt: (ByteArray) -> Unit, onText: (String) -> Unit, onError: (String) -> Unit, onDismiss: () -> Unit) {
    val decoder = remember { Ur.Decoder() }
    var frames by remember { mutableStateOf(0) }
    var plain by remember { mutableStateOf<String?>(null) }
    QrScannerDialog(
        onResult = {
            val p = plain
            if (p != null) onText(p)
            else runCatching { decoder.psbt() }.onSuccess(onPsbt).onFailure { onError(it.message ?: "could not read the PSBT") }
        },
        onDismiss = onDismiss,
        onFrame = { text ->
            frames++
            if (text.trim().startsWith("ur:", ignoreCase = true)) runCatching { decoder.receive(text) }.getOrDefault(false)
            else { plain = text; true }
        },
        progress = if (frames == 0) "Point the camera at the signed PSBT's QR" else
            "Reading the signed PSBT: ${(decoder.progress * 100).toInt()}%",
    )
}
