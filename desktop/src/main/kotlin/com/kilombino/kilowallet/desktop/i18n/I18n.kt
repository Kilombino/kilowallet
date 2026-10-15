package com.kilombino.kilowallet.desktop.i18n

import com.kilombino.kilowallet.desktop.Tr
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Spanish for the shared (English) screens, at run time: every text shown goes through [tr],
 * which looks it up in i18n/es.json (built from the code's own strings, see desktop/i18n).
 * Strings the code builds with values ("{0} sats each") match as templates; ones it builds by
 * joining pieces are translated piece by piece. Anything not found stays in English.
 */
object I18n {
    private class Template(val regex: Regex, val es: String, val fragment: Boolean)

    private val exact = HashMap<String, String>()
    private val templates = ArrayList<Template>()
    private val prefixes = ArrayList<Pair<String, String>>() // plain fragments that start a joined string
    private val suffixes = ArrayList<Pair<String, String>>() // and ones that end it
    private val cache = ConcurrentHashMap<String, String>()
    private val bullet = Regex("""^([•·\-–]\s+)(.+)$""", RegexOption.DOT_MATCHES_ALL)
    private val upper = HashMap<String, String>() // "BEFORE YOU TURN IT ON": the code upper-cases a mixed-case string

    /**
     * The phone's words, said for a computer: the fingerprint or screen lock is the wallet's
     * password here, and "this phone" is this computer. Applied in both languages, after
     * translating. (The wallet's own fingerprint, 73c5da0a, is "huella del monedero": untouched.)
     */
    private val desktopEn = listOf(
        "your fingerprint, face or screen lock" to "your password", "fingerprint, face or screen lock" to "password",
        "your fingerprint or PIN" to "your password", "fingerprint or PIN" to "password", "fingerprint or screen lock" to "password",
        "your fingerprint" to "your password", "without the fingerprint" to "without the password",
        "One fingerprint when you join" to "One password when you join", "one fingerprint when joining" to "one password when joining",
        "your biometrics" to "your password",
        "behind your biometrics" to "behind your password", "biometric" to "password",
        "this phone" to "this computer", "THIS phone" to "THIS computer", "the phone" to "the computer", "your phone" to "your computer",
        "another phone" to "another computer", "a new phone" to "a new computer", "two phones" to "two computers",
        "phones" to "computers", "phone" to "computer", "Phone" to "Computer", "PHONE" to "COMPUTER",
        "Android" to "Linux",
    )
    private val desktopEs = listOf(
        "tu huella, cara o bloqueo de pantalla" to "tu contraseña", "tu huella o tu PIN" to "tu contraseña", "tu huella o PIN" to "tu contraseña",
        "con tu huella" to "con tu contraseña", "tras tu biometría" to "tras tu contraseña", "tu biometría" to "tu contraseña",
        "sin la huella" to "sin la contraseña", "Una huella al unirte" to "Una contraseña al unirte", "una huella al unirte" to "una contraseña al unirte", "con la huella" to "con la contraseña", "tu huella" to "tu contraseña",
        "teléfono desbloqueado" to "ordenador desbloqueado", "ESTE teléfono" to "ESTE ordenador", "este teléfono" to "este ordenador",
        "otro teléfono" to "otro ordenador", "un teléfono nuevo" to "un ordenador nuevo", "dos teléfonos" to "dos ordenadores",
        "teléfonos" to "ordenadores", "teléfono" to "ordenador", "Teléfono" to "Ordenador", "TELÉFONO" to "ORDENADOR",
        "el móvil" to "el ordenador", "tu móvil" to "tu ordenador", "móvil" to "ordenador", "Android" to "Linux",
    )

    private fun desktopWording(s: String, es: Boolean): String {
        var out = s
        for ((a, b) in if (es) desktopEs else desktopEn) if (a in out) out = out.replace(a, b)
        return out
    }

    /** Whole sentences the desktop says differently (updates are an AppImage or .deb, checked by a signed hash, not an APK). */
    private val desktopSentences: List<Pair<String, String>> = runCatching {
        val o = JSONObject(I18n::class.java.getResourceAsStream("/i18n/desktop-en.json")!!.bufferedReader().use { it.readText() })
        o.keys().asSequence().map { it to o.getString(it) }.toList()
    }.getOrDefault(emptyList())

    init {
        runCatching {
            val o = JSONObject(I18n::class.java.getResourceAsStream("/i18n/es.json")!!.bufferedReader().use { it.readText() })
            for (en in o.keys()) {
                val es = o.getString(en)
                if ('{' in en && Regex("""\{\d+\}""").containsMatchIn(en)) {
                    val parts = en.split(Regex("""\{\d+\}"""))
                    val pattern = parts.joinToString("(.+?)") { Regex.escape(it) }
                    val frag = en.last().isWhitespace() || en.last() in "(≈:—-"
                    templates += Template(Regex("^$pattern" + if (frag) "" else "$", RegexOption.DOT_MATCHES_ALL), es, frag)
                } else {
                    exact[en] = es
                    if (en.any { it.isLowerCase() }) upper.putIfAbsent(en.uppercase(), es.uppercase())
                    if (en.length >= 3 && (en.last().isWhitespace() || en.endsWith(": ") || en.endsWith("("))) prefixes += en to es
                    if (en.length >= 3 && (en.first().isWhitespace() || en.first() in ".,)—")) suffixes += en to es
                }
            }
            prefixes.sortByDescending { it.first.length }; suffixes.sortByDescending { it.first.length }
            // Longer templates first: the most specific wins.
            templates.sortByDescending { it.regex.pattern.length }
        }
    }

    fun tr(s: String): String {
        if (s.isBlank()) return s
        val es = Tr.spanish.value
        val key = if (es) "es:$s" else "en:$s"
        cache[key]?.let { return it }
        var en = s
        for ((a, b) in desktopSentences) if (a in en) en = en.replace(a, b)
        val out = desktopWording(if (es) translate(en, 0) else en, es)
        if (cache.size > 20_000) cache.clear()
        cache[key] = out
        return out
    }

    private fun translate(s: String, depth: Int): String {
        exact[s]?.let { return it }
        upper[s]?.let { return it }
        if (depth > 4 || s.isEmpty()) return s
        for (t in templates) {
            val m = t.regex.find(s) ?: continue
            var es = t.es
            // The placeholders' values, translated themselves when they are words ("Taproot", "OPEN"…).
            m.groupValues.drop(1).forEachIndexed { i, v -> es = es.replace("{$i}", translate(v, depth + 1)) }
            return if (t.fragment) es + translate(s.substring(m.range.last + 1), depth + 1) else es
        }
        // A list item ("•  …") or pieces joined with " · ": each piece on its own.
        bullet.find(s)?.let { m ->
            return m.groupValues[1] + translate(m.groupValues[2], depth + 1)
        }
        if (" · " in s) return s.split(" · ").joinToString(" · ") { translate(it, depth + 1) }
        if ("\n\n" in s) return s.split("\n\n").joinToString("\n\n") { translate(it, depth + 1) }
        for ((en, es) in prefixes) if (s.startsWith(en)) return es + translate(s.substring(en.length), depth + 1)
        for ((en, es) in suffixes) if (s.endsWith(en)) return translate(s.substring(0, s.length - en.length), depth + 1) + es
        return s
    }
}
