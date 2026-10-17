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
| In-app update (APK) | AppImage replaces itself; a .deb is checked, saved to Downloads and the `sudo apt install` line shown (the wallet never asks for root itself); .msi goes to the Windows installer; .dmg is opened to drag over the old app. Only a SHA256SUMS signed (BIP-340) by the pinned release key is trusted, and the version is read from the file names in it: never older than the copy running; betas only for a beta |
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
packaging/build-appimage.sh 0.1.0-beta5       # → build/appimage/Kilowallet-…-x86_64.AppImage
packaging/finish-deb.sh 0.1.0-beta5           # → build/release/kilowallet_…_amd64.deb
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

The .deb installs on Ubuntu 24.04+ and Debian 12/13 (each renamed library has its Debian name as
an alternative); the AppImage runs on other distributions.

## What the password protects, and what it does not

- On disk, the password encrypts the recovery words, the wallet's keys (Ark, Lightning's wallet
  password, coinjoin's own keys) and your node's RPC passwords. Settings, labels, addresses,
  coinjoin sessions and Lightning's channel database are not encrypted (as on the phone).
- While the app is open and unlocked, the keys are in memory: the background work (Lightning,
  coinjoin on its own, Ark renewals, your node) needs them. 🔒 Lock (top left) locks at once; the
  unlock screen can make it lock itself after 15 minutes without use. A wrong password costs a
  growing wait.
- A computer cannot hide a screen from screenshots the way a phone does (on X11 any program can
  capture it): show the recovery words only where nobody and nothing is watching.
- Copied text is taken off the clipboard after a minute; a clipboard manager may keep its own copy.
- The update check asks api.github.com directly (not over Tor), on start and every few minutes;
  it can be turned off in settings.
