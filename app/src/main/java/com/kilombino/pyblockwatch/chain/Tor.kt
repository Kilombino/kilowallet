package com.kilombino.pyblockwatch.chain

import android.content.Context

/**
 * Tor inside the app: Arti, the Tor Project's Tor in Rust (tor-engine/ENGINE), shipped as the
 * program libarti.so and run as a child process, only when a connection needs it (a node
 * reached by .onion, Lightning). While it runs, its SOCKS port carries those connections.
 */
object Tor {
    @Volatile private var proc: Process? = null
    @Volatile private var socksPort = -1
    @Volatile private var ready = false
    /** Arti's last line worth showing ("Bootstrapped 45%…", an error): why it is not up yet, or why it stopped. */
    @Volatile var lastLine: String = ""
        private set
    private val lock = Object()

    /** Arti ships for arm64 only (like the Ark engine): on another processor a .onion can't be reached. */
    @Volatile var available: Boolean = false
        private set
    @Volatile private var exe: java.io.File? = null

    /**
     * The SOCKS proxy when Tor is already running, else null: for requests that go through Tor
     * when it is on (so they don't wait for it to start), and directly otherwise.
     */
    fun proxyIfRunning(): java.net.Proxy? = if (running) proxy(0) else null

    /** Tor is up: started for something else (a node reached by .onion) and its SOCKS port open. */
    val running: Boolean get() = ready && proc?.isAlive == true

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(lock) {
            if (proc?.isAlive == true || !available) return
            val bin = exe ?: return
            val dir = java.io.File(app.noBackupFilesDir, "arti").apply { mkdirs() }
            val state = java.io.File(dir, "state")
            // Whatever the check above missed, Arti's own word settles it: start from a clean state.
            if (stateCorrupted) { state.deleteRecursively(); stateCorrupted = false }
            repairState(state)
            // A port of our own: Arti is told exactly where to listen, so there is nothing to read back.
            val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val conf = java.io.File(dir, "arti.toml")
            conf.writeText(config(dir, port))
            val log = logFile(app)
            if (log.length() > 256_000) runCatching { log.writeText(log.readText().takeLast(32_000)) }
            ready = false; socksPort = port
            val p = ProcessBuilder(bin.path, "proxy", "-c", conf.path)
                .directory(dir).redirectErrorStream(true)
                .apply { environment()["HOME"] = dir.path }
                .start()
            proc = p
            Thread {
                runCatching {
                    java.io.FileWriter(log, true).use { out ->
                        p.inputStream.bufferedReader().forEachLine { line ->
                            out.write(line); out.write("\n"); out.flush()
                            if ("corrupted data in persistent state" in line) stateCorrupted = true
                            if (line.isNotBlank()) lastLine = line.substringAfter(" INFO ").substringAfter(" WARN ").substringAfter(" ERROR ").trim().take(160)
                            // Arti says this once it can carry traffic: from then on the SOCKS port works.
                            if ("Sufficiently bootstrapped" in line || "SOCKS now functional" in line) {
                                ready = true; synchronized(lock) { lock.notifyAll() }
                            }
                        }
                    }
                }
                ready = false; synchronized(lock) { lock.notifyAll() }
            }.apply { isDaemon = true; name = "arti" }.start()
        }
    }

    /**
     * Arti refuses to start on a state file it can't read ("corrupted data in persistent state"),
     * as a guards.json left empty when the app was killed mid-write: it stays down for good. Such a
     * file only holds what Arti learns again on its own (guards, timings), so an empty or unreadable
     * one is deleted before starting.
     */
    internal fun repairState(state: java.io.File) {
        state.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.forEach { f ->
            // Strictly an object or an array: a file of zero bytes (what a cut-short write often
            // leaves) reads as a bare "literal" to Android's lenient JSONTokener.
            val text = runCatching { f.readText().trim() }.getOrDefault("")
            val ok = runCatching {
                when (text.firstOrNull()) { '{' -> { org.json.JSONObject(text); true }; '[' -> { org.json.JSONArray(text); true }; else -> false }
            }.getOrDefault(false)
            if (!ok) f.delete()
        }
    }

    /** Arti said its state was corrupted on the last run: the whole state goes before the next start. */
    @Volatile private var stateCorrupted = false

    /** Arti's settings: only a local SOCKS port, .onion addresses allowed, its files in [dir]. */
    internal fun config(dir: java.io.File, port: Int): String = buildString {
        appendLine("[proxy]")
        appendLine("socks_listen = \"127.0.0.1:$port\"")
        appendLine("dns_listen = 0")
        appendLine("[address_filter]")
        appendLine("allow_onion_addrs = true")
        appendLine("[storage]")
        appendLine("cache_dir = \"${java.io.File(dir, "cache").path}\"")
        appendLine("state_dir = \"${java.io.File(dir, "state").path}\"")
        // The app's private folder is already its own: Android's sandbox, not Unix permissions
        // up the path (which Arti would otherwise refuse), keeps others out.
        appendLine("[storage.permissions]")
        appendLine("dangerously_trust_everyone = true")
        appendLine("[logging]")
        appendLine("console = \"info\"")
        appendLine("log_sensitive_information = false")
    }

    /** Tor's own log, kept for crash reports: what Arti printed, and why it stopped if it did. */
    fun logFile(ctx: Context) = java.io.File(ctx.applicationContext.noBackupFilesDir, "tor.log")

    /** The end of [logFile], to show and copy: no addresses or keys in it (log_sensitive_information off). */
    fun logTail(ctx: Context, lines: Int = 40): String =
        runCatching { logFile(ctx).readLines().takeLast(lines).joinToString("\n") }.getOrDefault("")

    /** Tor's process is alive (up, or still connecting). */
    val alive: Boolean get() = proc?.isAlive == true

    fun stop(ctx: Context) {
        synchronized(lock) {
            proc?.destroy(); proc = null; ready = false
        }
    }

    /** The SOCKS proxy, waiting up to [waitMs] for Tor to come up; null if it does not. */
    fun proxy(waitMs: Long = 90_000): java.net.Proxy? {
        val until = System.currentTimeMillis() + waitMs
        while (true) {
            if (ready && proc?.isAlive == true && socksPort > 0)
                return java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", socksPort))
            // Not running (never started, or it stopped): nothing to wait for.
            if (proc?.isAlive != true) return null
            val left = until - System.currentTimeMillis()
            if (left <= 0) return null
            synchronized(lock) { lock.wait(minOf(left, 1000)) }
        }
    }

    /** Setting: every Electrum server through Tor ([ElectrumClient.throughTor]). */
    const val ELECTRUM_TOR = "electrum_through_tor"

    fun setElectrumThroughTor(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences("pyblockwatch", Context.MODE_PRIVATE).edit().putBoolean(ELECTRUM_TOR, on).apply()
        ElectrumClient.throughTor = on
    }

    /** Hook Tor into the node connections: started the first time a .onion is used. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        exe = java.io.File(app.applicationInfo.nativeLibraryDir, "libarti.so").takeIf { it.exists() }
        available = exe != null
        NodeRpcBackend.torProxy = { if (available) { start(app); proxy() } else null }
        ElectrumClient.torProxy = { if (available) { start(app); proxy() } else null }
        ElectrumClient.throughTor = app.getSharedPreferences("pyblockwatch", Context.MODE_PRIVATE).getBoolean(ELECTRUM_TOR, false)
    }
}
