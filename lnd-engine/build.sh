#!/bin/bash
set -e
cd /mnt/datos2/lnd-mobile21
export ANDROID_HOME=$HOME/Android/Sdk ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/27.1.12297006
export GOMAXPROCS=3 GOTOOLCHAIN=local PATH=$HOME/go/pkg/mod/golang.org/toolchain@v0.0.1-go1.26.8.linux-amd64/bin:$PATH
ls mobile/*_generated.go >/dev/null 2>&1 || (cd lnrpc && COMPILE_MOBILE=1 SUBSERVER_PREFIX=1 nice ./gen_protos_docker.sh)
echo "== gomobile"; go install golang.org/x/mobile/cmd/gomobile@latest && go install golang.org/x/mobile/cmd/gobind@latest
export PATH=$HOME/go/bin:$PATH
gomobile init
GOFLAGS=-mod=mod go get golang.org/x/mobile/bind@latest
mkdir -p mobile/build/android
TAGS="mobile signrpc walletrpc chainrpc invoicesrpc routerrpc peersrpc neutrinorpc watchtowerrpc wtclientrpc"
echo "== bind"; GOFLAGS=-mod=mod nice gomobile bind -target=android/arm64,android/amd64 -androidapi 26 -tags="$TAGS" -ldflags "-s -w -extldflags '-Wl,-z,max-page-size=16384'" -v -o mobile/build/android/Lndmobile.aar ./mobile
ls -la mobile/build/android/
