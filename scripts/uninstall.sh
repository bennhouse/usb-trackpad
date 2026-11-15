#!/bin/bash
# Rimuove USB Trackpad da questo Mac (l'app sul telefono si disinstalla dal telefono).
LABEL="it.usbtrackpad.agent"
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"
rm -rf "$HOME/Library/Application Support/USBTrackpad"
rm -f "$HOME/Library/Logs/USBTrackpad.log"
echo "✅ USB Trackpad rimosso. Puoi togliere \"usbtrackpad\" anche da Accessibilità."
