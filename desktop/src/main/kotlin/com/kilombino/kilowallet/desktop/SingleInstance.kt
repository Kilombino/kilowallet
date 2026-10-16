package com.kilombino.kilowallet.desktop

import android.content.DesktopContext
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * One copy of the app per wallet folder: two would open the same wallet, Lightning engine and
 * coinjoin seats at once, and each left its own icon in the tray. The first copy holds a lock
 * on `instance.lock` and listens on a loopback port written in `instance.port`; a second copy
 * tells it to show its window and leaves.
 */
object SingleInstance {
    private const val SHOW = "show"
    private var lock: FileLock? = null
    private var channel: FileChannel? = null
    private var server: ServerSocket? = null

    /**
     * True when this is the only copy. False when another one is running: it was asked to show
     * its window. A copy that holds the lock without answering (the updater's old copy on its
     * way out) is waited for, up to [waitMs].
     */
    fun acquire(onShow: () -> Unit, waitMs: Long = 15_000): Boolean {
        val dir = DesktopContext.base
        val deadline = System.currentTimeMillis() + waitMs
        while (true) {
            val ch = RandomAccessFile(File(dir, "instance.lock"), "rw").channel
            val l = runCatching { ch.tryLock() }.getOrNull()
            if (l != null) {
                channel = ch; lock = l
                listen(File(dir, "instance.port"), onShow)
                return true
            }
            ch.close()
            if (signal(File(dir, "instance.port"))) return false
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(300)
        }
    }

    /** Lets a new copy start (the updater's restart): drops the lock and stops listening. */
    fun release() {
        runCatching { server?.close() }; server = null
        runCatching { lock?.release() }; lock = null
        runCatching { channel?.close() }; channel = null
    }

    private fun listen(portFile: File, onShow: () -> Unit) {
        val s = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        server = s
        portFile.writeText(s.localPort.toString())
        DesktopContext.private(portFile)
        Thread({
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                runCatching {
                    c.use { it.soTimeout = 2_000; if (it.getInputStream().bufferedReader().readLine() == SHOW) onShow() }
                }
            }
        }, "single-instance").apply { isDaemon = true }.start()
    }

    private fun signal(portFile: File): Boolean = runCatching {
        val port = portFile.readText().trim().toInt()
        Socket(InetAddress.getLoopbackAddress(), port).use { it.soTimeout = 2_000; it.getOutputStream().write("$SHOW\n".toByteArray()) }
        true
    }.getOrDefault(false)
}
