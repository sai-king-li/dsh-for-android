#Requires -Version 5.1
<#
  fetch-node-runtime.ps1

  Downloads the Node.js runtime for Android from the Termux package repository
  and stages it into the app's assets so the APK bundles a working `node` +
  `npm` that runs on any Android device (bionic libc).

  Sources (Termux main repo, binary-<arch>):
    - nodejs-lts   : Node.js 24 LTS binary for Android
    - npm          : npm CLI
    - libc++       : shared C++ runtime
    - openssl      : libssl/libcrypto
    - c-ares       : async DNS (node dependency)
    - libicu       : ICU (node dependency)
    - libsqlite    : SQLite (node dependency)
    - zlib         : zlib (node dependency)

  The .deb packages are extracted and their useful files are copied into
  <out>/bin (node), <out>/lib (shared libraries) and
  <out>/lib/node_modules/npm (npm). At runtime the app sets LD_LIBRARY_PATH
  to <out>/lib and spawns <out>/bin/node.

  Usage:
    powershell -ExecutionPolicy Bypass -File scripts\fetch-node-runtime.ps1 [-Arch arm64|x64] [-OutDir <path>]
#>
param(
    [ValidateSet("arm64", "x64")]
    [string]$Arch = "arm64",
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"

$repoBase = "https://packages.termux.dev/apt/termux-main"
if ($Arch -eq "arm64") {
    $debArch = "aarch64"
    $poolSuffix = "aarch64"
} else {
    $debArch = "x86_64"
    $poolSuffix = "x86_64"
}

if (-not $OutDir) {
    $OutDir = Join-Path $PSScriptRoot "..\app\src\main\assets\node"
}
$OutDir = [System.IO.Path]::GetFullPath($OutDir)
$work = Join-Path $env:TEMP "dsh-node-runtime-$Arch"
New-Item -ItemType Directory -Force -Path $work | Out-Null
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Get-Sha256([string]$path) {
    $h = Get-FileHash -Path $path -Algorithm SHA256
    return $h.Hash.ToLowerInvariant()
}

# The packages we need. `nodejs-lts` and `npm` are the runtimes; the rest are
# its shared-library dependencies (from the Depends field of nodejs-lts).
$needed = @("nodejs-lts", "npm", "libc++", "openssl", "c-ares", "libicu", "libsqlite", "zlib")

Write-Host "==> Downloading Termux package index (binary-$debArch) ..."
$indexPath = Join-Path $work "Packages"
curl.exe -sL -o $indexPath "$repoBase/dists/stable/main/binary-$debArch/Packages"
if (-not (Test-Path $indexPath) -or (Get-Item $indexPath).Length -lt 1000) {
    throw "Failed to download the Termux package index for $debArch"
}
$index = [System.IO.File]::ReadAllText($indexPath)

# Parse package blocks from the index.
$packages = @{}
$pattern = "(?ms)^Package: (?<name>[^\r\n]+)\r?\n(?<block>.*?)(?=^Package: )"
foreach ($m in [regex]::Matches($index, $pattern)) {
    $name = $m.Groups["name"].Value
    $block = $m.Groups["block"].Value
    $filename = ([regex]::Match($block, "(?m)^Filename: (.+)$")).Groups[1].Value.Trim()
    $sha = ([regex]::Match($block, "(?m)^SHA256: (.+)$")).Groups[1].Value.Trim().ToLowerInvariant()
    $ver = ([regex]::Match($block, "(?m)^Version: (.+)$")).Groups[1].Value.Trim()
    if ($filename) { $packages[$name] = @{ Filename = $filename; Sha256 = $sha; Version = $ver } }
}

foreach ($pkg in $needed) {
    if (-not $packages.ContainsKey($pkg)) { throw "Package '$pkg' not found in Termux index for $debArch" }
    Write-Host ("  {0,-12} {1}" -f $pkg, $packages[$pkg].Version)
}

# Download each .deb (skip if a matching copy is cached).
$debs = @()
foreach ($pkg in $needed) {
    $info = $packages[$pkg]
    $url = "$repoBase/$($info.Filename)"
    $debPath = Join-Path $work ([System.IO.Path]::GetFileName($info.Filename))
    if (-not (Test-Path $debPath) -or (Get-Sha256 $debPath) -ne $info.Sha256) {
        Write-Host "==> Downloading $pkg ..."
        curl.exe -sL -o $debPath $url
        if (-not (Test-Path $debPath)) { throw "Download failed: $url" }
        $actual = Get-Sha256 $debPath
        if ($info.Sha256 -and $actual -ne $info.Sha256) { throw "SHA256 mismatch for $pkg" }
    } else {
        Write-Host "==> $pkg already cached."
    }
    $debs += $debPath
}

# Extract .deb (ar) -> data.tar.xz -> files, using Windows tar for xz.
$extractRoot = Join-Path $work "extract"
New-Item -ItemType Directory -Force -Path $extractRoot | Out-Null

function Read-ArMember([string]$debPath, [string]$memberName) {
    # Minimal ar parser: returns the bytes of the named member.
    $bytes = [System.IO.File]::ReadAllBytes($debPath)
    $pos = 8  # skip the global "!<arch>\n" magic
    while ($pos -lt $bytes.Length) {
        if ($pos + 60 -gt $bytes.Length) { break }
        $header = [System.Text.Encoding]::ASCII.GetString($bytes, $pos, 60)
        $name = $header.Substring(0, 16).TrimEnd(" ", "/", [char]0)
        $size = [int64]([System.Text.Encoding]::ASCII.GetString($bytes, $pos + 48, 10).Trim())
        $dataStart = $pos + 60
        if ($name -eq $memberName) {
            $out = New-Object byte[] $size
            [System.Array]::Copy($bytes, $dataStart, $out, 0, $size)
            return $out
        }
        $pos = $dataStart + $size + ($size % 2)  # members are 2-byte aligned
    }
    throw "Member '$memberName' not found in $debPath"
}

foreach ($deb in $debs) {
    $name = [System.IO.Path]::GetFileNameWithoutExtension($deb)
    $pkgDir = Join-Path $extractRoot $name
    if (Test-Path (Join-Path $pkgDir "data.tar.xz")) {
        Write-Host "==> $name already extracted."
        continue
    }
    Write-Host "==> Extracting $name ..."
    New-Item -ItemType Directory -Force -Path $pkgDir | Out-Null
    $dataTar = Join-Path $pkgDir "data.tar.xz"
    try {
        $member = Read-ArMember $deb "data.tar.xz"
    } catch {
        # Some .debs store data.tar.gz
        $member = Read-ArMember $deb "data.tar.gz"
    }
    [System.IO.File]::WriteAllBytes($dataTar, $member)
    $filesRoot = Join-Path $pkgDir "files"
    New-Item -ItemType Directory -Force -Path $filesRoot | Out-Null
    # Windows tar cannot create symlinks without elevated privileges, and the
    # debs contain several (usr/bin/npm, npx, corepack, unversioned .so links).
    # Extract everything except the symlinks (their full paths are excluded);
    # versioned .so files are the real payloads and unversioned links are not
    # needed at runtime (LD_LIBRARY_PATH uses SONAMEs).
    $listing = & tar.exe -tvf $dataTar
    $excludes = foreach ($line in $listing) {
        if ($line.Length -gt 1 -and $line[0] -eq "l") {
            # Symlink entries look like:
            #   lrwxrwxrwx 0/0 0 <date> <time> ./path/name -> target
            # Date format is locale-dependent, so take the token before " -> "
            # (paths contain no spaces).
            if ($line -match " -> ") {
                $name = ($line.Substring(0, $line.IndexOf(" -> ")) -split "\s+")[-1].Trim()
            } else {
                $name = ($line -split "\s+")[-1].Trim()
            }
            "--exclude=$name"
        }
    }
    & tar.exe -xf $dataTar -C $filesRoot @($excludes)
    if ($LASTEXITCODE -ne 0) { throw "tar extraction failed for $name" }
}

# Copy what we need into the output trees.
$usr = Join-Path $extractRoot "*\files\data\data\com.termux\files\usr"
$usrDirs = Get-ChildItem (Join-Path $extractRoot "*") -Directory | ForEach-Object {
    Join-Path $_.FullName "files\data\data\com.termux\files\usr"
} | Where-Object { Test-Path $_ }
if (-not $usrDirs) { throw "No extracted usr/ trees found" }

$libOut = Join-Path $OutDir "lib"
New-Item -ItemType Directory -Force -Path $libOut | Out-Null

# 1. node binary -> jniLibs/<abi>/libnode.so.
#    Android 10+ SELinux W^X blocks execve() of files under getFilesDir()
#    (app_data_file label), so the executable must ship in the native library
#    directory (lib/<abi>/), which is labeled exec_type. It is named *.so so
#    the PackageManager extracts it to disk at install time; the app then
#    executes nativeLibraryDir/libnode.so.
$nodeSrc = Get-ChildItem $usrDirs -Recurse -Filter "node" -File | Where-Object { $_.DirectoryName -match "\\bin$" } | Select-Object -First 1
if (-not $nodeSrc) { throw "node binary not found in extracted packages" }
if ($debArch -eq "aarch64") { $jniAbi = "arm64-v8a" } else { $jniAbi = "x86_64" }
$jniOut = Join-Path $PSScriptRoot "..\app\src\main\jniLibs\$jniAbi"
New-Item -ItemType Directory -Force -Path $jniOut | Out-Null
Copy-Item $nodeSrc.FullName (Join-Path $jniOut "libnode.so") -Force
Write-Host "==> node -> jniLibs/$jniAbi/libnode.so"

# 2. npm tree (assets/node/lib/node_modules/npm).
foreach ($u in $usrDirs) {
    $npmSrc = Join-Path $u "lib\node_modules\npm"
    if (Test-Path $npmSrc) {
        Copy-Item $npmSrc (Join-Path $libOut "node_modules\npm") -Recurse -Force
    }
}

# 3. Shared libraries -> jniLibs, renamed to plain .so names and ELF-patched.
#    The bionic linker resolves DT_NEEDED by exact file name and validates
#    verneed entries against each loaded library's SONAME, and aapt2 only
#    packages jniLibs entries ending in .so — so every library is renamed to a
#    plain .so name and its DT_NEEDED/SONAME strings are rewritten in place.
$libOrigins = @{
    "libz.so.1.3.2"        = "libz.so"
    "libcares.so"          = "libcares.so"
    "libsqlite3.so.3.53.4" = "libsqlite3.so"
    "libcrypto.so.3"       = "libcrypto.so"
    "libssl.so.3"          = "libssl.so"
    "libicui18n.so.78.3"   = "libicui18n.so"
    "libicuuc.so.78.3"     = "libicuuc.so"
    "libicudata.so.78.3"   = "libicudata.so"
    "libc++_shared.so"     = "libc++_shared.so"
}
$mapArgs = @("libz.so.1=libz.so", "libcrypto.so.3=libcrypto.so", "libssl.so.3=libssl.so", "libicui18n.so.78=libicui18n.so", "libicuuc.so.78=libicuuc.so", "libicudata.so.78=libicudata.so")
$patchScript = Join-Path $PSScriptRoot "patch-elf-names.mjs"
$elfCheck = Join-Path $PSScriptRoot "check-elf-deps.mjs"
$nodeCheck = (Get-Command node -ErrorAction SilentlyContinue)
if (-not $nodeCheck) { throw "node is required on PATH to patch ELF names" }

Write-Host "==> Staging shared libraries into jniLibs ..."
foreach ($origin in $libOrigins.Keys) {
    $srcFile = Get-ChildItem $usrDirs -Recurse -File | Where-Object { $_.Name -eq $origin -and $_.DirectoryName -match "\\lib$" } | Select-Object -First 1
    if (-not $srcFile) { throw "library not found: $origin" }
    Copy-Item $srcFile.FullName (Join-Path $jniOut $origin) -Force
}

Write-Host "==> Patching ELF names (DT_NEEDED / SONAME) ..."
# Patch every file's strings first (--no-rename: renaming is done explicitly
# below so the loop never races with a rename that happened mid-iteration).
foreach ($origin in $libOrigins.Keys) {
    & node $patchScript (Join-Path $jniOut $origin) @mapArgs --no-rename 2>&1 | Select-String "hit|renamed" | ForEach-Object { Write-Host "    $_" }
}
& node $patchScript (Join-Path $jniOut "libnode.so") @mapArgs --no-rename 2>&1 | Select-String "hit|renamed" | ForEach-Object { Write-Host "    $_" }

# Rename libraries to their final plain-.so names (only if the target does not
# exist yet; several libs keep their name and need no rename).
foreach ($origin in $libOrigins.Keys) {
    $final = $libOrigins[$origin]
    if ($final -ne $origin) {
        $srcPath = Join-Path $jniOut $origin
        $finalPath = Join-Path $jniOut $final
        if ((Test-Path $srcPath) -and -not (Test-Path $finalPath)) {
            Move-Item $srcPath $finalPath -Force
            Write-Host "    renamed $origin -> $final"
        }
    }
}
# Drop leftover versioned names from jniLibs (aapt2 would silently drop
# non-.so names, but keep the tree clean and unambiguous).
Get-ChildItem $jniOut -File | Where-Object { $_.Name -match "\.so\.\d" } | Remove-Item -Force

Write-Host "==> Verifying dependency closure ..."
$verify = & node $elfCheck (Join-Path $jniOut "libnode.so") $jniOut 2>&1
$verify | Select-String "ok|system|aliased" | ForEach-Object { Write-Host "    $_" }
$verify | Select-String "MISSING" | ForEach-Object { Write-Host "    $_" }

# 4. Version stamp.
$nodeVer = (& (Join-Path $jniOut "libnode.so") --version 2>$null)
if (-not $nodeVer) { $nodeVer = "unknown" }
@"
arch: $debArch
node: $nodeVer
packages:
$(foreach ($p in $needed) { "  $p : $($packages[$p].Version)`n" })
fetched: $(Get-Date -Format o)
"@ | Out-File (Join-Path $OutDir "VERSION.txt") -Encoding utf8

Write-Host ""
Write-Host "==> Done. Node runtime staged:"
Write-Host "    executable: $(Join-Path $jniOut 'libnode.so')"
Write-Host "    assets:     $OutDir"
Write-Host "    node: $nodeVer (termux $debArch)"
$totalMB = [Math]::Round(((Get-ChildItem $OutDir -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 1)
$jniMB = [Math]::Round(((Get-ChildItem $jniOut -File | Measure-Object Length -Sum).Sum / 1MB), 1)
Write-Host "    assets size: $totalMB MB, jniLibs size: $jniMB MB"
