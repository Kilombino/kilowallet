package android.content

/** The system services the shared code asks a context for, on the desktop. */
object DesktopServices {
    val notifications = android.app.NotificationManager()
    val power = android.os.PowerManager()
    val activities = android.app.ActivityManager()
    val alarms = android.app.AlarmManager()
    val vibrator = android.os.Vibrator()
    val clipboard = ClipboardManager()
    fun byName(name: String): Any? = when (name) {
        Context.NOTIFICATION_SERVICE -> notifications
        Context.POWER_SERVICE -> power
        Context.ACTIVITY_SERVICE -> activities
        Context.ALARM_SERVICE -> alarms
        Context.VIBRATOR_SERVICE -> vibrator
        Context.CLIPBOARD_SERVICE -> clipboard
        else -> null
    }
    fun byClass(cls: Class<*>): Any? = when (cls) {
        android.app.NotificationManager::class.java -> notifications
        android.os.PowerManager::class.java -> power
        android.app.ActivityManager::class.java -> activities
        android.app.AlarmManager::class.java -> alarms
        ClipboardManager::class.java -> clipboard
        else -> null
    }
}
