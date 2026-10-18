package com.kilombino.pyblockwatch.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * What made the app close the last time it crashed, read from Android's own record of it
 * (ApplicationExitInfo, Android 11+): for a crash inside a native library, such as LND's, the
 * tombstone carries the abort message and the last lines the process logged, where Go writes
 * its "fatal error" or "panic". Kept so it can be copied and sent; nothing leaves the phone
 * on its own.
 */
object CrashReport {
    /**
     * Its own file, outside what a backup exports: the report carries log lines (channel points,
     * peer keys, host names) that have no business in a backup file.
     */
    private const val PREFS = "crash_report"
    private const val OLD = "pyblockwatch"

    private fun prefs(ctx: Context): android.content.SharedPreferences {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Moved here from the main settings file (before 1.1.3).
        val old = ctx.getSharedPreferences(OLD, Context.MODE_PRIVATE)
        if (old.contains("crash_last") || old.contains("crash_seen")) {
            p.edit().putLong("crash_seen", old.getLong("crash_seen", 0L)).putString("crash_last", old.getString("crash_last", null)).apply()
            old.edit().remove("crash_last").remove("crash_seen").apply()
        }
        return p
    }

    class Report(val time: Long, val text: String)

    /** A crash newer than the last one reported, once; null when there is none. */
    fun fresh(ctx: Context): Report? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val p = prefs(ctx)
        val seen = p.getLong("crash_seen", 0L)
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return null
        val info = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5) }.getOrNull() ?: return null
        val crash = info.firstOrNull {
            (it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_CRASH) && it.timestamp > seen
        } ?: return null
        val text = describe(ctx, crash)
        p.edit().putLong("crash_seen", crash.timestamp).putString("crash_last", text).apply()
        return Report(crash.timestamp, text)
    }

    /**
     * [text] with the places a server could be named blanked out: .onion names, URLs, IP
     * addresses and host:port pairs ("<host>"). A report is shared by hand; it should show
     * where the code failed, not which nodes this wallet talks to.
     */
    fun redact(text: String): String = text
        .replace(Regex("[a-z2-7]{16,56}\\.onion", RegexOption.IGNORE_CASE), "<onion>")
        .replace(Regex("\\b(?:https?|wss?|tcp|ssl)://[^\\s\"')]+", RegexOption.IGNORE_CASE), "<url>")
        .replace(Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b"), "<ip>")
        .replace(Regex("\\[?[0-9a-f]{0,4}(?::[0-9a-f]{0,4}){2,7}\\]?(?::\\d+)?", RegexOption.IGNORE_CASE)) { m -> if (m.value.count { it == ':' } >= 3) "<ip>" else m.value }
        // host:port, but not a source location such as Lnd.kt:479.
        .replace(Regex("\\b[a-z0-9.-]+\\.(?!(?:kt|java|rs|go|c|cc|cpp|h|so)\\b)[a-z]{2,}:\\d{2,5}\\b", RegexOption.IGNORE_CASE), "<host>")

    /** The last report kept, for settings. */
    fun last(ctx: Context): String? = prefs(ctx).getString("crash_last", null)

    /** The newest crash Android still has on record, described afresh (reported before or not). Blocking. */
    fun latest(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return last(ctx)
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return last(ctx)
        val crash = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5) }.getOrNull()
            ?.firstOrNull { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_CRASH }
            ?: return last(ctx)
        return describe(ctx, crash)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun describe(ctx: Context, e: ApplicationExitInfo): String {
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        val head = "Kilowallet $version · Android ${Build.VERSION.RELEASE} (${Build.MODEL}) · " +
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.timestamp)) +
            " · " + (if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) "native crash" else "crash") +
            (e.description?.let { " · $it" } ?: "")
        // The tombstone is a protobuf (system/core/debuggerd/proto/tombstone.proto): its abort
        // message (field 14), the signal (10) and the process's last log lines (18), where Go and
        // the allocator write why they stopped. Read field by field, nothing filtered out.
        val raw = runCatching { e.traceInputStream?.use { it.readBytes() } }.getOrNull() ?: ByteArray(0)
        val wanted = summarize(raw)
        // What native code wrote on stderr before stopping (Go's "fatal error"/"panic"), from the
        // run that crashed: the lines after the last process header before the crash.
        val stderr = runCatching {
            val lines = CrashLog.nativeErrors(ctx).readLines()
            val runs = lines.indices.filter { lines[it].startsWith("=== ") }
            val start = runs.lastOrNull { i -> lines[i].contains("process ${e.pid}") } ?: runs.dropLast(1).lastOrNull() ?: 0
            val end = runs.firstOrNull { it > start } ?: lines.size
            lines.subList(start, end).filter { it.isNotBlank() }.takeLast(60)
        }.getOrDefault(emptyList())
        // What the Lightning node was doing just before (its own log, not the crash itself).
        val lnd = runCatching {
            // The path Lnd.dir uses, without touching the Lnd object (which loads the Go library).
            java.io.File(ctx.noBackupFilesDir, "lnd/logs/bitcoin/mainnet/lnd.log").readLines().takeLast(30)
        }.getOrDefault(emptyList())
        // Tor's log: tor aborts on a failed check without an abort message, saying why only there.
        val tor = runCatching {
            java.io.File(ctx.noBackupFilesDir, "tor.log").takeIf { it.lastModified() >= e.timestamp - 10 * 60_000 }
                ?.readLines()?.takeLast(20).orEmpty()
        }.getOrDefault(emptyList())
        return (listOf(head) + wanted + (if (stderr.size > 1) listOf("--- stderr of the crashed run") + stderr else emptyList()) +
            (if (tor.isNotEmpty()) listOf("--- tor.log, last lines") + tor else emptyList()) +
            (if (lnd.isNotEmpty()) listOf("--- lnd.log, last lines") + lnd else emptyList()))
            .joinToString("\n").take(16_000)
    }

    /** What a tombstone says went wrong: abort message, signal, causes, the last log lines. */
    internal fun summarize(raw: ByteArray): List<String> {
        val top = runCatching { fields(raw) }.getOrDefault(emptyList())
        val abort = top.filter { it.first == 14 }.mapNotNull { (_, v) -> (v as? ByteArray)?.toString(Charsets.UTF_8) }
        val signal = top.filter { it.first == 10 }.mapNotNull { (_, v) -> (v as? ByteArray)?.let { b ->
            runCatching { fields(b).filter { it.second is ByteArray }.joinToString(" ") { (it.second as ByteArray).toString(Charsets.UTF_8) } }.getOrNull() } }
        // What the system made of it (a memory-tagging fault says use-after-free, overflow…).
        val causes = top.filter { it.first == 15 }.mapNotNull { (_, v) ->
            runCatching { (fields(v as ByteArray).firstOrNull { it.first == 1 }?.second as? ByteArray)?.toString(Charsets.UTF_8) }.getOrNull() }
        val logs = top.filter { it.first == 18 }.flatMap { (_, v) -> runCatching { logLines(v as ByteArray) }.getOrDefault(emptyList()) }
        return (abort.map { "Abort message: $it" } + signal.map { "Signal: $it" } + causes.map { "Cause: $it" } +
            (if (logs.isNotEmpty()) listOf("--- the process's last log lines") + logs.takeLast(80) else emptyList()))
            .ifEmpty { printable(raw).filter { it.contains("fatal", true) || it.contains("abort", true) || it.contains("panic", true) || it.contains("malloc", true) }.distinct().take(40) }
    }

    /** A protobuf message's top-level fields: number and value (Long for varints, ByteArray for length-delimited). */
    private fun fields(b: ByteArray): List<Pair<Int, Any>> {
        val out = ArrayList<Pair<Int, Any>>(); var i = 0
        fun varint(): Long { var r = 0L; var sh = 0; while (true) { val x = b[i++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl sh); if (x < 0x80) return r; sh += 7 } }
        while (i < b.size) {
            val key = varint(); val num = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> out += num to varint()
                1 -> { i += 8 }
                2 -> { val n = varint().toInt(); out += num to b.copyOfRange(i, i + n); i += n }
                5 -> { i += 4 }
                else -> return out
            }
        }
        return out
    }

    /** A LogBuffer's messages (field 2; a LogMessage has tag 5 and message 6), as "tag: message". */
    private fun logLines(buffer: ByteArray): List<String> = fields(buffer).filter { it.first == 2 }.mapNotNull { (_, v) ->
        val m = fields(v as ByteArray)
        val tag = m.firstOrNull { it.first == 5 }?.second as? ByteArray
        val msg = m.firstOrNull { it.first == 6 }?.second as? ByteArray ?: return@mapNotNull null
        (tag?.toString(Charsets.UTF_8)?.let { "$it: " } ?: "") + msg.toString(Charsets.UTF_8).trimEnd()
    }

    /** Runs of printable ASCII at least 12 long, as `strings` would list them. */
    private fun printable(b: ByteArray): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder()
        for (x in b) {
            val c = x.toInt() and 0xff
            if (c in 0x20..0x7e || c == 0x09) cur.append(c.toChar())
            else { if (cur.length >= 12) out += cur.toString(); cur.setLength(0) }
        }
        if (cur.length >= 12) out += cur.toString()
        return out
    }
}
