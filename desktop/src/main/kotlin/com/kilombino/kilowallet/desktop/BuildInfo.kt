package com.kilombino.kilowallet.desktop

/** The desktop build's version (set by the build in kilowallet-desktop.properties) and how it was installed. */
object BuildInfo {
    private val props = java.util.Properties().apply {
        BuildInfo::class.java.getResourceAsStream("/kilowallet-desktop.properties")?.use { load(it) }
    }
    val VERSION: String = props.getProperty("version", "0.0.0-dev")
    val CODE: Long = props.getProperty("code", "0").toLong()

    /**
     * How this copy was installed: "appimage" (it updates its own file), "deb" (in /opt/kilowallet),
     * "msi" (Windows), "dmg" (a Kilowallet.app on a Mac) or "dev" (run from the build).
     */
    fun packaging(): String {
        val res = System.getProperty("compose.application.resources.dir").orEmpty()
        return when {
            System.getenv("APPIMAGE") != null -> "appimage"
            res.startsWith("/opt/kilowallet") -> "deb"
            res.isEmpty() -> "dev"
            Platform.os == Platform.Os.WINDOWS -> "msi"
            Platform.os == Platform.Os.MAC && ".app/Contents/" in res -> "dmg"
            else -> "dev"
        }
    }

    /** The release file this copy updates from (as the release workflow names them). */
    fun releaseFile(name: String): Boolean = when (packaging()) {
        "deb" -> name.endsWith("_amd64.deb")
        "msi" -> name.endsWith("-windows-x64.msi")
        "dmg" -> name.endsWith("-macos-${Platform.arch}.dmg")
        else -> when (Platform.os) {
            Platform.Os.WINDOWS -> name.endsWith("-windows-x64.msi")
            Platform.Os.MAC -> name.endsWith("-macos-${Platform.arch}.dmg")
            Platform.Os.LINUX -> name.endsWith("x86_64.AppImage")
        }
    }
}
