# One-time setup for the importer: a private Python venv with yt-dlp + deno
# (YouTube needs a JS runtime now), and ffmpeg/ffprobe for MP3 conversion.
# Everything stays inside this folder. import.bat runs this automatically.
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
Set-Location $PSScriptRoot

if (-not (Test-Path ".venv\Scripts\python.exe")) {
    Write-Host "Creating Python venv..."
    $py = (Get-Command py -ErrorAction SilentlyContinue)
    if ($py) { & py -3 -m venv .venv } else { & python -m venv .venv }
    if ($LASTEXITCODE -ne 0) { throw "Python 3.10+ is required (python.org)" }
}
Write-Host "Installing yt-dlp + deno..."
& .venv\Scripts\python.exe -m pip install --disable-pip-version-check -q -U -r requirements.txt
if ($LASTEXITCODE -ne 0) { throw "pip install failed" }

if (-not (Test-Path "tools\ffmpeg\ffmpeg.exe")) {
    Write-Host "Downloading ffmpeg (yt-dlp's patched build, ~150 MB)..."
    New-Item -ItemType Directory -Force tools | Out-Null
    $zip = "tools\ffmpeg.zip"
    & curl.exe -L --fail --retry 3 -o $zip "https://github.com/yt-dlp/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip"
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg download failed" }
    Expand-Archive $zip tools\ffmpeg-extract -Force
    New-Item -ItemType Directory -Force tools\ffmpeg | Out-Null
    Get-ChildItem tools\ffmpeg-extract -Recurse -Include ffmpeg.exe, ffprobe.exe |
        ForEach-Object { Move-Item $_.FullName tools\ffmpeg\ -Force }
    Remove-Item -Recurse -Force tools\ffmpeg-extract, $zip
}
Write-Host "Importer ready."
