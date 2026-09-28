# Makes new music arrive by itself:
#   1. a nightly Task Scheduler job runs "import.bat update" without a window
#      (new songs from your playlists, loudness tags, lyrics; log: logs\update.log)
#   2. the sync server runs in the system tray from login (Startup folder shortcut),
#      so the phone can pull new songs on its own while it charges on Wi-Fi
# Run it again to change the time; undo everything with uninstall-autosync.ps1.
#
#   powershell -ExecutionPolicy Bypass -File install-autosync.ps1 [-At 03:30]
param([string]$At = "03:30")
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$TaskName = "Mixtape nightly update"
$ShortcutName = "Mixtape sync.lnk"

if (-not (Test-Path ".venv\Scripts\pythonw.exe") -or -not (Test-Path "tools\ffmpeg\ffmpeg.exe")) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File setup.ps1
    if ($LASTEXITCODE -ne 0) { throw "setup.ps1 failed" }
}
Write-Host "Installing the tray icon's packages..."
& .venv\Scripts\python.exe -m pip install --disable-pip-version-check -q -r requirements.txt
if ($LASTEXITCODE -ne 0) { throw "pip install failed" }
New-Item -ItemType Directory -Force logs | Out-Null

$pythonw = (Resolve-Path ".venv\Scripts\pythonw.exe").Path
$script = Join-Path $PSScriptRoot "ytimport.py"
$tray = Join-Path $PSScriptRoot "tray.pyw"
$log = Join-Path $PSScriptRoot "logs\update.log"

# 1. Nightly update. Runs as you, only while you're logged in (no password stored); if the PC was
#    off or asleep at that time it runs as soon as it can.
$action = New-ScheduledTaskAction -Execute $pythonw -Argument "`"$script`" update --log `"$log`"" -WorkingDirectory $PSScriptRoot
$trigger = New-ScheduledTaskTrigger -Daily -At $At
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -RunOnlyIfNetworkAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 3) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Force `
    -Description "Downloads new songs from the YouTube playlists imported into Mixtape, then adds loudness tags and lyrics. Log: $log" | Out-Null
Write-Host "Scheduled '$TaskName' daily at $At."

# 2. Tray sync server at login.
$startup = [Environment]::GetFolderPath("Startup")
$lnkPath = Join-Path $startup $ShortcutName
$shell = New-Object -ComObject WScript.Shell
$lnk = $shell.CreateShortcut($lnkPath)
$lnk.TargetPath = $pythonw
$lnk.Arguments = "`"$tray`""
$lnk.WorkingDirectory = $PSScriptRoot
$lnk.Description = "Mixtape sync server (system tray)"
$lnk.Save()
Write-Host "Added '$ShortcutName' to your Startup folder."

# Start it now unless it's already running.
$running = Get-CimInstance Win32_Process -Filter "Name = 'pythonw.exe'" | Where-Object { $_.CommandLine -like "*tray.pyw*" }
if (-not $running) {
    Start-Process -FilePath $pythonw -ArgumentList "`"$tray`"" -WorkingDirectory $PSScriptRoot
    Write-Host "Started the sync server: look for the cassette in the system tray."
    Write-Host "If Windows Firewall asks about pythonw.exe, allow it on private networks so the phone can reach it."
} else {
    Write-Host "The tray sync server is already running."
}
Write-Host "Done. Undo with: powershell -ExecutionPolicy Bypass -File uninstall-autosync.ps1"
