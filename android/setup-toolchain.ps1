# Installs everything needed to build the APK into one folder (default D:\Android).
# No admin rights, no system PATH / env changes. Safe to re-run: finished steps are skipped.
#   powershell -ExecutionPolicy Bypass -File setup-toolchain.ps1 [-Root D:\Android]
# Running it accepts the Android SDK License (sdkmanager --licenses).
param([string]$Root = "D:\Android")

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
New-Item -ItemType Directory -Force $Root, "$Root\downloads" | Out-Null

function Fetch($url, $out) {
    if (Test-Path $out) { return }
    Write-Host "Downloading $url"
    & curl.exe -L --fail --retry 3 -o "$out.part" $url
    if ($LASTEXITCODE -ne 0) { throw "download failed: $url" }
    Move-Item "$out.part" $out
}

# JDK 17 (Temurin) - what the Android Gradle Plugin runs on
$jdk = "$Root\jdk-17"
if (-not (Test-Path "$jdk\bin\java.exe")) {
    $zip = "$Root\downloads\jdk17.zip"
    Fetch "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse" $zip
    $tmp = "$Root\downloads\jdk-extract"
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive $zip $tmp
    Move-Item (Get-ChildItem $tmp -Directory | Select-Object -First 1).FullName $jdk
    Remove-Item -Recurse -Force $tmp
}
$env:JAVA_HOME = $jdk

# Android SDK command-line tools -> Sdk\cmdline-tools\latest
$sdk = "$Root\Sdk"
$sdkm = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $sdkm)) {
    $zip = "$Root\downloads\cmdline-tools.zip"
    Fetch "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" $zip
    $tmp = "$Root\downloads\clt-extract"
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive $zip $tmp
    New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
    Move-Item "$tmp\cmdline-tools" "$sdk\cmdline-tools\latest"
    Remove-Item -Recurse -Force $tmp
}

# PowerShell pipes don't reach sdkmanager's prompt reliably; cmd's < redirect does.
$yes = "$Root\downloads\yes.txt"
Set-Content -Encoding ascii $yes (@("y") * 30)
Write-Host "Accepting Android SDK licenses"
cmd /c "`"$sdkm`" --sdk_root=`"$sdk`" --licenses < `"$yes`"" | Out-Null
Write-Host "Installing SDK packages"
cmd /c "`"$sdkm`" --sdk_root=`"$sdk`" platform-tools platforms;android-35 build-tools;35.0.0 < `"$yes`"" | Out-Null
if (-not (Test-Path "$sdk\platforms\android-35")) { throw "sdkmanager failed to install platforms;android-35" }

# Gradle, only used once to generate the wrapper (gradlew) in the project
$gradle = "$Root\gradle-8.10.2"
if (-not (Test-Path "$gradle\bin\gradle.bat")) {
    $zip = "$Root\downloads\gradle-8.10.2-bin.zip"
    Fetch "https://services.gradle.org/distributions/gradle-8.10.2-bin.zip" $zip
    Expand-Archive $zip $Root
}

Write-Host "Toolchain ready in $Root"
