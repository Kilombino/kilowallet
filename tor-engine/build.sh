#!/bin/bash
# The Tor engine: Arti (the Tor Project's Tor in Rust), built from a pinned tag as an Android
# program (libarti.so, so Android extracts it next to the app's other native code and the app
# can run it), for arm64-v8a (and x86_64 for the emulator). Pinned in tor-engine/ENGINE.
#   ARTI=<checkout of https://gitlab.torproject.org/tpo/core/arti at the tag> tor-engine/build.sh [arm64-v8a|x86_64]
set -euo pipefail
ARTI=${ARTI:?set ARTI to a checkout of arti at the pinned tag}
ABI=${1:-arm64-v8a}
cd "$ARTI"
export PATH=$HOME/.cargo/bin:$PATH
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.1.12297006}
# No build paths in the program: the same bytes on any machine.
export RUSTFLAGS="--remap-path-prefix=$PWD=/src --remap-path-prefix=$HOME/.cargo=/cargo --remap-path-prefix=$HOME/.rustup=/rustup --remap-path-prefix=$ANDROID_NDK_HOME=/ndk -C link-arg=-Wl,--build-id=none"
# What the wallet needs: a SOCKS proxy to the Tor network and to .onion services, with rustls
# (no OpenSSL) and SQLite built in. No onion service hosting, bridges or pluggable transports.
FEATURES="tokio,rustls-ring,static-sqlite,onion-service-client,vanguards,flowctl-cc,compression,harden"
# All symbols stripped, not only debug info: the program is 4 MB smaller installed and
# 0.7 MB smaller in the APK; a panic in tor.log then shows addresses instead of names.
export CARGO_PROFILE_RELEASE_SMALL_STRIP=symbols
cargo ndk -t "$ABI" --platform 26 build --locked --profile release-small -p arti --no-default-features --features "$FEATURES"
T=$( [ "$ABI" = x86_64 ] && echo x86_64-linux-android || echo aarch64-linux-android )
OUT=target/$T/release-small/arti
sha256sum "$OUT"
