import java.security.MessageDigest
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing credentials live in keystore.properties, which is gitignored and
// never committed. Without that file the project still builds — you just get an
// unsigned APK, which is exactly what a third party verifying reproducibility wants.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.kilombino.pyblockwatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kilombino.pyblockwatch"
        minSdk = 26
        targetSdk = 35
        versionCode = 51
        versionName = "0.19.0-beta2"
        // One native library of ours: the Ark engine, arm64-v8a only, rebuilt from a pinned
        // commit and checked against ark-engine/ENGINE before every release build. On other
        // ABIs the wallet runs without Ark. See README-REPRODUCIBLE.md §1.
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // v1 off, v2 + v3 on. v3 carries a rotation lineage, so this key can be
                // replaced later without users having to uninstall and lose their data —
                // the gap the upstream PyBLØCK ᛒ app had before it added v3.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")

            // Do NOT stamp the git commit into the APK.
            //
            // By default AGP writes META-INF/version-control-info.textproto containing
            // the current commit SHA. That makes the APK depend on git state rather
            // than on source, and it breaks verification in a way that looks alarming:
            // anyone building from a source tarball, a shallow clone, or an exported
            // archive has no git metadata and gets a different hash — reporting "does
            // not reproduce" when the code is in fact identical.
            vcsInfo { include = false }
            // R8 on: it drops the unused code and resources (the APK went from 20.6 to
            // 14 MB and the dex from 22 to 3.4 MB) and starts faster. Its output is
            // deterministic, so two clean builds still give the same APK (§5); the only
            // name-sensitive code, the Ark engine's JNI class, is kept in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        // The Ark engine is a large native library: store it compressed in the APK (Android
        // extracts it at install) instead of uncompressed and page-aligned, which would make
        // the download several times bigger.
        jniLibs.useLegacyPackaging = true
    }
}

// A release must carry exactly the Ark engine pinned in ark-engine/ENGINE: anyone can
// rebuild it from that commit and get the same SHA-256 (README-REPRODUCIBLE.md §8).
val verifyArkEngine by tasks.registering {
    val pin = rootProject.file("ark-engine/ENGINE")
    val so = file("src/main/jniLibs/arm64-v8a/libkilombino_ark.so")
    inputs.files(pin)
    doLast {
        val expected = pin.readLines().first { it.startsWith("sha256=") }.substringAfter("=").trim()
        check(so.exists()) { "Missing $so — build it as README-REPRODUCIBLE.md §8 describes." }
        val actual = MessageDigest.getInstance("SHA-256").digest(so.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actual == expected) { "Ark engine hash $actual does not match the pinned $expected" }
        // Only arm64 ships; a stray emulator build must not reach a release.
        val others = file("src/main/jniLibs").listFiles()?.filter { it.isDirectory && it.name != "arm64-v8a" }.orEmpty()
        check(others.isEmpty()) { "Only arm64-v8a may ship; remove ${others.joinToString { it.name }}" }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyArkEngine) }

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // QR scanning: CameraX for the preview/analysis and ZXing core (pure Java, no JNI)
    // for decoding — reading an xpub off the screen is a convenience, not a crypto path.
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.zxing:core:3.5.3")

    // Biometric / device-credential gate for spending. This does NOT sign or hold keys — it
    // only unlocks the Android Keystore cipher that decrypts the seed (see SeedVault). The
    // signing itself stays in the pure-Kotlin crypto path.
    implementation("androidx.biometric:biometric:1.1.0")
    // biometric 1.1.0 pins an old androidx.fragment (1.2.x); registerForActivityResult needs
    // 1.3.0+, so pull a current fragment forward explicitly.
    implementation("androidx.fragment:fragment:1.8.5")

    // NOTE: there is deliberately no crypto dependency here — no BouncyCastle, no
    // bdk, no secp256k1 JNI. secp256k1, RIPEMD-160, Base58 and Bech32 all live in
    // app/src/main/java/.../crypto/ so the entire cryptographic path is source you
    // can read and rebuild. JSON comes from org.json, which is part of Android.

    testImplementation("junit:junit:4.13.2")
    // The real org.json for JVM tests (Android's copy is only stubs off-device). Test-only: not in the APK.
    testImplementation("org.json:json:20240303")
}
