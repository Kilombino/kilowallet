#!/bin/bash
# The Lightning engine for the desktop: the same LND tree and commit as the Android engine
# (lnd-engine/ENGINE), bound with gobind's Java glue and built as a Linux shared library
# (libgojni.so) the desktop JVM loads like Android does. Output: $LND/mobile/build/desktop/.
set -e
LND=${LND:?set LND to a checkout of the Lightning Fork (lnd) at the pinned commit}
HERE=$(cd "$(dirname "$0")" && pwd)
cd "$LND"
export GOMAXPROCS=3 GOTOOLCHAIN=local PATH=$HOME/go/pkg/mod/golang.org/toolchain@v0.0.1-go1.26.8.linux-amd64/bin:$HOME/go/bin:$PATH
JDK=${JDK:-/usr/lib/jvm/java-17-openjdk-amd64}
TAGS="mobile signrpc walletrpc chainrpc invoicesrpc routerrpc peersrpc neutrinorpc watchtowerrpc wtclientrpc"
OUT=$(mktemp -d)
GOFLAGS=-mod=mod gobind -lang=go,java -tags="$TAGS" -outdir="$OUT" ./mobile
G=mobile/build/desktop-gobind
rm -rf $G && mkdir -p $G/inc/android && cp "$OUT"/src/gobind/* $G/
# gobind's C glue is Android's: the same JNI, only its file names, log calls and one cast differ.
( cd $G
  for f in *_android.*; do mv "$f" "${f/_android/_desktop}"; done
  sed -i 's/_android\.h/_desktop.h/g' *.c *.h *.go
  sed -i 's|//#cgo LDFLAGS: -llog|//#cgo CFLAGS: -I${SRCDIR}/inc|' seq_desktop.go
  sed -i 's/AttachCurrentThread(jvm, &env, NULL)/AttachCurrentThread(jvm, (void **)\&env, NULL)/' seq_desktop.c
  cp "$HERE/android-log.h" inc/android/log.h
  # In a JVM the process's arguments are Java's (-Djava.library.path=…), and lndmobile's Start
  # appends its own to os.Args before parsing them all: keep only the program name, as an
  # Android app process has.
  printf 'package main\n\nimport "os"\n\nfunc init() {\n\tif len(os.Args) > 1 {\n\t\tos.Args = os.Args[:1]\n\t}\n}\n' > desktop_args.go )
CGO_ENABLED=1 CGO_CFLAGS="-O2 -D__GOBIND_ANDROID__ -I$PWD/$G/inc -I$JDK/include -I$JDK/include/linux" GOFLAGS=-mod=mod \
  nice go build -trimpath -buildmode=c-shared -tags="$TAGS" -ldflags "-s -w" -o mobile/build/desktop/libgojni.so ./$G
# The Java side, for desktop/src/main/java (go/ and lndmobile/).
rm -rf mobile/build/desktop/java && cp -r "$OUT"/java mobile/build/desktop/java
rm -rf "$OUT"
sha256sum mobile/build/desktop/libgojni.so
