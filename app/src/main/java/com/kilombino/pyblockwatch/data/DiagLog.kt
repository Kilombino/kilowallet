package com.kilombino.pyblockwatch.data

import android.content.Context
import android.os.Build
import java.io.File

/**
 * The app's diagnostic log, for Settings › Diagnostics (COPY / SHARE LOG): what went on lately
 * across the wallet, kept on the phone so a problem seen after the fact (a CoinJoin that confirmed
 * oddly, a node that stopped answering, a payment that failed) can still be looked at.
 *
 * Lines go to two files in the app's no-backup folder, the newest first filled up and then moved
 * aside: at most [MAX_FILE] bytes each and about [MAX_AGE_MS] of history, so it never grows past
 * about 1.5 MB. Every line goes through [Scrub] here, not at the callers: whatever a caller hands
 * over, words, keys, invoices and whole addresses or txids never reach the file. Nothing leaves
 * the phone unless the user copies or shares it.
 */
object DiagLog {
    enum class Level(val c: Char) { INFO('I'), WARN('W'), ERROR('E') }

    const val MAX_FILE = 750_000L
    const val MAX_AGE_MS = 5L * 24 * 3600 * 1000
    private const val MAX_LINE = 2_000

    @Volatile private var dir: File? = null
    /** The clock, for tests. */
    @Volatile var now: () -> Long = { System.currentTimeMillis() }
    private val lock = Any()

    fun init(ctx: Context) = initDir(File(ctx.applicationContext.noBackupFilesDir, "diag"))

    /** Where the files go (tests use a temporary folder). */
    fun initDir(d: File) {
        d.mkdirs()
        synchronized(lock) { dir = d; prune(d) }
    }

    private fun current(d: File) = File(d, "diag-0.log")
    private fun previous(d: File) = File(d, "diag-1.log")

    fun i(tag: String, msg: String) = write(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = write(Level.WARN, tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        write(Level.ERROR, tag, if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message ?: ""}")

    fun write(level: Level, tag: String, msg: String) {
        val d = dir ?: return
        val clean = Scrub.clean(msg.replace("\r", "").replace("\n", " ⏎ ")).take(MAX_LINE)
        val line = "${iso(now())} ${level.c} ${tag.take(24)}: $clean\n"
        runCatching {
            synchronized(lock) {
                val f = current(d)
                if (f.length() + line.length > MAX_FILE || tooOld(f)) rotate(d)
                f.appendText(line)
            }
        }
    }

    /** The current file was started more than half the history ago: start a new one. */
    private fun tooOld(f: File): Boolean {
        if (!f.exists() || f.length() == 0L) return false
        val first = runCatching { f.bufferedReader().use { it.readLine() } }.getOrNull() ?: return false
        val t = parseIso(first.substringBefore(' ')) ?: return false
        return now() - t > MAX_AGE_MS / 2
    }

    private fun rotate(d: File) {
        val p = previous(d); p.delete()
        current(d).renameTo(p)
    }

    /** Drops the older file once all of it is past the history kept. */
    private fun prune(d: File) {
        val p = previous(d)
        if (p.exists() && now() - p.lastModified() > MAX_AGE_MS) p.delete()
    }

    /** Everything kept, oldest first. */
    fun text(): String {
        synchronized(lock) {
            val d = dir ?: return ""
            prune(d)
            return listOf(previous(d), current(d)).filter { it.exists() }.joinToString("") { it.readText() }
        }
    }

    /** The newest [maxChars] of [text], cut at a line. */
    fun tail(maxChars: Int): String {
        val t = text()
        if (t.length <= maxChars) return t
        val cut = t.substring(t.length - maxChars)
        return cut.substringAfter('\n', cut)
    }

    fun clear() {
        synchronized(lock) {
            val d = dir ?: return
            current(d).delete(); previous(d).delete()
        }
    }

    /**
     * What to copy or share: a header (app and Android versions, device model, what the wallet
     * talks to: no identifiers), the log, then the last lines of Tor's log and the last crash
     * report if there is one. All of it through [Scrub].
     */
    fun export(ctx: Context, backends: String, maxChars: Int = Int.MAX_VALUE): String {
        val app = ctx.applicationContext
        val version = runCatching {
            val pi = app.packageManager.getPackageInfo(app.packageName, 0)
            "${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()})"
        }.getOrDefault("?")
        val head = header(version, Build.VERSION.RELEASE ?: "?", Build.VERSION.SDK_INT, "${Build.MANUFACTURER} ${Build.MODEL}", backends)
        val tor = runCatching { File(app.noBackupFilesDir, "tor.log").takeIf { it.exists() }?.readLines()?.takeLast(40).orEmpty() }
            .getOrDefault(emptyList())
        val crash = runCatching { CrashReport.last(app) }.getOrNull()
        val extra = buildString {
            if (tor.isNotEmpty()) { append("\n--- tor.log, last lines\n"); tor.forEach { append(Scrub.clean(it)).append('\n') } }
            if (crash != null) { append("\n--- last crash report\n"); append(Scrub.clean(CrashReport.redact(crash))).append('\n') }
        }
        val room = (maxChars.toLong() - head.length - extra.length - 32).coerceAtLeast(4_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return head + "\n--- log (UTC)\n" + tail(room) + extra
    }

    internal fun header(version: String, android: String, sdk: Int, device: String, backends: String): String =
        Scrub.clean("Kilowallet $version · Android $android (SDK $sdk) · $device · $backends · exported ${iso(now())}")

    private val fmt = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    }
    internal fun iso(t: Long): String = fmt.get()!!.format(java.util.Date(t))
    internal fun parseIso(s: String): Long? = runCatching { fmt.get()!!.parse(s)?.time }.getOrNull()
}

/**
 * What never goes into the diagnostic log, whoever writes the line: mnemonic words, passphrases,
 * private and extended keys, descriptors' keys, macaroons, Lightning invoices, offers and
 * preimages, nsec keys. Addresses and txids (and other 64-hex values) are cut to their first 8
 * and last 4 characters, enough to match them by eye, not to look them up.
 */
object Scrub {
    private val words: Set<String> by lazy { com.kilombino.pyblockwatch.crypto.Bip39Wordlist.WORDS.toHashSet() }

    private val B58 = "[1-9A-HJ-NP-Za-km-z]"
    private val rules: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // "secret: …": everything after it on the line goes (a passphrase can have spaces in it).
        Regex("(?i)\\b(pass(?:word|phrase)?|pwd|mnemonic|seed|words|macaroon|secret|private[ _-]?key|privkey|preimage|nsec|wif)(\\s*[=:]\\s*)\\S.*") to
            { m -> m.groupValues[1] + m.groupValues[2] + "<hidden>" },
        // Extended keys (private first): xprv/tprv/yprv/zprv…, xpub/ypub/zpub/tpub/vpub/upub…
        Regex("\\b[xyztuvYZUV]prv$B58{100,112}\\b") to { _ -> "<xprv>" },
        Regex("\\b[xyztuvYZUV]pub$B58{100,112}\\b") to { _ -> "<xpub>" },
        Regex("(?i)\\bnsec1[02-9ac-hj-np-z]{50,}\\b") to { _ -> "<nsec>" },
        // Lightning: invoices (BOLT11, BOLT12 lni), offers (lno), requests (lnr), LNURL.
        Regex("(?i)\\bln(?:bc|tb|bcrt|xbt|sb)[0-9a-z]{20,}\\b") to { _ -> "<invoice>" },
        Regex("(?i)\\bln[ior]1[0-9a-z]{20,}\\b") to { _ -> "<bolt12>" },
        Regex("(?i)\\blnurl1?[02-9ac-hj-np-z]{20,}\\b") to { _ -> "<lnurl>" },
        // WIF private keys (51–52 base58 starting 5, K, L, c, 9).
        Regex("\\b[5KLc9]$B58{50,51}\\b") to { _ -> "<wif>" },
        // Long base64 blobs (macaroons, PSBTs, raw keys).
        Regex("[A-Za-z0-9+/]{120,}={0,2}") to { _ -> "<blob>" },
        // Hex: longer than a txid or a public key = a blob (raw tx, macaroon); a txid, hash, key or
        // compressed public key is cut.
        Regex("(?i)\\b[0-9a-f]{67,}\\b") to { m -> "<hex ${m.value.length}>" },
        Regex("(?i)\\b[0-9a-f]{64,66}\\b") to { m -> cut(m.value) },
        // Addresses: bech32/bech32m on any of our networks, then base58 (P2PKH/P2SH, testnet).
        Regex("(?i)\\b(?:bc|tb|bcrt)1[02-9ac-hj-np-z]{8,87}\\b") to { m -> cut(m.value) },
        Regex("\\b[123mn]$B58{25,34}\\b") to { m -> if (m.value.any { it.isDigit() } && m.value.any { it.isUpperCase() }) cut(m.value) else m.value },
    )

    private fun cut(s: String) = if (s.length <= 14) s else s.take(8) + "…" + s.takeLast(4)

    fun clean(s: String): String {
        var t = s
        for ((re, f) in rules) t = re.replace(t, f)
        return hideWordRuns(t)
    }

    /** Twelve or more BIP39 words in a row (whatever lies between them but letters): a mnemonic, hidden. */
    private fun hideWordRuns(s: String): String {
        val m = Regex("[A-Za-z]+").findAll(s).toList()
        if (m.size < 12) return s
        val hide = ArrayList<IntRange>()
        var start = -1
        for (i in m.indices) {
            // Numbers and punctuation between them ("1. abandon 2. ability") don't break a run.
            if (m[i].value.lowercase() in words) { if (start < 0) start = i }
            else {
                if (start >= 0 && i - start >= 12) hide += m[start].range.first..m[i - 1].range.last
                start = -1
            }
        }
        if (start >= 0 && m.size - start >= 12) hide += m[start].range.first..m.last().range.last
        if (hide.isEmpty()) return s
        val out = StringBuilder(s)
        for (r in hide.reversed()) out.replace(r.first, r.last + 1, "<words>")
        return out.toString()
    }
}
