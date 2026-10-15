package android.app

import android.content.Context
import android.content.DesktopContext
import android.content.Intent
import android.os.IBinder

/** The desktop app has one Application, the desktop context itself. */
open class Application : Context() {
    open fun onCreate() {}
    override val applicationContext: Context get() = DesktopContext
}

/** An Android Activity on the desktop: a context; its screen is the main window's content. */
open class Activity : Context() {
    override val applicationContext: Context get() = DesktopContext
    open fun finish() { DesktopActivities.onFinish(this) }
    open fun recreate() { DesktopActivities.onRecreate(this) }
    open fun finishAffinity() = finish()
    /** ⏻ on the phone closes the app for good: the same here. */
    open fun finishAndRemoveTask() { kotlin.system.exitProcess(0) }
    open fun moveTaskToBack(b: Boolean): Boolean = true
    open val intent: Intent get() = Intent(Intent.ACTION_MAIN)
    open fun setIntent(i: Intent) {}
    open val window: android.view.Window get() = android.view.Window
    open fun shouldShowRequestPermissionRationale(p: String): Boolean = false
    open fun requestPermissions(p: Array<String>, code: Int) {}
}

object DesktopActivities {
    @Volatile var onFinish: (Activity) -> Unit = {}
    @Volatile var onRecreate: (Activity) -> Unit = {}
}

/** A background service runs inside the desktop app: its loop starts when "started". */
abstract class Service : Context() {
    override val applicationContext: Context get() = DesktopContext
    open fun onCreate() {}
    open fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    open fun onDestroy() {}
    abstract fun onBind(intent: Intent?): IBinder?
    open fun startForeground(id: Int, n: Notification) {}
    open fun startForeground(id: Int, n: Notification, type: Int) {}
    open fun stopForeground(flags: Int) {}
    @Deprecated("") open fun stopForeground(remove: Boolean) {}
    open fun stopSelf() { DesktopServiceHost.stop(this) }
    companion object {
        const val START_STICKY = 1; const val START_NOT_STICKY = 2
        const val STOP_FOREGROUND_REMOVE = 1
    }
}

/** Runs [Service] subclasses in the app, as the services Android would host. */
object DesktopServiceHost {
    private val running = HashMap<String, Service>()
    fun start(cls: Class<out Service>, intent: Intent? = null) = synchronized(this) {
        val s = running.getOrPut(cls.name) { cls.getDeclaredConstructor().newInstance().also { it.onCreate() } }
        Thread { runCatching { s.onStartCommand(intent, 0, 1) } }.apply { isDaemon = true; name = cls.simpleName }.start()
    }
    fun stop(s: Service) = synchronized(this) { if (running.remove(s.javaClass.name) != null) runCatching { s.onDestroy() } }
    fun stop(cls: Class<*>) = synchronized(this) { running.remove(cls.name)?.let { runCatching { it.onDestroy() } } }
}

/** A notification, kept to show on the desktop (see [NotificationManager]). */
class Notification(val title: CharSequence?, val text: CharSequence?, val ongoing: Boolean, val contentIntent: PendingIntent?) {
    class Builder(ctx: Context, channel: String? = null) {
        private var title: CharSequence? = null; private var text: CharSequence? = null
        private var ongoing = false; private var intent: PendingIntent? = null
        fun setSmallIcon(i: Int) = this
        fun setSmallIcon(i: android.graphics.drawable.Icon?) = this
        fun setContentTitle(t: CharSequence?) = apply { title = t }
        fun setContentText(t: CharSequence?) = apply { text = t }
        fun setStyle(s: Style?) = apply { (s as? BigTextStyle)?.big?.let { text = it } }
        fun setAutoCancel(b: Boolean) = this
        fun setContentIntent(p: PendingIntent?) = apply { intent = p }
        fun setOngoing(b: Boolean) = apply { ongoing = b }
        fun setOnlyAlertOnce(b: Boolean) = this
        fun setVisibility(v: Int) = this
        fun setCategory(c: String?) = this
        fun setPriority(p: Int) = this
        fun setCustomContentView(v: android.widget.RemoteViews?) = this
        fun setCustomBigContentView(v: android.widget.RemoteViews?) = this
        fun setCustomHeadsUpContentView(v: android.widget.RemoteViews?) = this
        fun setShowWhen(b: Boolean) = this
        fun setWhen(t: Long) = this
        fun setUsesChronometer(b: Boolean) = this
        fun setChronometerCountDown(b: Boolean) = this
        fun setSubText(t: CharSequence?) = this
        fun setColor(c: Int) = this
        fun setColorized(b: Boolean) = this
        fun setGroup(g: String?) = this
        fun setGroupSummary(b: Boolean) = this
        fun setSilent(b: Boolean) = this
        fun setDeleteIntent(p: PendingIntent?) = this
        fun setTimeoutAfter(ms: Long) = this
        fun setForegroundServiceBehavior(b: Int) = this
        fun setNumber(n: Int) = this
        fun setSortKey(k: String?) = this
        fun setProgress(max: Int, p: Int, ind: Boolean) = this
        fun addAction(icon: Int, title: CharSequence?, p: PendingIntent?) = this
        fun build() = Notification(title, text, ongoing, intent)
    }
    abstract class Style
    class BigTextStyle : Style() { var big: CharSequence? = null; fun bigText(t: CharSequence?) = apply { big = t } }
    class DecoratedCustomViewStyle : Style()
    companion object {
        const val VISIBILITY_PUBLIC = 1; const val VISIBILITY_PRIVATE = 0; const val VISIBILITY_SECRET = -1
        const val CATEGORY_PROGRESS = "progress"; const val CATEGORY_STATUS = "status"; const val CATEGORY_MESSAGE = "msg"
        const val PRIORITY_DEFAULT = 0; const val PRIORITY_LOW = -1; const val PRIORITY_HIGH = 1
        const val FOREGROUND_SERVICE_IMMEDIATE = 1
    }
}

class NotificationChannel(val id: String, val name: CharSequence?, val importance: Int) {
    var description: String? = null
    var lockscreenVisibility: Int = 0
    fun setShowBadge(b: Boolean) {}
    fun enableVibration(b: Boolean) {}
    fun setSound(u: android.net.Uri?, a: Any?) {}
    fun enableLights(b: Boolean) {}
}

/** Notifications on the desktop: alerts pop up from the tray icon; ongoing ones are listed in the window. */
class NotificationManager {
    fun createNotificationChannel(c: NotificationChannel) {}
    fun deleteNotificationChannel(id: String) {}
    fun getNotificationChannel(id: String): NotificationChannel? = null
    fun notify(id: Int, n: Notification) = DesktopNotifications.show(id, n)
    fun notify(tag: String?, id: Int, n: Notification) = DesktopNotifications.show(id, n)
    fun cancel(id: Int) = DesktopNotifications.cancel(id)
    fun cancelAll() = DesktopNotifications.cancelAll()
    fun areNotificationsEnabled(): Boolean = true
    val activeNotifications: Array<Any> get() = emptyArray()
    companion object {
        const val IMPORTANCE_MIN = 1; const val IMPORTANCE_LOW = 2; const val IMPORTANCE_DEFAULT = 3; const val IMPORTANCE_HIGH = 4
    }
}

object DesktopNotifications {
    /** The window registers how to pop an alert up (tray message) and where ongoing ones are listed. */
    @Volatile var popup: (String, String) -> Unit = { _, _ -> }
    val ongoing = java.util.concurrent.ConcurrentHashMap<Int, Notification>()
    private val shown = java.util.concurrent.ConcurrentHashMap<Int, String>()
    fun show(id: Int, n: Notification) {
        if (n.ongoing) { ongoing[id] = n; return }
        val key = "${n.title}|${n.text}"
        if (shown.put(id, key) == key) return // updated with the same text: once
        popup(n.title?.toString() ?: "Kilowallet", n.text?.toString() ?: "")
    }
    fun cancel(id: Int) { ongoing.remove(id); shown.remove(id) }
    fun cancelAll() { ongoing.clear(); shown.clear() }
}

class PendingIntent private constructor(val intent: Intent?) {
    fun send() { intent?.let { DesktopContext.startActivity(it) } }
    companion object {
        const val FLAG_IMMUTABLE = 0x04000000; const val FLAG_MUTABLE = 0x02000000
        const val FLAG_UPDATE_CURRENT = 0x08000000; const val FLAG_ONE_SHOT = 0x40000000; const val FLAG_CANCEL_CURRENT = 0x10000000
        fun getActivity(ctx: Context, code: Int, i: Intent?, flags: Int) = PendingIntent(i)
        fun getBroadcast(ctx: Context, code: Int, i: Intent?, flags: Int) = PendingIntent(i)
        fun getService(ctx: Context, code: Int, i: Intent?, flags: Int) = PendingIntent(i)
        fun getForegroundService(ctx: Context, code: Int, i: Intent?, flags: Int) = PendingIntent(i)
    }
}

class AlarmManager { fun cancel(p: PendingIntent?) {} fun set(type: Int, at: Long, p: PendingIntent?) {} fun setInexactRepeating(t: Int, a: Long, i: Long, p: PendingIntent?) {} companion object { const val RTC = 1; const val ELAPSED_REALTIME = 3 } }

/** No record of past exits on the desktop: crash reports come from the app's own files there. */
class ActivityManager { fun getHistoricalProcessExitReasons(pkg: String?, pid: Int, max: Int): List<ApplicationExitInfo> = emptyList() }
class ApplicationExitInfo {
    val reason: Int = 0; val timestamp: Long = 0; val description: String? = null; val pid: Int = 0
    val traceInputStream: java.io.InputStream? = null
    companion object { const val REASON_CRASH = 4; const val REASON_CRASH_NATIVE = 5 }
}
