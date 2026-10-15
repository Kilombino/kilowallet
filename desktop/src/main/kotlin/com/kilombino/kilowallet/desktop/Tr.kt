package com.kilombino.kilowallet.desktop

import android.content.DesktopContext

/** The desktop app's language: Spanish or English, the system's by default, changeable in the window. */
object Tr {
    private val prefs get() = DesktopContext.getSharedPreferences("desktop", 0)

    val spanish = androidx.compose.runtime.mutableStateOf(
        prefs.getString("lang", null)?.let { it == "es" } ?: (java.util.Locale.getDefault().language == "es"))

    fun set(es: Boolean) { spanish.value = es; prefs.edit().putString("lang", if (es) "es" else "en").apply() }

    fun t(en: String, es: String): String = if (spanish.value) es else en
}
