# Reproducible build — PyBLØCK Watch

Rebuild the published APK yourself and confirm it matches, byte for byte.

## 1. What is and is not compiled here

The XBT wallet is **pure JVM/Kotlin + Android Gradle Plugin**. secp256k1 arithmetic,
RIPEMD-160, Base58Check, Bech32 and BIP-32 derivation are all Kotlin in
`app/src/main/java/com/kilombino/pyblockwatch/crypto/`, compiled from this repo; the
spending wallet's signatures are RFC 6979 and pinned to the published secp256k1 vectors
in `SigningTest`.

Since 0.10.0 the APK carries **one native library of ours**: the **Ark engine**,
`lib/arm64-v8a/libkilombino_ark.so` — the Paperclip/bark wallet (Rust, MIT) that runs
Ark inside the app. It is not a prebuilt blob: it is rebuilt from a pinned commit of
[Kilombino/paperclip-wallet-app](https://github.com/Kilombino/paperclip-wallet-app),
reproducibly, and the release build refuses any library whose SHA-256 differs from the
pin in `ark-engine/ENGINE` (§8). It holds the Ark wallet's keys; the XBT spending
wallet's keys never reach it except as the shared words, when the user chooses one set
of words for both. It ships for arm64-v8a only; on other ABIs the wallet runs without Ark.

The other native code in the APK is prebuilt helpers with **no cryptographic role**,
pulled transitively and pinned by hash like every other dependency:
`libandroidx.graphics.path.so` (a Compose path parser), and
`libimage_processing_util_jni.so` and `libsurface_util_jni.so` from CameraX.

## 2. Toolchain pins

| Component | Version |
|---|---|
| JDK (build) | Temurin/OpenJDK **17.0.20+8** |
| Android Gradle Plugin | **8.7.3** |
| Gradle (wrapper) | **8.11.1** |
| Kotlin (android + compose) | **2.3.10** |
| compileSdk / targetSdk | **35** / **35** · build-tools **35.0.0** |
| Jetpack Compose | BOM **2024.12.01** |
| R8 / minify | **off** — no obfuscation variance |
| Packaged ABIs | all for the Kotlin app; the Ark engine is arm64-v8a only |
| Ark engine | rustc **1.98.0**, cargo-ndk **4.1.2**, NDK **27.1.12297006**, API **26** |

## 3. Dependency pinning

`gradle/verification-metadata.xml` records the SHA-256 of **613 resolved artifacts**.
Gradle verifies every one on each build; a mismatch fails the build rather than
producing a quietly different APK. Inspect or regenerate with:

```
./gradlew --write-verification-metadata sha256 assembleRelease
```

## 4. Build

First rebuild the Ark engine (§8) and place it at
`app/src/main/jniLibs/arm64-v8a/libkilombino_ark.so`. Then:

```
./gradlew clean assembleRelease
# → app/build/outputs/apk/release/app-release-unsigned.apk
```

A keystore is only needed to *sign*. The unsigned APK is what you compare.

## 5. Verified result

Version **0.17.2** (versionCode 47):

```
app-release-unsigned.apk
SHA-256  ec6c96a573db860b8a9659556c49e0c439b617eab206fed0a349a13f358c9e7e
```

(0.17.1 was `f8cb6fa36c05fbafb0797c203cb806acf07dc8cd0bddddc935a5de2abb4818ef`; 0.17.0 was `6efc7a407670225e628ced115d72f11af7ea25282a3e5e9fce7c5762509af253`; 0.16.1 was `2b7f5ad4a45906c8f55183ee657b203b75edfeed98fcfcc3203694571aa895e0`; 0.16.0 was `42e10088d7765f56b5c1410876804eebaf9b9414b620e079ed65f0e1e1661d18`; 0.15.3 was `3fee1ef211cc4b44b22ebb3871e790f980a8c77b6db896b970c1595c1e8d947a`; 0.15.2 was `a997c6555fb4d1db97bdec97ce4523260f603d62703c5722276d779e5ba4405c`; 0.15.1 was `cba7e43e0680256ca2ef6675775bac4b084e2c91aa3ddc7ec13a9f87792ed9e8`; 0.15.0 was `e667930845ed0089d1118cb44e30e46120d1c4b1055e75a996204863f25b62cd`; 0.14.3 was `5e161cc4765e33e51a2f30c499db1be5ecdd0f10ab58dad95f7be6edbee122ce`; 0.14.2 was `35be33c7c1d4c5d1f4494fb4316a5462dfb646ecac12e78b7648a22a4cf7e556`; 0.14.1 was `eaba27715c4a8900467578d0e0b26f593c15861aa420f6907020560222b04a61`; 0.14.0 was `915d09aea727c90ec635de05ac127862646624fe5a8e6eb8b0810718ca9507be`; 0.13.0 was `2962ffbb0af9d47ce2bbff5b6679d8e3699e9897ee656d6a22a7ef1c33a56af9`; 0.12.0 was `53edfa11ea86c33b6a3fc49fa86c660d6b4304a8605fd531fbafc9ea0d5b5f15`; 0.11.0 was `c31ca2f42b56b9fa842b52c69c54cfe0e978e1bf4c4b856366c6cbb8ce3fd06d`; 0.10.9 was `3034901567dfc0147e8cbdcbc7ebbd023f56642ebeda68f0321007e5fd31f9bf`; 0.10.8 was `d338ca0d443e97ca69574240954b1add58928f31d628e3d78201899e1093a6e8`; 0.10.7 was `194f7711fcd2b35dbe6afaa015da1e1948fa7f28d73b28cccae14c2c01ffcc03`; 0.10.6 was `54377162521af7a4785383186f0fe2f1526dd07e112eccac6621b4e3d1cd0d9f`; 0.10.5 was `c1573940a0a7da5f58f373cd6bcb6806220d14f7e1b4bd7ff549cb977cdeee1a`; 0.10.4 was `a6c9f26f2e1fd1cfad652e6499edae7468e22ae0e670d54156833575b712279e`; 0.10.3 was `764cb47e3d162702623b0b96980f8a10b6829a12e1c02bec87339e0b6e20b6dd`; 0.10.2 was `f18b8145276a4d0f608787aaccf70b51f364abe4f4d90897b2a916b3b9b40ea1`; 0.10.1 was `9c230e10de56a73b1f0e92d488d2773c95f5765addab3bf3d02f33a858a9a0cf`; 0.10.0 was `bbdc36441683d21201181223c3e4d6d5e134f3ff3baebfb67dd1520addead5dc`; 0.9.1 was `6b5b038f5158ab86661e5fce743cd4eca1cd0b37c65c2099bf7c30bdbe3e22d9`; 0.9.0 was `f5a7b6f6cd91add06d018614015dccfa39ecb0af3172f606db8858c6d6820deb`; 0.8.3 was `1be0aaeab71c775f76d2461017b92003fe31525af14e40c8b00c15d05c84f273`; 0.8.2 was `c2cb689bfa3e2f5cdde1cdb0b5c99a5031572ce94ff64ccc53e074dd80e77ab1`; 0.8.1 was `2b49d1b48c2a166cfc8e0f79f99106e147d79b7198815e4e6bf921fa2a8db7ea`; 0.8.0 was `840057da65ae636fdf7ee017e78b5e0521671e10c38234060c825cb496e6354b`; 0.7.8 was `<icon-only, not tagged>`; 0.7.7 was `99735a5626ded277638672ae92d39b8e5fdb2bbab9e6a9605064c9c690c40325`; 0.7.5 was `7c30055f7f658f8856e668d080cce9a39a62e1ffc27dc389a70fea979cc20544`; 0.7.4 was `941662c367b3e1cd107a7162b97b33c5dc2d5a3cca019f9f8a2a25009bff5c6d`; 0.7.3 was `a994513b7f1f46812d1c4475128f562accb8e550dddb9bc09a1c4099a2087f27`; 0.7.2 was `c0ebcfe958227069797c4d530ceab54fd92e6321c58b9a541c454a5aaba8bf00`; 0.7.1 was `416179406557aa482c95abf6e646bc63f27445741423afdb409649b7f72b5103`; 0.7.0 was `f4651247543d54680210daeae9a8b1f4be7dc57c0b1b49bbb5aae64d376e45a3`; 0.6.0 was `06725332267d8feb1413054d50057ad4337258ca8b3213fade88f4498f716d6f`; 0.5.0 was `5237b543ecd605f7884abb415b811c2753e01a1cf6c101c0b85b8fe172835eac`; 0.4.0 was `4a10be008fbce652bb7a9f596fae48b8b201a5aa3e4182a794454a2f007c53f3`; 0.3.0 was `1459eee0e7b61c7161a37d682310b5c766fef2b45cc9084e957654e28fa1d8b4`; 0.2.0 was `e668221b0eb97ffb38d039580427f63b10d01dc187f69b573d48bbda5247af8c`; 0.1.0 was `9c97676adc3625399c222e5958074a4303e12420e79fe01316ec5ff9b3a86b0f`.)

Verified three ways, all producing that identical hash:

- two independent clean builds from two separate fresh clones;
- a clone sitting on a **different commit**;
- a **source tarball with no `.git` directory at all**.

That last case is the one that matters and the reason for the `vcsInfo` line in
`app/build.gradle.kts`. By default AGP writes `META-INF/version-control-info.textproto`
into the APK containing the current git commit SHA, which makes the output depend on
git state rather than on source. Anyone verifying from a tarball, a shallow clone or
an exported archive would then get a different hash and reasonably report "does not
reproduce" — for a wallet, a false alarm of that kind is expensive. With the stamp
disabled, the APK is a function of the source alone.

## 6. Comparing against a signed release

The signing block differs by signer by design, so compare the **unsigned** APK:

```
apksigcopier extract published.apk sig.zip
diffoscope your-unsigned.apk published-unsigned.apk
```

Or copy the published signature onto your own build and check the whole file:

```
apksigcopier copy published.apk your-unsigned.apk rebuilt.apk
cmp rebuilt.apk published.apk
```

Do **not** report "does not reproduce" because of the signature — only the signing
block should differ.

## 7. Signing

Releases are signed with APK Signature Scheme **v2 + v3**. v3 carries a rotation
lineage, so the key can be rotated later without users reinstalling and losing their
data. Verify with:

```
apksigner verify -v --print-certs the.apk
```

Published **v0.1.0** signer certificate SHA-256 digest — what AppVerifier shows, and
what every future version must keep:

```
b8d7ad679fbfbe39f5640bce01d675347f52b27b7ae6f3731d2ad982c92ef135
```

The signed v0.17.2 APK you download has SHA-256
`b43e812851e510931e73e620d0dad97fa5ff122e457ff17eb1bbb24c6447c1eb`; the reproducible
unsigned build (§5) is `ec6c96a…`, and `apksigcopier` (§6) confirms the signed APK is
exactly that build plus this signature. It is signed with `--alignment-preserved`, so the
signed file is the unsigned build plus only a signature block — no re-zipping — which is
what lets a verifier's `apksigcopier copy` reproduce it byte for byte. The certificate is
unchanged from 0.1.0 — the v3 lineage means the key is the same across versions.
Distributed via
[GitHub Releases](https://github.com/Kilombino/kilowallet/releases/tag/v0.17.2) and
Zapstore.

## 8. The Ark engine

`ark-engine/ENGINE` pins the library: fork, commit, toolchain and SHA-256. Rebuild it:

```
git clone https://github.com/Kilombino/paperclip-wallet-app && cd paperclip-wallet-app
git checkout 6027909
ANDROID_NDK_HOME=/path/to/ndk/27.1.12297006 ./kilombino-ark/build-android.sh
# → d7220c3647195b7b8ef7d32ba5157c3bcb67872e647eb522c0ba5d9b7a95e181
```

The script remaps the source and toolchain paths, so the result does not depend on where
anything lives: two clean clones in different directories produced that identical hash. (0.15.0–0.15.1 shipped `5f0e55e089b652b6d29833fc92a2ea6100846ad02f43ca85a183e4b974474c03`, commit 72de502; 0.14.3 shipped `ff1009e99da20516ce11ab195f421a5c9b08dbe4e967c2cf474a860b435a662f`, commit fbc0d05; 0.11.0–0.14.2 shipped engines up to `c3e4856f3a3879071edb125a300efe853dae5c701cbc75216f8887f55ccc206e`, commit a7d0f82; 0.10.8–0.10.9 shipped `1e6cb4466b2fe4b9c1796a82e6a6dc2eb0a7cb6a8e2d591e116f9a7d1cb1a445`, commit bd446d9; 0.10.5–0.10.7 shipped `7fd3728da779e1cdd84550e9db8f03ab206c20701b53115be705c43ec4d70381`, commit 8d6be19; 0.10.4 shipped `6378b4e6bcf20502db958cd73c6fe4bfff5fe5c0c5eacaf3304191bacefd1780`, commit 7b33f3c; 0.10.3 shipped `a3b019ef6b782084f6b59797dee7ea5167b61bd1cb5ec8a13242bb0342202a44`, commit 0769c94; 0.10.0–0.10.2 shipped `29eb7ec38aef01563c581f94204c3bd5020f0a52245f679320b58a537d7692d9`, commit 7fadbef.)
`./gradlew assembleRelease` runs `verifyArkEngine` first and fails if the library in
`jniLibs` differs from the pin, or if any other ABI directory is present.
