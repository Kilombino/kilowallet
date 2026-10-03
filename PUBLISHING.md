# Publishing runbook

Everything needed to cut a signed release from a clean machine and put it on GitHub
Releases and Zapstore. Follow it in order; each step verifies the previous one.

Nothing here needs the machine that wrote the code. The only thing that must never
move between machines is the signing keystore.

---

## 0. Toolchain

Reproducibility depends on the exact versions in
[README-REPRODUCIBLE.md](README-REPRODUCIBLE.md) §2. A distro-packaged JDK will
usually be a different build and can produce a different APK, so pin it:

```bash
mkdir -p ~/pyblock-toolchain && cd ~/pyblock-toolchain
curl -L -o jdk17.tar.gz "https://api.adoptium.net/v3/binary/version/jdk-17.0.20%2B8/linux/x64/jdk/hotspot/normal/eclipse"
mkdir -p jdk && tar -xzf jdk17.tar.gz -C jdk && rm jdk17.tar.gz
curl -L -o cmdt.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
mkdir -p sdk/cmdline-tools && unzip -q cmdt.zip -d sdk/cmdline-tools \
  && mv sdk/cmdline-tools/cmdline-tools sdk/cmdline-tools/latest && rm cmdt.zip
```

Then, in every shell you use for the steps below:

```bash
export JAVA_HOME=~/pyblock-toolchain/jdk/jdk-17.0.20+8
export ANDROID_HOME=~/pyblock-toolchain/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME --licenses >/dev/null
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME \
  "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

Also useful for step 4:

```bash
python3 -m venv ~/pyblock-toolchain/venv && ~/pyblock-toolchain/venv/bin/pip install apksigcopier
```

## 1. Clone

```bash
git clone git@github.com:Kilombino/kilowallet.git && cd kilowallet
```

## 2. Run the tests

Nine vectors: the RIPEMD-160 specification, the published BIP-49 and BIP-84 account
vectors, and an Electrum scripthash confirmed against the live servers. If any of
these fail, stop — the wallet would be deriving wrong addresses.

```bash
./gradlew :app:testDebugUnitTest
```

## 3. Verify the build reproduces before signing anything

```bash
./gradlew clean assembleRelease
sha256sum app/build/outputs/apk/release/app-release-unsigned.apk
```

Must print the hash recorded in README-REPRODUCIBLE.md §5 for this version. If it
does not, do **not** publish: something in the toolchain differs from the pins, and
shipping it would break every third party trying to verify the release.

## 4. Create the signing key

Do this **once**, on the machine that will publish, and never move the file or the
passwords through a chat, a paste bin, or a repo. Back it up offline: losing it means
future releases cannot be installed as an update over this one.

```bash
keytool -genkeypair -v -keystore ~/pyblock-watch-release.jks \
  -alias pyblockwatch -keyalg RSA -keysize 4096 -validity 10000
```

Then, in the repo (`keystore.properties` is gitignored — never commit it):

```bash
cp keystore.properties.example keystore.properties
$EDITOR keystore.properties     # storeFile=/home/YOU/pyblock-watch-release.jks + passwords
```

## 5. Build signed and confirm signing is correct

```bash
./gradlew clean assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
$ANDROID_HOME/build-tools/35.0.0/apksigner verify -v --print-certs $APK
```

Expect `v1 scheme: false`, `v2 scheme: true`, `v3 scheme: true`. Record the
`Signer #1 certificate SHA-256 digest` — that is what users check in AppVerifier, and
it must stay identical for the life of the app. Put it in README-REPRODUCIBLE.md §7.

## 6. Confirm the signed APK still reproduces

The signing block differs by signer by design, so verify by copying the signature
onto an independent unsigned build and comparing the whole file:

```bash
git clone . /tmp/verify-build && (cd /tmp/verify-build && rm -f keystore.properties && ./gradlew clean assembleRelease)
~/pyblock-toolchain/venv/bin/apksigcopier copy \
  app/build/outputs/apk/release/app-release.apk \
  /tmp/verify-build/app/build/outputs/apk/release/app-release-unsigned.apk \
  /tmp/rebuilt.apk
cmp /tmp/rebuilt.apk app/build/outputs/apk/release/app-release.apk && echo "REPRODUCES"
```

## 7. Tag and publish the GitHub release

```bash
sha256sum app/build/outputs/apk/release/app-release.apk    # put this in the release notes
cp app/build/outputs/apk/release/app-release.apk ~/pyblock-watch-0.1.0.apk
git tag -a v0.1.0 -m "PyBLØCK Watch 0.1.0"
git push origin v0.1.0
```

Then either with the CLI:

```bash
gh release create v0.1.0 ~/pyblock-watch-0.1.0.apk \
  --title "PyBLØCK Watch 0.1.0" --notes-file release-notes.md
```

…or on the web at `https://github.com/Kilombino/kilowallet/releases/new`, choosing
tag `v0.1.0` and attaching the APK.

**Always publish both hashes in the release notes** — the signed APK's SHA-256 (what
users check before installing) and the certificate SHA-256 (what AppVerifier shows),
plus a pointer to README-REPRODUCIBLE.md.

## 8. Zapstore

Zapstore reads GitHub releases directly, so step 7 must be done first. Publishing is
free and needs no registration, but it does need a **nostr identity** — the same npub
you already use is fine.

```bash
go install github.com/zapstore/zsp@latest      # or grab a binary from its releases
zsp publish --wizard
```

The wizard writes a `zapstore.yaml` at the repo root containing your repository URL
and your npub. **Commit and push that file**: the relay fetches it, checks the pubkey
matches, and whitelists you automatically.

On the first publish you link your APK signing certificate to your nostr identity via
NIP-C1. That link is what lets Zapstore verify future releases really came from you,
so it must be the keystore from step 4 — which is why that key has to stay on one
machine and be backed up.

For unattended releases later, sign with a NIP-46 bunker rather than putting an nsec
in the environment:

```bash
SIGN_WITH="bunker://..." zsp publish -r github.com/Kilombino/kilowallet
```

## 9. Subsequent releases

Bump `versionCode` and `versionName` in `app/build.gradle.kts`, repeat steps 2–8, and
update the recorded hash in README-REPRODUCIBLE.md §5. The certificate fingerprint
never changes.
