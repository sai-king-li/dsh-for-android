#Requires -Version 5.1
<#
  setup-android-sdk.ps1

  One-shot Android build toolchain setup for this project. Installs into the
  project-local directories:
    .android-sdk  - Android SDK (cmdline-tools, platform-tools, platforms;android-35, build-tools)
    .tools/jdk21  - Temurin JDK 21 (Gradle 8.x runs best on 17-21)

  Afterwards run:
    .\gradlew.bat assembleDebug

  Usage:
    powershell -ExecutionPolicy Bypass -File scripts\setup-android-sdk.ps1
#>
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$sdkDir = Join-Path $root ".android-sdk"
$toolsDir = Join-Path $root ".tools"
New-Item -ItemType Directory -Force -Path $sdkDir, $toolsDir | Out-Null

# ---------------------------------------------------------------------------
# 1. Android command-line tools
# ---------------------------------------------------------------------------
$cmdToolsZip = Join-Path $toolsDir "cmdtools.zip"
$cmdToolsVer = "11076708"
if (-not (Test-Path (Join-Path $sdkDir "cmdline-tools\latest\bin\sdkmanager.bat"))) {
    if (-not (Test-Path $cmdToolsZip) -or (Get-Item $cmdToolsZip).Length -lt 1MB) {
        Write-Host "==> Downloading Android command-line tools ..."
        curl.exe -sL -o $cmdToolsZip "https://dl.google.com/android/repository/commandlinetools-win-${cmdToolsVer}_latest.zip"
    }
    Write-Host "==> Extracting command-line tools ..."
    $extract = Join-Path $toolsDir "cmdtools-extract"
    New-Item -ItemType Directory -Force -Path $extract | Out-Null
    Expand-Archive -Path $cmdToolsZip -DestinationPath $extract -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $sdkDir "cmdline-tools") | Out-Null
    Move-Item (Join-Path $extract "cmdline-tools") (Join-Path $sdkDir "cmdline-tools\latest") -Force
    Remove-Item $extract -Recurse -Force
}

# ---------------------------------------------------------------------------
# 2. JDK 21 (Temurin)
# ---------------------------------------------------------------------------
$jdkHome = Join-Path $toolsDir "jdk21"
if (-not (Test-Path (Join-Path $jdkHome "bin\java.exe"))) {
    $jdkZip = Join-Path $toolsDir "jdk21.zip"
    if (-not (Test-Path $jdkZip) -or (Get-Item $jdkZip).Length -lt 50MB) {
        Write-Host "==> Downloading Temurin JDK 21 ..."
        curl.exe -sL -o $jdkZip "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk"
    }
    Write-Host "==> Extracting JDK 21 ..."
    $extract = Join-Path $toolsDir "jdk21-extract"
    New-Item -ItemType Directory -Force -Path $extract | Out-Null
    Expand-Archive -Path $jdkZip -DestinationPath $extract -Force
    $inner = Get-ChildItem $extract -Directory | Select-Object -First 1
    Move-Item $inner.FullName $jdkHome -Force
    Remove-Item $extract -Recurse -Force
}

# ---------------------------------------------------------------------------
# 3. SDK packages (accept licenses, install platform + build tools)
# ---------------------------------------------------------------------------
$sdkmanager = Join-Path $sdkDir "cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path (Join-Path $sdkDir "platforms\android-35"))) {
    Write-Host "==> Installing SDK packages (platform-35, build-tools, platform-tools) ..."
    $env:JAVA_HOME = $jdkHome
    $env:PATH = "$jdkHome\bin;$env:PATH"
    $yes = ("y`n" * 24)
    $yes | & $sdkmanager --sdk_root=$sdkDir --licenses | Out-Null
    & $sdkmanager --sdk_root=$sdkDir "platform-tools" "platforms;android-35" "build-tools;35.0.0"
    if ($LASTEXITCODE -ne 0) { throw "sdkmanager install failed" }
}

# ---------------------------------------------------------------------------
# 4. local.properties
# ---------------------------------------------------------------------------
$localProps = Join-Path $root "local.properties"
$content = "sdk.dir=$($sdkDir -replace '\\', '\\')`n"
if (-not (Test-Path $localProps) -or (Get-Content $localProps -Raw) -notmatch "sdk.dir") {
    [System.IO.File]::WriteAllText($localProps, $content, [System.Text.UTF8Encoding]::new($false))
}
Write-Host "==> Done. SDK: $sdkDir  JDK: $jdkHome"
Write-Host "    Build with:  .\gradlew.bat assembleDebug"
