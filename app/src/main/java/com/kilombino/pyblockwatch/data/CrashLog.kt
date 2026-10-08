package com.kilombino.pyblockwatch.data

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

/**
 * When the app closes on an error, what failed is kept on the phone (never sent): the next time
 * it opens, the user is offered to share it. Only the error and where in the code it happened,
 * the app version and the phone model; no words, keys or addresses.
 */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "last-crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val v = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                val trace = java.io.StringWriter().also { e.printStackTrace(java.io.PrintWriter(it)) }.toString()
                file(app).writeText("Kilowallet $v · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · " +
                    "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SUPPORTED_ABIS.joinToString()}\nthread: ${thread.name}\n\n" +
                    trace.take(20_000))
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The report of the last crash, if there is one not yet dealt with. */
    fun pending(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.readText()

    fun clear(ctx: Context) { file(ctx).delete() }
}

/** Installs [CrashLog] before anything else runs (activities, the watcher, widgets). */
class KilowalletApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
