# PROCESS-SPEEDUP-SPEC-20261012 section 3.3: -Incremental omits --rerun-tasks so Gradle can reuse
# up-to-date outputs; full runs stay the default and are required every 4th train / on SDK-or-ABI change.
param([switch]$Incremental)

$ErrorActionPreference = "Stop"

# Resolve the repository root from this script so the release recipe is stable when it is
# invoked from a CI runner or another working directory.
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

# --- Exclusive build slot (T24) -------------------------------------------------------------
# Take the shared build lock BEFORE anything else, and refuse to start when it cannot be taken.
# Exit code 3 means "another participant holds / is occupying the build slot"; it is deliberately
# distinct from a compile/test failure so callers can tell the two apart.
#
# Honest scope: this mutex holds only when every participant uses the lock. A builder that never
# calls Enter-McaBuildLock still bypasses it - this does NOT globally prevent concurrent builds.
# What it adds is a runtime occupancy probe: if compiler/link processes are running while NOBODY
# holds the lock, the build is refused rather than silently competing with them.
$mcaLockModule = Join-Path $projectRoot 'tools\build-guards\McaBuildLock.psm1'
$script:McaBuildLockHandle = $null
if (Test-Path -LiteralPath $mcaLockModule) {
    Import-Module $mcaLockModule -Force
    try {
        Assert-McaBuildSlot | Out-Null
    } catch {
        Write-Host ("BUILD REFUSED (exit 3): " + $_.Exception.Message) -ForegroundColor Red
        exit 3
    }
    try {
        $script:McaBuildLockHandle = Enter-McaBuildLock -Kind build -CommandLine ("build.ps1 " + $MyInvocation.Line)
    } catch {
        Write-Host ("BUILD REFUSED (exit 3): " + $_.Exception.Message) -ForegroundColor Red
        exit 3
    }
    Write-Host ("build slot acquired: $($script:McaBuildLockHandle.path) (pid $PID)")
    # Release on every exit path, including an early `exit` further down this script.
    Register-EngineEvent -SourceIdentifier PowerShell.Exiting -SupportEvent -Action {
        if ($script:McaBuildLockHandle) { Exit-McaBuildLock -Lock $script:McaBuildLockHandle }
    } | Out-Null
} else {
    Write-Host ("BUILD REFUSED (exit 3): build-guard lock module not found at " + $mcaLockModule + " (fail-closed).") -ForegroundColor Red
    exit 3
}
# --------------------------------------------------------------------------------------------

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
    sourceProvenance = $null
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
    validationDiagnostics = @()
    abiLibraryCounts = @{}
    nativeLibraryEntriesByAbi = @{}
    nativeArtifactManifest = @()
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
. (Join-Path $PSScriptRoot "BuildProvenance.ps1")
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
# Split-Path -Parent yields an empty string once it reaches a drive root, and Join-Path
# rejects an empty -Path, so the three archive lookups are built from a bounded ancestor
# walk that stops when there is no parent left. At the usual depths this produces exactly
# the same three directories (parent, grandparent, great-grandparent) as before; it only
# stops early for a runtime root sitting close to the drive root, which previously threw.
$zipSearchRoots = New-Object System.Collections.Generic.List[string]
$zipSearchCursor = $sdkParent
while (-not [string]::IsNullOrWhiteSpace($zipSearchCursor) -and $zipSearchRoots.Count -lt 3) {
    $zipSearchRoots.Add($zipSearchCursor)
    $zipSearchCursor = Split-Path -Parent $zipSearchCursor
}
$zipCandidates = @(
    $zipSearchRoots | ForEach-Object { Join-Path $_ "v$sdkLeaf.zip" }
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
    "$PSScriptRoot\..\toolchains\jdk-21",
    "$env:USERPROFILE\.jdks\ms-21",
    "$env:USERPROFILE\.jdks\openjdk-21",
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
    # Native commands write their diagnostics to stderr, and with
    # $ErrorActionPreference = "Stop" Windows PowerShell 5.1 turns that output into a
    # terminating NativeCommandError -- `java -version` below fails every time on 5.1
    # because its version banner is a stderr write. Lower the preference around the two
    # native captures, the same way tools/benchmarks/run-real-device-comparison.ps1 does.
    $nativeCapturePreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $gitStatusLines = & git -C $projectRoot status --short 2>&1
        $gitStatusExitCode = $LASTEXITCODE
        $javaVersion = $null
        if (Get-Command java -ErrorAction SilentlyContinue) {
            $javaVersion = (& java -version 2>&1 | Out-String).Trim()
        }
    } finally {
        $ErrorActionPreference = $nativeCapturePreference
    }
    $buildRecord.sourceTreeStatus = if ($gitStatusExitCode -eq 0) { @($gitStatusLines) } else { "unavailable: git status failed: $($gitStatusLines -join ' ')" }
} else {
    $buildRecord.sourceRevision = "unavailable: git rev-parse failed"
    $buildRecord.sourceTreeStatus = "unavailable: repository metadata could not be read"
}
$buildRecord.sourceProvenance = Get-McaSourceProvenance $projectRoot

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
    "-Pkotlin.compiler.execution.strategy=in-process",
    "--max-workers=2",
    # APK signing uses the JVM common pool, independently of Gradle workers.
    "-Djava.util.concurrent.ForkJoinPool.common.parallelism=2",
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
if ($Incremental) { $gradleArgs = @($gradleArgs | Where-Object { $_ -ne '--rerun-tasks' }) }
$buildRecord.gradleArguments = @($gradleArgs)
$buildRecord.qnnRuntimeRoot = (Resolve-Path -LiteralPath $qnnRuntimeRoot).Path
Save-BuildRecord

# --- Process-side memory monitoring (approved 2026-10-10) ------------------------------------
# A start-of-build threshold only guards against STARTING too low; it does not guard against a
# plunge DURING the build. Measured: a build started at 3246 MB free and dipped to 1060 MB - far
# below the 2600 MB exception floor - yet finished without OOM. That was luck, not margin.
# The sampler RECORDS and WARNS only. It must never abort: stopping mid-build risks a more
# inconsistent state than finishing, the worktree is already covered by the stability guard, and a
# real OOM surfaces as hs_err.
$minFreeOut = Join-Path $buildRecordDirectory ".minfree-$buildRunId.txt"
$minFreeStop = Join-Path $buildRecordDirectory ".minfree-$buildRunId.stop"
$minFreeSampler = Join-Path $projectRoot 'tools\build-guards\Measure-McaMinFree.ps1'
$minFreePid = $null
if (Test-Path -LiteralPath $minFreeSampler) {
    Remove-Item -LiteralPath $minFreeOut -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $minFreeStop -Force -ErrorAction SilentlyContinue
    $samplerCmd = "powershell -NoProfile -ExecutionPolicy Bypass -File `"$minFreeSampler`" -OutFile `"$minFreeOut`" -StopFile `"$minFreeStop`""
    try {
        $sp = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $samplerCmd }
        if ($sp.ReturnValue -eq 0) { $minFreePid = $sp.ProcessId }
    } catch { }
}

$gradlewPath = Join-Path $projectRoot "gradlew.bat"
$gradleExecutable = if (Test-Path -LiteralPath $gradlewPath) { $gradlewPath } else { "gradle" }
$vcvars = "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"

Push-Location $projectRoot
try {
    if (Test-Path -LiteralPath $vcvars) {
        $quotedArgs = ($gradleArgs | ForEach-Object {
            if ($_ -match '\s') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
        }) -join " "
        # Gradle writes progress/warnings to stderr (and `call vcvars64.bat >nul` can too).
        # With $ErrorActionPreference = "Stop", Windows PowerShell 5.1 turns any stderr write
        # from cmd.exe into a terminating NativeCommandError, so a fully successful Gradle run
        # was being reported as a build failure with an empty *failure* field and no
        # "What went wrong" block. Only the process exit code may decide success here.
        $gradleNativePreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            cmd.exe /d /s /c "call `"$vcvars`" >nul && `"$gradleExecutable`" $quotedArgs"
            $exitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $gradleNativePreference
        }
    } else {
        $gradleNativePreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & $gradleExecutable @gradleArgs
            $exitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $gradleNativePreference
        }
    }
} finally {
    Pop-Location
}

if ($exitCode -ne 0) {
    Stop-Build "Gradle APK build failed with exit code $exitCode."
}
$sourceAfterBuild = Get-McaSourceProvenance $projectRoot
if ($sourceAfterBuild.worktreeSha256 -ne $buildRecord.sourceProvenance.worktreeSha256 -or
    ($sourceAfterBuild.submodules | ConvertTo-Json -Depth 6 -Compress) -ne
    ($buildRecord.sourceProvenance.submodules | ConvertTo-Json -Depth 6 -Compress)) {
    Stop-Build "Source or submodule inputs changed during this APK build; rerun from a stable worktree."
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
        "assets/dsh/MANIFEST.json",
        "assets/dsh/payload.zip",
        "lib/arm64-v8a/libdshnode.so",
        "lib/arm64-v8a/libdshrg.so",
        "lib/arm64-v8a/libdshcurl.so",
        "lib/arm64-v8a/libdshgit.so",
        "lib/arm64-v8a/libdshgitremotehttp.so",
        "lib/arm64-v8a/libdshgitremotehttps.so",
        "lib/arm64-v8a/libdshpython.so",
        "lib/x86_64/libmca_native.so",
        "lib/x86_64/libmca_qnn_native.so",
        "lib/x86_64/libmca_llama.so",
        "lib/x86_64/libmca_ggml.so",
        "lib/x86_64/libmca_ggml-base.so",
        "lib/x86_64/libmca_llama-common.so",
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
    if ($null -eq $qnnNativeEntry) {
        $buildRecord.missingEntries += "lib/arm64-v8a/libmca_qnn_native.so (QNN diagnostic unavailable because the entry is missing)"
        $buildRecord.qnnTypedBindingsStubMarkerAbsent = $false
        $buildRecord.qnnTypedBindingsEvidence = "not_run_missing_entry"
    } else {
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
    }

    $arm64Count = @($entryNames | Where-Object { $_ -like "lib/arm64-v8a/*" }).Count
    $x86Count = @($entryNames | Where-Object { $_ -like "lib/x86_64/*" }).Count
    $buildRecord.abiLibraryCounts = @{ "arm64-v8a" = $arm64Count; "x86_64" = $x86Count }
    $buildRecord.nativeLibraryEntriesByAbi = @{
        "arm64-v8a" = @($entryNames | Where-Object { $_ -like "lib/arm64-v8a/*" } | Sort-Object)
        "x86_64" = @($entryNames | Where-Object { $_ -like "lib/x86_64/*" } | Sort-Object)
    }
    $readElf = $null
    foreach ($candidate in @('llvm-readelf', 'readelf')) {
        $command = Get-Command $candidate -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($command) { $readElf = $command.Source; break }
    }
    if (-not $readElf -and $ndkRoot) {
        $ndkReadElfCandidates = @(
            (Join-Path $ndkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe'),
            (Join-Path $ndkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf')
        )
        $readElf = $ndkReadElfCandidates |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
            Select-Object -First 1
    }
    $apkNativeArtifacts = @(Get-McaApkNativeManifest $zip $readElf)
    $dshPayload = Get-McaDshPayloadNativeManifest $zip $readElf
    $dshManifestEntry = $zip.GetEntry("assets/dsh/MANIFEST.json")
    if ($dshManifestEntry) {
        $manifestStream = $dshManifestEntry.Open()
        $manifestReader = [IO.StreamReader]::new($manifestStream)
        try { $dshManifest = $manifestReader.ReadToEnd() | ConvertFrom-Json } finally {
            $manifestReader.Dispose()
            $manifestStream.Dispose()
        }
        if (-not $dshPayload -or [int64]$dshManifest.archive.size -ne [int64]$dshPayload.size -or
            [string]$dshManifest.archive.sha256 -ne [string]$dshPayload.sha256) {
            $buildRecord.validationErrors += 'DSH MANIFEST.json payload size/SHA-256 does not match assets/dsh/payload.zip.'
        }
        $nodeArtifact = @($apkNativeArtifacts | Where-Object { $_.path -eq 'lib/arm64-v8a/libdshnode.so' }) | Select-Object -First 1
        if (-not $nodeArtifact -or [int64]$dshManifest.node.size -ne [int64]$nodeArtifact.bytes -or
            [string]$dshManifest.node.sha256 -ne [string]$nodeArtifact.sha256) {
            $buildRecord.validationErrors += 'DSH MANIFEST.json Node size/SHA-256 does not match lib/arm64-v8a/libdshnode.so.'
        }
    }
    $buildRecord.nativeArtifactManifest = @(
        $apkNativeArtifacts + @($dshPayload.artifacts)
    )
    $androidSystemLibraries = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    @(
        'libc.so', 'libdl.so', 'libm.so', 'liblog.so', 'libandroid.so',
        'libjnigraphics.so', 'libz.so', 'libEGL.so', 'libGLESv2.so',
        'libGLESv3.so', 'libvulkan.so', 'libOpenSLES.so', 'libaaudio.so',
        'libmediandk.so', 'libnativewindow.so', 'libcamera2ndk.so',
        'libbinder_ndk.so', 'libneuralnetworks.so', 'libatomic.so'
    ) | ForEach-Object { [void]$androidSystemLibraries.Add($_) }
    $dspRuntimeLibraries = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    @('libc.so', 'libgcc.so', 'libc++.so.1', 'libc++abi.so.1') |
        ForEach-Object { [void]$dspRuntimeLibraries.Add($_) }
    $vendorDeviceLibraries = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    @('libcdsprpc.so', 'libOpenCL.so') |
        ForEach-Object { [void]$vendorDeviceLibraries.Add($_) }
    $dshRuntimeLibraries = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($artifact in $buildRecord.nativeArtifactManifest) {
        if ($artifact.path -match '^dsh-payload/arm64-v8a/runtime/lib/') {
            [void]$dshRuntimeLibraries.Add(($artifact.path -split '/')[-1])
            if ($artifact.soname) { [void]$dshRuntimeLibraries.Add([string]$artifact.soname) }
        }
    }
    foreach ($artifact in $buildRecord.nativeArtifactManifest) {
        if ($artifact.path -match '^lib/(arm64-v8a|x86_64)/' -or
            $artifact.path -match '^dsh-payload/arm64-v8a/runtime/lib/') {
            $isDsp = Test-McaDspArtifactPath $artifact.path
            if (-not $artifact.elf) {
                $buildRecord.validationErrors += "$($artifact.path) is not an ELF library."
            } elseif ($artifact.machine -ne $artifact.expectedMachine) {
                $buildRecord.validationErrors += "$($artifact.path) has ELF machine $($artifact.machine), expected $($artifact.expectedMachine)."
            }
            if ($artifact.dependencyStatus -ne 'read') {
                $buildRecord.validationErrors += "$($artifact.path) ELF dependency inspection did not succeed ($($artifact.dependencyStatus))."
                continue
            }
            $abi = if ($artifact.path.Contains('/arm64-v8a/')) { 'arm64-v8a' } else { 'x86_64' }
            $directory = $artifact.path.Substring(0, $artifact.path.LastIndexOf('/'))
            $available = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
            foreach ($candidate in $buildRecord.nativeArtifactManifest) {
                $candidateDirectory = $candidate.path.Substring(0, $candidate.path.LastIndexOf('/'))
                if ($candidateDirectory -eq $directory -or $candidateDirectory -eq "lib/$abi") {
                    [void]$available.Add(($candidate.path -split '/')[-1])
                    if ($candidate.soname) { [void]$available.Add([string]$candidate.soname) }
                }
            }
            if ($artifact.path -match '^lib/arm64-v8a/libdsh[^/]*\.so$') {
                foreach ($name in $dshRuntimeLibraries) { [void]$available.Add($name) }
            }
            foreach ($needed in $artifact.needed) {
                if ($available.Contains([string]$needed)) { continue }
                if ($isDsp -and $dspRuntimeLibraries.Contains([string]$needed)) {
                    $buildRecord.validationDiagnostics += "$($artifact.path) requires device DSP runtime library $needed."
                    continue
                }
                if (-not $isDsp -and $androidSystemLibraries.Contains([string]$needed)) { continue }
                if (-not $isDsp -and $abi -eq 'arm64-v8a' -and
                    $vendorDeviceLibraries.Contains([string]$needed)) {
                    $buildRecord.validationDiagnostics += "$($artifact.path) requires device vendor library $needed."
                    continue
                }
                $buildRecord.validationErrors += "$($artifact.path) requires $needed, absent from its packaged ABI/asset directory and declared platform runtime set."
            }
        }
    }
    $buildRecord.assetEntries = @($entryNames | Where-Object { $_ -like "assets/litert-qualcomm/*" -or $_ -like "assets/qwen-image-2.1/*" } | Sort-Object)
    if ($previousPassingRecord -and $previousPassingRecord.nativeLibraryEntriesByAbi) {
        $previousLibs = @($previousPassingRecord.nativeLibraryEntriesByAbi.'arm64-v8a') + @($previousPassingRecord.nativeLibraryEntriesByAbi.x86_64)
        $currentLibs = @($buildRecord.nativeLibraryEntriesByAbi.'arm64-v8a') + @($buildRecord.nativeLibraryEntriesByAbi.x86_64)
        $buildRecord.addedNativeEntriesSincePrevious = @($currentLibs | Where-Object { $_ -notin $previousLibs } | Sort-Object -Unique)
        $buildRecord.removedNativeEntriesSincePrevious = @($previousLibs | Where-Object { $_ -notin $currentLibs } | Sort-Object -Unique)
    }
    if ($arm64Count -lt 94) {
        $buildRecord.validationDiagnostics += "arm64-v8a library count is below the historical package baseline (actual=$arm64Count, baseline=94); required-entry and ELF checks remain authoritative."
    }
    if ($x86Count -lt 26) {
        $buildRecord.validationDiagnostics += "x86_64 library count is below the historical package baseline (actual=$x86Count, baseline=26); required-entry and ELF checks remain authoritative."
    }
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
} finally {
    # Release the build slot on EVERY path: normal completion, an early `exit`, or the `throw`
    # above. The previous attempt used a Register-EngineEvent PowerShell.Exiting handler, which
    # runs asynchronously in a separate runspace and did NOT run before the host tore down - the
    # 2026-10-10 08:45 build left `.mca-exclusive-build.lock` on disk with its own (by then dead)
    # pid inside, and a teammate read that and concluded a build was still running. `finally` runs
    # for `exit` as well as for `throw`, so this path cannot be skipped.
    # (A hard kill, SIGKILL-equivalent, cannot run any cleanup at all: the lock file then remains
    #  and must be read as "stale if the recorded pid is dead".)
    if ($script:McaBuildLockHandle) {
        try { Exit-McaBuildLock -Lock $script:McaBuildLockHandle | Out-Null } catch { }
        $script:McaBuildLockHandle = $null
    }

    # Stop the memory sampler and fold its result into the record before the script ends.
    if ($minFreePid) {
        try { [System.IO.File]::WriteAllText($minFreeStop, 'stop'); Start-Sleep -Seconds 3 } catch { }
        try {
            if (Test-Path -LiteralPath $minFreeOut) {
                $state = (Get-Content -LiteralPath $minFreeOut -Raw).Trim()
                $kv = @{}
                foreach ($pair in ($state -split '\s+')) {
                    $p = $pair -split '=', 2
                    if ($p.Count -eq 2) { $kv[$p[0]] = $p[1] }
                }
                $minMb = [int]$kv['minMB']
                $warnHits = [int]$kv['warnHits']
                $alertHits = [int]$kv['alertHits']
                $buildRecord.minFreePhysicalMB = $minMb
                $buildRecord.minFreeSamples = [int]$kv['samples']
                $buildRecord.minFreeWarnHits = $warnHits
                $buildRecord.minFreeAlertHits = $alertHits
                if ($alertHits -gt 0) {
                    Write-Host ("ALERT: free physical memory fell below 1000 MB during this build (min ${minMb} MB, ${alertHits} sample(s)). The build was NOT aborted by design; report this for scheduling.") -ForegroundColor Red
                } elseif ($warnHits -gt 0) {
                    Write-Host ("WARNING: free physical memory fell below 1500 MB during this build (min ${minMb} MB, ${warnHits} sample(s)). Recorded, not aborted.") -ForegroundColor Yellow
                } else {
                    Write-Host ("memory: min free ${minMb} MB during the build (no threshold hit)")
                }
                try { Save-BuildRecord } catch { }
            }
        } catch { }
        try { Remove-Item -LiteralPath $minFreeStop -Force -ErrorAction SilentlyContinue } catch { }
    }
}
