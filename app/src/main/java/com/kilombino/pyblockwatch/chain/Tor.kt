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

    /** Hook Tor into the node connections: started the first time a .onion is used. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        exe = java.io.File(app.applicationInfo.nativeLibraryDir, "libarti.so").takeIf { it.exists() }
        available = exe != null
        NodeRpcBackend.torProxy = { if (available) { start(app); proxy() } else null }
    }
}
