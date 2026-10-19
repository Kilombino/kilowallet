#!/bin/bash
# The AppImage: the app image Compose builds (./gradlew :desktop:createDistributable), wrapped
# by appimagetool with a pinned runtime. It updates itself (AppUpdater replaces $APPIMAGE).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
DESKTOP=$(cd "$HERE/.." && pwd)
VERSION=${1:?version, e.g. 0.1.0-beta1}
TOOLS=${TOOLS:-$HOME/.cache/kw-tools}
# appimagetool 1.9.0 and the type2 runtime, pinned by hash (neither publishes a signed digest).
echo "46fdd785094c7f6e545b61afcfb0f3d98d8eab243f644b4b17698c01d06083d1  $TOOLS/appimagetool-x86_64.AppImage" | sha256sum -c -
echo "$(cat "$HERE/runtime.sha256")  $TOOLS/runtime-x86_64" | sha256sum -c -
APP="$DESKTOP/build/compose/binaries/main/app/Kilowallet"
[ -x "$APP/bin/Kilowallet" ] || { echo "build the app image first: ./gradlew :desktop:createDistributable"; exit 1; }
DIR=$(mktemp -d)/Kilowallet.AppDir
mkdir -p "$DIR/usr/lib"
cp -a "$APP" "$DIR/usr/lib/kilowallet"
cp "$DESKTOP/packaging/icons/kilowallet.png" "$DIR/kilowallet.png"
cat > "$DIR/kilowallet.desktop" <<DESK
[Desktop Entry]
Type=Application
Name=Kilowallet
Comment=Bitcoin BLAKE2b (XBT) wallet — desktop beta
Exec=Kilowallet
Icon=kilowallet
Categories=Office;Finance;
Terminal=false
DESK
cat > "$DIR/AppRun" <<'RUN'
#!/bin/sh
HERE=$(dirname "$(readlink -f "$0")")
exec "$HERE/usr/lib/kilowallet/bin/Kilowallet" "$@"
RUN
chmod +x "$DIR/AppRun"
OUT="$DESKTOP/build/appimage/Kilowallet-$VERSION-x86_64.AppImage"
mkdir -p "$(dirname "$OUT")"
ARCH=x86_64 "$TOOLS/appimagetool-x86_64.AppImage" --appimage-extract-and-run \
    --runtime-file "$TOOLS/runtime-x86_64" --no-appstream "$DIR" "$OUT"
rm -rf "$(dirname "$DIR")"
sha256sum "$OUT"
