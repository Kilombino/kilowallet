package android.os

/** android.os pieces the shared code reads: the platform it runs on, and small helpers. */
object Build {
    @JvmField val MODEL: String = "Linux " + (System.getProperty("os.arch") ?: "")
    @JvmField val MANUFACTURER: String = System.getProperty("os.name") ?: "Linux"
    @JvmField val BRAND: String = "desktop"
    @JvmField val DEVICE: String = "desktop"
    @JvmField val SUPPORTED_ABIS: Array<String> = arrayOf(System.getProperty("os.arch") ?: "x86_64")
    object VERSION {
        // The shared code takes the newest paths (no legacy branches) on the desktop.
        @JvmField val SDK_INT: Int = 35
        @JvmField val RELEASE: String = "desktop (" + (System.getProperty("os.name") ?: "Linux") + " " + (System.getProperty("os.version") ?: "") + ")"
    }
    object VERSION_CODES {
        const val M = 23; const val N = 24; const val O = 26; const val P = 28; const val Q = 29; const val R = 30
        const val S = 31; const val TIRAMISU = 33; const val UPSIDE_DOWN_CAKE = 34; const val VANILLA_ICE_CREAM = 35
    }
}

class Bundle {
    private val m = HashMap<String, Any?>()
    fun putString(k: String, v: String?) { m[k] = v }
    fun putInt(k: String, v: Int) { m[k] = v }
    fun putLong(k: String, v: Long) { m[k] = v }
    fun putBoolean(k: String, v: Boolean) { m[k] = v }
    fun putObject(k: String, v: Any?) { m[k] = v }
    fun getString(k: String): String? = m[k] as? String
    fun getInt(k: String, d: Int = 0): Int = m[k] as? Int ?: d
    fun getLong(k: String, d: Long = 0): Long = m[k] as? Long ?: d
    fun getBoolean(k: String, d: Boolean = false): Boolean = m[k] as? Boolean ?: d
    fun containsKey(k: String) = k in m
}

interface IBinder
interface Parcelable
open class Binder : IBinder

object SystemClock {
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
    fun uptimeMillis(): Long = elapsedRealtime()
}

object Process {
    fun myPid(): Int = ProcessHandle.current().pid().toInt()
    fun killProcess(pid: Int) { kotlin.system.exitProcess(0) }
}

class Looper private constructor() { companion object { fun getMainLooper() = Looper(); fun myLooper() = Looper() } }

/** Runs on the UI thread (Swing's, on the desktop). */
class Handler(looper: Looper? = null) {
    fun post(r: Runnable): Boolean { javax.swing.SwingUtilities.invokeLater(r); return true }
    fun postDelayed(r: Runnable, ms: Long): Boolean {
        val t = javax.swing.Timer(ms.toInt()) { r.run() }; t.isRepeats = false; t.start(); return true
    }
}

class PowerManager {
    fun isIgnoringBatteryOptimizations(pkg: String): Boolean = true // no battery saver to opt out of
    fun isInteractive(): Boolean = true
}

class Vibrator { fun vibrate(ms: Long) {} fun hasVibrator() = false }
