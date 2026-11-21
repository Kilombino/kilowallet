#!/bin/bash
# Downloads the JetBrains Runtime with JCEF pinned in engines/JBR for <platform> (linux-x64,
# windows-x64, macos-arm64, macos-x64), checks its SHA-512 against the pin and unpacks it into
# <dir>. Prints the runtime's home (the folder with bin/ and jmods/): use it as KW_JBR and
# KW_PACKAGE_JDK. An archive already in <dir> is checked again and reused.
#
#   desktop/engines/fetch-jbr.sh linux-x64 /mnt/datos2/jbr
set -euo pipefail
P=${1:?usage: $0 <platform> <dir>}; DIR=${2:?usage: $0 <platform> <dir>}
PIN="$(cd "$(dirname "$0")" && pwd)/JBR"
line=$(grep "^$P=" "$PIN") || { echo "no $P in $PIN" >&2; exit 1; }
FILE=$(echo "${line#*=}" | awk '{print $1}'); SHA=$(echo "${line#*=}" | awk '{print $2}')
URL=$(grep '^url=' "$PIN" | cut -d= -f2-)
mkdir -p "$DIR"; cd "$DIR"
if [ ! -f "$FILE" ] || [ "$(sha512sum "$FILE" 2>/dev/null | awk '{print $1}' || shasum -a 512 "$FILE" | awk '{print $1}')" != "$SHA" ]; then
  curl -fsSL --retry 3 -o "$FILE.part" "$URL$FILE"
  mv "$FILE.part" "$FILE"
fi
GOT=$( (sha512sum "$FILE" 2>/dev/null || shasum -a 512 "$FILE") | awk '{print $1}')
[ "$GOT" = "$SHA" ] || { echo "$FILE: SHA-512 $GOT is not the pinned $SHA" >&2; rm -f "$FILE"; exit 1; }
NAME=${FILE%.tar.gz}
[ -d "$NAME" ] || tar xzf "$FILE"
HOME_DIR="$DIR/$NAME"; [ -d "$HOME_DIR/Contents/Home" ] && HOME_DIR="$HOME_DIR/Contents/Home"
[ -f "$HOME_DIR/jmods/jcef.jmod" ] || { echo "$HOME_DIR has no jmods/jcef.jmod" >&2; exit 1; }
echo "$HOME_DIR"
