param(
    [string] $OutRoot = 'artifacts/ui-quality/v9-closeout/debug-candidate'
)
<#
Builds ONE debug candidate under the shared build lock and records it honestly.

Why this exists instead of just calling scripts/build.ps1: build.ps1 refuses immediately when the
lock is held (correct, but this session shares the machine with another workstream that is running
app tests). This script waits for the lock, builds the same `:app:assembleDebug` task, and writes its
own candidate record with the source fingerprint BEFORE and AFTER the build. If the two differ, the
record says so instead of pretending the APK came from a stable tree.

It does not sign, does not touch applicationId, does not clear data, and does not claim to satisfy the
Release/performance gates.
#>
$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $repo 'scripts\BuildProvenance.ps1')
Import-Module (Join-Path $repo 'tools\build-guards\McaBuildLock.psm1') -Force

$runId = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss-fffZ')
$outDir = Join-Path $repo (Join-Path $OutRoot $runId)
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$recordPath = Join-Path $outDir 'debug-candidate.json'

$cpBefore = Get-McaSourceProvenance -ProjectRoot $repo
Write-Output ('provenance before: worktree=' + $cpBefore.worktreeSha256)

$lock = $null
$exit = -1
$logPath = Join-Path $outDir 'assembleDebug.log'
try {
    $lock = Enter-McaBuildLock -Kind build -RetrySeconds 2400 -RetryIntervalSeconds 5 `
        -CommandLine 'verify-frontend-behavior candidate :app:assembleDebug'
    Write-Output 'build lock acquired'
    $env:JAVA_HOME = 'E:\model\toolchains\jdk-21\jdk-21.0.12.1+1'
    $env:MCA_QNN_SDK_ROOT = 'E:\tools\qairt-sdk\2.45.0.260326'
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & (Join-Path $repo 'gradlew.bat') ':app:assembleDebug' '--console=plain' '--max-workers=2' *> $logPath
        $exit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
} catch {
    $_ | Out-String | Add-Content -LiteralPath $logPath
    $exit = 900
} finally {
    if ($lock) { Exit-McaBuildLock -Lock $lock | Out-Null }
}
Write-Output ('assembleDebug exit=' + $exit)

$cpAfter = Get-McaSourceProvenance -ProjectRoot $repo
$comparison = Compare-McaSourceProvenance -Before $cpBefore -After $cpAfter

$apk = Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk'
$apkInfo = $null
if (Test-Path -LiteralPath $apk) {
    $apkInfo = [ordered]@{
        path = $apk.Substring($repo.Length).TrimStart('\', '/').Replace('\', '/')
        sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
        bytes = (Get-Item -LiteralPath $apk).Length
        lastWriteUtc = (Get-Item -LiteralPath $apk).LastWriteTimeUtc.ToString('o')
    }
}

$record = [ordered]@{
    schema = 'mca.debug-candidate/1'
    runId = $runId
    generatedUtc = (Get-Date).ToUniversalTime().ToString('o')
    purpose = 'Behaviour observation candidate for the V9 frontend slices. Not a Release/performance gate.'
    build = [ordered]@{
        task = ':app:assembleDebug'
        exitCode = $exit
        log = $logPath.Substring($repo.Length).TrimStart('\', '/').Replace('\', '/')
        javaHome = $env:JAVA_HOME
    }
    apk = $apkInfo
    source = [ordered]@{
        head = $cpBefore.head
        before = [ordered]@{
            worktreeSha256 = $cpBefore.worktreeSha256
            trackedDiffSha256 = $cpBefore.trackedDiffSha256
            manifestSha256 = $cpBefore.manifestSha256
        }
        after = [ordered]@{
            worktreeSha256 = $cpAfter.worktreeSha256
            trackedDiffSha256 = $cpAfter.trackedDiffSha256
            manifestSha256 = $cpAfter.manifestSha256
        }
        stableDuringBuild = $comparison.equal
        changeReasons = @($comparison.reasons)
        changedPaths = @($comparison.changedPaths)
        bindingStatus = if ($comparison.equal) { 'bound' } else { 'unverified-source-changed-during-build' }
    }
    limits = @(
        'Debug build: usable for behaviour and correctness observation, not for quality scoring or performance acceptance.',
        'This device installed a fresh package; it is not an upgrade test over existing user data.',
        'The APK is not signed for release and carries no Baseline Profile claim.'
    )
}
# UTF-8 without BOM: a BOM breaks strict JSON readers (jq, python -m json.tool).
[IO.File]::WriteAllText($recordPath, ($record | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
Write-Output ('candidate source stable during build: ' + $comparison.equal)
Write-Output ('record: ' + $recordPath)
if ($exit -ne 0) { exit 1 }
if (-not $apkInfo) { Write-Output 'APK missing'; exit 1 }
exit 0
