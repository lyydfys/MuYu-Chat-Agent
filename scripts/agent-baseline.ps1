#!/usr/bin/env pwsh
# Reproducible source baseline for the native Agent completion work (N00 / N25 evidence).
#
# Prints a JSON document with:
#   - branch, HEAD
#   - SHA-256 over the sorted list of "<path>\0<content-sha256>" for tracked modifications
#   - the same for untracked (non-ignored) files
#   - each submodule's recorded revision, dirty count and diff fingerprint
#
# It only reads the working tree. It never stages, resets or modifies a file, and it never
# publishes anything. Use `-OutFile` to store the evidence next to a candidate report.

[CmdletBinding()]
param(
    [string] $OutFile
)

$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot

function Get-ContentFingerprint {
    param([string[]] $RelativePaths)
    $lines = foreach ($relative in ($RelativePaths | Sort-Object -Unique)) {
        $absolute = Join-Path $repo $relative
        if (-not (Test-Path -LiteralPath $absolute -PathType Leaf)) { continue }
        $hash = (Get-FileHash -LiteralPath $absolute -Algorithm SHA256).Hash.ToLowerInvariant()
        "$relative`0$hash"
    }
    $payload = ($lines -join "`n")
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

Push-Location $repo
try {
    $branch = (& git rev-parse --abbrev-ref HEAD 2>$null).Trim()
    $head = (& git rev-parse HEAD 2>$null).Trim()

    # `git diff HEAD --name-only` covers staged + unstaged tracked changes; `git ls-files
    # --others --exclude-standard` lists exactly the files `git add .` would pick up (ignored build
    # output and artifacts are excluded). Both are file-level, so the fingerprint covers content.
    $tracked = @(& git diff HEAD --name-only 2>$null)
    $untracked = @(& git ls-files --others --exclude-standard 2>$null)

    $submodules = @()
    if (Test-Path (Join-Path $repo '.gitmodules')) {
        $paths = @(& git config --file .gitmodules --get-regexp '^submodule\..*\.path$' 2>$null |
            ForEach-Object { ($_ -split ' ', 2)[1] })
        foreach ($path in $paths) {
            $absolute = Join-Path $repo $path
            if (-not (Test-Path -LiteralPath $absolute)) { continue }
            $revision = (& git -C $absolute rev-parse HEAD 2>$null).Trim()
            $subTracked = @(& git -C $absolute diff HEAD --name-only 2>$null)
            $subUntracked = @(& git -C $absolute ls-files --others --exclude-standard 2>$null)
            $submodules += [ordered]@{
                path                    = $path
                revision                = $revision
                dirtyTrackedFiles       = $subTracked.Count
                dirtyUntrackedFiles     = $subUntracked.Count
                diffSha256              = Get-ContentFingerprint -RelativePaths (@(
                        $subTracked | ForEach-Object { Join-Path $path $_ }))
                untrackedManifestSha256 = Get-ContentFingerprint -RelativePaths (@(
                        $subUntracked | ForEach-Object { Join-Path $path $_ }))
            }
        }
    }

    $document = [ordered]@{
        recordedAt            = (Get-Date).ToString('o')
        branch                = $branch
        head                  = $head
        trackedDiffFiles      = $tracked.Count
        trackedDiffSha256     = Get-ContentFingerprint -RelativePaths $tracked
        untrackedFiles        = $untracked.Count
        untrackedManifestSha256 = Get-ContentFingerprint -RelativePaths $untracked
        submodules            = $submodules
        roomSchemaVersion     = (Select-String -Path 'app/src/main/java/com/muyuchat/mca/ChatSessionStoreRoomDatabase.kt' `
                -Pattern 'version\s*=\s*(\d+)' | Select-Object -First 1 | ForEach-Object { $_.Matches[0].Groups[1].Value })
        adbDevices            = @((& 'D:\model\android-sdk\platform-tools\adb.exe' devices 2>$null) |
                Where-Object { $_ -match '\sdevice$' })
    }

    $json = $document | ConvertTo-Json -Depth 6
    if ($OutFile) {
        $directory = Split-Path -Parent $OutFile
        if ($directory -and -not (Test-Path -LiteralPath $directory)) {
            New-Item -ItemType Directory -Path $directory -Force | Out-Null
        }
        Set-Content -LiteralPath $OutFile -Value $json -Encoding UTF8
        Write-Output "wrote $OutFile"
    }
    Write-Output $json
} finally {
    Pop-Location
}
