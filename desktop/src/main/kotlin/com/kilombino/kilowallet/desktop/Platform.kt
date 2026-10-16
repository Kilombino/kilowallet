package com.kilombino.kilowallet.desktop

import java.io.File

/** Which desktop this is: where its files go, what its programs are called, which release file is its own. */
object Platform {
    enum class Os { LINUX, WINDOWS, MAC }

    val os: Os = System.getProperty("os.name").orEmpty().lowercase().let {
        when {
            it.startsWith("windows") -> Os.WINDOWS
            it.startsWith("mac") || it.contains("darwin") -> Os.MAC
            else -> Os.LINUX
        }
    }

    /** "x64" or "arm64", as the release files are named. */
    val arch: String = System.getProperty("os.arch").orEmpty().lowercase().let { if (it == "aarch64" || it == "arm64") "arm64" else "x64" }

    /** What the screens call this system. */
    val displayName: String get() = when (os) { Os.LINUX -> "Linux"; Os.WINDOWS -> "Windows"; Os.MAC -> "macOS" }

    /** A program's file name here (tor → tor.exe on Windows). */
    fun exe(name: String) = if (os == Os.WINDOWS) "$name.exe" else name

    /** [name] on the PATH, if it is there and can run. */
    fun onPath(name: String): File? = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        .filter { it.isNotBlank() }.map { File(it, exe(name)) }.firstOrNull { it.isFile && it.canExecute() }

    private val home get() = System.getProperty("user.home")

    /** The folder for the wallet's data: XDG on Linux, AppData\Roaming on Windows, Application Support on a Mac. */
    fun dataRoot(): File = when (os) {
        Os.WINDOWS -> File(System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: "$home\\AppData\\Roaming")
        Os.MAC -> File(home, "Library/Application Support")
        Os.LINUX -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share")
    }

    /** The folder for what can be thrown away (downloads, updates). */
    fun cacheRoot(): File = when (os) {
        Os.WINDOWS -> File(System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: "$home\\AppData\\Local", "Cache")
        Os.MAC -> File(home, "Library/Caches")
        Os.LINUX -> File(System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.cache")
    }

    /** Opens [target] (a web address or a file) with what the system uses for it. */
    fun open(target: String) {
        val cmd = when (os) {
            Os.WINDOWS -> listOf("rundll32", "url.dll,FileProtocolHandler", target)
            Os.MAC -> listOf("open", target)
            Os.LINUX -> listOf("xdg-open", target)
        }
        ProcessBuilder(cmd).start()
    }
}
