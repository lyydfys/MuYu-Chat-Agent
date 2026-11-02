# Stage the official DSH payload for the Android host (R01/R03).
#
# Source of truth: the pinned reference release APK (read-only input). This script:
#   1. extracts `assets/payload.zip` from the reference APK (or uses -PayloadZip),
#   2. verifies every executable + the payload archive against scripts/dsh/payload-lock.json,
#   3. emits jniLibs copies of the bundled ELF executables, renamed lib*.so so PackageManager
#      extracts them into nativeLibraryDir where exec() is permitted on targetSdk 36,
#   4. emits the runtime manifest (assets/dsh/MANIFEST.json) consumed by DshPayloadInstaller,
#   5. emits third_party/dsh/MANIFEST.json with source URL, version, sizes, sha256 and licenses.
#
# Binaries are NEVER committed: the emitted directories are expected to be git-ignored.
# A hash mismatch is a hard failure; -UpdateLock is the only way to re-pin, after re-audit.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\dsh\stage.ps1 `
#       -Apk E:\tmp\dsh-runtime\DeepSeekHarness.apk
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\dsh\stage.ps1 -PayloadZip E:\tmp\dsh-runtime\payload.zip
#   ... -UpdateLock     # re-pin after auditing a new source build
#   ... -Extract        # also extract the full payload tree to third_party/dsh/payload (dev only)

param(
    [string]$Apk = 'E:\tmp\dsh-runtime\DeepSeekHarness.apk',
    [string]$PayloadZip = '',
    [string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))),
    [switch]$UpdateLock,
    [switch]$Extract
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression

function Fail([string]$message) {
    Write-Error "stage.ps1: $message"
    exit 1
}

function Get-Sha256File([string]$path) {
    return (Get-FileHash -Algorithm SHA256 -Path $path).Hash.ToLowerInvariant()
}

function Open-Zip([string]$path) {
    return [System.IO.Compression.ZipFile]::OpenRead($path)
}

# Copies one zip entry to a destination file while hashing it; returns size/sha/ELF flag.
function Copy-ZipEntry($zip, [string]$entryName, [string]$destPath) {
    $entry = $zip.GetEntry($entryName)
    if (-not $entry) { throw "missing payload entry: $entryName" }
    $parent = Split-Path -Parent $destPath
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $elf = $false
    try {
        $in = $entry.Open()
        try {
            $out = [System.IO.File]::Create($destPath)
            try {
                $buffer = New-Object byte[] (256 * 1024)
                $first = $true
                while ($true) {
                    $read = $in.Read($buffer, 0, $buffer.Length)
                    if ($read -le 0) { break }
                    if ($first -and $read -ge 4) {
                        $elf = ($buffer[0] -eq 0x7F -and $buffer[1] -eq 0x45 -and $buffer[2] -eq 0x4C -and $buffer[3] -eq 0x46)
                        $first = $false
                    }
                    $sha.TransformBlock($buffer, 0, $read, $null, 0) | Out-Null
                    $out.Write($buffer, 0, $read)
                }
            } finally { $out.Dispose() }
        } finally { $in.Dispose() }
        $sha.TransformFinalBlock((New-Object byte[] 0), 0, 0) | Out-Null
        $hex = (($sha.Hash | ForEach-Object { $_.ToString('x2') }) -join '')
        return @{ size = [int64]$entry.Length; sha256 = $hex; elf = $elf }
    } finally { $sha.Dispose() }
}

# Hashes one zip entry without writing it.
function Get-ZipEntryHash($zip, [string]$entryName) {
    $entry = $zip.GetEntry($entryName)
    if (-not $entry) { throw "missing payload entry: $entryName" }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $in = $entry.Open()
        try {
            $buffer = New-Object byte[] (256 * 1024)
            while ($true) {
                $read = $in.Read($buffer, 0, $buffer.Length)
                if ($read -le 0) { break }
                $sha.TransformBlock($buffer, 0, $read, $null, 0) | Out-Null
            }
        } finally { $in.Dispose() }
        $sha.TransformFinalBlock((New-Object byte[] 0), 0, 0) | Out-Null
        $hex = (($sha.Hash | ForEach-Object { $_.ToString('x2') }) -join '')
        return @{ size = [int64]$entry.Length; sha256 = $hex }
    } finally { $sha.Dispose() }
}

function Read-ZipText($zip, [string]$entryName) {
    $entry = $zip.GetEntry($entryName)
    if (-not $entry) { throw "missing payload entry: $entryName" }
    $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}

$assetsDir = Join-Path $RepoRoot 'core\dsh-runtime\src\main\assets\dsh'
$jniDir = Join-Path $RepoRoot 'core\dsh-runtime\src\main\jniLibs\arm64-v8a'
$thirdPartyDir = Join-Path $RepoRoot 'third_party\dsh'
$lockPath = Join-Path $RepoRoot 'scripts\dsh\payload-lock.json'
$stageTemp = Join-Path 'E:\tmp\dsh-runtime\stage' 'payload.zip'

# ---------------------------------------------------------------- 1. payload source

$apkSha = $null
$apkSize = $null
$apkPath = $null
if ($PayloadZip -ne '') {
    $stageTemp = $PayloadZip
    Write-Host "payload source: $PayloadZip"
} else {
    if (-not (Test-Path $Apk)) { Fail "reference APK not found: $Apk (pass -Apk or -PayloadZip)" }
    $apkPath = (Resolve-Path $Apk).Path
    $apkSize = (Get-Item $apkPath).Length
    $apkSha = Get-Sha256File $apkPath
    Write-Host "APK: $apkPath ($apkSize bytes) sha256=$apkSha"
    $apkZip = Open-Zip $apkPath
    try {
        $entry = $apkZip.Entries | Where-Object { $_.FullName -eq 'assets/payload.zip' } | Select-Object -First 1
        if (-not $entry) { Fail "assets/payload.zip not found in APK $apkPath" }
        $stageDir = Split-Path -Parent $stageTemp
        if (-not (Test-Path $stageDir)) { New-Item -ItemType Directory -Force -Path $stageDir | Out-Null }
        if (Test-Path $stageTemp) { Remove-Item -Force $stageTemp }
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $stageTemp, $true)
    } finally { $apkZip.Dispose() }
}

if (-not (Test-Path $stageTemp)) { Fail "payload zip unavailable: $stageTemp" }
$payloadSize = (Get-Item $stageTemp).Length
$payloadSha = Get-Sha256File $stageTemp
Write-Host "payload.zip: $payloadSize bytes sha256=$payloadSha"

$zip = Open-Zip $stageTemp
try {
    # ------------------------------------------------------------ 2. inventory + executables

    $entries = $zip.Entries
    $fileEntries = $entries | Where-Object { -not $_.FullName.EndsWith('/') -and $_.Length -ge 0 }
    $fileCount = @($fileEntries).Count
    $totalBytes = [int64]0
    foreach ($e in $fileEntries) { $totalBytes += [int64]$e.Length }
    Write-Host "payload entries: $($entries.Count) total, $fileCount files, $totalBytes bytes"

    $executables = @(
        @{ entry = 'runtime/bin/node';                                    lib = 'libdshnode.so';            role = 'node';              required = $true  },
        @{ entry = 'runtime/bin/rg';                                      lib = 'libdshrg.so';              role = 'ripgrep';           required = $true  },
        @{ entry = 'runtime/bin/curl';                                    lib = 'libdshcurl.so';            role = 'curl';              required = $true  },
        @{ entry = 'bin/git';                                             lib = 'libdshgit.so';             role = 'git';               required = $true  },
        @{ entry = 'git/libexec/git-core/git-remote-http';                lib = 'libdshgitremotehttp.so';   role = 'git-remote-http';   required = $true  },
        @{ entry = 'git/libexec/git-core/git-remote-https';               lib = 'libdshgitremotehttps.so';  role = 'git-remote-https';  required = $true  },
        @{ entry = 'python/bin/python3.14';                               lib = 'libdshpython.so';          role = 'python3.14';        required = $false }
    )

    if (-not (Test-Path $jniDir)) { New-Item -ItemType Directory -Force -Path $jniDir | Out-Null }
    $mapping = @()
    $nodeInfo = $null
    foreach ($exe in $executables) {
        $entry = $zip.GetEntry($exe.entry)
        if (-not $entry) {
            if ($exe.required) { Fail "required executable missing from payload: $($exe.entry)" }
            Write-Host "optional executable absent, skipping: $($exe.entry)"
            continue
        }
        $dest = Join-Path $jniDir $exe.lib
        $info = Copy-ZipEntry $zip $exe.entry $dest
        if (-not $info.elf) { Fail "$($exe.entry) is not an ELF executable; refusing to stage it as jniLibs" }
        Write-Host ("jniLibs {0,-28} <- {1} size={2} sha256={3}" -f $exe.lib, $exe.entry, $info.size, $info.sha256)
        $row = @{
            role = $exe.role
            payloadEntry = $exe.entry
            jniLibsPath = "jniLibs/arm64-v8a/$($exe.lib)"
            size = $info.size
            sha256 = $info.sha256
        }
        $mapping += $row
        if ($exe.entry -eq 'runtime/bin/node') { $nodeInfo = $info }
    }
    if (-not $nodeInfo) { Fail 'the payload has no Node executable' }

    # ------------------------------------------------------------ 3. kernel metadata + critical files

    $kernelPkg = 'dshroot/lib/node_modules/@deepseek-ai/dsh/package.json'
    $kernelVersion = 'unknown'
    try {
        $pkgText = Read-ZipText $zip $kernelPkg
        $m = [regex]::Match($pkgText, '"version"\s*:\s*"([^"]+)"')
        if ($m.Success) { $kernelVersion = $m.Groups[1].Value }
    } catch { Fail "cannot read kernel package metadata: $kernelPkg" }
    $kernelRevision = 'unknown'
    try { $kernelRevision = (Read-ZipText $zip 'dshroot/REVISION').Trim() } catch { }

    $criticalEntries = @(
        'dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js',
        'dshroot/lib/node_modules/@deepseek-ai/dsh/package.json',
        'dshhome/profiles/web/package.json',
        'runtime/lib/libz.so.1',
        'runtime/lib/libc++_shared.so',
        'runtime/lib/libsqlite3.so'
    )
    $criticalFiles = @()
    foreach ($name in $criticalEntries) {
        $hash = Get-ZipEntryHash $zip $name
        Write-Host ("critical {0,-64} size={1} sha256={2}" -f $name, $hash.size, $hash.sha256)
        $criticalFiles += @{ path = $name; size = $hash.size; sha256 = $hash.sha256 }
    }

    $payloadVersion = "dsh-$kernelVersion+android-ref"
    if ($apkSha) { $payloadVersion = "$payloadVersion-$($apkSha.Substring(0, 12))" }
    else { $payloadVersion = "$payloadVersion-$($payloadSha.Substring(0, 12))" }

    # ------------------------------------------------------------ 4. pinned lock verification

    $observed = [ordered]@{
        apkPath = $apkPath
        apkSize = $apkSize
        apkSha256 = $apkSha
        payloadSize = $payloadSize
        payloadSha256 = $payloadSha
        kernelVersion = $kernelVersion
        kernelRevision = $kernelRevision
        executables = $mapping
        criticalFiles = $criticalFiles
    }

    if (Test-Path $lockPath) {
        $lock = Get-Content -Raw -Path $lockPath | ConvertFrom-Json
        $problems = @()
        if ($lock.payloadSha256 -ne $payloadSha) { $problems += "payload sha256 $payloadSha != pinned $($lock.payloadSha256)" }
        if ([int64]$lock.payloadSize -ne [int64]$payloadSize) { $problems += "payload size $payloadSize != pinned $($lock.payloadSize)" }
        if ($lock.kernelVersion -ne $kernelVersion) { $problems += "kernel version $kernelVersion != pinned $($lock.kernelVersion)" }
        if ($apkSha -and $lock.apkSha256 -and $lock.apkSha256 -ne $apkSha) {
            $problems += "APK sha256 $apkSha != pinned $($lock.apkSha256)"
        }
        foreach ($pinned in $lock.executables) {
            $actual = $mapping | Where-Object { $_.payloadEntry -eq $pinned.payloadEntry } | Select-Object -First 1
            if (-not $actual) { $problems += "pinned executable $($pinned.payloadEntry) is missing"; continue }
            if ($actual.sha256 -ne $pinned.sha256) {
                $problems += "executable $($pinned.payloadEntry) sha256 $($actual.sha256) != pinned $($pinned.sha256)"
            }
        }
        foreach ($pinned in $lock.criticalFiles) {
            $actual = $criticalFiles | Where-Object { $_.path -eq $pinned.path } | Select-Object -First 1
            if (-not $actual) { $problems += "pinned critical file $($pinned.path) is missing"; continue }
            if ($actual.sha256 -ne $pinned.sha256) {
                $problems += "critical file $($pinned.path) sha256 $($actual.sha256) != pinned $($pinned.sha256)"
            }
        }
        # Bidirectional pin set: a newly staged executable / critical file must not appear
        # without an audited pin entry. Otherwise adding a binary to this script would silently
        # ship it unpinned on the next run.
        foreach ($actual in $mapping) {
            $pinned = $lock.executables | Where-Object { $_.payloadEntry -eq $actual.payloadEntry } | Select-Object -First 1
            if (-not $pinned) { $problems += "executable $($actual.payloadEntry) has no pinned lock entry" }
        }
        foreach ($actual in $criticalFiles) {
            $pinned = $lock.criticalFiles | Where-Object { $_.path -eq $actual.path } | Select-Object -First 1
            if (-not $pinned) { $problems += "critical file $($actual.path) has no pinned lock entry" }
        }
        if ($problems.Count -gt 0) {
            if (-not $UpdateLock) {
                Write-Host 'PAYLOAD MISMATCH:'
                $problems | ForEach-Object { Write-Host "  - $_" }
                Fail 'payload does not match scripts/dsh/payload-lock.json; re-audit the source, then re-run with -UpdateLock'
            }
            Write-Host 'payload mismatch accepted because -UpdateLock was passed:'
            $problems | ForEach-Object { Write-Host "  - $_" }
        } else {
            Write-Host 'payload lock verified'
        }
    } else {
        Write-Host 'no payload lock yet; creating one (audit this run before distributing)'
    }

    # ------------------------------------------------------------ 5. runtime manifest asset

    if (-not (Test-Path $assetsDir)) { New-Item -ItemType Directory -Force -Path $assetsDir | Out-Null }
    $stagedPayload = Join-Path $assetsDir 'payload.zip'
    Copy-Item -Force $stageTemp $stagedPayload
    $stagedSha = Get-Sha256File $stagedPayload
    if ($stagedSha -ne $payloadSha) { Fail "copied payload sha256 $stagedSha != source $payloadSha" }

    $sourceInfo = [ordered]@{
        officialCommit = '687f282e8625c6b8d11ff58211a90524be2e4317'
        referenceRepo = 'https://github.com/woaiys3/deepseek-harness-android-app'
        referenceVersion = 'v1.20.1'
        referenceCommit = $null
    }
    $licenses = @(
        @{ name = '@deepseek-ai/dsh (DeepSeek Harness)'; license = 'MIT'; source = 'https://github.com/deepseek-ai/deepseek-harness' },
        @{ name = 'Node.js v26 (Termux bionic build)'; license = 'MIT'; source = 'https://nodejs.org' },
        @{ name = 'woaiys3/deepseek-harness-android-app payload assembly'; license = 'MIT'; source = 'https://github.com/woaiys3/deepseek-harness-android-app' },
        @{ name = 'ICU'; license = 'Unicode-3.0'; source = 'https://icu.unicode.org' },
        @{ name = 'OpenSSL 3'; license = 'Apache-2.0'; source = 'https://www.openssl.org' },
        @{ name = 'zlib'; license = 'Zlib'; source = 'https://zlib.net' },
        @{ name = 'SQLite'; license = 'Public-Domain'; source = 'https://sqlite.org' },
        @{ name = 'c-ares'; license = 'MIT'; source = 'https://c-ares.org' },
        @{ name = 'libffi'; license = 'MIT'; source = 'https://github.com/libffi/libffi' },
        @{ name = 'nghttp2'; license = 'MIT'; source = 'https://nghttp2.org' },
        @{ name = 'ngtcp2'; license = 'MIT'; source = 'https://ngtcp2.org' },
        @{ name = 'PCRE2'; license = 'BSD-3-Clause'; source = 'https://github.com/PCRE2Project/pcre2' },
        @{ name = 'libssh2'; license = 'BSD-3-Clause'; source = 'https://libssh2.org' },
        @{ name = 'zstd'; license = 'BSD-3-Clause'; source = 'https://facebook.github.io/zstd' },
        @{ name = 'bzip2'; license = 'bzip2-1.0.6'; source = 'https://sourceware.org/bzip2' },
        @{ name = 'expat'; license = 'MIT'; source = 'https://libexpat.github.io' },
        @{ name = 'ncurses'; license = 'X11'; source = 'https://invisible-island.net/ncurses' },
        @{ name = 'readline'; license = 'GPL-3.0 (review: redistribution obligations)'; source = 'https://tiswww.case.edu/php/chet/readline/rltop.html' },
        @{ name = 'Python 3.14'; license = 'PSF-2.0'; source = 'https://python.org' },
        @{ name = 'git'; license = 'GPL-2.0 (review: redistribution obligations)'; source = 'https://git-scm.com' },
        @{ name = 'ripgrep'; license = 'MIT OR Unlicense'; source = 'https://github.com/BurntSushi/ripgrep' },
        @{ name = 'curl'; license = 'curl'; source = 'https://curl.se' }
    )

    $runtimeManifest = [ordered]@{
        manifestVersion = 1
        payloadVersion = $payloadVersion
        archive = [ordered]@{ asset = 'dsh/payload.zip'; size = $payloadSize; sha256 = $payloadSha }
        node = [ordered]@{ jniLib = 'libdshnode.so'; size = $nodeInfo.size; sha256 = $nodeInfo.sha256 }
        kernelEntry = 'dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js'
        runtimeLibraryDir = 'runtime/lib'
        fileCount = $fileCount
        criticalFiles = $criticalFiles
        source = $sourceInfo
        licenses = $licenses
    }
    $manifestPath = Join-Path $assetsDir 'MANIFEST.json'
    $runtimeManifest | ConvertTo-Json -Depth 8 | Set-Content -Encoding ASCII -Path $manifestPath
    Write-Host "runtime manifest -> $manifestPath"

    # ------------------------------------------------------------ 6. third_party provenance manifest

    if (-not (Test-Path $thirdPartyDir)) { New-Item -ItemType Directory -Force -Path $thirdPartyDir | Out-Null }
    $thirdPartyManifest = [ordered]@{
        generatedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        source = [ordered]@{
            referenceApkUrl = 'https://github.com/woaiys3/deepseek-harness-android-app/releases/download/v1.20.1/DeepSeekHarness.apk'
            referenceApkPath = $apkPath
            referenceApkSize = $apkSize
            referenceApkSha256 = $apkSha
            payloadZipSha256 = $payloadSha
            payloadZipSize = $payloadSize
            kernelVersion = $kernelVersion
            kernelRevision = $kernelRevision
            officialCommit = $sourceInfo.officialCommit
        }
        payload = [ordered]@{
            fileCount = $fileCount
            totalBytes = $totalBytes
            runtimeLibraryDir = 'runtime/lib'
            kernelEntry = 'dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js'
        }
        jniLibsMapping = $mapping
        criticalFiles = $criticalFiles
        licenses = $licenses
        notes = @(
            'Binaries are not committed; regenerate with scripts/dsh/stage.ps1 and distribute under the listed licenses.',
            'readline (GPL-3.0) and git (GPL-2.0) are included in the reference payload; their redistribution obligations need legal review before shipping.',
            'The reference payload bundles python/npm/pnpm wrapper scripts that cannot be executed from writable app storage on targetSdk 36; only the ELF executables above are mirrored to nativeLibraryDir.'
        )
    }
    $thirdPartyManifestPath = Join-Path $thirdPartyDir 'MANIFEST.json'
    $thirdPartyManifest | ConvertTo-Json -Depth 8 | Set-Content -Encoding ASCII -Path $thirdPartyManifestPath
    Write-Host "third_party manifest -> $thirdPartyManifestPath"

    # full file list for audit (name + size), never committed (third_party/dsh is git-ignored)
    $filesTsv = Join-Path $thirdPartyDir 'FILES.tsv'
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine("size`tpath")
    foreach ($e in $fileEntries) { [void]$sb.AppendLine("$($e.Length)`t$($e.FullName)") }
    [System.IO.File]::WriteAllText($filesTsv, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))

    # ------------------------------------------------------------ 7. lock update

    if ($UpdateLock -or -not (Test-Path $lockPath)) {
        $lock = [ordered]@{
            generatedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
            apkUrl = 'https://github.com/woaiys3/deepseek-harness-android-app/releases/download/v1.20.1/DeepSeekHarness.apk'
            apkSize = $apkSize
            apkSha256 = $apkSha
            payloadSize = $payloadSize
            payloadSha256 = $payloadSha
            kernelVersion = $kernelVersion
            kernelRevision = $kernelRevision
            executables = $mapping
            criticalFiles = $criticalFiles
        }
        $lock | ConvertTo-Json -Depth 8 | Set-Content -Encoding ASCII -Path $lockPath
        Write-Host "payload lock -> $lockPath"
    }

    # ------------------------------------------------------------ 8. optional full extraction

    if ($Extract) {
        $extractDir = Join-Path $thirdPartyDir 'payload'
        if (Test-Path $extractDir) { Remove-Item -Recurse -Force $extractDir }
        New-Item -ItemType Directory -Force -Path $extractDir | Out-Null
        $extractRoot = (Resolve-Path $extractDir).Path
        foreach ($e in $fileEntries) {
            $target = Join-Path $extractRoot ($e.FullName -replace '/', '\')
            $parent = Split-Path -Parent $target
            if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $target, $true)
        }
        Write-Host "extracted payload tree -> $extractDir"
    }
} finally {
    $zip.Dispose()
}

Write-Host ''
Write-Host 'STAGE OK'
Write-Host "  assets : $assetsDir\payload.zip + MANIFEST.json"
Write-Host "  jniLibs: $jniDir"
Write-Host "  audit  : $thirdPartyDir\MANIFEST.json"
