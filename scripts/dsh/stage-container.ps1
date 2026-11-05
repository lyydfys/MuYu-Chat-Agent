<#
.SYNOPSIS
    Stages the offline container runtime (proot + Ubuntu rootfs) into the build tree.

.DESCRIPTION
    The container lane is a third-party asset decision, not source code: a prebuilt `proot`
    executable for Android arm64, its two shared-library dependencies, and an official Ubuntu
    base rootfs. None of those are committed to Git (see `.gitignore`); this script pins the
    exact upstream URLs and SHA-256 values, downloads them once, verifies every byte, and emits
    the two locations the app reads:

      core/dsh-runtime/src/main/jniLibs/arm64-v8a/libproot.so     (exec(), via nativeLibraryDir)
core/dsh-runtime/src/main/jniLibs/arm64-v8a/libproot_loader.so (proot's own loader; PROOT_LOADER)
      core/dsh-runtime/src/main/jniLibs/arm64-v8a/libtalloc.so
      core/dsh-runtime/src/main/jniLibs/arm64-v8a/libandroid-shmem.so
      app/src/main/assets/offline/container/rootfs.tar.gz
      app/src/main/assets/offline/container/container-manifest.json

    Android only permits exec() from the APK native library directory at targetSdk 36, which is
    why `proot` is packaged as `libproot.so` instead of an arbitrary file name. The same rule is
    why the rootfs may live in assets/files: it is data, never executed directly.

    A hash mismatch is a hard failure. Nothing here re-pins silently; to move to a new source,
    audit it first and then edit the table below.

.PARAMETER RepoRoot
    Repository root. Defaults to two levels up from this script.

.PARAMETER Cache
    Download cache. Defaults to artifacts/offline-runtime (git-ignored).

.EXAMPLE
    pwsh scripts/dsh/stage-container.ps1

.NOTES
    Licences (see docs/architecture/DSH_CONTAINER.md §7):
      proot / talloc / libandroid-shmem are Termux packages. proot is GPL-2.0-or-later; the
      Termux packaging carries its own notices. Only the compiled binaries are staged here, and
      the release record must name them with their source URL so the corresponding-source
      obligation can be met.
      The Ubuntu base rootfs is an Ubuntu image: every component keeps its own licence under
      /usr/share/doc inside the image; Canonical's trademark policy applies to redistribution.
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))),
    [string]$Cache = ''
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

if (-not $Cache) { $Cache = Join-Path $RepoRoot 'artifacts/offline-runtime' }

# Pinned sources. Every URL is paired with the SHA-256 the upstream index publishes; the download
# is rejected unless the bytes hash to exactly that value.
$ProotPackage = @{
    Name = 'proot'
    Version = '5.1.107.102'
    Url = 'https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.102_aarch64.deb'
    Sha256 = '10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030'
    File = 'proot_5.1.107.102_aarch64.deb'
    Member = './data/data/com.termux/files/usr/bin/proot'
    License = 'GPL-2.0-or-later'
    Repository = 'https://packages.termux.dev/apt/termux-main'
}
$TallocPackage = @{
    Name = 'libtalloc'
    Version = '2.5.0'
    Url = 'https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb'
    Sha256 = '556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7'
    File = 'libtalloc_2.5.0_aarch64.deb'
    Member = './data/data/com.termux/files/usr/lib/libtalloc.so.2.5.0'
    License = 'LGPL-3.0-or-later'
    Repository = 'https://packages.termux.dev/apt/termux-main'
}
$ShmemPackage = @{
    Name = 'libandroid-shmem'
    Version = '0.7'
    Url = 'https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb'
    Sha256 = '0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6'
    File = 'libandroid-shmem_0.7_aarch64.deb'
    Member = './data/data/com.termux/files/usr/lib/libandroid-shmem.so'
    License = 'Apache-2.0'
    Repository = 'https://packages.termux.dev/apt/termux-main'
}
$RootfsImage = @{
    Distribution = 'Ubuntu 24.04.5 LTS (noble) base, arm64'
    Url = 'https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz'
    Sha256 = 'a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2'
    File = 'ubuntu-base-24.04.5-base-arm64.tar.gz'
    License = 'Ubuntu base image; each component keeps its licence under /usr/share/doc'
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Get-Cached([hashtable]$Package) {
    $path = Join-Path $Cache $Package.File
    if (-not (Test-Path -LiteralPath $path)) {
        Write-Host "download $($Package.File)"
        Invoke-WebRequest -Uri $Package.Url -OutFile $path -UseBasicParsing -TimeoutSec 900
    }
    $actual = Get-Sha256 $path
    if ($actual -ne $Package.Sha256) {
        throw "SHA-256 mismatch for $($Package.File): expected $($Package.Sha256), got $actual"
    }
    return $path
}

function Expand-DebMember([string]$DebPath, [string]$Member, [string]$Destination) {
    $work = Join-Path $Cache ('.deb-' + [System.IO.Path]::GetFileNameWithoutExtension($DebPath))
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    # A .deb is an `ar` archive; `tar` (bsdtar) reads it. Only `data.tar.xz` carries the payload.
    & tar -xf $DebPath -C $work data.tar.xz 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "cannot read data.tar.xz from $DebPath" }
    & tar -xf (Join-Path $work 'data.tar.xz') -C $work $Member 2>&1 | Out-Null
    $extracted = Join-Path $work ($Member -replace '^\./','')
    if (-not (Test-Path -LiteralPath $extracted)) {
        throw "member $Member not found in $DebPath"
    }
    $parent = Split-Path -Parent $Destination
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    Copy-Item -LiteralPath $extracted -Destination $Destination -Force
    Remove-Item -LiteralPath $work -Recurse -Force
}

$jniLibs = Join-Path $RepoRoot 'core/dsh-runtime/src/main/jniLibs/arm64-v8a'
$assets = Join-Path $RepoRoot 'app/src/main/assets/offline/container'
New-Item -ItemType Directory -Force -Path $jniLibs | Out-Null
New-Item -ItemType Directory -Force -Path $assets | Out-Null
New-Item -ItemType Directory -Force -Path $Cache | Out-Null

# 1) proot itself, packaged as a native library so PackageManager extracts it into a directory
#    the app is allowed to exec() from.
$prootDeb = Get-Cached $ProotPackage
$prootOut = Join-Path $jniLibs 'libproot.so'
Expand-DebMember -DebPath $prootDeb -Member $ProotPackage.Member -Destination $prootOut

# proot does NOT exec() the guest program itself: it exec()s a small "loader" that maps the guest
# ELF and then runs it. When PROOT_LOADER is unset, proot writes that loader to a temp file and
# execs it there -- which fails on targetSdk>=29 with the misleading
# "execve("/usr/bin/dash"): Permission denied". Shipping the loader as a native library puts it in
# the one directory this app may exec() from, so the whole chain works without touching targetSdk.
# Verified on device (Android 15, targetSdk 36): Ubuntu 24.04.5 aarch64 boots and runs commands.
Expand-DebMember -DebPath $prootDeb -Member './data/data/com.termux/files/usr/libexec/proot/loader' -Destination (Join-Path $jniLibs 'libproot_loader.so')

# 2) proot's runtime dependencies. Their SONAMEs are `libtalloc.so` / `libandroid-shmem.so`, which
#    is exactly the names the packaged copies carry, so the dynamic loader finds them through the
#    native library directory.
$tallocDeb = Get-Cached $TallocPackage
# APK packaging only accepts `*.so`, while proot's ELF records `libtalloc.so.2` as DT_NEEDED.
# So the packaged copy keeps the `.so` name and DshContainerInstaller materialises the SONAME copy
# in the container library directory on device.
Expand-DebMember -DebPath $tallocDeb -Member $TallocPackage.Member -Destination (Join-Path $jniLibs 'libtalloc.so')
$shmemDeb = Get-Cached $ShmemPackage
Expand-DebMember -DebPath $shmemDeb -Member $ShmemPackage.Member -Destination (Join-Path $jniLibs 'libandroid-shmem.so')

# 3) The rootfs archive.
$rootfs = Get-Cached $RootfsImage
Copy-Item -LiteralPath $rootfs -Destination (Join-Path $assets 'rootfs.img') -Force

# 4) The manifest the installer reads. It repeats every hash so a device can verify what it is
#    about to unpack without trusting the build machine.
$manifest = [ordered]@{
    schema = 'mca.dsh.container.v1'
    createdAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    rootfs = [ordered]@{
        archive = 'rootfs.img'
        bytes = (Get-Item (Join-Path $assets 'rootfs.img')).Length
        sha256 = (Get-Sha256 (Join-Path $assets 'rootfs.img'))
        distribution = $RootfsImage.Distribution
        sourceUrl = $RootfsImage.Url
        license = $RootfsImage.License
    }
    proot = [ordered]@{
        library = 'libproot.so'
        bytes = (Get-Item $prootOut).Length
        sha256 = (Get-Sha256 $prootOut)
        version = $ProotPackage.Version
        sourceUrl = $ProotPackage.Url
        license = $ProotPackage.License
        repository = $ProotPackage.Repository
    }
    libraries = @(
        [ordered]@{
            library = 'libtalloc.so'
            bytes = (Get-Item (Join-Path $jniLibs 'libtalloc.so')).Length
            sha256 = (Get-Sha256 (Join-Path $jniLibs 'libtalloc.so'))
            version = $TallocPackage.Version
            sourceUrl = $TallocPackage.Url
            license = $TallocPackage.License
        },
        [ordered]@{
            library = 'libandroid-shmem.so'
            bytes = (Get-Item (Join-Path $jniLibs 'libandroid-shmem.so')).Length
            sha256 = (Get-Sha256 (Join-Path $jniLibs 'libandroid-shmem.so'))
            version = $ShmemPackage.Version
            sourceUrl = $ShmemPackage.Url
            license = $ShmemPackage.License
        }
    )
}
$manifestPath = Join-Path $assets 'container-manifest.json'
# UTF-8 without BOM: the manifest is parsed by org.json on device, where a BOM is not skipped.
$manifestText = $manifest | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText($manifestPath, $manifestText, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ''
Write-Host 'container runtime staged:'
foreach ($file in @($prootOut, (Join-Path $jniLibs 'libtalloc.so'), (Join-Path $jniLibs 'libandroid-shmem.so'), (Join-Path $assets 'rootfs.img'))) {
    $size = (Get-Item -LiteralPath $file).Length
    Write-Host ("  {0,-24} {1,12} bytes  sha256={2}" -f (Split-Path -Leaf $file), $size, (Get-Sha256 $file))
}
Write-Host ("  manifest -> {0}" -f $manifestPath)
Write-Host ''
Write-Host 'These paths are git-ignored on purpose. A build without them still succeeds and the'
Write-Host 'container lane reports MISSING assets instead of pretending the runtime exists.'
