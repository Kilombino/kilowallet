package com.kilombino.pyblockwatch.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
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
 * Updates the app from inside it, the way Amber does: download the release's APK with a
 * progress bar, check it, and hand it to Android's own installer.
 *
 * Two checks before anything is installed, so a swapped file on the way cannot get in:
 * its SHA-256 must be the one the release notes publish, and it must be signed with the
 * same certificate as the app already installed (Android refuses anything else as an
 * update anyway; checking first gives a clear message instead of a failed install).
 *
 * Android asks the user to confirm the first time (and to allow installs from this app).
 * From Android 12 on, once the app has installed itself, later updates may go through
 * without a prompt.
 */
object AppUpdater {
    sealed class State {
        object Idle : State()
        data class Downloading(val percent: Int, val version: String) : State()
        object Verifying : State()
        /** Android needs "install unknown apps" allowed for this app first. */
        object NeedsPermission : State()
        object Installing : State()
        data class Failed(val message: String) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Pair<File, UpdateCheck.Release>? = null

    fun start(ctx: Context, r: UpdateCheck.Release) {
        val app = ctx.applicationContext
        if (state.value is State.Downloading || state.value is State.Installing) return
        // So the app, once replaced, can say the update is in (see UpdatedReceiver).
        app.getSharedPreferences("pyblockwatch", Context.MODE_PRIVATE).edit().putString("updating_to", r.version).apply()
        scope.launch {
            runCatching {
                val url = r.apkUrl ?: error("This release has no APK attached.")
                val f = download(app, url, r.version)
                state.value = State.Verifying
                verify(app, f, r)
                pending = f to r
                install(app)
            }.onFailure { state.value = State.Failed(it.message ?: "The update failed.") }
        }
    }

    /** After the user allowed installs from this app in Android's settings. */
    fun resume(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { runCatching { install(app) }.onFailure { state.value = State.Failed(it.message ?: "The update failed.") } }
    }

    fun reset() { if (state.value !is State.Downloading && state.value !is State.Installing) state.value = State.Idle }

    private fun download(ctx: Context, url: String, version: String): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "kilowallet-$version.apk")
        state.value = State.Downloading(0, version)
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Kilowallet")
        try {
            check(c.responseCode == 200) { "Download failed (HTTP ${c.responseCode})." }
            val total = c.contentLengthLong
            var done = 0L; var last = -1
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != last) { last = pct; state.value = State.Downloading(pct, version) }
                    }
                }
            }
        } finally { c.disconnect() }
        return out
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun certs(pm: PackageManager, info: android.content.pm.PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun verify(ctx: Context, f: File, r: UpdateCheck.Release) {
        r.sha256?.let { expected ->
            val got = sha256(f)
            check(got == expected) { f.delete(); "The downloaded file is not the published one (SHA-256 differs). Nothing was installed." }
        }
        val pm = ctx.packageManager
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val theirs = certs(pm, pm.getPackageArchiveInfo(f.absolutePath, flag))
        val ours = certs(pm, pm.getPackageInfo(ctx.packageName, flag))
        check(theirs.isNotEmpty() && theirs == ours) { f.delete(); "The download is not signed with this app's certificate. Nothing was installed." }
        val pkg = pm.getPackageArchiveInfo(f.absolutePath, 0)?.packageName
        check(pkg == ctx.packageName) { f.delete(); "The download is another app. Nothing was installed." }
    }

    private fun install(ctx: Context) {
        val (f, _) = pending ?: error("Nothing downloaded to install.")
        val pm = ctx.packageManager
        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) { state.value = State.NeedsPermission; return }
        state.value = State.Installing
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("kilowallet.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val intent = Intent(ctx, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(ctx, 7, intent, flags).intentSender)
        }
    }

    /** Android's answer: ask the user to confirm, or report why it failed. Success restarts the app. */
    /**
     * Android closes the app to replace it and does not start it again. When the new version
     * is in (after an update started here), a notification says so; tapping it opens the app.
     */
    class UpdatedReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            val prefs = context.getSharedPreferences("pyblockwatch", Context.MODE_PRIVATE)
            prefs.getString("updating_to", null) ?: return
            prefs.edit().remove("updating_to").apply()
            // The version really installed now (it could be another one, from Zapstore).
            val v = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: return
            runCatching { Notifier(context).updated(v) }
        }
    }

    /** An update that was cancelled or failed is not waited for any more. */
    private fun clearPending(ctx: Context) =
        ctx.getSharedPreferences("pyblockwatch", Context.MODE_PRIVATE).edit().remove("updating_to").apply()

    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                                  else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
                }
                PackageInstaller.STATUS_SUCCESS -> state.value = State.Idle
                else -> {
                    clearPending(context)
                    state.value = State.Failed(
                        "Android did not install it: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cancelled or failed") + ".")
                }
            }
        }
    }
}
