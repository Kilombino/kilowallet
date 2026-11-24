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
 * aside: at most [MAX_FILE] bytes each and [MAX_AGE_MS] of history, so it never grows past about
 * 1.5 MB. Every line goes through [Scrub] here, not at the callers: whatever a caller hands over,
 * words, keys, invoices, server names and whole addresses or txids never reach the file. Writing
 * happens on one background thread, so a caller on the main thread never waits for the disk.
 * Nothing leaves the phone unless the user copies or shares it.
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
    /** When the current file was started (its first line), cached so a write never reads the file. */
    private var currentStart: Long? = null
    @Volatile private var warnedEarly = false

    /** One thread writes, in order; callers only hand the line over. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "diag-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    fun init(ctx: Context) {
        initDir(File(ctx.applicationContext.noBackupFilesDir, "diag"))
        // A copy left from the last SHARE LOG is not kept around.
        runCatching { File(ctx.applicationContext.cacheDir, "diag-export").listFiles()?.forEach { it.delete() } }
    }

    /** Where the files go (tests use a temporary folder). */
    fun initDir(d: File) {
        d.mkdirs()
        synchronized(lock) { dir = d; currentStart = null; prune(d) }
    }

    private fun current(d: File) = File(d, "diag-0.log")
    private fun previous(d: File) = File(d, "diag-1.log")

    fun i(tag: String, msg: String) = write(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = write(Level.WARN, tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        write(Level.ERROR, tag, if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message ?: ""}")

    fun write(level: Level, tag: String, msg: String) {
        val d = dir
        if (d == null) {
            if (!warnedEarly) { warnedEarly = true; runCatching { android.util.Log.w("DiagLog", "write before init: line dropped") } }
            return
        }
        val t = now()
        runCatching { writer.execute { append(d, t, level, tag, msg) } }
    }

    private fun append(d: File, t: Long, level: Level, tag: String, msg: String) {
        runCatching {
            val clean = Scrub.clean(msg.replace("\r", "").replace("\n", " ⏎ ")).take(MAX_LINE)
            val line = "${iso(t)} ${level.c} ${Scrub.clean(tag).take(24)}: $clean\n"
            val bytes = line.toByteArray(Charsets.UTF_8)
            synchronized(lock) {
                if (dir != d) return@synchronized
                val f = current(d)
                if (f.length() + bytes.size > MAX_FILE || tooOld(f, t)) rotate(d)
                java.io.FileOutputStream(f, true).use { it.write(bytes) }
                if (currentStart == null) currentStart = t
            }
        }
    }

    /** Waits until every line handed over so far is on disk. */
    fun flush() {
        runCatching { writer.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS) }
    }

    /** The current file was started more than half the history ago: start a new one. */
    private fun tooOld(f: File, t: Long): Boolean {
        if (!f.exists() || f.length() == 0L) { currentStart = null; return false }
        val start = currentStart ?: (runCatching { f.bufferedReader().use { it.readLine() } }.getOrNull()
            ?.let { parseIso(it.substringBefore(' ')) } ?: t).also { currentStart = it }
        return t - start > MAX_AGE_MS / 2
    }

    private fun rotate(d: File) {
        val p = previous(d); p.delete()
        val c = current(d)
        // If the move fails, the current file is emptied rather than left to grow.
        if (!c.renameTo(p)) runCatching { c.writeText("") }
        currentStart = null
    }

    /** Drops the older file once all of it is past the history kept. */
    private fun prune(d: File) {
        val p = previous(d)
        if (p.exists() && now() - p.lastModified() > MAX_AGE_MS) p.delete()
    }

    /** Everything kept from the last [MAX_AGE_MS], oldest first. */
    fun text(): String {
        flush()
        synchronized(lock) {
            val d = dir ?: return ""
            prune(d)
            val since = now() - MAX_AGE_MS
            return listOf(previous(d), current(d)).filter { it.exists() }.joinToString("") { f ->
                f.readLines().filter { l ->
                    val t = parseIso(l.substringBefore(' '))
                    t == null || t >= since
                }.joinToString("") { "$it\n" }
            }
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
        flush()
        synchronized(lock) {
            val d = dir ?: return
            current(d).delete(); previous(d).delete(); currentStart = null
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
            if (crash != null) {
                append("\n--- last crash report\n")
                crash.lines().forEach { append(Scrub.clean(CrashReport.redact(it))).append('\n') }
            }
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
    internal fun parseIso(s: String): Long? =
        if (s.length != 20 || s[4] != '-' || s[10] != 'T') null else runCatching { fmt.get()!!.parse(s)?.time }.getOrNull()
}

/**
 * What never goes into the diagnostic log, whoever writes the line: mnemonic words (six or more
 * in a row), passphrases and passwords, private and extended keys, descriptors' keys and origins,
 * macaroons, Lightning invoices, offers and preimages, nsec keys, credentials, server and node
 * names and IP addresses, onion addresses, Lightning addresses.
 *
 * Addresses, txids and other 64-hex values are cut to their first 6 and last 2 characters: enough
 * to tell two of them apart within one log. With the times and amounts the log also holds, they
 * can still point at a transaction for someone who looks it up: the panel says to share the log
 * only with someone you trust.
 */
object Scrub {
    private val words: Set<String> by lazy { com.kilombino.pyblockwatch.crypto.Bip39Wordlist.WORDS.toHashSet() }
    private val token = Regex("[A-Za-z]+")

    private const val B58 = "[1-9A-HJ-NP-Za-km-z]"
    private const val TLDS = "com|net|org|io|xyz|info|me|dev|app|cloud|online|site|tech|space|live|link|top|club|pro|biz|" +
        "ninja|social|network|systems|host|server|local|lan|home|internal|arpa|onion|i2p|duckdns|ddns|dyndns|" +
        "de|es|fr|uk|nl|ch|pl|cc|co|eu|us|ca|br|ar|mx|ru|cn|jp|kr|in|au|nz|se|no|fi|dk|be|at|it|pt|cz|sk|hu|ro|bg|gr|tr|il|za|ng|ke|is|li|lu|ie|cl|pe|ve|uy"

    private val rules: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // Credentials first, before anything cuts them up.
        // "secret: …": everything after it on the line goes (a passphrase can have spaces in it).
        Regex("(?i)(?<![a-z])((?:rpc[_-]?)?(?:pass(?:word|phrase)?|pwd|mnemonic|seed|words|macaroon|secret|private[ _-]?key|privkey|" +
            "preimage|nsec|wif|token|cookie|auth(?:orization)?))(\"?\\s*[=:]\\s*)\\S.*") to
            { m -> m.groupValues[1] + m.groupValues[2] + "<hidden>" },
        Regex("(?i)\\b(basic|bearer)\\s+\\S+") to { m -> m.groupValues[1] + " <hidden>" },
        // A key-like word glued to a hex value (seed_…, privkey0x…, entropy …).
        Regex("(?i)(seed|priv(?:ate)?[_ -]?key|privkey|secret|entropy)[_\\s:=-]*(?:0x)?[0-9a-f]{32,}") to
            { m -> m.groupValues[1] + " <hidden>" },
        // URLs (with any user:password@ in them), onion and Lightning addresses, IPs, host names.
        Regex("(?i)\\b(?:https?|wss?|tcp|ssl|electrum|bitcoin|lightning|nostr)://[^\\s\"')]+") to { _ -> "<url>" },
        Regex("(?i)[a-z2-7]{16,56}\\.onion(?::\\d+)?") to { _ -> "<onion>" },
        Regex("(?i)\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b") to { _ -> "<lnaddress>" },
        Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b") to { _ -> "<ip>" },
        Regex("(?i)\\[?(?<![0-9a-z:])[0-9a-f]{0,4}(?::[0-9a-f]{0,4}){2,7}\\]?(?::\\d+)?(?![0-9a-z:])") to
            { m -> if (m.value.count { it == ':' } >= 3 && m.value.any { it.isLetterOrDigit() }) "<ip>" else m.value },
        // A bare host name, but not a dotted class or package name (java.net.UnknownHostException).
        Regex("(?i)(?<![\\w.])(?:[a-z0-9-]+\\.)+(?:$TLDS)(?::\\d{1,5})?\\b(?!\\.[a-z0-9])") to { _ -> "<host>" },
        Regex("(?i)\\b[a-z0-9.-]+\\.(?!(?:kt|java|rs|go|c|cc|cpp|h|so|py|js|json|txt|log)\\b)[a-z]{2,}:\\d{2,5}\\b") to { _ -> "<host>" },
        // Descriptor origins: the master fingerprint and path tell wallets apart.
        Regex("(?i)\\[[0-9a-f]{8}(?:/[0-9]+[h']?)*\\]") to { _ -> "[<origin>]" },
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
        // Hex anywhere (also after 0x or glued to a word): 32–63 = entropy or a key part, hidden;
        // 64–66 = a txid, hash or public key, cut; longer = a blob (raw tx, macaroon). A 0x-prefixed
        // 64-hex is a key more often than a txid: hidden.
        Regex("(?i)(0x)?(?<![0-9a-f])[0-9a-f]{32,}(?![0-9a-f])") to { m ->
            val hex = m.value.removePrefix("0x").removePrefix("0X")
            when {
                hex.length in 64..66 && m.groupValues[1].isEmpty() -> cut(hex)
                else -> "<hex ${hex.length}>"
            }
        },
        // Addresses: bech32/bech32m on any of our networks, Ark and silent payments (long and
        // static: hidden whole), then base58 (P2PKH/P2SH, testnet).
        Regex("(?i)\\b(?:bc|tb|bcrt|t?ark|t?sp)1[02-9ac-hj-np-z]{8,}\\b") to { m -> if (m.value.length > 90) "<address>" else cut(m.value) },
        Regex("\\b[123mn]$B58{25,34}\\b") to { m -> if (m.value.any { it.isDigit() } && m.value.any { it.isUpperCase() }) cut(m.value) else m.value },
    )

    private fun cut(s: String) = if (s.length <= 10) s else s.take(6) + "…" + s.takeLast(2)

    fun clean(s: String): String {
        var t = s
        for ((re, f) in rules) t = re.replace(t, f)
        return hideWordRuns(t)
    }

    /**
     * Six or more BIP39 words close together: part of a mnemonic, hidden. Whatever lies between
     * them but letters (numbers, commas, quotes, ⏎) doesn't break a run, and neither does one
     * other word among them (a typo, a label).
     */
    private fun hideWordRuns(s: String): String {
        val m = token.findAll(s).toList()
        if (m.size < 6) return s
        val isWord = BooleanArray(m.size) { m[it].value.lowercase() in words }
        val hide = ArrayList<IntRange>()
        var i = 0
        while (i < m.size) {
            if (!isWord[i]) { i++; continue }
            var j = i; var count = 1; var last = i; var gapUsed = false
            while (j + 1 < m.size) {
                val n = j + 1
                if (isWord[n]) { count++; last = n; j = n }
                else if (!gapUsed && n + 1 < m.size && isWord[n + 1]) { gapUsed = true; j = n }
                else break
            }
            if (count >= 6) { hide += m[i].range.first..m[last].range.last; i = last + 1 } else i++
        }
        if (hide.isEmpty()) return s
        val out = StringBuilder(s)
        for (r in hide.reversed()) out.replace(r.first, r.last + 1, "<words>")
        return out.toString()
    }
}
