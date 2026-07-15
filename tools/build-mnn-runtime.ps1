[CmdletBinding()]
param(
    [string]$MnnSourceRoot = (Join-Path (Split-Path -Parent $PSScriptRoot) "third_party\MNN"),
    [string]$BuildDir,
    [ValidateSet("arm64-v8a", "armeabi-v7a", "x86", "x86_64")]
    [string]$Abi = "arm64-v8a",
    [ValidateRange(16, 99)]
    [int]$ApiLevel = 26,
    [switch]$Clean,
    [string]$AndroidSdkRoot,
    [string]$AndroidNdkRoot,
    [string]$CmakePath,
    [string]$NinjaPath,
    [string]$VendorManifestPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-ExistingDirectory {
    param([string]$Description, [string[]]$Candidates)

    foreach ($candidate in $Candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) {
            continue
        }
        $fullPath = [System.IO.Path]::GetFullPath($candidate)
        if (Test-Path -LiteralPath $fullPath -PathType Container) {
            return $fullPath
        }
    }
    throw "Could not find $Description. Provide its path explicitly."
}

function Get-ExistingFile {
    param([string]$Description, [string[]]$Candidates)

    foreach ($candidate in $Candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) {
            continue
        }
        $fullPath = [System.IO.Path]::GetFullPath($candidate)
        if (Test-Path -LiteralPath $fullPath -PathType Leaf) {
            return $fullPath
        }
    }
    throw "Could not find $Description. Provide its path explicitly."
}

function Invoke-Native {
    param([string]$Executable, [string[]]$Arguments)

    Write-Host "> $Executable $($Arguments -join ' ')"
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $Executable"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($VendorManifestPath)) {
    $VendorManifestPath = Join-Path $repoRoot "vendor\mnn\mnn-vendor.properties"
}
$verifyVendorScript = Join-Path $repoRoot "tools\vendor\verify-mnn-vendor.ps1"
if (-not (Test-Path -LiteralPath $verifyVendorScript -PathType Leaf)) {
    throw "MNN vendor verifier is missing: $verifyVendorScript"
}
& $verifyVendorScript -SourceRoot $MnnSourceRoot -ManifestPath $VendorManifestPath

$MnnSourceRoot = Get-ExistingDirectory "MNN source root" @($MnnSourceRoot)
if (-not (Test-Path -LiteralPath (Join-Path $MnnSourceRoot "CMakeLists.txt") -PathType Leaf)) {
    throw "MNN source root does not contain CMakeLists.txt: $MnnSourceRoot"
}

if ([string]::IsNullOrWhiteSpace($BuildDir)) {
    $BuildDir = Join-Path $MnnSourceRoot "project\android\build_64_mca_full"
}
$BuildDir = [System.IO.Path]::GetFullPath($BuildDir)

$localPropertiesSdk = $null
$localProperties = Join-Path $repoRoot "local.properties"
if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
    $sdkLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match "^\s*sdk\.dir\s*=" } | Select-Object -First 1
    if ($sdkLine) {
        $localPropertiesSdk = ($sdkLine -replace "^\s*sdk\.dir\s*=\s*", "").Trim()
        $localPropertiesSdk = $localPropertiesSdk -replace "\\:", ":" -replace "\\\\", "\"
    }
}

$sdkCandidates = @(
    $AndroidSdkRoot,
    $env:ANDROID_SDK_ROOT,
    $env:ANDROID_HOME,
    $localPropertiesSdk
)
if ($env:LOCALAPPDATA) {
    $sdkCandidates += Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
if ($env:USERPROFILE) {
    $sdkCandidates += Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk"
}
$AndroidSdkRoot = Get-ExistingDirectory "Android SDK" $sdkCandidates

$ndkCandidates = @(
    $AndroidNdkRoot,
    $env:ANDROID_NDK_ROOT,
    $env:ANDROID_NDK_HOME,
    (Join-Path $AndroidSdkRoot "ndk-bundle")
)
$ndkDirectory = Join-Path $AndroidSdkRoot "ndk"
if (Test-Path -LiteralPath $ndkDirectory -PathType Container) {
    $ndkCandidates += Get-ChildItem -LiteralPath $ndkDirectory -Directory |
        Sort-Object Name -Descending |
        ForEach-Object FullName
}

$AndroidNdkRoot = $null
foreach ($candidate in $ndkCandidates) {
    if ([string]::IsNullOrWhiteSpace($candidate)) {
        continue
    }
    $candidate = [System.IO.Path]::GetFullPath($candidate)
    if (Test-Path -LiteralPath (Join-Path $candidate "build\cmake\android.toolchain.cmake") -PathType Leaf) {
        $AndroidNdkRoot = $candidate
        break
    }
}
if (-not $AndroidNdkRoot) {
    throw "Could not find an Android NDK with build\cmake\android.toolchain.cmake. Provide -AndroidNdkRoot."
}

$cmakeCandidates = @($CmakePath)
$cmakeFromPath = Get-Command cmake.exe -CommandType Application -ErrorAction SilentlyContinue
if ($cmakeFromPath) {
    $cmakeCandidates += $cmakeFromPath.Source
}
if ($env:ProgramFiles) {
    $cmakeCandidates += Join-Path $env:ProgramFiles "CMake\bin\cmake.exe"
}
if (${env:ProgramFiles(x86)}) {
    $cmakeCandidates += Join-Path ${env:ProgramFiles(x86)} "CMake\bin\cmake.exe"
}
$sdkCmakeDirectory = Join-Path $AndroidSdkRoot "cmake"
if (Test-Path -LiteralPath $sdkCmakeDirectory -PathType Container) {
    $cmakeCandidates += Get-ChildItem -LiteralPath $sdkCmakeDirectory -Directory |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName "bin\cmake.exe" }
}
$CmakePath = Get-ExistingFile "CMake executable" $cmakeCandidates

$ninjaCandidates = @($NinjaPath)
$ninjaFromPath = Get-Command ninja.exe -CommandType Application -ErrorAction SilentlyContinue
if ($ninjaFromPath) {
    $ninjaCandidates += $ninjaFromPath.Source
}
$ninjaCandidates += @(
    (Join-Path (Split-Path -Parent $CmakePath) "ninja.exe"),
    (Join-Path $AndroidNdkRoot "prebuilt\windows-x86_64\bin\ninja.exe")
)
if (Test-Path -LiteralPath $sdkCmakeDirectory -PathType Container) {
    $ninjaCandidates += Get-ChildItem -LiteralPath $sdkCmakeDirectory -Directory |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName "bin\ninja.exe" }
}
$NinjaPath = Get-ExistingFile "Ninja executable" $ninjaCandidates

$toolchainFile = Join-Path $AndroidNdkRoot "build\cmake\android.toolchain.cmake"
Write-Host "Android SDK: $AndroidSdkRoot"
Write-Host "Android NDK: $AndroidNdkRoot"
Write-Host "CMake: $CmakePath"
Write-Host "Ninja: $NinjaPath"

if ($Clean -and (Test-Path -LiteralPath $BuildDir)) {
    if ([System.IO.Path]::GetPathRoot($BuildDir) -eq $BuildDir -or $BuildDir -eq $MnnSourceRoot) {
        throw "Refusing to clean unsafe build directory: $BuildDir"
    }
    Write-Host "Cleaning: $BuildDir"
    Remove-Item -LiteralPath $BuildDir -Recurse -Force
}
New-Item -ItemType Directory -Path $BuildDir -Force | Out-Null

$configureArgs = @(
    "-S", $MnnSourceRoot,
    "-B", $BuildDir,
    "-G", "Ninja",
    "-DCMAKE_BUILD_TYPE=Release",
    "-DCMAKE_TOOLCHAIN_FILE=$toolchainFile",
    "-DCMAKE_MAKE_PROGRAM=$NinjaPath",
    "-DANDROID_ABI=$Abi",
    "-DANDROID_PLATFORM=android-$ApiLevel",
    "-DANDROID_STL=c++_shared",
    "-DMNN_BUILD_FOR_ANDROID_COMMAND=ON",
    "-DNATIVE_LIBRARY_OUTPUT=$BuildDir",
    "-DNATIVE_INCLUDE_OUTPUT=$(Join-Path $BuildDir 'include')",
    "-DMNN_BUILD_SHARED_LIBS=ON",
    "-DMNN_SEP_BUILD=ON",
    "-DMNN_BUILD_LLM=ON",
    "-DMNN_BUILD_LLM_OMNI=ON",
    "-DMNN_BUILD_DIFFUSION=ON",
    "-DMNN_BUILD_OPENCV=ON",
    "-DMNN_BUILD_AUDIO=ON",
    "-DMNN_IMGCODECS=ON",
    "-DMNN_IMGPROC_COLOR=ON",
    "-DMNN_OPENCL=ON"
)
Invoke-Native $CmakePath $configureArgs

$mnnTargets = @("MNN", "MNN_Express", "MNNOpenCV", "MNNAudio", "MNN_CL", "llm")
$buildArgs = @("--build", $BuildDir, "--target") + $mnnTargets + @("--parallel")
Invoke-Native $CmakePath $buildArgs

$runtimeLayouts = @(
    [ordered]@{
        Name = 'current'
        Libraries = [ordered]@{
            "MNN" = "libMNN.so"
            "MNN_Express" = "libMNN_Express.so"
            "MNNOpenCV" = "tools/cv/libMNNOpenCV.so"
            "MNNAudio" = "tools/audio/libMNNAudio.so"
            "MNN_CL" = "libMNN_CL.so"
            "llm" = "libllm.so"
        }
    },
    [ordered]@{
        Name = 'legacy'
        Libraries = [ordered]@{
            "MNN" = "OFF/arm64-v8a/libMNN.so"
            "MNN_Express" = "express/OFF/arm64-v8a/libMNN_Express.so"
            "MNNOpenCV" = "tools/cv/OFF/arm64-v8a/libMNNOpenCV.so"
            "MNNAudio" = "tools/audio/OFF/arm64-v8a/libMNNAudio.so"
            "MNN_CL" = "source/backend/opencl/OFF/arm64-v8a/libMNN_CL.so"
            "llm" = "OFF/arm64-v8a/libllm.so"
        }
    }
)
$selectedLayout = $runtimeLayouts | Where-Object {
    $layout = $_
    @($layout.Libraries.Values | Where-Object {
        -not (Test-Path -LiteralPath (Join-Path $BuildDir $_) -PathType Leaf)
    }).Count -eq 0
} | Select-Object -First 1
if (-not $selectedLayout) {
    throw "MNN build completed, but no coherent current or legacy runtime layout was found under ${BuildDir}."
}
$builtLibraries = [ordered]@{}
foreach ($target in $selectedLayout.Libraries.Keys) {
    $builtLibraries[$target] = Join-Path $BuildDir $selectedLayout.Libraries[$target]
}
Write-Host "Built MNN runtime libraries:"
Write-Host "  layout: $($selectedLayout.Name)"
foreach ($target in $builtLibraries.Keys) {
    Write-Host "  ${target}: $($builtLibraries[$target])"
}

$vendorProperties = @{}
foreach ($line in Get-Content -LiteralPath $VendorManifestPath -Encoding UTF8) {
    if ($line -match '^\s*#' -or [string]::IsNullOrWhiteSpace($line) -or $line -notmatch '=') {
        continue
    }
    $key, $value = $line -split '=', 2
    $vendorProperties[$key.Trim()] = $value.Trim()
}
$vendorPatchSha256 = $vendorProperties['patchSha256']
$vendorCommit = $vendorProperties['commit']
if ([string]::IsNullOrWhiteSpace($vendorPatchSha256) -or [string]::IsNullOrWhiteSpace($vendorCommit)) {
    throw "MNN vendor manifest is missing commit or patchSha256: $VendorManifestPath"
}

$runtimeStampPath = Join-Path $BuildDir 'mca-mnn-runtime.properties'
$runtimeStampLines = @(
    '# Generated by tools/build-mnn-runtime.ps1. Do not edit.',
    "vendorCommit=$vendorCommit",
    "vendorPatchSha256=$vendorPatchSha256",
    "abi=$Abi",
    "apiLevel=$ApiLevel"
)
foreach ($target in $builtLibraries.Keys) {
    $library = Get-Item -LiteralPath $builtLibraries[$target]
    $runtimeStampLines += "lib.$($library.Name).sha256=$((Get-FileHash -LiteralPath $library.FullName -Algorithm SHA256).Hash.ToLowerInvariant())"
    $runtimeStampLines += "lib.$($library.Name).size=$($library.Length)"
}
[System.IO.File]::WriteAllLines($runtimeStampPath, $runtimeStampLines, [System.Text.UTF8Encoding]::new($false))
Write-Host "MNN runtime provenance: $runtimeStampPath"
Write-Host "MCA build option: -PmcaMnnAndroidBuildRoot=$BuildDir"
