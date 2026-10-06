package com.kilombino.pyblockwatch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity

/**
 * Street mode: balances and movement amounts read "STREET MODE" until the owner unlocks
 * them with the fingerprint (or screen lock). It comes back on every time the app leaves
 * the screen — backgrounded, or the phone locked — so the figures are never left showing.
 */
object StreetMode {
    const val MASK = "STREET MODE"

    var hidden by mutableStateOf(true)
        private set

    fun lock() { hidden = true }

    fun unlock(activity: FragmentActivity, onError: (String) -> Unit = {}) {
        Biometric.confirm(activity, "Unlock street mode", "Show your balances and movements",
            onSuccess = { hidden = false }, onError = onError)
    }
}

/** [s] as is, or the street mode mask while it is on. Read in composition, so it updates live. */
fun street(s: String): String = if (StreetMode.hidden) StreetMode.MASK else s

/** The eye next to a balance: unlock with the fingerprint, or hide again. */
@Composable
fun StreetEye(color: Color) {
    val activity = LocalContext.current as FragmentActivity
    TextButton(onClick = { if (StreetMode.hidden) StreetMode.unlock(activity) else StreetMode.lock() }) {
        Text(if (StreetMode.hidden) "👁 show" else "🙈 hide", style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/**
 * Whether the app is on screen. Loops meant for someone looking at the wallet (the 30 s
 * refresh, the Ark tab's reload, an invoice's status) pause while it is not, so a wallet
 * left in the background does not keep the radio busy. Background notifications have their
 * own watcher and are not affected.
 */
object AppVisible {
    var value by mutableStateOf(false)
}
