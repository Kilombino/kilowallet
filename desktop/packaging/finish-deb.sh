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
# jpackage writes the build machine's package names (Ubuntu 24.04: the t64 renames, libjpeg-turbo8).
# Each of those gets its Debian name as an alternative, so the same .deb installs on Debian 12/13.
sed -i -E '/^Depends:/{
  s/libasound2t64/libasound2t64 | libasound2/
  s/libglib2\.0-0t64/libglib2.0-0t64 | libglib2.0-0/
  s/libpng16-16t64/libpng16-16t64 | libpng16-16/
  s/libjpeg-turbo8/libjpeg-turbo8 | libjpeg62-turbo/
}' "$W/root/DEBIAN/control"
OUT="$DESKTOP/build/release/kilowallet_${VERSION}_amd64.deb"
mkdir -p "$(dirname "$OUT")"
fakeroot dpkg-deb --build -Zxz "$W/root" "$OUT" > /dev/null
rm -rf "$W"
dpkg-deb -f "$OUT" Package Version Depends Recommends
sha256sum "$OUT"
