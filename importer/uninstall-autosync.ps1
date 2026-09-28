# Undoes install-autosync.ps1: removes the nightly task and the Startup shortcut, and stops the
# tray sync server. Your music, the importer and its logs are left alone; sync.bat still works.
$ErrorActionPreference = "Stop"

$TaskName = "Mixtape nightly update"
$ShortcutName = "Mixtape sync.lnk"

if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Host "Removed the scheduled task '$TaskName'."
} else {
    Write-Host "No scheduled task '$TaskName'."
}

$lnkPath = Join-Path ([Environment]::GetFolderPath("Startup")) $ShortcutName
if (Test-Path $lnkPath) {
    Remove-Item $lnkPath
    Write-Host "Removed '$ShortcutName' from your Startup folder."
}

$tray = Get-CimInstance Win32_Process -Filter "Name = 'pythonw.exe'" | Where-Object { $_.CommandLine -like "*tray.pyw*" }
foreach ($p in $tray) {
    Stop-Process -Id $p.ProcessId -Force
    Write-Host "Stopped the tray sync server (pid $($p.ProcessId))."
}
Write-Host "Auto-sync is off."
