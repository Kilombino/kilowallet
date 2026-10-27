package com.kilombino.pyblockwatch.ui.i18n

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's language. The code is written in English; every text shown goes through [tr],
 * which looks it up in the language's table (assets/i18n/<lang>.json, made from the code's own
 * strings). Strings the code builds with values ("{0} sats each") match as templates; ones it
 * builds by joining pieces are translated piece by piece. Anything not found stays in English.
 * By default the phone's language (English when the app doesn't have it); settings can fix one.
 */
object I18n {
    const val AUTO = "auto"
    const val ES = "es"; const val EN = "en"; const val ZH = "zh"
    /** Each named in its own language. */
    val LANGUAGES = listOf(
        ES to "Español", EN to "English", ZH to "中文", "fr" to "Français", "it" to "Italiano",
        "de" to "Deutsch", "ca" to "Català", "pt" to "Português",
    )
    private const val PREFS = "pyblockwatch"
    private const val KEY = "ui_lang"

    /** The language shown, read by every [Text]: changing it redraws the screens. */
    val lang = mutableStateOf(EN)
    /** What settings say: [AUTO] or a language's code. */
    val choice = mutableStateOf(AUTO)

    /** The phone's language when the app has it, else English. */
    fun system(): String = java.util.Locale.getDefault().language.let { l -> if (LANGUAGES.any { it.first == l }) l else EN }

    @Volatile private var app: Context? = null
    /** Where a language's table comes from: the APK's assets, or (tests) any reader. */
    @Volatile internal var loader: ((String) -> String?)? = null

    /** Once, at start: the language chosen before. */
    fun init(ctx: Context) {
        val c = ctx.applicationContext
        app = c
        loader = { l -> runCatching { c.assets.open("i18n/$l.json").bufferedReader().use { it.readText() } }.getOrNull() }
        val saved = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { l -> LANGUAGES.any { it.first == l } }
        choice.value = saved ?: AUTO
        lang.value = saved ?: system()
        preload(lang.value)
    }

    /**
     * Builds [l]'s table away from the screen: reading it (~200 KB of JSON) and setting up its
     * templates took the first frame 50–120 ms. Started at app start, it is usually ready before
     * the first text; a [tr] that comes sooner waits for it rather than build it twice.
     */
    private fun preload(l: String) {
        if (l == EN || tables.containsKey(l)) return
        Thread { table(l) }.apply { isDaemon = true; name = "i18n"; priority = Thread.NORM_PRIORITY }.start()
    }

    /** [l]: a language's code, or [AUTO] to follow the phone. */
    fun set(ctx: Context, l: String) {
        choice.value = l
        lang.value = if (l == AUTO) system() else l
        preload(lang.value)
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (l == AUTO) remove(KEY) else putString(KEY, l)
        }.apply()
    }

    /**
     * [head]: the text before the first placeholder. A string that doesn't start with it can't
     * match, so most templates are skipped without their regex, which is only compiled when a
     * string gets that far.
     */
    private class Template(val head: String, pattern: String, val out: String, val fragment: Boolean) {
        val regex by lazy { Regex(pattern, RegexOption.DOT_MATCHES_ALL) }
        val length = pattern.length
    }
    private class Table(
        val exact: Map<String, String>, val upper: Map<String, String>, val templates: List<Template>,
        val prefixes: List<Pair<String, String>>, val suffixes: List<Pair<String, String>>,
    )

    private val tables = ConcurrentHashMap<String, Table>()
    private val cache = ConcurrentHashMap<String, String>()
    private val bullet = Regex("""^([•·\-–]\s+)(.+)$""", RegexOption.DOT_MATCHES_ALL)
    private val placeholder = Regex("""\{\d+\}""")

    private fun table(l: String): Table? = tables[l] ?: synchronized(tables) { tables[l] ?: build(l) }

    private fun build(l: String): Table? {
        val o = runCatching { JSONObject(loader?.invoke(l) ?: return null) }.getOrNull() ?: return null
        val exact = HashMap<String, String>(); val upper = HashMap<String, String>()
        val templates = ArrayList<Template>(); val prefixes = ArrayList<Pair<String, String>>(); val suffixes = ArrayList<Pair<String, String>>()
        for (en in o.keys()) {
            val out = o.getString(en)
            if (placeholder.containsMatchIn(en)) {
                val parts = en.split(placeholder)
                val pattern = parts.joinToString("(.+?)") { Regex.escape(it) }
                val frag = en.last().isWhitespace() || en.last() in "(≈:—-"
                templates += Template(parts.first(), "^$pattern" + if (frag) "" else "$", out, frag)
                // "EXACT COIN · 12 345 SATS": a section label upper-cases a template's text too.
                if (en.any { it.isLowerCase() }) {
                    val up = parts.map { it.uppercase() }
                    templates += Template(up.first(), "^" + up.joinToString("(.+?)") { Regex.escape(it) } + if (frag) "" else "$",
                        out.uppercase(), frag)
                }
            } else {
                exact[en] = out
                // "BEFORE YOU TURN IT ON": the code upper-cases a mixed-case string.
                if (en.any { it.isLowerCase() }) upper.putIfAbsent(en.uppercase(), out.uppercase())
                if (en.length >= 3 && (en.last().isWhitespace() || en.endsWith(": ") || en.endsWith("("))) prefixes += en to out
                if (en.length >= 3 && (en.first().isWhitespace() || en.first() in ".,)—")) suffixes += en to out
            }
        }
        prefixes.sortByDescending { it.first.length }; suffixes.sortByDescending { it.first.length }
        templates.sortByDescending { it.length } // the most specific first
        return Table(exact, upper, templates, prefixes, suffixes).also { tables[l] = it }
    }

    /** One piece of a joined string, its surrounding spaces kept ("m/84'/0'/0'/0/1  ·  receive"). */
    private fun piece(t: Table, p: String, depth: Int): String {
        val core = p.trim()
        if (core.isEmpty()) return p
        val lead = p.substring(0, p.indexOf(core)); val trail = p.substring(p.indexOf(core) + core.length)
        return lead + translate(t, core, depth + 1) + trail
    }

    /** [s] in the app's language (as is in English, or when there is no translation). */
    fun tr(s: String): String {
        val l = lang.value
        if (l == EN || s.isBlank()) return s
        val key = "$l:$s"
        cache[key]?.let { return it }
        val t = table(l) ?: return s
        val out = translate(t, s, 0)
        if (cache.size > 20_000) cache.clear()
        cache[key] = out
        return out
    }

    private fun translate(t: Table, s: String, depth: Int): String {
        t.exact[s]?.let { return it }
        t.upper[s]?.let { return it }
        if (depth > 4 || s.isEmpty()) return s
        for (tp in t.templates) {
            if (!s.startsWith(tp.head)) continue
            val m = tp.regex.find(s) ?: continue
            // A template that starts with its value ("{0} outputs each (v4)") must not swallow the
            // pieces before it in a line joined with " · ": those are translated one by one below.
            if (tp.head.isEmpty() && " · " in m.groupValues[1]) continue
            var out = tp.out
            // The placeholders' values, translated themselves when they are words ("Taproot", "OPEN"…).
            m.groupValues.drop(1).forEachIndexed { i, v -> out = out.replace("{$i}", translate(t, v, depth + 1)) }
            return if (tp.fragment) out + translate(t, s.substring(m.range.last + 1), depth + 1) else out
        }
        // A list item ("•  …"), pieces joined with " · ", or paragraphs: each piece on its own.
        bullet.find(s)?.let { m -> return m.groupValues[1] + translate(t, m.groupValues[2], depth + 1) }
        if (" · " in s) return s.split(" · ").joinToString(" · ") { piece(t, it, depth) }
        if ("\n\n" in s) return s.split("\n\n").joinToString("\n\n") { piece(t, it, depth) }
        for ((en, out) in t.prefixes) if (s.startsWith(en)) return out + translate(t, s.substring(en.length), depth + 1)
        for ((en, out) in t.suffixes) if (s.endsWith(en)) return translate(t, s.substring(0, s.length - en.length), depth + 1) + out
        return s
    }
}
