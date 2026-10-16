# Kilowallet desktop (beta): Linux, Windows, macOS

The Android app, compiled for the desktop JVM. The shared code is `app/src/main/java`, used
**unchanged**: the build copies it and alters one line per screen
(`import androidx.compose.material3.Text` → the desktop's translating `Text`). Nothing in this
module changes the Android build. The APK built from this branch hashes the same as without it.

## What stands in for Android

| Android | Desktop (`src/main/kotlin`) |
|---|---|
| Context, SharedPreferences, files | `~/.local/share/kilowallet` (owner-only), `%APPDATA%\kilowallet`, `~/Library/Application Support/kilowallet`; preferences as JSON files |
| Keystore (`AndroidKeyStore`) | a JCA provider of the same name: random AES keys wrapped under a key derived from the wallet's password with scrypt (2^17, 8, 1) |
| Fingerprint prompt (BiometricPrompt) | the wallet's password |
| Notifications | the system tray (pop-ups); background watching keeps running with the window closed |
| Foreground services | the same `Service` classes, run inside the app |
| File pickers, share | the system's file dialog; sharing copies to the clipboard |
| Camera QR scanner | webcam through ffmpeg (if installed), the screen, an image file or a pasted image |
| LND (gomobile AAR) | the same LND commit bound with gobind's Java glue, built as `libgojni.so` / `gojni.dll` / `libgojni.dylib` (`engines/build-lnd.sh`) |
| Ark engine | the same crate and commit, built for each system (`engines/build-ark.sh`); it also carries libsecp256k1 |
| Tor (tor-android) | a `tor` program run as a child process: the system's on Linux, the Tor Project's expert bundle shipped inside the Windows and macOS packages |
| In-app update (APK) | AppImage replaces itself; .deb installs with pkexec; .msi goes to the Windows installer; .dmg is opened to drag over the old app. Only a SHA256SUMS signed (BIP-340) by the pinned release key is trusted |
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
packaging/build-appimage.sh 0.1.0-beta3       # → build/appimage/Kilowallet-…-x86_64.AppImage
packaging/finish-deb.sh 0.1.0-beta3           # → build/release/kilowallet_…_amd64.deb
packaging/sign-release.py OUTDIR FILES…       # SHA256SUMS + SHA256SUMS.sig
```

Run from the build: `./gradlew :desktop:run` (add `JAVA_TOOL_OPTIONS=-Dkilowallet.profile=NAME`
for a separate data folder).

## Windows and macOS

Built by `.github/workflows/desktop.yml` on GitHub's runners from this branch: the engines from
the same pinned commits (with `engines/lnd-overlay` over LND, as the Android engine was built),
tor from the Tor Project's expert bundle pinned by hash, the tests run, then an .msi
(`Kilowallet-<v>-windows-x64.msi`) and two .dmg (`-macos-arm64`, `-macos-x64`). The release key
is not in CI: the packages are signed into SHA256SUMS afterwards, with the Linux ones.

The packages are not signed by Microsoft or Apple:
- Windows: SmartScreen says "Windows protected your PC": More info → Run anyway.
- macOS: open the .dmg and drag Kilowallet to Applications, then right-click it → Open → Open.
  If macOS says it is damaged: `xattr -cr /Applications/Kilowallet.app` in Terminal, then open it.

## Verify a release

```
sha256sum -c SHA256SUMS
# signature: BIP-340 over SHA-256(SHA256SUMS), key 3e2fcd910f0e78ab222bacadb1c438f5deb7677319e8f95b01417a603c01f257
```

The .deb targets Ubuntu 24.04+ / Debian 13 (its dependency names); the AppImage runs on
other distributions.
