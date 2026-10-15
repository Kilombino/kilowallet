package android.appwidget

/** Home-screen widgets do not exist on the desktop: the manager is never handed out (getSystemService gives null). */
class AppWidgetManager private constructor() {
    val isRequestPinAppWidgetSupported: Boolean get() = false
    fun requestPinAppWidget(provider: android.content.ComponentName, extras: android.os.Bundle?, callback: android.app.PendingIntent?): Boolean = false
}
