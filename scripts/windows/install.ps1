# Installa USB Trackpad su questo PC (Windows 10/11, x64 o ARM64).
# Dopo l'installazione è plug and play: parte da solo a ogni accesso e, quando
# colleghi un telefono Android via USB, installa l'app e apre il trackpad.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$app  = Join-Path $env:LOCALAPPDATA 'USBTrackpad'
$exe  = Join-Path $app 'usbtrackpad.exe'
$run  = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'

# architettura reale del PC (anche se questa PowerShell gira in emulazione)
$arch = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
$src = switch ($arch) {
  'ARM64' { 'usbtrackpad-arm64.exe' }
  'AMD64' { 'usbtrackpad-amd64.exe' }
  default { Write-Host "Architettura non supportata: $arch (serve Windows a 64 bit, x64 o ARM64)"; exit 1 }
}

New-Item -ItemType Directory -Force -Path $app | Out-Null

# ferma l'eventuale versione già in esecuzione
Get-Process usbtrackpad -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 500

Copy-Item (Join-Path $here $src) $exe -Force
Copy-Item (Join-Path $here 'USBTrackpad.apk') (Join-Path $app 'USBTrackpad.apk') -Force
Get-ChildItem $app -Recurse -File | Unblock-File

# adb: se non c'è, scarica i platform-tools ufficiali di Google
$hasAdb = (Get-Command adb -ErrorAction SilentlyContinue) -or
          (Test-Path (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')) -or
          (Test-Path (Join-Path $app 'platform-tools\adb.exe'))
if (-not $hasAdb) {
  Write-Host 'Scarico adb (Android platform-tools, circa 15 MB) da dl.google.com...'
  $zip = Join-Path $env:TEMP 'usbtrackpad-platform-tools.zip'
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  Invoke-WebRequest -UseBasicParsing 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
  Expand-Archive $zip -DestinationPath $app -Force
  Remove-Item $zip
  Get-ChildItem (Join-Path $app 'platform-tools') -Recurse -File | Unblock-File
}

# avvio automatico a ogni accesso (solo per questo utente, senza amministratore)
Set-ItemProperty -Path $run -Name 'USBTrackpad' -Value "`"$exe`""

Start-Process $exe

Write-Host ''
Write-Host 'USB Trackpad installato e attivo (si avvia da solo a ogni accesso).'
Write-Host ''
Write-Host 'Sul telefono: attiva Opzioni sviluppatore > Debug USB, collega il cavo e accetta l''autorizzazione.'
Write-Host 'Se il telefono non viene riconosciuto, installa il driver USB del produttore'
Write-Host '(per Pixel e Nexus: Google USB Driver).'
Write-Host "Registro: $(Join-Path $app 'USBTrackpad.log')"
