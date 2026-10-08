package com.kilombino.pyblockwatch.chain

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import org.torproject.jni.TorService

/**
 * Tor inside the app (tor-android, the same Tor as Orbot), started only when a connection
 * needs it: a node reached by .onion. While it runs, its SOCKS port carries those connections.
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

    fun start(ctx: Context) {
        if (bound || !available) return
        bound = ctx.applicationContext.bindService(Intent(ctx.applicationContext, TorService::class.java), conn, Context.BIND_AUTO_CREATE)
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
