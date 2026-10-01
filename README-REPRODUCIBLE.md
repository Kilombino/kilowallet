# Reproducible build — PyBLØCK Watch

Rebuild the published APK yourself and confirm it matches, byte for byte.

## 1. What is and is not compiled here

This project is **pure JVM/Kotlin + Android Gradle Plugin**. There is no Rust, no
NDK, no `Cargo.toml`, and no `.so` of ours anywhere.

That is the substantive difference from a typical Bitcoin wallet. Wallets usually
depend on prebuilt native libraries from Maven — `libbdkffi.so`, `libsecp256k1-jni.so`
— which are pinned by hash but **not rebuilt**, so the code that touches your keys
stays outside the reproducible surface. Here there is nothing to leave out: secp256k1
arithmetic, RIPEMD-160, Base58Check, Bech32 and BIP-32 derivation are all Kotlin in
`app/src/main/java/com/kilombino/pyblockwatch/crypto/`, compiled from this repo.

The only native code in the APK is prebuilt helpers with **no cryptographic role**,
pulled transitively and pinned by hash like every other dependency:
`libandroidx.graphics.path.so` (a Compose path parser), and — since 0.2.0 added the
QR scanner — `libimage_processing_util_jni.so` and `libsurface_util_jni.so` from
CameraX (camera frame/surface helpers). The key-handling path stays pure Kotlin from
this repo; none of these `.so` touch it. Drop Compose and the QR scanner for a build
with literally zero `.so`.

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
| Packaged ABIs | all (no `abiFilters`; nothing native of ours to filter) |

## 3. Dependency pinning

`gradle/verification-metadata.xml` records the SHA-256 of **613 resolved artifacts**.
Gradle verifies every one on each build; a mismatch fails the build rather than
producing a quietly different APK. Inspect or regenerate with:

```
./gradlew --write-verification-metadata sha256 assembleRelease
```

## 4. Build

```
./gradlew clean assembleRelease
# → app/build/outputs/apk/release/app-release-unsigned.apk
```

A keystore is only needed to *sign*. The unsigned APK is what you compare.

## 5. Verified result

Version **0.9.0** (versionCode 20):

```
app-release-unsigned.apk
SHA-256  f5a7b6f6cd91add06d018614015dccfa39ecb0af3172f606db8858c6d6820deb
```

(0.8.3 was `1be0aaeab71c775f76d2461017b92003fe31525af14e40c8b00c15d05c84f273`; 0.8.2 was `c2cb689bfa3e2f5cdde1cdb0b5c99a5031572ce94ff64ccc53e074dd80e77ab1`; 0.8.1 was `2b49d1b48c2a166cfc8e0f79f99106e147d79b7198815e4e6bf921fa2a8db7ea`; 0.8.0 was `840057da65ae636fdf7ee017e78b5e0521671e10c38234060c825cb496e6354b`; 0.7.8 was `<icon-only, not tagged>`; 0.7.7 was `99735a5626ded277638672ae92d39b8e5fdb2bbab9e6a9605064c9c690c40325`; 0.7.5 was `7c30055f7f658f8856e668d080cce9a39a62e1ffc27dc389a70fea979cc20544`; 0.7.4 was `941662c367b3e1cd107a7162b97b33c5dc2d5a3cca019f9f8a2a25009bff5c6d`; 0.7.3 was `a994513b7f1f46812d1c4475128f562accb8e550dddb9bc09a1c4099a2087f27`; 0.7.2 was `c0ebcfe958227069797c4d530ceab54fd92e6321c58b9a541c454a5aaba8bf00`; 0.7.1 was `416179406557aa482c95abf6e646bc63f27445741423afdb409649b7f72b5103`; 0.7.0 was `f4651247543d54680210daeae9a8b1f4be7dc57c0b1b49bbb5aae64d376e45a3`; 0.6.0 was `06725332267d8feb1413054d50057ad4337258ca8b3213fade88f4498f716d6f`; 0.5.0 was `5237b543ecd605f7884abb415b811c2753e01a1cf6c101c0b85b8fe172835eac`; 0.4.0 was `4a10be008fbce652bb7a9f596fae48b8b201a5aa3e4182a794454a2f007c53f3`; 0.3.0 was `1459eee0e7b61c7161a37d682310b5c766fef2b45cc9084e957654e28fa1d8b4`; 0.2.0 was `e668221b0eb97ffb38d039580427f63b10d01dc187f69b573d48bbda5247af8c`; 0.1.0 was `9c97676adc3625399c222e5958074a4303e12420e79fe01316ec5ff9b3a86b0f`.)

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

The signed v0.9.0 APK you download has SHA-256
`84dea5c1c9ffb0fb8a747a936a279f4683ef2221d79eefee7c866e309e312e72`; the reproducible
unsigned build (§5) is `f5a7b6f6…`, and `apksigcopier` (§6) confirms the signed APK is
exactly that build plus this signature. It is signed with `--alignment-preserved`, so the
signed file is the unsigned build plus only a signature block — no re-zipping — which is
what lets a verifier's `apksigcopier copy` reproduce it byte for byte. The certificate is
unchanged from 0.1.0 — the v3 lineage means the key is the same across versions.
Distributed via
[GitHub Releases](https://github.com/Kilombino/pyblock-watch/releases/tag/v0.9.0) and
Zapstore.
