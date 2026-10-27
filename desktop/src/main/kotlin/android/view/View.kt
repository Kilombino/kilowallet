package android.view

open class View {
    /** Nothing holds it: shared code looking for a dialog's window (DialogWindowProvider) finds none. */
    open val parent: Any? get() = null
    companion object { const val VISIBLE = 0; const val INVISIBLE = 4; const val GONE = 8 }
}
/** The window flags the shared code sets (FLAG_SECURE…); nothing to do on the desktop. */
object Window {
    fun addFlags(f: Int) {} fun clearFlags(f: Int) {} fun setFlags(f: Int, m: Int) {}
    var attributes: WindowManager.LayoutParams = WindowManager.LayoutParams
}
object WindowManager {
    object LayoutParams {
        const val FLAG_SECURE = 0x2000; const val FLAG_KEEP_SCREEN_ON = 0x80
        const val BRIGHTNESS_OVERRIDE_FULL = 1f
        /** The screen's brightness is the system's on the desktop: kept, never applied. */
        var screenBrightness = -1f
    }
}
