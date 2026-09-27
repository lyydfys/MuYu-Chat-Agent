$ErrorActionPreference = "Stop"

# Resolve the repository root from this script so the release recipe is stable when it is
# invoked from a CI runner or another working directory.
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$buildRunId = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss-fffZ")
$buildRecordDirectory = Join-Path $projectRoot "artifacts\apk-build-records"
New-Item -ItemType Directory -Path $buildRecordDirectory -Force | Out-Null
$buildRecordPath = Join-Path $buildRecordDirectory "debug-$buildRunId.json"
$buildRecord = [ordered]@{
    schemaVersion = 1
    buildRunId = $buildRunId
    status = "started"
    startedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    completedAtUtc = $null
    variant = "debug"
    abis = @("arm64-v8a", "x86_64")
    gradleArguments = @()
    qnnSdkRoot = $null
    qnnRuntimeRoot = $null
    qnnRuntimeArchive = $null
    qnnRuntimeArchiveSha256 = $null
    sourceRevision = $null
    sourceTreeStatus = $null
    previousPassingBuildRunId = $null
    previousApkSha256 = $null
    addedNativeEntriesSincePrevious = @()
    removedNativeEntriesSincePrevious = @()
    qnnTypedBindingsStubMarkerAbsent = $null
    qnnTypedBindingsEvidence = $null
    toolchain = @{}
    appVersionCode = $null
    appVersionName = $null
    expectedEntries = @()
    presentEntries = @()
    assetEntries = @()
    missingEntries = @()
    validationErrors = @()
    abiLibraryCounts = @{}
    nativeLibraryEntriesByAbi = @{}
    apkPath = $null
    apkBytes = $null
    apkLastWriteTimeUtc = $null
    apkSha256 = $null
    installStatus = "not_attempted"
    deviceSmokeStatus = "not_run"
    failure = $null
}
function Save-BuildRecord {
    $buildRecord | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $buildRecordPath -Encoding utf8
}
function Stop-Build {
    param([Parameter(Mandatory = $true)][string]$Message)
    if ($buildRecord.status -eq "started") { $buildRecord.status = "failed" }
    $buildRecord.failure = $Message
    $buildRecord.completedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    Save-BuildRecord
    throw $Message
}
Save-BuildRecord

try {
$qnnSdkRoot = @(
    $env:MCA_QNN_SDK_ROOT,
    $env:QNN_SDK_ROOT,
    $env:QAIRT_SDK_ROOT
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1

if ([string]::IsNullOrWhiteSpace($qnnSdkRoot) -or -not (Test-Path -LiteralPath $qnnSdkRoot)) {
    Stop-Build "A valid QAIRT/QNN SDK is required. Set MCA_QNN_SDK_ROOT (or QNN_SDK_ROOT/QAIRT_SDK_ROOT) before building the standard APK."
}

$env:MCA_QNN_SDK_ROOT = $qnnSdkRoot
$env:QNN_SDK_ROOT = $qnnSdkRoot
$env:QAIRT_SDK_ROOT = $qnnSdkRoot
$buildRecord.qnnSdkRoot = (Resolve-Path -LiteralPath $qnnSdkRoot).Path

# The typed QNN bridge can compile from headers alone, but a complete APK also
# needs the Android host companions and Hexagon HTP files for every supported
# DSP generation. Check the runtime closure before Gradle so a headers-only or
# partially extracted SDK cannot produce a superficially successful APK.
$sdkLeaf = Split-Path -Leaf $qnnSdkRoot
$qnnRequiredRuntimeNames = @(
    "libQnnHtpV68.so", "libQnnHtpV68CalculatorStub.so", "libQnnHtpV68Skel.so", "libQnnHtpV68Stub.so",
    "libQnnHtpV69.so", "libQnnHtpV69CalculatorStub.so", "libQnnHtpV69Skel.so", "libQnnHtpV69Stub.so",
    "libQnnHtpV73.so", "libQnnHtpV73CalculatorStub.so", "libQnnHtpV73QemuDriver.so", "libQnnHtpV73Skel.so", "libQnnHtpV73Stub.so",
    "libQnnHtpV75.so", "libQnnHtpV75CalculatorStub.so", "libQnnHtpV75Skel.so", "libQnnHtpV75Stub.so"
)
function Get-MissingRuntimeNames([string]$Root) {
    if (-not (Test-Path -LiteralPath $Root)) { return @($qnnRequiredRuntimeNames) }
    $runtimeLibRoots = @(
        (Join-Path $Root "lib"),
        (Join-Path $Root "qairt\$sdkLeaf\lib")
    ) | Where-Object { Test-Path -LiteralPath $_ }
    if (-not $runtimeLibRoots) { return @($qnnRequiredRuntimeNames) }
    $available = @{}
    foreach ($runtimeLibRoot in $runtimeLibRoots) {
        Get-ChildItem -LiteralPath $runtimeLibRoot -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -in $qnnRequiredRuntimeNames } |
            ForEach-Object { $available[$_.Name] = $true }
    }
    return @($qnnRequiredRuntimeNames | Where-Object { -not $available.ContainsKey($_) })
}

$qnnRuntimeRoot = $qnnSdkRoot
$missingRuntimeNames = @(Get-MissingRuntimeNames $qnnRuntimeRoot)
$sdkParent = Split-Path -Parent $qnnSdkRoot
$zipCandidates = @(
    (Join-Path $sdkParent "v$sdkLeaf.zip"),
    (Join-Path (Split-Path -Parent $sdkParent) "v$sdkLeaf.zip"),
    (Join-Path (Split-Path -Parent (Split-Path -Parent $sdkParent)) "v$sdkLeaf.zip")
) | Where-Object { Test-Path -LiteralPath $_ }
$qnnSdkZip = $zipCandidates | Select-Object -First 1

if ($missingRuntimeNames.Count -gt 0 -and $qnnSdkZip) {
    $qnnSdkZip = (Resolve-Path -LiteralPath $qnnSdkZip).Path
    $qnnSdkZipHash = (Get-FileHash -LiteralPath $qnnSdkZip -Algorithm SHA256).Hash.ToLowerInvariant()
    $buildRecord.qnnRuntimeArchive = $qnnSdkZip
    $buildRecord.qnnRuntimeArchiveSha256 = $qnnSdkZipHash
    $qnnRuntimeRoot = Join-Path $projectRoot "build\qnn-runtime-sdk"
    $qnnRuntimeStamp = Join-Path $qnnRuntimeRoot ".source-$sdkLeaf.json"
    $expectedStamp = [ordered]@{ archive = $qnnSdkZip; sha256 = $qnnSdkZipHash } | ConvertTo-Json -Compress
    $stampMatches = (Test-Path -LiteralPath $qnnRuntimeStamp) -and
        ((Get-Content -LiteralPath $qnnRuntimeStamp -Raw).Trim() -eq $expectedStamp)
    $cachedMissing = @(Get-MissingRuntimeNames $qnnRuntimeRoot)
    if (-not $stampMatches -or $cachedMissing.Count -gt 0) {
        if (Test-Path -LiteralPath $qnnRuntimeRoot) {
            $buildRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot "build")).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
            $cachePath = [System.IO.Path]::GetFullPath($qnnRuntimeRoot)
            if (-not $cachePath.StartsWith($buildRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
                Stop-Build "Refusing to replace QNN runtime cache outside the repository build directory: $cachePath"
            }
            Remove-Item -LiteralPath $qnnRuntimeRoot -Recurse -Force
        }
        New-Item -ItemType Directory -Path $qnnRuntimeRoot -Force | Out-Null
        Expand-Archive -LiteralPath $qnnSdkZip -DestinationPath $qnnRuntimeRoot -Force
        Set-Content -LiteralPath $qnnRuntimeStamp -Value $expectedStamp -NoNewline
    }
    $missingRuntimeNames = @(Get-MissingRuntimeNames $qnnRuntimeRoot)
    Write-Host "Using QAIRT runtime libraries from extracted SDK archive: $qnnSdkZip"
}

$buildRecord.qnnRuntimeRoot = (Resolve-Path -LiteralPath $qnnRuntimeRoot).Path
if ($buildRecord.qnnRuntimeArchive -eq $null -and $qnnSdkZip) {
    $buildRecord.qnnRuntimeArchive = (Resolve-Path -LiteralPath $qnnSdkZip).Path
    $buildRecord.qnnRuntimeArchiveSha256 = (Get-FileHash -LiteralPath $qnnSdkZip -Algorithm SHA256).Hash.ToLowerInvariant()
}
if ($missingRuntimeNames.Count -gt 0) {
    $buildRecord.missingEntries = @($missingRuntimeNames | ForEach-Object { "QAIRT runtime source: $_" })
    Save-BuildRecord
    Stop-Build "QAIRT runtime is incomplete. Missing source libraries: $($missingRuntimeNames -join ', '). See $buildRecordPath"
}
Save-BuildRecord

$jdkCandidates = @(
    "$PSScriptRoot\..\toolchains\jdk-17",
    "$env:USERPROFILE\.jdks\ms-17.0.15"
)
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    foreach ($candidate in $jdkCandidates) {
        if (Test-Path -LiteralPath (Join-Path $candidate "bin\java.exe")) {
            $env:JAVA_HOME = $candidate
            $env:PATH = (Join-Path $candidate "bin") + ";" + $env:PATH
            break
        }
    }
}

$gitHead = & git -C $projectRoot rev-parse HEAD 2>$null
if ($LASTEXITCODE -eq 0) {
    $buildRecord.sourceRevision = ([string]$gitHead).Trim()
    $gitStatusLines = & git -C $projectRoot status --short 2>&1
    $gitStatusExitCode = $LASTEXITCODE
    $buildRecord.sourceTreeStatus = if ($gitStatusExitCode -eq 0) { @($gitStatusLines) } else { "unavailable: git status failed: $($gitStatusLines -join ' ')" }
} else {
    $buildRecord.sourceRevision = "unavailable: git rev-parse failed"
    $buildRecord.sourceTreeStatus = "unavailable: repository metadata could not be read"
}

$javaVersion = $null
if (Get-Command java -ErrorAction SilentlyContinue) {
    $javaVersion = (& java -version 2>&1 | Out-String).Trim()
}
$ndkCandidates = @($env:ANDROID_NDK_HOME, $env:ANDROID_NDK_ROOT) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$androidSdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
if ([string]::IsNullOrWhiteSpace($androidSdkRoot)) {
    $localProperties = Join-Path $projectRoot "local.properties"
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties | Select-String '^sdk\.dir='
        if ($sdkLine) {
            $androidSdkRoot = ($sdkLine.Line -split '=', 2)[1].Trim() -replace '\\:', ':' -replace '\\\\', '\'
        }
    }
}
if ($androidSdkRoot -and (Test-Path -LiteralPath (Join-Path $androidSdkRoot "ndk"))) {
    $versionCatalog = Join-Path $projectRoot "gradle\libs.versions.toml"
    $configuredNdkVersion = $null
    if (Test-Path -LiteralPath $versionCatalog) {
        $ndkVersionLine = Get-Content -LiteralPath $versionCatalog | Select-String '^ndk\s*=\s*"([^"]+)"'
        if ($ndkVersionLine) { $configuredNdkVersion = $ndkVersionLine.Matches[0].Groups[1].Value }
    }
    if ($configuredNdkVersion) {
        $ndkCandidates += Join-Path (Join-Path $androidSdkRoot "ndk") $configuredNdkVersion
    } else {
        $ndkCandidates += Get-ChildItem -LiteralPath (Join-Path $androidSdkRoot "ndk") -Directory |
            Sort-Object Name -Descending | ForEach-Object FullName
    }
}
$ndkRoot = $ndkCandidates | Where-Object { Test-Path -LiteralPath (Join-Path $_ "source.properties") } | Select-Object -First 1
$ndkRevision = $null
if ($ndkRoot) {
    $ndkRevisionLine = Get-Content -LiteralPath (Join-Path $ndkRoot "source.properties") | Select-String '^Pkg.Revision\s*='
    if ($ndkRevisionLine) { $ndkRevision = ($ndkRevisionLine.Line -split '=', 2)[1].Trim() }
}
$wrapperProperties = Join-Path $projectRoot "gradle\wrapper\gradle-wrapper.properties"
$gradleDistribution = $null
if (Test-Path -LiteralPath $wrapperProperties) {
    $distributionLine = Get-Content -LiteralPath $wrapperProperties | Select-String '^distributionUrl='
    if ($distributionLine) { $gradleDistribution = ($distributionLine.Line -split '=', 2)[1].Trim() }
}
$appGradle = Get-Content -LiteralPath (Join-Path $projectRoot "app\build.gradle.kts") -Raw
$versionCodeMatch = [regex]::Match($appGradle, 'versionCode\s*=\s*(\d+)')
$versionNameMatch = [regex]::Match($appGradle, 'versionName\s*=\s*"([^"]+)"')
$buildRecord.appVersionCode = if ($versionCodeMatch.Success) { $versionCodeMatch.Groups[1].Value } else { $null }
$buildRecord.appVersionName = if ($versionNameMatch.Success) { $versionNameMatch.Groups[1].Value } else { $null }
$buildRecord.toolchain = @{
    javaHome = $env:JAVA_HOME
    javaVersion = $javaVersion
    gradleDistribution = $gradleDistribution
    androidSdkRoot = $androidSdkRoot
    androidNdkRoot = $ndkRoot
    androidNdkRevision = $ndkRevision
    powershellVersion = $PSVersionTable.PSVersion.ToString()
}
$previousPassingRecord = Get-ChildItem -LiteralPath $buildRecordDirectory -Filter "debug-*.json" -File |
    Where-Object { $_.FullName -ne $buildRecordPath } |
    Sort-Object LastWriteTimeUtc -Descending |
    ForEach-Object {
        try { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json } catch { $null }
    } |
    Where-Object { $_ -and $_.status -eq "passed" -and $_.nativeLibraryEntriesByAbi } |
    Select-Object -First 1
if ($previousPassingRecord) {
    $buildRecord.previousPassingBuildRunId = $previousPassingRecord.buildRunId
    $buildRecord.previousApkSha256 = $previousPassingRecord.apkSha256
}
Save-BuildRecord

$gradleArgs = @(
    ":app:assembleDebug",
    "-Pmca.abis=arm64-v8a,x86_64",
    "-PmcaQnnSdkRoot=$qnnSdkRoot",
    "-PmcaQnnRuntimeRoot=$qnnRuntimeRoot",
    "-PmcaQnnTypedBindingsRequired=true",
    # Native CMake configuration embeds the absolute QNN SDK path in the
    # externalNativeBuild variant.  Gradle otherwise considers an older
    # CMake variant up-to-date when the SDK path changes (for example from
    # QAIRT 2.46 on C: to QAIRT 2.45 on D:), and can silently package a JNI
    # library compiled against the wrong headers.  Always rerun configuration
    # for this product build so the typed graph bridge and the staged runtime
    # are built from the SDK selected above.
    "--rerun-tasks",
    "--no-daemon",
    "--console=plain"
)
$buildRecord.gradleArguments = @($gradleArgs)
$buildRecord.qnnRuntimeRoot = (Resolve-Path -LiteralPath $qnnRuntimeRoot).Path
Save-BuildRecord
$gradlewPath = Join-Path $projectRoot "gradlew.bat"
$gradleExecutable = if (Test-Path -LiteralPath $gradlewPath) { $gradlewPath } else { "gradle" }
$vcvars = "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"

Push-Location $projectRoot
try {
    if (Test-Path -LiteralPath $vcvars) {
        $quotedArgs = ($gradleArgs | ForEach-Object {
            if ($_ -match '\s') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
        }) -join " "
        cmd.exe /d /s /c "call `"$vcvars`" >nul && `"$gradleExecutable`" $quotedArgs"
    } else {
        & $gradleExecutable @gradleArgs
    }
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($exitCode -ne 0) {
    Stop-Build "Gradle APK build failed with exit code $exitCode."
}

$apkPath = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path -LiteralPath $apkPath)) {
    Stop-Build "Gradle reported success but the expected APK was not produced: $apkPath"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    $entryNames = @($zip.Entries | ForEach-Object { $_.FullName })
    $requiredEntries = @(
        "lib/arm64-v8a/libmca_qnn_native.so",
        "lib/arm64-v8a/libllama.so",
        "lib/arm64-v8a/libggml.so",
        "lib/arm64-v8a/libggml-opencl.so",
        "lib/arm64-v8a/libllama-common.so",
        "lib/arm64-v8a/libmca_mnn_native.so",
        "lib/arm64-v8a/libMNN_CL.so",
        "lib/arm64-v8a/libMNN_Express.so",
        "lib/arm64-v8a/libmca_sd_native.so",
        "lib/arm64-v8a/libMNN.so",
        "lib/arm64-v8a/libQnnHtpV68.so",
        "lib/arm64-v8a/libQnnHtpV68CalculatorStub.so",
        "lib/arm64-v8a/libQnnHtpV68Skel.so",
        "lib/arm64-v8a/libQnnHtpV68Stub.so",
        "lib/arm64-v8a/libQnnHtpV69.so",
        "lib/arm64-v8a/libQnnHtpV69CalculatorStub.so",
        "lib/arm64-v8a/libQnnHtpV69Skel.so",
        "lib/arm64-v8a/libQnnHtpV69Stub.so",
        "lib/arm64-v8a/libQnnHtpV73.so",
        "lib/arm64-v8a/libQnnHtpV73CalculatorStub.so",
        "lib/arm64-v8a/libQnnHtpV73QemuDriver.so",
        "lib/arm64-v8a/libQnnHtpV73Skel.so",
        "lib/arm64-v8a/libQnnHtpV73Stub.so",
        "lib/arm64-v8a/libQnnHtpV75.so",
        "lib/arm64-v8a/libQnnHtpV75CalculatorStub.so",
        "lib/arm64-v8a/libQnnHtpV75Skel.so",
        "lib/arm64-v8a/libQnnHtpV75Stub.so",
        "lib/arm64-v8a/libQnnSystem.so",
        "lib/arm64-v8a/libQnnHtp.so",
        "lib/arm64-v8a/libQnnHtpV79.so",
        "lib/arm64-v8a/libQnnHtpV79CalculatorStub.so",
        "lib/arm64-v8a/libQnnHtpV79Skel.so",
        "lib/arm64-v8a/libQnnHtpV79Stub.so",
        "lib/arm64-v8a/libQnnHtpV81.so",
        "lib/arm64-v8a/libQnnHtpV81CalculatorStub.so",
        "lib/arm64-v8a/libQnnHtpV81Skel.so",
        "lib/arm64-v8a/libQnnHtpV81Stub.so",
        "lib/x86_64/libmca_native.so",
        "lib/x86_64/libmca_qnn_native.so",
        "lib/x86_64/libllama.so",
        "lib/x86_64/libggml.so",
        "lib/x86_64/libggml-base.so",
        "lib/x86_64/libllama-common.so",
        "lib/x86_64/libmca_mnn_native.so",
        "lib/x86_64/libmca_sd_native.so",
        "lib/x86_64/liblitertlm_jni.so",
        "lib/x86_64/libmca_prompt_handoff.so",
        "assets/litert-qualcomm/v73/arm64-v8a/libLiteRtDispatch_Qualcomm.so",
        "assets/litert-qualcomm/v73/arm64-v8a/libQnnHtp.so",
        "assets/litert-qualcomm/v73/arm64-v8a/libQnnHtpV73Skel.so",
        "assets/litert-qualcomm/v73/arm64-v8a/libQnnHtpV73Stub.so",
        "assets/litert-qualcomm/v73/arm64-v8a/libQnnSystem.so",
        "assets/litert-qualcomm/v75/arm64-v8a/libLiteRtDispatch_Qualcomm.so",
        "assets/litert-qualcomm/v75/arm64-v8a/libQnnHtp.so",
        "assets/litert-qualcomm/v75/arm64-v8a/libQnnHtpV75Skel.so",
        "assets/litert-qualcomm/v75/arm64-v8a/libQnnHtpV75Stub.so",
        "assets/litert-qualcomm/v75/arm64-v8a/libQnnSystem.so",
        "assets/litert-qualcomm/v79/arm64-v8a/libQnnHtpV79Skel.so",
        "assets/litert-qualcomm/v79/arm64-v8a/libLiteRtDispatch_Qualcomm.so",
        "assets/litert-qualcomm/v79/arm64-v8a/libQnnHtp.so",
        "assets/litert-qualcomm/v79/arm64-v8a/libQnnHtpV79Stub.so",
        "assets/litert-qualcomm/v79/arm64-v8a/libQnnSystem.so",
        "assets/litert-qualcomm/arm64-v8a/libLiteRtDispatch_Qualcomm.so",
        "assets/litert-qualcomm/arm64-v8a/libQnnHtp.so",
        "assets/litert-qualcomm/arm64-v8a/libQnnHtpV81Skel.so",
        "assets/litert-qualcomm/arm64-v8a/libQnnHtpV81Stub.so",
        "assets/litert-qualcomm/arm64-v8a/libQnnSystem.so",
        "assets/qwen-image-2.1/arm64-v8a/runtime-manifest.json",
        "assets/qwen-image-2.1/arm64-v8a/libc++_shared.so",
        "assets/qwen-image-2.1/arm64-v8a/libmca_qwenimage21_mnn.so",
        "assets/qwen-image-2.1/arm64-v8a/libqwenimage21_jni.so"
    )
    foreach ($requiredEntry in $requiredEntries) {
        if ($entryNames -notcontains $requiredEntry) {
            $buildRecord.missingEntries += $requiredEntry
        } else {
            $buildRecord.presentEntries += $requiredEntry
        }
    }

    $qnnNativeEntry = $zip.GetEntry("lib/arm64-v8a/libmca_qnn_native.so")
    $qnnNativeStream = $qnnNativeEntry.Open()
    $qnnNativeBuffer = [System.IO.MemoryStream]::new()
    try {
        $qnnNativeStream.CopyTo($qnnNativeBuffer)
        $qnnNativeText = [System.Text.Encoding]::GetEncoding(28591).GetString($qnnNativeBuffer.ToArray())
    } finally {
        $qnnNativeStream.Dispose()
        $qnnNativeBuffer.Dispose()
    }
    if ($qnnNativeText.Contains("QNN typed graph bindings are unavailable in this APK") -or
        $qnnNativeText.Contains("QNN SDK headers were not available at build time.")) {
        $buildRecord.validationErrors += "libmca_qnn_native.so is the headerless QNN stub, not the typed graph runner."
    }
    $buildRecord.qnnTypedBindingsStubMarkerAbsent =
        -not $qnnNativeText.Contains("QNN typed graph bindings are unavailable in this APK") -and
        -not $qnnNativeText.Contains("QNN SDK headers were not available at build time.")
    $buildRecord.qnnTypedBindingsEvidence = "Gradle verifyMcaQnnSdkHeaders passed; QNN headerless-stub marker absent from packaged JNI library (static heuristic, not a device runtime test)."

    $arm64Count = @($entryNames | Where-Object { $_ -like "lib/arm64-v8a/*" }).Count
    $x86Count = @($entryNames | Where-Object { $_ -like "lib/x86_64/*" }).Count
    $buildRecord.abiLibraryCounts = @{ "arm64-v8a" = $arm64Count; "x86_64" = $x86Count }
    $buildRecord.nativeLibraryEntriesByAbi = @{
        "arm64-v8a" = @($entryNames | Where-Object { $_ -like "lib/arm64-v8a/*" } | Sort-Object)
        "x86_64" = @($entryNames | Where-Object { $_ -like "lib/x86_64/*" } | Sort-Object)
    }
    $buildRecord.assetEntries = @($entryNames | Where-Object { $_ -like "assets/litert-qualcomm/*" -or $_ -like "assets/qwen-image-2.1/*" } | Sort-Object)
    if ($previousPassingRecord -and $previousPassingRecord.nativeLibraryEntriesByAbi) {
        $previousLibs = @($previousPassingRecord.nativeLibraryEntriesByAbi.'arm64-v8a') + @($previousPassingRecord.nativeLibraryEntriesByAbi.x86_64)
        $currentLibs = @($buildRecord.nativeLibraryEntriesByAbi.'arm64-v8a') + @($buildRecord.nativeLibraryEntriesByAbi.x86_64)
        $buildRecord.addedNativeEntriesSincePrevious = @($currentLibs | Where-Object { $_ -notin $previousLibs } | Sort-Object -Unique)
        $buildRecord.removedNativeEntriesSincePrevious = @($previousLibs | Where-Object { $_ -notin $currentLibs } | Sort-Object -Unique)
    }
    if ($arm64Count -lt 94) { $buildRecord.validationErrors += "arm64-v8a library count is below the complete-package baseline (actual=$arm64Count, minimum=94)." }
    if ($x86Count -lt 26) { $buildRecord.validationErrors += "x86_64 library count is below the complete-package baseline (actual=$x86Count, minimum=26)." }
} finally {
    $zip.Dispose()
}

$apkInfo = Get-Item -LiteralPath $apkPath
$apkHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$buildRecord.expectedEntries = @($requiredEntries)
$buildRecord.apkPath = $apkPath
$buildRecord.apkBytes = $apkInfo.Length
$buildRecord.apkLastWriteTimeUtc = $apkInfo.LastWriteTimeUtc.ToString("o")
$buildRecord.apkSha256 = $apkHash
if ($buildRecord.missingEntries.Count -gt 0 -or $buildRecord.validationErrors.Count -gt 0) {
    $buildRecord.status = "validation_failed"
    $buildRecord.failure = "APK completeness validation failed."
    $buildRecord.completedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    Save-BuildRecord
    Stop-Build "APK completeness validation failed. Missing: $($buildRecord.missingEntries -join ', '); Errors: $($buildRecord.validationErrors -join '; '). See $buildRecordPath"
}
$buildRecord.status = "passed"
$buildRecord.completedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
Save-BuildRecord
Write-Host "Validated APK: $apkPath"
Write-Host "Size: $($apkInfo.Length) bytes"
Write-Host "SHA-256: $apkHash"
Write-Host "Build record: $buildRecordPath"
} catch {
    if ($buildRecord.status -eq "started") { $buildRecord.status = "failed" }
    if ([string]::IsNullOrWhiteSpace([string]$buildRecord.failure)) {
        $buildRecord.failure = $_.Exception.Message
    }
    $buildRecord.completedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    try { Save-BuildRecord } catch { Write-Warning "Could not write build record at $buildRecordPath : $($_.Exception.Message)" }
    throw
}
