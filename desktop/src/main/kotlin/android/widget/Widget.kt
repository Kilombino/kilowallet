package android.widget

import android.content.Context

/** A short message; on the desktop it shows at the bottom of the window. */
class Toast private constructor(private val text: String) {
    fun show() = DesktopToast.show(text)
    companion object {
        const val LENGTH_SHORT = 0; const val LENGTH_LONG = 1
        fun makeText(ctx: Context?, text: CharSequence, d: Int) = Toast(text.toString())
        fun makeText(ctx: Context?, res: Int, d: Int) = Toast("")
    }
}

object DesktopToast { @Volatile var show: (String) -> Unit = { System.err.println("toast: $it") } }

/** A notification's custom layout: on the desktop notifications are text, so its views are only set, not drawn. */
class RemoteViews(pkg: String, layout: Int) {
    fun setTextViewText(id: Int, text: CharSequence?) {}
    fun setTextColor(id: Int, color: Int) {}
    fun setViewVisibility(id: Int, v: Int) {}
    fun setImageViewResource(id: Int, res: Int) {}
    fun setInt(id: Int, method: String, value: Int) {}
    fun setChronometer(id: Int, base: Long, format: String?, started: Boolean) {}
    fun setChronometerCountDown(id: Int, down: Boolean) {}
    fun setOnClickPendingIntent(id: Int, p: android.app.PendingIntent?) {}
    fun setProgressBar(id: Int, max: Int, progress: Int, indeterminate: Boolean) {}
}
