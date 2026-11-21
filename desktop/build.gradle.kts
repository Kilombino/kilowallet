import java.security.MessageDigest
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Kilowallet for Linux: the Android app's own sources, compiled for the desktop JVM.
// Nothing here changes the Android build; what Android does through its framework
// (preferences, Keystore, notifications, file pickers, LND in-process) is provided by
// this module's own layer under src/main/kotlin.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(17) }

/**
 * The system this build is for (a package is built on its own system): its natives are in
 * resources/<hostDir>, the layout Compose's appResourcesRootDir uses.
 */
val hostOs = System.getProperty("os.name").lowercase().let { if (it.startsWith("windows")) "windows" else if (it.startsWith("mac")) "macos" else "linux" }
val hostArch = System.getProperty("os.arch").let { if (it == "aarch64" || it == "arm64") "arm64" else "x64" }
val hostDir = "$hostOs-$hostArch"
/** The engines' file names there, as System.loadLibrary("gojni") and ("kilombino_ark") look for them. */
val engineFiles = when (hostOs) {
    "windows" -> listOf("gojni.dll", "kilombino_ark.dll")
    "macos" -> listOf("libgojni.dylib", "libkilombino_ark.dylib")
    else -> listOf("libgojni.so", "libkilombino_ark.so")
}

/** The desktop app's own version (its betas are numbered apart from Android's). */
val desktopVersion = "0.1.0-beta24-roblo1"
val desktopCode = 20

/**
 * 🤖 ROBLO (the Roblosats client tab, roblo-client/CLIENT): on in this beta line, like Android's
 * `beta` build type; -PkwRoblo=false builds the app without it.
 */
val robloOn = providers.gradleProperty("kwRoblo").orNull != "false"
/**
 * A test build (-PkwDebug=true): the ROBLO page's remote debugging port (CDP, for the lab's
 * Playwright scenarios) and its test driver, and only with -ProbloLabZip the regtest lab's client
 * (which then reaches nothing but the lab). Never for anything handed out: packaging refuses it.
 */
val debugBuild = providers.gradleProperty("kwDebug").orNull == "true"
val robloLabZip: String? = providers.gradleProperty("robloLabZip").orNull
fun robloPin(key: String): String = rootProject.file("roblo-client/CLIENT").readLines()
    .first { it.startsWith("$key=") }.substringAfter("=").trim()
fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

/**
 * JetBrains Runtime with JCEF (the Chromium the ROBLO page runs in), pinned in engines/JBR and
 * fetched + checked by engines/fetch-jbr.sh. KW_JBR points at the unpacked runtime: its jcef
 * module is the compile-time API here and the packages' runtime (KW_PACKAGE_JDK).
 */
val jbrHome: String? = System.getenv("KW_JBR")

val writeBuildInfo by tasks.registering {
    val out = layout.buildDirectory.dir("generated/buildinfo")
    outputs.dir(out)
    inputs.property("v", desktopVersion)
    doLast { out.get().file("kilowallet-desktop.properties").asFile.apply { parentFile.mkdirs(); writeText("version=$desktopVersion\ncode=$desktopCode\n") } }
}

/**
 * The shared code's BuildConfig (Android generates it): ROBLO, DEBUG and the version, plus the
 * pinned Roblosats client's SHA-256, checked again when the app loads the zip.
 */
val writeBuildConfig by tasks.registering {
    val out = layout.buildDirectory.dir("generated/buildconfig")
    outputs.dir(out)
    val clientSha = if (robloLabZip != null) sha256(file(robloLabZip)) else robloPin("sha256")
    inputs.property("roblo", robloOn); inputs.property("debug", debugBuild); inputs.property("v", desktopVersion)
    inputs.property("client", clientSha); inputs.property("lab", robloLabZip != null)
    doLast {
        out.get().file("com/kilombino/pyblockwatch/BuildConfig.kt").asFile.apply {
            parentFile.mkdirs()
            writeText("""
                |package com.kilombino.pyblockwatch
                |
                |/** Written by the desktop build (desktop/build.gradle.kts writeBuildConfig), as Android generates it. */
                |object BuildConfig {
                |    const val ROBLO: Boolean = $robloOn
                |    const val DEBUG: Boolean = $debugBuild
                |    const val VERSION_NAME: String = "$desktopVersion"
                |    const val ROBLO_CLIENT_SHA256: String = "$clientSha"
                |    /** A client built for the regtest lab (debug builds only): it may reach nothing but the lab. */
                |    const val ROBLO_LAB: Boolean = ${robloLabZip != null}
                |}
                |""".trimMargin())
        }
    }
}

// The Roblosats client, as pinned (or the lab's in a debug build): the zip itself goes into the
// app; RobloAssets checks its hash against BuildConfig before serving anything from it.
val robloClientFile = if (robloLabZip != null) file(robloLabZip) else rootProject.file("roblo-client/" + robloPin("file"))
val verifyRobloClient by tasks.registering {
    inputs.files(rootProject.file("roblo-client/CLIENT"))
    doLast {
        check(robloLabZip == null || debugBuild) { "robloLabZip is for debug builds (-PkwDebug=true) against the lab only" }
        check(robloClientFile.exists()) { "Missing $robloClientFile — build it in Roblosats with ${robloPin("build")}." }
        if (robloLabZip == null) check(sha256(robloClientFile) == robloPin("sha256")) { "Roblosats client hash ${sha256(robloClientFile)} does not match the pinned ${robloPin("sha256")}" }
    }
}
val robloClientZip by tasks.registering(Sync::class) {
    dependsOn(verifyRobloClient)
    from(robloClientFile) { rename { "roblo-client.zip" } }
    into(layout.buildDirectory.dir("generated/roblo-client/roblo"))
}

/** JCEF's API for compiling (classes of the pinned runtime's jcef module; the runtime brings the rest). */
val jcefClasses by tasks.registering(Exec::class) {
    val dir = layout.buildDirectory.dir("jcef/extracted")
    outputs.dir(dir)
    onlyIf { jbrHome != null }
    doFirst {
        check(File("$jbrHome/jmods/jcef.jmod").isFile) { "KW_JBR=$jbrHome has no jmods/jcef.jmod (engines/fetch-jbr.sh)" }
        dir.get().asFile.deleteRecursively()
    }
    executable = "$jbrHome/bin/jmod" + if (hostOs == "windows") ".exe" else ""
    args("extract", "--dir", dir.get().asFile.path, "$jbrHome/jmods/jcef.jmod")
}
val jcefApi by tasks.registering(Jar::class) {
    from(jcefClasses.map { File(it.outputs.files.singleFile, "classes") }) { exclude("module-info.class") }
    archiveFileName.set("jcef-api.jar")
    destinationDirectory.set(layout.buildDirectory.dir("jcef"))
}

/**
 * The Android app's sources as the desktop compiles them: copied unchanged except for one line
 * per screen, the app's translating Text → the desktop's (the same tables, desktop wording).
 * The pieces the desktop replaces (its own versions are under src/main/kotlin) and the
 * home-screen widget are left out.
 */
val sharedSources by tasks.registering(Sync::class) {
    from("../app/src/main/java") {
        exclude(
            "com/kilombino/pyblockwatch/widget/**",
            "com/kilombino/pyblockwatch/ui/QrScanner.kt",
            "com/kilombino/pyblockwatch/data/UpdateCheck.kt",
            "com/kilombino/pyblockwatch/data/AppUpdater.kt",
            // The phone runs Arti (libarti.so, Android 1.1.7+); the desktop keeps the C tor of the
            // Tor Project's expert bundle, behind the same Tor object (src/main/kotlin).
            "com/kilombino/pyblockwatch/chain/Tor.kt",
            // The ROBLO page's host: Android's WebView there, JCEF here (desktop roblo/RobloWeb,
            // RobloAssets and RobloScreen); the bridge, the store, the watcher and the sheets are shared.
            "com/kilombino/pyblockwatch/roblo/RobloWeb.kt",
            "com/kilombino/pyblockwatch/roblo/RobloAssets.kt",
            "com/kilombino/pyblockwatch/roblo/RobloScreen.kt",
        )
        // The screens' Text is the app's translating one; on the desktop it also says "computer"
        // for "phone" and "password" for "fingerprint" (desktop i18n.Text, over the same tables).
        filter { line ->
            if (line == "import com.kilombino.pyblockwatch.ui.i18n.Text") "import com.kilombino.kilowallet.desktop.i18n.Text" else line
        }
    }
    into(layout.buildDirectory.dir("generated/shared"))
}

sourceSets {
    main {
        kotlin { srcDir(sharedSources); srcDir(writeBuildConfig) }
    }
    // The Android app's own unit tests, run on the desktop JVM too (with the native engines loaded).
    test {
        // Less the widget's, which the desktop does not have, and Arti's (the desktop runs tor, its own Tor.kt).
        kotlin { srcDir("../app/src/test/java"); exclude("com/kilombino/pyblockwatch/widget/**", "com/kilombino/pyblockwatch/chain/TorStateTest.kt") }
        resources { srcDir("../app/src/test/resources") }
    }
}

// The images the shared screens draw, from the Android app's res/ folder.
tasks.named<ProcessResources>("processResources") {
    from(writeBuildInfo)
    if (robloOn) from(robloClientZip) { into("roblo") }
    // The language tables and anything else in the app's assets.
    from("../app/src/main/assets") { into("assets") }
    from("../app/src/main/res") {
        include("drawable-nodpi/*.webp", "mipmap-xxxhdpi/*.webp")
        into("res")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.json:json:20240303")
    // ViewModel, viewModelScope, LifecycleOwner, LocalLifecycleOwner: the multiplatform androidx.lifecycle.
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.11.0")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")
    // JCEF from the pinned JetBrains Runtime: compile against its API, run on that runtime.
    compileOnly(files(jcefApi))
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.kilombino.kilowallet.desktop.MainKt"
        // The JDK the bundled runtime is cut from (jlink). For Linux releases a portable one
        // (Eclipse Temurin, built against an old glibc): a distribution's own JDK ties the
        // AppImage to that distribution's glibc (Ubuntu 24.04's needs 2.38, so it would not start
        // on Ubuntu 22.04 or Debian 12). CI already builds with Temurin.
        System.getenv("KW_PACKAGE_JDK")?.let { javaHome = it }
        // LND (libgojni.so) and Ark (libkilombino_ark.so), loaded by name as on Android.
        jvmArgs("-Djava.library.path=\$APPDIR/resources", "-Dkilowallet.natives=\$APPDIR/resources")
        nativeDistributions {
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            targetFormats(*when (hostOs) {
                "windows" -> arrayOf(TargetFormat.Msi)
                "macos" -> arrayOf(TargetFormat.Dmg)
                else -> arrayOf(TargetFormat.Deb, TargetFormat.AppImage)
            })
            packageName = "Kilowallet"
            packageVersion = desktopVersion.substringBefore('-')
            description = "Kilowallet: Bitcoin BLAKE2b (XBT) wallet with Lightning, Ark and coinjoin (desktop beta)"
            vendor = "Kilombino"
            licenseFile.set(project.file("../LICENSE"))
            // The modules the app uses (as ./gradlew suggestRuntimeModules lists them), not the whole JDK.
            modules("java.instrument", "java.management", "java.naming", "java.net.http", "java.prefs", "java.sql",
                "jdk.crypto.ec", "jdk.unsupported", "jdk.security.auth")
            // ROBLO: JCEF (and what it needs) from the JetBrains Runtime the package is cut from.
            if (robloOn) modules("jcef", "jogl.all", "gluegen.rt", "jdk.unsupported.desktop")
            linux {
                packageName = "kilowallet"
                debPackageVersion = desktopVersion.replace("-", "~")
                debMaintainer = "kilombino@proton.me"
                menuGroup = "Office;Finance"
                appCategory = "Finance"
                // jpackage wants a PNG; the app's images are WebP since 1.1.7 (the same pixels).
                iconFile.set(project.file("packaging/icons/kilowallet.png"))
                installationPath = "/opt"
            }
            windows {
                // The same code for every version, so a new .msi replaces the installed one.
                upgradeUuid = "6d6f1f4e-2b1c-4e7a-9a43-0f5c3b8e7d21"
                menuGroup = "Kilowallet"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                iconFile.set(project.file("packaging/icons/kilowallet.ico"))
            }
            macOS {
                bundleID = "com.kilombino.kilowallet.desktop"
                // A Mac app's version must start above 0: 0.1.0-betaN is 1.0.N here (the app
                // itself, its updates and its screens use desktopVersion).
                packageVersion = "1.0.$desktopCode"
                dmgPackageVersion = "1.0.$desktopCode"
                appCategory = "public.app-category.finance"
                iconFile.set(project.file("packaging/icons/kilowallet.icns"))
            }
        }
    }
}

// Running from the build: the natives straight from resources/<hostDir>, and their pins checked.
tasks.withType<Test>().configureEach {
    val natives = file("resources/$hostDir").absolutePath
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Djava.library.path=$natives", "-Dkilowallet.profile=kilowallet-test") })
}
tasks.withType<JavaExec>().configureEach {
    val natives = file("resources/$hostDir").absolutePath
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Djava.library.path=$natives", "-Dkilowallet.natives=$natives") })
}

// engines/ENGINES pins Linux's engines (built here); a release for Windows or a Mac is built by
// its workflow (.github/workflows/desktop.yml), which writes the hashes of what it built to
// resources/<hostDir>/ENGINES.sha256 for the release notes.
val verifyEngines by tasks.registering {
    doLast {
        if (hostDir != "linux-x64") {
            engineFiles.forEach { check(file("resources/$hostDir/$it").isFile) { "resources/$hostDir/$it is missing" } }
            return@doLast
        }
        val pins = file("engines/ENGINES").readLines().filter { "-sha256-" in it }.associate { it.substringBefore('=') to it.substringAfter('=').trim() }
        mapOf("lnd-sha256-x86_64" to "libgojni.so", "ark-sha256-x86_64" to "libkilombino_ark.so").forEach { (k, f) ->
            val bytes = file("resources/linux-x64/$f").readBytes()
            val h = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(h == pins[k]) { "$f is $h, not the pinned ${pins[k]}" }
        }
    }
}
tasks.matching { it.name.startsWith("package") }.configureEach { dependsOn(verifyEngines) }

// A package is never a test build, and a ROBLO package runs on the pinned JetBrains Runtime.
val checkPackaging by tasks.registering {
    doLast {
        check(!debugBuild && robloLabZip == null) { "-PkwDebug / -ProbloLabZip are for test builds only: not packaged" }
        if (robloOn) {
            val jdk = System.getenv("KW_PACKAGE_JDK")
            check(jdk != null && File("$jdk/jmods/jcef.jmod").isFile) { "ROBLO packages need KW_PACKAGE_JDK = the pinned JetBrains Runtime with JCEF (engines/fetch-jbr.sh)" }
        }
    }
}
tasks.matching { it.name.startsWith("package") || it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach { dependsOn(checkPackaging) }

// jlink copies jcef's natives without their execute bit: Chromium could not start its helper
// process ("GPU process isn't usable") and aborted the whole app the moment ROBLO opened (seen in
// the Ubuntu 22.04 container with the first AppImage). The runtime image gets them back before any
// package (AppImage, .deb, .msi, .dmg) is made from it.
if (robloOn) tasks.matching { it.name == "createRuntimeImage" }.configureEach {
    doLast {
        val lib = layout.buildDirectory.dir("compose/tmp/main/runtime/lib").get().asFile
        listOf("jcef_helper", "cef_server", "chrome-sandbox", "jspawnhelper").map { lib.resolve(it) }.filter { it.isFile }.forEach { f ->
            check(f.setExecutable(true, false)) { "could not make $f executable" }
        }
    }
}

// macOS: JCEF's framework and helper apps live next to the runtime's Home (Contents/Frameworks),
// where jlink does not copy them; the app's runtime gets them as the JetBrains Runtime has them
// (ditto keeps the frameworks' symlinks). The .dmg is made from this app.
if (hostOs == "macos" && robloOn) tasks.matching { it.name == "createDistributable" }.configureEach {
    doLast {
        val jdk = System.getenv("KW_PACKAGE_JDK") ?: error("KW_PACKAGE_JDK")
        val from = File(jdk).parentFile.resolve("Frameworks")
        check(from.resolve("Chromium Embedded Framework.framework").isDirectory) { "$from has no Chromium Embedded Framework" }
        val app = layout.buildDirectory.dir("compose/binaries/main/app").get().asFile.listFiles { f -> f.name.endsWith(".app") }!!.single()
        val to = app.resolve("Contents/runtime/Contents/Frameworks")
        to.mkdirs()
        val p = ProcessBuilder("ditto", from.path, to.path).inheritIO().start()
        check(p.waitFor() == 0) { "ditto failed" }
    }
}
