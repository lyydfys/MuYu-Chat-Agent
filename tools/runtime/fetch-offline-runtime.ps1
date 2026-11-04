<#
.SYNOPSIS
    Fetches the offline runtime assets DSHA ships: a proot binary and an Ubuntu rootfs.

.DESCRIPTION
    The reference project (DSHA) bundles an unprivileged container so the harness runs on a
    glibc system without Termux or root. Those are **binary assets**, not code: a proot
    executable and a ~200 MB Ubuntu arm64 rootfs. They are deliberately NOT committed to Git
    (the reference project does the same) — this script pins the exact upstream URLs and
    hashes them so a release build can be reproduced.

    Nothing here runs during a normal build. Run it once, keep the artefacts out of Git, and
    point the release build at the resulting folder when you actually want the container
    feature in an APK.

.PARAMETER Destination
    Folder that receives `proot-aarch64` and `ubuntu-noble-arm64-rootfs.tar.gz`.

.PARAMETER RootfsUrl
    Ubuntu base rootfs tarball. Defaults to the official noble arm64 base image.

.PARAMETER ProotUrl
    proot aarch64 binary. Defaults to the termux proot package, which is the binary the
    reference project's container is built around.

.EXAMPLE
    pwsh tools/runtime/fetch-offline-runtime.ps1 -Destination artifacts/offline-runtime

.NOTES
    After a successful run, publish the printed SHA-256 values in the release record; without
    them the asset cannot be verified as the one that was tested.
#>
[CmdletBinding()]
param(
    [string]$Destination = 'artifacts/offline-runtime',
    [string]$RootfsUrl = 'https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz',
    [string]$ProotUrl = 'https://github.com/termux/proot/releases/download/v5.1.107-59/proot-aarch64-static'
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $Destination | Out-Null

function Fetch-Asset {
    param([string]$Url, [string]$Path)
    if (Test-Path -LiteralPath $Path) {
        Write-Host "already present: $Path"
        return
    }
    Write-Host "downloading $Url"
    Invoke-WebRequest -Uri $Url -OutFile $Path -UseBasicParsing
    if (-not (Test-Path -LiteralPath $Path)) { throw "download failed: $Url" }
}

$rootfs = Join-Path $Destination 'ubuntu-noble-arm64-rootfs.tar.gz'
$proot = Join-Path $Destination 'proot-aarch64'
Fetch-Asset -Url $RootfsUrl -Path $rootfs
Fetch-Asset -Url $ProotUrl -Path $proot

# proot must be executable on Unix; on Windows this only stages the file.
if (-not $IsWindows) { & chmod 755 $proot }

Write-Host ''
Write-Host 'offline runtime staged:'
foreach ($file in @($rootfs, $proot)) {
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant()
    $size = (Get-Item -LiteralPath $file).Length
    Write-Host ("  {0}  {1} bytes  sha256={2}" -f (Split-Path -Leaf $file), $size, $hash)
}
Write-Host ''
Write-Host 'Next: copy these two files into app/src/main/assets/offline/ before a release build.'
Write-Host 'They are git-ignored on purpose: a 200 MB rootfs does not belong in source control.'
