package com.kilombino.kilowallet.desktop

/** The desktop build's version (set by the build in kilowallet-desktop.properties) and how it was installed. */
object BuildInfo {
    private val props = java.util.Properties().apply {
        BuildInfo::class.java.getResourceAsStream("/kilowallet-desktop.properties")?.use { load(it) }
    }
    val VERSION: String = props.getProperty("version", "0.0.0-dev")
    val CODE: Long = props.getProperty("code", "0").toLong()

    /** "appimage" (it updates its own file), "deb" (installed in /opt/kilowallet) or "dev". */
    fun packaging(): String = when {
        System.getenv("APPIMAGE") != null -> "appimage"
        System.getProperty("compose.application.resources.dir")?.startsWith("/opt/kilowallet") == true -> "deb"
        else -> "dev"
    }
}
