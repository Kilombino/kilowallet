#!/bin/bash
# The .deb jpackage writes, with "Recommends: tor" added (onion nodes and the watchtower need a
# tor program; without one the app runs and says .onion cannot be reached), renamed for release.
set -euo pipefail
DESKTOP=$(cd "$(dirname "$0")/.." && pwd)
VERSION=${1:?version, e.g. 0.1.0-beta1}
IN=$(ls "$DESKTOP"/build/compose/binaries/main/deb/kilowallet_*_amd64.deb)
W=$(mktemp -d)
dpkg-deb -R "$IN" "$W/root"
grep -q '^Recommends:' "$W/root/DEBIAN/control" || sed -i '/^Depends:/a Recommends: tor' "$W/root/DEBIAN/control"
OUT="$DESKTOP/build/release/kilowallet_${VERSION}_amd64.deb"
mkdir -p "$(dirname "$OUT")"
fakeroot dpkg-deb --build -Zxz "$W/root" "$OUT" > /dev/null
rm -rf "$W"
dpkg-deb -f "$OUT" Package Version Recommends
sha256sum "$OUT"
