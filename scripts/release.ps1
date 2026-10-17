# MCA signed arm64 Release builder + immutable provenance/evidence record.
[CmdletBinding()]
param(
    [string]$OutRoot = 'artifacts/releases/rc',
    [switch]$SkipBuild,
    [switch]$ReproCheck,
    [switch]$Optimize
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$apkPath = Join-Path $repo 'app\build\outputs\apk\release\app-release.apk'
$recordRoot = Join-Path $repo 'artifacts\release-build-records'
$outDir = Join-Path $repo $OutRoot
$runId = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss-fffZ')
$recordPath = Join-Path $recordRoot "release-$runId.json"
$logPath = Join-Path $outDir "release-$runId.gradle.out.log"
$errPath = Join-Path $outDir "release-$runId.gradle.err.log"
New-Item -ItemType Directory -Force -Path $recordRoot,$outDir | Out-Null

function Write-Lf([string]$Path, [string]$Text) {
    $dir = Split-Path -Parent $Path
    if ($dir) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [IO.File]::WriteAllText($Path, (($Text -replace "`r`n", "`n") -replace "`r", "`n"), [Text.UTF8Encoding]::new($false))
}
function Json-Lf($Value) {
    return ((($Value | ConvertTo-Json -Depth 80) -replace "`r`n", "`n") + "`n")
}
function Get-JavaVersionLine {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { return (& (Join-Path $env:JAVA_HOME 'bin\java.exe') -version 2>&1 | Select-Object -First 1 | Out-String).Trim() }
    finally { $ErrorActionPreference = $previous }
}
function Sha([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Get-BuildTool([string]$Name) {
    $candidates = Get-ChildItem -LiteralPath (Join-Path $env:ANDROID_HOME 'build-tools') -Recurse -Filter $Name -ErrorAction SilentlyContinue | Sort-Object FullName
    $tool = $candidates | Select-Object -Last 1 -ExpandProperty FullName
    if (-not $tool) { throw "Android build tool not found: $Name" }
    return $tool
}
function Read-SigningProperties([string]$Path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#') -or -not $trimmed.Contains('=')) { continue }
        $parts = $trimmed.Split('=', 2)
        $values[$parts[0].Trim()] = $parts[1].Trim()
    }
    return $values
}
function Optimize-And-SignReleaseApk([string]$InputApk, [string]$RecordRoot, [string]$RunId) {
    $work = Join-Path $RecordRoot "optimize-$RunId"
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    $unsigned = Join-Path $work 'app-release.unsigned.apk'
    $symbols = Join-Path $work 'native-debug-symbols.zip'
    $aligned = Join-Path $work 'app-release.aligned.apk'
    $signed = Join-Path $work 'app-release.signed.apk'
    $optimizer = Join-Path $repo 'tools\release\optimize_apk_native_symbols.py'
    $env:PYTHONDONTWRITEBYTECODE = '1'
    & python -B $optimizer --input-apk $InputApk --output-apk $unsigned --symbols-zip $symbols 2> (Join-Path $work 'optimize.err.log') 1> (Join-Path $work 'optimize.out.log')
    if ($LASTEXITCODE -ne 0) { throw "Native symbol externalization failed. See $work" }
    $zipalign = Get-BuildTool 'zipalign.exe'
    & $zipalign -P 16 -f 4 $unsigned $aligned 2> (Join-Path $work 'zipalign.err.log') 1> (Join-Path $work 'zipalign.out.log')
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed. See $work" }
    $signingPath = Join-Path $repo 'signing.properties'
    if (-not (Test-Path -LiteralPath $signingPath)) { throw 'signing.properties is required to re-sign the optimized APK.' }
    $signing = Read-SigningProperties $signingPath
    $storeFile = $signing['storeFile']
    if (-not [IO.Path]::IsPathRooted($storeFile)) { $storeFile = Join-Path $repo $storeFile }
    if (-not (Test-Path -LiteralPath $storeFile)) { throw "Release keystore not found: $storeFile" }
    $ksPassFile = Join-Path $work 'ks-pass.txt'
    $keyPassFile = Join-Path $work 'key-pass.txt'
    [IO.File]::WriteAllText($ksPassFile, $signing['storePassword'], [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText($keyPassFile, $signing['keyPassword'], [Text.UTF8Encoding]::new($false))
    $apksigner = Get-BuildTool 'apksigner.bat'
    try {
        & $apksigner sign --ks $storeFile --ks-pass "file:$ksPassFile" --ks-key-alias $signing['keyAlias'] --key-pass "file:$keyPassFile" --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false --out $signed $aligned 2> (Join-Path $work 'sign.err.log') 1> (Join-Path $work 'sign.out.log')
        if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed. See $work" }
    } finally {
        if (Test-Path -LiteralPath $ksPassFile) { [IO.File]::Delete($ksPassFile) }
        if (Test-Path -LiteralPath $keyPassFile) { [IO.File]::Delete($keyPassFile) }
    }
    return [ordered]@{
        unsignedApk = $unsigned
        symbolsZip = $symbols
        symbolsSha256 = Sha $symbols
        symbolsBytes = (Get-Item -LiteralPath $symbols).Length
        alignedApk = $aligned
        signedApk = $signed
        signedApkSha256 = Sha $signed
        signedApkBytes = (Get-Item -LiteralPath $signed).Length
        workDir = $work
    }
}
function Clear-OutputDir([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    $root = [IO.Path]::GetFullPath($repo).TrimEnd('\') + '\'
    if (-not $full.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { throw "Unsafe output path: $full" }
    if ([IO.Directory]::Exists($full)) { [IO.Directory]::Delete($full, $true) }
}
function Wait-MemoryFloor([int]$FloorMb = 2500, [int]$Tries = 120) {
    for ($i = 0; $i -lt $Tries; $i++) {
        $free = [int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024)
        if ($free -ge $FloorMb) { return $free }
        Start-Sleep -Seconds 5
    }
    throw "Free physical memory never reached ${FloorMb}MB."
}

$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'E:\model\toolchains\jdk-21\jdk-21.0.12.1+1' }
$env:MCA_QNN_SDK_ROOT = if ($env:MCA_QNN_SDK_ROOT) { $env:MCA_QNN_SDK_ROOT } else { 'E:\tools\qairt-sdk\2.45.0.260326' }
$env:ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'E:\model\android-sdk' }
if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { throw "Invalid JAVA_HOME: $env:JAVA_HOME" }
if (-not (Test-Path -LiteralPath $env:MCA_QNN_SDK_ROOT)) { throw "Missing MCA_QNN_SDK_ROOT: $env:MCA_QNN_SDK_ROOT" }

. (Join-Path $PSScriptRoot 'BuildProvenance.ps1')
. (Join-Path $repo 'tools\build-guards\McaWorktreeFingerprint.ps1')
Import-Module (Join-Path $repo 'tools\build-guards\McaBuildLock.psm1') -Force
$provenance = Get-McaSourceProvenance -ProjectRoot $repo
$versionText = Get-Content -LiteralPath (Join-Path $repo 'app\build.gradle.kts') -Raw
$versionName = [regex]::Match($versionText, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$versionCode = [regex]::Match($versionText, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if (-not $versionName -or -not $versionCode) { throw 'Cannot read app versionName/versionCode.' }

$record = [ordered]@{
    schema = 'mca.release-build-record/1'
    runId = $runId
    status = 'started'
    variant = 'release'
    abi = 'arm64-v8a'
    startedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    completedAtUtc = $null
    app = [ordered]@{ versionName = $versionName; versionCode = [int]$versionCode; packageName = 'com.muyuchat.mca' }
    source = [ordered]@{
        head = $provenance.head
        worktreeSha256 = $provenance.worktreeSha256
        trackedDiffSha256 = $provenance.trackedDiffSha256
        fileCount = @($provenance.manifest).Count
        submodules = @($provenance.submodules)
        localPolicyGuards = @($provenance.localPolicyGuards)
    }
    toolchain = [ordered]@{
        javaHome = $env:JAVA_HOME
        javaVersion = (Get-JavaVersionLine)
        qnnSdkRoot = $env:MCA_QNN_SDK_ROOT
        androidSdkRoot = $env:ANDROID_HOME
    }
    build = [ordered]@{
        abiProfile = 'arm64-v8a'
        minifyEnabled = $true
        shrinkResources = $true
        keepNativeDebugSymbols = $false
        reproducibleCheck = [bool]$ReproCheck
        gradleArgs = @()
        memoryFloorMb = 2500
    }
    artifacts = [ordered]@{ apk = $null; mapping = $null; nativeDebugSymbols = $null; checksum = $null; pageSizeReport = $null; sbom = $null }
    verification = [ordered]@{}
    failure = $null
}

$lock = $null
try {
    if (-not $SkipBuild) {
        & (Join-Path $repo 'gradlew.bat') --stop 2>&1 | Out-Null
        $free = Wait-MemoryFloor
        $record.build.freePhysicalMbBefore = $free
        $lock = Enter-McaBuildLock -Kind build -RetrySeconds 3600 -RetryIntervalSeconds 5 -CommandLine 'scripts/release.ps1 signed arm64 R8 build'
        Clear-OutputDir (Join-Path $repo 'app\build\outputs\apk\release')
        Clear-OutputDir (Join-Path $repo 'app\build\intermediates\packaged_manifests\release')
        $args = @(
            ':app:assembleRelease',
            '-Pmca.abis=arm64-v8a',
            '-Pmca.keepNativeSymbols=false',
            "-PmcaQnnSdkRoot=$env:MCA_QNN_SDK_ROOT",
            "-PmcaQnnRuntimeRoot=$env:MCA_QNN_SDK_ROOT",
            '-PmcaQnnTypedBindingsRequired=true',
            '--no-daemon', '--no-parallel', '--console=plain',
            '-Dorg.gradle.jvmargs=-Xmx5120m -Dfile.encoding=UTF-8'
        )
        $record.build.gradleArgs = $args
        & (Join-Path $repo 'gradlew.bat') @args 2> $errPath 1> $logPath
        $rc = $LASTEXITCODE
        $record.build.gradleExit = $rc
        if ($rc -ne 0) { throw "Gradle release build failed (exit $rc). See $logPath / $errPath" }
    } else {
        if (-not (Test-Path -LiteralPath $apkPath)) { throw "Release APK does not exist: $apkPath" }
    }

    if (-not (Test-Path -LiteralPath $apkPath)) { throw "Release APK was not produced: $apkPath" }
    if ($Optimize) {
        $optimization = Optimize-And-SignReleaseApk $apkPath $outDir $runId
        Copy-Item -LiteralPath $optimization.signedApk -Destination $apkPath -Force
        $record.optimization = $optimization
    }
    $artifactBase = "MCA-v$versionName-arm64-v8a"
    $artifactApk = Join-Path $outDir "$artifactBase.apk"
    $artifactChecksum = "$artifactApk.sha256"
    Copy-Item -LiteralPath $apkPath -Destination $artifactApk -Force
    $apkSha = Sha $artifactApk
    $apkBytes = (Get-Item -LiteralPath $artifactApk).Length
    Write-Lf $artifactChecksum ("$apkSha  $([IO.Path]::GetFileName($artifactApk))`n")
    $record.artifacts.apk = [ordered]@{ path = $artifactApk.Substring($repo.Length).TrimStart('\','/').Replace('\','/'); sha256 = $apkSha; bytes = $apkBytes }
    $record.artifacts.checksum = [ordered]@{ path = $artifactChecksum.Substring($repo.Length).TrimStart('\','/').Replace('\','/'); sha256 = Sha $artifactChecksum }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($artifactApk)
    try {
        $entries = @($zip.Entries | ForEach-Object FullName)
        $x64 = @($entries | Where-Object { $_ -like 'lib/x86_64/*' })
        $record.verification.arm64Only = $x64.Count -eq 0
        if ($x64.Count -ne 0) { throw "Release APK unexpectedly contains x86_64 entries: $($x64.Count)" }
        $readelf = Get-ChildItem -LiteralPath (Join-Path $env:ANDROID_HOME 'ndk') -Recurse -Filter llvm-readelf.exe -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
        $natives = Get-McaApkNativeManifest $zip $readelf
        $record.artifacts.nativeManifest = @($natives)
    } finally { $zip.Dispose() }

    $apksigner = Get-ChildItem -LiteralPath (Join-Path $env:ANDROID_HOME 'build-tools') -Recurse -Filter apksigner.bat -ErrorAction SilentlyContinue | Sort-Object FullName | Select-Object -Last 1 -ExpandProperty FullName
    if (-not $apksigner) { throw 'apksigner.bat not found under ANDROID_HOME.' }
    $signText = (& $apksigner verify --verbose --print-certs $artifactApk 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $signText" }
    $cert = [regex]::Match($signText, 'certificate SHA-256 digest:\s*([0-9a-fA-F]+)').Groups[1].Value.ToLowerInvariant()
    $schemes = @([regex]::Matches($signText, 'Verified using v(\d+(?:\.\d+)?) scheme[^:]*:\s*(true|false)', 'IgnoreCase') | ForEach-Object { [ordered]@{ scheme = $_.Groups[1].Value; verified = $_.Groups[2].Value -eq 'true' } })
    $record.verification.signature = [ordered]@{ verified = $true; certificateSha256 = $cert; schemes = $schemes }
    if ($cert -ne '2619ac4ce0ad8397b84c77df6ba165801fd4fab1460470f22f1eb7b3e4f9a9cf') { throw "Unexpected release certificate: $cert" }

    $mapping = Join-Path $repo 'app\build\outputs\mapping\release\mapping.txt'
    if (Test-Path -LiteralPath $mapping) {
        $copy = Join-Path $outDir "$artifactBase.mapping.txt"
        Copy-Item -LiteralPath $mapping -Destination $copy -Force
        $record.artifacts.mapping = [ordered]@{ path = $copy.Substring($repo.Length).TrimStart('\','/').Replace('\','/'); sha256 = Sha $copy; bytes = (Get-Item $copy).Length }
    } else { $record.verification.mappingPresent = $false }
    $symbols = Join-Path $repo 'app\build\outputs\native-debug-symbols\release\native-debug-symbols.zip'
    if (Test-Path -LiteralPath $symbols) {
        $copy = Join-Path $outDir "$artifactBase.native-debug-symbols.zip"
        Copy-Item -LiteralPath $symbols -Destination $copy -Force
        $record.artifacts.nativeDebugSymbols = [ordered]@{ path = $copy.Substring($repo.Length).TrimStart('\','/').Replace('\','/'); sha256 = Sha $copy; bytes = (Get-Item $copy).Length }
    } elseif ($record.optimization -and (Test-Path -LiteralPath $record.optimization.symbolsZip)) {
        $copy = Join-Path $outDir "$artifactBase.native-debug-symbols.zip"
        Copy-Item -LiteralPath $record.optimization.symbolsZip -Destination $copy -Force
        $record.artifacts.nativeDebugSymbols = [ordered]@{ path = $copy.Substring($repo.Length).TrimStart('\','/').Replace('\','/'); sha256 = Sha $copy; bytes = (Get-Item $copy).Length }
        $record.verification.nativeDebugSymbolsPresent = $true
    } else { $record.verification.nativeDebugSymbolsPresent = $false }

    $env:PYTHONDONTWRITEBYTECODE = '1'
    $pageTool = Join-Path $repo 'tools\release\verify_16kb_pagesize.py'
    & python -B $pageTool --apk $artifactApk *> (Join-Path $outDir 'page-size-audit.log')
    $pageExit = $LASTEXITCODE
    $pageReport = Join-Path $repo 'tools\release\16kb_page_size_report.json'
    $record.artifacts.pageSizeReport = [ordered]@{ path = $pageReport.Substring($repo.Length).TrimStart('\','/').Replace('\','/'); sha256 = Sha $pageReport; bytes = (Get-Item $pageReport).Length; exit = $pageExit }
    if ($pageExit -ne 0) { throw '16 KB page-size audit failed.' }

    $sbomTool = Join-Path $repo 'tools\sbom\generate_sbom.py'
    if (Test-Path -LiteralPath $sbomTool) {
        & python -B $sbomTool --apk $artifactApk *> (Join-Path $outDir 'sbom-generate.log')
        $sbomExit = $LASTEXITCODE
        $sbomPath = Join-Path $repo 'tools\sbom\mca-sbom.spdx.json'
        if (Test-Path -LiteralPath $sbomPath) {
            $record.artifacts.sbom = [ordered]@{ path = $sbomPath.Substring($repo.Length).TrimStart('\','/').Replace('\','/'); sha256 = Sha $sbomPath; bytes = (Get-Item $sbomPath).Length; exit = $sbomExit }
        }
        if ($sbomExit -ne 0) { throw 'SBOM generation failed.' }
    }

    if ($ReproCheck) {
        $first = Sha $artifactApk
        $firstCopy = Join-Path $outDir "$artifactBase.repro-build1.apk"
        Copy-Item -LiteralPath $artifactApk -Destination $firstCopy -Force
        Clear-OutputDir (Join-Path $repo 'app\build\outputs\apk\release')
        Clear-OutputDir (Join-Path $repo 'app\build\intermediates\packaged_manifests\release')
        $args2 = @($record.build.gradleArgs) + @('--rerun-tasks')
        & (Join-Path $repo 'gradlew.bat') @args2 2> $errPath 1> $logPath
        if ($LASTEXITCODE -ne 0) { throw 'Reproducibility rebuild failed.' }
        $second = Sha $apkPath
        $record.verification.reproducible = [ordered]@{ build1Sha256 = $first; build2Sha256 = $second; equal = ($first -eq $second) }
        if ($first -ne $second) { throw "Release rebuild is not byte-reproducible: $first != $second" }
    }

    $record.status = 'passed'
    $record.completedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    $record.finalFingerprint = (Get-McaWorktreeFingerprint -RepoRoot $repo).fingerprint
    Write-Lf $recordPath (Json-Lf $record)
    Copy-Item -LiteralPath $recordPath -Destination (Join-Path $outDir "$artifactBase.release-record.json") -Force
    Write-Host "RELEASE PASS: $artifactApk"
    Write-Host "SHA256=$apkSha BYTES=$apkBytes"
    Write-Host "RECORD=$recordPath"
} catch {
    $record.status = 'failed'
    $record.failure = $_.Exception.Message
    $record.completedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    Write-Lf $recordPath (Json-Lf $record)
    Write-Error $_
    exit 1
} finally {
    if ($lock) { Exit-McaBuildLock -Lock $lock | Out-Null }
}