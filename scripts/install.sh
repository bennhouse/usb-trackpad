#!/bin/bash
# Installa USB Trackpad su questo Mac (Apple Silicon, macOS 11 o successivo).
# Dopo l'installazione è plug and play: parte da solo a ogni login e, quando
# colleghi un telefono Android via USB, installa l'app e apre il trackpad.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HOME/Library/Application Support/USBTrackpad"
LABEL="it.usbtrackpad.agent"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$HOME/Library/Logs/USBTrackpad.log"

if [ "$(uname -m)" != "arm64" ]; then
  echo "❌ Serve un Mac con processore Apple Silicon (M1 o successivo)."; exit 1
fi

mkdir -p "$APP" "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"

# ferma l'eventuale versione già in esecuzione
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true

# copia il programma solo se è cambiato (così il permesso Accessibilità resta valido)
if ! cmp -s "$HERE/usbtrackpad" "$APP/usbtrackpad"; then
  cp "$HERE/usbtrackpad" "$APP/usbtrackpad"
fi
cp "$HERE/USBTrackpad.apk" "$APP/USBTrackpad.apk"
xattr -dr com.apple.quarantine "$APP" 2>/dev/null || true
chmod +x "$APP/usbtrackpad"

# adb: se sul Mac non c'è, scarica i platform-tools ufficiali di Google
if ! command -v adb >/dev/null 2>&1 \
   && [ ! -x "$HOME/Library/Android/sdk/platform-tools/adb" ] \
   && [ ! -x "$APP/platform-tools/adb" ]; then
  echo "⬇️  Scarico adb (Android platform-tools, circa 15 MB) da dl.google.com…"
  TMP="$(mktemp -d)"
  curl -fsSL -o "$TMP/pt.zip" "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip"
  unzip -q "$TMP/pt.zip" -d "$APP"
  rm -rf "$TMP"
fi
ADB_ENV=""
if command -v adb >/dev/null 2>&1; then ADB_ENV="$(command -v adb)"; fi

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array><string>$APP/usbtrackpad</string></array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>ADB</key><string>$ADB_ENV</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ProcessType</key><string>Interactive</string>
  <key>StandardOutPath</key><string>$LOG</string>
  <key>StandardErrorPath</key><string>$LOG</string>
</dict>
</plist>
EOF
# senza adb nel PATH, lascia che il programma lo cerchi da solo
[ -n "$ADB_ENV" ] || /usr/libexec/PlistBuddy -c "Delete :EnvironmentVariables" "$PLIST"

launchctl bootstrap "gui/$(id -u)" "$PLIST"

echo "✅ USB Trackpad installato e attivo (si avvia da solo a ogni login)."
echo
echo "Ultimo passo, una volta sola:"
echo "  Impostazioni di Sistema → Privacy e sicurezza → Accessibilità"
echo "  abilita \"usbtrackpad\" (se non è in elenco, aggiungilo con + da:"
echo "  $APP/usbtrackpad)"
echo
echo "Sul telefono: attiva Opzioni sviluppatore → Debug USB, collega il cavo e accetta l'autorizzazione."
echo "Registro: $LOG"
open "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility" 2>/dev/null || true
