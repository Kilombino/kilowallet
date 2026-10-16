package android.content

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File

/**
 * The part of Android's Context the shared Kilowallet code uses, on the desktop: the app's
 * folders under the XDG data directory, its preferences as JSON files, the system services it
 * asks for (notifications, clipboard, power) and starting "activities" (opening a link,
 * sharing text) the desktop way.
 */
abstract class Context {
    private val bound = java.util.concurrent.ConcurrentHashMap<ServiceConnection, android.app.Service>()

    open val applicationContext: Context get() = DesktopContext
    open val packageName: String get() = "com.kilombino.pyblockwatch"
    open val filesDir: File get() = DesktopContext.root("files")
    open val noBackupFilesDir: File get() = DesktopContext.root("no_backup")
    open val cacheDir: File get() = DesktopContext.cache()
    open val packageManager: PackageManager get() = PackageManager.instance
    open val contentResolver: ContentResolver get() = ContentResolver
    open val assets: android.content.res.AssetManager get() = android.content.res.AssetManager
    // Where the shared code looks for native helpers: Tor.install() checks for libtor.so there.
    open val applicationInfo: ApplicationInfo get() = ApplicationInfo(DesktopContext.nativeDir().path)

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = DesktopPrefs.of(name)

    open fun getSystemService(name: String): Any? = DesktopServices.byName(name)
    @Suppress("UNCHECKED_CAST")
    open fun <T> getSystemService(cls: Class<T>): T = DesktopServices.byClass(cls) as T

    open fun startActivity(intent: Intent) = DesktopIntents.start(intent)
    open fun startService(intent: Intent): ComponentName? = DesktopIntents.service(intent, start = true)
    open fun startForegroundService(intent: Intent): ComponentName? = DesktopIntents.service(intent, start = true)
    open fun stopService(intent: Intent): Boolean { DesktopIntents.service(intent, start = false); return true }
    open fun sendBroadcast(intent: Intent) {}

    /** A bound service runs in the app: it is created, and its binder handed over, at once. */
    open fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
        val cls = intent.component?.className ?: return false
        val s = runCatching { Class.forName(cls).getDeclaredConstructor().newInstance() as android.app.Service }.getOrNull() ?: return false
        s.onCreate()
        val binder = s.onBind(intent) ?: return false
        bound[conn] = s
        conn.onServiceConnected(intent.component, binder)
        return true
    }
    open fun unbindService(conn: ServiceConnection) { bound.remove(conn)?.onDestroy() }
    open fun getString(id: Int): String = ""
    open fun getColor(id: Int): Int = 0

    companion object {
        const val MODE_PRIVATE = 0
        const val NOTIFICATION_SERVICE = "notification"
        const val CLIPBOARD_SERVICE = "clipboard"
        const val POWER_SERVICE = "power"
        const val VIBRATOR_SERVICE = "vibrator"
        const val ACTIVITY_SERVICE = "activity"
        const val ALARM_SERVICE = "alarm"
        const val BIND_AUTO_CREATE = 1
    }
}

/** The one context of the desktop app. */
object DesktopContext : Context() {
    override val applicationContext: Context get() = this

    /**
     * The wallet's folder, private to the user: ~/.local/share/kilowallet (or $XDG_DATA_HOME) on
     * Linux, %APPDATA%\kilowallet on Windows, ~/Library/Application Support/kilowallet on a Mac.
     */
    val base: File by lazy {
        File(com.kilombino.kilowallet.desktop.Platform.dataRoot(), System.getProperty("kilowallet.profile", "kilowallet")).also { it.mkdirs(); private(it) }
    }

    /** What the shared code checks for natives: here, a marker for a tor program that can run. */
    fun nativeDir(): File {
        val d = File(cache(), "natives").also { it.mkdirs() }
        val marker = File(d, "libtor.so")
        if (org.torproject.jni.TorService.binary() != null) marker.createNewFile() else marker.delete()
        return d
    }

    fun root(sub: String): File = File(base, sub).also { it.mkdirs(); private(it) }

    fun cache(): File {
        return File(com.kilombino.kilowallet.desktop.Platform.cacheRoot(), System.getProperty("kilowallet.profile", "kilowallet")).also { it.mkdirs(); private(it) }
    }

    /** Owner-only, like an Android app's private storage. (On Windows the user's AppData already is.) */
    fun private(f: File) {
        if (com.kilombino.kilowallet.desktop.Platform.os == com.kilombino.kilowallet.desktop.Platform.Os.WINDOWS) return
        f.setReadable(false, false); f.setWritable(false, false); f.setExecutable(false, false)
        f.setReadable(true, true); f.setWritable(true, true); if (f.isDirectory) f.setExecutable(true, true)
    }
}

class ComponentName(val packageName: String, val className: String) {
    constructor(ctx: Context, cls: Class<*>) : this(ctx.packageName, cls.name)
}

interface ServiceConnection {
    fun onServiceConnected(name: ComponentName?, service: android.os.IBinder?)
    fun onServiceDisconnected(name: ComponentName?)
}

abstract class BroadcastReceiver {
    abstract fun onReceive(context: Context, intent: Intent)
}
