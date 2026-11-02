# One-shot verification for the DSH completion-v2 delivery.
#
# The whole DSH surface is verified ONCE, in one serialised Gradle invocation under the project's
# exclusive build lock, plus a fresh APK and an optional device install/smoke. It is not a
# substitute for device/account/model acceptance: the report records those as NOT_RUN unless the
# corresponding flags and conditions are present.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\dsh-verify-once.ps1
#   powershell ... -File tools\dsh-verify-once.ps1 -SkipAssemble
#   powershell ... -File tools\dsh-verify-once.ps1 -Device f9d98aa3
#
# Env: MCA_QNN_SDK_ROOT must point at the complete QAIRT/QNN SDK (product build contract).
[CmdletBinding()]
param(
    [switch]$SkipAssemble,
    [string]$Device = '',
    [string]$ReportDir = 'artifacts/dsh-v2/verification',
    [int]$TimeoutMinutes = 180
)
$ErrorActionPreference = 'Stop'
Set-Location 'E:\MAC'

$tasks = @(
    ':core:dsh-contract:testDebugUnitTest',
    ':core:dsh-runtime:testDebugUnitTest',
    ':core:dsh-platform:testDebugUnitTest',
    ':core:dsh-android-actions:testDebugUnitTest',
    ':core:dsh-a11y:testDebugUnitTest',
    ':core:dsh-vscreen:testDebugUnitTest',
    ':core:dsh-browser:testDebugUnitTest',
    ':core:dsh-maintenance:testDebugUnitTest',
    ':api:dsh-gateway:testDebugUnitTest',
    ':feature:dsh-workspace:testDebugUnitTest',
    ':app:testDebugUnitTest'
)
if (-not $SkipAssemble) { $tasks += ':app:assembleDebug' }

if (-not $env:MCA_QNN_SDK_ROOT) {
    foreach ($candidate in @('E:\tools\qairt-sdk\2.45.0.260326', 'D:\model\qairt-sdk\qairt\2.45.0.260326')) {
        if (Test-Path (Join-Path $candidate 'include/QNN/QnnInterface.h')) {
            $env:MCA_QNN_SDK_ROOT = $candidate
            break
        }
    }
}
if (-not $env:MCA_QNN_SDK_ROOT) {
    [Console]::Error.WriteLine('dsh-verify-once: no QAIRT/QNN SDK found; set MCA_QNN_SDK_ROOT')
    exit 2
}

if (-not (Test-Path -LiteralPath $ReportDir)) { New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null }
$logPath = Join-Path $ReportDir 'one-shot-gradle.log'
$started = Get-Date

Import-Module 'E:\MAC\tools\build-guards\McaBuildLock.psm1' -Force
$lock = $null
$exitCode = -1
try {
    $lock = Enter-McaBuildLock -Kind test -RetrySeconds 3600 -RetryIntervalSeconds 20 -CommandLine 'dsh verify-once'
    & .\gradlew.bat @tasks --console=plain --no-daemon --max-workers=2 2>&1 |
        Tee-Object -FilePath $logPath
    $exitCode = $LASTEXITCODE
}
finally {
    if ($lock) { Exit-McaBuildLock -Lock $lock | Out-Null }
}
$finished = Get-Date

# ---------------------------------------------------------------- test XML inventory
function Get-TestModuleReport([string]$modulePath, [string]$task) {
    $results = Join-Path $modulePath "build/test-results/$task"
    if (-not (Test-Path $results)) { return $null }
    $tests = 0; $failures = 0; $errors = 0; $skipped = 0; $suites = 0; $newest = [datetime]::MinValue
    Get-ChildItem $results -Filter '*.xml' -File | ForEach-Object {
        $suites += 1
        if ($_.LastWriteTime -gt $newest) { $newest = $_.LastWriteTime }
        try {
            [xml]$xml = Get-Content -LiteralPath $_.FullName -Raw
            $tests += [int]$xml.testsuite.tests
            $failures += [int]$xml.testsuite.failures
            $errors += [int]$xml.testsuite.errors
            $skipped += [int]$xml.testsuite.skipped
        } catch { }
    }
    return [pscustomobject]@{
        Module = $modulePath; Task = $task; Suites = $suites; Tests = $tests
        Failures = $failures; Errors = $errors; Skipped = $skipped; Newest = $newest
    }
}

$modules = @(
    @{ Path = 'core/dsh-contract'; Task = 'testDebugUnitTest' },
    @{ Path = 'core/dsh-runtime'; Task = 'testDebugUnitTest' },
    @{ Path = 'core/dsh-platform'; Task = 'testDebugUnitTest' },
    @{ Path = 'core/dsh-android-actions'; Task = 'testDebugUnitTest' },
    @{ Path = 'core/dsh-a11y'; Task = 'testDebugUnitTest' },
    @{ Path = 'core/dsh-vscreen'; Task = 'testDebugUnitTest' },
    @{ Path = 'core/dsh-browser'; Task = 'testDebugUnitTest' },
    @{ Path = 'core/dsh-maintenance'; Task = 'testDebugUnitTest' },
    @{ Path = 'api/dsh-gateway'; Task = 'testDebugUnitTest' },
    @{ Path = 'feature/dsh-workspace'; Task = 'testDebugUnitTest' },
    @{ Path = 'app'; Task = 'testDebugUnitTest' }
)
$rows = @()
foreach ($m in $modules) {
    $r = Get-TestModuleReport -modulePath $m.Path -task $m.Task
    if ($null -ne $r) { $rows += $r }
}

# ---------------------------------------------------------------- APK identity
$apkInfo = $null
$apk = Get-ChildItem 'app/build/outputs/apk/debug' -Filter '*.apk' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($apk) {
    $apkInfo = [pscustomobject]@{
        Path = $apk.FullName
        Bytes = $apk.Length
        Sha256 = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash
        LastWriteTime = $apk.LastWriteTime
    }
}

# ---------------------------------------------------------------- device smoke (optional)
$deviceLines = @()
if ($Device) {
    $adb = 'D:\model\android-sdk\platform-tools\adb.exe'
    if (Test-Path $adb) {
        # Windows PowerShell 5.1 turns native stderr into error records; a failed/blocked device
        # install must be recorded, not abort the report of an otherwise green build.
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $deviceLines += "adb devices: " + ((& $adb -s $Device get-state 2>&1) -join ' ')
            if ($apkInfo) {
                $installText = (& $adb -s $Device install -r $apkInfo.Path 2>&1) -join ' '
                $deviceLines += "install: " + $installText
                if ($installText -match 'Success') {
                    $deviceLines += "launch: " + ((& $adb -s $Device shell am start -n com.muyuchat.mca/.MainActivity 2>&1) -join ' ')
                    Start-Sleep -Seconds 20
                    $deviceLines += "pid: " + ((& $adb -s $Device shell pidof com.muyuchat.mca 2>&1) -join ' ')
                    $deviceLines += "fatal-scan: " + ((& $adb -s $Device logcat -d -t 800 2>&1 |
                        Select-String -Pattern 'FATAL EXCEPTION|ANR in com.muyuchat.mca' |
                        ForEach-Object { $_.Line }) -join ' | ')
                }
                else {
                    $deviceLines += 'device smoke: BLOCKED (install refused by the device; no runtime claim)'
                }
            }
        }
        catch {
            $deviceLines += "device smoke: BLOCKED (" + $_.Exception.Message + ")"
        }
        finally {
            $ErrorActionPreference = $previousPreference
        }
    }
    else {
        $deviceLines += 'device: NOT_RUN (adb not found)'
    }
}
else {
    $deviceLines += 'device: NOT_RUN (no -Device argument)'
}

# ---------------------------------------------------------------- report
$gitHead = (& git rev-parse HEAD 2>&1) -join ''
$gitStatus = (& git status --porcelain 2>&1 | Measure-Object -Line).Lines
$lines = @()
$lines += '# DSH completion-v2 one-shot verification'
$lines += ''
$lines += "- Started: $($started.ToString('s'))  Finished: $($finished.ToString('s'))"
$lines += "- Gradle exit code: $exitCode"
$lines += "- Log: ``$logPath``"
$lines += "- HEAD: ``$gitHead``  working-tree changes: $gitStatus paths (workspace is the build input, not HEAD alone)"
$lines += "- QAIRT/QNN SDK: ``$env:MCA_QNN_SDK_ROOT``"
$lines += "- Task list: $($tasks -join ' ')"
$lines += ''
$lines += '## Unit test inventory (fresh XML from this run)'
$lines += ''
$lines += '| Module | Suites | Tests | Failures | Errors | Skipped | Newest XML |'
$lines += '| --- | ---: | ---: | ---: | ---: | ---: | --- |'
foreach ($r in $rows) {
    $lines += "| $($r.Module) | $($r.Suites) | $($r.Tests) | $($r.Failures) | $($r.Errors) | $($r.Skipped) | $($r.Newest.ToString('HH:mm:ss')) |"
}
$totalTests = ($rows | Measure-Object -Property Tests -Sum).Sum
$totalFailures = ($rows | Measure-Object -Property Failures -Sum).Sum
$totalErrors = ($rows | Measure-Object -Property Errors -Sum).Sum
$lines += ''
$lines += "Totals: tests=$totalTests failures=$totalFailures errors=$totalErrors"
$lines += ''
$lines += '## APK identity'
$lines += ''
if ($apkInfo) {
    $lines += "- Path: ``$($apkInfo.Path)``"
    $lines += "- Bytes: $($apkInfo.Bytes)"
    $lines += "- SHA-256: ``$($apkInfo.Sha256)``"
    $lines += "- Built: $($apkInfo.LastWriteTime.ToString('s'))"
}
else {
    $lines += '- APK: NOT_RUN (assemble skipped or no output)'
}
$lines += ''
$lines += '## Device smoke'
$lines += ''
foreach ($line in $deviceLines) { $lines += "- $line" }
$lines += ''
$lines += '## Honest boundary'
$lines += ''
$lines += 'This report proves the JVM test suites and the APK build on this workspace. Device/account/'
$lines += 'model/plugin acceptance rows stay NOT_RUN unless a real run is recorded separately; the D26/D27'
$lines += 'evidence files are the authoritative place for those statuses.'
$reportPath = Join-Path $ReportDir 'ONE-SHOT-REPORT.md'
($lines -join "`n") | Set-Content -LiteralPath $reportPath -Encoding utf8
Write-Host "report: $reportPath"
Write-Host "tests=$totalTests failures=$totalFailures errors=$totalErrors exit=$exitCode"
if ($exitCode -ne 0 -or $totalFailures -ne 0 -or $totalErrors -ne 0) { exit 1 }
exit 0
