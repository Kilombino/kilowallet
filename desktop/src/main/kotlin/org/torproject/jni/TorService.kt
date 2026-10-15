package org.torproject.jni

import android.app.Service
import android.content.DesktopContext
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import java.io.File

/**
 * tor-android's TorService on the desktop: the tor program (the one shipped with the app, or the
 * system's) run as a child process with its own data folder, its SOCKS port chosen by tor and
 * read back from the file it writes.
 */
class TorService : Service() {
    inner class LocalBinder : Binder() { val service: TorService get() = this@TorService }

    @Volatile var socksPort: Int = -1
        private set
    private var proc: Process? = null

    override fun onBind(intent: Intent?): IBinder {
        start()
        return LocalBinder()
    }

    @Synchronized private fun start() {
        if (proc?.isAlive == true) return
        val exe = binary() ?: return
        val dir = DesktopContext.root("tor")
        val portFile = File(dir, "socks-port")
        portFile.delete()
        proc = ProcessBuilder(exe.path, "--DataDirectory", dir.path, "--SocksPort", "auto", "--ControlPort", "0",
            "--SocksPolicy", "accept 127.0.0.1", "--ClientOnly", "1", "--AvoidDiskWrites", "1",
            "--__OwningControllerProcess", ProcessHandle.current().pid().toString(),
            "--ControlPortWriteToFile", File(dir, "control-port").path, "--Log", "notice stdout")
            .redirectErrorStream(true).start()
        val p = proc!!
        Thread {
            // tor prints "Socks listener listening on port N."; the port is read from there.
            p.inputStream.bufferedReader().forEachLine { line ->
                Regex("""Socks listener listening on port (\d+)""").find(line)?.let { socksPort = it.groupValues[1].toInt() }
                if ("Bootstrapped 100%" in line && socksPort < 0) socksPort = -1
            }
            socksPort = -1
        }.apply { isDaemon = true; name = "tor" }.start()
        Runtime.getRuntime().addShutdownHook(Thread { p.destroy() })
    }

    override fun onDestroy() { proc?.destroy(); proc = null; socksPort = -1 }

    companion object {
        /** tor next to the app (the packaged one) first, then the system's. */
        fun binary(): File? {
            System.getProperty("kilowallet.natives")?.let { File(it, "tor") }?.takeIf { it.canExecute() }?.let { return it }
            return System.getenv("PATH").orEmpty().split(':').map { File(it, "tor") }.firstOrNull { it.canExecute() }
        }
    }
}
