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

    /** Settings: start hidden (the default) or showing the amounts. Read once at start. */
    var byDefault by mutableStateOf(true)
        private set

    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("pyblockwatch", android.content.Context.MODE_PRIVATE)

    fun init(ctx: android.content.Context) {
        byDefault = prefs(ctx).getBoolean("street_mode_default", true)
        hidden = byDefault
    }

    /**
     * Turning it on needs nothing; turning it off shows every amount from then on, so it asks
     * for the fingerprint first, like the eye does.
     */
    fun setByDefault(activity: FragmentActivity, on: Boolean) {
        fun apply() { byDefault = on; hidden = on; prefs(activity).edit().putBoolean("street_mode_default", on).apply() }
        if (on) apply() else Biometric.confirm(activity, "Turn street mode off", "Amounts will show without asking",
            onSuccess = { apply() }, onError = {})
    }

    /** Back to the chosen default: hidden, unless street mode is turned off in settings. */
    fun lock() { hidden = byDefault }

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
