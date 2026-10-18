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
        /**
         * Where tor-android reads its torrc. The app writes its log line there; here tor's log is
         * read from its output instead (for the SOCKS port), so the file is only kept, not used.
         */
        @JvmStatic fun getTorrc(@Suppress("UNUSED_PARAMETER") ctx: android.content.Context): File = File(DesktopContext.root("tor"), "torrc")

        /** tor shipped with the app first (its tor/ folder on Windows and macOS), then the system's: its usual place before whatever comes first on PATH. */
        fun binary(): File? {
            val exe = com.kilombino.kilowallet.desktop.Platform.exe("tor")
            val shipped = System.getProperty("kilowallet.natives")?.let { listOf(File(it, "tor/$exe"), File(it, exe)) }.orEmpty()
            // A package may not keep the program's execute bit: give it back.
            shipped.filter { it.isFile && !it.canExecute() }.forEach { runCatching { it.setExecutable(true, true) } }
            shipped.firstOrNull { it.isFile && it.canExecute() }?.let { return it }
            listOf("/usr/bin/tor", "/usr/sbin/tor", "/opt/homebrew/bin/tor", "/usr/local/bin/tor").map(::File)
                .firstOrNull { it.isFile && it.canExecute() }?.let { return it }
            return com.kilombino.kilowallet.desktop.Platform.onPath("tor")
        }
    }
}
