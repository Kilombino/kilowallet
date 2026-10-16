package com.kilombino.kilowallet.desktop.i18n

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import com.kilombino.pyblockwatch.ui.i18n.I18n as AppI18n

/**
 * The desktop's text: the app's own translation (the same language setting and tables as the
 * phone, see com.kilombino.pyblockwatch.ui.i18n.I18n) with what the desktop says differently
 * on top of it:
 *  - the desktop's own strings (unlock screen, QR reader, tray), in i18n-desktop/<lang>.json;
 *  - whole sentences said for a desktop (updates are an AppImage or .deb checked by a signed
 *    hash, not an APK), in i18n/desktop-en.json, swapped before translating;
 *  - the phone's words for a computer: "this phone" is this computer, the fingerprint or
 *    screen lock is the wallet's password, in every language, after translating. (The
 *    wallet's own fingerprint, 73c5da0a, keeps its name.)
 */
object I18n {
    private val cache = ConcurrentHashMap<String, String>()

    private val desktopSentences: List<Pair<String, String>> = runCatching {
        val o = JSONObject(I18n::class.java.getResourceAsStream("/i18n/desktop-en.json")!!.bufferedReader().use { it.readText() })
        o.keys().asSequence().map { it to o.getString(it) }.toList()
    }.getOrDefault(emptyList())

    private val desktopTables = ConcurrentHashMap<String, Map<String, String>>()

    private fun desktopTable(lang: String): Map<String, String> = desktopTables.getOrPut(lang) {
        runCatching {
            val o = JSONObject(I18n::class.java.getResourceAsStream("/i18n-desktop/$lang.json")!!.bufferedReader().use { it.readText() })
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
    }

    private val wording: Map<String, List<Pair<String, String>>> = mapOf(
        AppI18n.EN to listOf(
            "your fingerprint, face or screen lock" to "your password", "fingerprint, face or screen lock" to "password",
            "your fingerprint or PIN" to "your password", "fingerprint or PIN" to "password", "fingerprint or screen lock" to "password",
            "your fingerprint" to "your password", "without the fingerprint" to "without the password",
            "One fingerprint when you join" to "One password when you join", "one fingerprint when joining" to "one password when joining",
            "your biometrics" to "your password", "biometric" to "password",
            "this phone" to "this computer", "THIS phone" to "THIS computer", "the phone" to "the computer", "your phone" to "your computer",
            "another phone" to "another computer", "a new phone" to "a new computer", "two phones" to "two computers",
            "phones" to "computers", "phone" to "computer", "Phone" to "Computer", "PHONE" to "COMPUTER", "Android" to "your system",
        ),
        AppI18n.ES to listOf(
            "tu huella, cara o bloqueo de pantalla" to "tu contraseña", "tu huella o tu PIN" to "tu contraseña", "tu huella o PIN" to "tu contraseña",
            "con tu huella" to "con tu contraseña", "tras tu biometría" to "tras tu contraseña", "tu biometría" to "tu contraseña",
            "sin la huella" to "sin la contraseña", "con la huella" to "con la contraseña", "tu huella" to "tu contraseña",
            "Una huella al unirte" to "Una contraseña al unirte", "una huella al unirte" to "una contraseña al unirte",
            "teléfono desbloqueado" to "ordenador desbloqueado", "ESTE teléfono" to "ESTE ordenador", "este teléfono" to "este ordenador",
            "otro teléfono" to "otro ordenador", "un teléfono nuevo" to "un ordenador nuevo", "dos teléfonos" to "dos ordenadores",
            "teléfonos" to "ordenadores", "teléfono" to "ordenador", "Teléfono" to "Ordenador", "TELÉFONO" to "ORDENADOR",
            "el móvil" to "el ordenador", "tu móvil" to "tu ordenador", "móvil" to "ordenador", "Android" to "tu sistema",
        ),
        AppI18n.ZH to listOf(
            "你的指纹" to "你的密码", "用指纹" to "用密码", "指纹解锁" to "密码解锁",
            "这部手机" to "这台电脑", "此手机" to "此电脑", "新手机" to "新电脑", "手机" to "电脑", "Android" to "系统",
        ),
        "fr" to listOf(
            "ton empreinte digitale" to "ton mot de passe", "ton empreinte" to "ton mot de passe", "empreinte digitale" to "mot de passe",
            "ce téléphone" to "cet ordinateur", "un nouveau téléphone" to "un nouvel ordinateur", "téléphones" to "ordinateurs",
            "téléphone" to "ordinateur", "Téléphone" to "Ordinateur", "TÉLÉPHONE" to "ORDINATEUR",
        ),
        "it" to listOf(
            "la tua impronta digitale" to "la tua password", "la tua impronta" to "la tua password", "impronta digitale" to "password",
            "questo telefono" to "questo computer", "un nuovo telefono" to "un nuovo computer", "telefoni" to "computer",
            "telefono" to "computer", "Telefono" to "Computer", "TELEFONO" to "COMPUTER",
        ),
        "de" to listOf(
            "deinem Fingerabdruck" to "deinem Passwort", "deinen Fingerabdruck" to "dein Passwort", "dein Fingerabdruck" to "dein Passwort",
            "Fingerabdruck" to "Passwort", "diesem Handy" to "diesem Computer", "dieses Handy" to "dieser Computer",
            "Handys" to "Computer", "Handy" to "Computer", "Telefon" to "Computer", "HANDY" to "COMPUTER",
        ),
        "ca" to listOf(
            "la teva empremta" to "la teva contrasenya", "amb l'empremta" to "amb la contrasenya", "empremta" to "contrasenya",
            "aquest telèfon" to "aquest ordinador", "un telèfon nou" to "un ordinador nou", "telèfons" to "ordinadors",
            "telèfon" to "ordinador", "mòbil" to "ordinador", "Telèfon" to "Ordinador", "TELÈFON" to "ORDINADOR",
        ),
        "pt" to listOf(
            "sua impressão digital" to "sua senha", "impressão digital" to "senha", "sua digital" to "sua senha",
            "este celular" to "este computador", "um celular novo" to "um computador novo", "celulares" to "computadores",
            "celular" to "computador", "Celular" to "Computador", "CELULAR" to "COMPUTADOR", "telefone" to "computador",
        ),
    )

    private fun desktopWording(s: String, lang: String): String {
        var out = s
        for ((a, b) in wording[lang].orEmpty()) if (a in out) out = out.replace(a, b)
        return out
    }

    /** [s] (written in English) as the desktop shows it in the chosen language. */
    fun tr(s: String): String {
        if (s.isBlank()) return s
        val lang = AppI18n.lang.value
        val key = "$lang:$s"
        cache[key]?.let { return it }
        var en = s
        for ((a, b) in desktopSentences) if (a in en) en = en.replace(a, b)
        val translated = if (lang == AppI18n.EN) en else desktopTable(lang)[en] ?: AppI18n.tr(en)
        val out = desktopWording(translated, lang)
        if (cache.size > 20_000) cache.clear()
        cache[key] = out
        return out
    }
}
