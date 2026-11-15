#!/bin/bash
# Compila tutto e prepara i pacchetti:
#   dist/mac/      programma per Mac (arm64, da macOS 11), APK, install.sh, uninstall.sh
#   dist/windows/  programmi per Windows x64 e ARM64, APK, install.cmd, uninstall.cmd
# Ogni cartella si copia sul computer di destinazione e si installa da lì.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
DIST="$DIR/dist"
GO="$(command -v go || echo "$HOME/Library/Go/go/bin/go")"

mkdir -p "$DIR/build"

echo "🔨 Mac…"
swiftc -O -swift-version 5 -target arm64-apple-macos11.0 "$DIR/src/main.swift" -o "$DIR/build/usbtrackpad"

echo "🔨 Windows…"
for arch in amd64 arm64; do
  (cd "$DIR/windows" && GOOS=windows GOARCH=$arch CGO_ENABLED=0 "$GO" build -trimpath \
    -ldflags "-H=windowsgui -s -w" -o "$DIR/build/usbtrackpad-$arch.exe" .)
done

"$DIR/build-apk.sh"

rm -rf "$DIST"; mkdir -p "$DIST/mac" "$DIST/windows"
cp "$DIR/build/usbtrackpad" "$DIR/build/USBTrackpad.apk" "$DIR/scripts/install.sh" "$DIR/scripts/uninstall.sh" "$DIST/mac/"
chmod +x "$DIST/mac/"*.sh "$DIST/mac/usbtrackpad"
cp "$DIR/build/usbtrackpad-amd64.exe" "$DIR/build/usbtrackpad-arm64.exe" "$DIR/build/USBTrackpad.apk" \
   "$DIR/scripts/windows/"* "$DIST/windows/"
echo "✅ Pacchetti pronti in dist/mac e dist/windows"
