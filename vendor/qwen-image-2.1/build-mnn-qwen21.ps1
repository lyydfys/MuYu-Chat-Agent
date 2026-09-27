param(
    [string]$MnnSource = $env:QWEN21_MNN_SOURCE,
    [string]$AndroidNdk = $env:ANDROID_NDK,
    [string]$BuildDir = (Join-Path $env:TEMP 'mca-qwenimage21-mnn-arm64'),
    [int]$Parallelism = 4,
    [string]$Ninja = '',
    [switch]$CopyToVendor
)

$ErrorActionPreference = 'Stop'
$vendorRoot = (Resolve-Path $PSScriptRoot).Path
$expectedMnnCommit = '1965a2632b9d1197dff2ef364d307e40c150a5c7'

if ([string]::IsNullOrWhiteSpace($MnnSource) -or -not (Test-Path -LiteralPath (Join-Path $MnnSource 'CMakeLists.txt'))) {
    throw 'Pass -MnnSource (or QWEN21_MNN_SOURCE) pointing to the pinned scsonic/MNN checkout.'
}
$actualMnnCommit = (& git -C $MnnSource rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $actualMnnCommit -ne $expectedMnnCommit) {
    throw "Expected scsonic/MNN commit $expectedMnnCommit; got '$actualMnnCommit'."
}
$mcaPatch = Join-Path $vendorRoot 'patches\mnn\qwen-image21-runtime-adaptation.patch'
if (-not (Test-Path -LiteralPath $mcaPatch)) {
    throw "MCA Qwen runtime-adaptation patch is missing: $mcaPatch"
}
& git -C $MnnSource apply --reverse --check --ignore-whitespace $mcaPatch 2>$null
if ($LASTEXITCODE -eq 0) {
    Write-Output 'MCA Qwen runtime-adaptation patch is already applied.'
} else {
    & git -C $MnnSource apply --check --ignore-whitespace $mcaPatch
    if ($LASTEXITCODE -ne 0) {
        throw 'The pinned MNN checkout has local changes that conflict with the MCA Qwen runtime-adaptation patch.'
    }
    & git -C $MnnSource apply --ignore-whitespace $mcaPatch
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to apply the MCA Qwen runtime-adaptation patch.'
    }
    Write-Output "Applied MCA Qwen runtime patch to the pinned source: $mcaPatch"
}
if ([string]::IsNullOrWhiteSpace($AndroidNdk)) {
    throw 'Pass -AndroidNdk or set ANDROID_NDK to Android NDK 29.0.14206865.'
}
$ndkProperties = Join-Path $AndroidNdk 'source.properties'
$ndkRevision = if (Test-Path -LiteralPath $ndkProperties) {
    (Select-String -LiteralPath $ndkProperties -Pattern '^Pkg.Revision\s*=\s*(.+)$').Matches.Groups[1].Value.Trim()
} else { '' }
if ($ndkRevision -ne '29.0.14206865') {
    throw "This native snapshot is pinned to Android NDK 29.0.14206865; got '$ndkRevision'."
}
$toolchain = Join-Path $AndroidNdk 'build\cmake\android.toolchain.cmake'
if (-not (Test-Path -LiteralPath $toolchain)) { throw "NDK toolchain not found: $toolchain" }
$cxxRuntime = Join-Path $AndroidNdk 'toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\libc++_shared.so'
$expectedCxxSha256 = '0C52CFAB2DF0D957D8B346A2BDC5AE8D71FECA2591924D77E1CD724D5BF74352'
if (-not (Test-Path -LiteralPath $cxxRuntime) -or
    (Get-FileHash -LiteralPath $cxxRuntime -Algorithm SHA256).Hash -ne $expectedCxxSha256) {
    throw "Pinned NDK libc++_shared.so is missing or has the wrong SHA-256: $cxxRuntime"
}
if ([string]::IsNullOrWhiteSpace($Ninja)) { $Ninja = (Get-Command ninja -ErrorAction SilentlyContinue).Source }
if ([string]::IsNullOrWhiteSpace($Ninja) -or -not (Test-Path -LiteralPath $Ninja)) {
    throw 'Ninja was not found. Pass -Ninja with the full path to ninja.exe.'
}
if ($Parallelism -lt 1) {
    throw 'Parallelism must be at least 1.'
}

$resolvedBuildDir = [System.IO.Path]::GetFullPath($BuildDir)
$driverDir = Join-Path $resolvedBuildDir 'driver'
$nativeBuildDir = Join-Path $resolvedBuildDir 'build'
New-Item -ItemType Directory -Force -Path $driverDir,$nativeBuildDir | Out-Null
$sourcePathForCmake = ([System.IO.Path]::GetFullPath($MnnSource)).Replace('\','/')
$driverCmake = @"
cmake_minimum_required(VERSION 3.22)
project(mca_qwen21_mnn_build LANGUAGES C CXX ASM)
set(MNN_USE_SSE OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_FOR_ANDROID_COMMAND ON CACHE BOOL "" FORCE)
set(MNN_LOW_MEMORY ON CACHE BOOL "" FORCE)
set(MNN_BUILD_DIFFUSION ON CACHE BOOL "" FORCE)
set(MNN_BUILD_LLM ON CACHE BOOL "" FORCE)
set(MNN_BUILD_OPENCV ON CACHE BOOL "" FORCE)
set(MNN_IMGCODECS ON CACHE BOOL "" FORCE)
set(MNN_OPENCL ON CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD OFF CACHE BOOL "" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "" FORCE)
set(MNN_USE_LOGCAT ON CACHE BOOL "" FORCE)
add_subdirectory("$sourcePathForCmake" mnn)
set_target_properties(MNN PROPERTIES OUTPUT_NAME mca_qwenimage21_mnn)
"@
Set-Content -LiteralPath (Join-Path $driverDir 'CMakeLists.txt') -Value $driverCmake -Encoding ascii

& cmake -S $driverDir -B $nativeBuildDir -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$Ninja" `
    "-DCMAKE_TOOLCHAIN_FILE=$toolchain" `
    '-DANDROID_ABI=arm64-v8a' `
    '-DANDROID_PLATFORM=android-26' `
    '-DANDROID_STL=c++_shared' `
    '-DCMAKE_BUILD_TYPE=Release' `
    '-DMNN_BUILD_SHARED_LIBS=ON' `
    '-DMNN_BUILD_FOR_ANDROID_COMMAND=ON' `
    '-DNATIVE_LIBRARY_OUTPUT=.' `
    '-DNATIVE_INCLUDE_OUTPUT=.'
if ($LASTEXITCODE -ne 0) { throw "MNN CMake configure failed: $LASTEXITCODE" }
& cmake --build $nativeBuildDir --target MNN --parallel $Parallelism
if ($LASTEXITCODE -ne 0) { throw "Pinned MNN build failed: $LASTEXITCODE" }

$artifact = Join-Path $nativeBuildDir 'mnn\libmca_qwenimage21_mnn.so'
if (-not (Test-Path -LiteralPath $artifact)) { throw "Expected MNN output not found: $artifact" }
$readelf = Join-Path $AndroidNdk 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe'
$mnnDynamic = (& $readelf -d $artifact) -join "`n"
if ($mnnDynamic -notmatch 'SONAME.*libmca_qwenimage21_mnn\.so' -or
    $mnnDynamic -notmatch 'NEEDED.*libc\+\+_shared\.so' -or
    $mnnDynamic -match 'NEEDED.*libMNN\.so') {
    throw 'Built MNN ELF dependency/SONAME check failed.'
}
if ($CopyToVendor) {
    $destination = Join-Path $vendorRoot 'arm64-v8a\libmca_qwenimage21_mnn.so'
    Copy-Item -LiteralPath $artifact -Destination $destination -Force
    $artifact = $destination
}
Write-Output "Pinned MNN artifact: $artifact"
Write-Output "Pinned source commit: $actualMnnCommit"
Write-Output "MNN SONAME/DT_NEEDED verified; pinned libc++_shared SHA-256: $expectedCxxSha256"
