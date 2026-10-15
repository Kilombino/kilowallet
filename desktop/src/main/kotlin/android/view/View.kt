package android.view

open class View { companion object { const val VISIBLE = 0; const val INVISIBLE = 4; const val GONE = 8 } }
/** The window flags the shared code sets (FLAG_SECURE…); nothing to do on the desktop. */
object Window { fun addFlags(f: Int) {} fun clearFlags(f: Int) {} fun setFlags(f: Int, m: Int) {} }
object WindowManager { object LayoutParams { const val FLAG_SECURE = 0x2000; const val FLAG_KEEP_SCREEN_ON = 0x80 } }
