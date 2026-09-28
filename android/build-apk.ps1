# Builds the release APK -> music-player\Mixtape.apk
#   powershell -ExecutionPolicy Bypass -File build-apk.ps1 [-Install] [-Root D:\Android]
# -Install also pushes it to a USB-connected phone (USB debugging on) with adb.
# Run setup-toolchain.ps1 once first.
param([switch]$Install, [string]$Root = "D:\Android")

$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "$Root\jdk-17"
$env:ANDROID_HOME = "$Root\Sdk"
$env:ANDROID_USER_HOME = "$Root\user-home"   # holds the debug signing key; keep it to install updates over old builds
$env:GRADLE_USER_HOME = "$Root\gradle-home"  # Gradle's cache is big; keep it off C:
Set-Location $PSScriptRoot

if (-not (Test-Path "$env:JAVA_HOME\bin\java.exe")) { throw "No JDK in $Root - run setup-toolchain.ps1 first" }
"sdk.dir=$($env:ANDROID_HOME -replace '\\', '/')" | Set-Content -Encoding ascii local.properties

& .\gradlew.bat --no-daemon assembleRelease
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }

$apk = Join-Path (Split-Path $PSScriptRoot) "Mixtape.apk"
Copy-Item app\build\outputs\apk\release\app-release.apk $apk -Force
Write-Host "APK: $apk"

if ($Install) {
    & "$env:ANDROID_HOME\platform-tools\adb.exe" install -r $apk
}
