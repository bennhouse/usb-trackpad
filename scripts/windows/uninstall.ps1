# Rimuove USB Trackpad da questo PC (l'app sul telefono si disinstalla dal telefono).
$app = Join-Path $env:LOCALAPPDATA 'USBTrackpad'
Get-Process usbtrackpad -ErrorAction SilentlyContinue | Stop-Process -Force
Remove-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'USBTrackpad' -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 500
Remove-Item $app -Recurse -Force -ErrorAction SilentlyContinue
Write-Host 'USB Trackpad rimosso.'
