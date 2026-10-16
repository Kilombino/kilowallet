#!/bin/bash
# The Lightning engine for the desktop: the same LND tree and commit as the Android engine
# (lnd-engine/ENGINE), bound with gobind's Java glue and built as a shared library the desktop
# JVM loads like Android does: libgojni.so (Linux), gojni.dll (Windows), libgojni.dylib (macOS).
# Output: $LND/mobile/build/desktop/.
#
# LND: a checkout of Kilombino/lnd at the pinned commit. With LND_OVERLAY=1 the files in
# lnd-overlay/ are copied over it first: the gomobile go.mod and go.sum and the generated mobile
# API (what lnrpc/gen_protos_docker.sh writes with COMPILE_MOBILE=1), as the Android engine was
# built. Runs on Linux, macOS and Windows (Git Bash, with MinGW-w64's gcc).
set -e
LND=${LND:?set LND to a checkout of the Lightning Fork (lnd) at the pinned commit}
HERE=$(cd "$(dirname "$0")" && pwd)
[ "${LND_OVERLAY:-0}" = 1 ] && cp -R "$HERE/lnd-overlay/." "$LND/"
cd "$LND"
export GOTOOLCHAIN=local
TC=$HOME/go/pkg/mod/golang.org/toolchain@v0.0.1-go1.26.8.linux-amd64/bin
[ -d "$TC" ] && export GOMAXPROCS=3 PATH=$TC:$PATH
export PATH=$(go env GOPATH)/bin:$PATH
JDK=${JDK:-${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}}
case "$(uname -s)" in
  Linux*) JNI_OS=linux; OUTLIB=libgojni.so; EXTRA_LD="" ;;
  Darwin*) JNI_OS=darwin; OUTLIB=libgojni.dylib; EXTRA_LD="" ;;
  MINGW*|MSYS*|CYGWIN*) JNI_OS=win32; OUTLIB=gojni.dll; EXTRA_LD="-Wl,-Bstatic -lpthread -Wl,-Bdynamic" ;;
  *) echo "unknown system $(uname -s)"; exit 1 ;;
esac
TAGS="mobile signrpc walletrpc chainrpc invoicesrpc routerrpc peersrpc neutrinorpc watchtowerrpc wtclientrpc"
OUT=$(mktemp -d)
GOFLAGS=-mod=mod gobind -lang=go,java -tags="$TAGS" -outdir="$OUT" ./mobile
G=mobile/build/desktop-gobind
rm -rf $G && mkdir -p $G/inc/android && cp "$OUT"/src/gobind/* $G/
# gobind's C glue is Android's: the same JNI, only its file names, log calls and one cast differ.
( cd $G
  for f in *_android.*; do mv "$f" "${f/_android/_desktop}"; done
  perl -pi -e 's/_android\.h/_desktop.h/g' *.c *.h *.go
  perl -pi -e 's|//#cgo LDFLAGS: -llog|//#cgo CFLAGS: -I\${SRCDIR}/inc|' seq_desktop.go
  perl -pi -e 's/AttachCurrentThread\(jvm, &env, NULL\)/AttachCurrentThread(jvm, (void **)&env, NULL)/' seq_desktop.c
  # On macOS gobind's seq.go would also pick its Objective-C glue: here it is the JNI one everywhere.
  if [ "$JNI_OS" = darwin ]; then perl -ni -e 'print unless m|^// #cgo darwin CFLAGS: -D__GOBIND_DARWIN__|' seq.go; fi
  cp "$HERE/android-log.h" inc/android/log.h
  # In a JVM the process's arguments are Java's (-Djava.library.path=…), and lndmobile's Start
  # appends its own to os.Args before parsing them all: keep only the program name, as an
  # Android app process has.
  printf 'package main\n\nimport "os"\n\nfunc init() {\n\tif len(os.Args) > 1 {\n\t\tos.Args = os.Args[:1]\n\t}\n}\n' > desktop_args.go )
NICE=$(command -v nice || true)
CGO_ENABLED=1 CGO_CFLAGS="-O2 -D__GOBIND_ANDROID__ -I$PWD/$G/inc -I$JDK/include -I$JDK/include/$JNI_OS" CGO_LDFLAGS="$EXTRA_LD" GOFLAGS=-mod=mod \
  $NICE go build -trimpath -buildmode=c-shared -tags="$TAGS" -ldflags "-s -w" -o mobile/build/desktop/$OUTLIB ./$G
# The Java side, for desktop/src/main/java (go/ and lndmobile/).
rm -rf mobile/build/desktop/java && cp -R "$OUT"/java mobile/build/desktop/java
rm -rf "$OUT"
shasum -a 256 mobile/build/desktop/$OUTLIB 2>/dev/null || sha256sum mobile/build/desktop/$OUTLIB
