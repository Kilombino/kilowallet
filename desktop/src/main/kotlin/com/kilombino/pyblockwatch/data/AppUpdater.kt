package com.kilombino.pyblockwatch.data

import android.content.Context
import com.kilombino.kilowallet.desktop.BuildInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The desktop's updater, with the phone's API: download the new release with a progress bar,
 * check its SHA-256 against the signed sums ([UpdateCheck]), and put it in place.
 *
 * An AppImage replaces its own file (renamed over it, so a half-written one never runs) and
 * starts again. A .deb is installed with the system's package tool, which asks for the
 * administrator password (pkexec). On Windows the checked .msi is handed to the installer
 * (which replaces this copy once it has closed); on a Mac the checked .dmg is opened for the
 * new Kilowallet.app to be dragged over the old one.
 */
object AppUpdater {
    sealed class State {
        object Idle : State()
        data class Downloading(val percent: Int, val version: String) : State()
        object Verifying : State()
        object NeedsPermission : State()
        object Installing : State()
        data class Failed(val message: String) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(ctx: Context, r: UpdateCheck.Release) {
        if (state.value is State.Downloading || state.value is State.Installing) return
        scope.launch {
            runCatching {
                val url = r.apkUrl ?: error("This release is not signed for in-app updates: update from its page.")
                val f = download(ctx, url, r.version)
                state.value = State.Verifying
                val expected = r.sha256 ?: run { f.delete(); error("No signed hash for this release. Nothing was installed.") }
                check(sha256(f) == expected) { f.delete(); "The downloaded file is not the signed one (SHA-256 differs). Nothing was installed." }
                state.value = State.Installing
                install(f)
            }.onFailure { state.value = State.Failed(it.message ?: "The update failed.") }
        }
    }

    fun resume(ctx: Context) {}

    fun reset() { if (state.value !is State.Downloading && state.value !is State.Installing) state.value = State.Idle }

    private fun download(ctx: Context, url: String, version: String): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, url.substringAfterLast('/'))
        var u = URL(url)
        var c: HttpURLConnection
        // GitHub sends assets through a redirect to another host. Each hop is a fresh connection,
        // set up before it connects: touching one that already answered throws "Already connected".
        var hops = 0
        while (true) {
            c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000; c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "Kilowallet-desktop")
            if (c.responseCode !in 301..308) break
            check(++hops <= 5) { c.disconnect(); "Download failed: too many redirects" }
            u = URL(u, c.getHeaderField("Location")); c.disconnect()
        }
        try {
            check(c.responseCode == 200) { "Download failed: HTTP ${c.responseCode}" }
            val total = c.contentLengthLong
            var done = 0L; var last = -1
            c.inputStream.use { input -> out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != last) { last = pct; state.value = State.Downloading(pct, version) }
                }
            } }
        } finally { c.disconnect() }
        return out
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun install(f: File) {
        when (BuildInfo.packaging()) {
            "appimage" -> {
                val target = File(System.getenv("APPIMAGE") ?: error("Not running from an AppImage."))
                val tmp = File(target.parentFile, "." + target.name + ".new")
                f.copyTo(tmp, overwrite = true)
                tmp.setExecutable(true, false)
                check(tmp.renameTo(target)) { tmp.delete(); "Could not replace ${target.path}: is its folder writable?" }
                f.delete()
                restart(listOf(target.path))
            }
            "deb" -> {
                val p = ProcessBuilder("pkexec", "apt-get", "install", "-y", "--allow-downgrades", f.absolutePath).inheritIO().start()
                check(p.waitFor() == 0) { "The package was not installed (cancelled, or apt failed)." }
                f.delete()
                restart(listOf("/opt/kilowallet/bin/Kilowallet"))
            }
            "msi" -> {
                com.kilombino.kilowallet.desktop.SingleInstance.release()
                ProcessBuilder("msiexec", "/i", f.absolutePath).start()
                kotlin.system.exitProcess(0)
            }
            "dmg" -> {
                com.kilombino.kilowallet.desktop.Platform.open(f.absolutePath)
                state.value = State.Idle
            }
            else -> error("This copy was not installed from a release package: download the new one from its page.")
        }
    }

    /** Starts the new version and leaves; the old one's tray and window go with it. */
    private fun restart(cmd: List<String>) {
        com.kilombino.kilowallet.desktop.SingleInstance.release()
        ProcessBuilder(cmd).start()
        kotlin.system.exitProcess(0)
    }
}
