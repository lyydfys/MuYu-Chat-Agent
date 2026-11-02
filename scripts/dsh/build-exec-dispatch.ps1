# Build and stage the DSH command dispatcher executable (D11).
#
# Why a script and not Gradle: the dispatcher must be an *executable* ELF (PT_INTERP
# /system/bin/linker64) so the payload's `bin/{npm,npx,pnpm,pip,bash,node,python}` names can be
# symlinked to it and started by name on targetSdk 36. AGP's externalNativeBuild packages
# add_library() outputs, not add_executable() targets, so this one artifact is compiled directly
# with the project's NDK and staged into jniLibs, which AGP does package.
# (Same model as scripts/dsh/stage.ps1: the binary is never committed, only regenerated.)
#
# Usage:
#   pwsh -File .\scripts\dsh\build-exec-dispatch.ps1
#   pwsh -File .\scripts\dsh\build-exec-dispatch.ps1 -NdkRoot D:\model\android-sdk\ndk\29.0.13113456 -CheckOnly
#
# Exit codes: 0 = built and verified (or -CheckOnly self-check passed), 1 = failure.

param(
    [string]$NdkRoot = '',
    [string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))),
    [int]$AndroidApi = 26,
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'

function Fail([string]$message) {
    Write-Error "build-exec-dispatch.ps1: $message"
    exit 1
}

$source = Join-Path $RepoRoot 'core\dsh-runtime\src\main\cpp\dsh_cmd.c'
if (-not (Test-Path $source)) { Fail "dispatcher source is missing: $source" }

# ---------------------------------------------------------------- NDK discovery

if ($NdkRoot -eq '') {
    if ($env:ANDROID_NDK_HOME -and (Test-Path $env:ANDROID_NDK_HOME)) {
        $NdkRoot = $env:ANDROID_NDK_HOME
    } else {
        $localProperties = Join-Path $RepoRoot 'local.properties'
        $sdk = ''
        if (Test-Path $localProperties) {
            $line = Select-String -Path $localProperties -Pattern '^\s*sdk\.dir\s*=' | Select-Object -First 1
            if ($line) { $sdk = ($line.Line -replace '^\s*sdk\.dir\s*=\s*', '').Replace('\\', '\').Trim() }
        }
        $catalog = Join-Path $RepoRoot 'gradle\libs.versions.toml'
        $ndkVersion = ''
        if (Test-Path $catalog) {
            $versionLine = Select-String -Path $catalog -Pattern '^\s*ndk\s*=\s*"([^"]+)"' | Select-Object -First 1
            if ($versionLine) { $ndkVersion = $versionLine.Matches[0].Groups[1].Value }
        }
        if ($sdk -eq '' -or $ndkVersion -eq '') {
            Fail 'cannot resolve the Android SDK/NDK version; pass -NdkRoot explicitly'
        }
        $NdkRoot = Join-Path (Join-Path $sdk 'ndk') $ndkVersion
    }
}
if (-not (Test-Path $NdkRoot)) { Fail "NDK root not found: $NdkRoot" }

$isWindowsHost = (Test-Path Env:OS) -and ($env:OS -eq 'Windows_NT')
$isMacHost = -not $isWindowsHost -and ((Test-Path Env:OSTYPE) -and ($env:OSTYPE -like 'darwin*'))
$hostTag = if ($isWindowsHost) { 'windows-x86_64' } elseif ($isMacHost) { 'darwin-x86_64' } else { 'linux-x86_64' }
$clang = Join-Path $NdkRoot "toolchains\llvm\prebuilt\$hostTag\bin\clang"
if ($isWindowsHost) { $clang = "$clang.exe" }
if (-not (Test-Path $clang)) { Fail "NDK clang not found: $clang" }
$readelf = Join-Path $NdkRoot "toolchains\llvm\prebuilt\$hostTag\bin\llvm-readelf"
if ($isWindowsHost) { $readelf = "$readelf.exe" }

$abi = 'arm64-v8a'
$target = "aarch64-linux-android$AndroidApi"
$output = Join-Path $RepoRoot "core\dsh-runtime\src\main\jniLibs\$abi\libdshcmd.so"

Write-Host "NDK   : $NdkRoot"
Write-Host "source: $source"
Write-Host "output: $output"

# ---------------------------------------------------------------- compile + link

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("dsh-dispatch-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
$tempOutput = Join-Path $tempDir 'libdshcmd.so'
try {
    $arguments = @(
        "--target=$target",
        '-fPIE',
        '-pie',
        '-O2',
        '-Wall',
        '-Wextra',
        '-Werror',
        $source,
        '-o',
        $tempOutput
    )
    & $clang @arguments
    if ($LASTEXITCODE -ne 0) { Fail "clang failed with exit $LASTEXITCODE" }
    if (-not (Test-Path $tempOutput)) { Fail 'clang produced no output' }

    $header = & $readelf -h $tempOutput 2>&1 | Out-String
    if ($header -notmatch 'Type:\s+DYN') { Fail 'dispatcher is not an ET_DYN (PIE) executable' }
    $interp = & $readelf -l $tempOutput 2>&1 | Out-String
    if ($interp -notmatch '/system/bin/linker64') {
        Fail 'dispatcher has no /system/bin/linker64 interpreter; it cannot be exec()d on Android'
    }
    $sha = (Get-FileHash -Algorithm SHA256 -Path $tempOutput).Hash.ToLowerInvariant()
    $size = (Get-Item $tempOutput).Length
    Write-Host "built : $size bytes sha256=$sha"

    if ($CheckOnly) {
        Write-Host 'CHECK OK (not staged)'
        exit 0
    }

    $directory = Split-Path -Parent $output
    if (-not (Test-Path $directory)) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
    Copy-Item -Force $tempOutput $output
    $stagedSha = (Get-FileHash -Algorithm SHA256 -Path $output).Hash.ToLowerInvariant()
    if ($stagedSha -ne $sha) { Fail "staged artifact hash $stagedSha != built $sha" }
    Write-Host 'STAGE OK'
    Write-Host '  Next: symlink payload bin/{npm,npx,pnpm,pnpx,pip,pip3,bash} to this file;'
    Write-Host '        DshNodeCommand.environment exports DSH_PAYLOAD_DIR so the dispatcher resolves JS/Python entries.'
    exit 0
} finally {
    if (Test-Path $tempDir) { Remove-Item -Recurse -Force $tempDir }
}

