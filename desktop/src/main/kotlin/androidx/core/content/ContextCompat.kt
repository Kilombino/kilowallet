package androidx.core.content

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Permissions are the user's own on the desktop: every one is granted. */
object ContextCompat {
    fun checkSelfPermission(ctx: Context, permission: String): Int = PackageManager.PERMISSION_GRANTED
    fun startForegroundService(ctx: Context, intent: Intent) { ctx.startForegroundService(intent) }
    fun getMainExecutor(ctx: Context): java.util.concurrent.Executor = java.util.concurrent.Executor { javax.swing.SwingUtilities.invokeLater(it) }
    fun getColor(ctx: Context, id: Int): Int = 0
}
