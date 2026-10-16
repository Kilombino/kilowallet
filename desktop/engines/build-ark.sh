#!/bin/bash
# The Ark engine for the desktop: the same crate and commit as Android's (ark-engine/ENGINE),
# built with the same profile and path remapping for this system (or TARGET):
# libkilombino_ark.so (Linux), kilombino_ark.dll (Windows), libkilombino_ark.dylib (macOS).
set -euo pipefail
ARK=${ARK:?set ARK to a checkout of the Ark engine at the pinned commit}
cd "$ARK"
if [ -z "${TARGET:-}" ]; then
  case "$(uname -s)-$(uname -m)" in
    Linux-*) TARGET=x86_64-unknown-linux-gnu ;;
    Darwin-arm64) TARGET=aarch64-apple-darwin ;;
    Darwin-*) TARGET=x86_64-apple-darwin ;;
    *) TARGET=x86_64-pc-windows-msvc ;;
  esac
fi
case "$TARGET" in
  *windows*) LIB=kilombino_ark.dll ;;
  *darwin*) LIB=libkilombino_ark.dylib ;;
  *) LIB=libkilombino_ark.so ;;
esac
export RUSTFLAGS="--remap-path-prefix=$PWD=/src --remap-path-prefix=$HOME/.cargo=/cargo --remap-path-prefix=$HOME/.rustup=/rustup"
cargo build --locked --profile android --target "$TARGET" -p kilombino-ark --lib
F=target/$TARGET/android/$LIB
shasum -a 256 "$F" 2>/dev/null || sha256sum "$F"
