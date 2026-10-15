package com.kilombino.kilowallet.desktop

import androidx.compose.runtime.mutableStateOf

/**
 * Where the wallet asks for its password before a spend or showing the words (what the
 * fingerprint does on a phone). The window draws [pending] as a dialog; [answer] checks it.
 */
object PasswordPrompt {
    class Request(val title: String, val subtitle: String, val done: (Boolean) -> Unit)

    val pending = mutableStateOf<Request?>(null)

    fun ask(title: String, subtitle: String, done: (Boolean) -> Unit) {
        javax.swing.SwingUtilities.invokeLater {
            pending.value?.done?.invoke(false)
            pending.value = Request(title, subtitle, done)
        }
    }

    /** The password typed (null = cancelled); false = wrong, ask again. */
    fun answer(password: CharArray?): Boolean {
        val r = pending.value ?: return true
        if (password == null) { pending.value = null; r.done(false); return true }
        val ok = DesktopKeyStore.verify(password)
        password.fill(' ')
        if (!ok) return false
        pending.value = null
        r.done(true)
        return true
    }
}
