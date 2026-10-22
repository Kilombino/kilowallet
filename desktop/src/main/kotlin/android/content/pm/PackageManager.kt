package android.content.pm

import android.content.Intent

/** The installed app as the shared code asks about it: its version, its signing certificate. */
open class PackageManager {
    open fun getPackageInfo(pkg: String, flags: Int): PackageInfo = PackageInfo(DesktopVersion.name, DesktopVersion.code)
    open fun getPackageInfo(pkg: String, flags: PackageInfoFlags): PackageInfo = getPackageInfo(pkg, 0)
    open fun getLaunchIntentForPackage(pkg: String): Intent? = Intent(Intent.ACTION_MAIN)
    open fun getPackageArchiveInfo(path: String, flags: Int): PackageInfo? = null
    open val packageInstaller: PackageInstaller get() = PackageInstaller()
    open fun canRequestPackageInstalls(): Boolean = false

    class PackageInfoFlags private constructor(val value: Long) { companion object { fun of(v: Long) = PackageInfoFlags(v) } }

    companion object {
        val instance = PackageManager()
        const val PERMISSION_GRANTED = 0
        const val PERMISSION_DENIED = -1
        const val GET_SIGNING_CERTIFICATES = 0x08000000
        const val GET_SIGNATURES = 0x40
    }
}

class PackageInfo(val versionName: String?, val longVersionCode: Long) {
    val versionCode: Int get() = longVersionCode.toInt()
    val packageName: String = "com.kilombino.pyblockwatch"
    val signingInfo: SigningInfo? = null
    @Deprecated("") val signatures: Array<Signature>? = null
}

class SigningInfo { val apkContentsSigners: Array<Signature> = emptyArray() }
class Signature(private val bytes: ByteArray) { fun toByteArray() = bytes }
class ApplicationInfo(val nativeLibraryDir: String) {
    /** The desktop build is never debuggable (no test networks). */
    val flags: Int = 0
    companion object { const val FLAG_DEBUGGABLE = 2 }
}
class ServiceInfo { companion object { const val FOREGROUND_SERVICE_TYPE_DATA_SYNC = 1; const val FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 0x40000000 } }
class PackageInstaller { class SessionParams(mode: Int) { companion object { const val MODE_FULL_INSTALL = 1 } } }

/** The desktop build's version, set from its build. */
object DesktopVersion {
    @Volatile var name: String = System.getProperty("kilowallet.version", "desktop")
    @Volatile var code: Long = 1
}
