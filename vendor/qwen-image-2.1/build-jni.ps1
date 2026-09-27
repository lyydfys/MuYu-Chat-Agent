param(
    [string]$AndroidNdk = $env:ANDROID_NDK,
    [string]$BuildDir = (Join-Path $env:TEMP 'mca-qwenimage21-jni-arm64'),
    [string]$Ninja = ''
)

$ErrorActionPreference = 'Stop'
$vendorRoot = (Resolve-Path $PSScriptRoot).Path
$repoRoot = (Resolve-Path (Join-Path $vendorRoot '..\..')).Path

if ([string]::IsNullOrWhiteSpace($AndroidNdk)) {
    throw 'Pass -AndroidNdk or set ANDROID_NDK to an installed Android NDK directory.'
}
$toolchain = Join-Path $AndroidNdk 'build\cmake\android.toolchain.cmake'
if (-not (Test-Path -LiteralPath $toolchain)) {
    throw "Android NDK toolchain not found: $toolchain"
}
$ndkProperties = Join-Path $AndroidNdk 'source.properties'
$ndkRevision = if (Test-Path -LiteralPath $ndkProperties) {
    (Select-String -LiteralPath $ndkProperties -Pattern '^Pkg.Revision\s*=\s*(.+)$').Matches.Groups[1].Value.Trim()
} else { '' }
if ($ndkRevision -ne '29.0.14206865') {
    throw "This native snapshot is pinned to Android NDK 29.0.14206865; got '$ndkRevision'."
}
$cxxRuntime = Join-Path $AndroidNdk 'toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\libc++_shared.so'
$expectedCxxSha256 = '0C52CFAB2DF0D957D8B346A2BDC5AE8D71FECA2591924D77E1CD724D5BF74352'
if (-not (Test-Path -LiteralPath $cxxRuntime)) { throw "NDK C++ runtime not found: $cxxRuntime" }
$actualCxxSha256 = (Get-FileHash -LiteralPath $cxxRuntime -Algorithm SHA256).Hash
if ($actualCxxSha256 -ne $expectedCxxSha256) {
    throw "NDK libc++_shared.so SHA-256 mismatch: $actualCxxSha256"
}
$vendoredCxxRuntime = Join-Path $vendorRoot 'arm64-v8a\libc++_shared.so'
if (-not (Test-Path -LiteralPath $vendoredCxxRuntime) -or
    (Get-FileHash -LiteralPath $vendoredCxxRuntime -Algorithm SHA256).Hash -ne $expectedCxxSha256) {
    throw "Vendored libc++_shared.so is missing or does not match NDK 29.0.14206865: $vendoredCxxRuntime"
}
if ([string]::IsNullOrWhiteSpace($Ninja)) {
    $Ninja = (Get-Command ninja -ErrorAction SilentlyContinue).Source
}
if ([string]::IsNullOrWhiteSpace($Ninja) -or -not (Test-Path -LiteralPath $Ninja)) {
    throw 'Ninja was not found. Pass -Ninja with the full path to ninja.exe.'
}

$resolvedBuildDir = [System.IO.Path]::GetFullPath($BuildDir)
New-Item -ItemType Directory -Force -Path $resolvedBuildDir | Out-Null

& cmake -S $vendorRoot -B $resolvedBuildDir -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$Ninja" `
    "-DCMAKE_TOOLCHAIN_FILE=$toolchain" `
    '-DANDROID_ABI=arm64-v8a' `
    '-DANDROID_PLATFORM=android-26' `
    '-DANDROID_STL=c++_shared' `
    '-DCMAKE_BUILD_TYPE=Release'
if ($LASTEXITCODE -ne 0) { throw "CMake configure failed: $LASTEXITCODE" }

& cmake --build $resolvedBuildDir --target qwenimage21_jni --parallel
if ($LASTEXITCODE -ne 0) { throw "JNI build failed: $LASTEXITCODE" }

$artifact = Join-Path $resolvedBuildDir 'libqwenimage21_jni.so'
if (-not (Test-Path -LiteralPath $artifact)) { throw "JNI output not found: $artifact" }
$readelf = Join-Path $AndroidNdk 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe'
if (-not (Test-Path -LiteralPath $readelf)) { throw "NDK llvm-readelf not found: $readelf" }
$jniDynamic = (& $readelf -d $artifact) -join "`n"
$mnnDynamic = (& $readelf -d (Join-Path $vendorRoot 'arm64-v8a\libmca_qwenimage21_mnn.so')) -join "`n"
if ($jniDynamic -notmatch 'SONAME.*libqwenimage21_jni\.so' -or
    $jniDynamic -notmatch 'NEEDED.*libmca_qwenimage21_mnn\.so' -or
    $jniDynamic -notmatch 'NEEDED.*libc\+\+_shared\.so' -or
    $jniDynamic -match 'NEEDED.*libMNN\.so') {
    throw 'JNI ELF dependency/SONAME check failed; expected the unique Qwen MNN name and libc++_shared.'
}
if ($mnnDynamic -notmatch 'SONAME.*libmca_qwenimage21_mnn\.so' -or
    $mnnDynamic -notmatch 'NEEDED.*libc\+\+_shared\.so' -or
    $mnnDynamic -match 'NEEDED.*libMNN\.so') {
    throw 'Pinned MNN ELF dependency/SONAME check failed.'
}
Write-Output "JNI artifact: $artifact"
Write-Output "MCA checkout: $repoRoot"
Write-Output "JNI SONAME/DT_NEEDED verified; pinned libc++_shared SHA-256: $expectedCxxSha256"
