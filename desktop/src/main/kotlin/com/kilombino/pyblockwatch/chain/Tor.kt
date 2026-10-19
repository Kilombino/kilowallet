package com.kilombino.pyblockwatch.chain

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import org.torproject.jni.TorService

/**
 * Tor inside the app, started only when a connection needs it: a node reached by .onion,
 * Lightning. While it runs, its SOCKS port carries those connections. The desktop's copy of the
 * Tor object Android had up to 1.1.6: TorService here is the Tor Project's tor run as a child
 * process (desktop org.torproject.jni.TorService); Android itself now runs Arti.
 */
object Tor {
    @Volatile private var service: TorService? = null
    @Volatile private var bound = false
    private val lock = Object()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? TorService.LocalBinder)?.service
            synchronized(lock) { lock.notifyAll() }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    /** Tor ships for arm64 only (like the Ark engine): on another processor a .onion can't be reached. */
    @Volatile var available: Boolean = false
        private set

    /**
     * The SOCKS proxy when Tor is already running, else null: for requests that go through Tor
     * when it is on (so they don't wait for it to start), and directly otherwise.
     */
    fun proxyIfRunning(): java.net.Proxy? = if (running) proxy(0) else null

    /** Tor is up: started for something else (a node reached by .onion) and its SOCKS port open. */
    val running: Boolean get() = bound && runCatching { (service?.socksPort ?: -1) > 0 }.getOrDefault(false)

    fun start(ctx: Context) {
        if (bound || !available) return
        writeTorrc(ctx.applicationContext)
        bound = ctx.applicationContext.bindService(Intent(ctx.applicationContext, TorService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    /** Tor's own log, kept for crash reports: Tor stops with abort() and says why only here. */
    fun logFile(ctx: Context) = java.io.File(ctx.applicationContext.noBackupFilesDir, "tor.log")

    /** Tor reads this torrc on start: its log goes to [logFile], cut back when it grows. */
    private fun writeTorrc(app: Context) = runCatching {
        val log = logFile(app)
        if (log.length() > 256_000) log.writeText(log.readText().takeLast(32_000))
        TorService.getTorrc(app).writeText("Log notice file ${log.path}\n")
    }

    fun stop(ctx: Context) {
        if (!bound) return
        runCatching { ctx.applicationContext.unbindService(conn) }
        bound = false; service = null
    }

    /** The SOCKS proxy, waiting up to [waitMs] for Tor to come up; null if it does not. */
    fun proxy(waitMs: Long = 90_000): java.net.Proxy? {
        val until = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < until) {
            val port = runCatching { service?.socksPort ?: -1 }.getOrDefault(-1)
            if (port > 0) return java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port))
            synchronized(lock) { lock.wait(1000) }
        }
        return null
    }

    /** Hook Tor into the node connections: started the first time a .onion is used. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        available = java.io.File(app.applicationInfo.nativeLibraryDir, "libtor.so").exists()
        NodeRpcBackend.torProxy = { if (available) { start(app); proxy() } else null }
    }
}
