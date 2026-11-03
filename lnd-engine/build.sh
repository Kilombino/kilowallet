#!/bin/bash
set -e
# Kilombino/lnd branch neutrino-blake2b-2: tag v0.21.4-beta-blake2b.21.1 + gomobile, the generated
# mobile wrappers and neutrino from paulscode/neutrino-blake2b v0.17.1-blake2b.2.
cd "${LND_DIR:-/mnt/datos2/lnd-mobile-n2}"
export ANDROID_HOME=$HOME/Android/Sdk ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/27.1.12297006
export GOMAXPROCS=3 GOTOOLCHAIN=local PATH=$HOME/go/pkg/mod/golang.org/toolchain@v0.0.1-go1.27.3.linux-amd64/bin:$PATH
# Neutrino that advertises NODE_BLAKE2B (Knots 29.4.3 serves headers past the fork only to such peers):
# upstream since v0.17.1-blake2b.2 (already in the branch's go.mod; kept here so a bare tag works too).
GOFLAGS=-mod=mod go mod edit -replace github.com/lightninglabs/neutrino=github.com/paulscode/neutrino-blake2b@v0.17.1-blake2b.2
ls mobile/*_generated.go >/dev/null 2>&1 || (cd lnrpc && COMPILE_MOBILE=1 SUBSERVER_PREFIX=1 nice ./gen_protos_docker.sh)
echo "== gomobile"; go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20261011034528-197770444867 && go install golang.org/x/mobile/cmd/gobind@v0.0.0-20261011034528-197770444867
export PATH=$HOME/go/bin:$PATH
gomobile init
GOFLAGS=-mod=mod go get golang.org/x/mobile/bind@v0.0.0-20261011034528-197770444867
mkdir -p mobile/build/android
# offersrpc: pay BOLT 12 offers (lno1…).
# -trimpath: without it the engine carries gomobile's random work dir (/tmp/gomobile-work-N) and no
# two builds are the same. The checkout path stays in its build info (gomobile's replace of lnd), so
# build in the same directory (LND_DIR, see ENGINE) to get the pinned hash.
TAGS="mobile signrpc walletrpc chainrpc invoicesrpc routerrpc peersrpc neutrinorpc watchtowerrpc wtclientrpc offersrpc"
echo "== bind"; GOFLAGS=-mod=mod nice gomobile bind -trimpath -target=android/arm64,android/amd64 -androidapi 26 -tags="$TAGS" -ldflags "-s -w -extldflags '-Wl,-z,max-page-size=16384'" -v -o mobile/build/android/Lndmobile.aar ./mobile
ls -la mobile/build/android/
