#!/bin/bash
# The Ark engine for the desktop: the same crate and commit as Android's (ark-engine/ENGINE),
# built for x86_64 Linux with the same profile and path remapping.
set -euo pipefail
ARK=${ARK:-$HOME/Projects/blake2b/paperclip-wallet}
cd "$ARK"
export RUSTFLAGS="--remap-path-prefix=$PWD=/src --remap-path-prefix=$HOME/.cargo=/cargo --remap-path-prefix=$HOME/.rustup=/rustup"
cargo build --locked --profile android --target x86_64-unknown-linux-gnu -p kilombino-ark
sha256sum target/x86_64-unknown-linux-gnu/android/libkilombino_ark.so
