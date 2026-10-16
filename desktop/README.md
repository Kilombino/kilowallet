# Kilowallet for Linux (desktop beta)

The Android app, compiled for the desktop JVM. The shared code is `app/src/main/java`, used
**unchanged**: the build copies it and alters one line per screen
(`import androidx.compose.material3.Text` → the desktop's translating `Text`). Nothing in this
module changes the Android build. The APK built from this branch hashes the same as without it.

## What stands in for Android

| Android | Desktop (`src/main/kotlin`) |
|---|---|
| Context, SharedPreferences, files | `~/.local/share/kilowallet` (owner-only), preferences as JSON files |
| Keystore (`AndroidKeyStore`) | a JCA provider of the same name: random AES keys wrapped under a key derived from the wallet's password with scrypt (2^17, 8, 1) |
| Fingerprint prompt (BiometricPrompt) | the wallet's password |
| Notifications | the system tray (pop-ups); background watching keeps running with the window closed |
| Foreground services | the same `Service` classes, run inside the app |
| File pickers, share | the system's file dialog; sharing copies to the clipboard |
| Camera QR scanner | webcam through ffmpeg (if installed), the screen, an image file or a pasted image |
| LND (gomobile AAR) | the same LND commit bound with gobind's Java glue, built as `libgojni.so` for x86_64 (`engines/build-lnd.sh`) |
| Ark engine | the same crate and commit, built for x86_64 Linux (`engines/build-ark.sh`); it also carries libsecp256k1 |
| Tor (tor-android) | the system's `tor` program, run as a child process |
| In-app update (APK) | AppImage replaces itself; .deb installs with pkexec. Only a SHA256SUMS signed (BIP-340) by the pinned release key is trusted |
| Home-screen widget | none |

Languages: the app's own eight (English, Spanish, Chinese, French, Italian, German, Catalan,
Portuguese), from `app/src/main/assets/i18n`, following the system's language unless one is
chosen. The desktop's own strings (unlock screen, QR reader, tray) are in
`src/main/resources/i18n-desktop/<lang>.json`. "Phone" becomes "computer" and "fingerprint"
becomes "password" in every language.

## Build

```
LND=… engines/build-lnd.sh   # → libgojni.so      (pins in engines/ENGINES)
ARK=… engines/build-ark.sh   # → libkilombino_ark.so
cp both into desktop/resources/linux-x64/
./gradlew :desktop:test                       # the Android app's unit tests too, on the desktop JVM
./gradlew :desktop:packageDeb :desktop:createDistributable
packaging/build-appimage.sh 0.1.0-beta2       # → build/appimage/Kilowallet-…-x86_64.AppImage
packaging/finish-deb.sh 0.1.0-beta2           # → build/release/kilowallet_…_amd64.deb
packaging/sign-release.py OUTDIR FILES…       # SHA256SUMS + SHA256SUMS.sig
```

Run from the build: `./gradlew :desktop:run` (add `JAVA_TOOL_OPTIONS=-Dkilowallet.profile=NAME`
for a separate data folder).

## Verify a release

```
sha256sum -c SHA256SUMS
# signature: BIP-340 over SHA-256(SHA256SUMS), key 3e2fcd910f0e78ab222bacadb1c438f5deb7677319e8f95b01417a603c01f257
```

The .deb targets Ubuntu 24.04+ / Debian 13 (its dependency names); the AppImage runs on
other distributions.
